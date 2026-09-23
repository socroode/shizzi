package dev.shizzi

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.shizzi.ui.theme.AccentChoice
import dev.shizzi.ui.theme.DesignLanguage
import dev.shizzi.ui.theme.ThemeChoice
import dev.shizzi.ui.theme.parseAccent
import dev.shizzi.ui.theme.parseAccents
import dev.shizzi.ui.theme.serialize
import dev.shizzi.ui.theme.serializeAccents
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.WeekFields

data class Settings(
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    val design: DesignLanguage = DesignLanguage.MATERIAL_EXPRESSIVE,
    val accent: AccentChoice = AccentChoice.Default,
    val customAccents: List<Int> = emptyList(),
    val isLogging: Boolean = true,

    val vpnMode: VpnMode = VpnMode.AUTO,
    val hotspotBand: HotspotBand = HotspotBand.AUTO,

    val globalDownloadMbps: Int = 40,
    val globalUploadMbps: Int = 5,
    val globalQuotaBytes: Long = 0,
    val defaultClientDownloadMbps: Int = 0,
    val defaultClientUploadMbps: Int = 0,
    val defaultClientQuotaBytes: Long = 0,
    val clientPolicies: Map<String, ClientPolicySetting> = emptyMap(),
    val devicePolicies: Map<String, ClientPolicySetting> = emptyMap(),
    val monthlyUsageByDevice: Map<String, MonthlyUsageRecord> = emptyMap(),
    val dynamicBandwidthSharing: Boolean = false,
    val maxClients: Int = 0,
    val connectionHistory: List<ConnectionEvent> = emptyList(),
    val accessPassRequired: Boolean = false,
    val accessPasses: Map<String, AccessPass> = emptyMap(),
    val portalTitle: String = "Shizzi Hotspot",
    val portalMessage: String = "Enter your access code to go online.",
    val portalHtml: String = "",

    val hasCompletedOnboarding: Boolean = false,

    val isAutomationEnabled: Boolean = false,
    val automationToken: String = "",
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    produceMigrations = { listOf(RenamedKeys.migration()) },
)

class SettingsStore(private val context: Context) {

    val settings: Flow<Settings> = context.dataStore.data.map(::toSettings)

    suspend fun backfillTokenIfEnabled() {
        context.dataStore.edit { preferences ->
            if (preferences[AUTOMATION] != true) return@edit

            preferences.mintTokenIfAbsent()
        }
    }

    suspend fun setTheme(choice: ThemeChoice) {
        context.dataStore.edit { it[THEME] = choice.name }
    }

    suspend fun setDesign(design: DesignLanguage) {
        context.dataStore.edit { it[DESIGN] = design.name }
    }

    suspend fun setAccent(accent: AccentChoice) {
        context.dataStore.edit { it[ACCENT] = accent.serialize() }
    }

    suspend fun addCustomAccent(argb: Int) {
        context.dataStore.edit { preferences ->
            val existing = parseAccents(preferences[CUSTOM_ACCENTS])
            if (argb in existing) return@edit

            preferences[CUSTOM_ACCENTS] = serializeAccents(existing + argb)
        }
    }

    suspend fun setLogging(enabled: Boolean) {
        context.dataStore.edit { it[LOGGING] = enabled }
    }

    suspend fun setVpnMode(mode: VpnMode) {
        context.dataStore.edit { it[VPN_MODE] = mode.name }
    }

    suspend fun setHotspotBand(band: HotspotBand) {
        context.dataStore.edit { it[HOTSPOT_BAND] = band.name }
    }

    suspend fun setGlobalTrafficPolicy(downloadMbps: Int, uploadMbps: Int, quotaBytes: Long) {
        context.dataStore.edit {
            it[GLOBAL_DOWNLOAD_MBPS] = downloadMbps.coerceAtLeast(0)
            it[GLOBAL_UPLOAD_MBPS] = uploadMbps.coerceAtLeast(0)
            it[GLOBAL_QUOTA_BYTES] = quotaBytes.coerceAtLeast(0)
        }
    }

    suspend fun setDefaultClientTrafficPolicy(
        downloadMbps: Int,
        uploadMbps: Int,
        quotaBytes: Long,
    ) {
        context.dataStore.edit {
            it[DEFAULT_CLIENT_DOWNLOAD_MBPS] = downloadMbps.coerceAtLeast(0)
            it[DEFAULT_CLIENT_UPLOAD_MBPS] = uploadMbps.coerceAtLeast(0)
            it[DEFAULT_CLIENT_QUOTA_BYTES] = quotaBytes.coerceAtLeast(0)
        }
    }

    suspend fun setManagerOptions(dynamicBandwidthSharing: Boolean, maxClients: Int) {
        context.dataStore.edit {
            it[DYNAMIC_BANDWIDTH] = dynamicBandwidthSharing
            it[MAX_CLIENTS] = maxClients.coerceAtLeast(0)
        }
    }

    suspend fun setClientTrafficPolicy(ip: String, policy: ClientPolicySetting) {
        if (ip.isBlank()) return

        context.dataStore.edit { preferences ->
            val policies = decodeClientPolicies(preferences[CLIENT_POLICIES]).toMutableMap()
            policies[ip] = policy
            preferences[CLIENT_POLICIES] = encodeClientPolicies(policies)
        }
    }

    suspend fun setDeviceTrafficPolicy(deviceId: String, policy: ClientPolicySetting) {
        val normalized = deviceId.lowercase()
        if (normalized.isBlank()) return

        context.dataStore.edit { preferences ->
            val policies = decodeClientPolicies(preferences[DEVICE_POLICIES]).toMutableMap()
            policies[normalized] = policy
            preferences[DEVICE_POLICIES] = encodeClientPolicies(policies)
        }
    }

    suspend fun checkpointMonthlyUsage(
        month: String,
        sessionKey: String,
        countersByDevice: Map<String, Long>,
    ) {
        if (month.isBlank() || sessionKey.isBlank() || countersByDevice.isEmpty()) return

        val today = LocalDate.now()
        val dayKey = today.toString()
        val weekFields = WeekFields.ISO
        val weekKey = "%04d-W%02d".format(
            today.get(weekFields.weekBasedYear()),
            today.get(weekFields.weekOfWeekBasedYear()),
        )
        val now = System.currentTimeMillis()

        context.dataStore.edit { preferences ->
            val records = decodeMonthlyUsage(preferences[MONTHLY_USAGE]).toMutableMap()

            countersByDevice.forEach { (rawId, rawCounter) ->
                val deviceId = rawId.lowercase()
                val counter = rawCounter.coerceAtLeast(0L)
                if (deviceId.isBlank()) return@forEach

                val existing = records[deviceId]
                val baseMonthly = if (existing?.month == month) existing.bytes else 0L
                val baseDay = if (existing?.day == dayKey) existing.dayBytes else 0L
                val baseWeek = if (existing?.week == weekKey) existing.weekBytes else 0L
                val baseTotal = existing?.totalBytes ?: 0L

                val previousCounter = when {
                    existing == null -> 0L
                    existing.sessionKey != sessionKey -> 0L
                    else -> existing.lastSessionBytes
                }

                val delta = when {
                    counter >= previousCounter -> counter - previousCounter
                    else -> counter
                }

                records[deviceId] = MonthlyUsageRecord(
                    month = month,
                    bytes = baseMonthly + delta,
                    sessionKey = sessionKey,
                    lastSessionBytes = counter,
                    day = dayKey,
                    dayBytes = baseDay + delta,
                    week = weekKey,
                    weekBytes = baseWeek + delta,
                    totalBytes = baseTotal + delta,
                    lastSeenUnixMillis = now,
                )
            }

            preferences[MONTHLY_USAGE] = encodeMonthlyUsage(records)
        }
    }

    suspend fun resetMonthlyUsage(deviceId: String, month: String) {
        val normalized = deviceId.lowercase()
        if (normalized.isBlank() || month.isBlank()) return

        context.dataStore.edit { preferences ->
            val records = decodeMonthlyUsage(preferences[MONTHLY_USAGE]).toMutableMap()
            val previous = records[normalized]
            records[normalized] = MonthlyUsageRecord(
                month = month,
                bytes = 0L,
                sessionKey = previous?.sessionKey.orEmpty(),
                lastSessionBytes = previous?.lastSessionBytes ?: 0L,
                day = previous?.day.orEmpty(),
                dayBytes = previous?.dayBytes ?: 0L,
                week = previous?.week.orEmpty(),
                weekBytes = previous?.weekBytes ?: 0L,
                totalBytes = previous?.totalBytes ?: 0L,
                lastSeenUnixMillis = previous?.lastSeenUnixMillis ?: 0L,
            )
            preferences[MONTHLY_USAGE] = encodeMonthlyUsage(records)
        }
    }

    suspend fun setAccessPassRequired(required: Boolean) {
        context.dataStore.edit { it[ACCESS_PASS_REQUIRED] = required }
    }

    suspend fun setPortalCustomization(title: String, message: String, html: String) {
        context.dataStore.edit {
            it[PORTAL_TITLE] = title.take(80)
            it[PORTAL_MESSAGE] = message.take(240)
            it[PORTAL_HTML] = html.take(100_000)
        }
    }

    suspend fun upsertAccessPass(pass: AccessPass) {
        val code = pass.code.trim().uppercase()
        if (code.isBlank()) return
        context.dataStore.edit { preferences ->
            val passes = decodeAccessPasses(preferences[ACCESS_PASSES]).toMutableMap()
            passes[code] = pass.copy(code = code)
            preferences[ACCESS_PASSES] = encodeAccessPasses(passes)
        }
    }

    suspend fun assignAccessPass(code: String, deviceId: String) {
        val normalizedCode = code.trim().uppercase()
        val normalizedDevice = deviceId.lowercase()
        if (normalizedCode.isBlank() || normalizedDevice.isBlank()) return

        context.dataStore.edit { preferences ->
            val passes = decodeAccessPasses(preferences[ACCESS_PASSES]).toMutableMap()
            val pass = passes[normalizedCode] ?: return@edit
            if (!pass.enabled) return@edit
            if (
                pass.assignedDeviceId.isNotBlank() &&
                pass.assignedDeviceId != normalizedDevice
            ) {
                return@edit
            }
            if (
                pass.assignedDeviceId == normalizedDevice &&
                pass.activatedAtMillis > 0L
            ) {
                return@edit
            }

            val usage = decodeMonthlyUsage(preferences[MONTHLY_USAGE])
            val startTotal = usage[normalizedDevice]?.totalBytes ?: 0L
            val now = System.currentTimeMillis()

            passes.keys.toList().forEach { key ->
                val existing = passes[key] ?: return@forEach
                if (existing.assignedDeviceId == normalizedDevice && key != normalizedCode) {
                    passes[key] = existing.copy(
                        assignedDeviceId = "",
                        activatedAtMillis = 0L,
                        startTotalBytes = 0L,
                    )
                }
            }

            passes[normalizedCode] = pass.copy(
                assignedDeviceId = normalizedDevice,
                activatedAtMillis = now,
                startTotalBytes = startTotal,
            )
            preferences[ACCESS_PASSES] = encodeAccessPasses(passes)
        }
    }

    suspend fun revokeAccessPass(code: String) {
        val normalizedCode = code.trim().uppercase()
        if (normalizedCode.isBlank()) return
        context.dataStore.edit { preferences ->
            val passes = decodeAccessPasses(preferences[ACCESS_PASSES]).toMutableMap()
            val pass = passes[normalizedCode] ?: return@edit
            passes[normalizedCode] = pass.copy(
                assignedDeviceId = "",
                activatedAtMillis = 0L,
                startTotalBytes = 0L,
            )
            preferences[ACCESS_PASSES] = encodeAccessPasses(passes)
        }
    }

    suspend fun appendConnectionEvents(events: List<ConnectionEvent>) {
        if (events.isEmpty()) return
        context.dataStore.edit { preferences ->
            val history = decodeConnectionHistory(preferences[CONNECTION_HISTORY]).toMutableList()
            history += events
            val trimmed = history.takeLast(100)
            preferences[CONNECTION_HISTORY] = encodeConnectionHistory(trimmed)
        }
    }

    suspend fun setOnboardingComplete(hasCompleted: Boolean) {
        context.dataStore.edit { it[ONBOARDED] = hasCompleted }
    }

    suspend fun setAutomationEnabled(isEnabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTOMATION] = isEnabled
            if (isEnabled) preferences.mintTokenIfAbsent()
        }
    }

    suspend fun setAutomationToken(token: String) {
        context.dataStore.edit { it[AUTOMATION_TOKEN] = token }
    }

    private fun MutablePreferences.mintTokenIfAbsent() {
        if (!this[AUTOMATION_TOKEN].isNullOrEmpty()) return

        this[AUTOMATION_TOKEN] = AutomationToken.generate()
    }

}

internal val THEME = stringPreferencesKey("theme")
internal val DESIGN = stringPreferencesKey("design")
internal val ACCENT = stringPreferencesKey("accent")
internal val CUSTOM_ACCENTS = stringPreferencesKey("custom_accents")
internal val LOGGING = booleanPreferencesKey("logging")
internal val VPN_MODE = stringPreferencesKey("vpn_mode")
internal val HOTSPOT_BAND = stringPreferencesKey("hotspot_band")
internal val GLOBAL_DOWNLOAD_MBPS = intPreferencesKey("global_download_mbps")
internal val GLOBAL_UPLOAD_MBPS = intPreferencesKey("global_upload_mbps")
internal val GLOBAL_QUOTA_BYTES = longPreferencesKey("global_quota_bytes")
internal val DEFAULT_CLIENT_DOWNLOAD_MBPS = intPreferencesKey("default_client_download_mbps")
internal val DEFAULT_CLIENT_UPLOAD_MBPS = intPreferencesKey("default_client_upload_mbps")
internal val DEFAULT_CLIENT_QUOTA_BYTES = longPreferencesKey("default_client_quota_bytes")
internal val CLIENT_POLICIES = stringPreferencesKey("client_policies")
internal val DEVICE_POLICIES = stringPreferencesKey("device_policies")
internal val MONTHLY_USAGE = stringPreferencesKey("monthly_usage")
internal val DYNAMIC_BANDWIDTH = booleanPreferencesKey("dynamic_bandwidth")
internal val MAX_CLIENTS = intPreferencesKey("max_clients")
internal val CONNECTION_HISTORY = stringPreferencesKey("connection_history")
internal val ACCESS_PASS_REQUIRED = booleanPreferencesKey("access_pass_required")
internal val ACCESS_PASSES = stringPreferencesKey("access_passes")
internal val PORTAL_TITLE = stringPreferencesKey("portal_title")
internal val PORTAL_MESSAGE = stringPreferencesKey("portal_message")
internal val PORTAL_HTML = stringPreferencesKey("portal_html")
internal val ONBOARDED = booleanPreferencesKey("onboarded")
internal val AUTOMATION = booleanPreferencesKey("automation")
internal val AUTOMATION_TOKEN = stringPreferencesKey("automation_token")

internal fun toSettings(preferences: Preferences) = Settings(
    theme = runCatching { ThemeChoice.valueOf(preferences[THEME].orEmpty()) }
        .getOrDefault(ThemeChoice.SYSTEM),
    design = runCatching { DesignLanguage.valueOf(preferences[DESIGN].orEmpty()) }
        .getOrDefault(DesignLanguage.MATERIAL_EXPRESSIVE),
    accent = parseAccent(preferences[ACCENT]),
    customAccents = parseAccents(preferences[CUSTOM_ACCENTS]),
    isLogging = preferences[LOGGING] ?: true,
    vpnMode = parseVpnMode(preferences[VPN_MODE]),
    hotspotBand = parseHotspotBand(preferences[HOTSPOT_BAND]),
    globalDownloadMbps = preferences[GLOBAL_DOWNLOAD_MBPS] ?: 40,
    globalUploadMbps = preferences[GLOBAL_UPLOAD_MBPS] ?: 5,
    globalQuotaBytes = preferences[GLOBAL_QUOTA_BYTES] ?: 0,
    defaultClientDownloadMbps = preferences[DEFAULT_CLIENT_DOWNLOAD_MBPS] ?: 0,
    defaultClientUploadMbps = preferences[DEFAULT_CLIENT_UPLOAD_MBPS] ?: 0,
    defaultClientQuotaBytes = preferences[DEFAULT_CLIENT_QUOTA_BYTES] ?: 0,
    clientPolicies = decodeClientPolicies(preferences[CLIENT_POLICIES]),
    devicePolicies = decodeClientPolicies(preferences[DEVICE_POLICIES]),
    monthlyUsageByDevice = decodeMonthlyUsage(preferences[MONTHLY_USAGE]),
    dynamicBandwidthSharing = preferences[DYNAMIC_BANDWIDTH] ?: false,
    maxClients = preferences[MAX_CLIENTS] ?: 0,
    connectionHistory = decodeConnectionHistory(preferences[CONNECTION_HISTORY]),
    accessPassRequired = preferences[ACCESS_PASS_REQUIRED] ?: false,
    accessPasses = decodeAccessPasses(preferences[ACCESS_PASSES]),
    portalTitle = preferences[PORTAL_TITLE] ?: "Shizzi Hotspot",
    portalMessage = preferences[PORTAL_MESSAGE] ?: "Enter your access code to go online.",
    portalHtml = preferences[PORTAL_HTML].orEmpty(),
    hasCompletedOnboarding = preferences[ONBOARDED] ?: false,
    isAutomationEnabled = preferences[AUTOMATION] ?: false,
    automationToken = preferences[AUTOMATION_TOKEN].orEmpty(),
)
