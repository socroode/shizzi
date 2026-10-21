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
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Binder
import android.os.Handler
import android.os.Looper
import org.webrtc.SurfaceViewRenderer
import dev.shizzi.camrtc.CamPeer
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Single opt-in capture session. RTP+Opus remain encrypted by WebRTC DTLS-SRTP. */
class CameraCaptureService:Service() {
    @Volatile private var running=false
    private var pin=""
    private var quality="auto"
    private var network:Network?=null
    private var peer:CamPeer?=null
    private lateinit var worker:ScheduledExecutorService
    private val sending=Executors.newSingleThreadExecutor()
    private var cursor=0L
    private var count=0
    private var failures=0
    private var currentSession=""
    private lateinit var notifications:NotificationManager
    private val main=Handler(Looper.getMainLooper())
    @Volatile private var preview:SurfaceViewRenderer?=null
    @Volatile var captureStatus:String="Caméra inactive"
        private set
    inner class LocalBinder:Binder() {fun service():CameraCaptureService=this@CameraCaptureService}
    private val localBinder=LocalBinder()
    fun attachPreview(renderer:SurfaceViewRenderer) {
        preview=renderer
        peer?.attachLocalPreview(renderer)
    }
    fun detachPreview(renderer:SurfaceViewRenderer) {
        if(preview===renderer)preview=null
        peer?.detachLocalPreview(renderer)
    }

    override fun onCreate() {
        super.onCreate()
        worker=Executors.newSingleThreadScheduledExecutor()
        notifications=getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("shizzicam_av","ShizziCam vidéo + son",NotificationManager.IMPORTANCE_LOW))
    }
    override fun onBind(intent:Intent?):IBinder=localBinder
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==ACTION_STOP){stopSelf();return START_NOT_STICKY}
        if(running)return START_NOT_STICKY
        pin=intent?.getStringExtra(EXTRA_PIN).orEmpty()
        quality=intent?.getStringExtra(EXTRA_QUALITY).orEmpty().takeIf(CamPeer::validQuality) ?: "auto"
        if(!pin.matches(Regex("[a-f0-9]{64}"))||
           checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED||
           checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            stopSelf();return START_NOT_STICKY
        }
        val stop=PendingIntent.getService(this,100,Intent(this,CameraCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val note=Notification.Builder(this,"shizzicam_av")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Shizzi+Cam : vidéo et microphone actifs")
            .setContentText("Transmission WebRTC locale sécurisée · Arrêter à tout moment")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null,"Arrêter",stop).build()).build()
        try {
            if(Build.VERSION.SDK_INT>=34)
                startForeground(8421,note,ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(8421,note)
        }catch(_:Exception){stopSelf();return START_NOT_STICKY}
        running=true
        captureStatus="Ouverture caméra et microphone…"
        worker.execute {
            try {
                network=wifi() ?: error("Pas de Wi-Fi Shizzi")
                peer=CamPeer(this,null,signal={ send(it) },status={state->
                    captureStatus=state
                    android.util.Log.i("ShizziCam",state)
                })
                peer?.prepareSender()
                captureStatus="Capture caméra + son actifs, en attente de visionneuse"
                main.post {preview?.let {peer?.attachLocalPreview(it)}}
                worker.scheduleWithFixedDelay({tick()},0,650,TimeUnit.MILLISECONDS)
            }catch(e:Exception) {
                captureStatus="Échec capture : "+(e.message ?: e.javaClass.simpleName)
                android.util.Log.e("ShizziCam","Capture impossible",e)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    private fun wifi():Network? {
        val cm=getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true
        }
    }
    private fun request(method:String,path:String,body:JSONObject?=null):JSONObject {
        val n=network ?: error("Wi-Fi déconnecté")
        val c=RouterTls.connection("https://192.0.2.1"+path,pin,n)
        try {
            c.connectTimeout=3500
            c.readTimeout=3500
            c.requestMethod=method
            if(body!=null) {
                c.doOutput=true
                c.setRequestProperty("Content-Type","application/json")
                val data=body.toString().toByteArray(Charsets.UTF_8)
                c.setFixedLengthStreamingMode(data.size)
                c.outputStream.use{it.write(data)}
            }
            if(c.responseCode!=200)error("API caméra: HTTP "+c.responseCode)
            return JSONObject(c.inputStream.bufferedReader().use{it.readText()})
        }finally{c.disconnect()}
    }
    private fun send(obj:JSONObject) {
        sending.execute {
            if(!running)return@execute
            runCatching {request("POST","/api/v1/cam/events?side=camera",obj)}
                .onFailure {android.util.Log.w("ShizziCam","Signal non remis",it)}
        }
    }
    private fun tick() {
        if(!running)return
        try {
            if(count%3==0) {
                val battery=getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                request("POST","/api/v1/cam/hello?side=camera",
                    JSONObject().put("battery",battery).put("audio",true).put("quality",quality))
            }
            val msg=request("GET","/api/v1/cam/events?side=camera&after="+cursor)
                .optJSONArray("events")
            if(msg!=null) for(i in 0 until msg.length()) {
                val event=msg.getJSONObject(i)
                cursor=maxOf(cursor,event.optLong("id"))
                if(event.optString("type")=="watch") {
                    currentSession=event.getString("session")
                    peer?.beginSender(currentSession,quality)
                }else peer?.accept(event)
            }
            count++
            if(count%6==0)peer?.adjustAutomatic()
            failures=0
        }catch(e:Exception) {
            failures++
            if(failures==4)android.util.Log.w("ShizziCam","Le hotspot ne répond plus : "+e.message)
            // No Wi-Fi forced reconnect; keep service but stop on sustained lost network.
            if(failures>25)stopSelf()
        }
    }
    override fun onDestroy() {
        captureStatus="Caméra et microphone arrêtés"
        running=false
        worker.shutdownNow()
        sending.shutdownNow()
        peer?.close();peer=null
        super.onDestroy()
    }
    companion object {
        const val ACTION_STOP="dev.shizzi.conso.CAM_STOP"
        const val EXTRA_PIN="routerCertPin"
        const val EXTRA_QUALITY="quality"
    }
}
