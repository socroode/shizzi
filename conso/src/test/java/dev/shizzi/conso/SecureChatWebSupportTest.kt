package dev.shizzi.conso

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureChatWebSupportTest {

    @Test
    fun callsUseSyntheticHttpsSecureOrigin() {
        assertTrue(SecureChatWebSupport.SECURE_BASE_URL.startsWith("https://"))
        assertTrue(SecureChatWebSupport.isTrustedMediaOrigin("https", "shizzi.local"))
        assertFalse(SecureChatWebSupport.isTrustedMediaOrigin("http", "192.0.2.1"))
        assertFalse(SecureChatWebSupport.isTrustedMediaOrigin("https", "example.com"))
    }

    @Test
    fun nativeBridgeOnlyMapsChatApiPathsToRouter() {
        assertEquals(
            "http://192.0.2.1/chat/api/call/poll?after=42",
            SecureChatWebSupport.apiUrl("call/poll?after=42"),
        )
        assertEquals(
            "http://192.0.2.1/chat/api/send",
            SecureChatWebSupport.apiUrl("/send"),
        )
        assertNull(SecureChatWebSupport.apiUrl("../status.json"))
        assertNull(SecureChatWebSupport.apiUrl("https://example.com/"))
        assertNull(SecureChatWebSupport.apiUrl(""))
    }

    @Test
    fun callRoutesRemainRecognizableDuringChatUiTransitions() {
        assertTrue(SecureChatWebSupport.isCallApiPath("call/start"))
        assertTrue(SecureChatWebSupport.isCallApiPath("call/answer"))
        assertTrue(SecureChatWebSupport.isCallApiPath("call/ice"))
        assertTrue(SecureChatWebSupport.isCallApiPath("call/poll?after=42"))
        assertTrue(SecureChatWebSupport.isCallApiPath("call/reject"))
        assertTrue(SecureChatWebSupport.isCallApiPath("call/end"))

        assertFalse(SecureChatWebSupport.isCallApiPath("send"))
        assertFalse(SecureChatWebSupport.isCallApiPath("snapshot"))
        assertFalse(SecureChatWebSupport.isCallApiPath("../call/answer"))
    }
}
