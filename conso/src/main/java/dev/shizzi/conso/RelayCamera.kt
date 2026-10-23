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

/**
 * Actual captured frames: Android camera -> local ImageView and HTTPS frame uploader.
 * The viewer fetches those bytes from ShizziCam Hotspot, never from this handset.
 */
@Suppress("DEPRECATION")
internal class RelayCamera(
    private val context: Context,
    private val onFrame: (ByteArray) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onH264: (ByteArray,Int,Int,Int,Long) -> Unit = {_,_,_,_,_->}
) {
    private val thread = HandlerThread("ShizziCamCamera").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private var camera: Camera? = null
    @Volatile private var deviceDegrees = 0
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
    private var quality = "auto"
    private var width = 640
    private var height = 480
    private var lastFrameAt = 0L
    private var frameCount = 0L
    private var lastPreviewAt = 0L
    private var avc:CamH264Encoder?=null
    private var avcWidth=0
    private var avcHeight=0
    private var h264Unsupported=false
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
            avc?.close()
            avc=null
            avcWidth=0
            avcHeight=0
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
            val target = when (quality) {
                "360p" -> 360
                "720p" -> 720
                else -> 480
            }
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
                    if (now - lastFrameAt >= (if(h264Unsupported) 125 else 45)) {
                        lastFrameAt = now
                        val turn = CamFrameRotation.frameRotation(sensorDegrees, deviceDegrees, frontFacing)
                        val rotated = CamFrameRotation.rotateNV21(bytes, width, height, turn)
                        val outWidth = if (turn == 90 || turn == 270) height else width
                        val outHeight = if (turn == 90 || turn == 270) width else height
                        if(!h264Unsupported) {
                            try {
                                if(avc==null || avcWidth!=outWidth || avcHeight!=outHeight){
                                    avc?.close()
                                    avc=CamH264Encoder(outWidth,outHeight)
                                    avcWidth=outWidth;avcHeight=outHeight
                                }
                                avc?.encode(rotated,now*1000L){ encoded,flags,pts ->
                                    onH264(encoded,outWidth,outHeight,flags,pts)
                                }
                            } catch (e:Exception) {
                                avc?.close()
                                avc=null
                                h264Unsupported=true
                                onStatus("H.264 matériel indisponible : "+(e.message?:"erreur")+" · secours JPEG non conforme 22 FPS")
                            }
                        }
                        // Local preview is sparse; H264 is the primary router stream.
                        if(now-lastPreviewAt<500 && !h264Unsupported) return@setPreviewCallbackWithBuffer
                        lastPreviewAt=now
                        val out = ByteArrayOutputStream(96 * 1024)
                        val ok = YuvImage(rotated, ImageFormat.NV21, outWidth, outHeight, null)
                            .compressToJpeg(Rect(0, 0, outWidth, outHeight), 55, out)
                        if (ok) {
                            val jpg = out.toByteArray()
                            if (jpg.size <= 180000) {
                                frameCount++
                                latest = jpg
                                preview?.let { view ->
                                    val bitmap = BitmapFactory.decodeByteArray(jpg, 0, jpg.size)
                                    if (bitmap != null) main.post { if (preview === view) view.setImageBitmap(bitmap) }
                                }
                                if(h264Unsupported)onFrame(jpg)
                                if (frameCount == 1L) {
                                    state = "Première image redressée reçue ($outWidth × $outHeight · ${turn}°)"
                                    onStatus(state)
                                }
                            } else if (frameCount == 0L) onStatus("Image trop volumineuse pour le relais")
                        }
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
                    "quality" -> {
                        check(value in listOf("auto","360p","480p","720p")) { "Qualité inconnue" }
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
            try { avc?.close();avc=null;camera?.setPreviewCallbackWithBuffer(null); camera?.stopPreview(); camera?.release() }
            catch (_: Exception) {}
            camera = null
            texture?.release()
            texture = null
            state = "Caméra arrêtée"
            thread.quitSafely()
        }
    }
}
