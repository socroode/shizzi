package dev.shizzi

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSignalingHubTest {

    private fun accounts(count: Int = 8): Map<String, MessagingAccount> =
        (1..count).associate { index ->
            val number = "100$index"
            number to MessagingAccount(number, "Client $index", true)
        }

    private fun offer(): String =
        "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=Shizzi\r\nt=0 0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"

    private fun answer(): String =
        "v=0\r\no=- 2 2 IN IP4 127.0.0.1\r\ns=Shizzi\r\nt=0 0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"

    @Test
    fun incomingCallIsVisibleOnlyToCallee() {
        val hub = CallSignalingHub()
        val directory = accounts()
        val start = hub.start(
            callerRaw = "1001",
            calleeRaw = "1002",
            kindRaw = "audio",
            offerTypeRaw = "offer",
            offerSdpRaw = offer(),
            accounts = directory,
            nowMillis = 1_000L,
        )
        assertTrue(start.getBoolean("ok"))
        val callId = start.getString("callId")

        val callee = hub.poll("1002", 0L, directory, 1_100L)
        val events = callee.getJSONArray("events")
        assertEquals(1, events.length())
        assertEquals("incoming", events.getJSONObject(0).getString("type"))
        assertEquals(callId, events.getJSONObject(0).getString("callId"))
        assertEquals("1001", events.getJSONObject(0).getString("from"))
        assertEquals(offer(), events.getJSONObject(0).getJSONObject("description").getString("sdp"))
        assertTrue(events.getJSONObject(0).getJSONObject("description").getString("sdp").endsWith("\r\n"))

        for (index in 3..8) {
            val outsider = hub.poll("100$index", 0L, directory, 1_100L)
            assertEquals(0, outsider.getJSONArray("events").length())
        }
    }

    @Test
    fun calleeAnswersAndOnlyCallerReceivesAnswer() {
        val hub = CallSignalingHub()
        val directory = accounts()
        val started = hub.start("1001", "1002", "video", "offer", offer(), directory, 1_000L)
        val callId = started.getString("callId")
        val answer = hub.answer("1002", callId, "answer", answer(), directory, 1_200L)
        assertTrue(answer.getBoolean("ok"))

        val caller = hub.poll("1001", 0L, directory, 1_300L)
        val event = caller.getJSONArray("events").getJSONObject(0)
        assertEquals("answer", event.getString("type"))
        assertEquals("video", event.getString("kind"))
        assertEquals("answer", event.getJSONObject("description").getString("type"))
        assertEquals(answer(), event.getJSONObject("description").getString("sdp"))
        assertTrue(event.getJSONObject("description").getString("sdp").endsWith("\r\n"))

        val outsider = hub.poll("1003", 0L, directory, 1_300L)
        assertEquals(0, outsider.getJSONArray("events").length())
    }

    @Test
    fun outsiderCannotAnswerInjectIceOrEndCall() {
        val hub = CallSignalingHub()
        val directory = accounts()
        val started = hub.start("1001", "1002", "audio", "offer", offer(), directory, 1_000L)
        val callId = started.getString("callId")

        assertFalse(hub.answer("1003", callId, "answer", answer(), directory, 1_100L).getBoolean("ok"))
        assertFalse(
            hub.ice(
                "1003",
                callId,
                JSONObject().put("candidate", "candidate:1 1 UDP 1 192.168.7.3 5000 typ host"),
                directory,
                1_100L,
            ).getBoolean("ok"),
        )
        assertFalse(hub.end("1003", callId, directory, 1_100L).getBoolean("ok"))
        assertNotNull(hub.activeCallForAccount("1001", 1_100L))
    }

    @Test
    fun iceIsDeliveredOnlyToOtherParticipant() {
        val hub = CallSignalingHub()
        val directory = accounts()
        val started = hub.start("1001", "1002", "video", "offer", offer(), directory, 1_000L)
        val callId = started.getString("callId")

        val candidate = JSONObject()
            .put("candidate", "candidate:1 1 UDP 2122260223 192.168.7.10 50123 typ host")
            .put("sdpMid", "0")
            .put("sdpMLineIndex", 0)

        val sent = hub.ice("1001", callId, candidate, directory, 1_050L)
        assertTrue(sent.getBoolean("ok"))

        val callee = hub.poll("1002", 0L, directory, 1_100L)
        val eventTypes = (0 until callee.getJSONArray("events").length())
            .map { callee.getJSONArray("events").getJSONObject(it).getString("type") }
        assertTrue(eventTypes.contains("incoming"))
        assertTrue(eventTypes.contains("ice"))

        val outsider = hub.poll("1004", 0L, directory, 1_100L)
        assertEquals(0, outsider.getJSONArray("events").length())
    }

    @Test
    fun busyAccountCannotReceiveSecondCallAcrossEightAccounts() {
        val hub = CallSignalingHub()
        val directory = accounts()
        val first = hub.start("1001", "1002", "audio", "offer", offer(), directory, 1_000L)
        assertTrue(first.getBoolean("ok"))

        val second = hub.start("1003", "1002", "video", "offer", offer(), directory, 1_100L)
        assertFalse(second.getBoolean("ok"))

        val callerBusy = hub.start("1001", "1004", "audio", "offer", offer(), directory, 1_100L)
        assertFalse(callerBusy.getBoolean("ok"))

        for (index in 5..8) {
            val account = "100$index"
            assertNull(hub.activeCallForAccount(account, 1_100L))
        }
    }

    @Test
    fun rejectAndHangupFreeBothAccounts() {
        val hub = CallSignalingHub()
        val directory = accounts()

        val first = hub.start("1001", "1002", "audio", "offer", offer(), directory, 1_000L)
        val firstId = first.getString("callId")
        assertTrue(hub.reject("1002", firstId, directory, 1_100L).getBoolean("ok"))
        assertNull(hub.activeCallForAccount("1001", 1_100L))
        assertNull(hub.activeCallForAccount("1002", 1_100L))

        val second = hub.start("1001", "1002", "video", "offer", offer(), directory, 1_200L)
        val secondId = second.getString("callId")
        assertTrue(hub.answer("1002", secondId, "answer", answer(), directory, 1_300L).getBoolean("ok"))
        assertNotNull(hub.activeCallForAccount("1001", 1_300L))
        assertTrue(hub.end("1001", secondId, directory, 1_400L).getBoolean("ok"))
        assertNull(hub.activeCallForAccount("1001", 1_400L))
        assertNull(hub.activeCallForAccount("1002", 1_400L))
    }

    @Test
    fun ringingCallTimesOutAndAccountsBecomeAvailable() {
        val hub = CallSignalingHub()
        val directory = accounts()
        val first = hub.start("1001", "1002", "audio", "offer", offer(), directory, 1_000L)
        assertTrue(first.getBoolean("ok"))

        val afterTimeout = 1_000L + CallSignalingHub.RING_TIMEOUT_MILLIS + 1L
        assertNull(hub.activeCallForAccount("1001", afterTimeout))
        assertNull(hub.activeCallForAccount("1002", afterTimeout))

        val next = hub.start("1003", "1002", "audio", "offer", offer(), directory, afterTimeout + 1L)
        assertTrue(next.getBoolean("ok"))
    }
}
