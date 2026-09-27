package dev.shizzi

import org.json.JSONArray
import org.json.JSONObject

data class LiveClientTraffic(
    val ip: String,
    val upBytes: Long,
    val downBytes: Long,
    val lastSeenUnixMillis: Long,
    val downloadBps: Long,
    val uploadBps: Long,
    val quotaBytes: Long,
    val blocked: Boolean,
)

data class AttributionDiagnostics(
    val resolvedFlows: Long = 0L,
    val fallbackResolvedFlows: Long = 0L,
    val unresolvedFlows: Long = 0L,
    val mappedClients: Int = 0,
    val lastError: String = "",
    val lastMiss: String = "",
)

data class LivePortalAuthorization(
    val ip: String,
    val accountNumber: String,
    val startedAtMillis: Long,
    val sessionDataUsedBytes: Long,
)

data class LivePortalRechargeClaim(
    val ip: String,
    val accountNumber: String,
    val code: String,
    val claimedAtMillis: Long,
)

data class LiveTrafficSnapshot(
    val clients: List<LiveClientTraffic> = emptyList(),
    val attribution: AttributionDiagnostics = AttributionDiagnostics(),
    val portalAuthorizations: List<LivePortalAuthorization> = emptyList(),
    val portalRechargeClaims: List<LivePortalRechargeClaim> = emptyList(),
)

fun parseLiveTrafficSnapshot(raw: String?): LiveTrafficSnapshot {
    val root = runCatching { JSONObject(raw.orEmpty()) }.getOrNull()
        ?: return LiveTrafficSnapshot()

    val clients = root.optJSONArray("clients")?.let { array ->
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val ip = item.optString("ip")
                if (ip.isBlank()) continue
                add(
                    LiveClientTraffic(
                        ip = ip,
                        upBytes = item.optLong("upBytes"),
                        downBytes = item.optLong("downBytes"),
                        lastSeenUnixMillis = item.optLong("lastSeenUnixMillis"),
                        downloadBps = item.optLong("downloadBps"),
                        uploadBps = item.optLong("uploadBps"),
                        quotaBytes = item.optLong("quotaBytes"),
                        blocked = item.optBoolean("blocked"),
                    ),
                )
            }
        }
    }.orEmpty()

    val authorizations = root.optJSONArray("portalAuthorizations")?.let { array ->
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val ip = item.optString("ip")
                val account = item.optString("accountNumber")
                if (ip.isBlank() || account.isBlank()) continue
                add(
                    LivePortalAuthorization(
                        ip = ip,
                        accountNumber = account,
                        startedAtMillis = item.optLong("startedAtMillis"),
                        sessionDataUsedBytes = item.optLong("sessionDataUsedBytes"),
                    ),
                )
            }
        }
    }.orEmpty()

    val claims = root.optJSONArray("portalRechargeClaims")?.let { array ->
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val account = item.optString("accountNumber")
                val code = item.optString("code")
                if (account.isBlank() || code.isBlank()) continue
                add(
                    LivePortalRechargeClaim(
                        ip = item.optString("ip"),
                        accountNumber = account,
                        code = code,
                        claimedAtMillis = item.optLong("claimedAtMillis"),
                    ),
                )
            }
        }
    }.orEmpty()

    val attribution = root.optJSONObject("attribution")
    return LiveTrafficSnapshot(
        clients = clients,
        attribution = AttributionDiagnostics(
            resolvedFlows = attribution?.optLong("resolvedFlows") ?: 0L,
            fallbackResolvedFlows = attribution?.optLong("fallbackResolvedFlows") ?: 0L,
            unresolvedFlows = attribution?.optLong("unresolvedFlows") ?: 0L,
            mappedClients = attribution?.optInt("mappedClients") ?: 0,
            lastError = attribution?.optString("lastError").orEmpty(),
            lastMiss = attribution?.optString("lastMiss").orEmpty(),
        ),
        portalAuthorizations = authorizations,
        portalRechargeClaims = claims,
    )
}

fun CybercafeState.toPortalConfigJson(): String =
    JSONObject().apply {
        put("title", "Shizzi Hotspot")
        put("message", "Ouvrez votre compte ou rechargez avec un voucher.")
        put(
            "accounts",
            JSONArray().apply {
                accounts.values.sortedBy(PrepaidAccount::number).forEach { account ->
                    val boundIp = devices.values
                        .firstOrNull { it.accountNumber == account.number }
                        ?.ip
                        .orEmpty()
                    put(
                        JSONObject().apply {
                            put("number", account.number)
                            put("name", account.name)
                            put("pinSalt", account.pinSalt)
                            put("pinHash", account.pinHash)
                            put("enabled", account.enabled)
                            put("dataBalanceBytes", account.dataBalanceBytes)
                            put("dataValidUntilMillis", account.dataValidUntilMillis)
                            put("dataDownloadBps", account.dataDownloadBps)
                            put("dataUploadBps", account.dataUploadBps)
                            put("unlimitedUntilMillis", account.unlimitedUntilMillis)
                            put("unlimitedDownloadBps", account.unlimitedDownloadBps)
                            put("unlimitedUploadBps", account.unlimitedUploadBps)
                            put("unlimitedPlanName", account.unlimitedPlanName)
                            put("totalUpBytes", account.totalUpBytes)
                            put("totalDownBytes", account.totalDownBytes)
                            put("boundIp", boundIp)
                        },
                    )
                }
            },
        )
    }.toString()

/**
 * Pushes the durable Android account snapshot into the shell/gVisor datapath.
 * Once at least one account exists, the captive portal is enabled and physical
 * client attribution becomes fail-closed before account/rate/quota decisions.
 */
suspend fun TetherClient.applyCybercafePolicies(state: CybercafeState, nowMillis: Long) {
    val portalRequired = state.accounts.isNotEmpty()

    setGlobalTrafficPolicy(0L, 0L, 0L)
    setDefaultClientTrafficPolicy(0L, 0L, 0L, false)
    setPortalConfig(portalRequired, state.toPortalConfigJson())
    setRequireClientAttribution(portalRequired)

    state.devices.values.forEach { binding ->
        val ip = binding.ip
        if (ip.isBlank()) return@forEach

        val account = state.accounts[binding.accountNumber]
        if (account == null || !account.enabled) {
            setClientTrafficPolicy(ip, 0L, 0L, 0L, true)
            return@forEach
        }

        val hasUnlimited = account.hasUnlimited(nowMillis)
        val hasData = account.hasData(nowMillis)
        val quota = if (!hasUnlimited && hasData) account.dataBalanceBytes else 0L

        setClientTrafficPolicy(
            ip = ip,
            downloadBps = account.currentDownloadBps(nowMillis),
            uploadBps = account.currentUploadBps(nowMillis),
            quotaBytes = quota,
            blocked = !account.hasInternet(nowMillis),
        )
    }
}
