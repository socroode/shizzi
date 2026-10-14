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
