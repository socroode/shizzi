package dev.shizzi.conso

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

internal class CallReceptionHttpClient(private val open: (URL) -> HttpURLConnection) : CallReceptionTransport {
    override fun poll(): JSONObject = request("call/poll?snapshot=1", null)
    override fun reject(callId: String): JSONObject = request("call/reject", JSONObject().put("callId", callId).toString())

    private fun request(path: String, body: String?): JSONObject {
        val url = URL(checkNotNull(SecureChatWebSupport.apiUrl(path)))
        val connection = open(url)
        try {
            connection.connectTimeout = 2_000
            connection.readTimeout = 4_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.requestMethod = if (body == null) "GET" else "POST"
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException(if (code in listOf(401, 403)) "Connecte ton compte Shizzi." else "Routeur Shizzi indisponible ($code).")
            return runCatching { JSONObject(text) }.getOrElse { throw IOException("Réponse Shizzi non reconnue.") }
        } finally { connection.disconnect() }
    }
}
