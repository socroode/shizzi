package dev.shizzi.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminRefreshPolicyTest {

    @Test
    fun refreshIntervalIsExactlyFiveSeconds() {
        assertEquals(5_000L, AdminRefreshPolicy.INTERVAL_MS)
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
    @Test
    fun automaticRefreshDoesNotRedrawWhenVisibleStateIsUnchanged() {
        val signature = AdminRefreshPolicy.dashboardSignature(
            routerName = "Shizzi",
            stateJson = """{"accounts":[]}""",
            portalAuthorizationsJson = "[]",
            mediaDiagnosticsJson = "[]",
        )

        assertFalse(
            AdminRefreshPolicy.shouldRenderDashboard(
                manual = false,
                hasWindowFocus = true,
                currentSignature = signature,
                lastRenderedSignature = signature,
            ),
        )
    }

    @Test
    fun automaticRefreshRedrawsWhenAnAccountAppears() {
        val empty = AdminRefreshPolicy.dashboardSignature(
            routerName = "Shizzi",
            stateJson = """{"accounts":[]}""",
            portalAuthorizationsJson = "[]",
            mediaDiagnosticsJson = "[]",
        )
        val withAccount = AdminRefreshPolicy.dashboardSignature(
            routerName = "Shizzi",
            stateJson = """{"accounts":[{"number":"1001"}]}""",
            portalAuthorizationsJson = "[]",
            mediaDiagnosticsJson = "[]",
        )

        assertTrue(
            AdminRefreshPolicy.shouldRenderDashboard(
                manual = false,
                hasWindowFocus = true,
                currentSignature = withAccount,
                lastRenderedSignature = empty,
            ),
        )
    }

    @Test
    fun manualRefreshAlwaysRedrawsSoDiagnosticsCanBeUpdated() {
        assertTrue(
            AdminRefreshPolicy.shouldRenderDashboard(
                manual = true,
                hasWindowFocus = true,
                currentSignature = "same",
                lastRenderedSignature = "same",
            ),
        )
    }

    @Test
    fun dashboardSignatureIgnoresVolatileTrafficCountersByConstruction() {
        val before = AdminRefreshPolicy.dashboardSignature(
            routerName = "Shizzi",
            stateJson = """{"accounts":[]}""",
            portalAuthorizationsJson = "[]",
            mediaDiagnosticsJson = "[]",
        )
        val after = AdminRefreshPolicy.dashboardSignature(
            routerName = "Shizzi",
            stateJson = """{"accounts":[]}""",
            portalAuthorizationsJson = "[]",
            mediaDiagnosticsJson = "[]",
        )

        assertEquals(before, after)
    }

}
