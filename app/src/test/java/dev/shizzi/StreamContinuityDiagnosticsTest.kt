package dev.shizzi

import org.junit.Assert.*
import org.junit.Test

class StreamContinuityDiagnosticsTest {
    @Test
    fun flowEndsAndPacingRemainSeparateFromAccountRevocation() {
        val d = parseLiveTrafficSnapshot("""{"continuity":{
            "pacingWaits":42,"pacingMaxWaitMillis":125,"pacingCancelled":3,
            "recent":[{"protocol":"tcp","direction":"download","reason":"read-eof","atMillis":1234}]
        }}""").attribution
        assertEquals(42L, d.pacingWaits)
        assertEquals(125L, d.pacingMaxWaitMillis)
        assertEquals(3L, d.pacingCancelled)
        assertEquals("read-eof", d.lastFlowEndReason)
        assertEquals("tcp", d.lastFlowEndProtocol)
        assertEquals("download", d.lastFlowEndDirection)
        assertEquals(1234L, d.lastFlowEndMillis)
        assertEquals("", d.lastSessionEndReason)
        assertEquals("fin normale de l’envoi", flowEndReasonLabel(d.lastFlowEndReason))
    }

    @Test
    fun missingAndEmptyFlowHistoryRemainCompatible() {
        for (json in listOf("{}", """{"continuity":{"recent":[]}}""")) {
            val d = parseLiveTrafficSnapshot(json).attribution
            assertEquals("", d.lastFlowEndReason)
            assertEquals(0L, d.pacingMaxWaitMillis)
        }
    }
}
