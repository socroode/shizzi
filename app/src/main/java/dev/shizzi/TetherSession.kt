package dev.shizzi

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject

enum class SessionState { IDLE, STARTING, ACTIVE, ERROR }

class TetherSession(private val context: Context) {

    private val testNetworkApi = TestNetworkApi(context)
    private val inspector = UpstreamInspector()

    private var resources: SessionResources? = null
    private var presenceMonitor: ClientPresenceMonitor? = null
    private var state = SessionState.IDLE
    private var detail = "not started"
    private var interfaceName: String? = null

    private var activeSince: Long = 0
    private var vpnMode = VpnMode.AUTO
    private var ipv4Only = false
    private var watchdog: SessionWatchdog? = null
    private val teardown = SessionTeardown(context)
    private val downstream = DownstreamInspector()
    private val vpn = VpnUpstream(context) { problem -> tearDownAfter(problem) }

    val isActive: Boolean get() = state == SessionState.ACTIVE

    @Synchronized
    fun start(mode: VpnMode = VpnMode.AUTO, ipv4Only: Boolean = false): String {
        if (isActive) return status()
        // A failed startup may have left a datapath running to protect an
        // incompletely stopped hotspot. Never stack another TUN on top of it.
        if (resources != null || interfaceName != null) {
            SessionLog.warn("cleaning up previous session before restart")
            stop()
            check(resources == null && interfaceName == null) {
                "previous hotspot could not be stopped; refusing to create a second TUN"
            }
        }
        finalTrafficStats = "{}"

        vpnMode = mode
        this.ipv4Only = ipv4Only
        state = SessionState.STARTING
        SessionLog.info("session start requested (vpn mode ${mode.name.lowercase()})")

        SessionLog.info(
            "device: ${Build.MANUFACTURER} ${Build.MODEL}, " +
                "android ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT}), " +
                "contract ${TetherService.CONTRACT_VERSION}",
        )

        return runCatching { bringUp() }
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

    private fun bringUp(): String {
        // OPPO may keep the previous testtun selected until the downstream
        // has really stopped. The successful device probe stopped tethering
        // *before* creating the TUN; mirror that ordering here.
        val oldDownstream = teardown.releaseDownstream()
        check(oldDownstream == null) {
            "old hotspot is still tethered ($oldDownstream); refusing unsafe restart"
        }
        val group = SessionResources(testNetworkApi, context.connectivityManager())
        resources = group

        // Without an IPv6 address and IPv6 DNS on the TUN, tethering does not
        // provision IPv6 downstream: clients stay IPv4-only, and every flow
        // reaches the datapath NATed to 192.0.2.2 where it can be attributed.
        val dnsServers = if (ipv4Only) {
            TEST_NETWORK_DNS_SERVERS.filterIsInstance<java.net.Inet4Address>()
        } else {
            TEST_NETWORK_DNS_SERVERS
        }
        // Arm test-network selection before publishing the TUN. Start/restart
        // the hotspot only after the datapath is attached and ready.
        preferTestNetworks()

        val name = group.acquire(tunAddresses(), dnsServers, AVAILABILITY_TIMEOUT_MS)
        interfaceName = name
        SessionLog.info(
            if (ipv4Only) {
                "tun up: $name (mtu $TUN_MTU, $TUN_ADDRESS, IPv4-only for cybercafe)"
            } else {
                "tun up: $name (mtu $TUN_MTU, $TUN_ADDRESS, $TUN_ADDRESS_V6)"
            },
        )

        group.startDatapath(TUN_MTU)
        presenceMonitor = ClientPresenceMonitor(context) { raw -> resources?.setClientPresence(raw) }.also { it.start() }
        SessionLog.info("datapath attached to $name")
        followVpn(group)
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
        lateinit var guard: SessionWatchdog
        guard = SessionWatchdog(
            name,
            onDrift = { problem ->
                // An old watchdog must never shut down the replacement session.
                if (watchdog === guard) {
                    SessionLog.warn("upstream drift: $problem")
                    tearDownAfter(problem)
                }
            },
            onReselect = {
                if (watchdog === guard) {
                    TetheringPreferenceApi(context).setPreferTestNetworks(true)
                    SessionLog.info("upstream selection requested again for $name")
                }
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

    private fun restartDownstream() {
        // bringUp() has already verified that the old downstream is down.
        val control = DownstreamControl(context)
        val outcome = startDownstreamWith(
            start = {
                val (accepted, detail) = control.startWifiTethering()
                DownstreamStartAttempt(accepted = accepted, detail = detail)
            },
            isTethered = ::awaitDownstreamTethered,
            onRetry = SessionLog::warn,
        )

        check(outcome.success) {
            "restartDownstream: hotspot did not start after ${outcome.attempts} attempts " +
                "(${outcome.detail})"
        }
        SessionLog.info(
            "hotspot started automatically after ${outcome.attempts} attempt(s): ${outcome.detail}",
        )
    }

    private fun awaitDownstreamTethered(): Boolean {
        val deadline = System.currentTimeMillis() + DOWNSTREAM_SETTLE_MS
        val downstream = DownstreamInspector()

        while (System.currentTimeMillis() < deadline) {
            if (runCatching { downstream.findTetheredDownstream() }.getOrNull() != null) {
                SessionLog.info("downstream tethered")
                return true
            }
            Thread.sleep(DOWNSTREAM_POLL_MS)
        }

        return false
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

    private var finalTrafficStats = "{}"

    @Synchronized
    fun stopAndTrafficStats(): String {
        stop()
        return finalTrafficStats
    }

    @Synchronized
    fun stop(): String {
        presenceMonitor?.stop(); presenceMonitor = null
        watchdog?.stop()
        watchdog = null
        vpn.stop()
        teardown.removeShutdownHook()

        val summary = if (activeSince == 0L) null else sessionSummary()

        val downstreamProblem = teardown.releaseDownstream()
        if (downstreamProblem != null) {
            // Do not release the enforcing datapath while Android still
            // forwards traffic: doing so could expose unpaid Internet.
            state = SessionState.ERROR
            detail = "hotspot stop not confirmed ($downstreamProblem); datapath retained"
            SessionLog.error("teardown unsafe: $detail")
            return status()
        }

        teardown.releaseUpstreamSelection(interfaceName)

        resources?.let { group ->
            runCatching { group.stopDatapathAndStats() }
                .onSuccess { finalTrafficStats = it }
                .onFailure { SessionLog.error("final traffic snapshot failed: ${it.message}") }
        }
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

    fun trafficStats(): String = resources?.trafficStats() ?: "{}"

    fun setRequireClientAttribution(required: Boolean) {
        resources?.setRequireClientAttribution(required)
    }

    fun setGlobalTrafficPolicy(downloadBps: Long, uploadBps: Long, quotaBytes: Long) {
        resources?.setGlobalTrafficPolicy(downloadBps, uploadBps, quotaBytes)
    }

    fun setDefaultClientTrafficPolicy(
        downloadBps: Long,
        uploadBps: Long,
        quotaBytes: Long,
        blocked: Boolean,
    ) {
        resources?.setDefaultClientTrafficPolicy(downloadBps, uploadBps, quotaBytes, blocked)
    }

    fun setClientTrafficPolicy(
        ip: String,
        downloadBps: Long,
        uploadBps: Long,
        quotaBytes: Long,
        blocked: Boolean,
    ) {
        resources?.setClientTrafficPolicy(ip, downloadBps, uploadBps, quotaBytes, blocked)
    }

    fun resetTrafficStats() {
        resources?.resetTrafficStats()
    }

    fun setPortalConfig(required: Boolean, configJson: String) {
        resources?.setPortalConfig(required, configJson)
    }

    fun ackPortalClaims(idsJson: String) { resources?.ackPortalClaims(idsJson) }

    fun clearPortalClaims() {
        resources?.clearPortalClaims()
    }

    fun revokePortalClient(ip: String) {
        resources?.revokePortalClient(ip)
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
    }.toString()

    private fun isVpnBypassed(): Boolean {
        if (vpnMode != VpnMode.NEVER) return false
        if (!isActive) return false

        return vpn.isVpnPresent()
    }

    private fun tunAddresses() = buildList {
        add(buildLinkAddress(java.net.InetAddress.getByName(TUN_ADDRESS), TUN_PREFIX_LENGTH))
        if (!ipv4Only) {
            add(buildLinkAddress(java.net.InetAddress.getByName(TUN_ADDRESS_V6), TUN_PREFIX_LENGTH_V6))
        }
    }

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
    }
}
