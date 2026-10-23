package dev.shizzi.conso

/** Rotates raw NV21 frames before JPEG encoding for both local and remote viewers. */
internal object CamFrameRotation {
    fun frameRotation(sensorDegrees: Int, deviceDegrees: Int, front: Boolean): Int {
        require(sensorDegrees in listOf(0, 90, 180, 270))
        require(deviceDegrees in listOf(0, 90, 180, 270))
        return if (front) (sensorDegrees - deviceDegrees + 360) % 360
               else (sensorDegrees + deviceDegrees) % 360
    }
    fun rotateNV21(source: ByteArray, width: Int, height: Int, degrees: Int): ByteArray {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
        require(degrees in listOf(0, 90, 180, 270))
        require(source.size == width * height * 3 / 2)
        if (degrees == 0) return source
        val result = ByteArray(source.size)
        val outputWidth = if (degrees == 90 || degrees == 270) height else width
        val outputHeight = if (degrees == 90 || degrees == 270) width else height
        fun position(x: Int, y: Int, w: Int, h: Int): Pair<Int, Int> = when (degrees) {
            90 -> Pair(h - 1 - y, x)
            180 -> Pair(w - 1 - x, h - 1 - y)
            270 -> Pair(y, w - 1 - x)
            else -> Pair(x, y)
        }
        for (y in 0 until height) for (x in 0 until width) {
            val (nx, ny) = position(x, y, width, height)
            result[ny * outputWidth + nx] = source[y * width + x]
        }
        val start = width * height
        for (y in 0 until height / 2) for (x in 0 until width / 2) {
            val (nx, ny) = position(x, y, width / 2, height / 2)
            val original = start + y * width + x * 2
            val rotated = start + ny * outputWidth + nx * 2
            result[rotated] = source[original]
            result[rotated + 1] = source[original + 1]
        }
        check(result.size == outputWidth * outputHeight * 3 / 2)
        return result
    }
}
