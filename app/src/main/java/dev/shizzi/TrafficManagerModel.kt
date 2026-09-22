package dev.shizzi

import org.json.JSONArray
import org.json.JSONObject

data class ClientPolicySetting(
    val downloadMbps: Int = 0,
    val uploadMbps: Int = 0,
    val quotaBytes: Long = 0,
    val blocked: Boolean = false,
)

data class ClientTrafficStats(
    val ip: String,
    val upBytes: Long,
    val downBytes: Long,
    val lastSeenUnixMillis: Long,
    val downloadBps: Long,
    val uploadBps: Long,
    val quotaBytes: Long,
    val blocked: Boolean,
    val quotaReached: Boolean,
) {
    val totalBytes: Long get() = upBytes + downBytes
}

data class ManagerTrafficStats(
    val globalDownloadBps: Long = 0,
    val globalUploadBps: Long = 0,
    val globalQuotaBytes: Long = 0,
    val totalUpBytes: Long = 0,
    val totalDownBytes: Long = 0,
    val clients: List<ClientTrafficStats> = emptyList(),
) {
    val totalBytes: Long get() = totalUpBytes + totalDownBytes
}

fun parseManagerTrafficStats(raw: String?): ManagerTrafficStats {
    val root = runCatching { JSONObject(raw.orEmpty()) }.getOrNull()
        ?: return ManagerTrafficStats()

    val clients = root.optJSONArray("clients")
        ?.let(::parseClients)
        .orEmpty()
        .sortedByDescending(ClientTrafficStats::lastSeenUnixMillis)

    return ManagerTrafficStats(
        globalDownloadBps = root.optLong("globalDownloadBps"),
        globalUploadBps = root.optLong("globalUploadBps"),
        globalQuotaBytes = root.optLong("globalQuotaBytes"),
        totalUpBytes = root.optLong("totalUpBytes"),
        totalDownBytes = root.optLong("totalDownBytes"),
        clients = clients,
    )
}

private fun parseClients(array: JSONArray): List<ClientTrafficStats> =
    buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            add(
                ClientTrafficStats(
                    ip = item.optString("ip"),
                    upBytes = item.optLong("upBytes"),
                    downBytes = item.optLong("downBytes"),
                    lastSeenUnixMillis = item.optLong("lastSeenUnixMillis"),
                    downloadBps = item.optLong("downloadBps"),
                    uploadBps = item.optLong("uploadBps"),
                    quotaBytes = item.optLong("quotaBytes"),
                    blocked = item.optBoolean("blocked"),
                    quotaReached = item.optBoolean("quotaReached"),
                ),
            )
        }
    }

fun encodeClientPolicies(policies: Map<String, ClientPolicySetting>): String =
    JSONArray().apply {
        policies.toSortedMap().forEach { (ip, policy) ->
            put(
                JSONObject().apply {
                    put("ip", ip)
                    put("downloadMbps", policy.downloadMbps)
                    put("uploadMbps", policy.uploadMbps)
                    put("quotaBytes", policy.quotaBytes)
                    put("blocked", policy.blocked)
                },
            )
        }
    }.toString()

fun decodeClientPolicies(raw: String?): Map<String, ClientPolicySetting> {
    val array = runCatching { JSONArray(raw.orEmpty()) }.getOrNull() ?: return emptyMap()

    return buildMap {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val ip = item.optString("ip")
            if (ip.isBlank()) continue

            put(
                ip,
                ClientPolicySetting(
                    downloadMbps = item.optInt("downloadMbps"),
                    uploadMbps = item.optInt("uploadMbps"),
                    quotaBytes = item.optLong("quotaBytes"),
                    blocked = item.optBoolean("blocked"),
                ),
            )
        }
    }
}

fun managerConfigJson(settings: Settings): String =
    JSONObject().apply {
        put("globalDownloadBps", settings.globalDownloadMbps.toLong() * 1_000_000L)
        put("globalUploadBps", settings.globalUploadMbps.toLong() * 1_000_000L)
        put("globalQuotaBytes", settings.globalQuotaBytes)
        put("defaultClientDownloadBps", settings.defaultClientDownloadMbps.toLong() * 1_000_000L)
        put("defaultClientUploadBps", settings.defaultClientUploadMbps.toLong() * 1_000_000L)
        put("defaultClientQuotaBytes", settings.defaultClientQuotaBytes)
        put("clientPolicies", JSONArray(encodeClientPolicies(settings.clientPolicies)))
    }.toString()
