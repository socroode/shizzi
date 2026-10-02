package dev.shizzi

internal fun parseClientDownloadRange(
    raw: String?,
    sizeBytes: Long,
): LongRange? {
    if (raw.isNullOrBlank() || sizeBytes <= 0L) return null
    val value = raw.trim()
    if (!value.startsWith("bytes=", ignoreCase = true)) return null

    val spec = value.substringAfter('=').trim()
    if (spec.isBlank() || ',' in spec) return null

    val dash = spec.indexOf('-')
    if (dash < 0) return null

    val startRaw = spec.substring(0, dash).trim()
    val endRaw = spec.substring(dash + 1).trim()

    if (startRaw.isBlank()) {
        val suffix = endRaw.toLongOrNull()?.takeIf { it > 0L } ?: return null
        val length = suffix.coerceAtMost(sizeBytes)
        return (sizeBytes - length)..(sizeBytes - 1L)
    }

    val start = startRaw.toLongOrNull()?.takeIf { it >= 0L } ?: return null
    if (start >= sizeBytes) return null

    val requestedEnd = if (endRaw.isBlank()) {
        sizeBytes - 1L
    } else {
        endRaw.toLongOrNull()?.takeIf { it >= start } ?: return null
    }

    return start..requestedEnd.coerceAtMost(sizeBytes - 1L)
}

internal fun LongRange.byteLength(): Long =
    if (last < first) 0L else last - first + 1L
