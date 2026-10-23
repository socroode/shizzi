package dev.shizzi.conso

/**
 * Camera preview buffers are in the sensor's native NV21 orientation.
 * Display orientation and Camera.Parameters.rotation do NOT rotate PreviewCallback bytes.
 * Rotate pixels (Y and interleaved VU) before the JPEG sent to the Shizzi router.
 */
internal object CameraFrameOrientation {
    data class Nv21Frame(val pixels: ByteArray, val width: Int, val height: Int)

    /** OrientationEventListener is clockwise; the front lens uses the reverse sign. */
    fun jpegRotation(sensorDegrees: Int, deviceDegrees: Int, frontFacing: Boolean): Int {
        require(sensorDegrees in setOf(0, 90, 180, 270))
        require(deviceDegrees in setOf(0, 90, 180, 270))
        return if (frontFacing) (sensorDegrees - deviceDegrees + 360) % 360
               else (sensorDegrees + deviceDegrees) % 360
    }

    /** Keep the previous orientation when the device is flat or a sensor reading is ambiguous.
     *  A 55° threshold creates 10° hysteresis around each cardinal orientation.
     */
    fun snappedDeviceOrientation(rawDegrees: Int, previousDegrees: Int): Int {
        if (rawDegrees !in 0..359) return previousDegrees
        val proposed = ((rawDegrees + 45) / 90 % 4) * 90
        if (proposed == previousDegrees) return previousDegrees
        val distance = kotlin.math.min(
            (rawDegrees - previousDegrees + 360) % 360,
            (previousDegrees - rawDegrees + 360) % 360
        )
        return if (distance >= 55) proposed else previousDegrees
    }

    /** Rotate an entire NV21 frame clockwise; preserve VU ordering and frame dimensions. */
    fun rotateNv21(frame: ByteArray, width: Int, height: Int, degrees: Int): Nv21Frame {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
        require(degrees in setOf(0, 90, 180, 270))
        val lumaBytes = width * height
        require(frame.size >= lumaBytes + lumaBytes / 2)
        if (degrees == 0) return Nv21Frame(frame, width, height)

        val dstWidth = if (degrees == 90 || degrees == 270) height else width
        val dstHeight = if (degrees == 90 || degrees == 270) width else height
        val rotated = ByteArray(lumaBytes + lumaBytes / 2)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx: Int
                val dy: Int
                when (degrees) {
                    90 -> { dx = height - 1 - y; dy = x }
                    180 -> { dx = width - 1 - x; dy = height - 1 - y }
                    else -> { dx = y; dy = width - 1 - x }
                }
                rotated[dy * dstWidth + dx] = frame[y * width + x]
            }
        }

        // NV21 has half-resolution chroma with two bytes V,U per 2x2 block.
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val dstChromaWidth = dstWidth / 2
        for (cy in 0 until chromaHeight) {
            for (cx in 0 until chromaWidth) {
                val dx: Int
                val dy: Int
                when (degrees) {
                    90 -> { dx = chromaHeight - 1 - cy; dy = cx }
                    180 -> { dx = chromaWidth - 1 - cx; dy = chromaHeight - 1 - cy }
                    else -> { dx = cy; dy = chromaWidth - 1 - cx }
                }
                val from = lumaBytes + cy * width + 2 * cx
                val to = lumaBytes + dy * (dstChromaWidth * 2) + 2 * dx
                rotated[to] = frame[from]
                rotated[to + 1] = frame[from + 1]
            }
        }
        return Nv21Frame(rotated, dstWidth, dstHeight)
    }
}
