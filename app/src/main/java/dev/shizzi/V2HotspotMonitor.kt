package dev.shizzi

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android shell-side V2 gateway and device-presence bridge.
 *
 * Only a CURRENT Wi-Fi SoftAP MAC and a matching tethering DHCP MAC+IPv4
 * may authorize a station. No timer, port, sole-client shortcut or TUN shared
 * address may act as proof of physical client identity.
 *
 * Android OEMs vary: when a present-tense MAC list is unavailable, V2 reports
 * "identity unavailable" and keeps the public portal alive. It must not
 * invent a match based on the count of connected devices.
 */
internal class V2HotspotMonitor(
    private val resources: SessionResources,
    private val inspector: UpstreamInspector = UpstreamInspector(),
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var knownAssociated = emptySet<String>()
    private var directGateway: String? = null
    private var lastDiagnostic = ""

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread(::runLoop, "v2-hotspot-monitor").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        val active = thread
        thread = null
        active?.interrupt()
        if (active != null && active != Thread.currentThread()) {
            runCatching { active.join(1_500L) }
        }
        knownAssociated = emptySet()
        directGateway = null
    }

    private fun runLoop() {
        while (running.get()) {
            runCatching { refresh() }
                .onFailure { problem ->
                    diagnostic("V2 Wi-Fi observation unavailable: " + problem.javaClass.simpleName)
                }
            if (!running.get()) break
            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun refresh() {
        val tether = inspector.observe()
        if (tether.didTimeout) {
            diagnostic("V2 tethering snapshot timed out; retaining sessions")
            return
        }

        if (directGateway == null) {
            val gateway = V2HotspotObservation.systemGatewayFor(tether.rawOutput)
            if (gateway != null) {
                val result = resources.startV2DirectPortal(gateway)
                if (result.startsWith("http://")) {
                    directGateway = gateway
                    diagnostic("V2 physical PIN listener ready on private SoftAP interface")
                } else {
                    diagnostic("V2 physical PIN listener unavailable: " + result)
                }
            } else {
                diagnostic("V2 hotspot gateway address not yet available")
            }
        }

        val wifi = inspector.observeWifi()
        if (wifi.didTimeout) {
            diagnostic("V2 current Wi-Fi clients unavailable; retaining sessions")
            return
        }
        val associated = V2HotspotObservation.associatedMacs(wifi.rawOutput)
        val leases = V2HotspotObservation.physicalLeases(tether.rawOutput)
        if (associated == null || leases == null) {
            diagnostic("V2 identity unsupported by Android dumpsys; public portal remains available")
            return
        }

        // A complete current SoftAP list is authoritative for departure.
        // DHCP leases can persist for hours after clients have departed:
        // they must NEVER preserve an absent Wi-Fi station session.
        for (mac in knownAssociated - associated) {
            val result = resources.departV2WifiStation(mac)
            if (result != "OK") diagnostic("V2 Wi-Fi departure not applied: " + result)
        }
        knownAssociated = associated

        // Client association alone isn't sufficient to grant Internet.
        // A physical IP is accepted only when tethering matches its MAC.
        var verified = 0
        for (mac in associated) {
            val ip = leases[mac] ?: continue
            val result = resources.associateV2WifiStation(mac, ip)
            if (result == "OK") verified++
        }
        diagnostic("V2 stations: " + associated.size + " on Wi-Fi, " + verified + " verified DHCP matches")
    }

    private fun diagnostic(message: String) {
        if (message == lastDiagnostic) return
        lastDiagnostic = message
        SessionLog.info(message)
    }

    companion object {
        private const val POLL_INTERVAL_MS = 2_000L
    }
}
