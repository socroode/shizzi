package dev.shizzi

import java.util.Locale

object AdminFileTransferProtocol {
    const val MAX_CHUNK_BYTES = 1024 * 1024

    fun sanitizeFileName(raw: String): String {
        val leaf = raw
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
        val cleaned = buildString {
            leaf.forEach { ch ->
                when {
                    ch.code < 32 -> append('_')
                    ch == '/' || ch == '\\' -> append('_')
                    else -> append(ch)
                }
            }
        }.trim().trim('.')
        return cleaned
            .take(120)
            .ifBlank { "fichier-${System.currentTimeMillis()}" }
    }

    fun normalizeMimeType(raw: String?): String {
        val value = raw.orEmpty().trim().lowercase(Locale.US)
        return value
            .takeIf { it.contains('/') && !it.contains('\n') && !it.contains('\r') }
            ?: "application/octet-stream"
    }

    fun validChunkOffset(expected: Long, requested: Long, length: Int): Boolean =
        expected >= 0L &&
            requested == expected &&
            length in 1..MAX_CHUNK_BYTES
}
