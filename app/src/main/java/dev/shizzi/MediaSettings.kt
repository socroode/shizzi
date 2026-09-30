package dev.shizzi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest
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
    fun scan(context: Context, kind: MediaKind? = null): List<MediaEntry> {
        val kinds = kind?.let(::listOf) ?: MediaKind.entries
        return kinds.flatMap { scanKind(context, it) }
            .sortedWith(compareBy<MediaEntry>({ it.kind.ordinal }, { it.relativePath.lowercase() }))
    }

    private fun scanKind(context: Context, kind: MediaKind): List<MediaEntry> {
        val tree = MediaPrefs.treeUri(context, kind) ?: return emptyList()
        val root = runCatching { DocumentFile.fromTreeUri(context, tree) }.getOrNull() ?: return emptyList()
        if (!root.exists() || !root.isDirectory) return emptyList()

        val out = mutableListOf<MediaEntry>()
        walk(kind, root, "", out)
        return out
    }

    private fun walk(
        kind: MediaKind,
        directory: DocumentFile,
        prefix: String,
        out: MutableList<MediaEntry>,
    ) {
        val children = runCatching { directory.listFiles().toList() }.getOrDefault(emptyList())
            .sortedBy { it.name.orEmpty().lowercase() }

        children.forEach { child ->
            val name = child.name.orEmpty().ifBlank { "Sans titre" }
            val relative = if (prefix.isBlank()) name else "$prefix/$name"
            when {
                child.isDirectory -> walk(kind, child, relative, out)
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
                    )
                }
            }
        }
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
}

object MediaNetwork {
    const val PORT = 8088

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
