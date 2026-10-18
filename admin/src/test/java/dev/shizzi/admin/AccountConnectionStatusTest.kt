package dev.shizzi.admin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountConnectionStatusTest {
    private fun session(account: String, presence: String?) = JSONObject().put("accountNumber", account)
        .put("presence", presence).put("authorized", false)
    private fun traffic(vararg sessions: JSONObject) = JSONObject().put("portalRequired", true)
        .put("portalAuthorizations", JSONArray(sessions.toList()))

    @Test fun confirmedSessionIsConnectedEvenWhenInternetAllowanceIsExhausted() {
        assertEquals(AccountConnectionStatus.CONNECTED,
            AccountConnectionStatus.fromTraffic("1001", traffic(session("1001", "online"))))
    }

    @Test fun anotherAccountNeverMakesThisAccountAppearConnected() {
        assertEquals(AccountConnectionStatus.DISCONNECTED,
            AccountConnectionStatus.fromTraffic("1001", traffic(session("10010", "online"))))
    }

    @Test fun retainedSessionWithMissingDeviceIsDisconnected() {
        assertEquals(AccountConnectionStatus.DISCONNECTED,
            AccountConnectionStatus.fromTraffic("1001", traffic(session("1001", "missing"))))
    }

    @Test fun unknownOrLegacyPresenceNeverClaimsConfirmedConnection() {
        for (presence in listOf("unknown", null, "unexpected")) {
            assertEquals(AccountConnectionStatus.CHECKING,
                AccountConnectionStatus.fromTraffic("1001", traffic(session("1001", presence))))
        }
    }

    @Test fun anyConfirmedSessionWinsOverAnotherStaleSession() {
        assertEquals(AccountConnectionStatus.CONNECTED,
            AccountConnectionStatus.fromTraffic("1001", traffic(
                session("1001", "missing"), session("1001", "unknown"), session("1001", "online"))))
    }

    @Test fun omittedEmptyGoArrayMeansDisconnectedWithAValidTrafficSnapshot() {
        assertEquals(AccountConnectionStatus.DISCONNECTED,
            AccountConnectionStatus.fromTraffic("1001", JSONObject().put("portalRequired", true)))
        assertEquals(AccountConnectionStatus.DISCONNECTED,
            AccountConnectionStatus.fromTraffic("1001", traffic()))
    }

    @Test fun failedOrUnavailableSnapshotIsNotReportedAsAConfirmedDisconnection() {
        for (snapshot in listOf(null, JSONObject())) {
            assertEquals(AccountConnectionStatus.UNAVAILABLE,
                AccountConnectionStatus.fromTraffic("1001", snapshot))
        }
    }
}
