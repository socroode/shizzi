package dev.shizzi.admin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AccountSessionSummaryTest {
    private fun session(number: String, ip: String, presence: String = "unknown", authorized: Boolean = true) =
        JSONObject().put("accountNumber", number).put("ip", ip).put("mac", "02:00:00:00:00:01")
            .put("presence", presence).put("authorized", authorized)
    private fun traffic(vararg sessions: JSONObject) = JSONObject().put("portalRequired", true)
        .put("portalAuthorizations", JSONArray(sessions.toList()))

    @Test fun detailsAreAttachedOnlyToTheExactAccount() {
        val data = traffic(session("1001", "10.0.0.1"), session("10010", "10.0.0.2"))
        val result = AccountSessionSummary.fromTraffic("1001", data)
        assertEquals(1, result.sessions.size)
        assertTrue(result.details.contains("10.0.0.1"))
        assertFalse(result.details.contains("10.0.0.2"))
        assertFalse(result.details.contains("1001"))
    }

    @Test fun registeredSessionDoesNotClaimConfirmedWifiPresence() {
        val result = AccountSessionSummary.fromTraffic("1001", traffic(session("1001", "10.0.0.1")))
        assertEquals("Session enregistrée", result.headingLabel)
        assertEquals("Internet autorisé · Wi-Fi non vérifiable", result.information)
    }

    @Test fun internetPermissionRemainsDistinctFromConnectedWifi() {
        val result = AccountSessionSummary.fromTraffic("1001", traffic(session("1001", "10.0.0.1", "online", false)))
        assertEquals("Connecté", result.headingLabel)
        assertEquals("Internet bloqué · Wi-Fi confirmé", result.information)
    }

    @Test fun losingTheSessionRemovesItsIpAndMac() {
        val previous = AccountSessionSummary.fromTraffic("1001", traffic(session("1001", "10.0.0.1")))
        val current = AccountSessionSummary.fromTraffic("1001", traffic())
        assertTrue(previous.sessions.isNotEmpty())
        assertTrue(current.sessions.isEmpty())
        assertEquals("", current.details)
        assertEquals("Aucune session enregistrée", current.information)
    }

    @Test fun failedRefreshCannotKeepShowingOldDeviceDetails() {
        val result = AccountSessionSummary.fromTraffic("1001", null)
        assertEquals(AccountConnectionStatus.UNAVAILABLE, result.connection)
        assertTrue(result.sessions.isEmpty())
        assertEquals("État des sessions indisponible", result.information)
    }

    @Test fun missingLegacyInternetFieldIsNotReportedAsBlocked() {
        val legacy = session("1001", "10.0.0.1").apply { remove("authorized") }
        val result = AccountSessionSummary.fromTraffic("1001", traffic(legacy))
        assertTrue(result.information.startsWith("Accès Internet non renseigné"))
    }
}
