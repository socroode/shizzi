package dev.shizzi.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.shizzi.AccessPass
import dev.shizzi.ClientPolicySetting
import dev.shizzi.ClientPriority
import dev.shizzi.ClientTrafficStats
import dev.shizzi.DataUnit
import dev.shizzi.DurationUnit
import dev.shizzi.ManagerTrafficStats
import dev.shizzi.MonthlyUsageRecord
import dev.shizzi.RateUnit
import dev.shizzi.Settings
import dev.shizzi.VoucherTemplate
import dev.shizzi.Traffic
import dev.shizzi.UiStatus
import dev.shizzi.ui.theme.ScreenPadding
import dev.shizzi.ui.theme.ShizziTheme
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields

data class HotspotManagerActions(
    val onSetGlobalPolicy: (Int, Int, Long) -> Unit,
    val onSetDefaultClientPolicy: (Int, Int, Long) -> Unit,
    val onSetManagerOptions: (Boolean, Int) -> Unit,
    val onSetAccessPassRequired: (Boolean) -> Unit,
    val onSetPortalCustomization: (String, String, String) -> Unit,
    val onGenerateVouchers:
        (String, Int, RateUnit, Int, RateUnit, Long, DataUnit, Long, DurationUnit, Int, Boolean) -> Unit,
    val onDeleteVoucherTemplate: (String) -> Unit,
    val onSetAccessPassEnabled: (String, Boolean) -> Unit,
    val onAssignAccessPass: (String, String) -> Unit,
    val onRevokeAccessPass: (String) -> Unit,
    val onSetClientPolicy:
        (String, String, String, Int, Int, Long, ClientPriority, Boolean, Boolean) -> Unit,
    val onSetClientPause: (String, String, Long) -> Unit,
    val onResetClientMonthlyUsage: (String) -> Unit,
    val onResetStats: () -> Unit,
)

private data class PolicyTarget(
    val deviceId: String,
    val ip: String,
    val identity: String,
    val liveClient: ClientTrafficStats? = null,
)

private const val ACTIVE_WINDOW_MS = 15_000L

@Composable
fun HotspotManagerPage(
    settings: Settings,
    stats: ManagerTrafficStats,
    status: UiStatus,
    actions: HotspotManagerActions,
    onBack: () -> Unit,
) {
    var editGlobal by remember { mutableStateOf(false) }
    var editPortal by remember { mutableStateOf(false) }
    var editVoucherStudio by remember { mutableStateOf(false) }
    var editingTarget by remember { mutableStateOf<PolicyTarget?>(null) }
    val now = System.currentTimeMillis()
    val activeClients = stats.clients.filter {
        it.lastSeenUnixMillis > 0L && now - it.lastSeenUnixMillis <= ACTIVE_WINDOW_MS
    }
    val activeIds = activeClients
        .map { it.deviceId.lowercase().ifBlank { it.ip } }
        .toSet()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(),
    ) {
        ScreenHeader(title = "Hotspot Control", onBack = onBack)

        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenPadding),
        ) {
            SectionLabel("Overview")

            SettingsChoice(
                label = SettingsText(
                    title = "Bandwidth",
                    subtitle = if (settings.dynamicBandwidthSharing) {
                        "Dynamic weighted sharing is enabled"
                    } else {
                        "Shared maximum for all hotspot clients"
                    },
                ),
                value = "${settings.globalDownloadMbps}/${settings.globalUploadMbps} Mbps",
                onClick = { editGlobal = true },
            )

            ManagerMetric(
                title = "Clients",
                value = when {
                    settings.maxClients <= 0 -> "${activeClients.size} / ∞"
                    else -> "${activeClients.size} / ${settings.maxClients}"
                },
                subtitle = if (settings.dynamicBandwidthSharing) {
                    "Dynamic sharing · priority aware"
                } else {
                    "Manual per-device limits"
                },
            )

            ManagerMetric(
                title = "Session data",
                value = Traffic.format(stats.totalBytes),
                subtitle = quotaSubtitle(stats.totalBytes, settings.globalQuotaBytes),
            )

            ManagerMetric(
                title = "Status",
                value = if (status == UiStatus.CONNECTED) "Active" else "Stopped",
                subtitle = "${settings.devicePolicies.size} known device(s)",
            )

            TextButton(
                onClick = actions.onResetStats,
                enabled = status == UiStatus.CONNECTED,
            ) {
                Text("Reset session counters")
            }

            SectionLabel("Access passes")

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = ShizziTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsLabel(
                    title = "Require an access pass",
                    subtitle = "New clients stay offline until they enter a valid voucher",
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = settings.accessPassRequired,
                    onCheckedChange = actions.onSetAccessPassRequired,
                )
            }

            SettingsChoice(
                label = SettingsText(
                    title = "Captive portal",
                    subtitle = "HTML page shown automatically before Internet access",
                ),
                value = settings.portalTitle,
                onClick = { editPortal = true },
            )

            val availableVouchers = settings.accessPasses.values.count {
                it.enabled && it.assignedDeviceId.isBlank()
            }
            val activeVouchers = settings.accessPasses.values.count { pass ->
                voucherState(pass, settings, now) == VoucherState.ACTIVE
            }
            val exhaustedVouchers = settings.accessPasses.values.count { pass ->
                voucherState(pass, settings, now) == VoucherState.EXHAUSTED
            }

            SettingsChoice(
                label = SettingsText(
                    title = "Voucher Studio",
                    subtitle = "Custom speed, data, validity, templates and batch generation",
                ),
                value = "Open",
                onClick = { editVoucherStudio = true },
            )

            ManagerMetric(
                title = "Voucher inventory",
                value = "${settings.accessPasses.size}",
                subtitle = "$availableVouchers available · $activeVouchers active · " +
                    "$exhaustedVouchers exhausted",
            )

            if (settings.voucherTemplates.isNotEmpty()) {
                ManagerMetric(
                    title = "Saved templates",
                    value = settings.voucherTemplates.size.toString(),
                    subtitle = settings.voucherTemplates.values
                        .sortedBy { it.name.lowercase() }
                        .take(3)
                        .joinToString(" · ") { it.name },
                )
            }

            if (settings.accessPasses.isNotEmpty()) {
                settings.accessPasses.values
                    .sortedByDescending { it.createdAtMillis }
                    .take(5)
                    .forEach { pass ->
                        ManagerMetric(
                            title = pass.code,
                            value = voucherState(pass, settings, now).label,
                            subtitle = accessPassSummary(pass),
                        )
                    }
            }

            SectionLabel("Clients")

            if (activeClients.isEmpty()) {
                Text(
                    text = if (status == UiStatus.CONNECTED) {
                        "No active client traffic has crossed Shizzi recently."
                    } else {
                        "Start Shizzi, connect a device, then traffic will appear here."
                    },
                    style = ShizziTheme.typography.body,
                    color = ShizziTheme.colors.onSurfaceMuted,
                    modifier = Modifier.padding(vertical = ShizziTheme.spacing.lg),
                )
            }

            activeClients.forEach { client ->
                val deviceId = client.deviceId.lowercase().ifBlank { client.ip }
                val stored = settings.devicePolicies[deviceId]
                    ?: settings.clientPolicies[client.ip]
                val usage = settings.monthlyUsageByDevice[deviceId]

                ClientRow(
                    client = client,
                    displayName = stored?.name.orEmpty(),
                    priority = stored?.priority ?: ClientPriority.NORMAL,
                    usage = usage,
                    monthlyQuota = stored?.monthlyQuotaBytes ?: 0L,
                    pausedUntilMillis = stored?.pausedUntilMillis ?: 0L,
                    onClick = {
                        editingTarget = PolicyTarget(
                            deviceId = deviceId,
                            ip = client.ip,
                            identity = client.macAddress.ifBlank { client.ip },
                            liveClient = client,
                        )
                    },
                )
            }

            val offlineKnown = settings.devicePolicies
                .filterKeys { it !in activeIds }
                .toSortedMap()

            if (offlineKnown.isNotEmpty()) {
                SectionLabel("Known devices")

                offlineKnown.forEach { (deviceId, policy) ->
                    val usage = settings.monthlyUsageByDevice[deviceId]
                    SettingsChoice(
                        label = SettingsText(
                            title = policy.name.ifBlank { deviceId },
                            subtitle = buildString {
                                append("Offline · ")
                                append(policy.priority.displayLabel())
                                usage?.let {
                                    append(" · ")
                                    append(Traffic.format(currentMonthBytes(it)))
                                    append(" this month")
                                }
                            },
                        ),
                        value = when {
                            policy.monthlyQuotaBytes > 0 ->
                                "${Traffic.format(policy.monthlyQuotaBytes)} / month"
                            else -> "No monthly cap"
                        },
                        onClick = {
                            editingTarget = PolicyTarget(
                                deviceId = deviceId,
                                ip = "",
                                identity = deviceId,
                            )
                        },
                    )
                }
            }

            if (settings.connectionHistory.isNotEmpty()) {
                SectionLabel("Recent history")
                settings.connectionHistory
                    .takeLast(10)
                    .asReversed()
                    .forEach { event ->
                        val policy = settings.devicePolicies[event.deviceId]
                        ManagerMetric(
                            title = policy?.name?.ifBlank { event.deviceId } ?: event.deviceId,
                            value = if (event.connected) "Connected" else "Disconnected",
                            subtitle = formatEventTime(event.atUnixMillis),
                        )
                    }
            }

            Spacer(Modifier.height(ShizziTheme.spacing.xxxl))
        }
    }

    if (editGlobal) {
        GlobalPolicySheet(
            settings = settings,
            onSave = {
                    down,
                    up,
                    quota,
                    clientDown,
                    clientUp,
                    clientQuota,
                    dynamic,
                    maxClients,
                ->
                actions.onSetGlobalPolicy(down, up, quota)
                actions.onSetDefaultClientPolicy(clientDown, clientUp, clientQuota)
                actions.onSetManagerOptions(dynamic, maxClients)
                editGlobal = false
            },
            onDismiss = { editGlobal = false },
        )
    }

    if (editVoucherStudio) {
        VoucherStudioSheet(
            settings = settings,
            actions = actions,
            onDismiss = { editVoucherStudio = false },
        )
    }

    if (editPortal) {
        PortalCustomizationSheet(
            settings = settings,
            onSave = { title, message, html ->
                actions.onSetPortalCustomization(title, message, html)
                editPortal = false
            },
            onDismiss = { editPortal = false },
        )
    }

    editingTarget?.let { target ->
        val stored = settings.devicePolicies[target.deviceId]
            ?: settings.clientPolicies[target.ip]
        val usage = settings.monthlyUsageByDevice[target.deviceId]

        ClientPolicySheet(
            target = target,
            stored = stored,
            usage = usage,
            onSave = { name, down, up, quota, priority, blocked, blockOnQuota ->
                actions.onSetClientPolicy(
                    target.deviceId,
                    target.ip,
                    name,
                    down,
                    up,
                    quota,
                    priority,
                    blocked,
                    blockOnQuota,
                )
                editingTarget = null
            },
            onPause = { durationMillis ->
                actions.onSetClientPause(target.deviceId, target.ip, durationMillis)
            },
            accessPasses = settings.accessPasses,
            onAssignAccessPass = { code ->
                actions.onAssignAccessPass(code, target.deviceId)
            },
            onRevokeAccessPass = { code ->
                actions.onRevokeAccessPass(code)
            },
            onResetMonthly = {
                actions.onResetClientMonthlyUsage(target.deviceId)
            },
            onDismiss = { editingTarget = null },
        )
    }
}

@Composable
private fun ClientRow(
    client: ClientTrafficStats,
    displayName: String,
    priority: ClientPriority,
    usage: MonthlyUsageRecord?,
    monthlyQuota: Long,
    pausedUntilMillis: Long,
    onClick: () -> Unit,
) {
    val now = System.currentTimeMillis()
    val state = when {
        pausedUntilMillis > now -> "Paused"
        client.blocked -> "Blocked"
        client.quotaReached -> "Quota reached"
        else -> limitsLabel(client.downloadBps, client.uploadBps)
    }

    val identity = client.macAddress.ifBlank { client.ip }

    SettingsChoice(
        label = SettingsText(
            title = displayName.ifBlank { identity },
            subtitle = "${Traffic.format(currentMonthBytes(usage))} this month · " +
                "${priority.displayLabel()} · $state",
        ),
        value = when {
            monthlyQuota > 0 -> "${Traffic.format(monthlyQuota)} / month"
            else -> "No monthly cap"
        },
        onClick = onClick,
    )
}

@Composable
private fun ManagerMetric(title: String, value: String, subtitle: String = "") {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = ShizziTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsLabel(
            title = title,
            subtitle = subtitle,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = ShizziTheme.typography.body,
            color = ShizziTheme.colors.onSurface,
        )
    }
}

@Composable
private fun GlobalPolicySheet(
    settings: Settings,
    onSave: (Int, Int, Long, Int, Int, Long, Boolean, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var down by remember { mutableStateOf(settings.globalDownloadMbps.toString()) }
    var up by remember { mutableStateOf(settings.globalUploadMbps.toString()) }
    var quotaMb by remember {
        mutableStateOf((settings.globalQuotaBytes / 1_000_000L).toString())
    }
    var clientDown by remember { mutableStateOf(settings.defaultClientDownloadMbps.toString()) }
    var clientUp by remember { mutableStateOf(settings.defaultClientUploadMbps.toString()) }
    var clientQuotaMb by remember {
        mutableStateOf((settings.defaultClientQuotaBytes / 1_000_000L).toString())
    }
    var dynamic by remember { mutableStateOf(settings.dynamicBandwidthSharing) }
    var maxClients by remember { mutableStateOf(settings.maxClients.toString()) }

    ThemedBottomSheet(onDismiss = onDismiss) {
        Text(
            text = "Hotspot Control",
            style = ShizziTheme.typography.heading,
            color = ShizziTheme.colors.onSurface,
        )

        Spacer(Modifier.height(ShizziTheme.spacing.md))

        NumberField("Global download Mbps (0 = unlimited)", down) { down = it }
        NumberField("Global upload Mbps (0 = unlimited)", up) { up = it }
        NumberField("Global quota MB (0 = unlimited)", quotaMb) { quotaMb = it }
        NumberField("Maximum active clients (0 = unlimited)", maxClients) { maxClients = it }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = ShizziTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsLabel(
                title = "Dynamic bandwidth sharing",
                subtitle = "Shares the global limit by priority between active clients",
                modifier = Modifier.weight(1f),
            )
            Switch(checked = dynamic, onCheckedChange = { dynamic = it })
        }

        Spacer(Modifier.height(ShizziTheme.spacing.lg))

        Text(
            text = "Default per client",
            style = ShizziTheme.typography.subheading,
            color = ShizziTheme.colors.onSurface,
        )

        NumberField("Download Mbps (0 = unlimited)", clientDown) { clientDown = it }
        NumberField("Upload Mbps (0 = unlimited)", clientUp) { clientUp = it }
        NumberField("Quota MB (0 = unlimited)", clientQuotaMb) { clientQuotaMb = it }

        SaveRow(
            onSave = {
                onSave(
                    positiveInt(down),
                    positiveInt(up),
                    megabytes(quotaMb),
                    positiveInt(clientDown),
                    positiveInt(clientUp),
                    megabytes(clientQuotaMb),
                    dynamic,
                    positiveInt(maxClients),
                )
            },
            onCancel = onDismiss,
        )
    }
}

@Composable
private fun ClientPolicySheet(
    target: PolicyTarget,
    stored: ClientPolicySetting?,
    usage: MonthlyUsageRecord?,
    accessPasses: Map<String, AccessPass>,
    onSave: (String, Int, Int, Long, ClientPriority, Boolean, Boolean) -> Unit,
    onPause: (Long) -> Unit,
    onAssignAccessPass: (String) -> Unit,
    onRevokeAccessPass: (String) -> Unit,
    onResetMonthly: () -> Unit,
    onDismiss: () -> Unit,
) {
    val live = target.liveClient
    val initialName = stored?.name.orEmpty()
    val initialDown = stored?.downloadMbps
        ?: ((live?.downloadBps ?: 0L) / 1_000_000L).toInt()
    val initialUp = stored?.uploadMbps
        ?: ((live?.uploadBps ?: 0L) / 1_000_000L).toInt()
    val initialQuota = stored?.monthlyQuotaBytes ?: 0L
    val initialPriority = stored?.priority ?: ClientPriority.NORMAL
    val initialBlocked = stored?.blocked ?: false
    val initialBlockOnQuota = stored?.blockOnQuota ?: false

    var name by remember(target.deviceId) { mutableStateOf(initialName) }
    var down by remember(target.deviceId) { mutableStateOf(initialDown.toString()) }
    var up by remember(target.deviceId) { mutableStateOf(initialUp.toString()) }
    var quotaMb by remember(target.deviceId) {
        mutableStateOf((initialQuota / 1_000_000L).toString())
    }
    var priority by remember(target.deviceId) { mutableStateOf(initialPriority) }
    var blocked by remember(target.deviceId) { mutableStateOf(initialBlocked) }
    var blockOnQuota by remember(target.deviceId) { mutableStateOf(initialBlockOnQuota) }

    ThemedBottomSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .imePadding(),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = ShizziTheme.spacing.lg),
            ) {
                Text(
            text = target.identity,
            style = ShizziTheme.typography.heading,
            color = ShizziTheme.colors.onSurface,
        )

        Text(
            text = if (target.ip.isBlank()) "Known device · offline" else target.ip,
            style = ShizziTheme.typography.body,
            color = ShizziTheme.colors.onSurfaceMuted,
        )

        Spacer(Modifier.height(ShizziTheme.spacing.md))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(32) },
            label = { Text("Device name (optional)") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = ShizziTheme.spacing.xs),
        )

        Text(
            text = "Quick profiles",
            style = ShizziTheme.typography.subheading,
            color = ShizziTheme.colors.onSurface,
            modifier = Modifier.padding(top = ShizziTheme.spacing.md),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ShizziTheme.spacing.xs),
        ) {
            TextButton(
                onClick = {
                    down = "5"
                    up = "2"
                    quotaMb = "2000"
                    priority = ClientPriority.NORMAL
                    blockOnQuota = true
                },
            ) { Text("Guest") }
            TextButton(
                onClick = {
                    down = "10"
                    up = "5"
                    quotaMb = "0"
                    priority = ClientPriority.PRIORITY
                },
            ) { Text("Family") }
            TextButton(
                onClick = {
                    down = "20"
                    up = "5"
                    quotaMb = "0"
                    priority = ClientPriority.VIP
                },
            ) { Text("VIP") }
        }

        Text(
            text = "Bandwidth presets",
            style = ShizziTheme.typography.subheading,
            color = ShizziTheme.colors.onSurface,
            modifier = Modifier.padding(top = ShizziTheme.spacing.md),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ShizziTheme.spacing.xs),
        ) {
            TextButton(onClick = { down = "0"; up = "0" }) { Text("∞") }
            TextButton(onClick = { down = "1"; up = "1" }) { Text("1/1") }
            TextButton(onClick = { down = "5"; up = "2" }) { Text("5/2") }
            TextButton(onClick = { down = "10"; up = "5" }) { Text("10/5") }
        }

        NumberField("Download Mbps (0 = unlimited)", down) { down = it }
        NumberField("Upload Mbps (0 = unlimited)", up) { up = it }
        NumberField("Monthly quota MB (0 = unlimited)", quotaMb) { quotaMb = it }

        Text(
            text = "Priority",
            style = ShizziTheme.typography.subheading,
            color = ShizziTheme.colors.onSurface,
            modifier = Modifier.padding(top = ShizziTheme.spacing.md),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ShizziTheme.spacing.xs),
        ) {
            ClientPriority.entries.forEach { choice ->
                TextButton(onClick = { priority = choice }) {
                    Text(if (priority == choice) "✓ ${choice.displayLabel()}" else choice.displayLabel())
                }
            }
        }

        SectionLabel("Access pass")
        val activePass = accessPasses.values.firstOrNull {
            it.assignedDeviceId == target.deviceId
        }
        if (activePass != null) {
            ManagerMetric(
                title = activePass.code,
                value = if (activePass.isExpired(System.currentTimeMillis())) "Expired" else "Active",
                subtitle = accessPassSummary(activePass),
            )
            TextButton(onClick = { onRevokeAccessPass(activePass.code) }) {
                Text("Revoke access pass")
            }
        } else {
            val availablePasses = accessPasses.values
                .filter { it.enabled && it.assignedDeviceId.isBlank() }
                .sortedByDescending { it.createdAtMillis }
                .take(5)

            if (availablePasses.isEmpty()) {
                Text(
                    text = "No available access pass.",
                    style = ShizziTheme.typography.body,
                    color = ShizziTheme.colors.onSurfaceMuted,
                )
            } else {
                availablePasses.forEach { pass ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = ShizziTheme.spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SettingsLabel(
                            title = pass.code,
                            subtitle = accessPassSummary(pass),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onAssignAccessPass(pass.code) }) {
                            Text("Apply")
                        }
                    }
                }
            }
        }

        SectionLabel("Usage")
        ManagerMetric("Today", Traffic.format(currentDayBytes(usage)))
        ManagerMetric("This week", Traffic.format(currentWeekBytes(usage)))
        ManagerMetric("This month", Traffic.format(currentMonthBytes(usage)))
        ManagerMetric("Total", Traffic.format(usage?.totalBytes ?: 0L))

        TextButton(onClick = onResetMonthly) {
            Text("Reset monthly usage")
        }

        SectionLabel("Temporary pause")
        val pauseUntil = stored?.pausedUntilMillis ?: 0L
        Text(
            text = if (pauseUntil > System.currentTimeMillis()) {
                "Paused until ${formatEventTime(pauseUntil)}"
            } else {
                "Not paused"
            },
            style = ShizziTheme.typography.body,
            color = ShizziTheme.colors.onSurfaceMuted,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ShizziTheme.spacing.xs),
        ) {
            TextButton(onClick = { onPause(0L) }) { Text("Resume") }
            TextButton(onClick = { onPause(5 * 60_000L) }) { Text("5 min") }
            TextButton(onClick = { onPause(30 * 60_000L) }) { Text("30 min") }
            TextButton(onClick = { onPause(60 * 60_000L) }) { Text("1 h") }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = ShizziTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsLabel(
                title = "Block Internet now",
                subtitle = "Keeps the device on Wi-Fi but stops Internet forwarding immediately",
                modifier = Modifier.weight(1f),
            )
            Switch(checked = blocked, onCheckedChange = { blocked = it })
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = ShizziTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsLabel(
                title = "Block when quota is reached",
                subtitle = "Keeps Internet available until the monthly quota is actually reached",
                modifier = Modifier.weight(1f),
            )
            Switch(checked = blockOnQuota, onCheckedChange = { blockOnQuota = it })
        }

            }

            SaveRow(
                onSave = {
                    onSave(
                        name.trim(),
                        positiveInt(down),
                        positiveInt(up),
                        megabytes(quotaMb),
                        priority,
                        blocked,
                        blockOnQuota,
                    )
                },
                onCancel = onDismiss,
            )
        }
    }
}

private enum class VoucherState(val label: String) {
    AVAILABLE("Available"),
    ACTIVE("Active"),
    EXPIRED("Expired"),
    EXHAUSTED("Exhausted"),
    DISABLED("Disabled"),
}

private enum class VoucherFilter(val label: String) {
    ALL("All"),
    AVAILABLE("Available"),
    ACTIVE("Active"),
    EXPIRED("Expired"),
    EXHAUSTED("Exhausted"),
    DISABLED("Disabled"),
}

private fun voucherUsedBytes(pass: AccessPass, settings: Settings): Long {
    if (pass.assignedDeviceId.isBlank()) return 0L
    val total = settings.monthlyUsageByDevice[pass.assignedDeviceId]?.totalBytes ?: 0L
    return (total - pass.startTotalBytes).coerceAtLeast(0L)
}

private fun voucherState(
    pass: AccessPass,
    settings: Settings,
    now: Long = System.currentTimeMillis(),
): VoucherState {
    if (!pass.enabled) return VoucherState.DISABLED
    if (pass.assignedDeviceId.isBlank()) return VoucherState.AVAILABLE
    if (pass.isExpired(now)) return VoucherState.EXPIRED
    if (pass.quotaBytes > 0L && voucherUsedBytes(pass, settings) >= pass.quotaBytes) {
        return VoucherState.EXHAUSTED
    }
    return VoucherState.ACTIVE
}

private fun templateSummary(template: VoucherTemplate): String {
    val down = if (template.downloadBps <= 0L) "∞" else {
        when (template.downloadUnit) {
            RateUnit.KBPS -> "${template.downloadValue} kbps"
            RateUnit.MBPS -> "${template.downloadValue} Mbps"
        }
    }
    val up = if (template.uploadBps <= 0L) "∞" else {
        when (template.uploadUnit) {
            RateUnit.KBPS -> "${template.uploadValue} kbps"
            RateUnit.MBPS -> "${template.uploadValue} Mbps"
        }
    }
    val quota = if (template.quotaValue <= 0L) {
        "unlimited data"
    } else {
        "${template.quotaValue} ${template.quotaUnit.label}"
    }
    val duration = if (template.durationValue <= 0L) {
        "no expiry"
    } else {
        "${template.durationValue} ${template.durationUnit.label}"
    }
    return "$down/$up · $quota · $duration"
}

@Composable
private fun VoucherStudioSheet(
    settings: Settings,
    actions: HotspotManagerActions,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("Standard") }
    var downloadValue by remember { mutableStateOf("10") }
    var downloadUnit by remember { mutableStateOf(RateUnit.MBPS) }
    var uploadValue by remember { mutableStateOf("5") }
    var uploadUnit by remember { mutableStateOf(RateUnit.MBPS) }
    var quotaValue by remember { mutableStateOf("100") }
    var quotaUnit by remember { mutableStateOf(DataUnit.GB) }
    var durationValue by remember { mutableStateOf("30") }
    var durationUnit by remember { mutableStateOf(DurationUnit.DAYS) }
    var quantity by remember { mutableStateOf("1") }
    var saveTemplate by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(VoucherFilter.ALL) }

    val context = LocalContext.current
    val now = System.currentTimeMillis()
    val filtered = settings.accessPasses.values
        .asSequence()
        .filter { pass ->
            query.isBlank() ||
                pass.code.contains(query.trim(), ignoreCase = true) ||
                pass.name.contains(query.trim(), ignoreCase = true)
        }
        .filter { pass ->
            val state = voucherState(pass, settings, now)
            when (filter) {
                VoucherFilter.ALL -> true
                VoucherFilter.AVAILABLE -> state == VoucherState.AVAILABLE
                VoucherFilter.ACTIVE -> state == VoucherState.ACTIVE
                VoucherFilter.EXPIRED -> state == VoucherState.EXPIRED
                VoucherFilter.EXHAUSTED -> state == VoucherState.EXHAUSTED
                VoucherFilter.DISABLED -> state == VoucherState.DISABLED
            }
        }
        .sortedByDescending { it.createdAtMillis }
        .take(50)
        .toList()

    fun applyTemplate(template: VoucherTemplate) {
        name = template.name
        downloadValue = template.downloadValue.toString()
        downloadUnit = template.downloadUnit
        uploadValue = template.uploadValue.toString()
        uploadUnit = template.uploadUnit
        quotaValue = template.quotaValue.toString()
        quotaUnit = template.quotaUnit
        durationValue = template.durationValue.toString()
        durationUnit = template.durationUnit
    }

    ThemedBottomSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.94f)
                .imePadding(),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = ShizziTheme.spacing.lg),
            ) {
                Text(
                    text = "Voucher Studio",
                    style = ShizziTheme.typography.heading,
                    color = ShizziTheme.colors.onSurface,
                )
                Text(
                    text = "Create your own prepaid offers. Each generated voucher keeps its " +
                        "speed, quota and validity even if the template changes later.",
                    style = ShizziTheme.typography.body,
                    color = ShizziTheme.colors.onSurfaceMuted,
                    modifier = Modifier.padding(bottom = ShizziTheme.spacing.md),
                )

                if (settings.voucherTemplates.isNotEmpty()) {
                    SectionLabel("Saved templates")
                    settings.voucherTemplates.values
                        .sortedBy { it.name.lowercase() }
                        .forEach { template ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = ShizziTheme.spacing.xs),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SettingsLabel(
                                    title = template.name,
                                    subtitle = templateSummary(template),
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { applyTemplate(template) }) {
                                    Text("Use")
                                }
                                TextButton(
                                    onClick = {
                                        actions.onDeleteVoucherTemplate(template.id)
                                    },
                                ) {
                                    Text("Delete")
                                }
                            }
                        }
                }

                SectionLabel("Create vouchers")

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(48) },
                    label = { Text("Offer name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = ShizziTheme.spacing.xs),
                )

                NumberUnitField(
                    label = "Download",
                    value = downloadValue,
                    unit = downloadUnit.label,
                    onValueChange = { downloadValue = it },
                    onToggleUnit = {
                        downloadUnit = if (downloadUnit == RateUnit.KBPS) {
                            RateUnit.MBPS
                        } else {
                            RateUnit.KBPS
                        }
                    },
                )

                NumberUnitField(
                    label = "Upload",
                    value = uploadValue,
                    unit = uploadUnit.label,
                    onValueChange = { uploadValue = it },
                    onToggleUnit = {
                        uploadUnit = if (uploadUnit == RateUnit.KBPS) {
                            RateUnit.MBPS
                        } else {
                            RateUnit.KBPS
                        }
                    },
                )

                NumberUnitField(
                    label = "Data volume",
                    value = quotaValue,
                    unit = quotaUnit.label,
                    onValueChange = { quotaValue = it },
                    onToggleUnit = {
                        quotaUnit = if (quotaUnit == DataUnit.MB) DataUnit.GB else DataUnit.MB
                    },
                )

                NumberUnitField(
                    label = "Validity after first activation",
                    value = durationValue,
                    unit = durationUnit.label,
                    onValueChange = { durationValue = it },
                    onToggleUnit = {
                        durationUnit = when (durationUnit) {
                            DurationUnit.MINUTES -> DurationUnit.HOURS
                            DurationUnit.HOURS -> DurationUnit.DAYS
                            DurationUnit.DAYS -> DurationUnit.MINUTES
                        }
                    },
                )

                NumberField("Quantity (1–500)", quantity) {
                    quantity = it
                }

                Text(
                    text = "Use 0 for unlimited speed, unlimited data, or no expiry.",
                    style = ShizziTheme.typography.body,
                    color = ShizziTheme.colors.onSurfaceMuted,
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = ShizziTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SettingsLabel(
                        title = "Save as template",
                        subtitle = "Reuse this offer for future voucher batches",
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = saveTemplate,
                        onCheckedChange = { saveTemplate = it },
                    )
                }

                Button(
                    onClick = {
                        actions.onGenerateVouchers(
                            name.trim(),
                            positiveInt(downloadValue),
                            downloadUnit,
                            positiveInt(uploadValue),
                            uploadUnit,
                            positiveLong(quotaValue),
                            quotaUnit,
                            positiveLong(durationValue),
                            durationUnit,
                            positiveInt(quantity).coerceIn(1, 500),
                            saveTemplate,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (positiveInt(quantity).coerceAtLeast(1) == 1) {
                            "Generate voucher"
                        } else {
                            "Generate ${positiveInt(quantity).coerceIn(1, 500)} vouchers"
                        },
                    )
                }

                SectionLabel("Voucher inventory")

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = {
                            val csv = voucherCsv(settings)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/csv"
                                putExtra(Intent.EXTRA_SUBJECT, "Shizzi vouchers")
                                putExtra(Intent.EXTRA_TEXT, csv)
                            }
                            context.startActivity(
                                Intent.createChooser(intent, "Export voucher CSV"),
                            )
                        },
                        enabled = settings.accessPasses.isNotEmpty(),
                    ) {
                        Text("Export CSV")
                    }
                }

                val states = settings.accessPasses.values.groupingBy {
                    voucherState(it, settings, now)
                }.eachCount()

                Text(
                    text = buildString {
                        append(states[VoucherState.AVAILABLE] ?: 0)
                        append(" available · ")
                        append(states[VoucherState.ACTIVE] ?: 0)
                        append(" active · ")
                        append(states[VoucherState.EXPIRED] ?: 0)
                        append(" expired · ")
                        append(states[VoucherState.EXHAUSTED] ?: 0)
                        append(" exhausted")
                    },
                    style = ShizziTheme.typography.body,
                    color = ShizziTheme.colors.onSurfaceMuted,
                    modifier = Modifier.padding(bottom = ShizziTheme.spacing.sm),
                )

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(32) },
                    label = { Text("Search code or offer") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = ShizziTheme.spacing.xs),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ShizziTheme.spacing.xs),
                ) {
                    listOf(
                        VoucherFilter.ALL,
                        VoucherFilter.AVAILABLE,
                        VoucherFilter.ACTIVE,
                    ).forEach { choice ->
                        TextButton(onClick = { filter = choice }) {
                            Text(if (filter == choice) "✓ ${choice.label}" else choice.label)
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ShizziTheme.spacing.xs),
                ) {
                    listOf(
                        VoucherFilter.EXPIRED,
                        VoucherFilter.EXHAUSTED,
                        VoucherFilter.DISABLED,
                    ).forEach { choice ->
                        TextButton(onClick = { filter = choice }) {
                            Text(if (filter == choice) "✓ ${choice.label}" else choice.label)
                        }
                    }
                }

                if (filtered.isEmpty()) {
                    Text(
                        text = "No voucher matches this filter.",
                        style = ShizziTheme.typography.body,
                        color = ShizziTheme.colors.onSurfaceMuted,
                        modifier = Modifier.padding(vertical = ShizziTheme.spacing.md),
                    )
                } else {
                    filtered.forEach { pass ->
                        val state = voucherState(pass, settings, now)
                        val used = voucherUsedBytes(pass, settings)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = ShizziTheme.spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SettingsLabel(
                                title = pass.code,
                                subtitle = buildString {
                                    append(pass.name)
                                    append(" · ")
                                    append(accessPassSummary(pass))
                                    if (pass.quotaBytes > 0L && pass.assignedDeviceId.isNotBlank()) {
                                        append(" · ")
                                        append(Traffic.format(used))
                                        append(" used")
                                        append(" · ")
                                        append(Traffic.format((pass.quotaBytes - used).coerceAtLeast(0L)))
                                        append(" left")
                                    }
                                    if (pass.activatedAtMillis > 0L) {
                                        append(" · activated ")
                                        append(formatEventTime(pass.activatedAtMillis))
                                    }
                                    if (pass.expiresAtMillis() > 0L) {
                                        append(" · expires ")
                                        append(formatEventTime(pass.expiresAtMillis()))
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = state.label,
                                    style = ShizziTheme.typography.body,
                                    color = ShizziTheme.colors.onSurface,
                                )
                                TextButton(
                                    onClick = {
                                        actions.onSetAccessPassEnabled(pass.code, !pass.enabled)
                                    },
                                ) {
                                    Text(if (pass.enabled) "Disable" else "Enable")
                                }
                            }
                        }
                    }
                }
            }

            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = ShizziTheme.spacing.md),
            ) {
                Text("Close")
            }
        }
    }
}

@Composable
private fun NumberUnitField(
    label: String,
    value: String,
    unit: String,
    onValueChange: (String) -> Unit,
    onToggleUnit: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { raw ->
                onValueChange(raw.filter(Char::isDigit).take(9))
            },
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = ShizziTheme.spacing.xs),
        )
        TextButton(onClick = onToggleUnit) {
            Text(unit)
        }
    }
}

@Composable
private fun PortalCustomizationSheet(
    settings: Settings,
    onSave: (String, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(settings.portalTitle) }
    var message by remember { mutableStateOf(settings.portalMessage) }
    var html by remember { mutableStateOf(settings.portalHtml) }

    ThemedBottomSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .imePadding(),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = ShizziTheme.spacing.lg),
            ) {
                Text(
                    text = "Captive portal",
                    style = ShizziTheme.typography.heading,
                    color = ShizziTheme.colors.onSurface,
                )
                Text(
                    text = "Clients see this page before Internet access is granted.",
                    style = ShizziTheme.typography.body,
                    color = ShizziTheme.colors.onSurfaceMuted,
                    modifier = Modifier.padding(bottom = ShizziTheme.spacing.md),
                )

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(80) },
                    label = { Text("Portal title") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = ShizziTheme.spacing.xs),
                )

                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it.take(240) },
                    label = { Text("Welcome message") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = ShizziTheme.spacing.xs),
                )

                Text(
                    text = "Custom HTML (optional)",
                    style = ShizziTheme.typography.subheading,
                    color = ShizziTheme.colors.onSurface,
                    modifier = Modifier.padding(top = ShizziTheme.spacing.md),
                )
                Text(
                    text = "Leave empty for the built-in page. Supported placeholders: " +
                        "{{TITLE}}, {{MESSAGE}}, {{STATUS}}, {{FORM_ACTION}}.",
                    style = ShizziTheme.typography.body,
                    color = ShizziTheme.colors.onSurfaceMuted,
                )

                OutlinedTextField(
                    value = html,
                    onValueChange = { html = it.take(100_000) },
                    label = { Text("HTML / CSS") },
                    minLines = 8,
                    maxLines = 18,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = ShizziTheme.spacing.xs),
                )
            }

            SaveRow(
                onSave = { onSave(title.trim(), message.trim(), html) },
                onCancel = onDismiss,
            )
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { raw ->
            onValueChange(raw.filter(Char::isDigit).take(9))
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = ShizziTheme.spacing.xs),
    )
}

@Composable
private fun SaveRow(onSave: () -> Unit, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = ShizziTheme.spacing.lg),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onCancel) {
            Text("Cancel")
        }
        Button(onClick = onSave) {
            Text("Save")
        }
    }
}

private fun positiveInt(raw: String): Int = raw.toIntOrNull()?.coerceAtLeast(0) ?: 0

private fun positiveLong(raw: String): Long =
    raw.toLongOrNull()?.coerceAtLeast(0L) ?: 0L

private fun megabytes(raw: String): Long =
    (raw.toLongOrNull()?.coerceAtLeast(0) ?: 0L) * 1_000_000L

private fun quotaSubtitle(used: Long, quota: Long): String = when {
    quota <= 0 -> "No global data cap"
    else -> "${Traffic.format((quota - used).coerceAtLeast(0))} remaining"
}

private fun limitsLabel(downloadBps: Long, uploadBps: Long): String {
    val down = if (downloadBps <= 0) "∞" else "${downloadBps / 1_000_000}M"
    val up = if (uploadBps <= 0) "∞" else "${uploadBps / 1_000_000}M"
    return "$down/$up"
}

private fun ClientPriority.displayLabel(): String = when (this) {
    ClientPriority.NORMAL -> "Normal"
    ClientPriority.PRIORITY -> "Priority"
    ClientPriority.VIP -> "VIP"
}

private fun currentMonthBytes(record: MonthlyUsageRecord?): Long {
    if (record == null || record.month != YearMonth.now().toString()) return 0L
    return record.bytes
}

private fun currentDayBytes(record: MonthlyUsageRecord?): Long {
    if (record == null || record.day != LocalDate.now().toString()) return 0L
    return record.dayBytes
}

private fun currentWeekBytes(record: MonthlyUsageRecord?): Long {
    if (record == null) return 0L
    val today = LocalDate.now()
    val fields = WeekFields.ISO
    val key = "%04d-W%02d".format(
        today.get(fields.weekBasedYear()),
        today.get(fields.weekOfWeekBasedYear()),
    )
    return if (record.week == key) record.weekBytes else 0L
}

private fun accessPassSummary(pass: AccessPass): String {
    val speed = if (pass.downloadBps <= 0L && pass.uploadBps <= 0L) {
        "unlimited speed"
    } else {
        "${formatVoucherRate(pass.downloadBps, pass.downloadUnit)}/" +
            formatVoucherRate(pass.uploadBps, pass.uploadUnit)
    }
    val quota = when {
        pass.quotaBytes <= 0L -> "unlimited data"
        else -> Traffic.format(pass.quotaBytes)
    }
    val duration = when {
        pass.durationMinutes <= 0L -> "no expiry"
        pass.durationUnit == DurationUnit.MINUTES -> "${pass.durationMinutes} min"
        pass.durationUnit == DurationUnit.HOURS -> "${pass.durationMinutes / 60L} h"
        else -> "${pass.durationMinutes / 1_440L} days"
    }
    return "$speed · $quota · $duration"
}

private fun formatVoucherRate(bps: Long, unit: RateUnit): String {
    if (bps <= 0L) return "∞"
    return when (unit) {
        RateUnit.KBPS -> "${bps / 1_000L} kbps"
        RateUnit.MBPS -> "${bps / 1_000_000L} Mbps"
    }
}


private fun voucherCsv(settings: Settings): String {
    fun csvCell(value: String): String = """ + value.replace(""", """") + """

    return buildString {
        appendLine(
            "code,name,state,download,upload,quota_bytes,duration_minutes," +
                "activated_at,expires_at,device_id,used_bytes,remaining_bytes",
        )
        val now = System.currentTimeMillis()
        settings.accessPasses.values
            .sortedByDescending { it.createdAtMillis }
            .forEach { pass ->
                val used = voucherUsedBytes(pass, settings)
                val remaining = when {
                    pass.quotaBytes <= 0L -> 0L
                    else -> (pass.quotaBytes - used).coerceAtLeast(0L)
                }
                append(csvCell(pass.code))
                append(',')
                append(csvCell(pass.name))
                append(',')
                append(csvCell(voucherState(pass, settings, now).label))
                append(',')
                append(csvCell(formatVoucherRate(pass.downloadBps, pass.downloadUnit)))
                append(',')
                append(csvCell(formatVoucherRate(pass.uploadBps, pass.uploadUnit)))
                append(',')
                append(pass.quotaBytes)
                append(',')
                append(pass.durationMinutes)
                append(',')
                append(pass.activatedAtMillis)
                append(',')
                append(pass.expiresAtMillis())
                append(',')
                append(csvCell(pass.assignedDeviceId))
                append(',')
                append(used)
                append(',')
                append(remaining)
                appendLine()
            }
    }
}

private fun formatEventTime(atUnixMillis: Long): String {
    if (atUnixMillis <= 0L) return ""
    return DateTimeFormatter.ofPattern("dd/MM HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(atUnixMillis))
}
