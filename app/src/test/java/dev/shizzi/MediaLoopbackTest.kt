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
 

    @Test
    fun healthProbeReachesVirtualAndroidRouterBackend() {
        val bindAddress = InetAddress.getByName(MediaNetwork.LOOPBACK_HOST)
        ServerSocket().use { server ->
            server.reuseAddress = true
            server.bind(InetSocketAddress(bindAddress, 0))

            val worker = thread(name = "virtual-android-router-media") {
                server.accept().use { peer ->
                    val reader = peer.getInputStream().bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val body = "ok"
                    val response =
                        "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/plain\r\n" +
                            "Content-Length: ${body.length}\r\n" +
                            "Connection: close\r\n\r\n" +
                            body
                    peer.getOutputStream().write(response.toByteArray())
                    peer.getOutputStream().flush()
                }
            }

            assertTrue(
                "virtual Android router Media backend must answer /health",
                MediaNetwork.backendReachable(
                    host = MediaNetwork.LOOPBACK_HOST,
                    port = server.localPort,
                    timeoutMillis = 1_000,
                ),
            )
            worker.join(1_000)
            assertTrue("virtual Android router worker did not finish", !worker.isAlive)
        }
    }

    @Test
    fun healthProbeRejectsStoppedVirtualAndroidRouterBackend() {
        val port = ServerSocket(0).use { it.localPort }
        assertTrue(
            "stopped Media backend must be reported unavailable",
            !MediaNetwork.backendReachable(
                host = MediaNetwork.LOOPBACK_HOST,
                port = port,
                timeoutMillis = 200,
            ),
        )
    }
}
