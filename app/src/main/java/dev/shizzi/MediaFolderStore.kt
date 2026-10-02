package dev.shizzi

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

data class MediaFolderConfig(
    val id: String,
    val name: String,
    val kind: MediaKind,
    val treeUri: String?,
    val enabled: Boolean = true,
    val allowedAccounts: Set<String> = emptySet(),
) {
    fun uri(): Uri? = treeUri?.takeIf { it.isNotBlank() }?.let(Uri::parse)

    fun visibleTo(accountNumber: String?): Boolean {
        if (!enabled) return false
        if (allowedAccounts.isEmpty()) return true
        val normalized = normalizeMediaAccount(accountNumber)
        return normalized.isNotEmpty() && normalized in allowedAccounts
    }
}

fun normalizeMediaAccount(value: String?): String =
    value.orEmpty().trim().uppercase(Locale.US)

data class MediaAccountOption(
    val number: String,
    val name: String,
    val enabled: Boolean,
) {
    val label: String
        get() = if (name.isBlank()) number else "$name · $number"
}

fun mediaAccountOptions(state: CybercafeState): List<MediaAccountOption> =
    state.accounts.values
        .map { account ->
            MediaAccountOption(
                number = normalizeMediaAccount(account.number),
                name = account.name.trim(),
                enabled = account.enabled,
            )
        }
        .filter { it.number.isNotBlank() }
        .sortedWith(
            compareBy<MediaAccountOption>(
                { it.name.lowercase(Locale.getDefault()) },
                { it.number },
            ),
        )

object MediaFolderStore {
    const val MAX_FOLDERS = 10
    private const val PREFS = "shizzi_media"
    private const val KEY_FOLDERS = "folders_v2"

    fun isConfigured(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY_FOLDERS)

    fun load(context: Context): List<MediaFolderConfig> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_FOLDERS, null)
        if (!raw.isNullOrBlank()) {
            return decode(raw).take(MAX_FOLDERS)
        }

        val migrated = MediaKind.entries.mapNotNull { kind ->
            MediaPrefs.treeUri(context, kind)?.let { uri ->
                MediaFolderConfig(
                    id = "legacy-${kind.key}",
                    name = kind.label,
                    kind = kind,
                    treeUri = uri.toString(),
                )
            }
        }
        if (migrated.isNotEmpty()) save(context, migrated)
        return migrated
    }

    fun save(context: Context, folders: List<MediaFolderConfig>) {
        require(folders.size <= MAX_FOLDERS) { "Maximum $MAX_FOLDERS dossiers Media" }
        val sanitized = folders
            .distinctBy { it.id }
            .take(MAX_FOLDERS)
            .map(::sanitize)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FOLDERS, encode(sanitized))
            .apply()
    }

    fun upsert(context: Context, folder: MediaFolderConfig) {
        val current = load(context).toMutableList()
        val clean = sanitize(folder)
        val index = current.indexOfFirst { it.id == clean.id }
        if (index >= 0) {
            current[index] = clean
        } else {
            require(current.size < MAX_FOLDERS) { "Maximum $MAX_FOLDERS dossiers Media" }
            current += clean
        }
        save(context, current)
    }

    fun remove(context: Context, id: String) {
        save(context, load(context).filterNot { it.id == id })
    }

    fun create(
        name: String,
        kind: MediaKind = MediaKind.FILMS,
    ): MediaFolderConfig = MediaFolderConfig(
        id = UUID.randomUUID().toString(),
        name = name.trim().ifBlank { "Dossier Media" },
        kind = kind,
        treeUri = null,
    )

    fun byId(context: Context, id: String?): MediaFolderConfig? =
        id?.let { wanted -> load(context).firstOrNull { it.id == wanted } }

    fun visibleTo(context: Context, accountNumber: String?): List<MediaFolderConfig> =
        load(context).filter { it.visibleTo(accountNumber) }

    fun canAccess(context: Context, folderId: String?, accountNumber: String?): Boolean {
        val folder = byId(context, folderId) ?: return false
        return folder.visibleTo(accountNumber)
    }

    internal fun canAccess(
        folder: MediaFolderConfig,
        accountNumber: String?,
    ): Boolean = folder.visibleTo(accountNumber)

    internal fun visibleFromSnapshot(
        folders: List<MediaFolderConfig>,
        accountNumber: String?,
    ): List<MediaFolderConfig> = folders.filter { it.visibleTo(accountNumber) }

    internal fun canAccessSnapshot(
        folders: List<MediaFolderConfig>,
        folderId: String?,
        accountNumber: String?,
    ): Boolean {
        if (folderId.isNullOrBlank()) return false
        val folder = folders.firstOrNull { it.id == folderId } ?: return false
        return folder.visibleTo(accountNumber)
    }

    private fun sanitize(folder: MediaFolderConfig): MediaFolderConfig {
        val id = folder.id.trim().ifBlank { UUID.randomUUID().toString() }
        val name = folder.name.trim().take(60).ifBlank { "Dossier Media" }
        val accounts = folder.allowedAccounts
            .map(::normalizeMediaAccount)
            .filter { it.isNotBlank() }
            .toSortedSet()
        return folder.copy(id = id, name = name, allowedAccounts = accounts)
    }

    internal fun encodeSnapshot(folders: List<MediaFolderConfig>): String =
        encode(folders)

    internal fun decodeSnapshot(raw: String): List<MediaFolderConfig> =
        decode(raw).take(MAX_FOLDERS)

    private fun encode(folders: List<MediaFolderConfig>): String =
        JSONArray().apply {
            folders.forEach { folder ->
                put(JSONObject().apply {
                    put("id", folder.id)
                    put("name", folder.name)
                    put("kind", folder.kind.key)
                    put("treeUri", folder.treeUri ?: JSONObject.NULL)
                    put("enabled", folder.enabled)
                    put("allowedAccounts", JSONArray(folder.allowedAccounts.toList()))
                })
            }
        }.toString()

    private fun decode(raw: String): List<MediaFolderConfig> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val kind = MediaKind.fromKey(item.optString("kind")) ?: MediaKind.FILMS
                val accounts = buildSet {
                    val values = item.optJSONArray("allowedAccounts") ?: JSONArray()
                    for (j in 0 until values.length()) {
                        normalizeMediaAccount(values.optString(j))
                            .takeIf { it.isNotBlank() }
                            ?.let(::add)
                    }
                }
                add(
                    sanitize(
                        MediaFolderConfig(
                            id = item.optString("id"),
                            name = item.optString("name"),
                            kind = kind,
                            treeUri = if (item.isNull("treeUri")) null else item.optString("treeUri"),
                            enabled = item.optBoolean("enabled", true),
                            allowedAccounts = accounts,
                        ),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())
}
