package dev.shizzi

import org.junit.Assert.*
import org.junit.Test

class CandidateAccountingTest {
    private fun account() = PrepaidAccount("1001", "Test", "salt", CybercafeSecurity.hashPin("salt", "1234"), dataBalanceBytes = 1_000)

    private fun preferences(state: CybercafeState): MemoryPreferences = MemoryPreferences().apply {
        edit().putString("state", encodeCybercafeState(state)).commit()
    }

    @Test fun pinMigrationKeepsCredentialsAndAdmin() {
        val admin = RemoteAdminConfig(enabled = true, username = "owner", passwordSalt = "admin-salt", passwordHash = "existing-admin-verifier")
        val prefs = preferences(CybercafeState(accounts = mapOf("1001" to account()), remoteAdmin = admin))
        val store = CybercafeStore(prefs)
        val migrated = store.state.value.accounts.getValue("1001")
        assertEquals(CybercafeSecurity.PIN_KDF_ITERATIONS, migrated.pinKdfIterations)
        assertTrue(CybercafeSecurity.verifyPin(migrated, "1234"))
        assertFalse(CybercafeSecurity.verifyPin(migrated, "9999"))
        assertEquals(admin, store.state.value.remoteAdmin)
        assertEquals(migrated.pinHash, CybercafeStore(prefs).state.value.accounts.getValue("1001").pinHash)
    }

    @Test fun adminCanRedeemCouponOnceWithoutOverwritingBalance() {
        val offer = defaultOffers().getValue("eco-12")
        val voucher = Voucher(
            code = "CODE123",
            offerId = offer.id,
            createdAtMillis = 1L,
            snapshotVersion = 1,
            snapshotName = offer.name,
            snapshotKind = offer.kind,
            snapshotDownloadBps = offer.downloadBps,
            snapshotUploadBps = offer.uploadBps,
            snapshotQuotaBytes = offer.quotaBytes,
            snapshotDurationDays = offer.durationDays,
        )
        val store = CybercafeStore(preferences(CybercafeState(
            accounts = mapOf("1001" to account()),
            offers = mapOf(offer.id to offer),
            vouchers = mapOf(voucher.code to voucher),
        )))
        assertTrue(store.redeemVoucherForAccount("1001", " code123 ", 1_000L).success)
        val newBalance = 1_000L + offer.quotaBytes
        assertEquals(newBalance, store.state.value.accounts.getValue("1001").dataBalanceBytes)
        assertEquals("1001", store.state.value.vouchers.getValue("CODE123").redeemedByAccount)
        assertFalse(store.redeemVoucherForAccount("1001", "CODE123", 1_100L).success)
        assertEquals(newBalance, store.state.value.accounts.getValue("1001").dataBalanceBytes)
    }

    @Test fun adminCanIssueCouponAtomicallyAndPersistAcrossRestart() {
        val offer = defaultOffers().getValue("eco-12")
        val prefs = preferences(CybercafeState(
            accounts = mapOf("1001" to account()),
            offers = mapOf(offer.id to offer),
        ))
        val store = CybercafeStore(prefs)
        val result = store.issueCouponForAccount("1001", offer.id, 1_000L)
        assertTrue(result.message, result.success)
        val updated = store.state.value
        assertEquals(1_000L + offer.quotaBytes, updated.accounts.getValue("1001").dataBalanceBytes)
        assertEquals(1, updated.vouchers.size)
        assertEquals("1001", updated.vouchers.values.single().redeemedByAccount)
        assertEquals(updated.accounts.getValue("1001").dataBalanceBytes,
            CybercafeStore(prefs).state.value.accounts.getValue("1001").dataBalanceBytes)
    }

    @Test fun adminCanAssignExistingMediaVoucherWithoutChangingInternet() {
        val media = MediaOffer("media-1", "Media 7j", 7L * 24 * 60, 500)
        val voucher = MediaVoucher("MEDIACODE", media.id, 1L, snapshotVersion = 1,
            snapshotName = media.name, snapshotDurationMinutes = media.durationMinutes)
        val store = CybercafeStore(preferences(CybercafeState(
            accounts = mapOf("1001" to account().copy(mediaUntilMillis = 10_000L)),
            mediaOffers = mapOf(media.id to media),
            mediaVouchers = mapOf(voucher.code to voucher),
        )))
        assertTrue(store.redeemVoucherForAccount("1001", " mediacode ", 2_000L).success)
        val updated = store.state.value.accounts.getValue("1001")
        assertEquals(1_000L, updated.dataBalanceBytes)
        assertEquals(10_000L + media.durationMinutes * 60_000L, updated.mediaUntilMillis)
        assertEquals("1001", store.state.value.mediaVouchers.getValue("MEDIACODE").redeemedByAccount)
        assertFalse(store.redeemVoucherForAccount("1001", "MEDIACODE", 2_100L).success)
        assertEquals(updated, store.state.value.accounts.getValue("1001"))
    }

    @Test fun adminCanIssueMediaCouponAndStackRemainingDuration() {
        val media = MediaOffer("media-1", "Media 24h", 1_440L, 200)
        val prefs = preferences(CybercafeState(
            accounts = mapOf("1001" to account()),
            mediaOffers = mapOf(media.id to media),
        ))
        val store = CybercafeStore(prefs)
        assertTrue(store.issueMediaCouponForAccount("1001", media.id, 1_000L).success)
        val firstExpiry = store.state.value.accounts.getValue("1001").mediaUntilMillis
        assertEquals(1_000L + 1_440L * 60_000L, firstExpiry)
        assertTrue(store.issueMediaCouponForAccount("1001", media.id, 2_000L).success)
        assertEquals(firstExpiry + 1_440L * 60_000L,
            store.state.value.accounts.getValue("1001").mediaUntilMillis)
        assertEquals(1_000L, store.state.value.accounts.getValue("1001").dataBalanceBytes)
        assertEquals(2, store.state.value.mediaVouchers.size)
        assertTrue(store.state.value.mediaVouchers.values.all { it.redeemedByAccount == "1001" })
        assertEquals(store.state.value,
            CybercafeStore(prefs).state.value)
    }

    @Test fun adminCanIssueUnlimitedWithoutReplacingMediaOrData() {
        val unlimited = defaultOffers().getValue("unlimited-comfort")
        val store = CybercafeStore(preferences(CybercafeState(
            accounts = mapOf("1001" to account().copy(mediaUntilMillis = 9_000L)),
            offers = mapOf(unlimited.id to unlimited),
        )))
        assertTrue(store.issueCouponForAccount("1001", unlimited.id, 1_000L).success)
        val first = store.state.value.accounts.getValue("1001")
        assertEquals(1_000L, first.dataBalanceBytes)
        assertEquals(9_000L, first.mediaUntilMillis)
        assertEquals(1_000L + unlimited.durationDays * 86_400_000L, first.unlimitedUntilMillis)
        assertTrue(store.issueCouponForAccount("1001", unlimited.id, 2_000L).success)
        assertEquals(first.unlimitedUntilMillis + unlimited.durationDays * 86_400_000L,
            store.state.value.accounts.getValue("1001").unlimitedUntilMillis)
    }

    @Test fun adminCouponIssueRejectsInvalidAccountAndSuspendedAccount() {
        val offer = defaultOffers().getValue("eco-12")
        val store = CybercafeStore(preferences(CybercafeState(
            accounts = mapOf("1001" to account()),
            offers = mapOf(offer.id to offer),
        )))
        assertFalse(store.issueCouponForAccount("9999", offer.id, 1_000L).success)
        assertFalse(store.issueCouponForAccount("1001", "absent", 1_000L).success)
        assertTrue(store.setAccountEnabled("1001", false).success)
        assertFalse(store.issueCouponForAccount("1001", offer.id, 1_000L).success)
        assertTrue(store.state.value.vouchers.isEmpty())
    }

    @Test fun adminCouponWriteFailureDoesNotChangeAccountOrInventory() {
        val offer = defaultOffers().getValue("eco-12")
        val prefs = preferences(CybercafeState(
            accounts = mapOf("1001" to account()),
            offers = mapOf(offer.id to offer),
        ))
        val store = CybercafeStore(prefs)
        val before = store.state.value
        prefs.failWrites = true
        assertThrows(IllegalStateException::class.java) {
            store.issueCouponForAccount("1001", offer.id, 1_000L)
        }
        assertEquals(before, store.state.value)
    }

    @Test fun failedMediaCouponCommitDoesNotChangeAccountOrInventory() {
        val media = MediaOffer("media-1", "Media jour", 1_440L, 200)
        val prefs = preferences(CybercafeState(
            accounts = mapOf("1001" to account()),
            mediaOffers = mapOf(media.id to media),
        ))
        val store = CybercafeStore(prefs)
        val before = store.state.value
        prefs.failWrites = true
        assertThrows(IllegalStateException::class.java) {
            store.issueMediaCouponForAccount("1001", media.id, 1_000L)
        }
        assertEquals(before, store.state.value)
    }

    @Test fun failedCommitDoesNotReportRechargeSuccess() {
        val voucher = Voucher("CODE", "data-2gb", 1)
        val prefs = preferences(CybercafeState(accounts = mapOf("1001" to account()), vouchers = mapOf("CODE" to voucher)))
        val store = CybercafeStore(prefs)
        val old = store.state.value
        prefs.failWrites = true
        assertThrows(IllegalStateException::class.java) { store.redeemVoucherClaim("claim-1", "1001", "CODE", 100) }
        assertEquals(old, store.state.value)
    }

    @Test fun repeatedClaimAfterRestartKeepsOriginalReceiptAndBalance() {
        val offer = defaultOffers().values.first { it.kind == VoucherKind.DATA }
        val voucher = Voucher("CODE", offer.id, 1)
        val prefs = preferences(CybercafeState(accounts = mapOf("1001" to account()), vouchers = mapOf("CODE" to voucher)))
        val store = CybercafeStore(prefs)
        val result = store.redeemVoucherClaim("claim-1", "1001", "CODE", 100)
        assertTrue(result.success)
        val balance = store.state.value.accounts.getValue("1001").dataBalanceBytes
        val restored = CybercafeStore(prefs)
        val replay = restored.redeemVoucherClaim("claim-1", "1001", "CODE", 200)
        assertEquals(result.message, replay.message)
        assertTrue(replay.success)
        assertEquals(balance, restored.state.value.accounts.getValue("1001").dataBalanceBytes)
        assertFalse(restored.redeemVoucherClaim("claim-1", "1001", "OTHER", 200).success)
    }

    @Test fun ledgerAndBalanceRestartTogetherWithoutDoubleDebit() {
        val prefs = preferences(CybercafeState(accounts = mapOf("1001" to account())))
        val store = CybercafeStore(prefs)
        val ledger = store.newUsageLedger()
        val usage = listOf(LiveAccountUsage("1001", 30, 70, 100))
        store.absorbUsage(ledger, 42, usage)
        store.flushUsage(1000, force = true)
        val restored = CybercafeStore(prefs)
        val restoredLedger = restored.newUsageLedger()
        restored.absorbUsage(restoredLedger, 42, usage)
        assertEquals(900L, restored.state.value.accounts.getValue("1001").dataBalanceBytes)
        restored.absorbUsage(restoredLedger, 42, listOf(LiveAccountUsage("1001", 40, 80, 120)))
        assertEquals(880L, restored.state.value.accounts.getValue("1001").dataBalanceBytes)
    }

    @Test fun cameraEnrollmentNeedsPinAndIsRemovedByPinRotation() {
        val store = CybercafeStore(preferences(CybercafeState(accounts = mapOf("1001" to account()))))
        assertFalse(store.enrollCamera("1001", "wrong", "02:00:00:00:00:01").success)
        assertTrue(store.enrollCamera("1001", "1234", "02:00:00:00:00:01").success)
        assertTrue(store.state.value.cameras.containsKey("1001"))
        assertTrue(store.resetPin("1001", "5678").success)
        assertFalse(store.state.value.cameras.containsKey("1001"))
    }

    @Test fun deletingAndRecreatingAnAccountDoesNotRestoreItsCamera() {
        val store = CybercafeStore(preferences(CybercafeState(accounts = mapOf("1001" to account()))))
        assertTrue(store.enrollCamera("1001", "1234", "02:00:00:00:00:01").success)
        assertTrue(store.deleteAccount("1001").success)
        assertTrue(store.createAccount("1001", "5678", "New owner", 100).success)
        assertFalse(store.state.value.cameras.containsKey("1001"))
    }

    @Test fun adminMigrationKeepsTheExistingPassword() {
        val salt = "admin-salt"
        val hash = CybercafeSecurity.hashSecret(salt, "existing-password")
        val config = RemoteAdminConfig(enabled = true, username = "owner", passwordSalt = salt, passwordHash = hash)
        val store = CybercafeStore(preferences(CybercafeState(remoteAdmin = config)))
        val updated = store.state.value.remoteAdmin
        assertEquals("owner", updated.username)
        assertTrue(updated.enabled)
        assertEquals(CybercafeSecurity.PIN_KDF_ITERATIONS, updated.passwordKdfIterations)
        assertEquals(CybercafeSecurity.hashPin(salt, "existing-password", updated.passwordKdfIterations), updated.passwordHash)
    }

    @Test fun kotlinAndGoShareTheKdfVector() {
        assertEquals("28c41880cf8f2f594a05750a287add9c78b6ed0606d62743a851f05dbeb30ee1",
            CybercafeSecurity.hashPin("cross-platform-salt", "1234", CybercafeSecurity.PIN_KDF_ITERATIONS))
    }
}
