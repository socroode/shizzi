package dev.shizzi

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

internal const val VOUCHER_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

internal fun generateVoucherCode(random: SecureRandom): String =
    buildString(10) {
        repeat(10) {
            append(VOUCHER_ALPHABET[random.nextInt(VOUCHER_ALPHABET.length)])
        }
    }

class CybercafeStore(context: Context) {

    private val preferences =
        context.getSharedPreferences("shizzi_cybercafe_v1", Context.MODE_PRIVATE)

    private val mutableState = MutableStateFlow(
        decodeCybercafeState(preferences.getString(KEY_STATE, null)),
    )

    val state: StateFlow<CybercafeState> = mutableState.asStateFlow()

    private val mutablePolicyRevision = MutableStateFlow(0L)

    /**
     * Bumped by every change that affects what the datapath must enforce
     * (accounts, PINs, recharges, suspensions, offers...). Consumption alone
     * does NOT bump it: the datapath already counts consumption live, so the
     * policy is not re-pushed on every traffic tick.
     */
    val policyRevision: StateFlow<Long> = mutablePolicyRevision.asStateFlow()

    private var usageDirty = false
    private var lastUsageFlushMillis = 0L

    @Synchronized
    fun createAccount(numberRaw: String, pin: String, name: String, nowMillis: Long): RuleOutcome {
        val number = CybercafeRules.normalizeAccountNumber(numberRaw)
        if (number.isBlank()) return RuleOutcome(state.value, false, "Numéro de compte invalide.")
        if (pin.length < 4) return RuleOutcome(state.value, false, "Le code doit contenir au moins 4 caractères.")
        if (state.value.accounts.containsKey(number)) {
            return RuleOutcome(state.value, false, "Ce compte existe déjà.")
        }

        val salt = CybercafeSecurity.newSalt()
        val account = PrepaidAccount(
            number = number,
            name = name.trim(),
            pinSalt = salt,
            pinHash = CybercafeSecurity.hashPin(salt, pin),
            createdAtMillis = nowMillis,
        )
        return commit(
            RuleOutcome(
                state = state.value.copy(accounts = state.value.accounts + (number to account)),
                success = true,
                message = "Compte créé.",
            ),
        )
    }

    @Synchronized
    fun resetPin(numberRaw: String, newPin: String): RuleOutcome {
        val number = CybercafeRules.normalizeAccountNumber(numberRaw)
        val account = state.value.accounts[number]
            ?: return RuleOutcome(state.value, false, "Compte introuvable.")
        if (newPin.length < 4) {
            return RuleOutcome(state.value, false, "Le code doit contenir au moins 4 caractères.")
        }

        val salt = CybercafeSecurity.newSalt()
        val updated = account.copy(
            pinSalt = salt,
            pinHash = CybercafeSecurity.hashPin(salt, newPin),
        )
        return commit(
            RuleOutcome(
                state = state.value.copy(accounts = state.value.accounts + (number to updated)),
                success = true,
                message = "Code du compte modifié.",
            ),
        )
    }

    @Synchronized
    fun setAccountEnabled(numberRaw: String, enabled: Boolean): RuleOutcome {
        val number = CybercafeRules.normalizeAccountNumber(numberRaw)
        val account = state.value.accounts[number]
            ?: return RuleOutcome(state.value, false, "Compte introuvable.")
        return commit(
            RuleOutcome(
                state = state.value.copy(
                    accounts = state.value.accounts + (number to account.copy(enabled = enabled)),
                ),
                success = true,
                message = if (enabled) "Compte activé." else "Compte suspendu.",
            ),
        )
    }

    @Synchronized
    fun deleteAccount(numberRaw: String): RuleOutcome {
        val number = CybercafeRules.normalizeAccountNumber(numberRaw)
        if (!state.value.accounts.containsKey(number)) {
            return RuleOutcome(state.value, false, "Compte introuvable.")
        }
        val remainingDevices = state.value.devices.filterValues { it.accountNumber != number }
        return commit(
            RuleOutcome(
                state = state.value.copy(
                    accounts = state.value.accounts - number,
                    devices = remainingDevices,
                ),
                success = true,
                message = "Compte supprimé.",
            ),
        )
    }

    @Synchronized
    fun generateVouchers(offerId: String, count: Int, nowMillis: Long): List<Voucher> {
        val current = state.value
        val offer = current.offers[offerId] ?: return emptyList()

        val created = buildList<Voucher> {
            repeat(count.coerceIn(1, 100)) {
                var code: String
                do {
                    code = newVoucherCode()
                } while (current.vouchers.containsKey(code) || any { it.code == code })
                add(
                    Voucher(
                        code = code,
                        offerId = offerId,
                        createdAtMillis = nowMillis,
                        snapshotVersion = 1,
                        snapshotName = offer.name,
                        snapshotKind = offer.kind,
                        snapshotDownloadBps = offer.downloadBps,
                        snapshotUploadBps = offer.uploadBps,
                        snapshotQuotaBytes = offer.quotaBytes,
                        snapshotDurationDays = offer.durationDays,
                        snapshotPriceXpf = offer.priceXpf,
                    ),
                )
            }
        }

        if (created.isNotEmpty()) {
            val merged = current.vouchers + created.associateBy(Voucher::code)
            persist(current.copy(vouchers = merged))
        }
        return created
    }

    @Synchronized
    fun setVoucherEnabled(codeRaw: String, enabled: Boolean): RuleOutcome {
        val code = codeRaw.trim().uppercase()
        val voucher = state.value.vouchers[code]
            ?: return RuleOutcome(state.value, false, "Voucher introuvable.")
        return commit(
            RuleOutcome(
                state = state.value.copy(
                    vouchers = state.value.vouchers + (code to voucher.copy(enabled = enabled)),
                ),
                success = true,
                message = if (enabled) "Voucher activé." else "Voucher désactivé.",
            ),
        )
    }

    @Synchronized
    fun redeemVoucherForAccount(
        numberRaw: String,
        codeRaw: String,
        nowMillis: Long,
    ): RuleOutcome {
        val number = CybercafeRules.normalizeAccountNumber(numberRaw)
        return commit(CybercafeRules.redeemVoucher(state.value, number, codeRaw, nowMillis))
    }

    @Synchronized
    fun redeemVoucher(
        numberRaw: String,
        pin: String,
        codeRaw: String,
        nowMillis: Long,
    ): RuleOutcome {
        val number = CybercafeRules.normalizeAccountNumber(numberRaw)
        val account = state.value.accounts[number]
            ?: return RuleOutcome(state.value, false, "Compte introuvable.")
        if (!CybercafeSecurity.verifyPin(account, pin)) {
            return RuleOutcome(state.value, false, "Code du compte incorrect.")
        }
        return commit(CybercafeRules.redeemVoucher(state.value, number, codeRaw, nowMillis))
    }

    @Synchronized
    fun authenticate(numberRaw: String, pin: String): PrepaidAccount? {
        val number = CybercafeRules.normalizeAccountNumber(numberRaw)
        val account = state.value.accounts[number] ?: return null
        if (!account.enabled) return null
        return account.takeIf { CybercafeSecurity.verifyPin(it, pin) }
    }

    @Synchronized
    fun bindDevice(
        numberRaw: String,
        pin: String,
        deviceKey: String,
        ip: String,
        mac: String,
        nowMillis: Long,
    ): RuleOutcome {
        val number = CybercafeRules.normalizeAccountNumber(numberRaw)
        val account = state.value.accounts[number]
            ?: return RuleOutcome(state.value, false, "Compte introuvable.")
        if (!CybercafeSecurity.verifyPin(account, pin)) {
            return RuleOutcome(state.value, false, "Code du compte incorrect.")
        }
        return commit(
            CybercafeRules.bindDevice(
                state.value,
                number,
                deviceKey,
                ip,
                mac,
                nowMillis,
            ),
        )
    }

    @Synchronized
    fun unbindDevice(deviceKey: String) {
        persist(CybercafeRules.unbindDevice(state.value, deviceKey))
    }

    @Synchronized
    fun recordTraffic(
        deviceKey: String,
        uploadBytes: Long,
        downloadBytes: Long,
        nowMillis: Long,
    ) {
        persist(
            CybercafeRules.recordTraffic(
                state.value,
                deviceKey,
                uploadBytes,
                downloadBytes,
                nowMillis,
            ),
        )
    }


    @Synchronized
    fun renameAccount(numberRaw: String, name: String): RuleOutcome {
        val number = CybercafeRules.normalizeAccountNumber(numberRaw)
        val account = state.value.accounts[number]
            ?: return RuleOutcome(state.value, false, "Compte introuvable.")
        return commit(
            RuleOutcome(
                state = state.value.copy(
                    accounts = state.value.accounts + (number to account.copy(name = name.trim())),
                ),
                success = true,
                message = "Compte modifié.",
            ),
        )
    }

    @Synchronized
    fun upsertOffer(offer: Offer): RuleOutcome {
        val id = offer.id.trim().lowercase()
        if (id.isBlank() || offer.name.isBlank()) {
            return RuleOutcome(state.value, false, "Nom d'offre invalide.")
        }
        if (offer.downloadBps < 0L || offer.uploadBps < 0L || offer.quotaBytes < 0L) {
            return RuleOutcome(state.value, false, "Valeurs d'offre invalides.")
        }
        if (offer.durationDays <= 0 || offer.priceXpf < 0) {
            return RuleOutcome(state.value, false, "Durée ou prix invalide.")
        }
        val normalized = offer.copy(
            id = id,
            name = offer.name.trim(),
            quotaBytes = if (offer.kind == VoucherKind.UNLIMITED) 0L else offer.quotaBytes,
        )
        return commit(
            RuleOutcome(
                state = state.value.copy(offers = state.value.offers + (id to normalized)),
                success = true,
                message = "Offre enregistrée.",
            ),
        )
    }

    @Synchronized
    fun deleteOffer(offerIdRaw: String): RuleOutcome {
        val offerId = offerIdRaw.trim().lowercase()
        if (!state.value.offers.containsKey(offerId)) {
            return RuleOutcome(state.value, false, "Offre introuvable.")
        }
        return commit(
            RuleOutcome(
                state = state.value.copy(offers = state.value.offers - offerId),
                success = true,
                message = "Offre supprimée.",
            ),
        )
    }

    @Synchronized
    fun setRemoteAdmin(
        enabled: Boolean,
        usernameRaw: String,
        passwordRaw: String,
    ): RuleOutcome {
        val current = state.value.remoteAdmin
        val username = usernameRaw.trim().take(64)
        if (username.length < 3) {
            return RuleOutcome(state.value, false, "Identifiant admin trop court.")
        }
        if (enabled && current.passwordHash.isBlank() && passwordRaw.isBlank()) {
            return RuleOutcome(state.value, false, "Définissez d'abord un mot de passe admin.")
        }
        if (passwordRaw.isNotBlank() && passwordRaw.length < 8) {
            return RuleOutcome(state.value, false, "Le mot de passe admin doit contenir au moins 8 caractères.")
        }

        val updated = if (passwordRaw.isBlank()) {
            current.copy(
                enabled = enabled,
                username = username,
                downloadBps = 1_000_000L,
                uploadBps = 1_000_000L,
            )
        } else {
            val salt = CybercafeSecurity.newSalt()
            current.copy(
                enabled = enabled,
                username = username,
                passwordSalt = salt,
                passwordHash = CybercafeSecurity.hashSecret(salt, passwordRaw),
                downloadBps = 1_000_000L,
                uploadBps = 1_000_000L,
            )
        }

        return commit(
            RuleOutcome(
                state = state.value.copy(remoteAdmin = updated),
                success = true,
                message = if (enabled) "Administration distante activée." else "Administration distante désactivée.",
            ),
        )
    }

    @Synchronized
    fun setPortalCustomization(
        titleRaw: String,
        messageRaw: String,
        htmlRaw: String,
    ): RuleOutcome {
        val defaults = PortalCustomization()
        val portal = PortalCustomization(
            title = titleRaw.trim().ifBlank { defaults.title }.take(80),
            message = messageRaw.trim().ifBlank { defaults.message }.take(240),
            html = htmlRaw.take(100_000),
        )
        return commit(
            RuleOutcome(
                state = state.value.copy(portal = portal),
                success = true,
                message = "Portail enregistré.",
            ),
        )
    }


    /**
     * Applies consumption measured by the datapath to the shared account
     * balance. [dataBytes] is what the datapath counted in Data mode; it is
     * deducted as-is (the datapath decides Data vs Unlimited, not Android).
     *
     * Updates memory at once and disk at most every [USAGE_FLUSH_MILLIS]:
     * writing the whole state to SharedPreferences every second is wasteful.
     */
    @Synchronized
    fun recordAccountUsage(delta: UsageDelta) {
        val number = CybercafeRules.normalizeAccountNumber(delta.accountNumber)
        val account = state.value.accounts[number] ?: return
        val updated = account.copy(
            totalUpBytes = account.totalUpBytes + delta.upBytes.coerceAtLeast(0L),
            totalDownBytes = account.totalDownBytes + delta.downBytes.coerceAtLeast(0L),
            dataBalanceBytes = (account.dataBalanceBytes - delta.dataBytes.coerceAtLeast(0L))
                .coerceAtLeast(0L),
        )
        mutableState.value = state.value.copy(
            accounts = state.value.accounts + (number to updated),
        )
        usageDirty = true
    }

    @Synchronized
    fun flushUsage(nowMillis: Long, force: Boolean = false) {
        if (!usageDirty) return
        if (!force && nowMillis - lastUsageFlushMillis < USAGE_FLUSH_MILLIS) return
        writeToDisk(state.value)
        lastUsageFlushMillis = nowMillis
    }

    private fun commit(outcome: RuleOutcome): RuleOutcome {
        if (outcome.success) persist(outcome.state)
        return outcome
    }

    private fun persist(value: CybercafeState) {
        mutableState.value = value
        mutablePolicyRevision.value = mutablePolicyRevision.value + 1
        writeToDisk(value)
    }

    private fun writeToDisk(value: CybercafeState) {
        preferences.edit().putString(KEY_STATE, encodeCybercafeState(value)).apply()
        usageDirty = false
    }

    private fun newVoucherCode(): String = generateVoucherCode(random)

    private companion object {
        const val KEY_STATE = "state"
        const val USAGE_FLUSH_MILLIS = 15_000L
        val random = SecureRandom()
    }
}

internal fun encodeCybercafeState(state: CybercafeState): String =
    JSONObject().apply {
        put("schemaVersion", state.schemaVersion)
        put("portal", JSONObject().apply {
            put("title", state.portal.title)
            put("message", state.portal.message)
            put("html", state.portal.html)
        })
        put("remoteAdmin", JSONObject().apply {
            put("enabled", state.remoteAdmin.enabled)
            put("username", state.remoteAdmin.username)
            put("passwordSalt", state.remoteAdmin.passwordSalt)
            put("passwordHash", state.remoteAdmin.passwordHash)
            put("downloadBps", state.remoteAdmin.downloadBps)
            put("uploadBps", state.remoteAdmin.uploadBps)
        })
        put("offers", JSONArray().apply {
            state.offers.values.sortedBy(Offer::id).forEach { offer ->
                put(JSONObject().apply {
                    put("id", offer.id)
                    put("name", offer.name)
                    put("kind", offer.kind.name)
                    put("downloadBps", offer.downloadBps)
                    put("uploadBps", offer.uploadBps)
                    put("quotaBytes", offer.quotaBytes)
                    put("durationDays", offer.durationDays)
                    put("priceXpf", offer.priceXpf)
                })
            }
        })
        put("accounts", JSONArray().apply {
            state.accounts.values.sortedBy(PrepaidAccount::number).forEach { account ->
                put(JSONObject().apply {
                    put("number", account.number)
                    put("name", account.name)
                    put("pinSalt", account.pinSalt)
                    put("pinHash", account.pinHash)
                    put("enabled", account.enabled)
                    put("dataBalanceBytes", account.dataBalanceBytes)
                    put("dataValidUntilMillis", account.dataValidUntilMillis)
                    put("dataDownloadBps", account.dataDownloadBps)
                    put("dataUploadBps", account.dataUploadBps)
                    put("unlimitedUntilMillis", account.unlimitedUntilMillis)
                    put("unlimitedDownloadBps", account.unlimitedDownloadBps)
                    put("unlimitedUploadBps", account.unlimitedUploadBps)
                    put("unlimitedPlanName", account.unlimitedPlanName)
                    put("totalUpBytes", account.totalUpBytes)
                    put("totalDownBytes", account.totalDownBytes)
                    put("createdAtMillis", account.createdAtMillis)
                })
            }
        })
        put("vouchers", JSONArray().apply {
            state.vouchers.values.sortedBy(Voucher::code).forEach { voucher ->
                put(JSONObject().apply {
                    put("code", voucher.code)
                    put("offerId", voucher.offerId)
                    put("createdAtMillis", voucher.createdAtMillis)
                    put("enabled", voucher.enabled)
                    put("redeemedByAccount", voucher.redeemedByAccount)
                    put("redeemedAtMillis", voucher.redeemedAtMillis)
                    put("snapshotVersion", voucher.snapshotVersion)
                    put("snapshotName", voucher.snapshotName)
                    put("snapshotKind", voucher.snapshotKind.name)
                    put("snapshotDownloadBps", voucher.snapshotDownloadBps)
                    put("snapshotUploadBps", voucher.snapshotUploadBps)
                    put("snapshotQuotaBytes", voucher.snapshotQuotaBytes)
                    put("snapshotDurationDays", voucher.snapshotDurationDays)
                    put("snapshotPriceXpf", voucher.snapshotPriceXpf)
                })
            }
        })
        put("devices", JSONArray().apply {
            state.devices.values.sortedBy(DeviceBinding::deviceKey).forEach { device ->
                put(JSONObject().apply {
                    put("deviceKey", device.deviceKey)
                    put("accountNumber", device.accountNumber)
                    put("ip", device.ip)
                    put("mac", device.mac)
                    put("lastSeenMillis", device.lastSeenMillis)
                })
            }
        })
    }.toString()

internal fun decodeCybercafeState(raw: String?): CybercafeState {
    val root = runCatching { JSONObject(raw.orEmpty()) }.getOrNull()
        ?: return CybercafeState()

    val offers = linkedMapOf<String, Offer>()
    root.optJSONArray("offers")?.forEachObject { item ->
        val id = item.optString("id")
        if (id.isBlank()) return@forEachObject
        val kind = runCatching { VoucherKind.valueOf(item.optString("kind")) }
            .getOrDefault(VoucherKind.DATA)
        offers[id] = Offer(
            id = id,
            name = item.optString("name"),
            kind = kind,
            downloadBps = item.optLong("downloadBps"),
            uploadBps = item.optLong("uploadBps"),
            quotaBytes = item.optLong("quotaBytes"),
            durationDays = item.optInt("durationDays"),
            priceXpf = item.optInt("priceXpf"),
        )
    }

    val accounts = linkedMapOf<String, PrepaidAccount>()
    root.optJSONArray("accounts")?.forEachObject { item ->
        val number = CybercafeRules.normalizeAccountNumber(item.optString("number"))
        if (number.isBlank()) return@forEachObject
        accounts[number] = PrepaidAccount(
            number = number,
            name = item.optString("name"),
            pinSalt = item.optString("pinSalt"),
            pinHash = item.optString("pinHash"),
            enabled = item.optBoolean("enabled", true),
            dataBalanceBytes = item.optLong("dataBalanceBytes"),
            dataValidUntilMillis = item.optLong("dataValidUntilMillis"),
            dataDownloadBps = item.optLong("dataDownloadBps"),
            dataUploadBps = item.optLong("dataUploadBps"),
            unlimitedUntilMillis = item.optLong("unlimitedUntilMillis"),
            unlimitedDownloadBps = item.optLong("unlimitedDownloadBps"),
            unlimitedUploadBps = item.optLong("unlimitedUploadBps"),
            unlimitedPlanName = item.optString("unlimitedPlanName"),
            totalUpBytes = item.optLong("totalUpBytes"),
            totalDownBytes = item.optLong("totalDownBytes"),
            createdAtMillis = item.optLong("createdAtMillis"),
        )
    }

    val vouchers = linkedMapOf<String, Voucher>()
    root.optJSONArray("vouchers")?.forEachObject { item ->
        val code = item.optString("code").trim().uppercase()
        if (code.isBlank()) return@forEachObject
        vouchers[code] = Voucher(
            code = code,
            offerId = item.optString("offerId"),
            createdAtMillis = item.optLong("createdAtMillis"),
            enabled = item.optBoolean("enabled", true),
            redeemedByAccount = item.optString("redeemedByAccount"),
            redeemedAtMillis = item.optLong("redeemedAtMillis"),
            snapshotVersion = item.optInt("snapshotVersion", 0),
            snapshotName = item.optString("snapshotName"),
            snapshotKind = runCatching {
                VoucherKind.valueOf(item.optString("snapshotKind", VoucherKind.DATA.name))
            }.getOrDefault(VoucherKind.DATA),
            snapshotDownloadBps = item.optLong("snapshotDownloadBps"),
            snapshotUploadBps = item.optLong("snapshotUploadBps"),
            snapshotQuotaBytes = item.optLong("snapshotQuotaBytes"),
            snapshotDurationDays = item.optInt("snapshotDurationDays"),
            snapshotPriceXpf = item.optInt("snapshotPriceXpf"),
        )
    }

    val portalObject = root.optJSONObject("portal")
    val portalDefaults = PortalCustomization()
    val portal = PortalCustomization(
        title = portalObject?.optString("title").orEmpty()
            .ifBlank { root.optString("portalTitle") }
            .ifBlank { portalDefaults.title }
            .take(80),
        message = portalObject?.optString("message").orEmpty()
            .ifBlank { root.optString("portalMessage") }
            .ifBlank { portalDefaults.message }
            .take(240),
        html = if (portalObject != null) {
            portalObject.optString("html").take(100_000)
        } else {
            root.optString("portalHtml").take(100_000)
        },
    )

    val remoteAdminObject = root.optJSONObject("remoteAdmin")
    val remoteAdmin = if (remoteAdminObject == null) {
        RemoteAdminConfig()
    } else {
        RemoteAdminConfig(
            enabled = remoteAdminObject.optBoolean("enabled", false),
            username = remoteAdminObject.optString("username", "admin").trim().ifBlank { "admin" }.take(64),
            passwordSalt = remoteAdminObject.optString("passwordSalt"),
            passwordHash = remoteAdminObject.optString("passwordHash"),
            downloadBps = remoteAdminObject.optLong("downloadBps", 1_000_000L).coerceAtLeast(1_000_000L),
            uploadBps = remoteAdminObject.optLong("uploadBps", 1_000_000L).coerceAtLeast(1_000_000L),
        )
    }

    val devices = linkedMapOf<String, DeviceBinding>()
    root.optJSONArray("devices")?.forEachObject { item ->
        val key = item.optString("deviceKey").trim().lowercase()
        if (key.isBlank()) return@forEachObject
        devices[key] = DeviceBinding(
            deviceKey = key,
            accountNumber = CybercafeRules.normalizeAccountNumber(item.optString("accountNumber")),
            ip = item.optString("ip"),
            mac = item.optString("mac").lowercase(),
            lastSeenMillis = item.optLong("lastSeenMillis"),
        )
    }

    return CybercafeState(
        schemaVersion = 4,
        offers = if (offers.isEmpty()) defaultOffers() else offers,
        accounts = accounts,
        vouchers = vouchers,
        devices = devices,
        portal = portal,
        remoteAdmin = remoteAdmin,
    )
}

private inline fun JSONArray.forEachObject(block: (JSONObject) -> Unit) {
    for (index in 0 until length()) {
        optJSONObject(index)?.let(block)
    }
}
