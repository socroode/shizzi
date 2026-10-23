package dev.shizzi.conso

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.net.*
import android.os.*
import android.widget.ImageView
import dev.shizzi.camrtc.*
import org.json.JSONObject
import java.io.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection

/** Foreground camera; every byte of media goes to the authenticated local router. */
class CameraCaptureService:Service(){
    @Volatile private var running=false
    @Volatile private var relay:RelayCamera?=null
    @Volatile private var micEnabled=true
    private var pin=""
    @Volatile private var quality="auto"
    private var network:Network?=null
    private val clock=CamClock()
    private val workers=Executors.newFixedThreadPool(5)
    private val control=Executors.newSingleThreadScheduledExecutor()
    private val connections=ConcurrentHashMap.newKeySet<HttpsURLConnection>()
    private data class Video(val data:ByteArray,val w:Int,val h:Int,val flags:Int,val pts:Long)
    private val videos=ArrayBlockingQueue<Video>(4)
    private val audio=ArrayBlockingQueue<CamAudioPacket>(6)
    @Volatile private var videoConfig:Video?=null
    @Volatile private var audioConfig:CamAudioPacket?=null
    @Volatile private var waitKey=true
    private val videoDrops=AtomicLong()
    private val audioDrops=AtomicLong()
    private val interruptions=AtomicLong()
    private var cursor=0L
    private var session=""
    private var routerEpoch=""
    private var tickCount=0
    private val main=Handler(Looper.getMainLooper())
    private var preview:ImageView?=null
    @Volatile var captureStatus="Caméra inactive";private set
    @Volatile var audioStatus="Microphone en attente";private set
    private var siren:MediaPlayer?=null
    private var lastAlarm=0L
    private var wake:PowerManager.WakeLock?=null
    @Volatile private var playedSequence=0L
    @Volatile private var playbackVerified=false
    @Volatile private var playbackRouterUs=0L
    @Volatile private var alarmState="arrêtée"
    private val binder=LocalBinder()
    inner class LocalBinder:Binder(){fun service()=this@CameraCaptureService}
    override fun onBind(intent:Intent?):IBinder=binder
    fun attachPreview(view:ImageView){preview=view;relay?.attach(view)}
    fun detachPreview(view:ImageView){relay?.detach(view);if(preview===view)preview=null}
    fun controlLocal(command:String,value:String,done:(Boolean,String)->Unit={_,_->}){
        when(command){
            "mic"->{
                if(value !in listOf("on","off")){done(false,"Commande microphone invalide");return}
                micEnabled=value=="on"
                if(!micEnabled){audio.clear();audioStatus="Microphone coupé"}
                done(true,if(micEnabled)"Microphone activé" else "Microphone coupé")
            }
            "alarm"->main.post{
                try{
                    if(value=="stop"){stopAlarm();done(true,"Alarme arrêtée")}
                    else{
                        check(value=="start")
                        check(siren==null){"Alarme déjà en cours"}
                        val now=SystemClock.elapsedRealtime()
                        check(lastAlarm==0L||now-lastAlarm>=3000){"Attends avant un nouveau déclenchement"}
                        val manager=getSystemService(AudioManager::class.java)
                        check(manager.getStreamVolume(AudioManager.STREAM_ALARM)>0){"Volume alarme à zéro sur la caméra"}
                        val player=MediaPlayer().apply{
                            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                            resources.openRawResourceFd(R.raw.shizzicam_siren).use{setDataSource(it.fileDescriptor,it.startOffset,it.length)}
                            prepare()
                            setOnCompletionListener{stopAlarm()}
                            setOnErrorListener{_,_,_->stopAlarm();true}
                        }
                        siren=player;lastAlarm=now;player.start();alarmState="lecture démarrée"
                        main.postDelayed({if(siren===player)stopAlarm()},10000)
                        done(true,"Lecture de la sirène démarrée, 10 secondes maximum")
                    }
                }catch(e:Exception){done(false,e.message?:"Alarme indisponible")}
            }
            else->relay?.control(command,value,done)?:done(false,"Caméra non initialisée")
        }
    }
    private fun stopAlarm(){siren?.let{runCatching{it.stop()};it.release()};siren=null;alarmState="arrêtée"}
    override fun onCreate(){
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("shizzicam_av","ShizziCam caméra et son",NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        if(intent?.action==ACTION_STOP){stopSelf();return START_NOT_STICKY}
        if(running)return START_NOT_STICKY
        pin=intent?.getStringExtra(EXTRA_PIN).orEmpty()
        quality=intent?.getStringExtra(EXTRA_QUALITY).orEmpty().takeIf{it in CamQuality.names}?:"auto"
        if(!pin.matches(Regex("[a-f0-9]{64}"))||
            checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            captureStatus="Autorisation caméra ou micro manquante";stopSelf();return START_NOT_STICKY
        }
        val stop=PendingIntent.getService(this,100,Intent(this,CameraCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val note=Notification.Builder(this,"shizzicam_av").setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Shizzi+Cam : capture locale").setContentText("H.264 et Opus via ShizziCam")
            .setOngoing(true).addAction(Notification.Action.Builder(null,"Arrêter",stop).build()).build()
        try{
            if(Build.VERSION.SDK_INT>=34)startForeground(8421,note,ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(8421,note)
            wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"ShizziCam:Capture")
                .apply{acquire()}
        }catch(e:Exception){captureStatus="Service caméra refusé : ${e.message}";stopSelf();return START_NOT_STICKY}
        running=true
        workers.execute{
            try{
                clock.synchronize{json("GET","/api/v1/cam/clock?side=camera")}
                val camera=RelayCamera(this,onFrame={},nowUs={clock.nowUs()},
                    onH264={b,w,h,f,p->queueVideo(Video(b,w,h,f,p))},onStatus={captureStatus=it})
                relay=camera;main.post{preview?.let{camera.attach(it)}};camera.start(quality)
                workers.execute{sendVideo()}
                workers.execute{sendAudio()}
                workers.execute{captureAudio()}
                workers.execute{receiveTalk()}
                control.scheduleWithFixedDelay({tick()},0,500,TimeUnit.MILLISECONDS)
            }catch(e:Exception){captureStatus="Démarrage impossible : ${e.message}";stopSelf()}
        }
        return START_NOT_STICKY
    }
    private fun connection(path:String):HttpsURLConnection{
        val cm=getSystemService(ConnectivityManager::class.java)
        network=cm.allNetworks.firstOrNull{cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true}
            ?:error("Wi-Fi ShizziCam indisponible")
        return RouterTls.connection("https://192.0.2.1"+path,pin,network!!).apply{
            connectTimeout=2500;readTimeout=3000;instanceFollowRedirects=false
        }
    }
    private fun json(method:String,path:String,body:JSONObject?=null):JSONObject{
        val c=connection(path)
        try{
            c.requestMethod=method
            if(body!=null){
                c.doOutput=true;c.setRequestProperty("Content-Type","application/json")
                val data=body.toString().toByteArray();c.setFixedLengthStreamingMode(data.size)
                c.outputStream.use{it.write(data)}
            }
            check(c.responseCode==200){"API caméra HTTP ${c.responseCode}"}
            return JSONObject(c.inputStream.bufferedReader().use{it.readText()})
        }finally{c.disconnect()}
    }
    private fun queueVideo(p:Video){
        if(!running)return
        if(p.flags and 2!=0){videoConfig=p;videos.clear();waitKey=true}
        if(waitKey&&p.flags and 3==0)return
        if(p.flags and 1!=0)waitKey=false
        if(!videos.offer(p)){
            videos.clear();waitKey=true;videoDrops.incrementAndGet()
            videoConfig?.let{videos.offer(it)};relay?.requestKeyFrame()
            if(p.flags and 1!=0){videos.offer(p);waitKey=false}
        }
    }
    private fun Video.write(out:DataOutputStream){
        out.writeInt(data.size);out.writeShort(w);out.writeShort(h);out.writeLong(pts)
        out.writeByte(flags);out.write(data);out.flush()
    }
    private fun sendVideo(){
        while(running&&!Thread.currentThread().isInterrupted){
            var c:HttpsURLConnection?=null
            try{
                c=connection("/api/v1/cam/video-stream");connections.add(c)
                c.requestMethod="POST";c.doOutput=true;c.readTimeout=0
                c.setRequestProperty("Content-Type","application/x-shizzicam-h264");c.setChunkedStreamingMode(4096)
                val out=DataOutputStream(c.outputStream)
                videos.clear();waitKey=true;videoConfig?.write(out);relay?.requestKeyFrame()
                while(running){
                    val p=videos.poll(500,TimeUnit.MILLISECONDS)?:continue
                    if(p.flags and 2==0&&clock.nowUs()-p.pts>300000){
                        videoDrops.incrementAndGet();videos.clear();waitKey=true;relay?.requestKeyFrame();continue
                    }
                    p.write(out)
                }
            }catch(_:InterruptedException){break}
            catch(e:Exception){interruptions.incrementAndGet();captureStatus="Relais vidéo : reconnexion (${e.message})";pauseRetry()}
            finally{c?.let{connections.remove(it);it.disconnect()}}
        }
    }
    private fun captureAudio(){
        while(running&&!Thread.currentThread().isInterrupted){
            var record:AudioRecord?=null;var encoder:CamOpusEncoder?=null
            try{
                val min=AudioRecord.getMinBufferSize(48000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
                check(min>0){"Microphone 48 kHz indisponible"}
                record=AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,48000,AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,maxOf(min,3840))
                check(record.state==AudioRecord.STATE_INITIALIZED)
                encoder=CamOpusEncoder();record.startRecording()
                var recording=true
                val buffer=ByteArray(1920)
                while(running){
                    if(!micEnabled){
                        if(recording){record.stop();recording=false}
                        Thread.sleep(100);continue
                    }
                    if(!recording){record.startRecording();recording=true}
                    val n=record.read(buffer,0,buffer.size)
                    check(n>0&&n%2==0){"Capture microphone interrompue ($n)"}
                    if(!micEnabled)continue
                    val pts=clock.nowUs()-n*1000000L/96000
                    for(p in encoder.encode(buffer.copyOf(n),pts)){
                        if(p.flags==2)audioConfig=p
                        if(!audio.offer(p)){audio.clear();audioDrops.incrementAndGet();audioConfig?.let{audio.offer(it)};audio.offer(p)}
                    }
                }
            }catch(_:InterruptedException){break}
            catch(e:Exception){audioStatus="Capture audio : ${e.message}";interruptions.incrementAndGet();pauseRetry()}
            finally{runCatching{record?.stop()};record?.release();encoder?.close()}
        }
    }
    private fun sendAudio(){
        while(running&&!Thread.currentThread().isInterrupted){
            var c:HttpsURLConnection?=null
            try{
                c=connection("/api/v1/cam/audio-stream");connections.add(c)
                c.requestMethod="POST";c.doOutput=true;c.readTimeout=0
                c.setRequestProperty("Content-Type","application/x-shizzicam-opus-v3");c.setChunkedStreamingMode(1024)
                val out=DataOutputStream(c.outputStream);audioConfig?.write(out)
                while(running){
                    val p=audio.poll(500,TimeUnit.MILLISECONDS)?:continue
                    if(p.flags==0&&clock.nowUs()-p.ptsUs>180000){audioDrops.incrementAndGet();continue}
                    p.write(out);audioStatus="Opus 128 kbps : paquets envoyés au routeur"
                }
            }catch(_:InterruptedException){break}
            catch(e:Exception){audioStatus="Relais audio : ${e.message}";interruptions.incrementAndGet();pauseRetry()}
            finally{c?.let{connections.remove(it);it.disconnect()}}
        }
    }
    private fun receiveTalk(){
        while(running&&!Thread.currentThread().isInterrupted){
            var c:HttpsURLConnection?=null
            val decoder=CamOpusDecoder()
            var player:AudioTrack?=null
            try{
                player=CamAudioPlayback.create(this)
                c=connection("/api/v1/cam/talk-stream");connections.add(c);c.readTimeout=3000
                check(c.responseCode==200){"Interphone HTTP ${c.responseCode}"}
                val input=DataInputStream(c.inputStream)
                val pending=java.util.ArrayDeque<Pair<Long,Long>>()
                var written=0L;player.play()
                while(running){
                    val p=CamAudioPacket.read(input)
                    if(p.flags==0&&clock.nowUs()-p.ptsUs>250000){audioDrops.incrementAndGet();continue}
                    for(pcm in decoder.decode(p)){
                        CamAudioPlayback.write(player,pcm)
                        written+=pcm.size/2;pending.add(p.sequence to written)
                        val timestamp=AudioTimestamp()
                        if(player.getTimestamp(timestamp)){
                            while(pending.isNotEmpty()&&pending.first.second<=timestamp.framePosition){
                                playedSequence=pending.removeFirst().first;playbackVerified=true
                                playbackRouterUs=clock.nowUs()-(System.nanoTime()-timestamp.nanoTime)/1000
                            }
                        }
                        while(pending.size>32)pending.removeFirst()
                    }
                }
            }catch(_:InterruptedException){break}
            catch(e:Exception){audioStatus="Écoute interphone : ${e.message}";pauseRetry()}
            finally{c?.let{connections.remove(it);it.disconnect()};decoder.close();player?.let{runCatching{it.stop()};it.release()}}
        }
    }
    private fun tick(){
        if(!running)return
        try{
            if(tickCount%60==0)clock.synchronize{json("GET","/api/v1/cam/clock?side=camera")}
            if(tickCount%4==0){
                val cam=relay
                val battery=getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val thermal=getSystemService(PowerManager::class.java).currentThermalStatus
                val hello=json("POST","/api/v1/cam/hello?side=camera",JSONObject().put("battery",battery).put("audio",micEnabled).put("quality",quality))
                val epoch=hello.optString("epoch")
                if(epoch!=routerEpoch){cursor=0;session="";routerEpoch=epoch}
                json("POST","/api/v1/cam/telemetry?side=camera",JSONObject()
                    .put("battery",battery).put("width",cam?.activeWidth?:0).put("height",cam?.activeHeight?:0)
                    .put("quality",cam?.activeQuality?:"indisponible").put("videoCodec",cam?.videoCodec?:"indisponible")
                    .put("audioCodec",if(audioConfig!=null)"Opus" else "indisponible")
                    .put("capturedFps",cam?.capturedRate?.sample()?:0.0).put("encodedFps",cam?.encodedRate?.sample()?:0.0)
                    .put("videoDrops",videoDrops.get()+(cam?.dropped?:0)).put("audioDrops",audioDrops.get())
                    .put("interruptions",interruptions.get()).put("thermalStatus",thermal)
                    .put("clockUncertaintyMillis",clock.uncertaintyUs/1000.0)
                    .put("audioPlayedSequence",playedSequence).put("audioPlaybackVerified",playbackVerified).put("audioPlaybackRouterUs",playbackRouterUs)
                    .put("audioState",(audioStatus+" · alarme "+alarmState).take(160)))
                if(quality=="auto"){
                    val advice=json("GET","/api/v1/cam/advice?side=camera")
                    cam?.adjustAuto(minOf(advice.optInt("maxQualityIndex",2),if(thermal>=3)0 else if(thermal>=2)1 else 4))
                }
            }
            val events=json("GET","/api/v1/cam/events?side=camera&after="+cursor).optJSONArray("events")
            if(events!=null)for(i in 0 until events.length()){
                val event=events.getJSONObject(i);cursor=maxOf(cursor,event.optLong("id"))
                if(event.optString("type")=="watch")session=event.optString("session")
                if(event.optString("type")=="control"&&event.optString("session")==session){
                    val command=event.getString("command");val value=event.getString("value")
                    val expectedSession=session
                    controlLocal(command,value){success,detail->
                        if(success&&command=="quality")quality=value
                        workers.execute{runCatching{json("POST","/api/v1/cam/events?side=camera",JSONObject()
                            .put("session",expectedSession).put("type","status").put("command",command).put("value",value)
                            .put("requestId",event.optString("requestId")).put("success",success).put("detail",detail.take(250)))}}
                    }
                }
            }
            tickCount++
        }catch(e:Exception){captureStatus="Routeur indisponible : reconnexion automatique (${e.message})"}
    }
    private fun pauseRetry(){try{Thread.sleep(500)}catch(_:InterruptedException){Thread.currentThread().interrupt()}}
    override fun onDestroy(){
        running=false;control.shutdownNow();connections.forEach{it.disconnect()};workers.shutdownNow()
        relay?.stop();relay=null;main.removeCallbacksAndMessages(null);stopAlarm()
        if(wake?.isHeld==true)wake?.release();super.onDestroy()
    }
    companion object{
        const val ACTION_STOP="dev.shizzi.conso.CAM_STOP"
        const val EXTRA_PIN="routerCertPin"
        const val EXTRA_QUALITY="quality"
    }
}
