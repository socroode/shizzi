package dev.shizzi

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HotspotRecoveryTest {
    @Test
    fun `new session is started after confirmed shutdown`() = runBlocking {
        val events = mutableListOf<String>()
        val result = runHotspotRecoveryAttempt(
            stillWanted = { true },
            confirmStopped = { events += "confirmed down" },
            start = { events += "started"; "testtun1" },
        )
        assertEquals("testtun1", result)
        assertEquals(listOf("confirmed down", "started"), events)
    }

    @Test
    fun `failed confirmation does not start a second session`() = runBlocking {
        var started = false
        val attempt = runCatching {
            runHotspotRecoveryAttempt({ true }, { error("downstream state unknown") }, { started = true })
        }
        assertTrue(attempt.isFailure)
        assertEquals(false, started)
    }

    @Test
    fun `stop requested during cleanup prevents automatic restart`() = runBlocking {
        var wanted = true
        val result = runHotspotRecoveryAttempt<String>({ wanted }, { wanted = false }, { error("must not start") })
        assertNull(result)
    }

    @Test
    fun `no recovery is attempted once stopped by the user`() = runBlocking {
        val result = runHotspotRecoveryAttempt<String>({ false }, { error("must not stop") }, { error("must not start") })
        assertNull(result)
    }
}
