package dev.shizzi

import org.json.JSONArray
import org.json.JSONObject

enum class ClientPriority(val weight: Int) {
    NORMAL(1),
    PRIORITY(2),
    VIP(3),
}

data class ClientPolicySetting(
    val name: String = "",
    val downloadMbps: Int = 0,
    val uploadMbps: Int = 0,
    val quotaBytes: Long = 0,
    val monthlyQuotaBytes: Long = 0,
    val priority: ClientPriority = ClientPriority.NORMAL,
    val blocked: Boolean = false,
    val blockOnQuota: Boolean = false,
    val pausedUntilMillis: Long = 0,
)

data class MonthlyUsageRecord(
    val month: String,
    val bytes: Long,
    val sessionKey: String = "",
    val lastSessionBytes: Long = 0,
    val day: String = "",
    val dayBytes: Long = 0,
    val week: String = "",
    val weekBytes: Long = 0,
    val totalBytes: Long = 0,
    val lastSeenUnixMillis: Long = 0,
)

data class ConnectionEvent(
    val deviceId: String,
    val connected: Boolean,
    val atUnixMillis: Long,
)


enum class RateUnit(val label: String, val multiplier: Long) {
    KBPS("kbps", 1_000L),
    MBPS("Mbps", 1_000_000L),
}

enum class DataUnit(val label: String, val multiplier: Long) {
    MB("MB", 1_000_000L),
    GB("GB", 1_000_000_000L),
}

enum class DurationUnit(val label: String, val minutes: Long) {
    MINUTES("min", 1L),
    HOURS("hours", 60L),
    DAYS("days", 1_440L),
}

data class VoucherTemplate(
    val id: String,
    val name: String,
    val downloadValue: Int,
    val downloadUnit: RateUnit,
    val uploadValue: Int,
    val uploadUnit: RateUnit,
    val quotaValue: Long,
    val quotaUnit: DataUnit,
    val durationValue: Long,
    val durationUnit: DurationUnit,
    val createdAtMillis: Long = 0L,
) {
    val downloadBps: Long get() = downloadValue.toLong().coerceAtLeast(0L) * downloadUnit.multiplier
    val uploadBps: Long get() = uploadValue.toLong().coerceAtLeast(0L) * uploadUnit.multiplier
    val quotaBytes: Long get() = quotaValue.coerceAtLeast(0L) * quotaUnit.multiplier
    val durationMinutes: Long get() = durationValue.coerceAtLeast(0L) * durationUnit.minutes
}

data class AccessPass(
    val code: String,
    val name: String = "",
    val downloadBps: Long = 0L,
    val uploadBps: Long = 0L,
    val downloadUnit: RateUnit = RateUnit.MBPS,
    val uploadUnit: RateUnit = RateUnit.MBPS,
    val quotaBytes: Long = 0L,
    val quotaUnit: DataUnit = DataUnit.GB,
    val durationMinutes: Long = 0L,
    val durationUnit: DurationUnit = DurationUnit.DAYS,
    val createdAtMillis: Long = 0,
    // Legacy device fields are retained only for migration from older builds.
    val assignedDeviceId: String = "",
    val activatedAtMillis: Long = 0,
    val startTotalBytes: Long = 0,
    val usedBytes: Long = 0,
    val lastAuthorizationStartedAtMillis: Long = 0,
    val lastAuthorizationSessionBytes: Long = 0,
    val enabled: Boolean = true,
) {
    fun expiresAtMillis(): Long = when {
        activatedAtMillis <= 0L || durationMinutes <= 0L -> 0L
        else -> activatedAtMillis + durationMinutes * 60_000L
    }

    fun isExpired(nowMillis: Long): Boolean {
        val expires = expiresAtMillis()
        return expires > 0L && nowMillis >= expires
    }

    fun downloadDisplayValue(): Long = when (downloadUnit) {
        RateUnit.KBPS -> downloadBps / 1_000L
        RateUnit.MBPS -> downloadBps / 1_000_000L
    }

    fun uploadDisplayValue(): Long = when (uploadUnit) {
        RateUnit.KBPS -> uploadBps / 1_000L
        RateUnit.MBPS -> uploadBps / 1_000_000L
    }
}

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

data class PortalClaim(
    val ip: String,
    val code: String,
    val claimedAtMillis: Long,
)

data class PortalAuthorizationStatus(
    val ip: String,
    val code: String,
    val startedAtMillis: Long,
    val sessionUsedBytes: Long,
)

data class ManagerTrafficStats(
    val globalDownloadBps: Long = 0,
    val globalUploadBps: Long = 0,
    val globalQuotaBytes: Long = 0,
    val totalUpBytes: Long = 0,
    val totalDownBytes: Long = 0,
    val sharedUpBytes: Long = 0,
    val sharedDownBytes: Long = 0,
    val clients: List<ClientTrafficStats> = emptyList(),
    val portalClaims: List<PortalClaim> = emptyList(),
    val portalAuthorizations: List<PortalAuthorizationStatus> = emptyList(),
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
        portalClaims = root.optJSONArray("portalClaims")?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        PortalClaim(
                            ip = item.optString("ip"),
                            code = item.optString("code"),
                            claimedAtMillis = item.optLong("claimedAtMillis"),
                        ),
                    )
                }
            }
        }.orEmpty(),
        portalAuthorizations = root.optJSONArray("portalAuthorizations")?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        PortalAuthorizationStatus(
                            ip = item.optString("ip"),
                            code = item.optString("code").trim().uppercase(),
                            startedAtMillis = item.optLong("startedAtMillis"),
                            sessionUsedBytes = item.optLong("sessionUsedBytes").coerceAtLeast(0L),
                        ),
                    )
                }
            }
        }.orEmpty(),
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
                    put("priority", policy.priority.name)
                    put("blocked", policy.blocked)
                    put("blockOnQuota", policy.blockOnQuota)
                    put("pausedUntilMillis", policy.pausedUntilMillis)
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
                    priority = runCatching {
                        ClientPriority.valueOf(item.optString("priority"))
                    }.getOrDefault(ClientPriority.NORMAL),
                    // v0.7.1 used "blocked" for the quota switch. Migrate that
                    // meaning so existing users are not permanently blocked.
                    blocked = if (item.has("blockOnQuota")) item.optBoolean("blocked") else false,
                    blockOnQuota = if (item.has("blockOnQuota")) {
                        item.optBoolean("blockOnQuota")
                    } else {
                        item.optBoolean("blocked")
                    },
                    pausedUntilMillis = item.optLong("pausedUntilMillis"),
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
        put("defaultClientBlocked", false)
        put("portalRequired", settings.accessPassRequired)
        put("portalConfig", portalConfigJson(settings))
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
                    put("day", record.day)
                    put("dayBytes", record.dayBytes)
                    put("week", record.week)
                    put("weekBytes", record.weekBytes)
                    put("totalBytes", record.totalBytes)
                    put("lastSeenUnixMillis", record.lastSeenUnixMillis)
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
                    day = item.optString("day"),
                    dayBytes = item.optLong("dayBytes").coerceAtLeast(0L),
                    week = item.optString("week"),
                    weekBytes = item.optLong("weekBytes").coerceAtLeast(0L),
                    totalBytes = item.optLong("totalBytes").coerceAtLeast(0L),
                    lastSeenUnixMillis = item.optLong("lastSeenUnixMillis").coerceAtLeast(0L),
                ),
            )
        }
    }
}


fun encodeConnectionHistory(events: List<ConnectionEvent>): String =
    JSONArray().apply {
        events.forEach { event ->
            put(
                JSONObject().apply {
                    put("deviceId", event.deviceId)
                    put("connected", event.connected)
                    put("atUnixMillis", event.atUnixMillis)
                },
            )
        }
    }.toString()

fun decodeConnectionHistory(raw: String?): List<ConnectionEvent> {
    val array = runCatching { JSONArray(raw.orEmpty()) }.getOrNull() ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val deviceId = item.optString("deviceId").lowercase()
            if (deviceId.isBlank()) continue
            add(
                ConnectionEvent(
                    deviceId = deviceId,
                    connected = item.optBoolean("connected"),
                    atUnixMillis = item.optLong("atUnixMillis"),
                ),
            )
        }
    }
}


fun encodeAccessPasses(passes: Map<String, AccessPass>): String =
    JSONArray().apply {
        passes.toSortedMap().forEach { (_, pass) ->
            put(
                JSONObject().apply {
                    put("code", pass.code)
                    put("name", pass.name)
                    put("downloadBps", pass.downloadBps)
                    put("uploadBps", pass.uploadBps)
                    put("downloadUnit", pass.downloadUnit.name)
                    put("uploadUnit", pass.uploadUnit.name)
                    put("quotaBytes", pass.quotaBytes)
                    put("quotaUnit", pass.quotaUnit.name)
                    put("durationMinutes", pass.durationMinutes)
                    put("durationUnit", pass.durationUnit.name)
                    put("createdAtMillis", pass.createdAtMillis)
                    put("assignedDeviceId", pass.assignedDeviceId)
                    put("activatedAtMillis", pass.activatedAtMillis)
                    put("startTotalBytes", pass.startTotalBytes)
                    put("usedBytes", pass.usedBytes)
                    put("lastAuthorizationStartedAtMillis", pass.lastAuthorizationStartedAtMillis)
                    put("lastAuthorizationSessionBytes", pass.lastAuthorizationSessionBytes)
                    put("enabled", pass.enabled)
                },
            )
        }
    }.toString()

fun decodeAccessPasses(raw: String?): Map<String, AccessPass> {
    val array = runCatching { JSONArray(raw.orEmpty()) }.getOrNull() ?: return emptyMap()
    return buildMap {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val code = item.optString("code").trim().uppercase()
            if (code.isBlank()) continue

            val legacyDownload = item.optInt("downloadMbps").coerceAtLeast(0).toLong() * 1_000_000L
            val legacyUpload = item.optInt("uploadMbps").coerceAtLeast(0).toLong() * 1_000_000L
            put(
                code,
                AccessPass(
                    code = code,
                    name = item.optString("name"),
                    downloadBps = if (item.has("downloadBps")) {
                        item.optLong("downloadBps").coerceAtLeast(0L)
                    } else {
                        legacyDownload
                    },
                    uploadBps = if (item.has("uploadBps")) {
                        item.optLong("uploadBps").coerceAtLeast(0L)
                    } else {
                        legacyUpload
                    },
                    downloadUnit = runCatching {
                        RateUnit.valueOf(item.optString("downloadUnit"))
                    }.getOrDefault(RateUnit.MBPS),
                    uploadUnit = runCatching {
                        RateUnit.valueOf(item.optString("uploadUnit"))
                    }.getOrDefault(RateUnit.MBPS),
                    quotaBytes = item.optLong("quotaBytes").coerceAtLeast(0L),
                    quotaUnit = runCatching {
                        DataUnit.valueOf(item.optString("quotaUnit"))
                    }.getOrDefault(DataUnit.GB),
                    durationMinutes = item.optLong("durationMinutes").coerceAtLeast(0L),
                    durationUnit = runCatching {
                        DurationUnit.valueOf(item.optString("durationUnit"))
                    }.getOrDefault(DurationUnit.DAYS),
                    createdAtMillis = item.optLong("createdAtMillis").coerceAtLeast(0L),
                    assignedDeviceId = item.optString("assignedDeviceId").lowercase(),
                    activatedAtMillis = item.optLong("activatedAtMillis").coerceAtLeast(0L),
                    startTotalBytes = item.optLong("startTotalBytes").coerceAtLeast(0L),
                    usedBytes = item.optLong("usedBytes").coerceAtLeast(0L),
                    lastAuthorizationStartedAtMillis =
                        item.optLong("lastAuthorizationStartedAtMillis").coerceAtLeast(0L),
                    lastAuthorizationSessionBytes =
                        item.optLong("lastAuthorizationSessionBytes").coerceAtLeast(0L),
                    enabled = if (item.has("enabled")) item.optBoolean("enabled") else true,
                ),
            )
        }
    }
}

fun encodeVoucherTemplates(templates: Map<String, VoucherTemplate>): String =
    JSONArray().apply {
        templates.values.sortedBy { it.name.lowercase() }.forEach { template ->
            put(
                JSONObject().apply {
                    put("id", template.id)
                    put("name", template.name)
                    put("downloadValue", template.downloadValue)
                    put("downloadUnit", template.downloadUnit.name)
                    put("uploadValue", template.uploadValue)
                    put("uploadUnit", template.uploadUnit.name)
                    put("quotaValue", template.quotaValue)
                    put("quotaUnit", template.quotaUnit.name)
                    put("durationValue", template.durationValue)
                    put("durationUnit", template.durationUnit.name)
                    put("createdAtMillis", template.createdAtMillis)
                },
            )
        }
    }.toString()

fun decodeVoucherTemplates(raw: String?): Map<String, VoucherTemplate> {
    val array = runCatching { JSONArray(raw.orEmpty()) }.getOrNull() ?: return emptyMap()
    return buildMap {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id").trim()
            val name = item.optString("name").trim()
            if (id.isBlank() || name.isBlank()) continue
            put(
                id,
                VoucherTemplate(
                    id = id,
                    name = name,
                    downloadValue = item.optInt("downloadValue").coerceAtLeast(0),
                    downloadUnit = runCatching {
                        RateUnit.valueOf(item.optString("downloadUnit"))
                    }.getOrDefault(RateUnit.MBPS),
                    uploadValue = item.optInt("uploadValue").coerceAtLeast(0),
                    uploadUnit = runCatching {
                        RateUnit.valueOf(item.optString("uploadUnit"))
                    }.getOrDefault(RateUnit.MBPS),
                    quotaValue = item.optLong("quotaValue").coerceAtLeast(0L),
                    quotaUnit = runCatching {
                        DataUnit.valueOf(item.optString("quotaUnit"))
                    }.getOrDefault(DataUnit.GB),
                    durationValue = item.optLong("durationValue").coerceAtLeast(0L),
                    durationUnit = runCatching {
                        DurationUnit.valueOf(item.optString("durationUnit"))
                    }.getOrDefault(DurationUnit.DAYS),
                    createdAtMillis = item.optLong("createdAtMillis").coerceAtLeast(0L),
                ),
            )
        }
    }
}


fun portalConfigJson(settings: Settings): String =
    JSONObject().apply {
        put("title", settings.portalTitle)
        put("message", settings.portalMessage)
        put("html", settings.portalHtml)
        put("passes", JSONArray().apply {
            settings.accessPasses.values
                .sortedBy { it.code }
                .forEach { pass ->
                    put(
                        JSONObject().apply {
                            put("code", pass.code)
                            put("name", pass.name)
                            put("downloadBps", pass.downloadBps)
                            put("uploadBps", pass.uploadBps)
                            put("downloadUnit", pass.downloadUnit.name)
                            put("uploadUnit", pass.uploadUnit.name)
                            put("quotaBytes", pass.quotaBytes)
                            put("quotaUnit", pass.quotaUnit.name)
                            put("durationMinutes", pass.durationMinutes)
                            put("durationUnit", pass.durationUnit.name)
                            put("usedBytes", pass.usedBytes)
                            put("expiresAtMillis", pass.expiresAtMillis())
                            put("enabled", pass.enabled)
                        },
                    )
                }
        })
    }.toString()
