package dev.shizzi

import android.content.ContextWrapper
import dev.shizzi.history.RechargeHistory
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Exercise the actual Android command dispatcher and synchronous account storage. */
class RemoteAdminCouponTest {
    private val day = 86_400_000L
    private val now = 1_000_000L
    private val context = ContextWrapper(null)
    private val mediaOffer = MediaOffer("media-day", "Media 24 h", 24L * 60L, 300)
    private val extension = Offer("validite-15", "Validité 15 jours", VoucherKind.DATA_EXTENSION,
        downloadBps = 0L, uploadBps = 0L, quotaBytes = 0L, durationDays = 15, priceXpf = 300)

    private fun account(enabled: Boolean = true) = PrepaidAccount(
        number = "1001", name = "Test", pinSalt = "salt", pinHash = "hash",
        enabled = enabled, dataBalanceBytes = 5_000_000_000L,
        dataValidUntilMillis = now + 90L * day,
        dataDownloadBps = 2_000_000L, dataUploadBps = 1_000_000L,
    )

    private fun fixture(state: CybercafeState = CybercafeState(
        accounts = mapOf("1001" to account()),
        mediaOffers = mapOf(mediaOffer.id to mediaOffer),
    )): Pair<MemoryPreferences, CybercafeStore> {
        val prefs = MemoryPreferences()
        check(prefs.edit().putString("state", encodeCybercafeState(state)).commit())
        return prefs to CybercafeStore(prefs)
    }

    private fun command(
        store: CybercafeStore,
        action: String,
        params: JSONObject,
        at: Long = now,
    ): AdminCommandResult = processRemoteAdminCommand(
        LiveAdminCommand("coupon-test", "192.168.7.2", action, params.toString(), at),
        store, at, context,
    )

    private fun issue(
        store: CybercafeStore,
        offerId: String,
        media: Boolean = false,
        at: Long = now,
    ) = command(
        store,
        if (media) "account.coupon.media.issue" else "account.coupon.issue",
        JSONObject().put("number", "1001").put("offerId", offerId),
        at,
    )

    private fun redeem(store: CybercafeStore, code: String) = command(
        store, "account.coupon.redeem",
        JSONObject().put("number", "1001").put("code", code),
    )

    @Test
    fun directDataCouponsAccumulateAndSurviveReload() {
        val (prefs, store) = fixture()
        assertTrue(issue(store, "eco-12").success)
        assertTrue(issue(store, "eco-20", at = now + day).success)
        val state = store.state.value
        val account = state.accounts.getValue("1001")
        assertEquals(37_000_000_000L, account.dataBalanceBytes)
        assertEquals(now + 31L * day, account.dataValidUntilMillis)
        assertEquals(2, state.vouchers.size)
        assertTrue(state.vouchers.values.all { it.redeemedByAccount == "1001" })
        assertEquals(2L, store.policyRevision.value)
        assertEquals(state, CybercafeStore(prefs).state.value)
        val before = store.state.value
        assertFalse(redeem(store, state.vouchers.keys.first()).success)
        assertEquals(before, store.state.value)
    }

    @Test
    fun directUnlimitedAddsTimeKeepsDataAndFallsBackToData() {
        val (prefs, store) = fixture()
        assertTrue(issue(store, "unlimited-eco").success)
        assertTrue(issue(store, "unlimited-comfort", at = now + day).success)
        val account = store.state.value.accounts.getValue("1001")
        assertEquals(now + 60L * day, account.unlimitedUntilMillis)
        assertEquals(5_000_000_000L, account.dataBalanceBytes)
        assertEquals(4_000_000L, account.currentDownloadBps(now + 2L * day))
        assertEquals(2_000_000L, account.currentDownloadBps(account.unlimitedUntilMillis))
        assertTrue(account.hasData(account.unlimitedUntilMillis))
        assertEquals(store.state.value, CybercafeStore(prefs).state.value)
    }

    @Test
    fun directMediaAddsTimeAndPreservesAllInternetFields() {
        val original = account().copy(unlimitedUntilMillis = now + 10L * day,
            unlimitedDownloadBps = 4_000_000L, unlimitedUploadBps = 2_000_000L,
            unlimitedPlanName = "Illimité Confort", totalUpBytes = 123L, totalDownBytes = 456L)
        val (prefs, store) = fixture(CybercafeState(
            accounts = mapOf("1001" to original),
            mediaOffers = mapOf(mediaOffer.id to mediaOffer),
        ))
        assertTrue(issue(store, mediaOffer.id, media = true).success)
        assertTrue(issue(store, mediaOffer.id, media = true, at = now + day / 2).success)
        val state = store.state.value
        assertEquals(original.copy(mediaUntilMillis = now + 2L * day), state.accounts.getValue("1001"))
        assertTrue(state.vouchers.isEmpty())
        assertEquals(2, state.mediaVouchers.size)
        assertTrue(state.mediaVouchers.values.all { it.redeemedByAccount == "1001" })
        assertEquals(state, CybercafeStore(prefs).state.value)
        assertFalse(redeem(store, state.mediaVouchers.keys.first()).success)
    }

    @Test
    fun existingDataUnlimitedAndMediaCodesUseTheSameDispatcher() {
        val (_, store) = fixture(CybercafeState(
            accounts = mapOf("1001" to account()),
            vouchers = mapOf(
                "DATA" to Voucher("DATA", "eco-12", 0L),
                "UNLIMITED" to Voucher("UNLIMITED", "unlimited-eco", 0L),
            ),
            mediaOffers = mapOf(mediaOffer.id to mediaOffer),
            mediaVouchers = mapOf("MEDIA" to MediaVoucher(
                "MEDIA", mediaOffer.id, 0L, snapshotName = mediaOffer.name,
                snapshotDurationMinutes = mediaOffer.durationMinutes,
            )),
        ))
        for (code in listOf("data", "unlimited", "media")) {
            val result = redeem(store, " $code ")
            assertTrue(result.message, result.success)
            assertEquals("coupon-test", result.id)
        }
        val account = store.state.value.accounts.getValue("1001")
        assertEquals(17_000_000_000L, account.dataBalanceBytes)
        assertEquals(now + 30L * day, account.unlimitedUntilMillis)
        assertEquals(now + day, account.mediaUntilMillis)
        for (code in listOf("DATA", "UNLIMITED", "MEDIA")) {
            assertFalse(redeem(store, code).success)
        }
    }

    @Test
    fun invalidOffersAccountsAndCodesLeaveNoSpareCoupon() {
        val (prefs, store) = fixture()
        val before = store.state.value
        val stored = prefs.getString("state", null)
        assertFalse(issue(store, "missing").success)
        assertFalse(issue(store, "missing", media = true).success)
        assertFalse(redeem(store, "missing").success)
        for (action in listOf("account.coupon.issue", "account.coupon.media.issue", "account.coupon.redeem")) {
            assertFalse(command(store, action, JSONObject()
                .put("number", "9999").put("offerId", "eco-12").put("code", "DATA")).success)
        }
        assertEquals(before, store.state.value)
        assertEquals(stored, prefs.getString("state", null))
        assertEquals(0L, store.policyRevision.value)
    }

    @Test
    fun suspendedAccountCannotReceiveInternetOrMedia() {
        val (prefs, store) = fixture(CybercafeState(
            accounts = mapOf("1001" to account(enabled = false)),
            mediaOffers = mapOf(mediaOffer.id to mediaOffer),
            vouchers = mapOf("DATA" to Voucher("DATA", "eco-12", 0L)),
        ))
        val before = store.state.value
        assertFalse(issue(store, "eco-12").success)
        assertFalse(issue(store, "unlimited-eco").success)
        assertFalse(issue(store, mediaOffer.id, media = true).success)
        assertFalse(redeem(store, "DATA").success)
        assertEquals(before, store.state.value)
        assertEquals(before, CybercafeStore(prefs).state.value)
    }

    @Test
    fun dataExtensionWorksThroughAdminAndPortalAndKeepsThePaidDeadline() {
        val original = account().copy(unlimitedUntilMillis = now + 5L * day,
            unlimitedDownloadBps = 4_000_000L, unlimitedUploadBps = 2_000_000L,
            mediaUntilMillis = now + 3L * day)
        val (prefs, store) = fixture(CybercafeState(accounts = mapOf("1001" to original)))
        val created = command(store, "offer.upsert", JSONObject()
            .put("id", extension.id).put("name", extension.name).put("kind", extension.kind.name)
            .put("durationDays", extension.durationDays).put("priceXpf", extension.priceXpf)
            .put("downloadBps", 900_000L).put("uploadBps", 400_000L).put("quotaBytes", 8_000_000_000L))
        assertTrue(created.message, created.success)
        assertEquals(extension, store.state.value.offers.getValue(extension.id))
        assertTrue(issue(store, extension.id).success)
        val firstDeadline = now + 105L * day
        assertEquals(original.copy(dataValidUntilMillis = firstDeadline, dataExtensionUntilMillis = firstDeadline),
            store.state.value.accounts.getValue("1001"))

        assertTrue(command(store, "voucher.generate", JSONObject().put("offerId", extension.id).put("count", 1)).success)
        val code = store.state.value.vouchers.values.single { it.redeemedByAccount.isBlank() }.code
        assertTrue(store.upsertOffer(extension.copy(durationDays = 1, priceXpf = 999)).success)
        assertTrue(store.redeemVoucherClaim("extension-claim", "1001", code, now + day).success)
        val deadline = now + 120L * day
        val expected = original.copy(dataValidUntilMillis = deadline, dataExtensionUntilMillis = deadline)
        val reloaded = CybercafeStore(prefs)
        val state = reloaded.state.value
        assertEquals(expected, state.accounts.getValue("1001"))
        assertTrue(reloaded.redeemVoucherClaim("extension-claim", "1001", code, now + 2L * day).success)
        assertFalse(reloaded.redeemVoucherClaim("duplicate-claim", "1001", code, now + 2L * day).success)
        assertEquals(state, reloaded.state.value)

        val adminState = JSONObject(state.toPortalConfigJson()).getJSONObject("adminState")
        assertTrue(adminState.getBoolean("dataExtensionCoupons"))
        val history = RechargeHistory.read("1001", adminState).entries
        assertEquals(2, history.size)
        for (entry in history) {
            assertEquals(RechargeHistory.Kind.DATA_EXTENSION, entry.kind)
            assertEquals(15L, entry.durationDays)
            assertEquals(300L, entry.priceXpf)
            assertNull(entry.quotaBytes)
            assertNull(entry.downloadBps)
            assertTrue(entry.format { "$it" }.contains("+15 j de validité Data"))
        }
        assertTrue(issue(reloaded, "eco-12", at = now + 2L * day).success)
        assertEquals(expected.copy(dataBalanceBytes = 17_000_000_000L), reloaded.state.value.accounts.getValue("1001"))
    }

    @Test
    fun extensionRestartsValidityForAnExpiredRecordedBalance() {
        val original = account().copy(dataValidUntilMillis = now - 1L)
        val (_, store) = fixture(CybercafeState(
            accounts = mapOf("1001" to original), offers = defaultOffers() + (extension.id to extension),
        ))
        assertFalse(original.hasData(now))
        val code = store.generateVouchers(extension.id, 1, now).single().code
        assertTrue(store.redeemVoucherClaim("expired-claim", "1001", code, now).success)
        val deadline = now + 15L * day
        val account = store.state.value.accounts.getValue("1001")
        assertEquals(original.copy(dataValidUntilMillis = deadline, dataExtensionUntilMillis = deadline), account)
        assertTrue(account.hasData(now))
    }

    @Test
    fun ineligibleBalancesLeaveTheExtensionCouponAvailable() {
        val ineligible = listOf(
            account().copy(dataBalanceBytes = 0L, unlimitedUntilMillis = now + day),
            account().copy(dataValidUntilMillis = 0L),
            account(enabled = false),
        )
        for (account in ineligible) {
            val (prefs, store) = fixture(CybercafeState(
                accounts = mapOf("1001" to account), offers = defaultOffers() + (extension.id to extension),
            ))
            val code = store.generateVouchers(extension.id, 1, now).single().code
            val before = store.state.value
            assertFalse(redeem(store, code).success)
            assertFalse(issue(store, extension.id).success)
            assertEquals(before, store.state.value)
            assertEquals(before, CybercafeStore(prefs).state.value)
        }
    }

    @Test
    fun storageFailureNeverReportsSuccessOrCreditsAnUnsavedCoupon() {
        val offers = listOf("eco-12" to false, "unlimited-eco" to false, mediaOffer.id to true, extension.id to false)
        for ((offer, media) in offers) {
            val (prefs, store) = fixture(CybercafeState(
                accounts = mapOf("1001" to account()),
                offers = defaultOffers() + (extension.id to extension),
                mediaOffers = mapOf(mediaOffer.id to mediaOffer),
            ))
            val before = store.state.value
            val stored = prefs.getString("state", null)
            prefs.failWrites = true
            val result = issue(store, offer, media)
            assertFalse(result.message, result.success)
            assertEquals(before, store.state.value)
            assertEquals(stored, prefs.getString("state", null))
            assertEquals(0L, store.policyRevision.value)
        }
    }
}
