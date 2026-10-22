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
    private val audioWorker = Executors.newSingleThreadExecutor()
    private val audioSender = Executors.newSingleThreadExecutor()
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
    fun controlLocal(command: String, value: String) {
        if (command == "mic") {
            micEnabled = value == "on"
            captureStatus = if (micEnabled) "Microphone activé" else "Microphone coupé"
        } else relay?.control(command,value)
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
                    onStatus = { message ->
                        captureStatus = message
                        android.util.Log.i("ShizziCam",message)
                    })
                relay = camera
                main.post { preview?.let { camera.attach(it) } }
                camera.start(quality)
                audioWorker.execute { audioLoop() }
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

    private fun uploadAudio(bytes: ByteArray) {
        if (!running || !audioBusy.compareAndSet(false,true)) return
        audioSender.execute {
            try {
                val c = connection("/api/v1/cam/audio")
                try {
                    c.requestMethod = "POST"
                    c.doOutput = true
                    c.setRequestProperty("Content-Type","application/octet-stream")
                    c.setFixedLengthStreamingMode(bytes.size)
                    c.outputStream.use { it.write(bytes) }
                    if (c.responseCode != 200) error("Relais audio HTTP " + c.responseCode)
                } finally { c.disconnect() }
            } catch (e: Exception) {
                captureStatus="Audio vers routeur : "+(e.message ?: e.javaClass.simpleName)
                android.util.Log.w("ShizziCam","Audio relay failed",e)
            } finally { audioBusy.set(false) }
        }
    }

    private fun audioLoop() {
        val rate = 16000
        val minimum=AudioRecord.getMinBufferSize(rate,
            AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) {
            captureStatus="Microphone PCM16 indisponible ($minimum)"
            android.util.Log.e("ShizziCam",captureStatus)
            return
        }
        val size = maxOf(6400,minimum)
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.MIC,rate,AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,size*2)
        } catch (e: Exception) {
            android.util.Log.e("ShizziCam","Microphone unavailable",e)
            return
        }
        if (record.state!=AudioRecord.STATE_INITIALIZED) {
            captureStatus="Microphone non initialisé"
            record.release()
            return
        }
        var active = false
        try {
            val data = ByteArray(6400) // 200ms mono PCM16
            while (running) {
                if (!micEnabled) {
                    if (active) { record.stop(); active = false }
                    Thread.sleep(150)
                    continue
                }
                if (!active) {
                    record.startRecording()
                    if(record.recordingState!=AudioRecord.RECORDSTATE_RECORDING) error("Microphone non actif")
                    active = true
                }
                val length = record.read(data,0,data.size)
                if (length > 0 && length % 2 == 0) uploadAudio(data.copyOf(length))
                else if (length < 0) Thread.sleep(100)
            }
        } catch (e: Exception) {
            captureStatus="Échec microphone : "+(e.message ?: e.javaClass.simpleName)
            android.util.Log.w("ShizziCam","Audio capture ended",e)
        } finally {
            if (active) try { record.stop() } catch (_: Exception) {}
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
                        val command = event.optString("command")
                        val value = event.optString("value")
                        val eventId=event.optLong("id")
                        val activeSession=session
                        val callback: (Boolean,String)->Unit={ok,message ->
                            if(running && eventId>0) worker.execute {
                                try {
                                    json("POST","/api/v1/cam/events?side=camera",
                                        JSONObject().put("session",activeSession)
                                            .put("type","status").put("refId",eventId)
                                            .put("command",command).put("value",value)
                                            .put("ok",ok).put("error",if(ok) "" else message.take(200)))
                                } catch(e:Exception) {
                                    android.util.Log.w("ShizziCam","Camera command acknowledgement failed",e)
                                }
                            }
                        }
                        if(command=="mic") {
                            val allowed=value=="on" || value=="off"
                            if(allowed)micEnabled=value=="on"
                            callback(allowed,if(allowed)"Micro modifié" else "État micro invalide")
                        } else {
                            val camera=relay
                            if(camera==null) callback(false,"Capture caméra inactive")
                            else camera.controlWithResult(command,value) {ok,message ->
                                if(ok && command=="quality") quality=value
                                callback(ok,message)
                            }
                        }
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
    override fun onDestroy() {
        running = false
        captureStatus = "Caméra et microphone arrêtés"
        worker.shutdownNow()
        frames.shutdownNow()
        audioSender.shutdownNow()
        audioWorker.shutdownNow()
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
