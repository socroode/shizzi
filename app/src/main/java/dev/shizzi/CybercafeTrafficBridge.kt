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

data class LiveTrafficSnapshot(
    val clients: List<LiveClientTraffic> = emptyList(),
    val attribution: AttributionDiagnostics = AttributionDiagnostics(),
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
    )
}

/**
 * Applies persisted account state to the userspace datapath without making the
 * datapath the source of truth. CybercafeStore remains authoritative.
 *
 * Client attribution remains disabled until captive authentication is wired in;
 * this preserves the original fork's open-hotspot networking during rebuild.
 */
suspend fun TetherClient.applyCybercafePolicies(state: CybercafeState, nowMillis: Long) {
    setGlobalTrafficPolicy(0L, 0L, 0L)
    setDefaultClientTrafficPolicy(0L, 0L, 0L, false)
    setRequireClientAttribution(false)

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
