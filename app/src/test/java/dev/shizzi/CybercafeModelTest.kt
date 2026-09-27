package dev.shizzi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CybercafeModelTest {

    private val day = 86_400_000L

    private fun account(number: String = "1001"): PrepaidAccount {
        val salt = "salt"
        return PrepaidAccount(
            number = number,
            name = "Test",
            pinSalt = salt,
            pinHash = CybercafeSecurity.hashPin(salt, "1234"),
        )
    }

    @Test
    fun dataVouchersAccumulateBytesButResetValidityFromRechargeTime() {
        val first = Voucher("A", "eco-12", 0L)
        val second = Voucher("B", "eco-20", 0L)
        val initial = CybercafeState(
            accounts = mapOf("1001" to account()),
            vouchers = mapOf("A" to first, "B" to second),
        )

        val at = 1_000_000L
        val afterFirst = CybercafeRules.redeemVoucher(initial, "1001", "A", at)
        assertTrue(afterFirst.success)
        val firstAccount = afterFirst.state.accounts.getValue("1001")
        assertEquals(12_000_000_000L, firstAccount.dataBalanceBytes)
        assertEquals(at + 30L * day, firstAccount.dataValidUntilMillis)

        val later = at + 5L * day
        val afterSecond = CybercafeRules.redeemVoucher(afterFirst.state, "1001", "B", later)
        assertTrue(afterSecond.success)
        val secondAccount = afterSecond.state.accounts.getValue("1001")
        assertEquals(32_000_000_000L, secondAccount.dataBalanceBytes)
        assertEquals(later + 30L * day, secondAccount.dataValidUntilMillis)
    }

    @Test
    fun unlimitedDurationAccumulates() {
        val state = CybercafeState(
            accounts = mapOf("1001" to account()),
            vouchers = mapOf(
                "A" to Voucher("A", "unlimited-eco", 0L),
                "B" to Voucher("B", "unlimited-comfort", 0L),
            ),
        )
        val at = 2_000_000L

        val first = CybercafeRules.redeemVoucher(state, "1001", "A", at)
        val second = CybercafeRules.redeemVoucher(first.state, "1001", "B", at + day)

        assertTrue(second.success)
        assertEquals(
            at + 60L * day,
            second.state.accounts.getValue("1001").unlimitedUntilMillis,
        )
    }

    @Test
    fun dataBalanceIsNotConsumedWhileUnlimitedIsActive() {
        val acct = account().copy(
            dataBalanceBytes = 1_000_000_000L,
            dataValidUntilMillis = 100L * day,
            unlimitedUntilMillis = 10L * day,
        )
        val state = CybercafeState(
            accounts = mapOf("1001" to acct),
            devices = mapOf(
                "device-a" to DeviceBinding("device-a", "1001"),
            ),
        )

        val updated = CybercafeRules.recordTraffic(
            state,
            "device-a",
            uploadBytes = 10_000L,
            downloadBytes = 20_000L,
            nowMillis = day,
        )

        assertEquals(
            1_000_000_000L,
            updated.accounts.getValue("1001").dataBalanceBytes,
        )
    }

    @Test
    fun oneAccountCannotBeBoundToTwoDifferentDevices() {
        val state = CybercafeState(accounts = mapOf("1001" to account()))
        val first = CybercafeRules.bindDevice(
            state,
            "1001",
            "device-a",
            "192.168.1.2",
            "aa:bb:cc:dd:ee:ff",
            1L,
        )
        assertTrue(first.success)

        val second = CybercafeRules.bindDevice(
            first.state,
            "1001",
            "device-b",
            "192.168.1.3",
            "11:22:33:44:55:66",
            2L,
        )
        assertFalse(second.success)
    }

    @Test
    fun voucherCannotBeRedeemedTwice() {
        val state = CybercafeState(
            accounts = mapOf("1001" to account(), "1002" to account("1002")),
            vouchers = mapOf("A" to Voucher("A", "eco-12", 0L)),
        )

        val first = CybercafeRules.redeemVoucher(state, "1001", "A", 1L)
        val second = CybercafeRules.redeemVoucher(first.state, "1002", "A", 2L)

        assertTrue(first.success)
        assertFalse(second.success)
    }
}
