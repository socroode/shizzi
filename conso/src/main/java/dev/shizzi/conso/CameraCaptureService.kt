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
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

/**
 * Proof-of-concept secure still-frame streaming. The router stores only the most recent JPEG.
 * Captures are opt-in and run with Android's visible CAMERA foreground-service indicator.
 */
class CameraCaptureService : Service() {
    private lateinit var cameraThread: HandlerThread
    private lateinit var cameraHandler: Handler
    private val sender = Executors.newSingleThreadExecutor()
    private val sending = AtomicBoolean(false)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var pin: String = ""
    @Volatile private var active = false

    override fun onCreate() {
        super.onCreate()
        cameraThread = HandlerThread("shizzicam-camera").also { it.start() }
        cameraHandler = Handler(cameraThread.looper)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("shizzicam_capture","Caméra ShizziCam",NotificationManager.IMPORTANCE_LOW)
        )
    }
    override fun onBind(intent:Intent?):IBinder? = null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (active) return START_NOT_STICKY
        val proposed = intent?.getStringExtra(EXTRA_PIN).orEmpty()
        if (!proposed.matches(Regex("[0-9a-f]{64}")) ||
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            stopSelf();return START_NOT_STICKY
        }
        pin = proposed
        val stop = PendingIntent.getService(this,3,Intent(this,CameraCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val note = Notification.Builder(this,"shizzicam_capture")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Shizzi+Cam : caméra active")
            .setContentText("Images envoyées uniquement au routeur ShizziCam")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null,"Arrêter",stop).build()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(8421,note,ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        else startForeground(8421,note)
        active = true
        openCamera()
        return START_NOT_STICKY
    }
    private fun openCamera() {
        try {
            val manager = getSystemService(CameraManager::class.java)
            val cameraId = manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull() ?: error("Pas de caméra")
            val map=manager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: error("Format caméra indisponible")
            val sizes=map.getOutputSizes(ImageFormat.JPEG).orEmpty()
            val chosen=sizes.filter { it.width<=1280 && it.height<=720 }
                .minByOrNull { kotlin.math.abs(it.width*it.height-640*480) }
                ?: sizes.firstOrNull() ?: error("Capture JPEG indisponible")
            reader=ImageReader.newInstance(chosen.width,chosen.height,ImageFormat.JPEG,2).apply {
                setOnImageAvailableListener({ source ->
                    val img=source.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val buffer=img.planes[0].buffer
                        if (buffer.remaining() in 8..180000 && sending.compareAndSet(false,true)) {
                            val bytes=ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            sender.execute {
                                try { upload(bytes) } finally { sending.set(false) }
                            }
                        }
                    } finally {img.close()}
                },cameraHandler)
            }
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                stopSelf();return
            }
            manager.openCamera(cameraId,object:CameraDevice.StateCallback() {
                override fun onOpened(camera:CameraDevice) {
                    if (!active) {camera.close();return}
                    device=camera
                    camera.createCaptureSession(listOf(reader!!.surface),object:CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s:CameraCaptureSession) {
                            session=s
                            cameraHandler.post(captureLoop)
                        }
                        override fun onConfigureFailed(s:CameraCaptureSession) { stopSelf() }
                    },cameraHandler)
                }
                override fun onDisconnected(camera:CameraDevice) {camera.close();stopSelf()}
                override fun onError(camera:CameraDevice,error:Int) {camera.close();stopSelf()}
            },cameraHandler)
        } catch (_:Exception) {stopSelf()}
    }
    private val captureLoop=object:Runnable {
        override fun run() {
            if (!active) return
            try {
                val camera=device ?: return
                val s=session ?: return
                val capture=camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader!!.surface)
                    set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }
                s.capture(capture.build(),null,cameraHandler)
            } catch (_:Exception) {}
            if (active) cameraHandler.postDelayed(this,850)
        }
    }
    private fun upload(bytes:ByteArray) {
        try {
            val manager=getSystemService(ConnectivityManager::class.java)
            val wifi=manager.allNetworks.firstOrNull {
                manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true
            } ?: return
            val conn=RouterTls.connection("https://192.0.2.1/api/v1/cam/frame",pin,wifi)
            try {
                conn.requestMethod="POST"
                conn.connectTimeout=4000
                conn.readTimeout=4000
                conn.doOutput=true
                conn.setRequestProperty("Content-Type","image/jpeg")
                val battery=getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                conn.setRequestProperty("X-Shizzi-Cam-Battery",battery.toString())
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
                conn.responseCode
            } finally {conn.disconnect()}
        } catch (_:Exception) {
            // Keep camera active, but never fall back to unencrypted HTTP or mobile Internet.
        }
    }
    override fun onDestroy() {
        active=false
        cameraHandler.removeCallbacks(captureLoop)
        try {session?.close()}catch (_:Exception){}
        try {device?.close()}catch (_:Exception){}
        try {reader?.close()}catch (_:Exception){}
        sender.shutdownNow()
        cameraThread.quitSafely()
        super.onDestroy()
    }
    companion object {
        const val ACTION_STOP="dev.shizzi.conso.CAM_STOP"
        const val EXTRA_PIN="routerCertPin"
    }
}
