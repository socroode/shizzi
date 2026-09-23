package dev.shizzi.ui

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
import dev.shizzi.AccessPass
import dev.shizzi.ClientPolicySetting
import dev.shizzi.ClientPriority
import dev.shizzi.ClientTrafficStats
import dev.shizzi.ManagerTrafficStats
import dev.shizzi.MonthlyUsageRecord
import dev.shizzi.Settings
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
    val onCreateAccessPass: (String, Int, Int, Long, Long) -> Unit,
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ShizziTheme.spacing.xs),
            ) {
                TextButton(
                    onClick = {
                        actions.onCreateAccessPass(
                            "Guest 1h",
                            5,
                            2,
                            2_000_000_000L,
                            60L,
                        )
                    },
                ) { Text("Guest 1h") }
                TextButton(
                    onClick = {
                        actions.onCreateAccessPass(
                            "Day",
                            10,
                            5,
                            10_000_000_000L,
                            1_440L,
                        )
                    },
                ) { Text("Day") }
                TextButton(
                    onClick = {
                        actions.onCreateAccessPass(
                            "VIP 24h",
                            20,
                            5,
                            0L,
                            1_440L,
                        )
                    },
                ) { Text("VIP") }
            }

            if (settings.accessPasses.isEmpty()) {
                Text(
                    text = "No access pass yet. Create one with a preset above.",
                    style = ShizziTheme.typography.body,
                    color = ShizziTheme.colors.onSurfaceMuted,
                    modifier = Modifier.padding(vertical = ShizziTheme.spacing.sm),
                )
            } else {
                settings.accessPasses.values
                    .sortedByDescending { it.createdAtMillis }
                    .take(8)
                    .forEach { pass ->
                        val assignedName = settings.devicePolicies[pass.assignedDeviceId]
                            ?.name
                            .orEmpty()
                        val stateText = when {
                            !pass.enabled -> "Disabled"
                            pass.assignedDeviceId.isBlank() -> "Available"
                            pass.isExpired(now) -> "Expired"
                            assignedName.isNotBlank() -> "Used by $assignedName"
                            else -> "Used"
                        }
                        ManagerMetric(
                            title = pass.code,
                            value = stateText,
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
    val speed = when {
        pass.downloadMbps <= 0 && pass.uploadMbps <= 0 -> "unlimited speed"
        else -> "${pass.downloadMbps}/${pass.uploadMbps} Mbps"
    }
    val quota = when {
        pass.quotaBytes <= 0L -> "unlimited data"
        else -> Traffic.format(pass.quotaBytes)
    }
    val duration = when {
        pass.durationMinutes <= 0L -> "no expiry"
        pass.durationMinutes < 60L -> "${pass.durationMinutes} min"
        pass.durationMinutes % 1_440L == 0L -> "${pass.durationMinutes / 1_440L} day"
        else -> "${pass.durationMinutes / 60L} h"
    }
    return "$speed · $quota · $duration"
}

private fun formatEventTime(atUnixMillis: Long): String {
    if (atUnixMillis <= 0L) return ""
    return DateTimeFormatter.ofPattern("dd/MM HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(atUnixMillis))
}
