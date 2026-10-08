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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class SessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private val controller = TetherClient()
    private val notification by lazy { SessionNotification(this) }
    private val statusPoller = SessionStatusPoller(scope, controller)
    private val clientAppInfo by lazy { inspectClientAppAsset(this) }
    private val messagingServer by lazy {
        MessagingHttpServer(this) {
            (application as App).cybercafeStore.state.value.accounts
                .mapValues { (_, account) ->
                    MessagingAccount(
                        number = account.number,
                        name = account.name,
                        enabled = account.enabled,
                    )
                }
        }
    }
    private val adminFileTransferServer by lazy { AdminFileTransferServer(this) }

    private val internalState get() = sessionState

    private var isStopping = false

    private var reportTo: AutomationCommand? = null

    private var startJob: Job? = null

    private var cybercafeJob: Job? = null

    private var generation = 0

    private val sessionLock = Mutex()

    override fun onCreate() {
        super.onCreate()
        if (!RouterActivation.isActivated(this)) return
        controller.onSessionLost = ::handleSessionLost
        if (!messagingServer.start()) {
            SessionLog.warn("messaging backend failed to start")
        } else {
            SessionLog.info("messaging backend ready on 127.0.0.1:${MessagingHttpServer.PORT}")
        }
        if (!adminFileTransferServer.start()) {
            SessionLog.warn("admin file transfer backend failed to start")
        } else {
            SessionLog.info(
                "admin file transfer backend ready on 127.0.0.1:${AdminFileTransferServer.PORT}",
            )
        }
        liveService = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!RouterActivation.isActivated(this)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
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

        if (!isStopping && MediaPrefs.isEnabled(this)) {
            MediaServerService.start(this)
        }

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
                // Cybercafe mode (at least one account) runs the hotspot IPv4-only:
                // portal sessions are keyed by the client's IPv4 identity, and a
                // client with routed IPv6 would log in on one family and be
                // refused on the other.
                val cybercafe = (application as App).cybercafeStore.state.value
                val ipv4Only = cybercafe.accounts.isNotEmpty() || cybercafe.remoteAdmin.enabled
                if (ipv4Only) SessionLog.info("cybercafe mode: hotspot IPv4-only")
                runCatching { controller.start(settings.isLogging, settings.vpnMode, ipv4Only) }
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
            followCybercafe()
        }
    }

    /**
     * The one loop that connects the durable accounts to the datapath:
     *
     * 1. read live counters and pending voucher claims;
     * 2. apply usage deltas to the shared account balances (exactly once);
     * 3. redeem claimed vouchers;
     * 4. push the policy only when something policy-relevant changed.
     *
     * Consumption no longer triggers a push: the datapath enforces the shared
     * balance live from the markers it receives. A policy push therefore runs
     * to completion (it is never cancelled by the next consumption update, the
     * old collectLatest ChildCancelledException) and the datapath is not
     * reconfigured every second.
     */
    private fun followCybercafe() {
        cybercafeJob?.cancel()
        cybercafeJob = scope.launch {
            val store = (application as App).cybercafeStore
            val ledger = UsageLedger()
            val rates = SessionRateMeter()
            var pushedRevision = Long.MIN_VALUE
            var pushedEpoch = Long.MIN_VALUE
            var lastPushMillis = 0L
            var pendingResults = emptyList<PortalClaimResult>()
            var pendingAdminResults = emptyList<AdminCommandResult>()
            val handledAdminCommandIds = mutableSetOf<String>()
            var nextMediaHealthCheckMillis = 0L
            var nextBatteryPollMillis = 0L
            var latestBattery = RouterBatteryState()
            var pushedBattery: RouterBatteryState? = null

            while (internalState.value.status == UiStatus.CONNECTED) {
                val now = System.currentTimeMillis()

                if (now >= nextBatteryPollMillis) {
                    latestBattery = RouterBatteryReader.read(this@SessionService)
                    nextBatteryPollMillis = now + BATTERY_POLL_MS
                }

                if (MediaPrefs.isEnabled(this@SessionService) && now >= nextMediaHealthCheckMillis) {
                    if (!MediaNetwork.backendReachable()) {
                        SessionLog.warn("media backend health check failed; restarting local Media server")
                        MediaServerService.restart(this@SessionService)
                    }
                    nextMediaHealthCheckMillis = now + MEDIA_HEALTH_CHECK_MS
                }
                val snapshot = try {
                    parseLiveTrafficSnapshot(controller.trafficStats())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    SessionLog.warn(
                        "cybercafe traffic poll failed: " +
                            "${failure.javaClass.simpleName}: ${failure.message}",
                    )
                    null
                }

                if (snapshot != null && snapshot.epoch != 0L) {
                    val (restarted, deltas) = ledger.absorb(snapshot.epoch, snapshot.accountUsage)
                    if (restarted) SessionLog.info("cybercafe: datapath epoch ${snapshot.epoch}")
                    deltas.forEach(store::recordAccountUsage)

                    if (snapshot.portalRechargeClaims.isNotEmpty()) {
                        pendingResults = pendingResults + snapshot.portalRechargeClaims.map { claim ->
                            val outcome = store.redeemVoucherForAccount(
                                claim.accountNumber,
                                claim.code,
                                now,
                            )
                            if (!outcome.success) {
                                SessionLog.warn(
                                    "voucher ${claim.code} rejected for " +
                                        "${claim.accountNumber}: ${outcome.message}",
                                )
                            }
                            PortalClaimResult(
                                ip = claim.ip,
                                code = claim.code,
                                success = outcome.success,
                                message = outcome.message,
                            )
                        }
                        runCatching { controller.clearPortalClaims() }
                            .onFailure {
                                SessionLog.warn("could not clear portal claims: ${it.message}")
                            }
                    }

                    val freshAdminCommands = snapshot.adminCommands.filter {
                        handledAdminCommandIds.add(it.id)
                    }
                    if (freshAdminCommands.isNotEmpty()) {
                        pendingAdminResults = pendingAdminResults + freshAdminCommands.map {
                            processRemoteAdminCommand(
                                it,
                                store,
                                now,
                                this@SessionService,
                            )
                        }
                    }

                    publishLiveSessions(snapshot, store.state.value, rates, now)
                }

                val revision = store.policyRevision.value
                val due = revision != pushedRevision ||
                    ledger.epoch != pushedEpoch ||
                    pendingResults.isNotEmpty() ||
                    pendingAdminResults.isNotEmpty() ||
                    latestBattery != pushedBattery ||
                    now - lastPushMillis >= CYBERCAFE_RESYNC_MS
                var pushFailed = false
                if (due) {
                    try {
                        val mediaFolders = MediaFolderStore.load(this@SessionService)
                            .map { folder ->
                                folder.copy(
                                    writable = folder.uri()?.let { uri ->
                                        MediaRemoteSources.isWritable(this@SessionService, uri)
                                    } == true,
                                )
                            }
                        controller.applyCybercafePolicies(
                            store.state.value,
                            ledger.epoch,
                            ledger.markers(),
                            pendingResults,
                            pendingAdminResults,
                            clientAppInfo.takeIf { it.available },
                            MediaPrefs.isEnabled(this@SessionService),
                            mediaFolders,
                            MediaIndex.summary(applicationContext),
                            latestBattery,
                        )
                        pushedRevision = revision
                        pushedEpoch = ledger.epoch
                        pushedBattery = latestBattery
                        lastPushMillis = now
                        pendingResults = emptyList()
                        pendingAdminResults = emptyList()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        pushFailed = true
                        SessionLog.warn(
                            "cybercafe policy sync failed: " +
                                "${failure.javaClass.simpleName}: ${failure.message}",
                        )
                    }
                }

                store.flushUsage(now)

                if (pushFailed) {
                    delay(CYBERCAFE_POLL_MS)
                } else {
                    // Wake early when an admin edit or recharge changes the policy.
                    withTimeoutOrNull(CYBERCAFE_POLL_MS) {
                        store.policyRevision.first { it != pushedRevision }
                    }
                }
            }
        }
    }

    private fun publishLiveSessions(
        snapshot: LiveTrafficSnapshot,
        state: CybercafeState,
        rates: SessionRateMeter,
        nowMillis: Long,
    ) {
        val keys = mutableSetOf<String>()
        val sessions = snapshot.portalAuthorizations.map { session ->
            val key = session.ip + "|" + session.startedAtMillis
            keys += key
            val (upBps, downBps) = rates.measure(key, session.upBytes, session.downBytes, nowMillis)
            val account = state.accounts[session.accountNumber]
            LiveSession(
                ip = session.ip,
                mac = session.mac,
                accountNumber = session.accountNumber,
                accountName = account?.name.orEmpty(),
                plan = account?.planLabel(nowMillis) ?: "Compte supprimé",
                authorized = session.authorized,
                limitDownloadBps = session.downloadBps,
                limitUploadBps = session.uploadBps,
                measuredDownloadBps = downBps,
                measuredUploadBps = upBps,
                sessionUpBytes = session.upBytes,
                sessionDownBytes = session.downBytes,
                startedAtMillis = session.startedAtMillis,
            )
        }
        rates.retain(keys)
        mutableLiveSessions.value = sessions
        mutableAttribution.value = snapshot.attribution
        mutableMediaDiagnostics.value = snapshot.mediaDiagnostics
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
        clearLiveSessions()

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
        clearLiveSessions()
        (application as App).cybercafeStore.flushUsage(System.currentTimeMillis(), force = true)
        messagingServer.stop()
        adminFileTransferServer.stop()
        controller.unbind()
        scope.cancel()
        liveService = null
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CYBERCAFE_POLL_MS = 1_000L
        private const val BATTERY_POLL_MS = 5_000L
        private const val CYBERCAFE_RESYNC_MS = 60_000L
        private const val MEDIA_HEALTH_CHECK_MS = 5_000L
        const val ACTION_STOP = "dev.shizzi.STOP_SESSION"
        const val EXTRA_REPORT_AS = "reportAs"

        private val sessionState = MutableStateFlow(SessionUiState())

        val liveState: StateFlow<SessionUiState> = sessionState.asStateFlow()

        private var liveService: SessionService? = null

        val isRunning: Boolean get() = liveService != null

        val isSessionUp: Boolean get() = liveState.value.status == UiStatus.CONNECTED

        val isSessionBusy: Boolean get() = liveState.value.status == UiStatus.LOADING

        private val mutableLiveSessions = MutableStateFlow<List<LiveSession>>(emptyList())

        /** Devices currently logged in through the portal (one entry per device). */
        val liveSessions: StateFlow<List<LiveSession>> = mutableLiveSessions.asStateFlow()

        private val mutableAttribution = MutableStateFlow(AttributionDiagnostics())

        val attributionDiagnostics: StateFlow<AttributionDiagnostics> =
            mutableAttribution.asStateFlow()

        private val mutableMediaDiagnostics =
            MutableStateFlow<List<LiveMediaDiagnostic>>(emptyList())

        /** Recent Media access/proxy events for the Hotspot Media diagnostic screen. */
        val mediaDiagnostics: StateFlow<List<LiveMediaDiagnostic>> =
            mutableMediaDiagnostics.asStateFlow()

        private fun clearLiveSessions() {
            mutableLiveSessions.value = emptyList()
            mutableAttribution.value = AttributionDiagnostics()
            mutableMediaDiagnostics.value = emptyList()
        }

        /**
         * Ends one device's session. The account, its balance and its other
         * devices are untouched; the device goes back to the portal.
         */
        fun disconnectSession(ip: String) {
            val service = liveService ?: return
            service.scope.launch {
                runCatching { service.controller.revokePortalClient(ip) }
                    .onFailure { SessionLog.warn("disconnect $ip failed: ${it.message}") }
            }
        }

        fun disconnectAccount(accountNumber: String) {
            liveSessions.value
                .filter { it.accountNumber == accountNumber }
                .forEach { disconnectSession(it.ip) }
        }

        fun start(context: Context, reportAs: AutomationCommand? = null) {
            check(RouterActivation.isActivated(context)) { "Activation du routeur Shizzi requise" }
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
