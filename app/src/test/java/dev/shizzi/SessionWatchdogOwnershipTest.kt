package dev.shizzi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionWatchdogOwnershipTest {

    @Test
    fun newerTunSupersedesOlderWatchdog() {
        assertEquals(
            "testtun28",
            newerShizziInterface(
                expectedInterface = "testtun27",
                observedInterfaces = listOf("wlan0", "testtun28"),
            ),
        )
    }

    @Test
    fun olderOrSameTunDoesNotSupersedeCurrentWatchdog() {
        assertNull(
            newerShizziInterface(
                expectedInterface = "testtun28",
                observedInterfaces = listOf("wlan0", "testtun27", "testtun28"),
            ),
        )
    }

    @Test
    fun unrelatedInterfacesDoNotSupersedeCurrentWatchdog() {
        assertNull(
            newerShizziInterface(
                expectedInterface = "testtun28",
                observedInterfaces = listOf("wlan0", "rmnet_data0"),
            ),
        )
    }
}
