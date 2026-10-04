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

class MediaHttpServer(
    private val context: Context,
    private val folderSnapshot: List<MediaFolderConfig> = MediaFolderStore.load(context),
) {
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
                "/thumbnail" -> serveThumbnail(
                    output,
                    query["id"],
                    accountNumber,
                    method == "HEAD",
                )
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
        val folders = MediaFolderStore.visibleFromSnapshot(folderSnapshot, accountNumber)
            .filter { it.uri() != null }
        val allowedFolderIds = folders.map { it.id }.toSet()
        val entries = MediaIndex.entries(context)
            .filter { it.folderId in allowedFolderIds }
            .take(HOME_ENTRY_LIMIT)

        val mediaCards = if (entries.isEmpty()) {
            "<p class=\"empty\">Aucun média indexé pour ce compte.</p>"
        } else {
            entries.joinToString("") { entry -> mediaCard(entry) }
        }

        val folderCards = if (folders.isEmpty()) {
            "<p class=\"empty\">Aucun dossier Media autorisé.</p>"
        } else {
            folders.joinToString("") { folder ->
                """
                <a class="folder-card" href="library?folder=${folder.id}">
                  <span class="folder-icon">▣</span>
                  <strong>${escape(folder.name)}</strong>
                  <small>${escape(folder.kind.label)}</small>
                </a>
                """.trimIndent()
            }
        }

        val html = page(
            "Shizzi Media",
            """
            <section class="hero">
              <div>
                <span class="eyebrow">BIBLIOTHÈQUE LOCALE</span>
                <h1>Shizzi <em>Media</em></h1>
                <p class="lead">Films, séries et musique disponibles directement sur le routeur Shizzi.</p>
              </div>
            </section>
            <section>
              <div class="section-head"><h2>Vignettes</h2><span>${entries.size} média(s)</span></div>
              <div class="poster-grid">$mediaCards</div>
            </section>
            <section>
              <div class="section-head"><h2>Dossiers</h2><span>${folders.size}</span></div>
              <div class="folder-grid">$folderCards</div>
            </section>
            <p class="hint">Les miniatures vidéo sont générées automatiquement sur le routeur et mises en cache.</p>
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
        val visibleFolders = MediaFolderStore.visibleFromSnapshot(folderSnapshot, accountNumber)
            .filter { it.uri() != null }
        val selectedFolder = folderId
            ?.let { wanted -> visibleFolders.firstOrNull { it.id == wanted } }
        val selectedKind = if (folderId.isNullOrBlank()) MediaKind.fromKey(rawKind) else null

        val selectedFolders = when {
            selectedFolder != null -> listOf(selectedFolder)
            selectedKind != null -> visibleFolders.filter { it.kind == selectedKind }
            else -> emptyList()
        }

        if (selectedFolders.isEmpty()) {
            writeText(output, 404, "Not Found", "text/plain; charset=utf-8", "Dossier introuvable", headOnly)
            return
        }

        val title = selectedFolder?.name ?: selectedKind?.label ?: "Media"
        val entries = selectedFolders
            .flatMap { folder -> MediaIndex.entriesForFolder(context, folder.id) }
            .sortedBy { MediaThumbnailPolicy.displayTitle(it.name).lowercase(Locale.getDefault()) }

        val cards = if (entries.isEmpty()) {
            "<p class=\"empty\">Aucun fichier trouvé dans ${escape(title)}.</p>"
        } else {
            entries.joinToString("") { entry -> mediaCard(entry) }
        }

        val html = page(
            title,
            """
            <a class="back" href="./">← Accueil Media</a>
            <div class="section-head"><h1>${escape(title)}</h1><span>${entries.size} média(s)</span></div>
            <div class="poster-grid">$cards</div>
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
            "<video controls autoplay playsinline preload=\"metadata\" poster=\"thumbnail?id=${entry.id}&v=${entry.size}\" src=\"stream?id=${entry.id}\"></video>"
        }
        val html = page(
            entry.name,
            """
            <a class="back" href="library?folder=${entry.folderId}">← ${escape(entry.folderName.ifBlank { entry.kind.label })}</a>
            <h1>${escape(MediaThumbnailPolicy.displayTitle(entry.name))}</h1>
            <p>${escape(entry.relativePath)}</p>
            <div class="player">$mediaTag</div>
            <p class="hint">Lecture locale via le hotspot Shizzi.</p>
            """.trimIndent(),
        )
        writeText(output, 200, "OK", "text/html; charset=utf-8", html, headOnly)
    }

    private fun serveThumbnail(
        output: BufferedOutputStream,
        id: String?,
        accountNumber: String,
        headOnly: Boolean,
    ) {
        val entry = MediaIndex.find(context, id)
        if (entry == null || !entryAllowed(entry, accountNumber)) {
            writeText(output, 404, "Not Found", "text/plain; charset=utf-8", "Miniature introuvable", headOnly)
            return
        }

        val thumbnail = MediaThumbnailStore.thumbnailFile(context, entry)
        if (thumbnail != null && thumbnail.isFile) {
            writeBinary(
                output = output,
                contentType = "image/jpeg",
                bytes = thumbnail.readBytes(),
                headOnly = headOnly,
                cacheControl = "private, max-age=86400",
            )
            return
        }

        val label = escape(MediaThumbnailPolicy.displayTitle(entry.name)).take(50)
        val icon = if (entry.kind == MediaKind.MUSIC) "♫" else "▶"
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" width="480" height="270" viewBox="0 0 480 270">
              <defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop stop-color="#28145d"/><stop offset="1" stop-color="#07111f"/></linearGradient></defs>
              <rect width="480" height="270" rx="24" fill="url(#g)"/>
              <text x="240" y="118" text-anchor="middle" fill="#a78bfa" font-size="48">$icon</text>
              <text x="240" y="172" text-anchor="middle" fill="#f8fafc" font-family="sans-serif" font-size="22">$label</text>
            </svg>
        """.trimIndent().toByteArray(Charsets.UTF_8)
        writeBinary(
            output,
            "image/svg+xml; charset=utf-8",
            svg,
            headOnly,
            "private, max-age=600",
        )
    }

    private fun mediaCard(entry: MediaEntry): String {
        val title = escape(MediaThumbnailPolicy.displayTitle(entry.name))
        val folder = escape(entry.folderName.ifBlank { entry.kind.label })
        val badge = when (entry.kind) {
            MediaKind.FILMS -> "Film"
            MediaKind.SERIES -> "Série"
            MediaKind.MUSIC -> "Musique"
        }
        val size = if (entry.size > 0L) humanBytes(entry.size) else "Local"
        return """
            <a class="poster" data-title="$title" href="play?id=${entry.id}">
              <div class="poster-image">
                <img loading="lazy" src="thumbnail?id=${entry.id}&v=${entry.size}" alt="$title">
                <span class="badge">$badge</span>
              </div>
              <strong>$title</strong>
              <small>$folder · $size</small>
            </a>
        """.trimIndent()
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
        return MediaFolderStore.canAccessSnapshot(folderSnapshot, entry.folderId, accountNumber)
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

    private fun writeBinary(
        output: BufferedOutputStream,
        contentType: String,
        bytes: ByteArray,
        headOnly: Boolean,
        cacheControl: String,
    ) {
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: $cacheControl\r\n" +
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
          <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
          <title>${escape(title)} · Shizzi Media</title>
          <style>
            :root{color-scheme:dark;background:#06101d;color:#f8fafc;font-family:Inter,system-ui,-apple-system,sans-serif}
            *{box-sizing:border-box}body{max-width:1100px;margin:auto;padding:18px 16px 38px;background:radial-gradient(circle at 90% 0,#211044 0,transparent 34%),#06101d}
            a{color:inherit}.top{position:sticky;top:0;z-index:10;padding:8px 0 14px;background:linear-gradient(#06101df7 80%,transparent)}
            .brand{display:flex;align-items:center;gap:11px;font-size:1.55rem;font-weight:900;text-decoration:none}.logo{display:grid;place-items:center;width:38px;height:38px;border-radius:13px;background:linear-gradient(135deg,#7c3aed,#2563eb)}
            .brand em,.hero em{font-style:normal;color:#8b5cf6}.search{width:100%;margin-top:14px;padding:14px 16px;border:1px solid #334155;border-radius:17px;background:#101a2b;color:#fff;font-size:1rem;outline:none}
            .chips{display:flex;gap:8px;overflow-x:auto;padding:12px 0 2px;scrollbar-width:none}.chip{white-space:nowrap;text-decoration:none;padding:10px 14px;border:1px solid #334155;border-radius:999px;background:#0e1728;color:#dbeafe;font-weight:700}.chip:first-child{background:linear-gradient(90deg,#6d28d9,#7c3aed);border-color:#8b5cf6}
            .hero{margin:14px 0 26px;padding:22px;border:1px solid #ffffff17;border-radius:24px;background:linear-gradient(135deg,#17122d,#0b1d33);box-shadow:0 16px 50px #0005}.hero h1{font-size:2.25rem;margin:5px 0 6px}.eyebrow{font-size:.72rem;letter-spacing:.13em;color:#a78bfa;font-weight:900}.lead,.hint,.empty{color:#94a3b8;line-height:1.5}
            section{margin:24px 0}.section-head{display:flex;align-items:end;justify-content:space-between;gap:12px;margin:0 0 12px}.section-head h1,.section-head h2{margin:0}.section-head span{color:#94a3b8;font-size:.9rem}
            .poster-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}.poster{min-width:0;text-decoration:none}.poster-image{position:relative;aspect-ratio:16/10;overflow:hidden;border-radius:16px;background:#111827;border:1px solid #ffffff14}.poster img{width:100%;height:100%;display:block;object-fit:cover}.poster strong{display:block;margin:8px 3px 2px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.poster small{display:block;margin:0 3px;color:#94a3b8;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.badge{position:absolute;top:8px;right:8px;padding:5px 8px;border-radius:9px;background:#050b15d9;color:#fff;font-size:.72rem;font-weight:900}
            .folder-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.folder-card{display:flex;flex-direction:column;gap:4px;padding:16px;border:1px solid #334155;border-radius:17px;background:#0f1a2b;text-decoration:none}.folder-card small{color:#94a3b8}.folder-icon{color:#a78bfa;font-size:1.35rem}
            .back{display:inline-block;margin:8px 0 20px;color:#c4b5fd;text-decoration:none;font-weight:800}.player{margin-top:18px}video{width:100%;max-height:70vh;background:#000;border-radius:18px}audio{width:100%}
            @media(min-width:700px){.poster-grid{grid-template-columns:repeat(4,minmax(0,1fr))}.folder-grid{grid-template-columns:repeat(4,minmax(0,1fr))}}
          </style>
        </head>
        <body>
          <div class="top">
            <a class="brand" href="./"><span class="logo">▶</span><span>Shizzi <em>Media</em></span></a>
            <input id="media-search" class="search" type="search" placeholder="Rechercher un film, une série, une musique…" autocomplete="off">
            <nav class="chips">
              <a class="chip" href="./">Accueil</a>
              <a class="chip" href="library?kind=films">Films</a>
              <a class="chip" href="library?kind=series">Séries</a>
              <a class="chip" href="library?kind=music">Musique</a>
            </nav>
          </div>
          $body
          <script>
          (function(){
            var input=document.getElementById('media-search');
            if(!input)return;
            input.addEventListener('input',function(){
              var q=(input.value||'').trim().toLowerCase();
              document.querySelectorAll('.poster').forEach(function(card){
                var title=(card.getAttribute('data-title')||'').toLowerCase();
                card.style.display=!q||title.indexOf(q)>=0?'':'none';
              });
            });
          })();
          </script>
        </body>
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
        private const val HOME_ENTRY_LIMIT = 24
    }
}
