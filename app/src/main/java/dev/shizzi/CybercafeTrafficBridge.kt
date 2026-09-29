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
    val unresolvedTcpFlows: Long = 0L,
    val unresolvedUdpFlows: Long = 0L,
    val refusedIpv6Flows: Long = 0L,
    val refusedUnauthorizedFlows: Long = 0L,
    val looseCandidateFlows: Long = 0L,
    val mappedClients: Int = 0,
    val clientListKnown: Boolean = false,
    val slowestResolveMillis: Long = 0L,
    val dumpCount: Long = 0L,
    val lastDumpMillis: Long = 0L,
    val slowestDumpMillis: Long = 0L,
    val unattributedDnsBytes: Long = 0L,
    val lastError: String = "",
    val lastMiss: String = "",
)

/** One logged-in device (session) as seen by the datapath. */
data class LivePortalAuthorization(
    val ip: String,
    val accountNumber: String,
    val startedAtMillis: Long,
    val sessionDataUsedBytes: Long,
    val mac: String = "",
    val upBytes: Long = 0L,
    val downBytes: Long = 0L,
    val authorized: Boolean = false,
    val downloadBps: Long = 0L,
    val uploadBps: Long = 0L,
)

data class PortalClaimResult(
    val ip: String,
    val code: String,
    val success: Boolean,
    val message: String,
)

data class LivePortalRechargeClaim(
    val ip: String,
    val accountNumber: String,
    val code: String,
    val claimedAtMillis: Long,
)

data class LiveAdminCommand(
    val id: String,
    val ip: String,
    val action: String,
    val paramsJson: String,
    val createdAtMillis: Long,
)

data class AdminCommandResult(
    val id: String,
    val success: Boolean,
    val message: String,
    val payloadJson: String = "",
)

data class LiveTrafficSnapshot(
    val epoch: Long = 0L,
    val accountUsage: List<LiveAccountUsage> = emptyList(),
    val clients: List<LiveClientTraffic> = emptyList(),
    val attribution: AttributionDiagnostics = AttributionDiagnostics(),
    val portalAuthorizations: List<LivePortalAuthorization> = emptyList(),
    val portalRechargeClaims: List<LivePortalRechargeClaim> = emptyList(),
    val adminCommands: List<LiveAdminCommand> = emptyList(),
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
                        mac = item.optString("mac"),
                        upBytes = item.optLong("upBytes"),
                        downBytes = item.optLong("downBytes"),
                        authorized = item.optBoolean("authorized"),
                        downloadBps = item.optLong("downloadBps"),
                        uploadBps = item.optLong("uploadBps"),
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

    val adminCommands = root.optJSONArray("adminCommands")?.let { array ->
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id")
                val action = item.optString("action")
                if (id.isBlank() || action.isBlank()) continue
                add(
                    LiveAdminCommand(
                        id = id,
                        ip = item.optString("ip"),
                        action = action,
                        paramsJson = item.optJSONObject("params")?.toString() ?: "{}",
                        createdAtMillis = item.optLong("createdAtMillis"),
                    ),
                )
            }
        }
    }.orEmpty()

    val usage = root.optJSONArray("accountUsage")?.let { array ->
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val account = item.optString("accountNumber")
                if (account.isBlank()) continue
                add(
                    LiveAccountUsage(
                        accountNumber = account,
                        upBytes = item.optLong("upBytes"),
                        downBytes = item.optLong("downBytes"),
                        dataBytes = item.optLong("dataBytes"),
                    ),
                )
            }
        }
    }.orEmpty()

    val attribution = root.optJSONObject("attribution")
    return LiveTrafficSnapshot(
        epoch = root.optLong("epoch"),
        accountUsage = usage,
        clients = clients,
        attribution = AttributionDiagnostics(
            resolvedFlows = attribution?.optLong("resolvedFlows") ?: 0L,
            fallbackResolvedFlows = attribution?.optLong("fallbackResolvedFlows") ?: 0L,
            unresolvedFlows = attribution?.optLong("unresolvedFlows") ?: 0L,
            unresolvedTcpFlows = attribution?.optLong("unresolvedTcpFlows") ?: 0L,
            unresolvedUdpFlows = attribution?.optLong("unresolvedUdpFlows") ?: 0L,
            refusedIpv6Flows = root.optLong("refusedIpv6Flows"),
            refusedUnauthorizedFlows = root.optLong("refusedUnauthorizedFlows"),
            looseCandidateFlows = attribution?.optLong("looseCandidateFlows") ?: 0L,
            mappedClients = attribution?.optInt("mappedClients") ?: 0,
            clientListKnown = attribution?.optBoolean("clientListKnown") ?: false,
            slowestResolveMillis = attribution?.optLong("slowestResolveMillis") ?: 0L,
            dumpCount = attribution?.optLong("dumpCount") ?: 0L,
            lastDumpMillis = attribution?.optLong("lastDumpMillis") ?: 0L,
            slowestDumpMillis = attribution?.optLong("slowestDumpMillis") ?: 0L,
            unattributedDnsBytes = root.optLong("unattributedDnsBytes"),
            lastError = attribution?.optString("lastError").orEmpty(),
            lastMiss = attribution?.optString("lastMiss").orEmpty(),
        ),
        portalAuthorizations = authorizations,
        portalRechargeClaims = claims,
        adminCommands = adminCommands,
    )
}

fun CybercafeState.toPortalConfigJson(
    epoch: Long = 0L,
    markers: Map<String, AccountMarker> = emptyMap(),
    claimResults: List<PortalClaimResult> = emptyList(),
    adminResults: List<AdminCommandResult> = emptyList(),
): String =
    JSONObject().apply {
        put("admin", JSONObject().apply {
            put("enabled", remoteAdmin.enabled)
            put("username", remoteAdmin.username)
            put("passwordSalt", remoteAdmin.passwordSalt)
            put("passwordHash", remoteAdmin.passwordHash)
            put("downloadBps", remoteAdmin.downloadBps.coerceAtLeast(1_000_000L))
            put("uploadBps", remoteAdmin.uploadBps.coerceAtLeast(1_000_000L))
        })
        put("adminState", JSONObject().apply {
            put("schemaVersion", schemaVersion)
            put("routerName", portal.title)
            put("portal", JSONObject().apply {
                put("title", this@toPortalConfigJson.portal.title)
                put("message", this@toPortalConfigJson.portal.message)
                put("html", this@toPortalConfigJson.portal.html)
            })
            put("accounts", JSONArray().apply {
                accounts.values.sortedBy(PrepaidAccount::number).forEach { account ->
                    put(JSONObject().apply {
                        put("number", account.number)
                        put("name", account.name)
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
                    })
                }
            })
            put("offers", JSONArray().apply {
                offers.values.sortedBy(Offer::name).forEach { offer ->
                    put(JSONObject().apply {
                        put("id", offer.id)
                        put("name", offer.name)
                        put("kind", offer.kind.name)
                        put("downloadBps", offer.downloadBps)
                        put("uploadBps", offer.uploadBps)
                        put("quotaBytes", offer.quotaBytes)
                        put("durationDays", offer.durationDays)
                        put("priceXpf", offer.priceXpf)
                    })
                }
            })
            put("vouchers", JSONArray().apply {
                vouchers.values.sortedByDescending(Voucher::createdAtMillis).forEach { voucher ->
                    put(JSONObject().apply {
                        put("code", voucher.code)
                        put("offerId", voucher.offerId)
                        put("createdAtMillis", voucher.createdAtMillis)
                        put("enabled", voucher.enabled)
                        put("redeemedByAccount", voucher.redeemedByAccount)
                        put("redeemedAtMillis", voucher.redeemedAtMillis)
                        put("snapshotVersion", voucher.snapshotVersion)
                        put("snapshotName", voucher.snapshotName)
                        put("snapshotKind", voucher.snapshotKind.name)
                        put("snapshotDownloadBps", voucher.snapshotDownloadBps)
                        put("snapshotUploadBps", voucher.snapshotUploadBps)
                        put("snapshotQuotaBytes", voucher.snapshotQuotaBytes)
                        put("snapshotDurationDays", voucher.snapshotDurationDays)
                        put("snapshotPriceXpf", voucher.snapshotPriceXpf)
                    })
                }
            })
        })
        put("title", portal.title)
        put("message", portal.message)
        put("html", portal.html)
        put(
            "accounts",
            JSONArray().apply {
                accounts.values.sortedBy(PrepaidAccount::number).forEach { account ->
                    val marker = markers[account.number]
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
                            put("markerEpoch", epoch)
                            put("consumedMarkerBytes", marker?.consumedMarkerBytes ?: 0L)
                            put("usageMarkerBytes", marker?.usageMarkerBytes ?: 0L)
                        },
                    )
                }
            },
        )
        put(
            "claimResults",
            JSONArray().apply {
                claimResults.forEach { result ->
                    put(
                        JSONObject().apply {
                            put("ip", result.ip)
                            put("code", result.code)
                            put("success", result.success)
                            put("message", result.message)
                        },
                    )
                }
            },
        )
        put(
            "adminResults",
            JSONArray().apply {
                adminResults.forEach { result ->
                    put(JSONObject().apply {
                        put("id", result.id)
                        put("success", result.success)
                        put("message", result.message)
                        if (result.payloadJson.isNotBlank()) {
                            put("payload", runCatching { JSONObject(result.payloadJson) }.getOrElse {
                                runCatching { JSONArray(result.payloadJson) }.getOrElse { result.payloadJson }
                            })
                        }
                    })
                }
            },
        )
    }.toString()

/**
 * Pushes the durable Android account snapshot into the shell/gVisor datapath.
 * Once at least one account exists, the captive portal is enabled and physical
 * client attribution becomes fail-closed before account/rate/quota decisions.
 *
 * Every device's rights are derived inside the datapath from its own portal
 * session and that session's account: nothing here is keyed by IP, so no
 * account is ever tied to an address and no device inherits another's access.
 *
 * Must be called from the same loop that applies usage deltas (see
 * [UsageLedger]) so balance and markers describe the same instant.
 */
suspend fun TetherClient.applyCybercafePolicies(
    state: CybercafeState,
    epoch: Long,
    markers: Map<String, AccountMarker>,
    claimResults: List<PortalClaimResult>,
    adminResults: List<AdminCommandResult> = emptyList(),
) {
    val portalRequired = state.accounts.isNotEmpty()

    setGlobalTrafficPolicy(0L, 0L, 0L)
    setDefaultClientTrafficPolicy(0L, 0L, 0L, false)
    // Fail closed on identity before the portal switches on, never after.
    if (portalRequired) setRequireClientAttribution(true)
    setPortalConfig(
        portalRequired,
        state.toPortalConfigJson(epoch, markers, claimResults, adminResults),
    )
    if (!portalRequired) setRequireClientAttribution(false)
}
