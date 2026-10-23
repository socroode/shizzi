package dev.shizzi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CybercafeLedgerTest {

    private val gb = 1_000_000_000L

    private fun account(number: String, dataBytes: Long) = PrepaidAccount(
        number = number,
        name = "RONIU",
        pinSalt = "s",
        pinHash = "h",
        dataBalanceBytes = dataBytes,
        dataValidUntilMillis = Long.MAX_VALUE,
    )

    /** Mirrors CybercafeStore.recordAccountUsage without Android storage. */
    private fun PrepaidAccount.apply(delta: UsageDelta) = copy(
        totalUpBytes = totalUpBytes + delta.upBytes,
        totalDownBytes = totalDownBytes + delta.downBytes,
        dataBalanceBytes = (dataBalanceBytes - delta.dataBytes).coerceAtLeast(0L),
    )

    @Test
    fun sharedAccountOnTwoDevicesIsDeductedOnce() {
        // B uses 3 Go and C 2 Go on RONIU (20 Go): the datapath reports one
        // account counter, so RONIU ends at 15 Go, not 17 or 18 per device.
        val ledger = UsageLedger()
        var roniu = account("2000", 20 * gb)

        val (_, first) = ledger.absorb(
            epoch = 42L,
            usage = listOf(LiveAccountUsage("2000", 0L, 3 * gb, 3 * gb)),
        )
        first.forEach { roniu = roniu.apply(it) }
        val (_, second) = ledger.absorb(
            epoch = 42L,
            usage = listOf(LiveAccountUsage("2000", 0L, 5 * gb, 5 * gb)),
        )
        second.forEach { roniu = roniu.apply(it) }

        assertEquals(15 * gb, roniu.dataBalanceBytes)
        assertEquals(5 * gb, roniu.totalDownBytes)
    }

    @Test
    fun sameCountersAreNeverAppliedTwice() {
        val ledger = UsageLedger()
        ledger.absorb(7L, listOf(LiveAccountUsage("1", 10L, 20L, 30L)))
        val (_, again) = ledger.absorb(7L, listOf(LiveAccountUsage("1", 10L, 20L, 30L)))
        assertTrue(again.isEmpty())
    }

    @Test
    fun markersEchoWhatWasApplied() {
        val ledger = UsageLedger()
        ledger.absorb(7L, listOf(LiveAccountUsage("1", 100L, 400L, 300L)))
        val marker = ledger.markers().getValue("1")
        assertEquals(300L, marker.consumedMarkerBytes)
        assertEquals(500L, marker.usageMarkerBytes)
    }

    @Test
    fun newDatapathInstanceRestartsCountersWithoutNegativeDeltas() {
        val ledger = UsageLedger()
        ledger.absorb(7L, listOf(LiveAccountUsage("1", 100L, 400L, 300L)))
        val (restarted, deltas) = ledger.absorb(8L, listOf(LiveAccountUsage("1", 5L, 6L, 7L)))
        assertTrue(restarted)
        assertEquals(listOf(UsageDelta("1", 5L, 6L, 7L)), deltas)
    }

    @Test
    fun unlimitedUsageCountsButDoesNotDeductData() {
        // During Unlimited the datapath reports dataBytes = 0: totals grow for
        // information, the stored Data stays for after the Unlimited ends.
        val ledger = UsageLedger()
        var roniu = account("2000", 12 * gb)
        ledger.absorb(1L, listOf(LiveAccountUsage("2000", gb, 4 * gb, 0L)))
            .second.forEach { roniu = roniu.apply(it) }
        assertEquals(12 * gb, roniu.dataBalanceBytes)
        assertEquals(5 * gb, roniu.totalUpBytes + roniu.totalDownBytes)
    }

    @Test
    fun rateMeterMeasuresPerSessionAndForgetsEndedOnes() {
        val meter = SessionRateMeter()
        assertEquals(0L to 0L, meter.measure("a|1", 0L, 0L, 1_000L))
        val (up, down) = meter.measure("a|1", 125_000L, 1_250_000L, 2_000L)
        assertEquals(1_000_000L, up)
        assertEquals(10_000_000L, down)
        meter.retain(emptySet())
        assertEquals(0L to 0L, meter.measure("a|1", 0L, 0L, 3_000L))
    }

    @Test
    fun planLabelFollowsAccountState() {
        val now = 1_000L
        assertEquals("Data", account("1", 10L).planLabel(now))
        assertEquals("Aucun forfait actif", account("1", 0L).planLabel(now))
        assertEquals(
            "Illimité Premium",
            account("1", 10L).copy(unlimitedUntilMillis = 2_000L, unlimitedPlanName = "Illimité Premium")
                .planLabel(now),
        )
        assertFalse(account("1", 10L).copy(enabled = false).hasInternet(now))
    }
}
