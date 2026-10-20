package dev.shizzi.accounts

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AccountDashboardLayoutTest {
    @Test fun liveTrafficAndCreditUpdatesPreserveAccountViewsButRenamingRebuildsThem() {
        val account = JSONObject().put("number", "1001").put("name", "Client test").put("enabled", true)
        val response = JSONObject().put("state", JSONObject().put("accounts", JSONArray().put(account)))
        val before = AccountDashboardLayout.key(response, true)
        account.put("totalDownBytes", 1_000_000).put("dataBalanceBytes", 50_000_000)
        response.put("traffic", JSONObject().put("accountUsage", JSONArray().put(JSONObject().put("upBytes", 123))))
        assertEquals(before, AccountDashboardLayout.key(response, true))
        account.put("name", "Client renommé")
        assertNotEquals(before, AccountDashboardLayout.key(response, true))
    }

    @Test fun fullDashboardStillDetectsOtherRouterChanges() {
        val response = JSONObject().put("routerName", "Shizzi")
        val before = AccountDashboardLayout.key(response, false)
        response.put("state", JSONObject().put("modules", JSONObject().put("mediaEnabled", false)))
        assertNotEquals(before, AccountDashboardLayout.key(response, false))
    }
}
