package dev.shizzi

import dev.shizzi.history.RechargeHistory
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RechargeHistoryPersistenceTest {
    private val now = 1_800_000_000_000L
    private val media = MediaOffer("media-day", "Media 24 h", 1440L, 300)
    private fun account(number: String) = PrepaidAccount(number, "Client $number", "salt", "hash")
    private fun fixture(extra: (CybercafeState) -> CybercafeState = { it }): Pair<MemoryPreferences, CybercafeStore> {
        val prefs = MemoryPreferences()
        val state = extra(CybercafeState(
            accounts = listOf(account("1001"), account("1002")).associateBy { it.number },
            mediaOffers = mapOf(media.id to media),
        ))
        check(prefs.edit().putString("state", encodeCybercafeState(state)).commit())
        return prefs to CybercafeStore(prefs)
    }
    private fun history(state: CybercafeState, number: String = "1001") =
        RechargeHistory.read(number, JSONObject(encodeCybercafeState(state)))

    @Test fun allRechargeTypesSurviveReloadOfferChangesAndRemoteSerialization() {
        val (prefs, store) = fixture()
        assertTrue(store.issueCouponForAccount("1001", "eco-12", now).success)
        assertTrue(store.issueCouponForAccount("1001", "unlimited-eco", now + 1000).success)
        assertTrue(store.issueMediaCouponForAccount("1001", media.id, now + 2000).success)
        assertTrue(store.issueCouponForAccount("1001", "eco-20", now + 3000).success)
        assertTrue(store.issueCouponForAccount("1002", "eco-12", now + 4000).success)
        val before = history(store.state.value)
        assertEquals(4, before.entries.size)
        assertEquals(1, history(store.state.value, "1002").entries.size)
        assertEquals(setOf(RechargeHistory.Kind.DATA, RechargeHistory.Kind.UNLIMITED, RechargeHistory.Kind.MEDIA),
            before.entries.map { it.kind }.toSet())

        assertTrue(store.upsertOffer(store.state.value.offers.getValue("eco-12")
            .copy(name = "Nom modifié", priceXpf = 9_999)).success)
        for (id in listOf("eco-12", "eco-20", "unlimited-eco")) assertTrue(store.deleteOffer(id).success)
        assertTrue(store.deleteMediaOffer(media.id).success)
        assertTrue(store.setAccountEnabled("1001", false).success)
        val reloaded = CybercafeStore(prefs).state.value
        assertEquals(before, history(reloaded))
        assertEquals(32_000_000_000L, reloaded.accounts.getValue("1001").dataBalanceBytes)
        val remote = JSONObject(reloaded.toPortalConfigJson()).getJSONObject("adminState")
        assertEquals(before, RechargeHistory.read("1001", remote))
    }

    @Test fun redeemingLegacyCouponsCapturesTheActuallyAppliedOfferForFutureHistory() {
        val (prefs, store) = fixture { state -> state.copy(
            vouchers = mapOf("LEGACY" to Voucher("LEGACY", "eco-12", 1L)),
            mediaVouchers = mapOf("OLDMEDIA" to MediaVoucher("OLDMEDIA", media.id, 1L, snapshotVersion = 0)),
        ) }
        assertTrue(store.redeemVoucherForAccount("1001", "LEGACY", now).success)
        assertTrue(store.redeemVoucherForAccount("1001", "OLDMEDIA", now + 1000).success)
        assertEquals(1, store.state.value.vouchers.getValue("LEGACY").snapshotVersion)
        assertEquals(1, store.state.value.mediaVouchers.getValue("OLDMEDIA").snapshotVersion)
        assertTrue(store.deleteOffer("eco-12").success)
        assertTrue(store.deleteMediaOffer(media.id).success)
        val reloaded = CybercafeStore(prefs).state.value
        val entries = history(reloaded).entries
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.originalDetails })
        val data = entries.first { it.code == "LEGACY" }
        assertEquals("Eco 12 Go", data.name)
        assertEquals(12_000_000_000L, data.quotaBytes)
        assertEquals(1_000L, data.priceXpf)
        val mediaEntry = entries.first { it.code == "OLDMEDIA" }
        assertEquals(media.name, mediaEntry.name)
        assertEquals(1440L, mediaEntry.durationMinutes)
        assertEquals(300L, mediaEntry.priceXpf)
    }

    @Test fun failedOrRepeatedRechargesNeverCreatePhantomHistoryEntries() {
        val (prefs, store) = fixture()
        prefs.failWrites = true
        assertFalse(store.issueCouponForAccount("1001", "eco-12", now).success)
        assertTrue(history(store.state.value).entries.isEmpty())
        assertTrue(history(CybercafeStore(prefs).state.value).entries.isEmpty())
        prefs.failWrites = false
        assertTrue(store.issueCouponForAccount("1001", "eco-12", now).success)
        val before = history(store.state.value)
        val code = before.entries.single().code
        assertFalse(store.redeemVoucherForAccount("1001", code, now + 1000).success)
        assertFalse(store.issueCouponForAccount("1001", "missing", now + 1000).success)
        assertEquals(before, history(store.state.value))
        assertEquals(before, history(CybercafeStore(prefs).state.value))
    }
}
