package dev.shizzi

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CallReceptionSnapshotTest {
    private val accounts = (1..8).associate { "100$it" to MessagingAccount("100$it", "Client $it", true) }
    private val offer = "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=Shizzi\r\nt=0 0\r\n"
    private fun start(hub: CallSignalingHub) = hub.start("1001", "1002", "audio", "offer", offer, accounts, 1_000).getString("callId")

    @Test fun snapshotRecoversOfferAndIceWithoutConsumingWebEvents() {
        val hub = CallSignalingHub(); val id = start(hub)
        hub.ice("1001", id, JSONObject().put("candidate", "candidate:1 1 UDP 1 192.168.7.2 5000 typ host"), accounts, 1_100)
        val snapshot = hub.incomingState("1002", accounts, 1_200)
        val incoming = snapshot.getJSONObject("incomingCall")
        assertEquals(id, incoming.getString("callId"))
        assertEquals(offer, incoming.getJSONObject("description").getString("sdp"))
        assertEquals(1, incoming.getJSONArray("candidates").length())
        assertEquals(2, hub.poll("1002", 0, accounts, 1_300).getJSONArray("events").length())
    }
    @Test fun sixIndependentReceiversSeeOnlyTheirOwnCall() {
        val hub = CallSignalingHub(); val id = start(hub)
        for (n in 2..7) {
            val state = hub.incomingState("100$n", accounts, 1_200)
            assertEquals("100$n", state.getString("accountNumber"))
            if (n == 2) assertEquals(id, state.getJSONObject("incomingCall").getString("callId"))
            else assertTrue(state.isNull("incomingCall"))
        }
        assertTrue(hub.incomingState("1001", accounts, 1_200).isNull("incomingCall"))
    }
    @Test fun callerHangupClearsSnapshotEvenWithHistoricalIncomingEvent() {
        val hub = CallSignalingHub(); val id = start(hub)
        hub.end("1001", id, accounts, 1_200)
        assertTrue(hub.incomingState("1002", accounts, 1_300).isNull("incomingCall"))
        assertTrue(hub.poll("1002", 0, accounts, 1_300).isNull("incomingCall"))
    }
    @Test fun rejectionAndAnswerBothClearRingingState() {
        val hub = CallSignalingHub(); val first = start(hub)
        hub.reject("1002", first, accounts, 1_200)
        assertTrue(hub.incomingState("1002", accounts, 1_300).isNull("incomingCall"))
        val second = hub.start("1001", "1002", "audio", "offer", offer, accounts, 2_000).getString("callId")
        hub.answer("1002", second, "answer", offer, accounts, 2_100)
        assertTrue(hub.incomingState("1002", accounts, 2_200).isNull("incomingCall"))
    }
    @Test fun expiredAndSuspendedAccountsNeverGetRingingState() {
        val hub = CallSignalingHub(); start(hub)
        assertTrue(hub.incomingState("1002", accounts, 50_000).isNull("incomingCall"))
        val suspended = accounts + ("1002" to MessagingAccount("1002", "Suspended", false))
        assertFalse(hub.incomingState("1002", suspended, 50_000).getBoolean("ok"))
    }
    @Test fun liveSnapshotSurvivesAClientCursorFromBeforeRouterRestart() {
        val hub = CallSignalingHub(); val id = start(hub)
        val state = hub.poll("1002", 1_000_000, accounts, 1_100)
        assertEquals(id, state.getJSONObject("incomingCall").getString("callId"))
    }
}
