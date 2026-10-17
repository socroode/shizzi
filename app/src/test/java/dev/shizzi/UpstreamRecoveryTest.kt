package dev.shizzi

import org.junit.Assert.*
import org.junit.Test

class UpstreamRecoveryTest {
    @Test fun missingUpstreamRetriesWithoutAnEndlessLoop() {
        val recovery = UpstreamRecovery()
        val retries = (1..12).filter { recovery.shouldReselect(UpstreamReading.ABSENT) }
        assertEquals(listOf(1, 4, 7), retries)
    }
    @Test fun recoveryResetsAfterHealthyAndNeverExcusesDrift() {
        val recovery = UpstreamRecovery()
        assertTrue(recovery.shouldReselect(UpstreamReading.ABSENT))
        assertFalse(recovery.shouldReselect(UpstreamReading.DRIFTED))
        assertFalse(recovery.shouldReselect(UpstreamReading.TIMED_OUT))
        assertFalse(recovery.shouldReselect(UpstreamReading.HEALTHY))
        assertTrue(recovery.shouldReselect(UpstreamReading.ABSENT))
    }
}
