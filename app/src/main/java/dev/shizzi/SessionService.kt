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
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
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

    private var cybercafeJob: Job? = null

    private var cybercafePortalJob: Job? = null

    private val lastPortalTraffic = mutableMapOf<String, Traffic>()

    private var generation = 0

    private val sessionLock = Mutex()

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
                runCatching { controller.start(settings.isLogging, settings.vpnMode) }
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
            announceOutcome()
            followStatus()
            followCybercafePolicies()
            followCybercafePortal()
        }
    }

    private fun followCybercafePortal() {
        cybercafePortalJob?.cancel()
        cybercafePortalJob = scope.launch {
            while (internalState.value.status == UiStatus.CONNECTED) {
                val snapshot = runCatching {
                    parseLiveTrafficSnapshot(controller.trafficStats())
                }.getOrElse { failure ->
                    SessionLog.warn(
                        "cybercafe traffic poll failed: " +
                            "${failure.javaClass.simpleName}: ${failure.message}",
                    )
                    LiveTrafficSnapshot()
                }

                val store = (application as App).cybercafeStore
                val clientsByIp = snapshot.clients.associateBy(LiveClientTraffic::ip)
                val activeKeys = mutableSetOf<String>()

                snapshot.portalAuthorizations.forEach { authorization ->
                    val now = System.currentTimeMillis()
                    val binding = store.bindAuthenticatedDevice(
                        authorization.accountNumber,
                        authorization.ip,
                        now,
                    )
                    if (!binding.success) {
                        SessionLog.warn(
                            "portal account ${authorization.accountNumber} rejected for " +
                                "${authorization.ip}: ${binding.message}",
                        )
                        runCatching { controller.revokePortalClient(authorization.ip) }
                        return@forEach
                    }

                    val live = clientsByIp[authorization.ip] ?: return@forEach
                    val key = authorization.ip + "|" +
                        authorization.accountNumber + "|" +
                        authorization.startedAtMillis
                    activeKeys += key
                    val previous = lastPortalTraffic[key]
                    if (previous == null) {
                        lastPortalTraffic[key] = Traffic(up = live.upBytes, down = live.downBytes)
                    } else {
                        val upDelta = (live.upBytes - previous.up).coerceAtLeast(0L)
                        val downDelta = (live.downBytes - previous.down).coerceAtLeast(0L)
                        if (upDelta > 0L || downDelta > 0L) {
                            store.recordAccountTraffic(
                                authorization.accountNumber,
                                upDelta,
                                downDelta,
                                now,
                            )
                            lastPortalTraffic[key] = Traffic(up = live.upBytes, down = live.downBytes)
                        }
                    }
                }
                lastPortalTraffic.keys.retainAll(activeKeys)

                if (snapshot.portalRechargeClaims.isNotEmpty()) {
                    snapshot.portalRechargeClaims.forEach { claim ->
                        val outcome = store.redeemVoucherForAccount(
                            claim.accountNumber,
                            claim.code,
                            System.currentTimeMillis(),
                        )
                        if (!outcome.success) {
                            SessionLog.warn(
                                "voucher ${claim.code} rejected for " +
                                    "${claim.accountNumber}: ${outcome.message}",
                            )
                        }
                    }
                    runCatching { controller.clearPortalClaims() }
                        .onFailure {
                            SessionLog.warn("could not clear portal claims: ${it.message}")
                        }
                }

                delay(CYBERCAFE_POLL_MS)
            }
        }
    }

    private fun followCybercafePolicies() {
        cybercafeJob?.cancel()
        cybercafeJob = scope.launch {
            (application as App).cybercafeStore.state.collectLatest { cybercafe ->
                if (internalState.value.status != UiStatus.CONNECTED) return@collectLatest
                runCatching {
                    controller.applyCybercafePolicies(
                        cybercafe,
                        System.currentTimeMillis(),
                    )
                }.onFailure { failure ->
                    SessionLog.warn(
                        "cybercafe policy sync failed: " +
                            "${failure.javaClass.simpleName}: ${failure.message}",
                    )
                }
            }
        }
    }

    private fun followStatus() = statusPoller.follow(
        isConnected = { internalState.value.status == UiStatus.CONNECTED },
        onStatus = { outcome ->
            internalState.update { current -> current.applyOutcome(outcome) }
            publishState()
        },
    )

    private fun settingsStore(): SettingsStore =
        (application as App).settingsStore

    private fun stopSession() {
        val stopped = ++generation

        val abandoned = startJob
        startJob = null

        statusPoller.stop()
        cybercafeJob?.cancel()
        cybercafeJob = null
        cybercafePortalJob?.cancel()
        cybercafePortalJob = null
        lastPortalTraffic.clear()

        internalState.update {
            it.asStopped()
        }

        publishState()

        scope.launch {

            abandoned?.cancelAndJoin()

            sessionLock.withLock {
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
        cybercafeJob?.cancel()
        cybercafeJob = null
        cybercafePortalJob?.cancel()
        cybercafePortalJob = null
        lastPortalTraffic.clear()
        controller.unbind()
        scope.cancel()
        liveService = null
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CYBERCAFE_POLL_MS = 1_000L
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
