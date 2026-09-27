package dev.shizzi

import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.math.max

private const val DAY_MILLIS = 86_400_000L
private const val GB = 1_000_000_000L

enum class VoucherKind {
    DATA,
    UNLIMITED,
}

data class Offer(
    val id: String,
    val name: String,
    val kind: VoucherKind,
    val downloadBps: Long,
    val uploadBps: Long,
    val quotaBytes: Long,
    val durationDays: Int,
    val priceXpf: Int,
)

data class PrepaidAccount(
    val number: String,
    val name: String,
    val pinSalt: String,
    val pinHash: String,
    val enabled: Boolean = true,
    val dataBalanceBytes: Long = 0L,
    val dataValidUntilMillis: Long = 0L,
    val dataDownloadBps: Long = 0L,
    val dataUploadBps: Long = 0L,
    val unlimitedUntilMillis: Long = 0L,
    val unlimitedDownloadBps: Long = 0L,
    val unlimitedUploadBps: Long = 0L,
    val unlimitedPlanName: String = "",
    val totalUpBytes: Long = 0L,
    val totalDownBytes: Long = 0L,
    val createdAtMillis: Long = 0L,
) {
    fun hasUnlimited(nowMillis: Long): Boolean =
        enabled && unlimitedUntilMillis > nowMillis

    fun hasData(nowMillis: Long): Boolean =
        enabled &&
            dataBalanceBytes > 0L &&
            (dataValidUntilMillis <= 0L || nowMillis < dataValidUntilMillis)

    fun hasInternet(nowMillis: Long): Boolean =
        hasUnlimited(nowMillis) || hasData(nowMillis)

    fun currentDownloadBps(nowMillis: Long): Long = when {
        hasUnlimited(nowMillis) -> unlimitedDownloadBps
        hasData(nowMillis) -> dataDownloadBps
        else -> 0L
    }

    fun currentUploadBps(nowMillis: Long): Long = when {
        hasUnlimited(nowMillis) -> unlimitedUploadBps
        hasData(nowMillis) -> dataUploadBps
        else -> 0L
    }
}

data class Voucher(
    val code: String,
    val offerId: String,
    val createdAtMillis: Long,
    val enabled: Boolean = true,
    val redeemedByAccount: String = "",
    val redeemedAtMillis: Long = 0L,
)

data class DeviceBinding(
    val deviceKey: String,
    val accountNumber: String,
    val ip: String = "",
    val mac: String = "",
    val lastSeenMillis: Long = 0L,
)

data class CybercafeState(
    val schemaVersion: Int = 1,
    val offers: Map<String, Offer> = defaultOffers(),
    val accounts: Map<String, PrepaidAccount> = emptyMap(),
    val vouchers: Map<String, Voucher> = emptyMap(),
    val devices: Map<String, DeviceBinding> = emptyMap(),
)

data class RuleOutcome(
    val state: CybercafeState,
    val success: Boolean,
    val message: String,
)

fun defaultOffers(): Map<String, Offer> = listOf(
    Offer(
        id = "eco-12",
        name = "Eco 12 Go",
        kind = VoucherKind.DATA,
        downloadBps = 2_000_000L,
        uploadBps = 1_000_000L,
        quotaBytes = 12L * GB,
        durationDays = 30,
        priceXpf = 1_000,
    ),
    Offer(
        id = "eco-20",
        name = "Eco+ 20 Go",
        kind = VoucherKind.DATA,
        downloadBps = 4_000_000L,
        uploadBps = 2_000_000L,
        quotaBytes = 20L * GB,
        durationDays = 30,
        priceXpf = 1_500,
    ),
    Offer(
        id = "data-100",
        name = "Data 100 Go",
        kind = VoucherKind.DATA,
        downloadBps = 10_000_000L,
        uploadBps = 5_000_000L,
        quotaBytes = 100L * GB,
        durationDays = 30,
        priceXpf = 5_000,
    ),
    Offer(
        id = "unlimited-eco",
        name = "Illimité Eco",
        kind = VoucherKind.UNLIMITED,
        downloadBps = 1_000_000L,
        uploadBps = 1_000_000L,
        quotaBytes = 0L,
        durationDays = 30,
        priceXpf = 4_000,
    ),
    Offer(
        id = "unlimited-comfort",
        name = "Illimité Confort",
        kind = VoucherKind.UNLIMITED,
        downloadBps = 4_000_000L,
        uploadBps = 2_000_000L,
        quotaBytes = 0L,
        durationDays = 30,
        priceXpf = 6_000,
    ),
).associateBy(Offer::id)

object CybercafeRules {

    fun normalizeAccountNumber(raw: String): String =
        raw.filter(Char::isDigit)

    fun redeemVoucher(
        state: CybercafeState,
        accountNumber: String,
        rawCode: String,
        nowMillis: Long,
    ): RuleOutcome {
        val number = normalizeAccountNumber(accountNumber)
        val code = rawCode.trim().uppercase()
        val account = state.accounts[number]
            ?: return RuleOutcome(state, false, "Compte introuvable.")
        if (!account.enabled) {
            return RuleOutcome(state, false, "Compte suspendu.")
        }

        val voucher = state.vouchers[code]
            ?: return RuleOutcome(state, false, "Voucher invalide.")
        if (!voucher.enabled) {
            return RuleOutcome(state, false, "Voucher désactivé.")
        }
        if (voucher.redeemedByAccount.isNotBlank()) {
            return RuleOutcome(state, false, "Voucher déjà utilisé.")
        }

        val offer = state.offers[voucher.offerId]
            ?: return RuleOutcome(state, false, "Offre du voucher introuvable.")

        val durationMillis = offer.durationDays.coerceAtLeast(0) * DAY_MILLIS
        val updatedAccount = when (offer.kind) {
            VoucherKind.DATA -> account.copy(
                dataBalanceBytes = account.dataBalanceBytes + offer.quotaBytes.coerceAtLeast(0L),
                dataValidUntilMillis = if (durationMillis > 0L) {
                    nowMillis + durationMillis
                } else {
                    0L
                },
                dataDownloadBps = offer.downloadBps,
                dataUploadBps = offer.uploadBps,
            )

            VoucherKind.UNLIMITED -> {
                val start = max(nowMillis, account.unlimitedUntilMillis)
                account.copy(
                    unlimitedUntilMillis = start + durationMillis,
                    unlimitedDownloadBps = offer.downloadBps,
                    unlimitedUploadBps = offer.uploadBps,
                    unlimitedPlanName = offer.name,
                )
            }
        }

        val updatedVoucher = voucher.copy(
            redeemedByAccount = number,
            redeemedAtMillis = nowMillis,
        )

        return RuleOutcome(
            state = state.copy(
                accounts = state.accounts + (number to updatedAccount),
                vouchers = state.vouchers + (code to updatedVoucher),
            ),
            success = true,
            message = "Recharge appliquée.",
        )
    }

    fun bindDevice(
        state: CybercafeState,
        accountNumber: String,
        deviceKey: String,
        ip: String,
        mac: String,
        nowMillis: Long,
    ): RuleOutcome {
        val number = normalizeAccountNumber(accountNumber)
        val account = state.accounts[number]
            ?: return RuleOutcome(state, false, "Compte introuvable.")
        if (!account.enabled) {
            return RuleOutcome(state, false, "Compte suspendu.")
        }

        val normalizedKey = deviceKey.trim().lowercase()
        if (normalizedKey.isBlank()) {
            return RuleOutcome(state, false, "Identifiant appareil manquant.")
        }

        val otherDevice = state.devices.values.firstOrNull {
            it.accountNumber == number && it.deviceKey != normalizedKey
        }
        if (otherDevice != null) {
            return RuleOutcome(
                state,
                false,
                "Ce compte est déjà associé à un autre appareil.",
            )
        }

        val binding = DeviceBinding(
            deviceKey = normalizedKey,
            accountNumber = number,
            ip = ip.trim(),
            mac = mac.trim().lowercase(),
            lastSeenMillis = nowMillis,
        )

        return RuleOutcome(
            state = state.copy(devices = state.devices + (normalizedKey to binding)),
            success = true,
            message = "Appareil associé.",
        )
    }

    fun unbindDevice(state: CybercafeState, deviceKey: String): CybercafeState {
        val key = deviceKey.trim().lowercase()
        return state.copy(devices = state.devices - key)
    }

    fun recordTraffic(
        state: CybercafeState,
        deviceKey: String,
        uploadBytes: Long,
        downloadBytes: Long,
        nowMillis: Long,
    ): CybercafeState {
        val key = deviceKey.trim().lowercase()
        val binding = state.devices[key] ?: return state
        val account = state.accounts[binding.accountNumber] ?: return state

        val up = uploadBytes.coerceAtLeast(0L)
        val down = downloadBytes.coerceAtLeast(0L)
        val sessionBytes = up + down

        val consumeData = !account.hasUnlimited(nowMillis) && account.hasData(nowMillis)
        val updatedAccount = account.copy(
            totalUpBytes = account.totalUpBytes + up,
            totalDownBytes = account.totalDownBytes + down,
            dataBalanceBytes = if (consumeData) {
                (account.dataBalanceBytes - sessionBytes).coerceAtLeast(0L)
            } else {
                account.dataBalanceBytes
            },
        )
        val updatedBinding = binding.copy(lastSeenMillis = nowMillis)

        return state.copy(
            accounts = state.accounts + (account.number to updatedAccount),
            devices = state.devices + (key to updatedBinding),
        )
    }
}

object CybercafeSecurity {
    private val random = SecureRandom()

    fun newSalt(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun hashPin(salt: String, pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest((salt + ":" + pin).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun verifyPin(account: PrepaidAccount, pin: String): Boolean =
        hashPin(account.pinSalt, pin) == account.pinHash
}
