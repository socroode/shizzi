package dev.shizzi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import dev.shizzi.ClientPolicySetting
import dev.shizzi.ClientTrafficStats
import dev.shizzi.ManagerTrafficStats
import dev.shizzi.Settings
import dev.shizzi.Traffic
import dev.shizzi.UiStatus
import dev.shizzi.ui.theme.ScreenPadding
import dev.shizzi.ui.theme.ShizziTheme

data class HotspotManagerActions(
    val onSetGlobalPolicy: (Int, Int, Long) -> Unit,
    val onSetDefaultClientPolicy: (Int, Int, Long) -> Unit,
    val onSetClientPolicy:
        (String, String, String, Int, Int, Long, Boolean, Boolean) -> Unit,
    val onResetStats: () -> Unit,
)

@Composable
fun HotspotManagerPage(
    settings: Settings,
    stats: ManagerTrafficStats,
    status: UiStatus,
    actions: HotspotManagerActions,
    onBack: () -> Unit,
) {
    var editGlobal by remember { mutableStateOf(false) }
    var editingClient by remember { mutableStateOf<ClientTrafficStats?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(),
    ) {
        ScreenHeader(title = "Hotspot Manager Pro", onBack = onBack)

        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenPadding),
        ) {
            SectionLabel("Global")

            SettingsChoice(
                label = SettingsText(
                    title = "Bandwidth",
                    subtitle = "Shared maximum for all hotspot clients",
                ),
                value = "${settings.globalDownloadMbps}/${settings.globalUploadMbps} Mbps",
                onClick = { editGlobal = true },
            )

            ManagerMetric(
                title = "Session data",
                value = Traffic.format(stats.totalBytes),
                subtitle = quotaSubtitle(stats.totalBytes, settings.globalQuotaBytes),
            )

            ManagerMetric(
                title = "Status",
                value = if (status == UiStatus.CONNECTED) "Active" else "Stopped",
                subtitle = "${stats.clients.size} routed client(s)",
            )

            TextButton(
                onClick = actions.onResetStats,
                enabled = status == UiStatus.CONNECTED,
            ) {
                Text("Reset session counters")
            }

            SectionLabel("Clients")

            if (stats.clients.isEmpty()) {
                Text(
                    text = if (status == UiStatus.CONNECTED) {
                        "No client traffic has crossed Shizzi yet."
                    } else {
                        "Start Shizzi, connect a device, then traffic will appear here."
                    },
                    style = ShizziTheme.typography.body,
                    color = ShizziTheme.colors.onSurfaceMuted,
                    modifier = Modifier.padding(vertical = ShizziTheme.spacing.lg),
                )
            }

            stats.clients.forEach { client ->
                val month = java.time.YearMonth.now().toString()
                val monthlyUsed = settings.monthlyUsageByDevice[client.deviceId.lowercase()]
                    ?.takeIf { it.month == month }
                    ?.bytes
                    ?: 0L
                val stored = settings.devicePolicies[client.deviceId.lowercase()]
                    ?: settings.clientPolicies[client.ip]

                ClientRow(
                    client = client,
                    displayName = stored?.name.orEmpty(),
                    monthlyUsed = monthlyUsed,
                    monthlyQuota = stored?.monthlyQuotaBytes ?: 0L,
                    onClick = { editingClient = client },
                )
            }

            Spacer(Modifier.height(ShizziTheme.spacing.xxxl))
        }
    }

    if (editGlobal) {
        GlobalPolicySheet(
            settings = settings,
            onSave = { down, up, quota, clientDown, clientUp, clientQuota ->
                actions.onSetGlobalPolicy(down, up, quota)
                actions.onSetDefaultClientPolicy(clientDown, clientUp, clientQuota)
                editGlobal = false
            },
            onDismiss = { editGlobal = false },
        )
    }

    editingClient?.let { client ->
        val stored = settings.devicePolicies[client.deviceId.lowercase()]
            ?: settings.clientPolicies[client.ip]
        val month = java.time.YearMonth.now().toString()
        val monthlyUsed = settings.monthlyUsageByDevice[client.deviceId.lowercase()]
            ?.takeIf { it.month == month }
            ?.bytes
            ?: 0L

        ClientPolicySheet(
            client = client,
            stored = stored,
            monthlyUsed = monthlyUsed,
            onSave = { name, down, up, monthlyQuota, blocked, blockOnQuota ->
                actions.onSetClientPolicy(
                    client.deviceId,
                    client.ip,
                    name,
                    down,
                    up,
                    monthlyQuota,
                    blocked,
                    blockOnQuota,
                )
                editingClient = null
            },
            onDismiss = { editingClient = null },
        )
    }
}

@Composable
private fun ClientRow(
    client: ClientTrafficStats,
    displayName: String,
    monthlyUsed: Long,
    monthlyQuota: Long,
    onClick: () -> Unit,
) {
    val state = when {
        client.blocked -> "Blocked"
        client.quotaReached -> "Quota reached"
        else -> limitsLabel(client.downloadBps, client.uploadBps)
    }

    val identity = when {
        client.macAddress.isNotBlank() -> client.macAddress
        else -> client.ip
    }

    SettingsChoice(
        label = SettingsText(
            title = displayName.ifBlank { identity },
            subtitle = "${Traffic.format(monthlyUsed)} this month · $state · ${client.ip}",
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
    onSave: (Int, Int, Long, Int, Int, Long) -> Unit,
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

    ThemedBottomSheet(onDismiss = onDismiss) {
        Text(
            text = "Global traffic policy",
            style = ShizziTheme.typography.heading,
            color = ShizziTheme.colors.onSurface,
        )

        Spacer(Modifier.height(ShizziTheme.spacing.md))

        NumberField("Global download Mbps (0 = unlimited)", down) { down = it }
        NumberField("Global upload Mbps (0 = unlimited)", up) { up = it }
        NumberField("Global quota MB (0 = unlimited)", quotaMb) { quotaMb = it }

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
                )
            },
            onCancel = onDismiss,
        )
    }
}

@Composable
private fun ClientPolicySheet(
    client: ClientTrafficStats,
    stored: ClientPolicySetting?,
    monthlyUsed: Long,
    onSave: (String, Int, Int, Long, Boolean, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialName = stored?.name.orEmpty()
    val initialDown = stored?.downloadMbps
        ?: (client.downloadBps / 1_000_000L).toInt()
    val initialUp = stored?.uploadMbps
        ?: (client.uploadBps / 1_000_000L).toInt()
    val initialQuota = stored?.monthlyQuotaBytes ?: 0L
    val initialBlocked = stored?.blocked ?: false
    val initialBlockOnQuota = stored?.blockOnQuota ?: false

    var name by remember(client.deviceId) { mutableStateOf(initialName) }
    var down by remember(client.ip) { mutableStateOf(initialDown.toString()) }
    var up by remember(client.ip) { mutableStateOf(initialUp.toString()) }
    var quotaMb by remember(client.ip) {
        mutableStateOf((initialQuota / 1_000_000L).toString())
    }
    var blocked by remember(client.ip) { mutableStateOf(initialBlocked) }
    var blockOnQuota by remember(client.ip) { mutableStateOf(initialBlockOnQuota) }

    ThemedBottomSheet(onDismiss = onDismiss) {
        Text(
            text = if (client.macAddress.isNotBlank()) client.macAddress else client.ip,
            style = ShizziTheme.typography.heading,
            color = ShizziTheme.colors.onSurface,
        )

        Text(
            text = "${Traffic.format(monthlyUsed)} used this month · ${client.ip}",
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
            TextButton(onClick = { down = "20"; up = "5" }) { Text("20/5") }
        }

        NumberField("Download Mbps (0 = unlimited)", down) { down = it }
        NumberField("Upload Mbps (0 = unlimited)", up) { up = it }
        NumberField("Monthly quota MB (0 = unlimited)", quotaMb) { quotaMb = it }

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

        SaveRow(
            onSave = {
                onSave(
                    name.trim(),
                    positiveInt(down),
                    positiveInt(up),
                    megabytes(quotaMb),
                    blocked,
                    blockOnQuota,
                )
            },
            onCancel = onDismiss,
        )
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
