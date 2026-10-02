package dev.shizzi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Collections

enum class MediaKind(val key: String, val label: String) {
    FILMS("films", "Films"),
    SERIES("series", "Séries"),
    MUSIC("music", "Musique");

    fun accepts(name: String, mime: String?): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (this) {
            FILMS, SERIES -> mime?.startsWith("video/") == true ||
                ext in setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "ts")
            MUSIC -> mime?.startsWith("audio/") == true ||
                ext in setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac")
        }
    }

    companion object {
        fun fromKey(value: String?): MediaKind? = entries.firstOrNull { it.key == value }
    }
}

data class MediaEntry(
    val id: String,
    val kind: MediaKind,
    val name: String,
    val relativePath: String,
    val uri: Uri,
    val mimeType: String,
    val size: Long,
    val folderId: String = "",
    val folderName: String = "",
)

object MediaPrefs {
    private const val PREFS = "shizzi_media"
    private const val KEY_ENABLED = "enabled"

    private fun key(kind: MediaKind) = "tree_${kind.key}"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun treeUri(context: Context, kind: MediaKind): Uri? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(kind), null)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return runCatching { Uri.parse(raw) }.getOrNull()
    }

    fun setTreeUri(context: Context, kind: MediaKind, uri: Uri?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key(kind), uri?.toString())
            .apply()
    }

    fun hasAnyLibrary(context: Context): Boolean =
        MediaKind.entries.any { treeUri(context, it) != null }
}

object MediaCatalog {
    data class ScanProgress(
        val kind: MediaKind,
        val filesFound: Int,
        val directoriesVisited: Int,
        val currentPath: String,
    )

    fun scan(
        context: Context,
        kind: MediaKind? = null,
        onProgress: ((ScanProgress) -> Unit)? = null,
    ): List<MediaEntry> {
        val configured = MediaFolderStore.load(context)
            .filter { it.enabled && it.uri() != null && (kind == null || it.kind == kind) }

        if (MediaFolderStore.isConfigured(context)) {
            return configured.flatMap { scanFolder(context, it, onProgress) }
                .sortedWith(
                    compareBy<MediaEntry>(
                        { it.folderName.lowercase() },
                        { it.relativePath.lowercase() },
                    ),
                )
        }

        val kinds = kind?.let(::listOf) ?: MediaKind.entries
        return kinds.flatMap { scanKind(context, it, onProgress) }
            .sortedWith(compareBy<MediaEntry>({ it.kind.ordinal }, { it.relativePath.lowercase() }))
    }

    private fun scanFolder(
        context: Context,
        folder: MediaFolderConfig,
        onProgress: ((ScanProgress) -> Unit)?,
    ): List<MediaEntry> {
        val tree = folder.uri() ?: return emptyList()

        val fast = runCatching {
            scanWithDocumentsContract(
                context = context,
                kind = folder.kind,
                treeUri = tree,
                onProgress = onProgress,
                folderId = folder.id,
                folderName = folder.name,
            )
        }.getOrNull()
        if (fast != null) return fast

        return scanWithDocumentFile(
            context = context,
            kind = folder.kind,
            treeUri = tree,
            onProgress = onProgress,
            folderId = folder.id,
            folderName = folder.name,
        )
    }

    private fun scanKind(
        context: Context,
        kind: MediaKind,
        onProgress: ((ScanProgress) -> Unit)?,
    ): List<MediaEntry> {
        val tree = MediaPrefs.treeUri(context, kind) ?: return emptyList()

        // Fast path: query children in batches through Android's DocumentsProvider.
        // This avoids DocumentFile performing several binder calls for every file.
        val fast = runCatching {
            scanWithDocumentsContract(
                context = context,
                kind = kind,
                treeUri = tree,
                onProgress = onProgress,
                folderId = "legacy-${kind.key}",
                folderName = kind.label,
            )
        }.getOrNull()
        if (fast != null) return fast

        // Compatibility fallback for unusual OEM/cloud providers.
        return scanWithDocumentFile(
            context = context,
            kind = kind,
            treeUri = tree,
            onProgress = onProgress,
            folderId = "legacy-${kind.key}",
            folderName = kind.label,
        )
    }

    private data class PendingDirectory(
        val documentId: String,
        val relativePath: String,
    )

    private fun scanWithDocumentsContract(
        context: Context,
        kind: MediaKind,
        treeUri: Uri,
        onProgress: ((ScanProgress) -> Unit)?,
        folderId: String,
        folderName: String,
    ): List<MediaEntry> {
        val resolver = context.contentResolver
        val rootDocumentId = runCatching { DocumentsContract.getDocumentId(treeUri) }
            .getOrElse { DocumentsContract.getTreeDocumentId(treeUri) }
        val pending = ArrayDeque<PendingDirectory>()
        pending.add(PendingDirectory(rootDocumentId, ""))

        val out = mutableListOf<MediaEntry>()
        var directoriesVisited = 0
        var lastProgressFiles = -1

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        while (pending.isNotEmpty()) {
            val directory = pending.removeFirst()
            directoriesVisited++

            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                directory.documentId,
            )

            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)

                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(idIndex) ?: continue
                    val name = cursor.getString(nameIndex)?.ifBlank { "Sans titre" } ?: "Sans titre"
                    val mime = cursor.getString(mimeIndex)
                    val relative = if (directory.relativePath.isBlank()) {
                        name
                    } else {
                        "${directory.relativePath}/$name"
                    }

                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        pending.add(PendingDirectory(documentId, relative))
                        continue
                    }

                    if (!kind.accepts(name, mime)) continue

                    val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                    val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L
                    out += MediaEntry(
                        id = stableId(documentUri),
                        kind = kind,
                        name = name,
                        relativePath = relative,
                        uri = documentUri,
                        mimeType = mime ?: inferMime(name) ?: "application/octet-stream",
                        size = size,
                        folderId = folderId,
                        folderName = folderName,
                    )

                    if (out.size == 1 || out.size - lastProgressFiles >= PROGRESS_STEP) {
                        lastProgressFiles = out.size
                        onProgress?.invoke(
                            ScanProgress(
                                kind = kind,
                                filesFound = out.size,
                                directoriesVisited = directoriesVisited,
                                currentPath = directory.relativePath,
                            ),
                        )
                    }
                }
            } ?: throw IllegalStateException("DocumentsProvider query returned null")
        }

        onProgress?.invoke(
            ScanProgress(
                kind = kind,
                filesFound = out.size,
                directoriesVisited = directoriesVisited,
                currentPath = "",
            ),
        )
        return out
    }

    private fun scanWithDocumentFile(
        context: Context,
        kind: MediaKind,
        treeUri: Uri,
        onProgress: ((ScanProgress) -> Unit)?,
        folderId: String,
        folderName: String,
    ): List<MediaEntry> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        if (!root.exists() || !root.isDirectory) return emptyList()

        val out = mutableListOf<MediaEntry>()
        var directoriesVisited = 0

        fun walk(directory: DocumentFile, prefix: String) {
            directoriesVisited++
            val children = runCatching { directory.listFiles().toList() }.getOrDefault(emptyList())
            children.forEach { child ->
                val name = child.name.orEmpty().ifBlank { "Sans titre" }
                val relative = if (prefix.isBlank()) name else "$prefix/$name"
                when {
                    child.isDirectory -> walk(child, relative)
                    child.isFile -> {
                        val mime = child.type ?: inferMime(name)
                        if (!kind.accepts(name, mime)) return@forEach
                        out += MediaEntry(
                            id = stableId(child.uri),
                            kind = kind,
                            name = name,
                            relativePath = relative,
                            uri = child.uri,
                            mimeType = mime ?: "application/octet-stream",
                            size = child.length(),
                            folderId = folderId,
                            folderName = folderName,
                        )
                        if (out.size == 1 || out.size % PROGRESS_STEP == 0) {
                            onProgress?.invoke(
                                ScanProgress(
                                    kind = kind,
                                    filesFound = out.size,
                                    directoriesVisited = directoriesVisited,
                                    currentPath = prefix,
                                ),
                            )
                        }
                    }
                }
            }
        }

        walk(root, "")
        onProgress?.invoke(
            ScanProgress(
                kind = kind,
                filesFound = out.size,
                directoriesVisited = directoriesVisited,
                currentPath = "",
            ),
        )
        return out
    }

    private fun stableId(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(uri.toString().toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    private fun inferMime(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isBlank()) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: when (ext) {
                "mkv" -> "video/x-matroska"
                "flac" -> "audio/flac"
                "opus" -> "audio/ogg"
                else -> null
            }
    }

    private const val PROGRESS_STEP = 10
}

object MediaNetwork {
    const val LOOPBACK_HOST = "127.0.0.1"
    const val PORT = 8088

    fun backendReachable(
        host: String = LOOPBACK_HOST,
        port: Int = PORT,
        timeoutMillis: Int = 500,
    ): Boolean =
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMillis)
                socket.soTimeout = timeoutMillis
                socket.getOutputStream().write(
                    "GET /health HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n"
                        .toByteArray(Charsets.US_ASCII),
                )
                socket.getOutputStream().flush()
                val status = socket.getInputStream().bufferedReader(Charsets.US_ASCII).readLine().orEmpty()
                status.contains(" 200 ")
            }
        }.getOrDefault(false)

    /**
     * Returns addresses that belong to local interfaces which are not exposed
     * by ConnectivityManager as normal upstream networks. Android tethering
     * downstream/SoftAP interfaces are local interfaces, but are not ordinary
     * app-visible Networks. This avoids guessing OEM interface names or
     * hard-coding subnets, so the same code works across Oppo, Samsung, etc.
     */
    fun portalUrls(context: Context): List<String> {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val upstreamAddresses = runCatching {
            manager.allNetworks
                .flatMap { network ->
                    manager.getLinkProperties(network)?.linkAddresses.orEmpty()
                }
                .mapNotNull { link -> link.address as? Inet4Address }
                .mapNotNull { address -> address.hostAddress }
                .toSet()
        }.getOrDefault(emptySet())

        val addresses = mutableListOf<Inet4Address>()
        val interfaces = runCatching { Collections.list(NetworkInterface.getNetworkInterfaces()) }
            .getOrDefault(emptyList())

        interfaces
            .filter { iface -> runCatching { iface.isUp }.getOrDefault(false) }
            .filterNot { iface ->
                iface.isLoopback ||
                    iface.name.startsWith("testtun") ||
                    iface.name.startsWith("tun")
            }
            .forEach { iface ->
                Collections.list(iface.inetAddresses)
                    .filterIsInstance<Inet4Address>()
                    .filter { address ->
                        address.isSiteLocalAddress &&
                            !address.isLoopbackAddress &&
                            !address.isLinkLocalAddress &&
                            address.hostAddress !in upstreamAddresses
                    }
                    .forEach(addresses::add)
            }

        return addresses
            .distinctBy { it.hostAddress }
            .sortedBy { it.hostAddress }
            .map { "http://${it.hostAddress}:$PORT/" }
    }
}
