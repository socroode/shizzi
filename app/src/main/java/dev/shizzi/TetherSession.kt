package dev.shizzi

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

enum class SessionState { IDLE, STARTING, ACTIVE, ERROR }

class TetherSession(private val context: Context) {

    private val testNetworkApi = TestNetworkApi(context)
    private val inspector = UpstreamInspector()

    private var resources: SessionResources? = null
    private var state = SessionState.IDLE
    private var detail = "not started"
    private var interfaceName: String? = null

    private var activeSince: Long = 0
    private var vpnMode = VpnMode.AUTO
    private var watchdog: SessionWatchdog? = null
    private val teardown = SessionTeardown(context)
    private val downstream = DownstreamInspector()
    private val clientIdentities = ClientIdentityInspector()
    private val tetheredClients = TetheredClientsInspector(context)
    private val vpn = VpnUpstream(context) { problem -> tearDownAfter(problem) }

    val isActive: Boolean get() = state == SessionState.ACTIVE

    fun start(mode: VpnMode = VpnMode.AUTO, managerConfigJson: String = ""): String {
        if (isActive) return status()

        vpnMode = mode
        state = SessionState.STARTING
        SessionLog.info("session start requested (vpn mode ${mode.name.lowercase()})")

        SessionLog.info(
            "device: ${Build.MANUFACTURER} ${Build.MODEL}, " +
                "android ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT}), " +
                "contract ${TetherService.CONTRACT_VERSION}",
        )

        return runCatching { bringUp(managerConfigJson) }
            .getOrElse { failure ->
                Log.e(TAG, "start failed", failure)
                SessionLog.error(
                    "start failed: ${failure.javaClass.simpleName}: ${failure.message}",
                )
                stop()
                state = SessionState.ERROR
                detail = "${failure.javaClass.simpleName}: ${failure.message}"
                status()
            }
    }

    private fun bringUp(managerConfigJson: String): String {
        val group = SessionResources(testNetworkApi, context.connectivityManager())
        resources = group

        val name = group.acquire(tunAddresses(), TEST_NETWORK_DNS_SERVERS, AVAILABILITY_TIMEOUT_MS)
        interfaceName = name
        SessionLog.info("tun up: $name (mtu $TUN_MTU, $TUN_ADDRESS, $TUN_ADDRESS_V6)")

        group.startDatapath(TUN_MTU)
        applyTrafficManagerConfig(group, managerConfigJson)
        SessionLog.info("datapath attached to $name")
        followVpn(group)

        preferTestNetworks()
        restartDownstream()

        verifyUpstream(name)
        SessionLog.info("upstream verified: $name is sole upstream")

        state = SessionState.ACTIVE
        activeSince = System.currentTimeMillis()
        detail = "tethered clients routing through $name"

        teardown.installShutdownHook()
        startWatchdog(name)
        return status()
    }

    private fun applyTrafficManagerConfig(
        group: SessionResources,
        managerConfigJson: String,
    ) {
        val root = runCatching { JSONObject(managerConfigJson) }.getOrNull()
            ?: JSONObject()

        val globalDownloadBps = root.optLong("globalDownloadBps", 40_000_000L)
        val globalUploadBps = root.optLong("globalUploadBps", 5_000_000L)
        val globalQuotaBytes = root.optLong("globalQuotaBytes", 0L)

        group.setGlobalTrafficPolicy(
            globalDownloadBps,
            globalUploadBps,
            globalQuotaBytes,
        )

        group.setDefaultClientPolicy(
            root.optLong("defaultClientDownloadBps", 0L),
            root.optLong("defaultClientUploadBps", 0L),
            root.optLong("defaultClientQuotaBytes", 0L),
        )

        val policies = root.optJSONArray("clientPolicies")
        if (policies != null) {
            for (index in 0 until policies.length()) {
                val item = policies.optJSONObject(index) ?: continue
                val ip = item.optString("ip")
                if (ip.isBlank()) continue

                group.setClientPolicy(
                    ip = ip,
                    downloadBps = item.optLong("downloadMbps", 0L) * 1_000_000L,
                    uploadBps = item.optLong("uploadMbps", 0L) * 1_000_000L,
                    quotaBytes = item.optLong("quotaBytes", 0L),
                    blocked = item.optBoolean("blocked", false),
                )
            }
        }

        SessionLog.info(
            "traffic manager: global " +
                "${globalDownloadBps / 1_000_000} Mbps down / " +
                "${globalUploadBps / 1_000_000} Mbps up; " +
                "quota=$globalQuotaBytes bytes",
        )
    }

    fun trafficStats(): String {
        val raw = resources?.trafficStatsJson() ?: return "{}"
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return raw
        val rawClients = root.optJSONArray("clients") ?: JSONArray()
        val devices = tetheredClients.snapshot()
        val identities = clientIdentities.byIp()

        if (devices.isEmpty()) {
            for (index in 0 until rawClients.length()) {
                val item = rawClients.optJSONObject(index) ?: continue
                val ip = item.optString("ip")
                val identity = identities[ip]

                when (identity) {
                    null -> {
                        item.put("deviceId", ip)
                        item.put("macAddress", JSONObject.NULL)
                    }
                    else -> {
                        item.put("deviceId", identity.deviceId)
                        item.put("macAddress", identity.mac)
                    }
                }
            }
            root.put("physicalClientCount", rawClients.length())
            return root.toString()
        }

        val deviceByMac = devices.associateBy { it.deviceId }
        val ipToMac = mutableMapOf<String, String>()

        devices.forEach { device ->
            device.addresses.forEach { address ->
                ipToMac[address] = device.deviceId
            }
        }
        identities.forEach { (ip, identity) ->
            val mac = identity.deviceId
            if (deviceByMac.containsKey(mac)) ipToMac[ip] = mac
        }

        data class Aggregate(
            val device: TetheredDevice,
            var up: Long = 0,
            var down: Long = 0,
            var lastSeen: Long = 0,
            var downloadBps: Long = 0,
            var uploadBps: Long = 0,
            var quotaBytes: Long = 0,
            var blocked: Boolean = false,
            var quotaReached: Boolean = false,
            var firstObservedIp: String = "",
        )

        val aggregates = devices.associate { it.deviceId to Aggregate(it) }.toMutableMap()
        val soleDeviceId = devices.singleOrNull()?.deviceId

        for (index in 0 until rawClients.length()) {
            val item = rawClients.optJSONObject(index) ?: continue
            val ip = item.optString("ip")
            val mac = ipToMac[ip] ?: soleDeviceId ?: continue
            val aggregate = aggregates[mac] ?: continue

            aggregate.up += item.optLong("upBytes")
            aggregate.down += item.optLong("downBytes")
            aggregate.lastSeen = maxOf(
                aggregate.lastSeen,
                item.optLong("lastSeenUnixMillis"),
            )
            aggregate.downloadBps = item.optLong("downloadBps", aggregate.downloadBps)
            aggregate.uploadBps = item.optLong("uploadBps", aggregate.uploadBps)
            aggregate.quotaBytes = item.optLong("quotaBytes", aggregate.quotaBytes)
            aggregate.blocked = aggregate.blocked || item.optBoolean("blocked")
            aggregate.quotaReached =
                aggregate.quotaReached || item.optBoolean("quotaReached")
            if (aggregate.firstObservedIp.isBlank()) aggregate.firstObservedIp = ip
        }

        if (soleDeviceId != null) {
            val aggregate = aggregates[soleDeviceId]
            if (aggregate != null) {
                aggregate.up += root.optLong("sharedUpBytes")
                aggregate.down += root.optLong("sharedDownBytes")
                if (aggregate.lastSeen == 0L && aggregate.up + aggregate.down > 0) {
                    aggregate.lastSeen = System.currentTimeMillis()
                }
            }
        }

        val physicalClients = JSONArray()
        aggregates.values.forEach { aggregate ->
            val preferredIp = aggregate.device.addresses
                .firstOrNull { it.contains('.') }
                ?: aggregate.firstObservedIp
                ?: aggregate.device.addresses.firstOrNull().orEmpty()

            physicalClients.put(
                JSONObject().apply {
                    put("ip", preferredIp)
                    put("deviceId", aggregate.device.deviceId)
                    put("macAddress", aggregate.device.macAddress)
                    put("upBytes", aggregate.up)
                    put("downBytes", aggregate.down)
                    put("lastSeenUnixMillis", aggregate.lastSeen)
                    put("downloadBps", aggregate.downloadBps)
                    put("uploadBps", aggregate.uploadBps)
                    put("quotaBytes", aggregate.quotaBytes)
                    put("blocked", aggregate.blocked)
                    put("quotaReached", aggregate.quotaReached)
                },
            )
        }

        root.put("clients", physicalClients)
        root.put("physicalClientCount", devices.size)
        root.put(
            "unattributedSharedBytes",
            if (devices.size > 1) {
                root.optLong("sharedUpBytes") + root.optLong("sharedDownBytes")
            } else {
                0L
            },
        )
        return root.toString()
    }

    fun setGlobalTrafficPolicy(downloadBps: Long, uploadBps: Long, quotaBytes: Long) {
        resources?.setGlobalTrafficPolicy(downloadBps, uploadBps, quotaBytes)
    }

    fun setClientTrafficPolicy(
        ip: String,
        downloadBps: Long,
        uploadBps: Long,
        quotaBytes: Long,
        blocked: Boolean,
    ) {
        if (ip.isBlank()) return
        val group = resources ?: return

        val devices = tetheredClients.snapshot()
        val identities = clientIdentities.byIp()

        val target = devices.firstOrNull { device ->
            ip in device.addresses ||
                identities[ip]?.deviceId == device.deviceId
        } ?: devices.singleOrNull()

        if (target == null) {
            group.setClientPolicy(ip, downloadBps, uploadBps, quotaBytes, blocked)
            return
        }

        val routedIps = buildSet {
            addAll(target.addresses)
            identities.forEach { (candidateIp, identity) ->
                if (identity.deviceId == target.deviceId) add(candidateIp)
            }
            add(ip)
        }

        routedIps
            .filter { it.isNotBlank() }
            .forEach { routedIp ->
                group.setClientPolicy(
                    routedIp,
                    downloadBps,
                    uploadBps,
                    quotaBytes,
                    blocked,
                )
            }

        if (devices.size == 1) {
            group.setSharedPolicy(
                downloadBps,
                uploadBps,
                quotaBytes,
                blocked,
            )
        }
    }

    fun resetTrafficStats() {
        resources?.resetTrafficStats()
    }

    private fun followVpn(group: SessionResources) {
        if (vpnMode == VpnMode.NEVER) {
            SessionLog.info("vpn mode never: datapath left unbound")
            return
        }

        check(vpnMode != VpnMode.ALWAYS || vpn.isVpnPresent()) {
            "followVpn: vpn mode always requires an active VPN, none is connected"
        }
        vpn.follow(group)
    }

    private fun startWatchdog(name: String) {
        val guard = SessionWatchdog(
            expectedInterface = name,
            onRecover = { problem -> recoverUpstream(name, problem) },
            onDrift = { problem ->
                SessionLog.warn("upstream drift: $problem")
                tearDownAfter(problem)
            },
        )
        watchdog = guard
        guard.start()
    }

    private fun tearDownAfter(problem: String) {
        stop()

        val teardownProblem = detail.takeIf { state == SessionState.ERROR }
        state = SessionState.ERROR
        detail = when (teardownProblem) {
            null -> problem
            else -> "$problem; teardown incomplete: $teardownProblem"
        }
    }

    private fun preferTestNetworks() {
        val api = TetheringPreferenceApi(context)
        runCatching { api.setPreferTestNetworks(false) }
            .onFailure { SessionLog.warn("could not clear the stale test-network preference: ${it.message}") }
        api.setPreferTestNetworks(true)
    }

    private fun recoverUpstream(name: String, problem: String): Boolean {
        if (!isActive || interfaceName != name) return false

        SessionLog.warn("watchdog recovery starting for $name: $problem")

        val preferenceReset = runCatching { preferTestNetworks() }
            .onFailure { SessionLog.warn("recovery preference reset failed: ${it.message}") }
            .isSuccess

        if (preferenceReset && awaitOwnedUpstream(name, RECOVERY_PREFERENCE_WAIT_MS)) {
            SessionLog.info("watchdog recovery: preference reset restored $name")
            return true
        }

        SessionLog.warn("watchdog recovery: restarting hotspot to force upstream reselection")

        val restarted = runCatching {
            restartDownstream()
            preferTestNetworks()
        }.onFailure {
            SessionLog.warn("watchdog recovery hotspot restart failed: ${it.message}")
        }.isSuccess

        if (!restarted) return false

        val recovered = awaitOwnedUpstream(name, RECOVERY_RESTART_WAIT_MS)
        when {
            recovered -> SessionLog.info("watchdog recovery: hotspot restart restored $name")
            else -> SessionLog.warn("watchdog recovery: $name was not restored")
        }
        return recovered
    }

    private fun awaitOwnedUpstream(name: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            val observed = runCatching { liveUpstreams(name) }.getOrDefault(emptyList())
            if (observed.isNotEmpty() && observed.all { it == name }) return true
            Thread.sleep(RECOVERY_POLL_MS)
        }
        return false
    }

    private fun restartDownstream() {
        val control = DownstreamControl(context)
        control.stopWifiTethering()

        val (didStart, startDetail) = control.startWifiTethering()
        check(didStart) { "restartDownstream: hotspot did not start ($startDetail)" }

        awaitDownstreamTethered()
    }

    private fun awaitDownstreamTethered() {
        val deadline = System.currentTimeMillis() + DOWNSTREAM_SETTLE_MS
        val downstream = DownstreamInspector()

        while (System.currentTimeMillis() < deadline) {

            if (downstream.findTetheredDownstream() != null) {
                SessionLog.info("downstream tethered")
                return
            }
            Thread.sleep(DOWNSTREAM_POLL_MS)
        }

        SessionLog.warn("downstream not tethered after ${DOWNSTREAM_SETTLE_MS}ms; continuing")
    }

    private fun verifyUpstream(name: String) {
        val deadline = System.currentTimeMillis() + UPSTREAM_SETTLE_MS
        var observed = liveUpstreams(name)

        while (System.currentTimeMillis() < deadline) {
            if (observed.isNotEmpty() && observed.all { it == name }) return
            Thread.sleep(UPSTREAM_POLL_MS)
            observed = liveUpstreams(name)
        }
        error("verifyUpstream: expected only $name, tethering reports $observed")
    }

    private fun liveUpstreams(owned: String): List<String> =
        inspector.observe().liveInterfaceNames(owned)

    fun stop(): String {
        watchdog?.stop()
        watchdog = null
        vpn.stop()
        teardown.removeShutdownHook()

        val summary = if (activeSince == 0L) null else sessionSummary()

        val downstreamProblem = teardown.releaseDownstream()

        teardown.releaseUpstreamSelection(interfaceName)

        val releaseProblems = resources?.release().orEmpty()
        resources = null

        interfaceName = null
        activeSince = 0
        state = if (downstreamProblem == null) SessionState.IDLE else SessionState.ERROR
        detail = downstreamProblem ?: "stopped"

        when (downstreamProblem) {
            null -> SessionLog.info("session stopped; downstream confirmed down")
            else -> SessionLog.error("teardown: $downstreamProblem")
        }
        if (releaseProblems.isNotEmpty()) {
            SessionLog.warn("${releaseProblems.size} resource(s) not released cleanly")
        }
        summary?.let(SessionLog::info)
        return status()
    }

    private fun sessionSummary(): String {
        val traffic = interfaceName?.let(InterfaceCounters::read) ?: Traffic()
        val elapsed = when (activeSince) {
            0L -> 0L
            else -> System.currentTimeMillis() - activeSince
        }

        return "session summary: active ${formatDuration(elapsed)}, " +
            "${Traffic.format(traffic.up)} up, ${Traffic.format(traffic.down)} down"
    }

    private fun formatDuration(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    fun status(): String = JSONObject().apply {
        put("state", state.name)
        put("detail", detail)
        put("interface", interfaceName ?: JSONObject.NULL)

        put("isVpnBound", vpn.isBound)
        put("isVpnBypassed", isVpnBypassed())

        val traffic = interfaceName?.let(InterfaceCounters::read) ?: Traffic()
        put("bytesUp", traffic.up)
        put("bytesDown", traffic.down)
        put("clientCount", if (isActive) downstream.countDevices() else 0)
        put("trafficManager", JSONObject(trafficStats()))
    }.toString()

    private fun isVpnBypassed(): Boolean {
        if (vpnMode != VpnMode.NEVER) return false
        if (!isActive) return false

        return vpn.isVpnPresent()
    }

    private fun tunAddresses() = listOf(
        buildLinkAddress(java.net.InetAddress.getByName(TUN_ADDRESS), TUN_PREFIX_LENGTH),
        buildLinkAddress(java.net.InetAddress.getByName(TUN_ADDRESS_V6), TUN_PREFIX_LENGTH_V6),
    )

    private companion object {
        const val TAG = "TetherSession"
        const val TUN_ADDRESS = "192.0.2.2"
        const val TUN_PREFIX_LENGTH = 24

        const val TUN_ADDRESS_V6 = "2001:db8::2"
        const val TUN_PREFIX_LENGTH_V6 = 64

        const val TUN_MTU = 1500
        const val AVAILABILITY_TIMEOUT_MS = 10_000
        const val UPSTREAM_SETTLE_MS = 8_000L
        const val UPSTREAM_POLL_MS = 500L

        const val DOWNSTREAM_SETTLE_MS = 10_000L
        const val DOWNSTREAM_POLL_MS = 500L

        const val RECOVERY_PREFERENCE_WAIT_MS = 4_000L
        const val RECOVERY_RESTART_WAIT_MS = 8_000L
        const val RECOVERY_POLL_MS = 500L
    }
}
