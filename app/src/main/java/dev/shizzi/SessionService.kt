package dev.shizzi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private val controller = TetherClient()
    private val notification by lazy { SessionNotification(this) }
    private val statusPoller = SessionStatusPoller(scope, controller)

    private val internalState get() = sessionState

    private var isStopping = false

    private var reportTo: AutomationCommand? = null

    private var startJob: Job? = null

    private var generation = 0

    private val sessionLock = Mutex()
    private val monthlyUsageLock = Mutex()

    private var lastUsageSyncAt = 0L
    private var onlineDeviceIds: Set<String> = emptySet()

    override fun onCreate() {
        super.onCreate()
        controller.onSessionLost = ::handleSessionLost
        liveService = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isStopping = intent?.action == ACTION_STOP
        reportTo = intent?.getStringExtra(EXTRA_REPORT_AS)
            ?.let { runCatching { AutomationCommand.valueOf(it) }.getOrNull() }
        startForeground(
            NOTIFICATION_ID,
            notification.build(
                SessionUiState(status = UiStatus.LOADING),
                isStopping,
            ),
        )

        when {
            isStopping -> stopSession()
            else -> startSession()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSession() {
        if (internalState.value.isBusy) {
            announceOutcome()
            return
        }
        val attempt = ++generation
        internalState.update {
            it.copy(isBusy = true, status = UiStatus.LOADING, lastError = "", detail = "")
        }

        startJob = scope.launch {

            val settings = settingsStore().settings.first()

            val outcome = sessionLock.withLock {
                if (attempt != generation) return@launch
                runCatching { controller.start(settings.isLogging, settings.vpnMode, settings.hotspotBand, managerConfigJson(settings)) }
            }
            if (attempt != generation) return@launch

            outcome.exceptionOrNull()?.let { failure ->
                SessionLog.error(
                    "start failed in the app process: " +
                        "${failure.javaClass.name}: ${failure.message}",
                )
            }

            internalState.update { current -> current.applyOutcome(outcome) }
            publishState()

            if (internalState.value.status == UiStatus.CONNECTED) {
                runCatching { syncMonthlyUsage(force = true) }
                    .onFailure {
                        SessionLog.warn("initial monthly policy sync failed: ${it.message}")
                    }
            }

            announceOutcome()
            followStatus()
        }
    }

    private fun followStatus() = statusPoller.follow(
        isConnected = { internalState.value.status == UiStatus.CONNECTED },
        onStatus = { outcome ->
            internalState.update { current -> current.applyOutcome(outcome) }
            publishState()
            scope.launch { syncMonthlyUsage() }
        },
    )

    private suspend fun syncMonthlyUsage(
        force: Boolean = false,
        sessionKeyOverride: String? = null,
    ) {
        if (!force && internalState.value.status != UiStatus.CONNECTED) return

        monthlyUsageLock.withLock {
            val now = System.currentTimeMillis()
            if (!force && now - lastUsageSyncAt < MONTHLY_USAGE_SYNC_MS) return
            lastUsageSyncAt = now

            val month = java.time.YearMonth.now().toString()
            val sessionKey = sessionKeyOverride
                ?: internalState.value.interfaceName
            if (sessionKey.isBlank()) return

            val stats = runCatching { controller.getTrafficStats() }
                .getOrElse { failure ->
                    SessionLog.warn("monthly usage read failed: ${failure.message}")
                    return
                }

            val activeClients = stats.clients.filter { client ->
                client.lastSeenUnixMillis > 0L &&
                    now - client.lastSeenUnixMillis <= ACTIVE_CLIENT_WINDOW_MS
            }

            val counters = activeClients
                .groupBy { client ->
                    client.deviceId.lowercase().ifBlank { client.ip }
                }
                .mapValues { (_, clients) ->
                    clients.sumOf { it.totalBytes.coerceAtLeast(0L) }
                }

            settingsStore().checkpointMonthlyUsage(
                month = month,
                sessionKey = sessionKey,
                countersByDevice = counters,
            )

            var settings = settingsStore().settings.first()

            activeClients.forEach { client ->
                val deviceId = client.deviceId.lowercase().ifBlank { client.ip }
                if (deviceId !in settings.devicePolicies) {
                    settingsStore().setDeviceTrafficPolicy(
                        deviceId,
                        ClientPolicySetting(
                            downloadMbps = settings.defaultClientDownloadMbps,
                            uploadMbps = settings.defaultClientUploadMbps,
                            quotaBytes = settings.defaultClientQuotaBytes,
                        ),
                    )
                }
            }
            settings = settingsStore().settings.first()

            if (stats.portalClaims.isNotEmpty()) {
                stats.portalClaims
                    .distinctBy { "${it.ip}|${it.code}" }
                    .forEach { claim ->
                        val claimant = stats.clients.firstOrNull { it.ip == claim.ip }
                            ?: when (claim.ip) {
                                "192.0.2.2", "2001:db8::2" -> stats.clients.singleOrNull()
                                else -> null
                            }
                        val deviceId = claimant
                            ?.deviceId
                            ?.lowercase()
                            ?.ifBlank { claimant.ip }
                            .orEmpty()
                        if (deviceId.isNotBlank()) {
                            settingsStore().assignAccessPass(claim.code, deviceId)
                        }
                    }
                settings = settingsStore().settings.first()
                runCatching {
                    controller.setPortalConfig(
                        settings.accessPassRequired,
                        portalConfigJson(settings),
                    )
                }.onFailure {
                    SessionLog.warn("portal claim sync failed: ${it.message}")
                }
            }

            val currentOnline = activeClients
                .map { it.deviceId.lowercase().ifBlank { it.ip } }
                .filter { it.isNotBlank() }
                .toSet()
            val historyEvents = buildList {
                (currentOnline - onlineDeviceIds).forEach { deviceId ->
                    add(ConnectionEvent(deviceId, connected = true, atUnixMillis = now))
                }
                (onlineDeviceIds - currentOnline).forEach { deviceId ->
                    add(ConnectionEvent(deviceId, connected = false, atUnixMillis = now))
                }
            }
            if (historyEvents.isNotEmpty()) {
                settingsStore().appendConnectionEvents(historyEvents)
                settings = settingsStore().settings.first()
            }
            onlineDeviceIds = currentOnline

            fun policyFor(client: ClientTrafficStats): ClientPolicySetting {
                val deviceId = client.deviceId.lowercase().ifBlank { client.ip }
                return settings.devicePolicies[deviceId]
                    ?: settings.clientPolicies[client.ip]
                    ?: ClientPolicySetting(
                        downloadMbps = settings.defaultClientDownloadMbps,
                        uploadMbps = settings.defaultClientUploadMbps,
                        quotaBytes = settings.defaultClientQuotaBytes,
                    )
            }

            val orderedActive = activeClients.sortedWith(
                compareByDescending<ClientTrafficStats> { policyFor(it).priority.weight }
                    .thenBy { it.deviceId.lowercase().ifBlank { it.ip } },
            )
            val admittedIds = when {
                settings.maxClients <= 0 -> orderedActive
                else -> orderedActive.take(settings.maxClients)
            }.map { it.deviceId.lowercase().ifBlank { it.ip } }.toSet()

            val totalWeight = orderedActive
                .filter { it.deviceId.lowercase().ifBlank { it.ip } in admittedIds }
                .sumOf { policyFor(it).priority.weight }
                .coerceAtLeast(1)

            stats.clients.forEach { client ->
                val deviceId = client.deviceId.lowercase().ifBlank { client.ip }
                val policy = policyFor(client)
                val usageRecord = settings.monthlyUsageByDevice[deviceId]

                val monthlyUsed = usageRecord
                    ?.takeIf { it.month == month }
                    ?.bytes
                    ?: 0L

                val accessPass = settings.accessPasses.values.firstOrNull { pass ->
                    pass.enabled && pass.assignedDeviceId == deviceId
                }
                val passUsed = accessPass?.let { pass ->
                    ((usageRecord?.totalBytes ?: 0L) - pass.startTotalBytes).coerceAtLeast(0L)
                } ?: 0L
                val passExpired = accessPass?.isExpired(now) == true
                val passQuotaReached = accessPass?.let { pass ->
                    pass.quotaBytes > 0L && passUsed >= pass.quotaBytes
                } == true
                val passValid = accessPass != null && !passExpired && !passQuotaReached
                val passQuotaRemaining = when {
                    accessPass == null || accessPass.quotaBytes <= 0L -> 0L
                    else -> (accessPass.quotaBytes - passUsed).coerceAtLeast(0L)
                }

                if (settings.accessPassRequired) {
                    runCatching {
                        controller.setPortalClientAccess(
                            ip = client.ip,
                            code = accessPass?.code.orEmpty(),
                            expiresAtMillis = accessPass?.expiresAtMillis() ?: 0L,
                            quotaRemainingBytes = passQuotaRemaining,
                            allowed = passValid,
                        )
                    }.onFailure {
                        SessionLog.warn(
                            "portal access sync failed for $deviceId/${client.ip}: " +
                                it.message,
                        )
                    }
                }

                val monthlyQuota = policy.monthlyQuotaBytes
                val monthlySessionQuota = when {
                    monthlyQuota <= 0 -> policy.quotaBytes
                    else -> client.totalBytes + (monthlyQuota - monthlyUsed).coerceAtLeast(0L)
                }
                val passSessionQuota = when {
                    accessPass == null || accessPass.quotaBytes <= 0L -> 0L
                    else -> client.totalBytes +
                        (accessPass.quotaBytes - passUsed).coerceAtLeast(0L)
                }
                val effectiveSessionQuota = when {
                    monthlySessionQuota <= 0L -> passSessionQuota
                    passSessionQuota <= 0L -> monthlySessionQuota
                    else -> minOf(monthlySessionQuota, passSessionQuota)
                }

                val monthlyBlocked =
                    policy.blockOnQuota && monthlyQuota > 0 && monthlyUsed >= monthlyQuota
                val paused = policy.pausedUntilMillis > now
                val isActive = deviceId in currentOnline
                val overClientLimit =
                    isActive && settings.maxClients > 0 && deviceId !in admittedIds

                fun passCappedMbps(policyMbps: Int, passMbps: Int): Int = when {
                    !passValid || passMbps <= 0 -> policyMbps
                    policyMbps <= 0 -> passMbps
                    else -> minOf(policyMbps, passMbps)
                }

                fun effectiveRate(globalMbps: Int, clientMbps: Int): Long {
                    val manual = clientMbps.toLong() * 1_000_000L
                    if (!settings.dynamicBandwidthSharing || !isActive || deviceId !in admittedIds) {
                        return manual
                    }
                    val global = globalMbps.toLong() * 1_000_000L
                    if (global <= 0L) return manual
                    val share = global * policy.priority.weight / totalWeight
                    return when {
                        manual <= 0L -> share
                        else -> manual.coerceAtMost(share)
                    }
                }

                val cappedDownload = passCappedMbps(
                    policy.downloadMbps,
                    accessPass?.downloadMbps ?: 0,
                )
                val cappedUpload = passCappedMbps(
                    policy.uploadMbps,
                    accessPass?.uploadMbps ?: 0,
                )

                runCatching {
                    controller.setClientTrafficPolicy(
                        ip = client.ip,
                        downloadBps = effectiveRate(
                            settings.globalDownloadMbps,
                            cappedDownload,
                        ),
                        uploadBps = effectiveRate(
                            settings.globalUploadMbps,
                            cappedUpload,
                        ),
                        quotaBytes = effectiveSessionQuota,
                        blocked = policy.blocked ||
                            paused ||
                            monthlyBlocked ||
                            overClientLimit,
                    )
                }.onFailure { failure ->
                    SessionLog.warn(
                        "monthly/access policy apply failed for $deviceId/${client.ip}: " +
                            failure.message,
                    )
                }
            }
        }
    }

    private fun settingsStore(): SettingsStore =
        (application as App).settingsStore

    private fun stopSession() {
        val stopped = ++generation
        val stoppingInterface = internalState.value.interfaceName

        val abandoned = startJob
        startJob = null

        statusPoller.stop()

        internalState.update {
            it.asStopped()
        }

        publishState()

        scope.launch {

            abandoned?.cancelAndJoin()

            sessionLock.withLock {
                runCatching {
                    syncMonthlyUsage(
                        force = true,
                        sessionKeyOverride = stoppingInterface,
                    )
                }
                    .onFailure { SessionLog.warn("final monthly usage sync failed: ${it.message}") }

                if (onlineDeviceIds.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    settingsStore().appendConnectionEvents(
                        onlineDeviceIds.map { deviceId ->
                            ConnectionEvent(deviceId, connected = false, atUnixMillis = now)
                        },
                    )
                    onlineDeviceIds = emptySet()
                }

                runCatching { controller.stop() }
                    .onFailure { SessionLog.error("teardown failed: ${it.message}") }

                controller.unbindAndStopDaemon()
            }

            announceOutcome()

            if (stopped == generation) stopSelf()
        }
    }

    private fun handleSessionLost() {
        statusPoller.stop()

        isStopping = false
        SessionLog.error("shell process died; recovering any downstream it left up")

        internalState.update {
            it.copy(
                isBusy = false,
                status = UiStatus.ERROR,
                interfaceName = "",
                lastError = "Session ended unexpectedly — dropping the hotspot…",
            )
        }
        publishState()

        scope.launch {
            val problem = runCatching { controller.releaseOrphanedDownstream() }
                .getOrElse { failure -> "teardown failed: ${failure.message}" }

            when (problem) {
                null -> SessionLog.info("orphan recovery: hotspot dropped")
                else -> SessionLog.error("orphan recovery failed: $problem")
            }

            internalState.update { current -> current.copy(lastError = notification.describeLoss(problem)) }
            publishState()
            stopSelf()
        }
    }

    private fun announceOutcome() {
        val command = reportTo ?: return
        reportTo = null
        AutomationResult.announce(this, command, internalState.value)
    }

    private fun publishState() {
        notificationManager().notify(
            NOTIFICATION_ID,
            notification.build(internalState.value, isStopping),
        )
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    override fun onDestroy() {
        controller.onSessionLost = null
        controller.unbind()
        scope.cancel()
        liveService = null
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val MONTHLY_USAGE_SYNC_MS = 5_000L
        private const val ACTIVE_CLIENT_WINDOW_MS = 15_000L
        const val ACTION_STOP = "dev.shizzi.STOP_SESSION"
        const val EXTRA_REPORT_AS = "reportAs"

        private val sessionState = MutableStateFlow(SessionUiState())

        val liveState: StateFlow<SessionUiState> = sessionState.asStateFlow()

        private var liveService: SessionService? = null

        val isRunning: Boolean get() = liveService != null

        val isSessionUp: Boolean get() = liveState.value.status == UiStatus.CONNECTED

        val isSessionBusy: Boolean get() = liveState.value.status == UiStatus.LOADING

        fun start(context: Context, reportAs: AutomationCommand? = null) {
            context.startForegroundService(
                Intent(context, SessionService::class.java).reporting(reportAs),
            )
        }

        fun stop(context: Context, reportAs: AutomationCommand? = null) {
            context.startForegroundService(
                Intent(context, SessionService::class.java)
                    .setAction(ACTION_STOP)
                    .reporting(reportAs),
            )
        }

        private fun Intent.reporting(command: AutomationCommand?): Intent = when (command) {
            null -> this
            else -> putExtra(EXTRA_REPORT_AS, command.name)
        }
    }
}
