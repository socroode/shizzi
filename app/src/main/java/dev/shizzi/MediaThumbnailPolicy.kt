package dev.shizzi

object MediaThumbnailPolicy {
    const val WIDTH = 480
    const val HEIGHT = 270
    const val JPEG_QUALITY = 82
    const val FRAME_PERCENT = 25L

    fun frameTimeUs(durationMillis: Long): Long {
        if (durationMillis <= 0L) return 0L
        return (durationMillis * 1_000L * FRAME_PERCENT) / 100L
    }

    fun cacheFileName(id: String, size: Long, mimeType: String): String {
        val safeId = id.filter { it.isLetterOrDigit() }.take(40).ifBlank { "media" }
        val mimeHash = mimeType.lowercase().hashCode().toString().replace('-', 'n')
        return "$safeId-$size-$mimeHash.jpg"
    }

    fun displayTitle(fileName: String): String {
        val trimmed = fileName.trim()
        if (trimmed.isBlank()) return "Sans titre"
        val dot = trimmed.lastIndexOf('.')
        return if (dot > 0) trimmed.substring(0, dot) else trimmed
    }
}
