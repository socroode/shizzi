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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.shizzi.App
import dev.shizzi.CybercafeState
import dev.shizzi.DeviceBinding
import dev.shizzi.Offer
import dev.shizzi.PrepaidAccount
import dev.shizzi.Voucher
import dev.shizzi.VoucherKind
import dev.shizzi.ui.theme.ScreenPadding
import dev.shizzi.ui.theme.ShizziTheme
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class AdminTab {
    ACCOUNTS,
    VOUCHERS,
    DEVICES,
}

@Composable
fun CybercafeAdminPage(onBack: () -> Unit) {
    val store = App.instance.cybercafeStore
    val state by store.state.collectAsState()
    var tab by remember { mutableStateOf(AdminTab.ACCOUNTS) }
    var message by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(),
    ) {
        ScreenHeader(title = "Shizzi Hotspot", onBack = onBack)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(ShizziTheme.spacing.xs),
        ) {
            TextButton(onClick = { tab = AdminTab.ACCOUNTS }) { Text("Accounts") }
            TextButton(onClick = { tab = AdminTab.VOUCHERS }) { Text("Vouchers") }
            TextButton(onClick = { tab = AdminTab.DEVICES }) { Text("Devices") }
        }

        if (message.isNotBlank()) {
            Text(
                text = message,
                style = ShizziTheme.typography.body,
                color = ShizziTheme.colors.onSurfaceMuted,
                modifier = Modifier.padding(horizontal = ScreenPadding),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenPadding),
        ) {
            when (tab) {
                AdminTab.ACCOUNTS -> AccountEditor(
                    state = state,
                    onMessage = { message = it },
                )
                AdminTab.VOUCHERS -> VoucherEditor(
                    state = state,
                    onMessage = { message = it },
                )
                AdminTab.DEVICES -> ConnectedDevices(
                    state = state,
                    onMessage = { message = it },
                )
            }
            Spacer(Modifier.height(ShizziTheme.spacing.xxxl))
        }
    }
}

@Composable
private fun AccountEditor(
    state: CybercafeState,
    onMessage: (String) -> Unit,
) {
    val store = App.instance.cybercafeStore
    var number by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var editingPinFor by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }

    SectionTitle("Account Editor")
    OutlinedTextField(
        value = number,
        onValueChange = { number = it.filter(Char::isDigit) },
        label = { Text("Numéro de compte") },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("Nom") },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = pin,
        onValueChange = { pin = it },
        label = { Text("Mot de passe / PIN") },
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = {
            val result = store.createAccount(number, pin, name, System.currentTimeMillis())
            onMessage(result.message)
            if (result.success) {
                number = ""
                name = ""
                pin = ""
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Créer le compte")
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = ShizziTheme.spacing.lg))

    if (state.accounts.isEmpty()) {
        Text("Aucun compte utilisateur.")
    }

    state.accounts.values.sortedBy(PrepaidAccount::number).forEach { account ->
        AccountRow(account)

        Row(
            horizontalArrangement = Arrangement.spacedBy(ShizziTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (account.enabled) "Actif" else "Suspendu",
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = account.enabled,
                onCheckedChange = {
                    onMessage(store.setAccountEnabled(account.number, it).message)
                },
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(ShizziTheme.spacing.xs)) {
            TextButton(onClick = {
                editingPinFor = if (editingPinFor == account.number) "" else account.number
                newPin = ""
            }) {
                Text("Changer code")
            }
            TextButton(onClick = {
                val keys = state.devices.values
                    .filter { it.accountNumber == account.number }
                    .map(DeviceBinding::deviceKey)
                keys.forEach(store::unbindDevice)
                onMessage("Appareil dissocié.")
            }) {
                Text("Dissocier")
            }
            TextButton(onClick = {
                onMessage(store.deleteAccount(account.number).message)
            }) {
                Text("Supprimer")
            }
        }

        if (editingPinFor == account.number) {
            OutlinedTextField(
                value = newPin,
                onValueChange = { newPin = it },
                label = { Text("Nouveau code") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    val result = store.resetPin(account.number, newPin)
                    onMessage(result.message)
                    if (result.success) {
                        editingPinFor = ""
                        newPin = ""
                    }
                },
            ) {
                Text("Enregistrer le code")
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = ShizziTheme.spacing.md))
    }
}

@Composable
private fun AccountRow(account: PrepaidAccount) {
    val now = System.currentTimeMillis()
    Text(
        text = account.name.ifBlank { "Compte " + account.number },
        style = ShizziTheme.typography.heading,
        color = ShizziTheme.colors.onSurface,
    )
    Text(
        text = "N° " + account.number,
        color = ShizziTheme.colors.onSurfaceMuted,
    )

    val plan = when {
        account.hasUnlimited(now) -> account.unlimitedPlanName.ifBlank { "Illimité" }
        account.hasData(now) -> "Data"
        else -> "Aucun forfait actif"
    }
    Text("Forfait : " + plan)
    Text("Data restante : " + formatBytes(account.dataBalanceBytes))
    Text(
        "Débit : " +
            formatMbps(account.currentDownloadBps(now)) +
            " ↓ / " +
            formatMbps(account.currentUploadBps(now)) +
            " ↑",
    )
    Text("Consommation : " + formatBytes(account.totalUpBytes + account.totalDownBytes))
    if (account.hasUnlimited(now)) {
        Text("Illimité jusqu'au " + formatDate(account.unlimitedUntilMillis))
    } else if (account.dataValidUntilMillis > 0L) {
        Text("Data valable jusqu'au " + formatDate(account.dataValidUntilMillis))
    }
}

@Composable
private fun VoucherEditor(
    state: CybercafeState,
    onMessage: (String) -> Unit,
) {
    val store = App.instance.cybercafeStore
    var offerName by remember { mutableStateOf("") }
    var down by remember { mutableStateOf("2") }
    var up by remember { mutableStateOf("1") }
    var quota by remember { mutableStateOf("12") }
    var days by remember { mutableStateOf("30") }
    var price by remember { mutableStateOf("1000") }
    var unlimited by remember { mutableStateOf(false) }
    var lastGenerated by remember { mutableStateOf("") }

    SectionTitle("Voucher Editor")
    Text("Créer / modifier une offre", style = ShizziTheme.typography.heading)
    OutlinedTextField(
        value = offerName,
        onValueChange = { offerName = it },
        label = { Text("Nom de l'offre") },
        modifier = Modifier.fillMaxWidth(),
    )

    Row(horizontalArrangement = Arrangement.spacedBy(ShizziTheme.spacing.xs)) {
        OutlinedTextField(
            value = down,
            onValueChange = { down = it.filter(Char::isDigit) },
            label = { Text("↓ Mbps") },
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = up,
            onValueChange = { up = it.filter(Char::isDigit) },
            label = { Text("↑ Mbps") },
            modifier = Modifier.weight(1f),
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Illimité", modifier = Modifier.weight(1f))
        Switch(checked = unlimited, onCheckedChange = { unlimited = it })
    }

    if (!unlimited) {
        OutlinedTextField(
            value = quota,
            onValueChange = { quota = it.filter(Char::isDigit) },
            label = { Text("Data (Go)") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    OutlinedTextField(
        value = days,
        onValueChange = { days = it.filter(Char::isDigit) },
        label = { Text("Validité (jours)") },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = price,
        onValueChange = { price = it.filter(Char::isDigit) },
        label = { Text("Prix (FCFP)") },
        modifier = Modifier.fillMaxWidth(),
    )

    Button(
        onClick = {
            val name = offerName.trim()
            val id = name.lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
            val result = store.upsertOffer(
                Offer(
                    id = id,
                    name = name,
                    kind = if (unlimited) VoucherKind.UNLIMITED else VoucherKind.DATA,
                    downloadBps = (down.toLongOrNull() ?: 0L) * 1_000_000L,
                    uploadBps = (up.toLongOrNull() ?: 0L) * 1_000_000L,
                    quotaBytes = if (unlimited) 0L else
                        (quota.toLongOrNull() ?: 0L) * 1_000_000_000L,
                    durationDays = days.toIntOrNull() ?: 0,
                    priceXpf = price.toIntOrNull() ?: 0,
                ),
            )
            onMessage(result.message)
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Enregistrer l'offre")
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = ShizziTheme.spacing.lg))
    Text("Offres et génération", style = ShizziTheme.typography.heading)

    state.offers.values.sortedBy(Offer::name).forEach { offer ->
        Text(offer.name, style = ShizziTheme.typography.heading)
        Text(
            formatMbps(offer.downloadBps) + " ↓ / " +
                formatMbps(offer.uploadBps) + " ↑ · " +
                if (offer.kind == VoucherKind.UNLIMITED) "Illimité" else formatBytes(offer.quotaBytes),
        )
        Text(offer.durationDays.toString() + " jours · " + formatXpf(offer.priceXpf))
        Button(
            onClick = {
                val generated = store.generateVouchers(
                    offer.id,
                    1,
                    System.currentTimeMillis(),
                )
                lastGenerated = generated.firstOrNull()?.code.orEmpty()
                onMessage(
                    if (lastGenerated.isBlank()) "Échec de génération."
                    else "Voucher généré : " + lastGenerated,
                )
            },
        ) {
            Text("Générer 1 voucher")
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = ShizziTheme.spacing.md))
    }

    if (lastGenerated.isNotBlank()) {
        Text("Dernier code : " + lastGenerated, style = ShizziTheme.typography.heading)
    }

    SectionTitle("Inventaire")
    if (state.vouchers.isEmpty()) {
        Text("Aucun voucher généré.")
    }
    state.vouchers.values
        .sortedByDescending(Voucher::createdAtMillis)
        .take(30)
        .forEach { voucher ->
            val offer = state.offers[voucher.offerId]
            Text(voucher.code, style = ShizziTheme.typography.heading)
            Text(offer?.name ?: voucher.offerId)
            Text(
                if (voucher.redeemedByAccount.isBlank()) {
                    if (voucher.enabled) "Disponible" else "Désactivé"
                } else {
                    "Utilisé par " + voucher.redeemedByAccount
                },
            )
            if (voucher.redeemedByAccount.isBlank()) {
                TextButton(
                    onClick = {
                        onMessage(
                            store.setVoucherEnabled(voucher.code, !voucher.enabled).message,
                        )
                    },
                ) {
                    Text(if (voucher.enabled) "Désactiver" else "Réactiver")
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = ShizziTheme.spacing.sm))
        }
}

@Composable
private fun ConnectedDevices(
    state: CybercafeState,
    onMessage: (String) -> Unit,
) {
    val store = App.instance.cybercafeStore
    SectionTitle("Connected Devices")

    if (state.devices.isEmpty()) {
        Text(
            "Aucun appareil associé. Les liaisons seront créées par le portail captif " +
                "après identification fiable de l'appareil.",
        )
        return
    }

    state.devices.values
        .sortedByDescending(DeviceBinding::lastSeenMillis)
        .forEach { device ->
            val account = state.accounts[device.accountNumber]
            Text(
                account?.name?.ifBlank { "Compte " + device.accountNumber }
                    ?: "Compte " + device.accountNumber,
                style = ShizziTheme.typography.heading,
            )
            Text("Appareil : " + device.deviceKey)
            if (device.ip.isNotBlank()) Text("IP : " + device.ip)
            if (device.mac.isNotBlank()) Text("MAC : " + device.mac)
            if (device.lastSeenMillis > 0L) {
                Text("Vu : " + formatDate(device.lastSeenMillis))
            }
            TextButton(onClick = {
                store.unbindDevice(device.deviceKey)
                onMessage("Appareil dissocié.")
            }) {
                Text("Dissocier")
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = ShizziTheme.spacing.md))
        }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(ShizziTheme.spacing.lg))
    Text(
        text = text,
        style = ShizziTheme.typography.heading,
        color = ShizziTheme.colors.onSurface,
        modifier = Modifier.padding(vertical = ShizziTheme.spacing.sm),
    )
}

private fun formatBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L)
    return when {
        value >= 1_000_000_000L -> String.format(Locale.FRANCE, "%.2f Go", value / 1_000_000_000.0)
        value >= 1_000_000L -> String.format(Locale.FRANCE, "%.1f Mo", value / 1_000_000.0)
        else -> value.toString() + " o"
    }
}

private fun formatMbps(bitsPerSecond: Long): String =
    String.format(Locale.FRANCE, "%.1f Mbps", bitsPerSecond.coerceAtLeast(0L) / 1_000_000.0)

private fun formatXpf(value: Int): String =
    NumberFormat.getIntegerInstance(Locale.FRANCE).format(value) + " FCFP"

private fun formatDate(epochMillis: Long): String {
    if (epochMillis <= 0L) return "—"
    val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
    return Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(formatter)
}
