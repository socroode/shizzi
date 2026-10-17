package dev.shizzi.admin

import com.sun.net.httpserver.HttpServer
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AdminCompatibilityTest {
    @Test fun independentLegacyProofVector() {
        assertEquals("d376a5a7b003ee4761ed147eae14b57cad7487e6096d3fe39f9271275e812be0", AdminProof.compute("salt-test", "mot-de-passe", "nonce-test", 0))
    }

    @Test fun independentUpgradedProofVector() {
        assertEquals("f41a37db6103b2f02ce5055fd090fbe9f8402f842e2c8032e1b4a04c63eb47c4", AdminProof.compute("salt-test", "mot-de-passe", "nonce-test", 120_000))
    }

    @Test fun independentUnicodeProofVector() {
        assertEquals("225e00b2eb10a3ac9cb6f350cd258daea6f0435949af568850c4229176e05b48", AdminProof.compute("sel-polynesie", "Pāss & + é", "nonce-unique", 120_000))
    }

    @Test fun legacyAuthenticationWithoutIterationsField() = withRouter { router ->
        val client = router.client()
        client.login("admin", "secret")
        assertEquals("token-admin", client.token)
        assertTrue(client.request("GET", "/state").getBoolean("ok"))
        assertFalse(router.lastLoginBody.contains("secret"))
    }

    @Test fun upgradedAuthenticationWithPbkdf2() = withRouter(120_000) { router ->
        val client = router.client(secure = true)
        client.login("admin", "secret")
        assertTrue(client.request("GET", "/state").getBoolean("ok"))
        assertEquals(1, router.loginCount.get())
    }

    @Test fun credentialsWithAccentsAndFormCharactersAreEncoded() = withRouter { router ->
        router.password = "Pāss & + é"
        router.client().login("admin + é", router.password)
        assertEquals("admin + é", router.lastUsername)
    }

    @Test fun closedTlsListenerSelectsLegacy() = withRouter { router ->
        val (client, _) = AdminConnector.connect("admin", "secret", { throw ConnectException() }, { router.client() })
        assertFalse(client.secure)
        assertEquals(1, router.loginCount.get())
    }

    @Test fun absentTlsTimeoutSelectsLegacy() = withRouter { router ->
        val (client, _) = AdminConnector.connect("admin", "secret", { throw SocketTimeoutException() }, { router.client() })
        assertFalse(client.secure)
    }

    @Test fun tlsCertificateFailureNeverSelectsLegacy() {
        var legacy = false
        assertThrows(SSLHandshakeException::class.java) {
            AdminConnector.connect("admin", "secret", { throw SSLHandshakeException("bad certificate") }, {
                legacy = true; error("Must not downgrade")
            })
        }
        assertFalse(legacy)
    }

    @Test fun cancelledPairingNeverSelectsLegacy() {
        var legacy = false
        assertThrows(IllegalStateException::class.java) {
            AdminConnector.connect("admin", "secret", { error("Cancelled") }, { legacy = true; error("Must not downgrade") })
        }
        assertFalse(legacy)
    }

    @Test fun wrongSecurePasswordDoesNotRetryOnLegacy() = withRouter { router ->
        var legacy = false
        val failed = assertThrows(AdminApiException::class.java) {
            AdminConnector.connect("admin", "wrong", { router.client(true) }, { legacy = true; router.client() })
        }
        assertEquals(401, failed.status)
        assertEquals(1, router.loginCount.get())
        assertFalse(legacy)
    }

    @Test fun disabledSecureAdminDoesNotDowngrade() = withRouter { router ->
        router.disabled = true
        var legacy = false
        assertEquals(503, assertThrows(AdminApiException::class.java) {
            AdminConnector.connect("admin", "secret", { router.client(true) }, { legacy = true; router.client() })
        }.status)
        assertFalse(legacy)
        assertEquals(0, router.loginCount.get())
    }

    @Test fun malformedChallengeNeverSendsLogin() = withRouter { router ->
        router.badChallenge = true
        assertThrows(IllegalStateException::class.java) { router.client().login("admin", "secret") }
        assertEquals(0, router.loginCount.get())
    }

    @Test fun unknownKdfNeverSendsLogin() = withRouter(999_999_999) { router ->
        assertThrows(IllegalStateException::class.java) { router.client().login("admin", "secret") }
        assertEquals(0, router.loginCount.get())
    }

    @Test fun redirectsNeverReceiveToken() = withRouter { router ->
        val client = router.client()
        client.login("admin", "secret")
        assertEquals(302, assertThrows(AdminApiException::class.java) { client.request("GET", "/redirect") }.status)
        assertEquals(0, router.redirectHits.get())
    }

    @Test fun expiredSessionClearsTokenButTemporaryFailurePreservesIt() = withRouter { router ->
        val client = router.client()
        client.login("admin", "secret")
        assertEquals(503, assertThrows(AdminApiException::class.java) { client.request("GET", "/unavailable") }.status)
        assertFalse(client.token.isBlank())
        assertEquals(401, assertThrows(AdminApiException::class.java) { client.request("GET", "/expired") }.status)
        assertTrue(client.token.isBlank())
    }

    @Test fun forbiddenCommandIsSentExactlyOnce() = withRouter { router ->
        val client = router.client()
        client.login("admin", "secret")
        assertEquals(403, assertThrows(AdminApiException::class.java) { client.request("POST", "/forbidden", "{}") }.status)
        assertEquals(1, router.commandCount.get())
    }

    @Test fun commandResultsAndBinaryUploadsKeepSessionToken() = withRouter { router ->
        val client = router.client()
        client.login("admin", "secret")
        val result = client.request("POST", "/command", JSONObject().put("action", "account.create").toString())
        assertEquals("command-1", result.getString("id"))
        assertFalse(client.request("GET", "/result?id=command-1").getBoolean("pending"))
        assertTrue(client.requestBytes("PUT", "/upload", byteArrayOf(0, 1, -1), "application/octet-stream").getBoolean("ok"))
        assertArrayEquals(byteArrayOf(0, 1, -1), router.lastUpload)
    }

    @Test fun sixConcurrentClientsHaveSeparateSessions() = withRouter { router ->
        val executor = Executors.newFixedThreadPool(6)
        try {
            val sessions = executor.invokeAll((1..6).map { n -> Callable {
                val client = router.client()
                client.login("admin$n", "secret")
                assertTrue(client.request("GET", "/state").getBoolean("ok"))
                client.token
            } }).map { it.get() }
            assertEquals(6, sessions.toSet().size)
            assertEquals(6, router.loginCount.get())
        } finally { executor.shutdownNow() }
    }

    @Test fun switchingRouterCannotReusePreviousToken() = withRouter { first -> withRouter { second ->
        val a = first.client(); a.login("admin", "secret")
        val b = second.client()
        assertTrue(b.token.isBlank())
        assertThrows(IllegalStateException::class.java) { b.request("GET", "/state") }
        assertEquals(0, second.stateCount.get())
        assertFalse(a.token.isBlank())
    } }

    @Test fun oldRouterExposesOnlyItsActualSections() {
        val c = AdminCapabilities(state("accounts" to JSONArray(), "offers" to JSONArray(), "vouchers" to JSONArray(), "portal" to JSONObject(), "remoteAdmin" to JSONObject()))
        assertTrue(c.accounts && c.offers && c.portal && c.credentials)
        assertFalse(c.media || c.mediaOffers || c.battery || c.modules || c.fileUpload || c.cameras)
        assertTrue(c.supports("account.create")); assertFalse(c.supports("media.enable"))
    }

    @Test fun gatewayModulesAreRecognized() {
        val c = AdminCapabilities(state("modules" to JSONObject().put("mediaEnabled", true)))
        assertTrue(c.modules && c.supports("module.media.set"))
        assertFalse(c.media)
    }

    @Test fun mediaGenerationWithoutUploadIsRecognized() {
        val c = AdminCapabilities(state("media" to JSONObject().put("folders", JSONArray().put(JSONObject().put("treeUri", "source")))))
        assertTrue(c.media); assertFalse(c.fileUpload || c.mediaOffers || c.cameras)
    }

    @Test fun currentOptionalFeaturesAreRecognizedEvenWhenDisabled() {
        val c = AdminCapabilities(state(
            "accounts" to JSONArray().put(JSONObject().put("cameraMac", "")),
            "battery" to JSONObject().put("available", false),
            "mediaOffers" to JSONArray(), "mediaVouchers" to JSONArray(),
            "media" to JSONObject().put("folders", JSONArray().put(JSONObject().put("writable", false))),
        ))
        assertTrue(c.cameras && c.battery && c.mediaOffers && c.fileUpload)
    }

    @Test fun emptyOrFutureStateDoesNotInventFunctions() {
        val c = AdminCapabilities(JSONObject().put("state", JSONObject().put("futureField", true)))
        assertFalse(c.accounts || c.media || c.modules || c.offers || c.supports("future.delete"))
    }

    private fun state(vararg fields: Pair<String, Any>): JSONObject = JSONObject().put("state", JSONObject().apply {
        fields.forEach { (key, value) -> put(key, value) }
    })

    private fun withRouter(iterations: Int = 0, block: (Router) -> Unit) {
        val router = Router(iterations)
        try { block(router) } finally { router.close() }
    }

    private class Router(val iterations: Int) : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val executor = Executors.newCachedThreadPool()
        private val sessions = ConcurrentHashMap.newKeySet<String>()
        val loginCount = AtomicInteger(); val stateCount = AtomicInteger()
        val commandCount = AtomicInteger(); val redirectHits = AtomicInteger()
        @Volatile var password = "secret"
        @Volatile var lastLoginBody = ""
        @Volatile var lastUsername = ""
        @Volatile var lastUpload = byteArrayOf()
        var disabled = false; var badChallenge = false

        init {
            server.executor = executor
            server.createContext("/") { exchange ->
                var status = 200
                val path = exchange.requestURI.path.substringAfter("/api/v1/admin")
                val json = when (path) {
                    "/challenge" -> when {
                        disabled -> { status = 503; JSONObject().put("ok", false) }
                        badChallenge -> JSONObject().put("ok", true)
                        else -> JSONObject().put("ok", true).put("salt", "salt-test").put("nonce", "nonce-test").apply {
                            if (iterations != 0) put("iterations", iterations)
                        }
                    }
                    "/login" -> {
                        loginCount.incrementAndGet()
                        lastLoginBody = exchange.requestBody.bufferedReader().readText()
                        val fields = lastLoginBody.split('&').associate {
                            val parts = it.split('=', limit = 2)
                            URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
                        }
                        lastUsername = fields["username"].orEmpty()
                        val valid = fields["proof"] == AdminProof.compute("salt-test", password, "nonce-test", iterations)
                        if (!valid) { status = 401; JSONObject().put("ok", false) }
                        else {
                            val token = "token-" + fields["username"]
                            sessions.add(token)
                            JSONObject().put("ok", true).put("token", token).put("routerName", "Fixture")
                        }
                    }
                    else -> {
                        if (exchange.requestHeaders.getFirst("X-Shizzi-Admin-Token") !in sessions) {
                            status = 401; JSONObject().put("ok", false)
                        } else when (path) {
                            "/state" -> { stateCount.incrementAndGet(); JSONObject().put("ok", true).put("state", JSONObject()) }
                            "/command" -> { commandCount.incrementAndGet(); JSONObject().put("ok", true).put("id", "command-1") }
                            "/result" -> JSONObject().put("ok", true).put("pending", false).put("success", true)
                            "/upload" -> { lastUpload = exchange.requestBody.readBytes(); JSONObject().put("ok", true) }
                            "/expired" -> { status = 401; JSONObject().put("ok", false) }
                            "/unavailable" -> { status = 503; JSONObject().put("ok", false) }
                            "/forbidden" -> { commandCount.incrementAndGet(); status = 403; JSONObject().put("ok", false) }
                            "/redirect" -> { status = 302; exchange.responseHeaders.add("Location", "/api/v1/admin/target"); JSONObject() }
                            "/target" -> { redirectHits.incrementAndGet(); JSONObject() }
                            else -> { status = 404; JSONObject() }
                        }
                    }
                }
                val bytes = json.toString().toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            server.start()
        }
        fun client(secure: Boolean = false) = AdminApi("http://127.0.0.1:${server.address.port}/api/v1/admin", secure)
        override fun close() { server.stop(0); executor.shutdownNow() }
    }
}
