package dev.shizzi.conso

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.Camera
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.OrientationEventListener
import android.hardware.SensorManager
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import dev.shizzi.camrtc.CamH264Encoder
import dev.shizzi.camrtc.CamQuality
import dev.shizzi.camrtc.CamRate
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import android.os.PowerManager

/**
 * Actual captured frames: Android camera -> local ImageView and HTTPS frame uploader.
 * The viewer fetches those bytes from ShizziCam Hotspot, never from this handset.
 */
@Suppress("DEPRECATION")
internal class RelayCamera(
    private val context: Context,
    private val onFrame: (ByteArray) -> Unit,
    private val nowUs: () -> Long,
    private val onStatus: (String) -> Unit,
    private val onH264: (ByteArray,Int,Int,Int,Long) -> Unit = {_,_,_,_,_->}
) {
    private val thread = HandlerThread("ShizziCamCamera").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private var camera: Camera? = null
    @Volatile private var deviceDegrees = 0
    @Volatile private var rotationOverride=-1
    private var sensorDegrees = 0
    private var frontFacing = false
    private val orientation = object : OrientationEventListener(context.applicationContext,
        SensorManager.SENSOR_DELAY_UI) {
        override fun onOrientationChanged(angle: Int) {
            if (angle != ORIENTATION_UNKNOWN)
                deviceDegrees = (((angle + 45) / 90) * 90) % 360
        }
    }

    private var texture: SurfaceTexture? = null
    private var side = "back"
    private var torch = false
    private var zoom = 1f
    @Volatile private var quality = "auto"
    private var width = 640
    private var height = 480
    private var lastFrameAt = 0L
    private var frameCount = 0L
    private var lastPreviewAt = 0L
    private var avc:CamH264Encoder?=null
    private var avcWidth=0
    private var avcHeight=0
    @Volatile private var h264Unsupported=false
    @Volatile var activeWidth=0
    @Volatile var activeHeight=0
    @Volatile var activeQuality="360p"
    @Volatile var videoCodec="indisponible"
    val capturedRate=CamRate()
    val encodedRate=CamRate()
    @Volatile var dropped=0L
    private val generation=AtomicInteger()
    private data class Frame(val data:ByteArray,val w:Int,val h:Int,val turn:Int,val pts:Long,val generation:Int)
    private val pending=ArrayBlockingQueue<Frame>(2)
    private val encoderWorker=Executors.newSingleThreadExecutor()
    @Volatile private var encoding=true
    @Volatile private var autoLevel=2
    @Volatile private var keyFrameRequested=false
    init {encoderWorker.execute{encodeLoop()}}
    fun requestKeyFrame(){keyFrameRequested=true}
    private var autoUpVotes=0
    private var lastAutoChange=0L
    fun adjustAuto(level:Int){
        handler.post{
            if(quality!="auto")return@post
            val target=level.coerceIn(0,4)
            val now=android.os.SystemClock.elapsedRealtime()
            val next=when{
                target<autoLevel->{autoUpVotes=0;target}
                target>autoLevel&&++autoUpVotes>=3&&now-lastAutoChange>=8000->{autoUpVotes=0;autoLevel+1}
                else->autoLevel
            }
            if(next!=autoLevel){autoLevel=next;lastAutoChange=now;open()}
        }
    }
    @Volatile private var preview: ImageView? = null
    @Volatile private var latest: ByteArray? = null
    @Volatile var state = "Capture inactive"
        private set

    fun attach(image: ImageView) {
        preview = image
        latest?.let { jpg ->
            val bitmap = BitmapFactory.decodeByteArray(jpg, 0, jpg.size)
            if (bitmap != null) image.setImageBitmap(bitmap)
        }
    }

    fun detach(image: ImageView) { if (preview === image) preview = null }
    fun start(requested: String) {
        quality = requested
        if (orientation.canDetectOrientation()) orientation.enable()
        else onStatus("Capteur d'orientation indisponible : mode portrait par défaut")
        handler.post { open() }
    }
    private fun open() {
        try {
            camera?.setPreviewCallbackWithBuffer(null)
            camera?.stopPreview()
            camera?.release()
            camera = null
            generation.incrementAndGet()
            pending.clear()
            h264Unsupported=false
            texture?.release()
            texture = null
            val facing = if (side == "front") Camera.CameraInfo.CAMERA_FACING_FRONT
                         else Camera.CameraInfo.CAMERA_FACING_BACK
            val info = Camera.CameraInfo()
            var selected = -1
            for (i in 0 until Camera.getNumberOfCameras()) {
                Camera.getCameraInfo(i, info)
                if (info.facing == facing) { selected = i; break }
            }
            if (selected < 0) throw IllegalStateException("Caméra $side indisponible")
            Camera.getCameraInfo(selected, info)
            sensorDegrees = info.orientation
            frontFacing = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
            val cam = Camera.open(selected)
            camera = cam
            val params = cam.parameters
            val supported = params.supportedPreviewFormats ?: emptyList()
            if (ImageFormat.NV21 !in supported) throw IllegalStateException("Capture NV21 indisponible")
            params.previewFormat = ImageFormat.NV21
            params.supportedPreviewFpsRange?.filter{it.size==2&&it[1]>=22000}
                ?.minByOrNull{kotlin.math.abs(it[1]-22000)}?.let{params.setPreviewFpsRange(it[0],it[1])}
            val target = CamQuality.profile(if(quality=="auto") CamQuality.profiles[autoLevel].name else quality).height
            val sizes = params.supportedPreviewSizes ?: emptyList()
            val chosen = sizes.minByOrNull {
                kotlin.math.abs(it.height - target) * 3 + kotlin.math.abs(it.width * 9 - it.height * 16) / 16
            } ?: throw IllegalStateException("Aucune résolution disponible")
            width = chosen.width
            height = chosen.height
            params.setPreviewSize(width, height)
            if (Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO in (params.supportedFocusModes ?: emptyList())) {
                params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
            }
            if (params.isZoomSupported) {
                val maxZoom = params.maxZoom
                params.zoom = ((zoom - 1f) / 7f * maxZoom).toInt().coerceIn(0, maxZoom)
            }
            if (Camera.Parameters.FLASH_MODE_TORCH in (params.supportedFlashModes ?: emptyList())) {
                params.flashMode = if (torch) Camera.Parameters.FLASH_MODE_TORCH
                                   else Camera.Parameters.FLASH_MODE_OFF
            } else torch = false
            cam.parameters = params
            texture = SurfaceTexture(11)
            cam.setPreviewTexture(texture)
            val byteCount = width * height * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8
            repeat(3) { cam.addCallbackBuffer(ByteArray(byteCount)) }
            cam.setPreviewCallbackWithBuffer { bytes, device ->
                try {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now >= lastFrameAt) {
                        // Carry fractional cadence across 30 FPS callbacks; resetting to now would cap at 15 FPS.
                        if(lastFrameAt==0L||now-lastFrameAt>100)lastFrameAt=now
                        lastFrameAt += 45
                        capturedRate.event()
                        val turn=if(rotationOverride<0)CamFrameRotation.frameRotation(sensorDegrees,deviceDegrees,frontFacing) else rotationOverride
                        val frame=Frame(bytes.copyOf(),width,height,turn,nowUs(),generation.get())
                        if(!pending.offer(frame)){pending.poll();pending.offer(frame);dropped++}

                    }
                } catch (e: Exception) {
                    onStatus("Capture : " + (e.message ?: e.javaClass.simpleName))
                } finally {
                    device.addCallbackBuffer(bytes)
                }
            }
            cam.startPreview()
            state = "Ouverture de la caméra $side ($width × $height)…"
            onStatus(state)
        } catch (e: Exception) {
            state = "Échec caméra : " + (e.message ?: e.javaClass.simpleName)
            onStatus(state)
            try { camera?.release() } catch (_: Exception) {}
            camera = null
        }
    }
    private fun encodeLoop(){
        var previousGeneration=-1
        var previousProfile=""
        var previousTurn=-1
        try{
            while(encoding&&!Thread.currentThread().isInterrupted){
                val frame=pending.take()
                if(frame.generation!=generation.get())continue
                val profile=CamQuality.profile(if(quality=="auto")CamQuality.profiles[autoLevel].name else quality)
                val portrait=frame.turn==90||frame.turn==270
                if(previousGeneration!=frame.generation||previousProfile!=profile.name||previousTurn!=frame.turn){
                    avc?.close();avc=null
                    h264Unsupported=false
                    val w=if(portrait)profile.height else profile.width
                    val h=if(portrait)profile.width else profile.height
                    try{
                        avc=CamH264Encoder(w,h,profile.bitrate)
                        activeWidth=avc!!.width;activeHeight=avc!!.height
                        activeQuality=profile.name;videoCodec=if(avc!!.hardware)"H264 matériel" else "H264 logiciel"
                        onStatus("$videoCodec · $activeWidth × $activeHeight · ${profile.name}")
                    }catch(e:Exception){
                        h264Unsupported=true;videoCodec="indisponible"
                        onStatus("Échec H.264 : ${e.message}. Aucun direct H.264 disponible.")
                    }
                    previousGeneration=frame.generation;previousProfile=profile.name;previousTurn=frame.turn
                }
                if(h264Unsupported)continue
                val rotated=CamFrameRotation.rotateNV21(frame.data,frame.w,frame.h,frame.turn)
                val rw=if(portrait)frame.h else frame.w
                val rh=if(portrait)frame.w else frame.h
                val codec=avc?:continue
                val scaled=CamFrameRotation.fitNV21(rotated,rw,rh,codec.width,codec.height)
                if(keyFrameRequested){codec.requestKeyFrame();keyFrameRequested=false}
                codec.encode(scaled,frame.pts){bytes,flags,pts->
                    if(flags and 2==0)encodedRate.event()
                    onH264(bytes,codec.width,codec.height,flags,pts)
                }
                val now=android.os.SystemClock.elapsedRealtime()
                if(now-lastPreviewAt>=500&&preview!=null){
                    lastPreviewAt=now
                    val out=ByteArrayOutputStream()
                    YuvImage(scaled,ImageFormat.NV21,codec.width,codec.height,null)
                        .compressToJpeg(Rect(0,0,codec.width,codec.height),45,out)
                    val jpg=out.toByteArray();latest=jpg
                    val bitmap=BitmapFactory.decodeByteArray(jpg,0,jpg.size)
                    main.post{preview?.setImageBitmap(bitmap)}
                }
            }
        }catch(_:InterruptedException){}
        catch(e:Exception){videoCodec="indisponible";onStatus("Encodage arrêté : ${e.message}")}
        finally{avc?.close();avc=null}
    }

    /** Result is dispatched after the camera device operation, never at enqueue. */
    fun control(command: String, value: String, done: (Boolean,String) -> Unit = { _,_ -> }) {
        handler.post {
            try {
                val result = when (command) {
                    "camera" -> {
                        if (value !in listOf("front","back")) error("Caméra demandée inconnue")
                        if (side != value) {
                            side = value
                            open()
                        }
                        check(camera != null) { "Caméra $value indisponible" }
                        "Caméra $value activée"
                    }
                    "zoom" -> {
                        val amount = value.toFloatOrNull() ?: error("Zoom invalide")
                        check(amount in 1f..8f) { "Zoom hors limites" }
                        val cam = camera ?: error("Caméra inactive")
                        val p = cam.parameters
                        check(p.isZoomSupported) { "Zoom indisponible sur cet appareil" }
                        p.zoom = ((amount - 1f)/7f*p.maxZoom).toInt().coerceIn(0,p.maxZoom)
                        cam.parameters = p
                        zoom = amount
                        "Zoom %.1f× appliqué".format(amount)
                    }
                    "torch" -> {
                        val cam = camera ?: error("Caméra inactive")
                        val p = cam.parameters
                        check(Camera.Parameters.FLASH_MODE_TORCH in (p.supportedFlashModes ?: emptyList())) {
                            "Flash indisponible sur cette caméra"
                        }
                        p.flashMode = if (value=="on") Camera.Parameters.FLASH_MODE_TORCH else Camera.Parameters.FLASH_MODE_OFF
                        cam.parameters = p
                        torch = value=="on"
                        if (torch) "Flash activé" else "Flash désactivé"
                    }
                    "rotation" -> {
                        check(value in listOf("auto","0","90","180","270")){"Rotation invalide"}
                        rotationOverride=if(value=="auto")-1 else value.toInt()
                        "Orientation $value réglée"
                    }
                    "quality" -> {
                        check(value in CamQuality.names) { "Qualité inconnue" }
                        quality = value
                        open()
                        check(camera != null) { "Échec du changement de qualité" }
                        "Qualité $value appliquée"
                    }
                    else -> error("Commande non reconnue")
                }
                done(true,result)
            } catch (e: Exception) {
                val message=e.message ?: "Commande non exécutée"
                onStatus("Échec $command : $message")
                done(false,message)
            }
        }
    }
    fun stop() {
        orientation.disable()
        handler.post {
            try { generation.incrementAndGet();encoding=false;encoderWorker.shutdownNow();camera?.setPreviewCallbackWithBuffer(null); camera?.stopPreview(); camera?.release() }
            catch (_: Exception) {}
            camera = null
            texture?.release()
            texture = null
            state = "Caméra arrêtée"
            thread.quitSafely()
        }
    }
}
