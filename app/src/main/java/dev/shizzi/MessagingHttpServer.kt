package dev.shizzi

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

class MessagingHttpServer(
    context: Context,
    private val accountsProvider: () -> Map<String, MessagingAccount>,
) {
    private val store = MessagingStore(File(context.filesDir, STORE_FILE))
    private val callHub = CallSignalingHub()
    private val presence = ConcurrentHashMap<String, Long>()
    private val running = AtomicBoolean(false)
    private val acceptExecutor = Executors.newSingleThreadExecutor()
    private val clientPool = Executors.newFixedThreadPool(MAX_CLIENTS)
    private var serverSocket: ServerSocket? = null

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return isListening()

        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), PORT))
            }
        } catch (failure: IOException) {
            running.set(false)
            Log.e(TAG, "messaging server failed to bind $LOOPBACK_HOST:$PORT", failure)
            return false
        }

        serverSocket = socket
        acceptExecutor.execute { acceptLoop(socket) }
        Log.i(TAG, "messaging server listening on $LOOPBACK_HOST:$PORT")
        return true
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptExecutor.shutdownNow()
        clientPool.shutdownNow()
        presence.clear()
        Log.i(TAG, "messaging server stopped")
    }

    fun isListening(): Boolean =
        running.get() && serverSocket?.let { it.isBound && !it.isClosed } == true

    private fun acceptLoop(server: ServerSocket) {
        try {
            while (running.get()) {
                val client = try {
                    server.accept()
                } catch (failure: IOException) {
                    if (running.get()) Log.w(TAG, "messaging accept failed", failure)
                    break
                }
                try {
                    clientPool.execute { handleSafely(client) }
                } catch (_: RejectedExecutionException) {
                    runCatching { client.close() }
                }
            }
        } finally {
            running.set(false)
            runCatching { server.close() }
            if (serverSocket === server) serverSocket = null
        }
    }

    private fun handleSafely(socket: Socket) {
        try {
            handle(socket)
        } catch (failure: Exception) {
            if (!isNormalDisconnect(failure)) Log.w(TAG, "messaging client error", failure)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 20_000
            if (!client.inetAddress.isLoopbackAddress) {
                writeJson(client, 403, JSONObject().put("ok", false).put("message", "Accès local uniquement."))
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

            val accounts = accountsProvider()
            val account = MessagingStore.normalizeAccount(headers["x-shizzi-chat-account"].orEmpty())
            if (account.isBlank() || accounts[account]?.enabled != true) {
                writeJson(
                    output,
                    401,
                    JSONObject().put("ok", false).put("message", "Compte Shizzi requis."),
                    headOnly = method == "HEAD",
                )
                return
            }

            val now = System.currentTimeMillis()
            presence[account] = now
            prunePresence(now)

            val uri = target.substringBefore('?')
            val query = parseQuery(target.substringAfter('?', ""))
            val body = if (method == "POST") readBody(input, headers) else ByteArray(0)

            when {
                method in setOf("GET", "HEAD") && (uri == "/" || uri == "/index.html") ->
                    writeText(output, 200, "OK", "text/html; charset=utf-8", page(), method == "HEAD")

                method == "GET" && uri == "/api/snapshot" -> {
                    val payload = store.snapshot(
                        accountRaw = account,
                        accounts = accounts,
                        presence = presence,
                        nowMillis = now,
                        conversationIdRaw = query["conversation"],
                        markRead = query["markRead"] != "0",
                    )
                    writeJson(output, if (payload.optBoolean("ok")) 200 else 403, payload)
                }

                method == "POST" && uri == "/api/send" -> {
                    val payload = parseJson(body)
                    val result = store.send(
                        senderRaw = account,
                        conversationIdRaw = payload.optString("conversationId"),
                        textRaw = payload.optString("text"),
                        accounts = accounts,
                        nowMillis = now,
                    )
                    writeJson(output, if (result.optBoolean("ok")) 200 else 400, result)
                }

                method == "POST" && uri == "/api/group/create" -> {
                    val payload = parseJson(body)
                    val rawMembers = buildList {
                        val array = payload.optJSONArray("members") ?: JSONArray()
                        for (index in 0 until array.length()) add(array.optString(index))
                    }
                    val result = store.createGroup(
                        creatorRaw = account,
                        nameRaw = payload.optString("name"),
                        membersRaw = rawMembers,
                        accounts = accounts,
                        nowMillis = now,
                    )
                    writeJson(output, if (result.optBoolean("ok")) 200 else 400, result)
                }

                method == "POST" && uri == "/api/call/start" -> {
                    val payload = parseJson(body)
                    val offer = payload.optJSONObject("offer")
                    val result = callHub.start(
                        callerRaw = account,
                        calleeRaw = payload.optString("target"),
                        kindRaw = payload.optString("kind"),
                        offerTypeRaw = offer?.optString("type").orEmpty(),
                        offerSdpRaw = offer?.optString("sdp").orEmpty(),
                        accounts = accounts,
                        nowMillis = now,
                    )
                    writeJson(output, if (result.optBoolean("ok")) 200 else 409, result)
                }

                method == "POST" && uri == "/api/call/answer" -> {
                    val payload = parseJson(body)
                    val answer = payload.optJSONObject("answer")
                    val result = callHub.answer(
                        accountRaw = account,
                        callIdRaw = payload.optString("callId"),
                        answerTypeRaw = answer?.optString("type").orEmpty(),
                        answerSdpRaw = answer?.optString("sdp").orEmpty(),
                        accounts = accounts,
                        nowMillis = now,
                    )
                    writeJson(output, if (result.optBoolean("ok")) 200 else 409, result)
                }

                method == "POST" && uri == "/api/call/ice" -> {
                    val payload = parseJson(body)
                    val result = callHub.ice(
                        accountRaw = account,
                        callIdRaw = payload.optString("callId"),
                        candidate = payload.optJSONObject("candidate"),
                        accounts = accounts,
                        nowMillis = now,
                    )
                    writeJson(output, if (result.optBoolean("ok")) 200 else 403, result)
                }

                method == "POST" && uri == "/api/call/reject" -> {
                    val payload = parseJson(body)
                    val result = callHub.reject(
                        accountRaw = account,
                        callIdRaw = payload.optString("callId"),
                        accounts = accounts,
                        nowMillis = now,
                    )
                    writeJson(output, if (result.optBoolean("ok")) 200 else 409, result)
                }

                method == "POST" && uri == "/api/call/end" -> {
                    val payload = parseJson(body)
                    val result = callHub.end(
                        accountRaw = account,
                        callIdRaw = payload.optString("callId"),
                        accounts = accounts,
                        nowMillis = now,
                    )
                    writeJson(output, if (result.optBoolean("ok")) 200 else 403, result)
                }

                method == "GET" && uri == "/api/call/poll" -> {
                    val result = callHub.poll(
                        accountRaw = account,
                        afterSequence = query["after"]?.toLongOrNull() ?: 0L,
                        accounts = accounts,
                        nowMillis = now,
                    )
                    writeJson(output, if (result.optBoolean("ok")) 200 else 403, result)
                }

                method == "POST" && uri == "/api/heartbeat" -> {
                    presence[account] = now
                    writeJson(
                        output,
                        200,
                        JSONObject()
                            .put("ok", true)
                            .put("unread", store.unreadTotal(account, accounts)),
                    )
                }

                method in setOf("GET", "HEAD") && uri == "/health" ->
                    writeText(output, 200, "OK", "text/plain; charset=utf-8", "ok", method == "HEAD")

                else -> writeJson(output, 404, JSONObject().put("ok", false).put("message", "Introuvable."))
            }
        }
    }

    private fun readBody(input: BufferedInputStream, headers: Map<String, String>): ByteArray {
        val length = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        if (length > MAX_BODY_BYTES) throw IllegalArgumentException("Requête trop volumineuse.")
        val out = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(out, offset, length - offset)
            if (read < 0) break
            offset += read
        }
        return if (offset == out.size) out else out.copyOf(offset)
    }

    private fun parseJson(body: ByteArray): JSONObject =
        runCatching { JSONObject(body.toString(Charsets.UTF_8)) }.getOrElse { JSONObject() }

    private fun prunePresence(now: Long) {
        presence.entries.removeIf { now - it.value > PRESENCE_RETENTION_MILLIS }
    }

    private fun isNormalDisconnect(failure: Exception): Boolean {
        if (failure is SocketException) return true
        val message = failure.message.orEmpty().lowercase(Locale.US)
        return message.contains("broken pipe") ||
            message.contains("connection reset") ||
            message.contains("socket closed")
    }

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

    private fun writeJson(socket: Socket, code: Int, payload: JSONObject) {
        val output = BufferedOutputStream(socket.getOutputStream())
        writeJson(output, code, payload)
    }

    private fun writeJson(
        output: BufferedOutputStream,
        code: Int,
        payload: JSONObject,
        headOnly: Boolean = false,
    ) {
        writeText(output, code, reason(code), "application/json; charset=utf-8", payload.toString(), headOnly)
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

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        else -> "Error"
    }

    private fun page(): String = MessagingWebUi.page()

    companion object {
        private const val TAG = "ShizziMessaging"
        private const val STORE_FILE = "shizzi-messaging-v1.json"
        private const val LOOPBACK_HOST = "127.0.0.1"
        internal const val PORT = 8090
        internal const val MAX_CLIENTS = 32
        private const val MAX_BODY_BYTES = 64 * 1024
        private const val PRESENCE_RETENTION_MILLIS = 60_000L
    }
}
