package dev.shizzi

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference

data class MediaIndexSummary(
    val films: Int,
    val series: Int,
    val music: Int,
    val total: Int,
    val updatedAt: Long,
)

object MediaIndex {
    private const val FILE_NAME = "shizzi-media-index.json"
    private const val FORMAT_VERSION = 2

    private data class Cache(
        val modifiedAt: Long,
        val entries: List<MediaEntry>,
        val indexedAt: Long,
    )

    private val cache = AtomicReference<Cache?>(null)

    fun entries(context: Context, kind: MediaKind? = null): List<MediaEntry> {
        val all = load(context).entries
        return if (kind == null) all else all.filter { it.kind == kind }
    }

    fun entriesForFolder(context: Context, folderId: String): List<MediaEntry> =
        load(context).entries.filter { it.folderId == folderId }

    fun find(context: Context, id: String?): MediaEntry? {
        if (id.isNullOrBlank()) return null
        return load(context).entries.firstOrNull { it.id == id }
    }

    fun summary(context: Context): MediaIndexSummary {
        val loaded = load(context)
        val films = loaded.entries.count { it.kind == MediaKind.FILMS }
        val series = loaded.entries.count { it.kind == MediaKind.SERIES }
        val music = loaded.entries.count { it.kind == MediaKind.MUSIC }
        return MediaIndexSummary(
            films = films,
            series = series,
            music = music,
            total = loaded.entries.size,
            updatedAt = loaded.indexedAt,
        )
    }

    /**
     * Rebuild only one category when possible. This keeps selecting a Films
     * folder from forcing an unnecessary rescan of Series and Music.
     */
    @Synchronized
    fun rebuild(
        context: Context,
        kind: MediaKind? = null,
        onProgress: ((MediaCatalog.ScanProgress) -> Unit)? = null,
    ): MediaIndexSummary {
        val preserved = if (kind == null) {
            emptyList()
        } else {
            load(context).entries.filterNot { it.kind == kind }
        }

        val scanned = MediaCatalog.scan(context, kind, onProgress)
        val merged = (preserved + scanned)
            .sortedWith(compareBy<MediaEntry>({ it.kind.ordinal }, { it.relativePath.lowercase() }))

        write(context, merged)
        return summary(context)
    }

    @Synchronized
    fun remove(context: Context, kind: MediaKind) {
        val remaining = load(context).entries.filterNot { it.kind == kind }
        write(context, remaining)
    }

    @Synchronized
    fun removeFolder(context: Context, folderId: String) {
        val remaining = load(context).entries.filterNot { it.folderId == folderId }
        write(context, remaining)
    }

    private fun load(context: Context): Cache {
        val file = indexFile(context)
        if (!file.isFile) return Cache(0L, emptyList(), 0L)

        val modified = file.lastModified()
        cache.get()?.takeIf { it.modifiedAt == modified }?.let { return it }

        val parsed = runCatching {
            val root = JSONObject(file.readText())
            val version = root.optInt("version", 0)
            if (version !in 1..FORMAT_VERSION) {
                return@runCatching Cache(modified, emptyList(), 0L)
            }
            val indexedAt = root.optLong("indexedAt", 0L)
            val array = root.optJSONArray("entries") ?: JSONArray()
            val entries = buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val kind = MediaKind.fromKey(item.optString("kind")) ?: continue
                    val uri = item.optString("uri").takeIf { it.isNotBlank() } ?: continue
                    add(
                        MediaEntry(
                            id = item.optString("id"),
                            kind = kind,
                            name = item.optString("name"),
                            relativePath = item.optString("relativePath"),
                            uri = Uri.parse(uri),
                            mimeType = item.optString("mimeType", "application/octet-stream"),
                            size = item.optLong("size", 0L),
                            folderId = item.optString("folderId")
                                .ifBlank { "legacy-${kind.key}" },
                            folderName = item.optString("folderName")
                                .ifBlank { kind.label },
                        ),
                    )
                }
            }
            Cache(modified, entries, indexedAt)
        }.getOrElse {
            Cache(modified, emptyList(), 0L)
        }

        cache.set(parsed)
        return parsed
    }

    private fun write(context: Context, entries: List<MediaEntry>) {
        val indexedAt = System.currentTimeMillis()
        val root = JSONObject().apply {
            put("version", FORMAT_VERSION)
            put("indexedAt", indexedAt)
            put(
                "entries",
                JSONArray().apply {
                    entries.forEach { entry ->
                        put(
                            JSONObject().apply {
                                put("id", entry.id)
                                put("kind", entry.kind.key)
                                put("name", entry.name)
                                put("relativePath", entry.relativePath)
                                put("uri", entry.uri.toString())
                                put("mimeType", entry.mimeType)
                                put("size", entry.size)
                                put("folderId", entry.folderId)
                                put("folderName", entry.folderName)
                            },
                        )
                    }
                },
            )
        }

        val destination = indexFile(context)
        val temporary = File(context.filesDir, "$FILE_NAME.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(root.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }

        if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
        }

        cache.set(null)
    }

    private fun indexFile(context: Context): File =
        File(context.filesDir, FILE_NAME)
}
