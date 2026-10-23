package dev.shizzi.conso

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioManager
import android.media.AudioTrack
import dev.shizzi.camrtc.CamOpusEncoder
import dev.shizzi.camrtc.CamOpusDecoder
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.ImageView
import org.json.JSONObject
import java.io.DataOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Camera JPEG video and PCM16 audio are always uploaded to the router over pinned HTTPS.
 * No direct media connection to any viewer is created.
 */
class CameraCaptureService : Service() {
    @Volatile private var running = false
    @Volatile private var relay: RelayCamera? = null
    @Volatile private var micEnabled = true
    private var pin = ""
    private var quality = "auto"
    private var network: Network? = null
    private lateinit var worker: ScheduledExecutorService
    private val frames = Executors.newSingleThreadExecutor()
    private val videoSender = Executors.newSingleThreadExecutor()
    private data class VideoPacket(val data:ByteArray,val width:Int,val height:Int,val flags:Int,val ptsUs:Long)
    private val videoQueue=ArrayBlockingQueue<VideoPacket>(40)
    @Volatile private var lastConfig:VideoPacket?=null
    private val audioWorker = Executors.newSingleThreadExecutor()
    private val speakerWorker = Executors.newSingleThreadExecutor()
    private val audioSender = Executors.newSingleThreadExecutor()
    private val controlSender = Executors.newSingleThreadExecutor()
    private val audioQueue = ArrayBlockingQueue<ByteArray>(16)
    @Volatile private var sentAudioPackets = 0L
    @Volatile private var capturedAudioPackets = 0L
    @Volatile var audioStatus = "Microphone en attente"
        private set
    private val frameBusy = AtomicBoolean(false)
    private val audioBusy = AtomicBoolean(false)
    private var cursor = 0L
    private var count = 0
    private var failures = 0
    private var transmitted = 0L
    private var session = ""
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var preview: ImageView? = null
    @Volatile var captureStatus = "Caméra inactive"
        private set

    inner class LocalBinder : Binder() { fun service(): CameraCaptureService = this@CameraCaptureService }
    private val binder = LocalBinder()
    fun attachPreview(image: ImageView) { preview = image; relay?.attach(image) }
    fun detachPreview(image: ImageView) {
        relay?.detach(image)
        if (preview === image) preview = null
    }
    fun controlLocal(command: String, value: String, done: (Boolean,String) -> Unit = { _,_ -> }) {
        if (command=="mic") {
            if (value!="on" && value!="off") {done(false,"Valeur microphone incorrecte");return}
            micEnabled = value=="on"
            val message=if (micEnabled) "Microphone activé" else "Microphone coupé"
            audioStatus=message
            done(true,message)
        } else {
            val device = relay
            if (device==null) done(false,"Caméra non initialisée")
            else device.control(command,value,done)
        }
    }

    override fun onCreate() {
        super.onCreate()
        worker = Executors.newSingleThreadScheduledExecutor()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("shizzicam_av", "ShizziCam caméra et son", NotificationManager.IMPORTANCE_LOW)
        )
    }
    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY
        pin = intent?.getStringExtra(EXTRA_PIN).orEmpty()
        quality = intent?.getStringExtra(EXTRA_QUALITY).orEmpty()
            .takeIf { it in listOf("auto","360p","480p","720p") } ?: "auto"
        if (!pin.matches(Regex("[a-f0-9]{64}")) ||
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            captureStatus = "Autorisation caméra / micro manquante"
            stopSelf(); return START_NOT_STICKY
        }
        val stop = PendingIntent.getService(this,100,
            Intent(this,CameraCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val note = Notification.Builder(this,"shizzicam_av")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Shizzi+Cam : capture locale")
            .setContentText("Vidéo / audio envoyés au routeur ShizziCam · Arrêter")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null,"Arrêter",stop).build()).build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(8421,note,ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else startForeground(8421,note)
        } catch (e: Exception) {
            captureStatus = "Service caméra refusé : " + e.javaClass.simpleName
            stopSelf(); return START_NOT_STICKY
        }
        running = true
        captureStatus = "Ouverture caméra, attente première image réelle…"
        worker.execute {
            try {
                network = getSystemService(ConnectivityManager::class.java).allNetworks.firstOrNull {
                    getSystemService(ConnectivityManager::class.java)
                        .getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                } ?: error("Connexion Wi-Fi ShizziCam manquante")
                val camera = RelayCamera(this, onFrame = { bytes -> uploadFrame(bytes) },
                    onH264 = { bytes,w,h,flags,pts -> queueVideo(bytes,w,h,flags,pts) },
                    onStatus = { message ->
                        captureStatus = message
                        android.util.Log.i("ShizziCam",message)
                    })
                relay = camera
                main.post { preview?.let { camera.attach(it) } }
                camera.start(quality)
                videoSender.execute {sendVideoLoop()}
                audioSender.execute { sendAudioLoop() }
                audioWorker.execute { audioLoop() }
                speakerWorker.execute { speakerLoop() }
                worker.scheduleWithFixedDelay({ tick() }, 0, 650, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                captureStatus = "Échec capture : " + (e.message ?: e.javaClass.simpleName)
                android.util.Log.e("ShizziCam","Camera service start failed",e)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun connection(path: String): javax.net.ssl.HttpsURLConnection =
        RouterTls.connection("https://192.0.2.1" + path, pin, network ?: error("Wi-Fi indisponible"))
            .apply { connectTimeout = 3000; readTimeout = 3000; instanceFollowRedirects = false }

    private fun queueVideo(bytes:ByteArray,width:Int,height:Int,flags:Int,ptsUs:Long){
        if(!running||bytes.isEmpty()||bytes.size>262144)return
        val packet=VideoPacket(bytes,width,height,flags,ptsUs)
        if(flags and 2!=0)lastConfig=packet
        if(!videoQueue.offer(packet)){
            videoQueue.poll()
            videoQueue.offer(packet)
        }
    }

    /** Single long-lived HTTPS upload instead of 22 individual TLS sessions per second. */
    private fun sendVideoLoop(){
        while(running&&!Thread.currentThread().isInterrupted){
            var c:javax.net.ssl.HttpsURLConnection?=null
            try{
                val uplink=connection("/api/v1/cam/video-stream")
                c=uplink
                uplink.requestMethod="POST"
                uplink.doOutput=true
                uplink.connectTimeout=3500
                uplink.readTimeout=0
                uplink.setRequestProperty("Content-Type","application/x-shizzicam-h264")
                uplink.setChunkedStreamingMode(16*1024)
                val data=DataOutputStream(uplink.outputStream)
                lastConfig?.let{packet->
                    data.writeInt(packet.data.size)
                    data.writeShort(packet.width)
                    data.writeShort(packet.height)
                    data.writeLong(packet.ptsUs)
                    data.writeByte(packet.flags)
                    data.write(packet.data)
                    data.flush()
                }
                while(running){
                    val packet=videoQueue.poll(600,TimeUnit.MILLISECONDS)?:continue
                    data.writeInt(packet.data.size)
                    data.writeShort(packet.width)
                    data.writeShort(packet.height)
                    data.writeLong(packet.ptsUs)
                    data.writeByte(packet.flags)
                    data.write(packet.data)
                    data.flush()
                }
            }catch(_:InterruptedException){break}
            catch(e:Exception){
                android.util.Log.w("ShizziCam","H264 continuous uplink interrupted",e)
                if(running)captureStatus="Relais H264 : reconnexion ("+(e.message?:"réseau")+")"
                try{Thread.sleep(600)}catch(_:InterruptedException){break}
            }finally{runCatching{c?.disconnect()}}
        }
    }

    private fun uploadFrame(bytes: ByteArray) {
        if (!running || !frameBusy.compareAndSet(false,true)) return
        frames.execute {
            try {
                val c = connection("/api/v1/cam/frame")
                try {
                    c.requestMethod = "POST"
                    c.doOutput = true
                    c.setRequestProperty("Content-Type","image/jpeg")
                    c.setRequestProperty("X-Shizzi-Cam-Battery",battery().toString())
                    c.setFixedLengthStreamingMode(bytes.size)
                    c.outputStream.use { it.write(bytes) }
                    if (c.responseCode != 200) error("Relais image HTTP " + c.responseCode)
                    transmitted++
                    if (transmitted == 1L) captureStatus = "Première image transmise au routeur"
                } finally { c.disconnect() }
            } catch (e: Exception) {
                android.util.Log.w("ShizziCam","Frame relay failed",e)
                if (transmitted == 0L) captureStatus = "Image locale présente, relais inaccessible : " +
                    (e.message ?: "erreur")
            } finally { frameBusy.set(false) }
        }
    }

    /** Isolate microphone reads from Wi-Fi/HTTPS delays. Bounded queue prevents memory growth. */
    private fun uploadAudio(bytes: ByteArray) {
        capturedAudioPackets++
        if (!audioQueue.offer(bytes)) {
            audioQueue.poll()
            audioQueue.offer(bytes)
            audioStatus="Son : réseau lent, quelques paquets perdus"
        }
    }
    private fun sendAudioLoop() {
        while (running && !Thread.currentThread().isInterrupted) {
            try {
                val bytes = audioQueue.poll(300,TimeUnit.MILLISECONDS) ?: continue
                val c=connection("/api/v1/cam/audio")
                try {
                    c.requestMethod="POST"
                    c.doOutput=true
                    c.setRequestProperty("Content-Type","audio/opus")
                    c.setFixedLengthStreamingMode(bytes.size)
                    c.outputStream.use { it.write(bytes) }
                    if (c.responseCode!=200) error("Relais audio HTTP "+c.responseCode)
                    sentAudioPackets++
                    if (sentAudioPackets==1L) audioStatus="Microphone transmis au routeur"
                } finally {c.disconnect()}
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                audioStatus="Audio non transmis : "+(e.message ?: "réseau indisponible")
                android.util.Log.w("ShizziCam","Audio relay failed",e)
            }
        }
    }

    private fun audioLoop() {
        val rate = 48000
        val size = maxOf(3840,AudioRecord.getMinBufferSize(rate,
            AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT))
        if (size <= 0) { audioStatus="Enregistrement audio non pris en charge"; android.util.Log.e("ShizziCam","AudioRecord unsupported"); return }
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.MIC,rate,AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,size*2)
        } catch (e: Exception) {
            android.util.Log.e("ShizziCam","Microphone unavailable",e)
            audioStatus="Microphone indisponible : "+(e.message ?: "erreur")
            return
        }
        if (record.state!=AudioRecord.STATE_INITIALIZED) {
            audioStatus="Échec initialisation du microphone"
            record.release()
            return
        }
        var active = false
        var encoder:CamOpusEncoder?=null
        try {
            encoder = CamOpusEncoder()
            val data = ByteArray(1920) // 20ms mono PCM16 at 48 kHz
            while (running) {
                if (!micEnabled) {
                    if (active) { record.stop(); active = false }
                    Thread.sleep(150)
                    continue
                }
                if (!active) { record.startRecording(); active = true; audioStatus="Microphone actif" }
                val length = record.read(data,0,data.size)
                if (length > 0 && length % 2 == 0) {
                    for (packet in encoder.encode(data.copyOf(length))) uploadAudio(packet)
                }
                else if (length < 0) Thread.sleep(100)
            }
        } catch (e: Exception) {
            audioStatus="Capture audio arrêtée : "+(e.message ?: "erreur")
            android.util.Log.w("ShizziCam","Audio capture ended",e)
        } finally {
            if (active) try { record.stop() } catch (_: Exception) {}
            encoder?.close()
            record.release()
        }
    }

    private fun battery(): Int = getSystemService(BatteryManager::class.java)
        .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private fun json(method: String, path: String, body: JSONObject? = null): JSONObject {
        val c = connection(path)
        try {
            c.requestMethod = method
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type","application/json")
                val data = body.toString().toByteArray(Charsets.UTF_8)
                c.setFixedLengthStreamingMode(data.size)
                c.outputStream.use { it.write(data) }
            }
            if (c.responseCode != 200) error("API caméra : HTTP " + c.responseCode)
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }
    private fun tick() {
        if (!running) return
        try {
            if (count % 3 == 0) {
                json("POST","/api/v1/cam/hello?side=camera",
                    JSONObject().put("battery",battery()).put("audio",micEnabled).put("quality",quality))
            }
            val items = json("GET","/api/v1/cam/events?side=camera&after=" + cursor)
                .optJSONArray("events")
            if (items != null) for (i in 0 until items.length()) {
                val event = items.getJSONObject(i)
                cursor = maxOf(cursor,event.optLong("id"))
                when (event.optString("type")) {
                    "watch" -> session = event.optString("session")
                    "control" -> if (event.optString("session") == session) {
                        val command=event.optString("command")
                        val value=event.optString("value")
                        val requestId=event.optString("requestId")
                        val expectedSession=session
                        controlLocal(command,value) { success,detail ->
                            if (requestId.isNotEmpty()) controlSender.execute {
                                try {
                                    json("POST","/api/v1/cam/events?side=camera",
                                        JSONObject().put("session",expectedSession).put("type","status")
                                            .put("command",command).put("value",value)
                                            .put("requestId",requestId).put("success",success)
                                            .put("detail",detail.take(250)))
                                } catch (e: Exception) {
                                    android.util.Log.w("ShizziCam","Camera command ACK failed",e)
                                }
                            }
                        }
                        if (command=="quality") quality=value
                    }
                }
            }
            count++
            failures = 0
        } catch (e: Exception) {
            failures++
            if (failures == 4) captureStatus = "Routeur inaccessible : " + (e.message ?: "erreur réseau")
            if (failures > 25) stopSelf()
        }
    }
    /** Opus talk-back always comes from the router, never from the viewer handset. */
    private fun speakerLoop() {
        val decoder=try { CamOpusDecoder() }catch(e:Exception){
            audioStatus="Interphone : codec Opus indisponible"
            android.util.Log.e("ShizziCam","Talk-back Opus unavailable",e)
            return
        }
        val rate=48000
        val min=AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT)
        if(min<=0){decoder.close();audioStatus="Haut-parleur interphone indisponible";return}
        val speaker=try {
            AudioTrack(AudioManager.STREAM_MUSIC,rate,AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,maxOf(min,19200),AudioTrack.MODE_STREAM)
        }catch(e:Exception){decoder.close();audioStatus="Haut-parleur interphone indisponible";return}
        var after=0L
        try {
            speaker.play()
            while(running&&!Thread.currentThread().isInterrupted){
                try {
                    val c=connection("/api/v1/cam/talk?after="+after)
                    try {
                        when(c.responseCode){
                            200 -> {
                                val seq=c.getHeaderField("X-Shizzi-Cam-Talk-Seq")?.toLongOrNull()?:0
                                val compressed=c.inputStream.use{it.readBytes()}
                                if(seq>after && compressed.isNotEmpty() && compressed.size<=4096){
                                    after=seq
                                    for(pcm in decoder.decode(compressed)){
                                        val wrote=speaker.write(pcm,0,pcm.size)
                                        if(wrote<0) android.util.Log.w("ShizziCam","Speaker playback $wrote")
                                    }
                                }
                            }
                            204 -> Thread.sleep(45)
                            else -> Thread.sleep(250)
                        }
                    }finally{c.disconnect()}
                }catch(_:InterruptedException){break}
                catch(e:Exception){
                    android.util.Log.w("ShizziCam","Talk-back read failed",e)
                    Thread.sleep(250)
                }
            }
        }catch(_:InterruptedException){}finally{
            try{speaker.pause();speaker.flush();speaker.release()}catch(_:Exception){}
            decoder.close()
        }
    }

    override fun onDestroy() {
        running = false
        captureStatus = "Caméra et microphone arrêtés"
        worker.shutdownNow()
        frames.shutdownNow()
        videoSender.shutdownNow()
        videoQueue.clear()
        audioSender.shutdownNow()
        controlSender.shutdownNow()
        audioQueue.clear()
        audioWorker.shutdownNow()
        speakerWorker.shutdownNow()
        relay?.stop()
        relay = null
        super.onDestroy()
    }
    companion object {
        const val ACTION_STOP = "dev.shizzi.conso.CAM_STOP"
        const val EXTRA_PIN = "routerCertPin"
        const val EXTRA_QUALITY = "quality"
    }
}
