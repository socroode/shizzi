package dev.shizzi

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Binder
import android.os.ParcelFileDescriptor
import datapath.Datapath
import datapath.Session as DatapathSession

class SessionResources(
    private val testNetworkApi: TestNetworkApi,
    private val connectivityManager: ConnectivityManager,
) {

    private var tun: TunHandle? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var network: Network? = null
    private var datapathSession: DatapathSession? = null
    private var keepAliveCallback: ConnectivityManager.NetworkCallback? = null

    private val lifetimeToken = Binder()

    val interfaceName: String? get() = runCatching { tun?.interfaceName }.getOrNull()
    val acquiredNetwork: Network? get() = network

    fun acquire(
        addresses: List<android.net.LinkAddress>,
        dnsServers: List<java.net.InetAddress>,
        availabilityTimeoutMs: Int,
    ): String {
        val created = testNetworkApi.createTunInterface(addresses)
        tun = created
        fileDescriptor = created.fileDescriptor

        val name = created.interfaceName
        testNetworkApi.setupTestNetwork(name, dnsServers, lifetimeToken)

        network = awaitAvailability(name, availabilityTimeoutMs)
        requestKeepAlive()
        return name
    }

    private fun requestKeepAlive() {
        val request = NetworkRequest.Builder()
            .clearCapabilities()
            .addTransportType(resolveTransportTest())
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {}
        keepAliveCallback = callback

        runCatching { connectivityManager.requestNetwork(request, callback) }
            .onFailure { failure ->
                keepAliveCallback = null
                throw IllegalStateException(
                    "requestKeepAlive: could not request the test network, " +
                        "it would be lingered away within seconds",
                    failure,
                )
            }
    }

    fun startDatapath(mtu: Int) {
        val descriptor = fileDescriptor
            ?: error("startDatapath: no TUN fd; acquire() must succeed first")

        datapathSession = runCatching { Datapath.start(descriptor.fd.toLong(), mtu.toLong()) }
            .getOrElse { failure ->
                throw IllegalStateException(
                    "startDatapath: userspace stack failed to attach to fd " +
                        "${descriptor.fd} (mtu=$mtu)",
                    failure,
                )
            }
    }

    fun bindDatapathTo(handle: Long) {
        val session = datapathSession
            ?: error("bindDatapathTo($handle): no datapath session; startDatapath must succeed first")

        session.setNetwork(handle)
    }

    fun setGlobalTrafficPolicy(downloadBps: Long, uploadBps: Long, quotaBytes: Long) {
        datapathSession?.setGlobalPolicy(downloadBps, uploadBps, quotaBytes)
    }

    fun setDefaultClientPolicy(
        downloadBps: Long,
        uploadBps: Long,
        quotaBytes: Long,
        blocked: Boolean,
    ) {
        datapathSession?.setDefaultClientPolicy(downloadBps, uploadBps, quotaBytes, blocked)
    }

    fun setClientPolicy(
        ip: String,
        downloadBps: Long,
        uploadBps: Long,
        quotaBytes: Long,
        blocked: Boolean,
    ) {
        datapathSession?.setClientPolicy(ip, downloadBps, uploadBps, quotaBytes, blocked)
    }

    fun setSharedPolicy(
        downloadBps: Long,
        uploadBps: Long,
        quotaBytes: Long,
        blocked: Boolean,
    ) {
        datapathSession?.setSharedPolicy(downloadBps, uploadBps, quotaBytes, blocked)
    }


    fun setPortalConfig(required: Boolean, configJson: String) {
        datapathSession?.setPortalConfig(required, configJson)
    }

    fun setPortalClientAccess(
        ip: String,
        code: String,
        expiresAtMillis: Long,
        quotaRemainingBytes: Long,
        allowed: Boolean,
    ) {
        datapathSession?.setPortalClientAccess(
            ip,
            code,
            expiresAtMillis,
            quotaRemainingBytes,
            allowed,
        )
    }

    fun clearPortalClaims() {
        datapathSession?.clearPortalClaims()
    }

    fun trafficStatsJson(): String =
        datapathSession?.trafficStatsJSON() ?: "{}"

    fun resetTrafficStats() {
        datapathSession?.resetTrafficStats()
    }

    fun staleShizziInterfaces(excludingInterface: String? = null): List<String> =
        shizziTestNetworks(excludingInterface).map { it.second }

    fun releaseStaleShizziNetworks(excludingInterface: String? = null): List<String> {
        val released = mutableListOf<String>()

        shizziTestNetworks(excludingInterface).forEach { (network, name) ->
            runCatching { testNetworkApi.teardownTestNetwork(network) }
                .onSuccess { released += name }
                .onFailure { failure ->
                    SessionLog.warn(
                        "startup cleanup: could not teardown stale $name: ${failure.message}",
                    )
                }
        }

        return released
    }

    private fun shizziTestNetworks(
        excludingInterface: String? = null,
    ): List<Pair<Network, String>> =
        connectivityManager.allNetworks.mapNotNull { candidate ->
            val properties = connectivityManager.getLinkProperties(candidate)
                ?: return@mapNotNull null
            val name = properties.interfaceName ?: return@mapNotNull null

            if (name == excludingInterface || !TEST_TUN_PATTERN.matches(name)) {
                return@mapNotNull null
            }

            val addresses = properties.linkAddresses.map { it.address }
            if (SHIZZI_V4 !in addresses || SHIZZI_V6 !in addresses) {
                return@mapNotNull null
            }

            candidate to name
        }

    private fun awaitAvailability(interfaceName: String, timeoutMs: Int): Network {
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            findNetworkOn(interfaceName)?.let { return it }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        error("test network '$interfaceName' did not become available within ${timeoutMs}ms")
    }

    private fun findNetworkOn(interfaceName: String): Network? =
        connectivityManager.allNetworks.firstOrNull { candidate ->
            connectivityManager.getLinkProperties(candidate)?.interfaceName == interfaceName
        }

    fun release(): List<String> {
        val problems = mutableListOf<String>()

        keepAliveCallback?.let { callback ->
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
                .onFailure { problems += "unregisterNetworkCallback: ${it.message}" }
        }
        keepAliveCallback = null

        datapathSession?.let { session ->
            runCatching { session.stop() }
                .onFailure { problems += "datapath.stop: ${it.message}" }
        }
        datapathSession = null

        network?.let { acquired ->
            runCatching { testNetworkApi.teardownTestNetwork(acquired) }
                .onFailure { problems += "teardownTestNetwork: ${it.message}" }
        }
        network = null

        fileDescriptor?.let { descriptor ->
            runCatching { descriptor.close() }
                .onFailure { problems += "close(tun fd): ${it.message}" }
        }
        fileDescriptor = null
        tun = null

        problems.forEach { SessionLog.warn("teardown problem: $it") }
        return problems
    }

    private companion object {
        const val POLL_INTERVAL_MS = 200L

        val TEST_TUN_PATTERN = Regex("^testtun\\d+$")
        val SHIZZI_V4: java.net.InetAddress =
            java.net.InetAddress.getByName("192.0.2.2")
        val SHIZZI_V6: java.net.InetAddress =
            java.net.InetAddress.getByName("2001:db8::2")
    }
}
