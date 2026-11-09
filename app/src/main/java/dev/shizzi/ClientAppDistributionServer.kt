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
class ClientAppDistributionServer(
    private val apkBytes: ByteArray,
    private val adminApkBytes: ByteArray,
) {
    private val running = AtomicBoolean(false)
    private val acceptExecutor = Executors.newSingleThreadExecutor()
    private val clientExecutor = Executors.newFixedThreadPool(2)
    private var serverSocket: ServerSocket? = null

    val info: ClientAppDistributionInfo by lazy {
        inspectClientAppBytes(apkBytes)
    }

    val adminInfo: ClientAppDistributionInfo by lazy {
        inspectEmbeddedAppBytes(
            adminApkBytes,
            ADMIN_VERSION,
            ADMIN_FILE_NAME,
        )
    }

    fun start(): Boolean {
        if (!info.available || !adminInfo.available) {
            SessionLog.warn(
                "Embedded app distribution asset unavailable: " +
                    "plus=${info.available}, admin=${adminInfo.available}",
            )
            return false
        }
        if (!running.compareAndSet(false, true)) return true

        val socket = runCatching {
            val candidate = ServerSocket()
            try {
                candidate.reuseAddress = true
                candidate.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), PORT))
                candidate
            } catch (failure: Exception) {
                runCatching { candidate.close() }
                throw failure
            }
        }.getOrElse { failure ->
            running.set(false)
            if (failure is java.net.BindException && compatibleDistributionIsAlive()) {
                SessionLog.info("compatible Shizzi+ distribution already serving on 127.0.0.1:$PORT")
                return true
            }
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
            "Embedded app distribution ready: " +
                "${info.fileName} (${info.sizeBytes} bytes), " +
                "${adminInfo.fileName} (${adminInfo.sizeBytes} bytes)",
        )
        return true
    }

    // Another shell process may still own 8091 after Shizuku rebinds.
    // Reuse only if BOTH embedded APKs match by hash. Never accept an
    // unrelated listener, or one serving stale client/admin binaries.
    private fun compatibleDistributionIsAlive(): Boolean =
        servesSameApk("/shizzi-plus.apk", info.sha256) &&
            servesSameApk("/shizzi-admin.apk", adminInfo.sha256)

    private fun servesSameApk(path: String, expectedSha: String): Boolean = runCatching {
        Socket().use { peer ->
            peer.connect(InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), PORT), 1_200)
            peer.soTimeout = 1_200
            peer.getOutputStream().apply {
                write("HEAD $path HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                flush()
            }
            peer.getInputStream().bufferedReader().use { input ->
                val status = input.readLine().orEmpty()
                var actualSha = ""
                while (true) {
                    val header = input.readLine() ?: break
                    if (header.isEmpty()) break
                    if (header.startsWith("X-Shizzi-SHA256:", ignoreCase = true)) {
                        actualSha = header.substringAfter(':').trim()
                    }
                }
                status.startsWith("HTTP/1.1 200 ") &&
                    expectedSha.isNotBlank() &&
                    actualSha.equals(expectedSha, ignoreCase = true)
            }
        }
    }.getOrDefault(false)

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

            val payload = when (path.lowercase()) {
                "/shizzi-plus.apk" -> apkBytes to info
                "/shizzi-admin.apk" -> adminApkBytes to adminInfo
                else -> null
            }
            if ((method != "GET" && method != "HEAD") || payload == null) {
                writeText(output, "404 Not Found", "Introuvable")
                return
            }

            val (selectedBytes, metadata) = payload
            if (!metadata.available) {
                writeText(output, "503 Service Unavailable", "Application Shizzi indisponible")
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
                val length = requestedRange?.byteLength() ?: selectedBytes.size.toLong()
                output.write(
                    selectedBytes,
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
        const val CLIENT_VERSION = "0.3.0-background-calls"
        const val CLIENT_FILE_NAME = "Shizzi-Plus-0.3.0-background-calls.apk"
        const val ASSET_PATH = "shizzi/Shizzi-Plus.apk"
        const val ADMIN_VERSION = "0.3.7-data-extension"
        const val ADMIN_FILE_NAME = "Shizzi-Admin-0.3.7-data-extension.apk"
        const val ADMIN_ASSET_PATH = "shizzi/Shizzi-Admin.apk"
    }
}


internal fun readClientAppAsset(context: Context): ByteArray =
    context.assets.open(ClientAppDistributionServer.ASSET_PATH).use { it.readBytes() }

internal fun readEmbeddedAppAssetFromInstalledShizzi(
    context: Context,
    assetPath: String,
    displayName: String,
): ByteArray {
    val applicationInfo = context.packageManager.getApplicationInfo(
        BuildConfig.APPLICATION_ID,
        0,
    )
    ZipFile(applicationInfo.sourceDir).use { zip ->
        val entryName = "assets/" + assetPath
        val entry = zip.getEntry(entryName)
            ?: error("Embedded $displayName missing from installed Shizzi APK: $entryName")
        return zip.getInputStream(entry).use { it.readBytes() }
    }
}

internal fun readClientAppAssetFromInstalledShizzi(context: Context): ByteArray =
    readEmbeddedAppAssetFromInstalledShizzi(
        context,
        ClientAppDistributionServer.ASSET_PATH,
        "Shizzi+",
    )

internal fun readAdminAppAssetFromInstalledShizzi(context: Context): ByteArray =
    readEmbeddedAppAssetFromInstalledShizzi(
        context,
        ClientAppDistributionServer.ADMIN_ASSET_PATH,
        "Shizzi Admin",
    )

internal fun inspectClientAppAsset(context: Context): ClientAppDistributionInfo =
    inspectClientAppBytes(readClientAppAsset(context))

internal fun inspectClientAppBytes(bytes: ByteArray): ClientAppDistributionInfo =
    inspectEmbeddedAppBytes(
        bytes,
        ClientAppDistributionServer.CLIENT_VERSION,
        ClientAppDistributionServer.CLIENT_FILE_NAME,
    )

internal fun inspectEmbeddedAppBytes(
    bytes: ByteArray,
    version: String,
    fileName: String,
): ClientAppDistributionInfo {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return ClientAppDistributionInfo(
        available = bytes.isNotEmpty(),
        version = version,
        fileName = fileName,
        sizeBytes = bytes.size.toLong(),
        sha256 = digest.joinToString("") { "%02x".format(it) },
    )
}
