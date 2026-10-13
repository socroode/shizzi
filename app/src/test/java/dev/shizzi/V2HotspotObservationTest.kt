package dev.shizzi

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V2HotspotObservationTest {
    private val wifiTwo = """
        Wi-Fi service:
        getConnectedClientList().size(): 2
        getConnectedClientList(): [WifiClient{mMacAddress=02:11:22:33:44:55, mApInstanceIdentifier=ap0}, WifiClient{mMacAddress=02:66:77:88:99:aa, mApInstanceIdentifier=ap0}]
    """.trimIndent()

    private val tether = """
        IPv4 Upstream:
        tcp [00:00:00:00:00:00] 41(41) 192.168.247.161:12345 -> 42(testtun0) 192.0.2.2:12345 -> 8.8.8.8:443
        Client Information:
        TetheredClient {hwAddr 02:11:22:33:44:55, addresses [AddressInfo {/192.168.247.161/24}], tetheringType 0}
        TetheredClient {hwAddr 02:66:77:88:99:aa, addresses [AddressInfo {/192.168.247.162/24}], tetheringType 0}
        TetheredClient {hwAddr 02:de:ad:be:ef:33, addresses [AddressInfo {/192.168.247.163/24}], tetheringType 0}
        ap0 - TetheredState - [...]
    """.trimIndent()

    @Test fun twoWifiStationsGetSeparatePhysicalAddresses() {
        val associated = V2HotspotObservation.associatedMacs(wifiTwo)
        val leases = V2HotspotObservation.physicalLeases(tether)
        assertEquals(setOf("02:11:22:33:44:55", "02:66:77:88:99:aa"), associated)
        assertEquals("192.168.247.161", leases?.get("02:11:22:33:44:55"))
        assertEquals("192.168.247.162", leases?.get("02:66:77:88:99:aa"))
        // Stale lease of a departed third station does NOT mean Wi-Fi presence.
        assertTrue("02:de:ad:be:ef:33" !in (associated ?: emptySet()))
    }

    @Test fun clientCountAloneNeverAuthenticatesAnyone() {
        assertNull(V2HotspotObservation.associatedMacs("getConnectedClientList().size(): 2"))
        assertNull(V2HotspotObservation.associatedMacs("Previously connected: 02:11:22:33:44:55"))
        assertNull(V2HotspotObservation.physicalLeases("IPv4 Upstream: 192.168.247.161:443"))
    }

    @Test fun explicitEmptyCurrentListProvesDeparture() {
        assertEquals(emptySet<String>(), V2HotspotObservation.associatedMacs("getConnectedClientList(): []"))
        assertNull(V2HotspotObservation.associatedMacs("Connected clients: [???]"))
    }

    @Test fun gatewayMustBePrivateOnExactlyOneTetheredWifiInterface() {
        val ip = InetAddress.getByName("192.168.247.1")
        val answer = V2HotspotObservation.gatewayAddress(tether) { iface ->
            if (iface == "ap0") listOf(ip) else emptyList()
        }
        assertEquals("192.168.247.1", answer)
        assertNull(V2HotspotObservation.gatewayAddress(tether.replace("ap0 - TetheredState", "rmnet0 - TetheredState")) {
            listOf(ip)
        })
        assertNull(V2HotspotObservation.gatewayAddress("ap0 - TetheredState - []\nwlan2 - TetheredState - []") {
            listOf(ip)
        })
    }

    @Test fun conflictingDhcpMacAssignmentsAreNotTrusted() {
        val malformed = tether + "\nTetheredClient {hwAddr 02:11:22:33:44:55, addresses [AddressInfo {/192.168.247.200/24}], tetheringType 0}"
        val leases = V2HotspotObservation.physicalLeases(malformed)
        assertEquals(null, leases?.get("02:11:22:33:44:55"))
        assertEquals("192.168.247.162", leases?.get("02:66:77:88:99:aa"))
    }
}
