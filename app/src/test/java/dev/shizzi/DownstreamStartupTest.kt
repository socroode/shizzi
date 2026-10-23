package dev.shizzi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownstreamStartupTest {

    @Test
    fun hotspotStartsOnFirstAttemptWhenDownstreamAppears() {
        var starts = 0
        val result = startDownstreamWith(
            start = {
                starts++
                DownstreamStartAttempt(true, "accepted")
            },
            isTethered = { true },
        )

        assertTrue(result.success)
        assertEquals(1, result.attempts)
        assertEquals(1, starts)
    }

    @Test
    fun hotspotRetriesUntilRealDownstreamAppears() {
        var starts = 0
        val result = startDownstreamWith(
            start = {
                starts++
                DownstreamStartAttempt(true, "accepted-$starts")
            },
            isTethered = { starts >= 2 },
        )

        assertTrue(result.success)
        assertEquals(2, result.attempts)
        assertEquals(2, starts)
    }

    @Test
    fun rejectedCallbackStillSucceedsIfAndroidActuallyTethered() {
        val result = startDownstreamWith(
            start = { DownstreamStartAttempt(false, "callback rejected") },
            isTethered = { true },
        )

        assertTrue(result.success)
        assertEquals(1, result.attempts)
    }

    @Test
    fun hotspotFailureIsReportedAfterThreeAttempts() {
        var starts = 0
        val retries = mutableListOf<String>()
        val result = startDownstreamWith(
            start = {
                starts++
                DownstreamStartAttempt(false, "rejected-$starts")
            },
            isTethered = { false },
            onRetry = { retries += it },
        )

        assertFalse(result.success)
        assertEquals(DOWNSTREAM_START_ATTEMPTS, result.attempts)
        assertEquals(DOWNSTREAM_START_ATTEMPTS, starts)
        assertEquals(DOWNSTREAM_START_ATTEMPTS - 1, retries.size)
    }
}
