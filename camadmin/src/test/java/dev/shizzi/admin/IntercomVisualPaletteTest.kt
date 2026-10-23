package dev.shizzi.admin

import org.junit.Assert.*
import org.junit.Test

class IntercomVisualPaletteTest {
    @Test fun greenOnlyWhenRouterAcceptedAudioPackets() {
        val active=IntercomVisualPalette.model(IntercomVisualMode.TRANSMITTING)
        assertEquals("Couper mon micro",active.buttonLabel)
        for(mode in listOf(IntercomVisualMode.OFF,IntercomVisualMode.CONNECTING,IntercomVisualMode.ERROR)) {
            assertNotEquals(active.buttonColor,IntercomVisualPalette.model(mode).buttonColor)
        }
    }
    @Test fun accessibleStatesAreNotColourOnly() {
        assertEquals("Annuler",IntercomVisualPalette.model(IntercomVisualMode.CONNECTING).buttonLabel)
        assertEquals("Parler",IntercomVisualPalette.model(IntercomVisualMode.OFF).buttonLabel)
        assertEquals("Réessayer",IntercomVisualPalette.model(IntercomVisualMode.ERROR).buttonLabel)
        assertTrue(IntercomVisualPalette.model(IntercomVisualMode.TRANSMITTING).description.contains("transmission"))
    }
}
