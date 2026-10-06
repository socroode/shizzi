package dev.shizzi.admin

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.Switch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var connectivity: ConnectivityManager
    private lateinit var root: LinearLayout

    private var boundWifi: Network? = null
    private var token: String = ""
    private var routerName: String = "Shizzi"
    private var lastState: JSONObject? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connectivity = getSystemService(ConnectivityManager::class.java)

        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(40))
        }
        scroll.addView(root)
        setContentView(scroll)

        bindToWifi()
        showLogin()
    }

    override fun onResume() {
        super.onResume()
        bindToWifi()
    }

    override fun onDestroy() {
        if (boundWifi != null) {
            connectivity.bindProcessToNetwork(null)
            boundWifi = null
        }
        super.onDestroy()
    }

    private fun bindToWifi(): Boolean {
        val active = connectivity.activeNetwork
        if (active != null) {
            val caps = connectivity.getNetworkCapabilities(active)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                connectivity.bindProcessToNetwork(active)
                boundWifi = active
                return true
            }
        }
        for (network in connectivity.allNetworks) {
            val caps = connectivity.getNetworkCapabilities(network)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                connectivity.bindProcessToNetwork(network)
                boundWifi = network
                return true
            }
        }
        return false
    }

    private fun showLogin(message: String = "") {
        root.removeAllViews()
        title("Shizzi Admin")
        info(
            "Admin associé à Shizzi 0.4.3.1 Media. " +
                "Connectez ce téléphone au Wi-Fi Shizzi à administrer.",
        )
        if (message.isNotBlank()) info(message)

        val username = field("Identifiant admin", "admin")
        val password = field("Mot de passe admin", "", password = true)

        button("Se connecter") {
            val user = username.text.toString().trim()
            val pass = password.text.toString()
            if (user.isBlank() || pass.isBlank()) {
                toast("Identifiant et mot de passe requis.")
                return@button
            }
            login(user, pass)
        }
    }

    private fun login(username: String, password: String) {
        runNetwork {
            if (!bindToWifi()) error("Aucun Wi-Fi connecté.")
            val challenge = get(
                "/challenge?username=" + URLEncoder.encode(username, "UTF-8"),
                authenticated = false,
            )
            if (!challenge.optBoolean("ok")) {
                error(challenge.optString("message", "Administration indisponible."))
            }

            val salt = challenge.getString("salt")
            val nonce = challenge.getString("nonce")
            val passwordHash = sha256Hex("$salt:$password")
            val proof = hmacHex(passwordHash, nonce)
            val form =
                "username=" + URLEncoder.encode(username, "UTF-8") +
                    "&nonce=" + URLEncoder.encode(nonce, "UTF-8") +
                    "&proof=" + URLEncoder.encode(proof, "UTF-8")
            val result = request(
                method = "POST",
                path = "/login",
                body = form,
                contentType = "application/x-www-form-urlencoded",
                authenticated = false,
            )
            if (!result.optBoolean("ok")) {
                error(result.optString("message", "Connexion refusée."))
            }
            token = result.getString("token")
            routerName = result.optString("routerName", "Shizzi")
            refresh()
        }
    }

    private fun refresh() {
        runNetwork {
            val response = get("/state")
            if (!response.optBoolean("ok")) error("Session Admin expirée.")
            lastState = response
            runOnUiThread { showDashboard(response) }
        }
    }

    private fun showDashboard(response: JSONObject) {
        root.removeAllViews()
        routerName = response.optString("routerName", routerName)
        title("Shizzi Admin — $routerName")

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(makeButton("Actualiser") { refresh() }, weighted())
        header.addView(makeButton("Déconnexion") { logout() }, weighted())
        root.addView(header)

        info(
            "Administration distante active. Internet Admin : 1 Mbps ↓ / 1 Mbps ↑. " +
                "ON/OFF Shizzi reste volontairement absent.",
        )

        val state = response.optJSONObject("state") ?: JSONObject()
        val traffic = response.optJSONObject("traffic") ?: JSONObject()

        renderAccounts(state.optJSONArray("accounts") ?: JSONArray())
        renderOffersAndVouchers(
            state.optJSONArray("offers") ?: JSONArray(),
            state.optJSONArray("vouchers") ?: JSONArray(),
        )
        renderDevices(traffic.optJSONArray("portalAuthorizations") ?: JSONArray())
        val modules = state.optJSONObject("modules") ?: JSONObject()
        renderModules(modules)
        renderMediaStatus(modules.optBoolean("mediaEnabled", true))
        renderPortal(state.optJSONObject("portal") ?: JSONObject())
        renderAdminCredentials(state.optJSONObject("remoteAdmin") ?: JSONObject())
        renderDiagnostics(traffic)
    }

    private fun renderAccounts(accounts: JSONArray) {
        section("Comptes")
        val number = field("N° de compte")
        val name = field("Nom")
        val pin = field("PIN / mot de passe")
        button("Créer le compte") {
            command(
                "account.create",
                JSONObject()
                    .put("number", number.text.toString())
                    .put("name", name.text.toString())
                    .put("pin", pin.text.toString()),
            )
        }

        for (i in 0 until accounts.length()) {
            val account = accounts.optJSONObject(i) ?: continue
            val n = account.optString("number")
            val label = account.optString("name").ifBlank { "Compte $n" }
            val enabled = account.optBoolean("enabled", true)
            info(
                "$label — N° $n\n" +
                    (if (enabled) "Actif" else "Suspendu") +
                    " · restant " + formatBytes(account.optLong("dataBalanceBytes")) +
                    " · utilisé " + formatBytes(
                    account.optLong("totalUpBytes") + account.optLong("totalDownBytes"),
                ),
            )
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(
                makeButton(if (enabled) "Suspendre" else "Activer") {
                    command(
                        "account.enable",
                        JSONObject().put("number", n).put("enabled", !enabled),
                    )
                },
                weighted(),
            )
            row.addView(
                makeButton("Déconnecter") {
                    command("account.disconnect", JSONObject().put("number", n))
                },
                weighted(),
            )
            root.addView(row)

            val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row2.addView(
                makeButton("Renommer") {
                    prompt("Nouveau nom", account.optString("name")) { value ->
                        command("account.rename", JSONObject().put("number", n).put("name", value))
                    }
                },
                weighted(),
            )
            row2.addView(
                makeButton("Changer PIN") {
                    prompt("Nouveau PIN", "", password = true) { value ->
                        command("account.pin", JSONObject().put("number", n).put("pin", value))
                    }
                },
                weighted(),
            )
            row2.addView(
                makeButton("Supprimer") {
                    confirm("Supprimer le compte $n ?") {
                        command("account.delete", JSONObject().put("number", n))
                    }
                },
                weighted(),
            )
            root.addView(row2)
            divider()
        }
    }

    private fun renderOffersAndVouchers(offers: JSONArray, vouchers: JSONArray) {
        section("Offres & vouchers")

        val id = field("ID offre (ex: promo-30)")
        val name = field("Nom de l'offre")
        val kind = field("Type : DATA ou UNLIMITED", "DATA")
        val down = field("Débit ↓ Mbps", "2")
        val up = field("Débit ↑ Mbps", "1")
        val quota = field("Volume Go (0 si illimité)", "12")
        val days = field("Validité jours", "30")
        val price = field("Prix FCFP", "1000")

        button("Créer / modifier l'offre") {
            val unlimited = kind.text.toString().trim().uppercase() == "UNLIMITED"
            command(
                "offer.upsert",
                JSONObject()
                    .put("id", id.text.toString().trim())
                    .put("name", name.text.toString().trim())
                    .put("kind", if (unlimited) "UNLIMITED" else "DATA")
                    .put("downloadBps", (down.text.toString().toLongOrNull() ?: 0L) * 1_000_000L)
                    .put("uploadBps", (up.text.toString().toLongOrNull() ?: 0L) * 1_000_000L)
                    .put(
                        "quotaBytes",
                        if (unlimited) 0L else
                            (quota.text.toString().toLongOrNull() ?: 0L) * 1_000_000_000L,
                    )
                    .put("durationDays", days.text.toString().toIntOrNull() ?: 0)
                    .put("priceXpf", price.text.toString().toIntOrNull() ?: 0),
            )
        }

        for (i in 0 until offers.length()) {
            val offer = offers.optJSONObject(i) ?: continue
            val offerId = offer.optString("id")
            info(
                offer.optString("name") + " — " +
                    (offer.optLong("downloadBps") / 1_000_000L) + "/" +
                    (offer.optLong("uploadBps") / 1_000_000L) + " Mbps · " +
                    offer.optString("kind") + " · " +
                    offer.optInt("durationDays") + " j · " +
                    offer.optInt("priceXpf") + " FCFP",
            )
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(
                makeButton("Charger") {
                    id.setText(offerId)
                    name.setText(offer.optString("name"))
                    kind.setText(offer.optString("kind"))
                    down.setText((offer.optLong("downloadBps") / 1_000_000L).toString())
                    up.setText((offer.optLong("uploadBps") / 1_000_000L).toString())
                    quota.setText((offer.optLong("quotaBytes") / 1_000_000_000L).toString())
                    days.setText(offer.optInt("durationDays").toString())
                    price.setText(offer.optInt("priceXpf").toString())
                },
                weighted(),
            )
            row.addView(
                makeButton("Générer") {
                    prompt("Nombre de vouchers (1 à 100)", "1") { value ->
                        command(
                            "voucher.generate",
                            JSONObject()
                                .put("offerId", offerId)
                                .put("count", (value.toIntOrNull() ?: 1).coerceIn(1, 100)),
                        )
                    }
                },
                weighted(),
            )
            row.addView(
                makeButton("Supprimer") {
                    confirm("Supprimer l'offre ${offer.optString("name")} ?") {
                        command("offer.delete", JSONObject().put("id", offerId))
                    }
                },
                weighted(),
            )
            root.addView(row)
            divider()
        }

        section("Inventaire vouchers")
        val max = minOf(vouchers.length(), 50)
        for (i in 0 until max) {
            val voucher = vouchers.optJSONObject(i) ?: continue
            val code = voucher.optString("code")
            val redeemed = voucher.optString("redeemedByAccount")
            val enabled = voucher.optBoolean("enabled", true)
            info(
                "$code — " + voucher.optString("snapshotName").ifBlank {
                    voucher.optString("offerId")
                } + " — " +
                    if (redeemed.isBlank()) {
                        if (enabled) "Disponible" else "Désactivé"
                    } else {
                        "Utilisé par $redeemed"
                    },
            )
            if (redeemed.isBlank()) {
                button(if (enabled) "Désactiver $code" else "Réactiver $code") {
                    command(
                        "voucher.enable",
                        JSONObject().put("code", code).put("enabled", !enabled),
                    )
                }
            }
        }
    }

    private fun renderDevices(sessions: JSONArray) {
        section("Appareils connectés")
        if (sessions.length() == 0) info("Aucune session client active.")
        for (i in 0 until sessions.length()) {
            val item = sessions.optJSONObject(i) ?: continue
            val ip = item.optString("ip")
            info(
                "$ip · " + item.optString("mac").ifBlank { "MAC —" } +
                    "\nCompte " + item.optString("accountNumber") +
                    " · " + if (item.optBoolean("authorized")) "Internet actif" else "Internet bloqué",
            )
            button("Déconnecter $ip") {
                command("session.disconnect", JSONObject().put("ip", ip))
            }
        }
    }

    private fun renderModules(modules: JSONObject) {
        section("Modules Shizzi")
        info("Internet — toujours actif")

        val messengerEnabled = modules.optBoolean("messengerEnabled", true)
        val messenger = Switch(this).apply {
            text = "Shizzi Messenger"
            isChecked = messengerEnabled
            setOnCheckedChangeListener { _, enabled ->
                command("module.messenger.set", JSONObject().put("enabled", enabled))
            }
        }
        root.addView(messenger, full())

        val mediaEnabled = modules.optBoolean("mediaEnabled", true)
        val media = Switch(this).apply {
            text = "Shizzi Media"
            isChecked = mediaEnabled
            setOnCheckedChangeListener { _, enabled ->
                command("module.media.set", JSONObject().put("enabled", enabled))
            }
        }
        root.addView(media, full())
    }

    private fun renderMediaStatus(mediaEnabled: Boolean) {
        if (!mediaEnabled) {
            section("Shizzi Media")
            info("Module Media désactivé sur ce hotspot.")
            return
        }
        section("Shizzi Media")
        val mediaStatus = TextView(this).apply {
            text = "Détection du serveur Media…"
            textSize = 14f
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(mediaStatus, full())

        fun probe() {
            mediaStatus.text = "Détection du serveur Media…"
            runNetwork {
                val wifi = boundWifi ?: run {
                    runOnUiThread { mediaStatus.text = "Aucun Wi-Fi Shizzi connecté." }
                    return@runNetwork
                }
                val found = mediaCandidates(wifi).firstOrNull { base ->
                    runCatching {
                        val connection = wifi.openConnection(URL(base + "health")) as HttpURLConnection
                        connection.connectTimeout = 1_000
                        connection.readTimeout = 1_000
                        connection.useCaches = false
                        val ok = connection.responseCode == 200
                        connection.disconnect()
                        ok
                    }.getOrDefault(false)
                }
                runOnUiThread {
                    mediaStatus.text = if (found == null) {
                        "Serveur Media indisponible ou désactivé sur le routeur."
                    } else {
                        "Serveur Media actif : $found"
                    }
                    if (found != null) {
                        button("Ouvrir Shizzi Media") {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(found)))
                        }
                    }
                }
            }
        }

        button("Tester Shizzi Media") { probe() }
        info("Le choix des dossiers Films / Séries / Musique reste volontairement effectué sur le téléphone routeur.")
        probe()
    }

    private fun mediaCandidates(network: Network): List<String> {
        val routes = connectivity.getLinkProperties(network)?.routes.orEmpty()
        return routes
            .sortedByDescending { route -> route.isDefaultRoute }
            .mapNotNull { route -> route.gateway as? Inet4Address }
            .mapNotNull { gateway -> gateway.hostAddress }
            .distinct()
            .map { gateway -> "http://$gateway:8088/" }
    }

    private fun renderPortal(portal: JSONObject) {
        section("Portail")
        val titleField = field("Titre / nom Wi-Fi", portal.optString("title"))
        val messageField = field("Message", portal.optString("message"))
        val htmlField = field("HTML / CSS", portal.optString("html"), multiline = true)
        button("Enregistrer le portail") {
            command(
                "portal.set",
                JSONObject()
                    .put("title", titleField.text.toString())
                    .put("message", messageField.text.toString())
                    .put("html", htmlField.text.toString()),
            )
        }
    }

    private fun renderAdminCredentials(config: JSONObject) {
        section("Compte Admin")
        val username = field("Identifiant admin", config.optString("username", "admin"))
        val password = field("Nouveau mot de passe (8 caractères minimum)", "", password = true)
        button("Changer les identifiants Admin") {
            if (password.text.toString().length < 8) {
                toast("Le nouveau mot de passe doit contenir au moins 8 caractères.")
            } else {
                command(
                    "admin.credentials",
                    JSONObject()
                        .put("username", username.text.toString())
                        .put("password", password.text.toString()),
                )
            }
        }
        info(
            "Ce compte reste portable et fonctionne sur n'importe quel téléphone. " +
                "Configurez le même identifiant/mot de passe sur WIFI1 et WIFI2 si vous le souhaitez.",
        )
    }

    private fun renderDiagnostics(traffic: JSONObject) {
        section("Diagnostic")
        val attribution = traffic.optJSONObject("attribution") ?: JSONObject()
        info(
            "Clients Android : " + attribution.optInt("mappedClients") +
                "\nFlux identifiés : " + attribution.optLong("resolvedFlows") +
                "\nFlux non identifiés : " + attribution.optLong("unresolvedFlows") +
                " (TCP " + attribution.optLong("unresolvedTcpFlows") +
                " / UDP " + attribution.optLong("unresolvedUdpFlows") + ")" +
                "\nAttente max : " + attribution.optLong("slowestResolveMillis") + " ms" +
                "\ndumpsys max : " + attribution.optLong("slowestDumpMillis") + " ms" +
                "\nRefus sans session/forfait : " + traffic.optLong("refusedUnauthorizedFlows") +
                "\nDNS non facturé : " + formatBytes(traffic.optLong("unattributedDnsBytes")),
        )
    }

    private fun command(action: String, params: JSONObject) {
        runNetwork {
            val start = request(
                "POST",
                "/command",
                JSONObject().put("action", action).put("params", params).toString(),
            )
            if (!start.optBoolean("ok")) error(start.optString("message", "Commande refusée."))
            val id = start.getString("id")
            var result: JSONObject? = null
            for (attempt in 0 until 20) {
                Thread.sleep(300)
                val candidate = get("/result?id=" + URLEncoder.encode(id, "UTF-8"))
                if (!candidate.optBoolean("pending", true)) {
                    result = candidate
                    break
                }
            }
            val finished = result ?: error("La commande est toujours en attente.")
            val message = finished.optString("message")
            runOnUiThread {
                toast(message.ifBlank { if (finished.optBoolean("success")) "OK" else "Échec" })
            }
            if (finished.optBoolean("success")) {
                Thread.sleep(250)
                refresh()
            }
        }
    }

    private fun logout() {
        if (token.isBlank()) {
            showLogin()
            return
        }
        runNetwork {
            runCatching { request("POST", "/logout", "") }
            token = ""
            runOnUiThread { showLogin("Session Admin fermée.") }
        }
    }

    private fun get(path: String, authenticated: Boolean = true): JSONObject =
        request("GET", path, authenticated = authenticated)

    private fun request(
        method: String,
        path: String,
        body: String? = null,
        contentType: String = "application/json",
        authenticated: Boolean = true,
    ): JSONObject {
        val connection = URL(BASE_URL + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 5_000
        connection.readTimeout = 8_000
        connection.useCaches = false
        connection.setRequestProperty("Accept", "application/json")
        if (authenticated && token.isNotBlank()) {
            connection.setRequestProperty("X-Shizzi-Admin-Token", token)
        }
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", contentType)
            connection.outputStream.use {
                it.write(body.toByteArray(StandardCharsets.UTF_8))
            }
        }
        val stream = if (connection.responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream ?: connection.inputStream
        }
        val text = BufferedReader(InputStreamReader(stream)).use { it.readText() }
        connection.disconnect()
        return JSONObject(text.ifBlank { "{}" })
    }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun hmacHex(key: String, value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun runNetwork(block: () -> Unit) {
        thread {
            try {
                block()
            } catch (failure: Throwable) {
                runOnUiThread {
                    toast(failure.message ?: failure.javaClass.simpleName)
                }
            }
        }
    }

    private fun title(text: String) {
        root.addView(TextView(this).apply {
            this.text = text
            textSize = 25f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(10))
        })
    }

    private fun section(text: String) {
        root.addView(TextView(this).apply {
            this.text = text
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(24), 0, dp(8))
        })
    }

    private fun info(text: String) {
        root.addView(TextView(this).apply {
            this.text = text
            textSize = 14f
            setPadding(0, dp(4), 0, dp(8))
        })
    }

    private fun field(
        hint: String,
        initial: String = "",
        password: Boolean = false,
        multiline: Boolean = false,
    ): EditText {
        val edit = EditText(this).apply {
            this.hint = hint
            setText(initial)
            if (password) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else if (multiline) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 6
                gravity = Gravity.TOP
            }
        }
        root.addView(edit, full())
        return edit
    }

    private fun button(text: String, onClick: () -> Unit) {
        root.addView(makeButton(text, onClick), full())
    }

    private fun makeButton(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
        }

    private fun divider() {
        root.addView(View(this).apply {
            setBackgroundColor(0x22000000)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(10)
            bottomMargin = dp(10)
        })
    }

    private fun prompt(
        title: String,
        initial: String = "",
        password: Boolean = false,
        callback: (String) -> Unit,
    ) {
        val input = EditText(this).apply {
            setText(initial)
            if (password) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("OK") { _, _ -> callback(input.text.toString()) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun confirm(message: String, callback: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("Confirmer") { _, _ -> callback() }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun full() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun weighted() = LinearLayout.LayoutParams(
        0,
        LinearLayout.LayoutParams.WRAP_CONTENT,
        1f,
    )

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format("%.2f Go", bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> String.format("%.1f Mo", bytes / 1_000_000.0)
        else -> "$bytes o"
    }

    private companion object {
        const val BASE_URL = "http://192.0.2.1/api/v1/admin"
    }
}
