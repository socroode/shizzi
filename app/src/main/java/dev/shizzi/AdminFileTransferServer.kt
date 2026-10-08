package dev.shizzi

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

class AdminFileTransferServer(private val context: Context) {
    private data class UploadSession(
        val id: String,
        val folderId: String,
        val finalName: String,
        val mimeType: String,
        val expectedSize: Long,
        val tempUri: Uri,
        val descriptor: ParcelFileDescriptor,
        val output: FileOutputStream,
        @Volatile var receivedBytes: Long = 0L,
        @Volatile var closed: Boolean = false,
    )

    private val running = AtomicBoolean(false)
    private val sessions = ConcurrentHashMap<String, UploadSession>()
    private val acceptExecutor = Executors.newSingleThreadExecutor()
    private val clientPool = Executors.newFixedThreadPool(2)
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
            Log.e(TAG, "admin file server failed to bind $LOOPBACK_HOST:$PORT", failure)
            return false
        }
        serverSocket = socket
        acceptExecutor.execute { acceptLoop(socket) }
        Log.i(TAG, "admin file server listening on $LOOPBACK_HOST:$PORT")
        return true
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        sessions.values.forEach(::closeSession)
        sessions.clear()
        acceptExecutor.shutdownNow()
        clientPool.shutdownNow()
    }

    fun isListening(): Boolean =
        running.get() && serverSocket?.let { it.isBound && !it.isClosed } == true

    private fun acceptLoop(server: ServerSocket) {
        try {
            while (running.get()) {
                val client = try {
                    server.accept()
                } catch (failure: IOException) {
                    if (running.get()) Log.w(TAG, "admin file accept failed", failure)
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
            if (!isNormalDisconnect(failure)) {
                Log.w(TAG, "admin file transfer error", failure)
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 90_000
            if (client.inetAddress?.isLoopbackAddress != true) {
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

            val path = target.substringBefore('?')
            val query = parseQuery(target.substringAfter('?', ""))
            when {
                method == "GET" && path == "/health" ->
                    writeJson(output, 200, "OK", JSONObject().put("ok", true))

                method == "POST" && path == "/upload/start" ->
                    startUpload(output, readJson(input, headers))

                method == "GET" && path == "/upload/status" ->
                    uploadStatus(output, query["id"])

                method == "PUT" && path == "/upload/chunk" ->
                    appendChunk(output, input, headers, query["id"], query["offset"])

                method == "POST" && path == "/upload/finish" ->
                    finishUpload(output, readJson(input, headers), query["id"])

                method == "DELETE" && path == "/upload" ->
                    cancelUpload(output, query["id"])

                else -> writeJson(
                    output,
                    404,
                    "Not Found",
                    JSONObject().put("ok", false).put("message", "Route de transfert inconnue."),
                )
            }
        }
    }

    private fun startUpload(output: BufferedOutputStream, body: JSONObject) {
        if (sessions.size >= MAX_ACTIVE_UPLOADS) {
            writeJson(
                output,
                429,
                "Too Many Requests",
                JSONObject().put("ok", false).put("message", "Un autre transfert est déjà actif."),
            )
            return
        }

        val folderId = body.optString("folderId").trim()
        val folder = MediaFolderStore.byId(context, folderId)
        val treeUri = folder?.uri()
        if (folder == null || treeUri == null) {
            writeJson(
                output,
                400,
                "Bad Request",
                JSONObject().put("ok", false).put("message", "Dossier de destination non configuré."),
            )
            return
        }
        if (!MediaRemoteSources.isWritable(context, treeUri)) {
            writeJson(
                output,
                403,
                "Forbidden",
                JSONObject().put("ok", false).put(
                    "message",
                    "Android n'a pas accordé l'écriture sur ce dossier. Autorisez-le une fois sur le routeur.",
                ),
            )
            return
        }

        val fileName = AdminFileTransferProtocol.sanitizeFileName(body.optString("fileName"))
        val mimeType = AdminFileTransferProtocol.normalizeMimeType(body.optString("mimeType"))
        val expectedSize = body.optLong("sizeBytes", -1L)
        val id = UUID.randomUUID().toString().replace("-", "")
        val tempName = AdminFileTransferProtocol.sanitizeFileName(
            "$fileName.shizzi-${id.take(8)}.part",
        )

        val tempUri = runCatching {
            val parentId = runCatching { DocumentsContract.getDocumentId(treeUri) }
                .getOrElse { DocumentsContract.getTreeDocumentId(treeUri) }
            val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
            DocumentsContract.createDocument(
                context.contentResolver,
                parent,
                mimeType,
                tempName,
            )
        }.getOrNull()

        if (tempUri == null) {
            writeJson(
                output,
                500,
                "Internal Server Error",
                JSONObject().put("ok", false).put("message", "Impossible de créer le fichier sur le routeur."),
            )
            return
        }

        val descriptor = runCatching {
            context.contentResolver.openFileDescriptor(tempUri, "w")
        }.getOrNull()
        if (descriptor == null) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, tempUri) }
            writeJson(
                output,
                500,
                "Internal Server Error",
                JSONObject().put("ok", false).put("message", "Impossible d'ouvrir le fichier de destination."),
            )
            return
        }

        val session = UploadSession(
            id = id,
            folderId = folderId,
            finalName = fileName,
            mimeType = mimeType,
            expectedSize = expectedSize,
            tempUri = tempUri,
            descriptor = descriptor,
            output = FileOutputStream(descriptor.fileDescriptor),
        )
        sessions[id] = session
        writeJson(
            output,
            200,
            "OK",
            JSONObject()
                .put("ok", true)
                .put("id", id)
                .put("receivedBytes", 0L)
                .put("chunkBytes", AdminFileTransferProtocol.MAX_CHUNK_BYTES),
        )
    }

    private fun uploadStatus(output: BufferedOutputStream, rawId: String?) {
        val session = sessions[rawId.orEmpty()]
        if (session == null) {
            writeJson(
                output,
                404,
                "Not Found",
                JSONObject().put("ok", false).put("message", "Transfert introuvable."),
            )
            return
        }
        writeJson(
            output,
            200,
            "OK",
            JSONObject()
                .put("ok", true)
                .put("id", session.id)
                .put("receivedBytes", session.receivedBytes)
                .put("sizeBytes", session.expectedSize),
        )
    }

    private fun appendChunk(
        output: BufferedOutputStream,
        input: BufferedInputStream,
        headers: Map<String, String>,
        rawId: String?,
        rawOffset: String?,
    ) {
        val session = sessions[rawId.orEmpty()]
        if (session == null) {
            writeJson(output, 404, "Not Found", JSONObject().put("ok", false).put("message", "Transfert introuvable."))
            return
        }

        val length = headers["content-length"]?.toIntOrNull() ?: -1
        val offset = rawOffset?.toLongOrNull() ?: -1L
        synchronized(session) {
            if (session.closed) {
                writeJson(output, 409, "Conflict", JSONObject().put("ok", false).put("message", "Transfert déjà terminé."))
                return
            }
            if (!AdminFileTransferProtocol.validChunkOffset(session.receivedBytes, offset, length)) {
                writeJson(
                    output,
                    409,
                    "Conflict",
                    JSONObject()
                        .put("ok", false)
                        .put("message", "Décalage de reprise invalide.")
                        .put("receivedBytes", session.receivedBytes),
                )
                return
            }
            val data = readExact(input, length)
            if (data == null) {
                writeJson(output, 400, "Bad Request", JSONObject().put("ok", false).put("message", "Bloc incomplet."))
                return
            }
            session.output.write(data)
            session.output.flush()
            session.receivedBytes += data.size
            writeJson(
                output,
                200,
                "OK",
                JSONObject()
                    .put("ok", true)
                    .put("receivedBytes", session.receivedBytes),
            )
        }
    }

    private fun finishUpload(
        output: BufferedOutputStream,
        body: JSONObject,
        rawId: String?,
    ) {
        val id = rawId.orEmpty()
        val session = sessions[id]
        if (session == null) {
            writeJson(output, 404, "Not Found", JSONObject().put("ok", false).put("message", "Transfert introuvable."))
            return
        }

        synchronized(session) {
            if (!session.closed) {
                closeOutput(session)
            }
        }

        if (session.expectedSize >= 0L && session.receivedBytes != session.expectedSize) {
            writeJson(
                output,
                409,
                "Conflict",
                JSONObject()
                    .put("ok", false)
                    .put("message", "Taille reçue différente du fichier source.")
                    .put("receivedBytes", session.receivedBytes),
            )
            return
        }

        val expectedSha = body.optString("sha256").trim().lowercase(Locale.US)
        val actualSha = runCatching { sha256(session.tempUri) }.getOrNull()
        if (actualSha == null || expectedSha.isBlank() || actualSha != expectedSha) {
            writeJson(
                output,
                409,
                "Conflict",
                JSONObject()
                    .put("ok", false)
                    .put("message", "Vérification SHA-256 échouée.")
                    .put("sha256", actualSha ?: ""),
            )
            return
        }

        val finalUri = runCatching {
            DocumentsContract.renameDocument(
                context.contentResolver,
                session.tempUri,
                session.finalName,
            )
        }.getOrNull()
        if (finalUri == null) {
            writeJson(
                output,
                500,
                "Internal Server Error",
                JSONObject().put("ok", false).put(
                    "message",
                    "Fichier reçu mais Android n'a pas pu finaliser son nom.",
                ),
            )
            return
        }

        sessions.remove(id)
        kotlin.concurrent.thread(name = "shizzi-admin-upload-scan") {
            runCatching {
                val folder = MediaFolderStore.byId(context, session.folderId)
                MediaIndex.rebuild(context.applicationContext, folder?.kind)
                if (MediaPrefs.isEnabled(context)) {
                    MediaServerService.restart(context.applicationContext)
                }
            }.onFailure {
                SessionLog.warn("admin upload Media scan failed: ${it.message}")
            }
        }

        writeJson(
            output,
            200,
            "OK",
            JSONObject()
                .put("ok", true)
                .put("fileName", session.finalName)
                .put("sizeBytes", session.receivedBytes)
                .put("sha256", actualSha)
                .put("scanStarted", true),
        )
    }

    private fun cancelUpload(output: BufferedOutputStream, rawId: String?) {
        val session = sessions.remove(rawId.orEmpty())
        if (session == null) {
            writeJson(output, 200, "OK", JSONObject().put("ok", true))
            return
        }
        closeSession(session)
        runCatching {
            DocumentsContract.deleteDocument(context.contentResolver, session.tempUri)
        }
        writeJson(output, 200, "OK", JSONObject().put("ok", true))
    }

    private fun sha256(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        context.contentResolver.openInputStream(uri).use { stream ->
            requireNotNull(stream) { "Fichier inaccessible." }
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun closeOutput(session: UploadSession) {
        if (session.closed) return
        session.closed = true
        runCatching { session.output.flush() }
        runCatching { session.output.fd.sync() }
        runCatching { session.output.close() }
        runCatching { session.descriptor.close() }
    }

    private fun closeSession(session: UploadSession) {
        synchronized(session) { closeOutput(session) }
    }

    private fun readJson(
        input: BufferedInputStream,
        headers: Map<String, String>,
    ): JSONObject {
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length !in 0..MAX_JSON_BYTES) return JSONObject()
        val bytes = readExact(input, length) ?: return JSONObject()
        return runCatching { JSONObject(String(bytes, StandardCharsets.UTF_8)) }
            .getOrElse { JSONObject() }
    }

    private fun readExact(input: BufferedInputStream, length: Int): ByteArray? {
        if (length < 0) return null
        val out = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(out, offset, length - offset)
            if (read < 0) return null
            offset += read
        }
        return out
    }

    private fun parseQuery(raw: String): Map<String, String> =
        raw.split('&')
            .mapNotNull { part ->
                if (part.isBlank()) return@mapNotNull null
                val pieces = part.split('=', limit = 2)
                val key = URLDecoder.decode(pieces[0], "UTF-8")
                val value = URLDecoder.decode(pieces.getOrElse(1) { "" }, "UTF-8")
                key to value
            }
            .toMap()

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>(128)
        while (bytes.size < 16 * 1024) {
            val value = input.read()
            if (value < 0) return if (bytes.isEmpty()) null else String(bytes.toByteArray(), StandardCharsets.UTF_8)
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes += value.toByte()
        }
        return String(bytes.toByteArray(), StandardCharsets.UTF_8)
    }

    private fun writeSimple(socket: Socket, code: Int, reason: String, message: String) {
        val output = BufferedOutputStream(socket.getOutputStream())
        writeJson(output, code, reason, JSONObject().put("ok", false).put("message", message))
    }

    private fun writeJson(
        output: BufferedOutputStream,
        code: Int,
        reason: String,
        body: JSONObject,
    ) {
        val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
        output.write(
            (
                "HTTP/1.1 $code $reason\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "Connection: close\r\n\r\n"
            ).toByteArray(StandardCharsets.UTF_8),
        )
        output.write(bytes)
        output.flush()
    }

    private fun isNormalDisconnect(failure: Exception): Boolean {
        if (failure is SocketException) return true
        val message = failure.message.orEmpty().lowercase(Locale.US)
        return message.contains("broken pipe") ||
            message.contains("connection reset") ||
            message.contains("socket closed")
    }

    companion object {
        private const val TAG = "ShizziAdminFiles"
        private const val LOOPBACK_HOST = "127.0.0.1"
        internal const val PORT = 8092
        private const val MAX_ACTIVE_UPLOADS = 2
        private const val MAX_JSON_BYTES = 64 * 1024
    }
}
