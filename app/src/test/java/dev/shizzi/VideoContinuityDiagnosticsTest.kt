package dev.shizzi

import org.junit.Assert.*
import org.junit.Test

class VideoContinuityDiagnosticsTest {
    @Test
    fun missingLeaseAndSessionEndRemainDistinctInDiagnostics() {
        val d = parseLiveTrafficSnapshot("""{"attribution":{
            "callbackPresenceKnown":true,"callbackStationCount":2,"callbackLeaseCount":1,
            "callbackMappingComplete":false,"bpfDiscardedAbsentOwners":3,
            "lastUnresolvedReason":"nat-log-conflict"
        },"lastSessionEnd":{
            "reason":"account-moved-to-another-device","message":"Compte utilisé sur un autre appareil.",
            "accountNumber":"1001","atMillis":1234
        }}""").attribution
        assertTrue(d.callbackPresenceKnown)
        assertEquals(2, d.callbackStationCount)
        assertEquals(1, d.callbackLeaseCount)
        assertFalse(d.callbackMappingComplete)
        assertEquals(3, d.bpfDiscardedAbsentOwners)
        assertEquals("nat-log-conflict", d.lastUnresolvedReason)
        assertEquals("account-moved-to-another-device", d.lastSessionEndReason)
        assertEquals("Compte utilisé sur un autre appareil.", d.lastSessionEndMessage)
        assertEquals("1001", d.lastSessionEndAccount)
        assertEquals(1234L, d.lastSessionEndMillis)
        val old = parseLiveTrafficSnapshot("{}").attribution
        assertFalse(old.callbackPresenceKnown)
        assertEquals("", old.lastSessionEndReason)
    }
}
