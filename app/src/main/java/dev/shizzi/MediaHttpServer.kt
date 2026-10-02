package dev.shizzi

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class MediaHttpServer(private val context: Context) {
    private val running = AtomicBoolean(false)
    private val acceptExecutor = Executors.newSingleThreadExecutor()
    private val clientPool = Executors.newFixedThreadPool(MAX_CLIENTS)
    private var serverSocket: ServerSocket? = null

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return isListening()

        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(
                    InetSocketAddress(
                        InetAddress.getByName(MediaNetwork.LOOPBACK_HOST),
                        MediaNetwork.PORT,
                    ),
                )
            }
        } catch (failure: IOException) {
            running.set(false)
            Log.e(TAG, "media server failed to bind port ${MediaNetwork.PORT}", failure)
            return false
        }

        serverSocket = socket
        acceptExecutor.execute { acceptLoop(socket) }
        Log.i(TAG, "media server listening on ${MediaNetwork.LOOPBACK_HOST}:${MediaNetwork.PORT}")
        return true
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptExecutor.shutdownNow()
        clientPool.shutdownNow()
        Log.i(TAG, "media server stopped")
    }

    fun isListening(): Boolean =
        running.get() && serverSocket?.let { it.isBound && !it.isClosed } == true

    private fun acceptLoop(server: ServerSocket) {
        try {
            while (running.get()) {
                val client = try {
                    server.accept()
                } catch (failure: IOException) {
                    if (running.get()) Log.w(TAG, "media accept failed", failure)
                    break
                }

                try {
                    clientPool.execute { handleSafely(client) }
                } catch (_: RejectedExecutionException) {
                    runCatching { client.close() }
                    if (running.get()) Log.w(TAG, "media client rejected: worker pool unavailable")
                }
            }
        } finally {
            running.set(false)
            runCatching { server.close() }
            if (serverSocket === server) serverSocket = null
        }
    }

    private fun handleSafely(socket: Socket) {
        val remote = socket.inetAddress?.hostAddress.orEmpty()
        try {
            Log.i(TAG, "media client connected: $remote")
            handle(socket)
            Log.i(TAG, "media client disconnected: $remote")
        } catch (failure: Exception) {
            if (isNormalDisconnect(failure)) {
                Log.i(TAG, "media client disconnected early: $remote (${failure.javaClass.simpleName})")
            } else {
                Log.w(TAG, "media client error from $remote", failure)
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun isNormalDisconnect(failure: Exception): Boolean {
        if (failure is SocketException) return true
        val message = failure.message.orEmpty().lowercase(Locale.US)
        return message.contains("broken pipe") ||
            message.contains("connection reset") ||
            message.contains("socket closed")
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 15_000
            if (!isAllowedRemote(client.inetAddress)) {
                writeSimple(client, 403, "Forbidden", "Accès local uniquement")
                return
            }

            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return

            val method = parts[0].uppercase(Locale.US)
            val target = parts[1]
            val headers = linkedMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val split = line.indexOf(':')
                if (split > 0) {
                    headers[line.substring(0, split).trim().lowercase(Locale.US)] =
                        line.substring(split + 1).trim()
                }
            }

            if (method != "GET" && method != "HEAD") {
                writeText(output, 405, "Method Not Allowed", "text/plain; charset=utf-8", "GET/HEAD uniquement", method == "HEAD")
                return
            }

            val uri = target.substringBefore('?')
            val query = parseQuery(target.substringAfter('?', ""))
            val accountNumber = normalizeMediaAccount(headers["x-shizzi-media-account"])
            when (uri) {
                "/", "/index.html" -> serveHome(output, accountNumber, method == "HEAD")
                "/library" -> serveLibrary(
                    output,
                    query["folder"],
                    query["kind"],
                    accountNumber,
                    method == "HEAD",
                )
                "/play" -> servePlayer(output, query["id"], accountNumber, method == "HEAD")
                "/stream" -> serveStream(
                    output,
                    query["id"],
                    headers["range"],
                    accountNumber,
                    method == "HEAD",
                )
                "/health" -> writeText(output, 200, "OK", "text/plain; charset=utf-8", "ok", method == "HEAD")
                else -> writeText(output, 404, "Not Found", "text/plain; charset=utf-8", "Introuvable", method == "HEAD")
            }
        }
    }

    private fun serveHome(
        output: BufferedOutputStream,
        accountNumber: String,
        headOnly: Boolean,
    ) {
        val folders = MediaFolderStore.visibleTo(context, accountNumber)
            .filter { it.uri() != null }
        val cards = if (folders.isEmpty()) {
            "<p>Aucun dossier Media n'est autorisé pour ce compte.</p>"
        } else {
            folders.joinToString("") { folder ->
                "<a class=\"card\" href=\"library?folder=${folder.id}\"><strong>${escape(folder.name)}</strong><span>Ouvrir</span></a>"
            }
        }
        val html = page(
            "Shizzi Media",
            """
            <h1>Shizzi Media</h1>
            <p class="lead">Contenus disponibles pour le compte ${escape(accountNumber.ifBlank { "local" })}.</p>
            <div class="grid">$cards</div>
            <p class="hint">Seuls les dossiers autorisés pour ce compte sont affichés.</p>
            """.trimIndent(),
        )
        writeText(output, 200, "OK", "text/html; charset=utf-8", html, headOnly)
    }

    private fun serveLibrary(
        output: BufferedOutputStream,
        folderId: String?,
        rawKind: String?,
        accountNumber: String,
        headOnly: Boolean,
    ) {
        val folder = folderId
            ?.let { MediaFolderStore.byId(context, it) }
            ?: MediaKind.fromKey(rawKind)?.let { kind ->
                MediaFolderStore.visibleTo(context, accountNumber).firstOrNull { it.kind == kind }
            }

        if (folder == null || !folder.visibleTo(accountNumber)) {
            writeText(output, 404, "Not Found", "text/plain; charset=utf-8", "Dossier introuvable", headOnly)
            return
        }

        val entries = MediaIndex.entriesForFolder(context, folder.id)
        val rows = if (entries.isEmpty()) {
            "<p>Aucun fichier trouvé dans ${escape(folder.name)}.</p>"
        } else {
            entries.joinToString("") { entry ->
                val size = if (entry.size > 0) humanBytes(entry.size) else "taille inconnue"
                """
                <a class="item" href="play?id=${entry.id}">
                  <strong>${escape(entry.name)}</strong>
                  <span>${escape(entry.relativePath)} · $size</span>
                </a>
                """.trimIndent()
            }
        }
        val html = page(
            folder.name,
            """
            <a class="back" href="./">← Shizzi Media</a>
            <h1>${escape(folder.name)}</h1>
            <div class="list">$rows</div>
            """.trimIndent(),
        )
        writeText(output, 200, "OK", "text/html; charset=utf-8", html, headOnly)
    }

    private fun servePlayer(
        output: BufferedOutputStream,
        id: String?,
        accountNumber: String,
        headOnly: Boolean,
    ) {
        val entry = MediaIndex.find(context, id)
        if (entry == null || !entryAllowed(entry, accountNumber)) {
            writeText(output, 404, "Not Found", "text/plain; charset=utf-8", "Fichier introuvable", headOnly)
            return
        }
        val mediaTag = if (entry.kind == MediaKind.MUSIC) {
            "<audio controls autoplay preload=\"metadata\" src=\"stream?id=${entry.id}\"></audio>"
        } else {
            "<video controls autoplay playsinline preload=\"metadata\" src=\"stream?id=${entry.id}\"></video>"
        }
        val html = page(
            entry.name,
            """
            <a class="back" href="library?folder=${entry.folderId}">← ${escape(entry.folderName.ifBlank { entry.kind.label })}</a>
            <h1>${escape(entry.name)}</h1>
            <p>${escape(entry.relativePath)}</p>
            <div class="player">$mediaTag</div>
            <p class="hint">Lecture locale via le hotspot Shizzi.</p>
            """.trimIndent(),
        )
        writeText(output, 200, "OK", "text/html; charset=utf-8", html, headOnly)
    }

    private fun serveStream(
        output: BufferedOutputStream,
        id: String?,
        rangeHeader: String?,
        accountNumber: String,
        headOnly: Boolean,
    ) {
        val entry = MediaIndex.find(context, id)
        if (entry == null || entry.size <= 0L || !entryAllowed(entry, accountNumber)) {
            writeText(output, 404, "Not Found", "text/plain; charset=utf-8", "Fichier introuvable", headOnly)
            return
        }

        val size = entry.size
        val range = parseRange(rangeHeader, size)
        if (rangeHeader != null && range == null) {
            output.write(
                ("HTTP/1.1 416 Range Not Satisfiable\r\n" +
                    "Content-Range: bytes */$size\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(),
            )
            output.flush()
            return
        }

        val start = range?.first ?: 0L
        val end = range?.second ?: (size - 1L)
        val contentLength = end - start + 1L
        val statusLine = if (range != null) "HTTP/1.1 206 Partial Content" else "HTTP/1.1 200 OK"

        val header = buildString {
            append(statusLine).append("\r\n")
            append("Content-Type: ").append(entry.mimeType).append("\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Content-Length: ").append(contentLength).append("\r\n")
            if (range != null) append("Content-Range: bytes $start-$end/$size\r\n")
            append("Cache-Control: private, max-age=3600\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray())
        if (headOnly) {
            output.flush()
            return
        }

        val pfd = context.contentResolver.openFileDescriptor(entry.uri, "r")
            ?: throw IOException("Cannot open ${entry.uri}")
        pfd.use {
            FileInputStream(it.fileDescriptor).use { stream ->
                val channel = stream.channel
                if (start > 0L) {
                    runCatching { channel.position(start) }.getOrElse {
                        var remaining = start
                        while (remaining > 0) {
                            val skipped = stream.skip(remaining)
                            if (skipped <= 0) break
                            remaining -= skipped
                        }
                    }
                }

                val buffer = ByteArray(64 * 1024)
                var remaining = contentLength
                while (remaining > 0 && running.get()) {
                    val read = stream.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    remaining -= read
                }
                output.flush()
            }
        }
    }

    private fun entryAllowed(entry: MediaEntry, accountNumber: String): Boolean {
        if (entry.folderId.isBlank()) return true
        return MediaFolderStore.canAccess(context, entry.folderId, accountNumber)
    }

    private fun parseRange(header: String?, size: Long): Pair<Long, Long>? {
        if (header.isNullOrBlank()) return null
        if (!header.startsWith("bytes=")) return null
        val value = header.removePrefix("bytes=").substringBefore(',')
        val pieces = value.split('-', limit = 2)
        if (pieces.size != 2) return null

        val startText = pieces[0].trim()
        val endText = pieces[1].trim()
        val start: Long
        val end: Long

        if (startText.isBlank()) {
            val suffix = endText.toLongOrNull()?.takeIf { it > 0 } ?: return null
            start = (size - suffix).coerceAtLeast(0L)
            end = size - 1L
        } else {
            start = startText.toLongOrNull() ?: return null
            end = endText.toLongOrNull() ?: (size - 1L)
        }

        if (start < 0 || start >= size || end < start) return null
        return start to end.coerceAtMost(size - 1L)
    }

    private fun isAllowedRemote(address: InetAddress): Boolean =
        address.isLoopbackAddress

    private fun parseQuery(query: String): Map<String, String> =
        query.split('&')
            .mapNotNull { pair ->
                if (pair.isBlank()) return@mapNotNull null
                val index = pair.indexOf('=')
                val key = if (index >= 0) pair.substring(0, index) else pair
                val value = if (index >= 0) pair.substring(index + 1) else ""
                decode(key) to decode(value)
            }
            .toMap()

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>(128)
        while (true) {
            val value = input.read()
            if (value < 0) return if (bytes.isEmpty()) null else bytes.toByteArray().toString(Charsets.UTF_8)
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes += value.toByte()
            if (bytes.size > 16_384) throw IOException("HTTP line too long")
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }

    private fun writeSimple(socket: Socket, code: Int, reason: String, text: String) {
        val output = BufferedOutputStream(socket.getOutputStream())
        writeText(output, code, reason, "text/plain; charset=utf-8", text, false)
    }

    private fun writeText(
        output: BufferedOutputStream,
        code: Int,
        reason: String,
        contentType: String,
        body: String,
        headOnly: Boolean,
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        output.write(header.toByteArray())
        if (!headOnly) output.write(bytes)
        output.flush()
    }

    private fun page(title: String, body: String): String = """
        <!doctype html>
        <html lang="fr">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width,initial-scale=1">
          <title>${escape(title)} · Shizzi Media</title>
          <style>
            :root{color-scheme:dark;background:#0b0e14;color:#f6f7fb;font-family:system-ui,sans-serif}
            body{max-width:900px;margin:auto;padding:22px}
            h1{font-size:2rem;margin:.4rem 0 1rem}.lead,.hint{color:#aeb7c7}
            .grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:12px}
            .card,.item{display:flex;flex-direction:column;gap:6px;padding:18px;border:1px solid #293244;border-radius:16px;background:#131925;color:inherit;text-decoration:none}
            .card span,.item span{color:#9eabba;font-size:.9rem}.list{display:grid;gap:10px}
            .back{color:#8cb8ff;text-decoration:none}.player{margin-top:18px}
            video{width:100%;max-height:70vh;background:#000;border-radius:16px}audio{width:100%}
          </style>
        </head>
        <body>$body</body>
        </html>
    """.trimIndent()

    private fun escape(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    private fun humanBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes o"
        val units = arrayOf("Ko", "Mo", "Go", "To")
        var value = bytes.toDouble()
        var index = -1
        do {
            value /= 1024.0
            index++
        } while (value >= 1024 && index < units.lastIndex)
        return String.format(Locale.FRANCE, "%.1f %s", value, units[index])
    }

    companion object {
        private const val TAG = "ShizziMedia"
        internal const val MAX_CLIENTS = 24
    }
}
