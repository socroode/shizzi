package dev.shizzi

import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaLoopbackTest {

    @Test
    fun backendIpv4LoopbackIsReachableByPortalProxyAddress() {
        val bindAddress = InetAddress.getByName(MediaNetwork.LOOPBACK_HOST)
        assertTrue("Media backend must bind an IPv4 loopback address", bindAddress is Inet4Address)
        assertEquals("127.0.0.1", bindAddress.hostAddress)

        ServerSocket().use { server ->
            server.reuseAddress = true
            server.bind(InetSocketAddress(bindAddress, 0))

            val worker = thread(name = "virtual-media-router") {
                server.accept().use { peer ->
                    peer.getOutputStream().write("ok".toByteArray())
                    peer.getOutputStream().flush()
                }
            }

            Socket().use { client ->
                client.connect(
                    InetSocketAddress(MediaNetwork.LOOPBACK_HOST, server.localPort),
                    1_000,
                )
                val response = client.getInputStream().readBytes().toString(Charsets.UTF_8)
                assertEquals("ok", response)
            }

            worker.join(1_000)
            assertTrue("virtual router worker did not finish", !worker.isAlive)
        }
    }
}
