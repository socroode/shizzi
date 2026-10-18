package dev.shizzi.conso

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CallReceptionProtocolTest {
    private fun snapshot(id: String? = "call:1", account: String = "1002", now: Long = 1_000) = JSONObject()
        .put("ok", true).put("accountNumber", account).put("serverTimeMillis", now)
        .put("incomingCall", id?.let { JSONObject().put("callId", it).put("from", "1001")
            .put("fromName", "Alice").put("kind", "video").put("expiresAtMillis", 45_000) } ?: JSONObject.NULL)

    @Test fun incomingCallerAndKindAreDecoded() {
        val p = CallReceptionProtocol(); val call = p.update(snapshot())!!
        assertEquals("1002", p.account); assertEquals("Alice", call.name); assertTrue(call.video)
    }
    @Test fun repeatedPollDoesNotLoseIncoming() {
        val p = CallReceptionProtocol(); val a = p.update(snapshot()); assertEquals(a, p.update(snapshot()))
    }
    @Test fun answeringSilencesUntilServerStateChanges() {
        val p = CallReceptionProtocol(); p.update(snapshot()); p.silence()
        assertNull(p.update(snapshot())); assertEquals("call:1", p.incoming?.id)
        p.update(snapshot(null)); assertNotNull(p.update(snapshot("call:2")))
    }
    @Test fun finishedCallRemovesAlertAndNeverReplaysEvents() {
        val p = CallReceptionProtocol(); p.update(snapshot())
        val ended = snapshot(null).put("events", org.json.JSONArray().put(JSONObject().put("type", "incoming").put("callId", "old")))
        assertNull(p.update(ended)); assertNull(p.incoming)
    }
    @Test fun serverClockIsUsedForExpiry() {
        assertNull(CallReceptionProtocol().update(snapshot(now = 45_000)))
        assertNotNull(CallReceptionProtocol().update(snapshot(now = 44_999)))
    }
    @Test fun changingAccountClearsSilencedCall() {
        val p = CallReceptionProtocol(); p.update(snapshot()); p.silence()
        assertNotNull(p.update(snapshot(account = "1003"))); assertEquals("1003", p.account)
    }
    @Test fun oldRouterIsIdentifiedWithoutInventingReceptionSupport() {
        assertThrows(IllegalStateException::class.java) { CallReceptionProtocol().update(JSONObject().put("ok", true)) }
    }
    @Test fun unauthenticatedSnapshotIsRejected() {
        assertThrows(IllegalStateException::class.java) { CallReceptionProtocol().update(snapshot().put("ok", false)) }
    }
    @Test fun networkLossClearsThePreviousAccount() {
        val p = CallReceptionProtocol(); p.update(snapshot()); p.reset()
        assertEquals("", p.account); assertNull(p.incoming)
    }
}
