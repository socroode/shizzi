package dev.shizzi

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.shizzi.ui.theme.AccentChoice
import dev.shizzi.ui.theme.DesignLanguage
import dev.shizzi.ui.theme.ThemeChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SessionViewModel(application: Application) : AndroidViewModel(application) {

    private val diagnostics = TetherClient()

    private val settingsStore = getApplication<App>().settingsStore

    val settings: StateFlow<Settings?> = settingsStore.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = null,
    )

    private val localState = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = localState.asStateFlow()

    private val localDiagnostics = MutableStateFlow<DiagnosticsState>(DiagnosticsState.Idle)
    val diagnosticsState: StateFlow<DiagnosticsState> = localDiagnostics.asStateFlow()

    private val compatibility =
        CompatibilityController(getApplication(), diagnostics, viewModelScope)

    val compatibilityState: StateFlow<CompatibilityState> = compatibility.state

    private val permissions = PermissionInspector(application)

    private val permissionRequest = PermissionRequest(application)

    private val localPermissions = MutableStateFlow(emptyList<PermissionStatus>())
    val permissionState: StateFlow<List<PermissionStatus>> = localPermissions.asStateFlow()

    private var sessionCollector: Job? = null

    init {
        refreshShizukuState()
        refreshPermissions()
        observeSession()
    }

    private fun observeSession() {
        if (sessionCollector?.isActive == true) return

        sessionCollector = viewModelScope.launch {
            SessionService.liveState.collect { session ->
                localState.update { local -> session.copy(shizukuState = local.shizukuState) }
            }
        }
    }

    fun refreshShizukuState() {
        localState.update { it.copy(shizukuState = ShizukuGate.currentState()) }
    }

    fun requestPermission() {
        ShizukuGate.requestPermission()
    }

    fun actOnShizuku() {
        ShizukuGate.remedy(localState.value.shizukuState)
    }

    fun refreshPermissions() {
        localPermissions.value = permissions.observe()
    }

    fun isPermissionGranted(permission: AppPermission): Boolean =
        permissions.isGranted(permission)

    fun openPermissionSettings(permission: AppPermission) {
        permissionRequest.open(permission)
    }

    fun setAutomation(isEnabled: Boolean) {
        viewModelScope.launch { settingsStore.setAutomationEnabled(isEnabled) }
    }

    fun regenerateAutomationToken() {
        viewModelScope.launch { settingsStore.setAutomationToken(AutomationToken.generate()) }
    }

    fun setLogging(enabled: Boolean) {
        SessionLog.setEnabled(enabled)
        diagnostics.setLogging(enabled)
        viewModelScope.launch { settingsStore.setLogging(enabled) }
    }

    fun setVpnMode(mode: VpnMode) {
        viewModelScope.launch { settingsStore.setVpnMode(mode) }
    }

    fun setHotspotBand(band: HotspotBand) {
        viewModelScope.launch { settingsStore.setHotspotBand(band) }
    }

    fun setGlobalTrafficPolicy(downloadMbps: Int, uploadMbps: Int, quotaBytes: Long) {
        viewModelScope.launch {
            settingsStore.setGlobalTrafficPolicy(downloadMbps, uploadMbps, quotaBytes)

            if (SessionService.isSessionUp) {
                runCatching {
                    diagnostics.setGlobalTrafficPolicy(
                        downloadMbps.toLong() * 1_000_000L,
                        uploadMbps.toLong() * 1_000_000L,
                        quotaBytes,
                    )
                }.onFailure {
                    SessionLog.warn("live global traffic policy update failed: ${it.message}")
                }
            }
        }
    }

    fun setDefaultClientTrafficPolicy(downloadMbps: Int, uploadMbps: Int, quotaBytes: Long) {
        viewModelScope.launch {
            settingsStore.setDefaultClientTrafficPolicy(downloadMbps, uploadMbps, quotaBytes)
        }
    }

    fun setClientTrafficPolicy(
        deviceId: String,
        ip: String,
        downloadMbps: Int,
        uploadMbps: Int,
        monthlyQuotaBytes: Long,
        blocked: Boolean,
    ) {
        viewModelScope.launch {
            val policy = ClientPolicySetting(
                downloadMbps = downloadMbps,
                uploadMbps = uploadMbps,
                quotaBytes = 0,
                monthlyQuotaBytes = monthlyQuotaBytes,
                blocked = blocked,
            )
            settingsStore.setDeviceTrafficPolicy(deviceId, policy)

            if (SessionService.isSessionUp) {
                runCatching {
                    val month = java.time.YearMonth.now().toString()
                    val settings = settingsStore.settings.first()
                    val used = settings.monthlyUsageByDevice[deviceId.lowercase()]
                        ?.takeIf { it.month == month }
                        ?.bytes
                        ?: 0L
                    val sessionUsed = SessionService.liveState.value.managerTraffic.clients
                        .firstOrNull { it.ip == ip }
                        ?.totalBytes
                        ?: 0L
                    val effectiveQuota = when {
                        monthlyQuotaBytes <= 0 -> 0L
                        else -> sessionUsed + (monthlyQuotaBytes - used).coerceAtLeast(0L)
                    }

                    diagnostics.setClientTrafficPolicy(
                        ip = ip,
                        downloadBps = downloadMbps.toLong() * 1_000_000L,
                        uploadBps = uploadMbps.toLong() * 1_000_000L,
                        quotaBytes = effectiveQuota,
                        blocked = blocked || (monthlyQuotaBytes > 0 && used >= monthlyQuotaBytes),
                    )
                }.onFailure {
                    SessionLog.warn("live device traffic policy update failed for $deviceId/$ip: ${it.message}")
                }
            }
        }
    }

    fun resetTrafficStats() {
        viewModelScope.launch {
            if (!SessionService.isSessionUp) return@launch
            runCatching { diagnostics.resetTrafficStats() }
                .onFailure { SessionLog.warn("traffic stats reset failed: ${it.message}") }
        }
    }

    fun setTheme(choice: ThemeChoice) {
        viewModelScope.launch { settingsStore.setTheme(choice) }
    }

    fun setDesign(design: DesignLanguage) {
        viewModelScope.launch { settingsStore.setDesign(design) }
    }

    fun setAccent(accent: AccentChoice) {
        viewModelScope.launch { settingsStore.setAccent(accent) }
    }

    fun addCustomAccent(argb: Int) {
        viewModelScope.launch { settingsStore.addCustomAccent(argb) }
    }

    fun toggle() {
        val context = getApplication<Application>()

        when {
            SessionService.isSessionUp -> SessionService.stop(context)
            else -> SessionService.start(context)
        }
    }

    fun cancel() {
        localState.update {
            it.asStopped()
        }
        SessionService.stop(getApplication())
    }

    fun runProbes() {
        if (localDiagnostics.value is DiagnosticsState.Running) return
        localDiagnostics.value = DiagnosticsState.Running

        viewModelScope.launch {
            localDiagnostics.value = runCatching { diagnostics.runProbes(true) }
                .fold(
                    onSuccess = { report ->
                        DiagnosticsState.Complete(report, TetherService.REPORT_PATH)
                    },
                    onFailure = { failure ->

                        SessionLog.error(
                            "diagnostics failed in the app process: " +
                                "${failure.javaClass.name}: ${failure.message}",
                        )
                        DiagnosticsState.Failed(
                            "${failure.javaClass.simpleName}: ${failure.message}",
                        )
                    },
                )
            refreshShizukuState()
        }
    }

    fun checkCompatibility() = compatibility.check()

    fun downloadTetheringApex() = compatibility.downloadApex()

    fun installTetheringApex() = compatibility.installApex()

    fun rebootDevice() = compatibility.rebootDevice()

    fun completeOnboarding() {
        viewModelScope.launch { settingsStore.setOnboardingComplete(true) }
    }

    fun restartOnboarding() {
        compatibility.reset()
        viewModelScope.launch { settingsStore.setOnboardingComplete(false) }
    }

    fun dismissDiagnostics() {
        if (localDiagnostics.value is DiagnosticsState.Running) return
        localDiagnostics.value = DiagnosticsState.Idle
    }

    fun clearLog(onCleared: (String?) -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { SessionLog.clear() }
            onCleared(diagnostics.clearLog())
        }
    }

    override fun onCleared() {

        diagnostics.unbind()
        super.onCleared()
    }
}
