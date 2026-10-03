package dev.shizzi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagingCallWebUiTest {

    @Test
    fun callUiContainsLocalWebRtcVoiceVideoAndControls() {
        val page = MessagingWebUi.page()

        listOf(
            "RTCPeerConnection",
            "getUserMedia",
            "window.isSecureContext",
            "ShizziNativeBridge",
            "nativeBridgeAvailable",
            "startIncomingRingtone",
            "startOutgoingRingback",
            "stopCallTone",
            "navigator.vibrate",
            "call/start",
            "call/answer",
            "call/ice",
            "call/reject",
            "call/end",
            "call/poll?after=",
            "Appel vocal",
            "Appel vidéo",
            "Décrocher",
            "Refuser",
            "Raccrocher",
            "Retourner",
            "echoCancellation",
            "noiseSuppression",
            "facingMode",
        ).forEach { expected ->
            assertTrue("missing $expected", page.contains(expected))
        }
    }

    @Test
    fun webRtcConfigurationDoesNotUseInternetIceServers() {
        val page = MessagingWebUi.page().lowercase()

        assertTrue(page.contains("iceservers:[]"))
        assertFalse(page.contains("stun:"))
        assertFalse(page.contains("turn:"))
        assertFalse(page.contains("turns:"))
    }
}
