package dev.shizzi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TetheringObservationTest {
    @Test
    fun `a valid AOSP report can confirm an active or stopped downstream`() {
        val active = report("wlan1 - TetheredState - lastError = 0")
        assertTrue(active.hasUpstreamReport)
        assertEquals(listOf("wlan1"), active.tetheredDownstreams())
        assertEquals(emptyList<String>(), report("wlan1 - AvailableState - lastError = 0").tetheredDownstreams())
    }

    @Test
    fun `timeouts and failed dumps never prove that the hotspot is stopped`() {
        val known = report("wlan1 - UnavailableState - lastError = 0")
        for (observation in listOf(
            known.copy(didTimeout = true),
            known.copy(commandSucceeded = false),
            known.copy(rawOutput = known.rawOutput + "\n*** SERVICE DUMP TIMEOUT ***"),
            known.copy(rawOutput = "Permission Denial"),
        )) {
            assertFalse(observation.hasUpstreamReport)
            assertNull(observation.tetheredDownstreams())
        }
    }

    @Test
    fun `unrecognized or partial downstream sections do not prove shutdown`() {
        val unknown = report("wlan1 - UnknownState - lastError = 0")
        assertNull(unknown.tetheredDownstreams())
        assertNull(unknown.copy(rawOutput = "Tethering:\n  Tether state:").tetheredDownstreams())
    }

    private fun report(state: String) = UpstreamObservation(
        rawOutput = "Tethering:\n  Tether state:\n    $state\n    Upstream wanted: false\n" +
            "    Current upstream interface(s): [testtun1]\n  Hardware offload:",
        interfaceNames = listOf("testtun1"),
        didTimeout = false,
    )
}
