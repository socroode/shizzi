package dev.shizzi

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import rikka.shizuku.Shizuku

enum class UiStatus { READY, LOADING, CONNECTED, ERROR }

data class SessionUiState(
    val shizukuState: ShizukuState = ShizukuState.NotRunning,
    val status: UiStatus = UiStatus.READY,
    val isBusy: Boolean = false,
    val detail: String = "",
    val interfaceName: String = "",
    val lastError: String = "",
    val isVpnBound: Boolean = false,
    val isVpnBypassed: Boolean = false,

    val clientCount: Int = 0,

    val traffic: Traffic = Traffic(),
) {

    val canStart: Boolean get() = shizukuState is ShizukuState.Ready && !isBusy
}

fun SessionUiState.asStopped(): SessionUiState = copy(
    isBusy = false,
    status = UiStatus.READY,
    lastError = "",
    detail = "Stopped",
    interfaceName = "",
    isVpnBound = false,
    isVpnBypassed = false,
    clientCount = 0,
    traffic = Traffic(),
)

fun SessionUiState.applyOutcome(outcome: Result<String>): SessionUiState {
    val failure = outcome.exceptionOrNull()
    if (failure != null) {
        return copy(
            isBusy = false,
            status = UiStatus.ERROR,
            lastError = "${failure.javaClass.simpleName}: ${failure.message}",

            interfaceName = "",
            isVpnBound = false,
            isVpnBypassed = false,
            clientCount = 0,
            traffic = Traffic(),
        )
    }

    val parsed = runCatching { JSONObject(outcome.getOrDefault("{}")) }.getOrNull()
    val sessionState = parsed?.optString("state").orEmpty()
    val sessionDetail = parsed?.optString("detail").orEmpty()

    return copy(
        isBusy = false,
        status = statusFor(sessionState),
        detail = sessionDetail,
        interfaceName = parsed?.optString("interface").orEmpty().takeIf { it != "null" }.orEmpty(),
        lastError = if (sessionState == "ERROR") sessionDetail else "",

        isVpnBound = parsed?.optBoolean("isVpnBound") == true,
        isVpnBypassed = parsed?.optBoolean("isVpnBypassed") == true,
        clientCount = parsed?.optInt("clientCount") ?: 0,
        traffic = Traffic(
            up = parsed?.optLong("bytesUp") ?: 0,
            down = parsed?.optLong("bytesDown") ?: 0,
        ),
    )
}

private fun statusFor(sessionState: String): UiStatus = when (sessionState) {
    "ACTIVE" -> UiStatus.CONNECTED
    "ERROR" -> UiStatus.ERROR
    "STARTING" -> UiStatus.LOADING
    else -> UiStatus.READY
}

class TetherClient {

    @Volatile private var boundService: ITetherService? = null
    @Volatile private var pendingBind: CompletableDeferred<ITetherService>? = null
    private val bindMutex = Mutex()
    @Volatile private var observedBinder: IBinder? = null

    var onSessionLost: (() -> Unit)? = null

    private var deathRecipient: IBinder.DeathRecipient? = null

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, TetherService::class.java.name),
    )

        .daemon(true)

        .tag(SERVICE_TAG)
        .processNameSuffix("probe")

        .debuggable(BuildConfig.DEBUG)
        .version(TetherService.CONTRACT_VERSION)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = binder?.let { ITetherService.Stub.asInterface(it) }
            when (service) {
                null -> pendingBind?.completeExceptionally(
                    IllegalStateException("bindUserService: Shizuku returned a null binder"),
                )

                else -> {
                    boundService = service
                    pendingBind?.complete(service)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService = null
        }
    }

    private suspend fun service(): ITetherService = bindMutex.withLock {
        // Status poller, portal sync and recovery may otherwise each start a
        // separate Shizuku binding, overwriting pendingBind.
        boundService?.let { existing ->
            if (existing.asBinder().pingBinder()) {
                if (observedBinder !== existing.asBinder()) observeDeath(existing)
                return@withLock existing
            }
            boundService = null
        }

        val deferred = CompletableDeferred<ITetherService>()
        pendingBind = deferred
        try {
            Shizuku.bindUserService(userServiceArgs, connection)
            val bound = awaitBind(deferred)
            observeDeath(bound)
            bound
        } finally {
            if (pendingBind === deferred) pendingBind = null
        }
    }

    private suspend fun awaitBind(deferred: CompletableDeferred<ITetherService>): ITetherService =
        try {
            withTimeout(BIND_TIMEOUT_MS) { deferred.await() }
        } catch (timeout: TimeoutCancellationException) {
            pendingBind = null
            throw IllegalStateException(
                "bindUserService: Shizuku returned no binder for the privileged " +
                    "helper within ${BIND_TIMEOUT_MS}ms. The shell process either " +
                    "never started or exited before handing one back; " +
                    "`adb logcat -s ShizukuServiceStarter:*` carries the reason.",
                timeout,
            )
        }

    private fun observeDeath(bound: ITetherService) {
        val binder = bound.asBinder()
        if (observedBinder === binder) return
        deathRecipient?.let { recipient ->
            runCatching { observedBinder?.unlinkToDeath(recipient, 0) }
        }
        deathRecipient = null
        observedBinder = binder
        val recipient = IBinder.DeathRecipient {
            if (observedBinder === binder) {
                observedBinder = null
                boundService = null
                onSessionLost?.invoke()
            }
        }
        runCatching { binder.linkToDeath(recipient, 0) }
            .onSuccess { deathRecipient = recipient }
            .onFailure {
                observedBinder = null
                boundService = null
            }
    }

    suspend fun runProbes(attemptTethering: Boolean): String = withContext(Dispatchers.IO) {
        val bound = service()
        verifyContract(bound)
        bound.runProbes(attemptTethering, AVAILABILITY_TIMEOUT_MS)
    }

    suspend fun checkCompatibility(): List<CapabilityResult> = withContext(Dispatchers.IO) {
        val bound = service()
        verifyContract(bound)
        parseCapabilities(bound.checkCompatibility())
    }

    suspend fun installTetheringApex(path: String): StagingOutcome =
        withContext(Dispatchers.IO) {
            val bound = service()
            verifyContract(bound)

            ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
                .use { apex -> parseStagingOutcome(bound.installTetheringApex(apex)) }
        }

    suspend fun rebootDevice(): String = withContext(Dispatchers.IO) {
        val bound = service()
        verifyContract(bound)
        bound.rebootDevice()
    }

    suspend fun start(
        logging: Boolean,
        vpnMode: VpnMode,
        ipv4Only: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        val bound = service()
        verifyContract(bound)
        bound.start(logging, vpnMode.name, ipv4Only)
    }

    fun setLogging(enabled: Boolean) {
        runCatching { boundService?.setLogging(enabled) }
    }

    suspend fun stopAndTrafficStats(): String = withContext(Dispatchers.IO) { service().stopAndTrafficStats() }

    suspend fun stop(): String = withContext(Dispatchers.IO) {
        service().stop()
    }

    suspend fun clearLog(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bound = service()

            verifyContract(bound)
            bound.clearLog()
        }.fold(
            onSuccess = { null },
            onFailure = { failure -> failure.message ?: "could not reach Shizuku" },
        )
    }

    suspend fun releaseOrphanedDownstream(): String? = withContext(Dispatchers.IO) {
        runCatching { service().stop() }
            .fold(
                onSuccess = { response ->
                    val result = runCatching { JSONObject(response) }.getOrNull()
                    if (result?.optString("state") == "ERROR") {
                        result.optString("detail", "hotspot shutdown failed")
                    } else null
                },
                onFailure = { failure ->
                    "could not reach Shizuku to drop the hotspot: ${failure.message}"
                },
            )
    }

    suspend fun status(): String = withContext(Dispatchers.IO) {
        service().status
    }

    suspend fun trafficStats(): String = withContext(Dispatchers.IO) {
        val bound = service()
        verifyContract(bound)
        bound.trafficStats
    }

    suspend fun setRequireClientAttribution(required: Boolean) = withContext(Dispatchers.IO) {
        val bound = service()
        verifyContract(bound)
        bound.setRequireClientAttribution(required)
    }

    suspend fun setGlobalTrafficPolicy(
        downloadBps: Long,
        uploadBps: Long,
        quotaBytes: Long,
    ) = withContext(Dispatchers.IO) {
        val bound = service()
        verifyContract(bound)
        bound.setGlobalTrafficPolicy(downloadBps, uploadBps, quotaBytes)
    }

    suspend fun setDefaultClientTrafficPolicy(
        downloadBps: Long,
        uploadBps: Long,
        quotaBytes: Long,
        blocked: Boolean,
    ) = withContext(Dispatchers.IO) {
        val bound = service()
        verifyContract(bound)
        bound.setDefaultClientTrafficPolicy(downloadBps, uploadBps, quotaBytes, blocked)
    }

    suspend fun setClientTrafficPolicy(
        ip: String,
        downloadBps: Long,
        uploadBps: Long,
        quotaBytes: Long,
        blocked: Boolean,
    ) = withContext(Dispatchers.IO) {
        val bound = service()
        verifyContract(bound)
        bound.setClientTrafficPolicy(ip, downloadBps, uploadBps, quotaBytes, blocked)
    }

    suspend fun resetTrafficStats() = withContext(Dispatchers.IO) {
        val bound = service()
        verifyContract(bound)
        bound.resetTrafficStats()
    }

    suspend fun setPortalConfig(required: Boolean, configJson: String) =
        withContext(Dispatchers.IO) {
            val bound = service()
            verifyContract(bound)
            bound.setPortalConfig(required, configJson)
        }

    suspend fun ackPortalClaims(idsJson: String) = withContext(Dispatchers.IO) {
        val bound = service(); verifyContract(bound); bound.ackPortalClaims(idsJson)
    }

    suspend fun clearPortalClaims() = withContext(Dispatchers.IO) {
        val bound = service()
        verifyContract(bound)
        bound.clearPortalClaims()
    }

    suspend fun revokePortalClient(ip: String) = withContext(Dispatchers.IO) {
        val bound = service()
        verifyContract(bound)
        bound.revokePortalClient(ip)
    }

    private fun verifyContract(bound: ITetherService) {
        val remote = bound.contractVersion
        check(remote == TetherService.CONTRACT_VERSION) {
            "UserService is stale: app is build ${TetherService.CONTRACT_VERSION}, " +
                "shell process reports $remote — press Stop then Start to reload it"
        }
    }

    fun unbind() = releaseBinding(shouldTerminate = false)

    fun unbindAndStopDaemon() = releaseBinding(shouldTerminate = true)

    private fun releaseBinding(shouldTerminate: Boolean) {
        deathRecipient?.let { recipient ->
            runCatching { observedBinder?.unlinkToDeath(recipient, 0) }
        }
        observedBinder = null
        deathRecipient = null
        pendingBind = null

        runCatching { Shizuku.unbindUserService(userServiceArgs, connection, shouldTerminate) }
        boundService = null
    }

    private companion object {

        private const val SERVICE_TAG = "shizzi-session"

        const val BIND_TIMEOUT_MS = 35_000L

        const val AVAILABILITY_TIMEOUT_MS = 10_000
    }
}
