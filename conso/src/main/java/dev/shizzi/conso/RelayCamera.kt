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
import android.widget.ImageView
import java.io.ByteArrayOutputStream

/**
 * Actual captured frames: Android camera -> local ImageView and HTTPS frame uploader.
 * The viewer fetches those bytes from ShizziCam Hotspot, never from this handset.
 */
@Suppress("DEPRECATION")
internal class RelayCamera(
    private val context: Context,
    private val onFrame: (ByteArray) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val thread = HandlerThread("ShizziCamCamera").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private var camera: Camera? = null
    private var texture: SurfaceTexture? = null
    private var side = "back"
    private var torch = false
    private var zoom = 1f
    private var quality = "auto"
    private var width = 640
    private var height = 480
    private var lastFrameAt = 0L
    private var frameCount = 0L
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
        handler.post { open() }
    }
    private fun open(): Boolean {
        try {
            camera?.setPreviewCallbackWithBuffer(null)
            camera?.stopPreview()
            camera?.release()
            camera = null
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
                    if (now - lastFrameAt >= 125) {
                        lastFrameAt = now
                        val out = ByteArrayOutputStream(96 * 1024)
                        val ok = YuvImage(bytes, ImageFormat.NV21, width, height, null)
                            .compressToJpeg(Rect(0, 0, width, height), 55, out)
                        if (ok) {
                            val jpg = out.toByteArray()
                            if (jpg.size <= 180000) {
                                frameCount++
                                latest = jpg
                                preview?.let { view ->
                                    val bitmap = BitmapFactory.decodeByteArray(jpg, 0, jpg.size)
                                    if (bitmap != null) main.post { if (preview === view) view.setImageBitmap(bitmap) }
                                }
                                onFrame(jpg)
                                if (frameCount == 1L) {
                                    state = "Première image réelle reçue ($width × $height)"
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
            return true
        } catch (e: Exception) {
            state = "Échec caméra : " + (e.message ?: e.javaClass.simpleName)
            onStatus(state)
            try { camera?.release() } catch (_: Exception) {}
            camera = null
            return false
        }
    }
    fun control(command: String, value: String, callback: (Boolean, String) -> Unit = { _, _ -> }) {
        handler.post {
            try {
                check(camera != null) { "Caméra non démarrée" }
                when (command) {
                    "camera" -> {
                        check(value == "front" || value == "back") { "Objectif invalide" }
                        if (side != value) {
                            val oldSide = side
                            side = value
                            if (!open()) {
                                side = oldSide
                                open()
                                error("Caméra $value indisponible")
                            }
                        }
                    }
                    "zoom" -> {
                        val amount = value.toFloatOrNull() ?: error("Zoom invalide")
                        check(amount in 1f..8f) { "Zoom hors limites" }
                        val cam = camera ?: error("Caméra non démarrée")
                        val params = cam.parameters
                        check(params.isZoomSupported || amount == 1f) { "Zoom indisponible" }
                        if (params.isZoomSupported) {
                            params.zoom = ((amount - 1f) / 7f * params.maxZoom).toInt()
                                .coerceIn(0, params.maxZoom)
                            cam.parameters = params
                        }
                        zoom = amount
                    }
                    "torch" -> {
                        check(value == "on" || value == "off") { "Commande flash invalide" }
                        val cam = camera ?: error("Caméra non démarrée")
                        val params = cam.parameters
                        check(Camera.Parameters.FLASH_MODE_TORCH in (params.supportedFlashModes ?: emptyList())) {
                            "Flash indisponible sur cet objectif"
                        }
                        params.flashMode = if (value == "on") Camera.Parameters.FLASH_MODE_TORCH
                                           else Camera.Parameters.FLASH_MODE_OFF
                        cam.parameters = params
                        torch = value == "on"
                    }
                    "quality" -> {
                        check(value in listOf("auto","360p","480p","720p")) { "Qualité inconnue" }
                        val old = quality
                        quality = value
                        if (!open()) {
                            quality = old
                            open()
                            error("Changement de qualité impossible")
                        }
                    }
                    else -> error("Commande inconnue")
                }
                onStatus("Commande appliquée : $command → $value")
                callback(true, "")
            } catch (e: Exception) {
                val why = e.message ?: e.javaClass.simpleName
                onStatus("Commande $command refusée : $why")
                callback(false, why)
            }
        }
    }
    fun stop() {
        handler.post {
            try { camera?.setPreviewCallbackWithBuffer(null); camera?.stopPreview(); camera?.release() }
            catch (_: Exception) {}
            camera = null
            texture?.release()
            texture = null
            state = "Caméra arrêtée"
            thread.quitSafely()
        }
    }
}
