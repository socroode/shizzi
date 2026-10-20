package dev.shizzi.accounts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminRefreshPolicyTest {

    @Test
    fun refreshIntervalIsExactlyFiveSeconds() {
        assertEquals(5_000L, AdminRefreshPolicy.INTERVAL_MS)
        assertEquals(1_000L, AdminRefreshPolicy.interval(true))
        assertEquals(5_000L, AdminRefreshPolicy.interval(false))
    }

    @Test
    fun pollsOnlyWhenDashboardIsVisibleAndIdle() {
        assertTrue(
            AdminRefreshPolicy.shouldPoll(
                activityResumed = true,
                hasToken = true,
                hasWindowFocus = true,
                refreshInFlight = false,
            ),
        )
        assertFalse(AdminRefreshPolicy.shouldPoll(false, true, true, false))
        assertFalse(AdminRefreshPolicy.shouldPoll(true, false, true, false))
        assertFalse(AdminRefreshPolicy.shouldPoll(true, true, false, false))
        assertFalse(AdminRefreshPolicy.shouldPoll(true, true, true, true))
    }
}
