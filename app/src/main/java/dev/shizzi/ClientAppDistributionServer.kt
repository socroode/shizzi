package dev.shizzi

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.zip.ZipFile
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
 * The bridge is hosted inside the Shizuku/shell user-service process, beside
 * the Go captive portal. It opens the Shizzi+ APK embedded in the installed
 * Shizzi package through a package Context, then streams it on 127.0.0.1.
 * This avoids depending on the ordinary Android app process staying reachable.
 */
class ClientAppDistributionServer(private val apkBytes: ByteArray) {
    private val running = AtomicBoolean(false)
    private val acceptExecutor = Executors.newSingleThreadExecutor()
    private val clientExecutor = Executors.newFixedThreadPool(2)
    private var serverSocket: ServerSocket? = null

    val info: ClientAppDistributionInfo by lazy {
        inspectClientAppBytes(apkBytes)
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
                        InetAddress.getByName(LOOPBACK_HOST),
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

            val headers = linkedMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val split = line.indexOf(':')
                if (split > 0) {
                    headers[line.substring(0, split).trim().lowercase()] =
                        line.substring(split + 1).trim()
                }
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

            val rangeHeader = headers["range"]
            val requestedRange = parseClientDownloadRange(rangeHeader, metadata.sizeBytes)
            if (!rangeHeader.isNullOrBlank() && requestedRange == null) {
                val header = buildString {
                    append("HTTP/1.1 416 Range Not Satisfiable\r\n")
                    append("Content-Range: bytes */").append(metadata.sizeBytes).append("\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    append("Content-Length: 0\r\n")
                    append("Cache-Control: no-store\r\n")
                    append("Connection: close\r\n\r\n")
                }
                output.write(header.toByteArray(Charsets.UTF_8))
                output.flush()
                return
            }

            val contentLength = requestedRange?.byteLength() ?: metadata.sizeBytes
            val header = buildString {
                if (requestedRange != null) {
                    append("HTTP/1.1 206 Partial Content\r\n")
                } else {
                    append("HTTP/1.1 200 OK\r\n")
                }
                append("Content-Type: application/vnd.android.package-archive\r\n")
                append("Content-Disposition: attachment; filename=\"")
                    .append(metadata.fileName)
                    .append("\"\r\n")
                append("Content-Length: ").append(contentLength).append("\r\n")
                append("Accept-Ranges: bytes\r\n")
                if (requestedRange != null) {
                    append("Content-Range: bytes ")
                        .append(requestedRange.first)
                        .append("-")
                        .append(requestedRange.last)
                        .append("/")
                        .append(metadata.sizeBytes)
                        .append("\r\n")
                }
                append("X-Content-Type-Options: nosniff\r\n")
                append("X-Shizzi-Version: ").append(metadata.version).append("\r\n")
                append("X-Shizzi-SHA256: ").append(metadata.sha256).append("\r\n")
                append("Cache-Control: no-store\r\n")
                append("Connection: close\r\n\r\n")
            }
            output.write(header.toByteArray(Charsets.UTF_8))

            if (method == "GET") {
                val first = requestedRange?.first ?: 0L
                val length = requestedRange?.byteLength() ?: apkBytes.size.toLong()
                output.write(
                    apkBytes,
                    first.toInt(),
                    length.toInt(),
                )
            }
            output.flush()
        }
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
        const val LOOPBACK_HOST = "127.0.0.1"
        const val PORT = 8091
        const val CLIENT_VERSION = "0.2.10-test"
        const val CLIENT_FILE_NAME = "Shizzi-Plus-0.2.10-test.apk"
        const val ASSET_PATH = "shizzi/Shizzi-Plus.apk"
    }
}


internal fun readClientAppAsset(context: Context): ByteArray =
    context.assets.open(ClientAppDistributionServer.ASSET_PATH).use { it.readBytes() }

internal fun readClientAppAssetFromInstalledShizzi(context: Context): ByteArray {
    val applicationInfo = context.packageManager.getApplicationInfo(
        BuildConfig.APPLICATION_ID,
        0,
    )
    ZipFile(applicationInfo.sourceDir).use { zip ->
        val entryName = "assets/" + ClientAppDistributionServer.ASSET_PATH
        val entry = zip.getEntry(entryName)
            ?: error("Embedded Shizzi+ missing from installed Shizzi APK: $entryName")
        return zip.getInputStream(entry).use { it.readBytes() }
    }
}

internal fun inspectClientAppAsset(context: Context): ClientAppDistributionInfo =
    inspectClientAppBytes(readClientAppAsset(context))

internal fun inspectClientAppBytes(bytes: ByteArray): ClientAppDistributionInfo {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return ClientAppDistributionInfo(
        available = bytes.isNotEmpty(),
        version = ClientAppDistributionServer.CLIENT_VERSION,
        fileName = ClientAppDistributionServer.CLIENT_FILE_NAME,
        sizeBytes = bytes.size.toLong(),
        sha256 = digest.joinToString("") { "%02x".format(it) },
    )
}
