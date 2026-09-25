package dev.shizzi

import android.content.Context
import android.net.LinkAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import android.util.Log
import org.json.JSONObject
import java.net.Inet6Address
import java.net.InetAddress

class ProbeRunner(private val context: Context) {

    private val testNetworkApi = TestNetworkApi(context)
    private val inspector = UpstreamInspector()
    private val teardown = SessionTeardown(context)
    private var resources: SessionResources? = null

    private var didStartDownstream = false

    fun run(attemptTethering: Boolean, availabilityTimeoutMs: Int): String {
        val report = ProbeReportBuilder()
        report.recordHiddenApiResolutions(testNetworkApi.resolveAll())

        val canProceed = probeIdentityAndPlatform(report)
        try {
            when {
                canProceed -> probeNetworkPath(report, attemptTethering, availabilityTimeoutMs)
                else -> skipRemaining(report, "blocked by Q0/Q1 failure")
            }
        } finally {
            releaseSession(report)
        }

        return report.build(environment())
    }

    private fun releaseSession(report: ProbeReportBuilder) {
        val downstreamProblem = when {
            didStartDownstream -> teardown.releaseDownstream()
            else -> null
        }
        didStartDownstream = false

        teardown.releaseUpstreamSelection(resources?.interfaceName)

        val problems = resources?.release().orEmpty()
        resources = null

        report.recordReleaseProblems(
            when (downstreamProblem) {
                null -> problems
                else -> problems + "downstream: $downstreamProblem"
            },
        )
    }

    private fun probeIdentityAndPlatform(report: ProbeReportBuilder): Boolean {
        val uid = Process.myUid()
        val isPrivilegedUid = uid == SHELL_UID || uid == ROOT_UID
        report.record(
            id = "Q0",
            question = "Does the privileged service run as shell (2000) or root (0)?",
            outcome = if (isPrivilegedUid) ProbeOutcome.PASS else ProbeOutcome.FAIL,
            detail = "uid=$uid (${if (uid == SHELL_UID) "shell" else if (uid == ROOT_UID) "root" else "unexpected"})",
        )

        report.record(
            id = "Q1",
            question = "Is TestNetworkManager reachable from this UID?",
            outcome = if (testNetworkApi.isAvailable) ProbeOutcome.PASS else ProbeOutcome.FAIL,
            detail = when {
                testNetworkApi.isAvailable -> "getSystemService(\"test_network\") returned an instance"
                else -> "test_network service or TestNetworkManager class unavailable"
            },
        )

        return isPrivilegedUid && testNetworkApi.isAvailable
    }

    private fun probeNetworkPath(
        report: ProbeReportBuilder,
        attemptTethering: Boolean,
        availabilityTimeoutMs: Int,
    ) {
        val group = SessionResources(testNetworkApi, context.connectivityManager())
        resources = group

        if (attemptTethering) prepareFreshProbeStartup()

        val acquired = runCatching {
            group.acquire(tunAddresses(), TEST_NETWORK_DNS_SERVERS, availabilityTimeoutMs)
        }

        acquired.fold(
            onSuccess = { name -> onTunAcquired(report, name, attemptTethering) },
            onFailure = { failure -> onTunFailed(report, failure) },
        )
    }

    private fun prepareFreshProbeStartup() {
        val control = DownstreamControl(context)

        runCatching { control.stopWifiTethering() }
            .onFailure { failure ->
                SessionLog.warn(
                    "probe startup: could not stop hotspot before TUN creation: ${failure.message}",
                )
            }

        runCatching { TetheringPreferenceApi(context).setPreferTestNetworks(false) }
            .onFailure { failure ->
                SessionLog.warn(
                    "probe startup: could not clear test-network preference: ${failure.message}",
                )
            }

        val group = resources ?: return
        val staleBefore = group.staleShizziInterfaces()
        if (staleBefore.isNotEmpty()) {
            SessionLog.warn("probe startup: stale Shizzi test networks=$staleBefore")
            val requested = group.releaseStaleShizziNetworks()
            if (requested.isNotEmpty()) {
                SessionLog.info("probe startup: teardown requested for $requested")
            }

            val deadline = System.currentTimeMillis() + STALE_TUN_RELEASE_WAIT_MS
            while (
                System.currentTimeMillis() < deadline &&
                group.staleShizziInterfaces().isNotEmpty()
            ) {
                Thread.sleep(STALE_TUN_POLL_MS)
            }

            val remaining = group.staleShizziInterfaces()
            if (remaining.isNotEmpty()) {
                SessionLog.warn("probe startup: competing Shizzi networks remain $remaining")
            } else {
                SessionLog.info("probe startup: stale Shizzi networks released")
            }
        }

        Thread.sleep(PROBE_STARTUP_SETTLE_MS)
    }

    private fun onTunAcquired(
        report: ProbeReportBuilder,
        interfaceName: String,
        attemptTethering: Boolean,
    ) {
        report.recordPass("Q2", "Does createTunInterface() return a usable TUN?", "interface=$interfaceName")
        report.recordPass(
            "Q3",
            "Does setupTestNetwork() produce an available network?",
            "ConnectivityManager reported available; netId handle=${resources?.acquiredNetwork}",
        )
        probeUpstreamEligibility(report)
        probeDatapath(report)
        probeUpstreamCallback(report, interfaceName)
        probeTetheringPreference(report, interfaceName, attemptTethering)
    }

    private fun probeDatapath(report: ProbeReportBuilder) {
        val group = resources
        if (group == null) {
            report.recordSkip("Q7", QUESTION_DATAPATH, "no session resources")
            return
        }

        runCatching { group.startDatapath(TUN_MTU) }.fold(
            onSuccess = {
                report.recordPass("Q7", QUESTION_DATAPATH, "netstack attached to TUN fd, mtu=$TUN_MTU")
            },
            onFailure = { failure ->
                report.recordFail("Q7", QUESTION_DATAPATH, "${failure.javaClass.simpleName}: ${failure.message}")
            },
        )
    }

    private fun probeUpstreamCallback(report: ProbeReportBuilder, interfaceName: String) {
        val network = resources?.acquiredNetwork
        if (network == null) {
            report.recordSkip("Q8", QUESTION_CALLBACK, "no test network")
            return
        }

        val observed = awaitCallbackDelivery(interfaceName)
        report.record(
            id = "Q8",
            question = QUESTION_CALLBACK,
            outcome = if (observed.first) ProbeOutcome.PASS else ProbeOutcome.FAIL,
            detail = observed.second,
        )
    }

    private fun awaitCallbackDelivery(interfaceName: String): Pair<Boolean, String> {
        val manager = context.connectivityManager()
        val latch = java.util.concurrent.CountDownLatch(1)
        val seen = java.util.Collections.synchronizedList(mutableListOf<String>())

        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(available: Network) {
                val name = manager.getLinkProperties(available)?.interfaceName ?: "?"
                seen += name
                if (name == interfaceName) latch.countDown()
            }
        }

        val request = android.net.NetworkRequest.Builder()
            .clearCapabilities()
            .build()

        return runCatching {
            manager.registerNetworkCallback(request, callback)
            val delivered = latch.await(CALLBACK_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            runCatching { manager.unregisterNetworkCallback(callback) }

            delivered to when {
                delivered -> "onAvailable delivered $interfaceName; all seen=$seen"
                else -> "onAvailable never delivered $interfaceName within ${CALLBACK_WAIT_MS}ms; " +
                    "all seen=$seen"
            }
        }.getOrElse { failure ->
            false to "registerNetworkCallback rejected: " +
                "${failure.javaClass.simpleName}: ${failure.message}"
        }
    }

    private fun onTunFailed(report: ProbeReportBuilder, failure: Throwable) {
        val message = "${failure.javaClass.simpleName}: ${failure.message}"

        val didCreateTun = resources?.interfaceName != null
        when {
            didCreateTun -> {
                report.recordPass("Q2", "Does createTunInterface() return a usable TUN?", "interface=${resources?.interfaceName}")
                report.recordFail("Q3", "Does setupTestNetwork() produce an available network?", message)
            }

            else -> {
                report.recordFail("Q2", "Does createTunInterface() return a usable TUN?", message)
                report.recordSkip("Q3", "Does setupTestNetwork() produce an available network?", "no TUN to register")
            }
        }
        report.recordSkip("Q3b", QUESTION_ELIGIBILITY, "no test network")
        report.recordSkip("Q8", QUESTION_CALLBACK, "no test network")
        report.recordSkip("Q4", QUESTION_PREFER, "no test network")
        report.recordSkip("Q5", QUESTION_UPSTREAM, "no test network")
        report.recordSkip("Q6", QUESTION_IPV6, "no test network")
    }

    private fun probeUpstreamEligibility(report: ProbeReportBuilder) {
        val network = resources?.acquiredNetwork
        if (network == null) {
            report.recordSkip("Q3b", QUESTION_ELIGIBILITY, "no test network")
            return
        }

        val capabilities = context.connectivityManager().getNetworkCapabilities(network)
        if (capabilities == null) {
            report.recordFail("Q3b", QUESTION_ELIGIBILITY, "getNetworkCapabilities returned null")
            return
        }

        val hasTestTransport = capabilities.hasTransport(resolveTransportTest())
        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

        report.record(
            id = "Q3b",
            question = QUESTION_ELIGIBILITY,
            outcome = if (hasTestTransport) ProbeOutcome.PASS else ProbeOutcome.FAIL,
            detail = "TRANSPORT_TEST=$hasTestTransport (the selection criterion); " +
                "INTERNET=$hasInternet (informational: never granted by " +
                "TestNetworkService); raw=$capabilities",
        )
    }

    private fun probeTetheringPreference(
        report: ProbeReportBuilder,
        interfaceName: String,
        attemptTethering: Boolean,
    ) {
        val preferenceApi = TetheringPreferenceApi(context)
        val didSetPreference = runCatching { preferenceApi.setPreferTestNetworks(true) }

        didSetPreference.fold(
            onSuccess = {
                report.recordPass("Q4", QUESTION_PREFER, "setPreferTestNetworks(true) accepted")
            },
            onFailure = { failure ->
                report.recordFail("Q4", QUESTION_PREFER, "${failure.javaClass.simpleName}: ${failure.message}")
            },
        )

        val restartDetail = when {
            attemptTethering -> restartDownstreamAfterTun()
            else -> "no restart: observing the running downstream"
        }
        observeUpstream(report, interfaceName, restartDetail)
        probeIpv6Surface(report)
    }

    private fun restartDownstreamAfterTun(): String {
        val control = DownstreamControl(context)
        val didStop = runCatching { control.stopWifiTethering() }.getOrDefault(false)
        val (didStart, startDetail) = runCatching { control.startWifiTethering() }
            .getOrElse { failure -> false to "${failure.javaClass.simpleName}: ${failure.message}" }

        didStartDownstream = didStop || didStart

        return when {
            didStart -> "downstream restarted after TUN creation; start=$startDetail"
            else -> "downstream restart after TUN creation failed; " +
                "stopped=$didStop, opPackage=${control.opPackageName}, start=$startDetail"
        }
    }

    private fun observeUpstream(
        report: ProbeReportBuilder,
        interfaceName: String,
        restartDetail: String,
    ) {
        val observation = awaitUpstreamSettle(interfaceName)
        val isOnlyOwnedTun = observation.interfaceNames.isNotEmpty() &&
            observation.interfaceNames.all { it == interfaceName }

        val outcome = when {
            observation.didTimeout -> ProbeOutcome.FAIL
            isOnlyOwnedTun -> ProbeOutcome.PASS
            else -> ProbeOutcome.FAIL
        }

        report.record(
            id = "Q5",
            question = QUESTION_UPSTREAM,
            outcome = outcome,
            detail = buildString {
                append("owned=$interfaceName; ")
                append("observed=${observation.interfaceNames}; ")
                append("$restartDetail; ")
                append("timedOut=${observation.didTimeout}\n--- selection lines ---\n")
                append(selectionLines(observation.rawOutput))
            },
        )
    }

    private fun selectionLines(rawOutput: String): String =
        rawOutput.lineSequence()
            .filter { line -> SELECTION_KEYS.any { key -> line.contains(key) } }
            .joinToString("\n")
            .take(DUMP_EXCERPT_CHARS)

    private fun awaitUpstreamSettle(interfaceName: String): UpstreamObservation {
        val deadline = System.currentTimeMillis() + UPSTREAM_SETTLE_MS
        var latest = inspector.observe()

        while (System.currentTimeMillis() < deadline) {
            val hasSettled = latest.interfaceNames.isNotEmpty() &&
                latest.interfaceNames.all { it == interfaceName }
            if (hasSettled) return latest
            Log.i(TAG, "awaitUpstreamSettle: waiting for $interfaceName, saw ${latest.interfaceNames}")

            Thread.sleep(UPSTREAM_POLL_MS)
            latest = inspector.observe()
        }
        return latest
    }

    private fun probeIpv6Surface(report: ProbeReportBuilder) {
        val network = resources?.acquiredNetwork
        val properties = network?.let { context.connectivityManager().getLinkProperties(it) }
        val hasIpv6Address = properties?.linkAddresses
            ?.any { it.address is Inet6Address }
            ?: false
        val hasIpv6Dns = properties?.dnsServers
            ?.any { it is Inet6Address }
            ?: false
        val ipv4Only = properties != null && !hasIpv6Address && !hasIpv6Dns

        report.record(
            id = "Q6",
            question = QUESTION_IPV6,
            outcome = if (ipv4Only) ProbeOutcome.PASS else ProbeOutcome.FAIL,
            detail = "IPv4-only diagnostic: ipv6Address=$hasIpv6Address; " +
                "ipv6Dns=$hasIpv6Dns; linkProperties=${properties ?: "unavailable"}",
        )
    }

    fun teardown(): String {
        val downstreamProblem = when {
            didStartDownstream -> teardown.releaseDownstream()
            else -> null
        }
        didStartDownstream = false

        val restored = runCatching {
            teardown.releaseUpstreamSelection(resources?.interfaceName)
        }
        val problems = resources?.release() ?: emptyList()
        resources = null

        val result = JSONObject()
        result.put("preferTestNetworksRestored", restored.isSuccess)
        restored.exceptionOrNull()?.let { result.put("restoreError", it.message) }
        downstreamProblem?.let { result.put("downstreamProblem", it) }
        result.put("teardownProblems", org.json.JSONArray(problems))
        return result.toString(2)
    }

    private fun skipRemaining(report: ProbeReportBuilder, reason: String) {
        listOf(
            "Q2" to "Does createTunInterface() return a usable TUN?",
            "Q3" to "Does setupTestNetwork() produce an available network?",
            "Q3b" to QUESTION_ELIGIBILITY,
            "Q7" to QUESTION_DATAPATH,
            "Q8" to QUESTION_CALLBACK,
            "Q4" to QUESTION_PREFER,
            "Q5" to QUESTION_UPSTREAM,
            "Q6" to QUESTION_IPV6,
        ).forEach { (id, question) -> report.recordSkip(id, question, reason) }
    }

    private fun tunAddresses(): List<LinkAddress> = listOf(
        buildLinkAddress(InetAddress.getByName(TUN_ADDRESS), TUN_PREFIX_LENGTH),
    )

    private fun environment(): JSONObject = JSONObject().apply {
        put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        put("fingerprint", Build.FINGERPRINT)
        put("sdkInt", Build.VERSION.SDK_INT)
        put("release", Build.VERSION.RELEASE)
        put("uid", Process.myUid())

        put("contextPackage", context.packageName)
    }

    private companion object {
        const val SHELL_UID = 2000
        const val ROOT_UID = 0

        const val TUN_ADDRESS = "192.0.2.2"
        const val TUN_PREFIX_LENGTH = 24

        const val TUN_MTU = 1500

        const val DUMP_EXCERPT_CHARS = 4000

        val SELECTION_KEYS = listOf(
            "Current upstream",
            "Upstream wanted",
            "Upstream quota",
            "isDunRequired",
            "chooseUpstreamAutomatically",
            "Exempted",
            "testtun",
        )

        const val UPSTREAM_SETTLE_MS = 8_000L
        const val UPSTREAM_POLL_MS = 1_000L
        const val TAG = "ProbeRunner"
        const val QUESTION_PREFER = "Does TetheringManager.setPreferTestNetworks exist and accept the call?"
        const val QUESTION_UPSTREAM = "Does the tethering stack report the owned testtunN as sole upstream?"
        const val QUESTION_ELIGIBILITY =
            "Does the test network carry the capabilities tethering requires of an upstream?"
        const val QUESTION_IPV6 = "Is this diagnostic TestNetwork IPv4-only?"
        const val QUESTION_DATAPATH = "Does the userspace stack attach to the TUN fd?"
        const val QUESTION_CALLBACK =
            "Does a NetworkCallback listen ever deliver the test network? " +
                "(this is what populates UpstreamNetworkMonitor.mNetworkMap)"

        const val CALLBACK_WAIT_MS = 5_000L
        const val PROBE_STARTUP_SETTLE_MS = 500L
        const val STALE_TUN_RELEASE_WAIT_MS = 3_000L
        const val STALE_TUN_POLL_MS = 200L
    }
}
