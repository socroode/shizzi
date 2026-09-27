package dev.shizzi

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Pre-authentication identification channel reachable directly on the hotspot LAN.
 *
 * Traffic sent to the hotspot gateway itself does not enter the shared Shizzi TUN,
 * so DatagramPacket.address is the real downstream client address (for example
 * 192.168.7.66), before Android rewrites forwarded Internet traffic to 192.0.2.2.
 */
class PortalDeviceRegistrationServer(
    private val onRegistration: (sourceIp: String, token: String) -> Boolean,
) {
    @Volatile
    private var running = false

    private var socket: DatagramSocket? = null
    private var listenerThread: Thread? = null
    private var workers: ExecutorService? = null

    @Synchronized
    fun start() {
        if (running) return

        val opened = DatagramSocket(null).apply {
            reuseAddress = true
            bind(
                InetSocketAddress(
                    InetAddress.getByName("0.0.0.0"),
                    PORT,
                ),
            )
        }

        socket = opened
        workers = Executors.newFixedThreadPool(MAX_PARALLEL_REGISTRATIONS)
        running = true

        listenerThread = Thread(
            {
                receiveLoop(opened)
            },
            "shizzi-device-registration",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun receiveLoop(opened: DatagramSocket) {
        while (running) {
            val buffer = ByteArray(MAX_PACKET_BYTES)
            val packet = DatagramPacket(buffer, buffer.size)

            try {
                opened.receive(packet)
            } catch (_: SocketException) {
                if (!running) return
                continue
            } catch (failure: Throwable) {
                SessionLog.warn(
                    "device identification receive failed: ${failure.message}",
                )
                continue
            }

            val sourceIp = packet.address?.hostAddress
                ?.substringBefore('%')
                .orEmpty()
            val sourceAddress = packet.address ?: continue
            val sourcePort = packet.port
            val token = String(
                packet.data,
                packet.offset,
                packet.length,
                StandardCharsets.UTF_8,
            ).trim()

            workers?.execute {
                val accepted = runCatching {
                    token.matches(TOKEN_PATTERN) &&
                        onRegistration(sourceIp, token)
                }.getOrElse { failure ->
                    SessionLog.warn(
                        "device identification failed for $sourceIp: ${failure.message}",
                    )
                    false
                }

                val response = if (accepted) OK else ERROR
                val responseBytes = response.toByteArray(StandardCharsets.UTF_8)
                runCatching {
                    opened.send(
                        DatagramPacket(
                            responseBytes,
                            responseBytes.size,
                            sourceAddress,
                            sourcePort,
                        ),
                    )
                }.onFailure { failure ->
                    if (running) {
                        SessionLog.warn(
                            "device identification reply failed for $sourceIp: " +
                                failure.message,
                        )
                    }
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        if (!running && socket == null) return

        running = false
        socket?.close()
        socket = null

        listenerThread?.interrupt()
        listenerThread = null

        workers?.shutdownNow()
        workers = null
    }

    companion object {
        const val PORT = 49_200

        private const val MAX_PACKET_BYTES = 256
        private const val MAX_PARALLEL_REGISTRATIONS = 4

        const val OK = "SHIZZI-DEVICE-OK"
        const val ERROR = "SHIZZI-DEVICE-ERR"

        private val TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]{24,128}$")
    }
}
