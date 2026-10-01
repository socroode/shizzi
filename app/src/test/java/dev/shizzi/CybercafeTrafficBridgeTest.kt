package dev.shizzi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CybercafeTrafficBridgeTest {

    @Test
    fun mediaDiagnosticsAreParsedForHotspotUi() {
        val snapshot = parseLiveTrafficSnapshot(
            """{
              "epoch": 42,
              "mediaDiagnostics": [{
                "atMillis": 123456789,
                "clientIp": "192.168.7.66",
                "path": "/media/",
                "accountAuthenticated": true,
                "accountNumber": "RONIU",
                "proxyTarget": "/",
                "backend": "127.0.0.1:8088",
                "backendConnected": true,
                "bytesCopied": 2048,
                "result": "proxied"
              }]
            }""",
        )

        assertEquals(42L, snapshot.epoch)
        assertEquals(1, snapshot.mediaDiagnostics.size)

        val event = snapshot.mediaDiagnostics.single()
        assertEquals("192.168.7.66", event.clientIp)
        assertEquals("/media/", event.path)
        assertTrue(event.accountAuthenticated)
        assertEquals("RONIU", event.accountNumber)
        assertEquals("/", event.proxyTarget)
        assertEquals("127.0.0.1:8088", event.backend)
        assertTrue(event.backendConnected)
        assertEquals(2048L, event.bytesCopied)
        assertEquals("proxied", event.result)
        assertFalse(event.error.isNotBlank())
    }
}
