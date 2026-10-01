package dev.shizzi

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class ClientAppDistributionInfo(
    val available: Boolean,
    val version: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
)

/**
 * Loopback-only bridge used by the privileged datapath portal.
 *
 * The captive portal itself runs in the Shizuku/shell process, which cannot
 * read this app's private assets directly. This tiny server stays bound to
 * 127.0.0.1 so only local processes can fetch the embedded Shizzi+ APK. The
 * Go captive portal proxies that response to the hotspot client.
 */
class ClientAppDistributionServer(private val context: Context) {
    private val running = AtomicBoolean(false)
    private val acceptExecutor = Executors.newSingleThreadExecutor()
    private val clientExecutor = Executors.newFixedThreadPool(2)
    private var serverSocket: ServerSocket? = null

    val info: ClientAppDistributionInfo by lazy {
        runCatching { inspectAsset() }.getOrElse {
            ClientAppDistributionInfo(
                available = false,
                version = CLIENT_VERSION,
                fileName = CLIENT_FILE_NAME,
                sizeBytes = 0L,
                sha256 = "",
            )
        }
    }

    fun start(): Boolean {
        if (!info.available) {
            SessionLog.warn("Shizzi+ distribution asset unavailable")
            return false
        }
        if (!running.compareAndSet(false, true)) return true

        val socket = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(
                    InetSocketAddress(
                        InetAddress.getLoopbackAddress(),
                        PORT,
                    ),
                )
            }
        }.getOrElse { failure ->
            running.set(false)
            SessionLog.warn(
                "Shizzi+ distribution server failed: " +
                    "${failure.javaClass.simpleName}: ${failure.message}",
            )
            return false
        }

        serverSocket = socket
        acceptExecutor.execute {
            while (running.get()) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                clientExecutor.execute { handle(client) }
            }
        }
        SessionLog.info(
            "Shizzi+ distribution ready: ${info.fileName} " +
                "(${info.sizeBytes} bytes, sha256=${info.sha256})",
        )
        return true
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptExecutor.shutdownNow()
        clientExecutor.shutdownNow()
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 10_000
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0).orEmpty()
            val path = parts.getOrNull(1).orEmpty().substringBefore('?')

            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
            }

            if ((method != "GET" && method != "HEAD") || path != "/shizzi-plus.apk") {
                writeText(output, "404 Not Found", "Introuvable")
                return
            }

            val metadata = info
            if (!metadata.available) {
                writeText(output, "503 Service Unavailable", "Shizzi+ indisponible")
                return
            }

            val header = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: application/vnd.android.package-archive\r\n")
                append("Content-Disposition: attachment; filename=\"")
                    .append(metadata.fileName)
                    .append("\"\r\n")
                append("Content-Length: ").append(metadata.sizeBytes).append("\r\n")
                append("X-Shizzi-Version: ").append(metadata.version).append("\r\n")
                append("X-Shizzi-SHA256: ").append(metadata.sha256).append("\r\n")
                append("Cache-Control: no-store\r\n")
                append("Connection: close\r\n\r\n")
            }
            output.write(header.toByteArray(Charsets.UTF_8))

            if (method == "GET") {
                context.assets.open(ASSET_PATH).use { asset ->
                    asset.copyTo(output, DEFAULT_BUFFER_SIZE)
                }
            }
            output.flush()
        }
    }

    private fun inspectAsset(): ClientAppDistributionInfo {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        context.assets.open(ASSET_PATH).use { asset ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = asset.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer, 0, read)
                size += read
            }
        }
        return ClientAppDistributionInfo(
            available = size > 0,
            version = CLIENT_VERSION,
            fileName = CLIENT_FILE_NAME,
            sizeBytes = size,
            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
        )
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>(128)
        while (true) {
            val value = input.read()
            if (value < 0) {
                return if (bytes.isEmpty()) null else bytes.toByteArray().toString(Charsets.UTF_8)
            }
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes += value.toByte()
            if (bytes.size > 16_384) return null
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }

    private fun writeText(
        output: BufferedOutputStream,
        status: String,
        text: String,
    ) {
        val body = text.toByteArray(Charsets.UTF_8)
        output.write(
            (
                "HTTP/1.1 $status\r\n" +
                    "Content-Type: text/plain; charset=utf-8\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Connection: close\r\n\r\n"
            ).toByteArray(Charsets.UTF_8),
        )
        output.write(body)
        output.flush()
    }

    companion object {
        const val PORT = 8091
        const val CLIENT_VERSION = "0.3.0"
        const val CLIENT_FILE_NAME = "Shizzi-Plus-0.3.0.apk"
        const val ASSET_PATH = "shizzi/Shizzi-Plus.apk"
    }
}
