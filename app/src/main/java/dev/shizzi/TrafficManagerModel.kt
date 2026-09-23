package dev.shizzi

import org.json.JSONArray
import org.json.JSONObject

data class ClientPolicySetting(
    val name: String = "",
    val downloadMbps: Int = 0,
    val uploadMbps: Int = 0,
    val quotaBytes: Long = 0,
    val monthlyQuotaBytes: Long = 0,
    val blocked: Boolean = false,
    val blockOnQuota: Boolean = false,
)

data class MonthlyUsageRecord(
    val month: String,
    val bytes: Long,
    val sessionKey: String = "",
    val lastSessionBytes: Long = 0,
)

data class ClientTrafficStats(
    val ip: String,
    val deviceId: String,
    val macAddress: String,
    val upBytes: Long,
    val downBytes: Long,
    val lastSeenUnixMillis: Long,
    val downloadBps: Long,
    val uploadBps: Long,
    val quotaBytes: Long,
    val monthlyQuotaBytes: Long,
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
    val sharedUpBytes: Long = 0,
    val sharedDownBytes: Long = 0,
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
        sharedUpBytes = root.optLong("sharedUpBytes"),
        sharedDownBytes = root.optLong("sharedDownBytes"),
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
                    deviceId = item.optString("deviceId").ifBlank { item.optString("ip") },
                    macAddress = item.optString("macAddress").takeUnless { it == "null" }.orEmpty(),
                    upBytes = item.optLong("upBytes"),
                    downBytes = item.optLong("downBytes"),
                    lastSeenUnixMillis = item.optLong("lastSeenUnixMillis"),
                    downloadBps = item.optLong("downloadBps"),
                    uploadBps = item.optLong("uploadBps"),
                    quotaBytes = item.optLong("quotaBytes"),
                    monthlyQuotaBytes = item.optLong("monthlyQuotaBytes"),
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
                    put("name", policy.name)
                    put("downloadMbps", policy.downloadMbps)
                    put("uploadMbps", policy.uploadMbps)
                    put("quotaBytes", policy.quotaBytes)
                    put("monthlyQuotaBytes", policy.monthlyQuotaBytes)
                    put("blocked", policy.blocked)
                    put("blockOnQuota", policy.blockOnQuota)
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
                    name = item.optString("name"),
                    downloadMbps = item.optInt("downloadMbps"),
                    uploadMbps = item.optInt("uploadMbps"),
                    quotaBytes = item.optLong("quotaBytes"),
                    monthlyQuotaBytes = item.optLong("monthlyQuotaBytes"),
                    // v0.7.1 used "blocked" for the quota switch. Migrate that
                    // meaning so existing users are not permanently blocked.
                    blocked = if (item.has("blockOnQuota")) item.optBoolean("blocked") else false,
                    blockOnQuota = if (item.has("blockOnQuota")) {
                        item.optBoolean("blockOnQuota")
                    } else {
                        item.optBoolean("blocked")
                    },
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


fun encodeMonthlyUsage(records: Map<String, MonthlyUsageRecord>): String =
    JSONArray().apply {
        records.toSortedMap().forEach { (deviceId, record) ->
            put(
                JSONObject().apply {
                    put("deviceId", deviceId)
                    put("month", record.month)
                    put("bytes", record.bytes)
                    put("sessionKey", record.sessionKey)
                    put("lastSessionBytes", record.lastSessionBytes)
                },
            )
        }
    }.toString()

fun decodeMonthlyUsage(raw: String?): Map<String, MonthlyUsageRecord> {
    val array = runCatching { JSONArray(raw.orEmpty()) }.getOrNull() ?: return emptyMap()

    return buildMap {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val deviceId = item.optString("deviceId").lowercase()
            val month = item.optString("month")
            if (deviceId.isBlank() || month.isBlank()) continue

            put(
                deviceId,
                MonthlyUsageRecord(
                    month = month,
                    bytes = item.optLong("bytes").coerceAtLeast(0L),
                    sessionKey = item.optString("sessionKey"),
                    lastSessionBytes = item.optLong("lastSessionBytes").coerceAtLeast(0L),
                ),
            )
        }
    }
}
