package dev.shizzi.admin

import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

internal class AdminApiException(val status: Int, message: String) : IOException(message)
internal class AdminTlsUnavailable(cause: IOException) : IOException("Aucun service TLS Shizzi détecté.", cause)

/** One endpoint, token and Wi-Fi network for the complete session. No redirect or write retry. */
internal class AdminApi(
    private val baseUrl: String,
    val secure: Boolean,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    @Volatile var token: String = ""
        private set

    fun clearSession() { token = "" }

    fun login(username: String, password: String): JSONObject {
        clearSession()
        val challenge = request("GET", "/challenge?username=" + encode(username), authenticated = false)
        check(challenge.optBoolean("ok")) {
            challenge.optString("message", "Administration distante indisponible.")
        }
        val salt = challenge.optString("salt")
        val nonce = challenge.optString("nonce")
        check(salt.isNotBlank() && nonce.isNotBlank()) { "Réponse Admin Shizzi non reconnue." }
        val proof = AdminProof.compute(salt, password, nonce, challenge.optInt("iterations", 0))
        val result = request(
            "POST", "/login",
            "username=${encode(username)}&nonce=${encode(nonce)}&proof=${encode(proof)}",
            "application/x-www-form-urlencoded", authenticated = false,
        )
        check(result.optBoolean("ok")) { result.optString("message", "Connexion Admin refusée.") }
        val issued = result.optString("token")
        check(issued.isNotBlank()) { "Le routeur n'a pas délivré de session Admin." }
        token = issued
        return result
    }

    fun request(
        method: String,
        path: String,
        body: String? = null,
        contentType: String = "application/json",
        authenticated: Boolean = true,
        readTimeoutMillis: Int = 8_000,
    ): JSONObject = exchange(method, path, body?.toByteArray(UTF_8), contentType, authenticated, readTimeoutMillis)

    fun requestBytes(method: String, path: String, body: ByteArray, contentType: String): JSONObject =
        exchange(method, path, body, contentType, true, 90_000)

    private fun exchange(
        method: String, path: String, body: ByteArray?, contentType: String,
        authenticated: Boolean, timeout: Int,
    ): JSONObject {
        require(path.startsWith("/") && !path.startsWith("//"))
        val currentToken = token
        check(!authenticated || currentToken.isNotBlank()) { "Connectez-vous au compte Admin." }
        val connection = open(URL(baseUrl + path))
        try {
            connection.requestMethod = method
            connection.connectTimeout = 5_000
            connection.readTimeout = timeout
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            if (authenticated) connection.setRequestProperty("X-Shizzi-Admin-Token", currentToken)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(UTF_8)?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (status !in 200..299) {
                val fallback = when (status) {
                    401 -> "Session Admin expirée ou identifiants incorrects."
                    403 -> "Cette commande n'est pas disponible sur ce Shizzi."
                    426 -> "Ce routeur nécessite la connexion HTTPS. Réessayez la connexion."
                    503 -> "Activez l'administration à distance sur le routeur Shizzi."
                    in 300..399 -> "Redirection Admin refusée. Vérifiez le Wi-Fi Shizzi."
                    else -> "Le routeur a répondu avec le code $status."
                }
                if (authenticated && status == 401) clearSession()
                throw AdminApiException(status, json?.optString("message")?.takeIf { it.isNotBlank() } ?: fallback)
            }
            return json ?: throw IOException("Réponse Admin Shizzi non reconnue. Vérifiez le Wi-Fi connecté.")
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}

internal object AdminProof {
    fun compute(salt: String, password: String, nonce: String, iterations: Int): String {
        val base = MessageDigest.getInstance("SHA-256").digest("$salt:$password".toByteArray(UTF_8)).hex()
        val hash = when (iterations) {
            0 -> base
            120_000 -> {
                val spec = PBEKeySpec(base.toCharArray(), salt.toByteArray(UTF_8), iterations, 256)
                try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded.hex() }
                finally { spec.clearPassword() }
            }
            else -> error("Méthode d'authentification non prise en charge ($iterations).")
        }
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(hash.toByteArray(UTF_8), "HmacSHA256"))
            doFinal(nonce.toByteArray(UTF_8)).hex()
        }
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}

internal object AdminConnector {
    const val HTTP = "http://192.0.2.1/api/v1/admin"
    const val HTTPS = "https://192.0.2.1/api/v1/admin"

    /** Legacy fallback only when the TLS listener is unreachable, before any authentication. */
    fun connect(
        username: String, password: String,
        secureFactory: () -> AdminApi, legacyFactory: () -> AdminApi,
    ): Pair<AdminApi, JSONObject> {
        val client = try { secureFactory() } catch (failure: IOException) {
            if (failure !is AdminTlsUnavailable && failure !is ConnectException && failure !is SocketTimeoutException &&
                failure !is NoRouteToHostException) throw failure
            legacyFactory()
        }
        // Authentication errors, rejected certificates and cancelled pairing never cause a downgrade.
        return client to client.login(username, password)
    }
}
