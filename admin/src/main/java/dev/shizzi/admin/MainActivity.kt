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
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.ArrayDeque
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var connectivity: ConnectivityManager
    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView

    private var boundWifi: Network? = null
    private var token: String = ""
    private var routerName: String = "Shizzi"
    private var lastState: JSONObject? = null
    private var lastRenderedStateJson: String = ""
    private var activityResumed = false
    private var pendingUploadFolderId: String? = null
    private var pendingUploadFolderName: String? = null
    private val recentUploads = ArrayDeque<String>()
    @Volatile private var refreshInFlight = false
    @Volatile private var uploadCancelled = false
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val autoRefreshRunnable = object : Runnable {
        override fun run() {
            if (
                AdminRefreshPolicy.shouldPoll(
                    activityResumed = activityResumed,
                    hasToken = token.isNotBlank(),
                    hasWindowFocus = hasWindowFocus(),
                    refreshInFlight = refreshInFlight,
                )
            ) {
                refresh(manual = false)
            }
            scheduleAutoRefresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connectivity = getSystemService(ConnectivityManager::class.java)

        scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(40))
        }
        scroll.addView(root)
        setContentView(scroll)

        bindToWifi()
        showLogin()
    }

    @Deprecated("Legacy Activity result API kept for Android 11+ compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_UPLOAD_FILE || resultCode != RESULT_OK) return

        val uri = data?.data ?: return
        runCatching {
            val flags = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (flags != 0) {
                contentResolver.takePersistableUriPermission(uri, flags)
            }
        }

        val folderId = pendingUploadFolderId
        val folderName = pendingUploadFolderName
        pendingUploadFolderId = null
        pendingUploadFolderName = null
        if (folderId.isNullOrBlank()) {
            toast("Dossier de destination perdu. Recommence le transfert.")
            return
        }
        startAdminFileUpload(uri, folderId, folderName.orEmpty())
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        bindToWifi()
        if (token.isNotBlank()) scheduleAutoRefresh(immediate = true)
    }

    override fun onPause() {
        activityResumed = false
        stopAutoRefresh()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && activityResumed && token.isNotBlank()) {
            scheduleAutoRefresh(immediate = true)
        }
    }

    override fun onDestroy() {
        stopAutoRefresh()
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
        stopAutoRefresh()
        lastRenderedStateJson = ""
        root.removeAllViews()
        title("Shizzi Admin")
        info(
            "Administration distante Shizzi. " +
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
            runOnUiThread { scheduleAutoRefresh() }
        }
    }

    private fun refresh(manual: Boolean = true) {
        if (refreshInFlight) return
        refreshInFlight = true
        thread {
            try {
                val response = get("/state")
                if (!response.optBoolean("ok")) {
                    token = ""
                    runOnUiThread { showLogin("Session Admin expirée.") }
                    return@thread
                }
                lastState = response
                val snapshot = response.toString()
                runOnUiThread {
                    val shouldRender = snapshot != lastRenderedStateJson &&
                        (manual || hasWindowFocus())
                    if (shouldRender) {
                        val oldScrollY = scroll.scrollY
                        showDashboard(response)
                        lastRenderedStateJson = snapshot
                        scroll.post { scroll.scrollTo(0, oldScrollY) }
                    }
                }
            } catch (failure: Throwable) {
                if (manual) {
                    runOnUiThread {
                        toast(failure.message ?: failure.javaClass.simpleName)
                    }
                }
            } finally {
                refreshInFlight = false
            }
        }
    }

    private fun scheduleAutoRefresh(immediate: Boolean = false) {
        refreshHandler.removeCallbacks(autoRefreshRunnable)
        if (!activityResumed || token.isBlank()) return
        refreshHandler.postDelayed(
            autoRefreshRunnable,
            if (immediate) 0L else AdminRefreshPolicy.INTERVAL_MS,
        )
    }

    private fun stopAutoRefresh() {
        refreshHandler.removeCallbacks(autoRefreshRunnable)
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

        val battery = state.optJSONObject("battery") ?: JSONObject()
        section("État du routeur")
        info(
            AdminBatteryLabel.format(
                available = battery.optBoolean("available", false),
                percent = battery.optInt("percent", -1),
                charging = battery.optBoolean("charging", false),
            ),
        )

        renderAccounts(state.optJSONArray("accounts") ?: JSONArray())
        renderOffersAndVouchers(
            state.optJSONArray("offers") ?: JSONArray(),
            state.optJSONArray("vouchers") ?: JSONArray(),
        )
        renderDevices(traffic.optJSONArray("portalAuthorizations") ?: JSONArray())
        renderMediaManagement(
            state.optJSONObject("media") ?: JSONObject(),
            state.optJSONArray("accounts") ?: JSONArray(),
        )
        renderPortal(state.optJSONObject("portal") ?: JSONObject())
        renderAdminCredentials(state.optJSONObject("remoteAdmin") ?: JSONObject())
        renderDiagnostics(traffic)
        renderMediaDiagnostics(traffic.optJSONArray("mediaDiagnostics") ?: JSONArray())
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


    private fun renderMediaManagement(media: JSONObject, accounts: JSONArray) {
        section("Shizzi Media")

        val enabled = media.optBoolean("enabled", false)
        val summary = media.optJSONObject("summary") ?: JSONObject()
        val folders = media.optJSONArray("folders") ?: JSONArray()
        val maxFolders = media.optInt("maxFolders", 10)

        info(
            (if (enabled) "Serveur Media : ACTIF" else "Serveur Media : ARRÊTÉ") +
                "\nIndex : " + summary.optInt("total") + " fichier(s)" +
                " · " + summary.optInt("films") + " films" +
                " · " + summary.optInt("series") + " séries" +
                " · " + summary.optInt("music") + " musiques" +
                "\nDossiers : " + folders.length() + "/" + maxFolders,
        )

        val serverRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        serverRow.addView(
            makeButton(if (enabled) "Désactiver Media" else "Activer Media") {
                command("media.enable", JSONObject().put("enabled", !enabled))
            },
            weighted(),
        )
        serverRow.addView(
            makeButton("Scanner Media") {
                command("media.scan", JSONObject())
            },
            weighted(),
        )
        root.addView(serverRow)

        button("Envoyer un fichier au routeur") {
            chooseUploadDestination(folders)
        }
        if (recentUploads.isNotEmpty()) {
            info(
                "Transferts récents :\n" +
                    recentUploads.joinToString("\n") { "• $it" },
            )
        }

        if (folders.length() < maxFolders) {
            button("Ajouter un dossier Media") {
                prompt("Nom du dossier", "Dossier Media") { value ->
                    command(
                        "media.folder.create",
                        JSONObject()
                            .put("name", value)
                            .put("kind", "films"),
                    )
                }
            }
        }

        for (i in 0 until folders.length()) {
            val folder = folders.optJSONObject(i) ?: continue
            val id = folder.optString("id")
            val name = folder.optString("name").ifBlank { "Dossier Media" }
            val kind = folder.optString("kind", "films")
            val folderEnabled = folder.optBoolean("enabled", true)
            val allowed = folder.optJSONArray("allowedAccounts") ?: JSONArray()
            val treeUri = folder.optString("treeUri")
            val accessText = if (allowed.length() == 0) {
                "Tous les comptes"
            } else {
                val labels = mutableListOf<String>()
                for (index in 0 until allowed.length()) {
                    val number = allowed.optString(index)
                    labels += accountLabel(accounts, number)
                }
                labels.joinToString(", ")
            }

            val writable = folder.optBoolean("writable", false)
            info(
                "$name — ${mediaKindLabel(kind)}" +
                    "\n" + (if (folderEnabled) "Actif" else "Désactivé") +
                    " · Accès : $accessText" +
                    "\nSource : " + mediaSourceLabel(treeUri) +
                    "\nÉcriture Admin : " + if (writable) "OUI" else "NON",
            )

            val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row1.addView(
                makeButton("Accès") { chooseMediaAccess(folder, accounts) },
                weighted(),
            )
            row1.addView(
                makeButton("Source") { browseMediaSource(folder, "") },
                weighted(),
            )
            row1.addView(
                makeButton(if (folderEnabled) "Désactiver" else "Activer") {
                    command(
                        "media.folder.update",
                        mediaFolderUpdateParams(
                            folder,
                            enabled = !folderEnabled,
                        ),
                    )
                },
                weighted(),
            )
            root.addView(row1)

            val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row2.addView(
                makeButton("Renommer") {
                    prompt("Nouveau nom", name) { value ->
                        command(
                            "media.folder.update",
                            mediaFolderUpdateParams(folder, name = value),
                        )
                    }
                },
                weighted(),
            )
            row2.addView(
                makeButton("Type → ${mediaKindLabel(nextMediaKind(kind))}") {
                    command(
                        "media.folder.update",
                        mediaFolderUpdateParams(folder, kind = nextMediaKind(kind)),
                    )
                },
                weighted(),
            )
            row2.addView(
                makeButton("Supprimer") {
                    confirm("Supprimer le dossier $name ?") {
                        command("media.folder.delete", JSONObject().put("id", id))
                    }
                },
                weighted(),
            )
            root.addView(row2)
            divider()
        }

        info(
            "Les sources proposées ici sont les emplacements déjà autorisés par Android au routeur. " +
                "Une nouvelle autorisation système SAF reste la seule opération qui peut exiger le téléphone routeur.",
        )
    }

    private data class UploadSourceInfo(
        val name: String,
        val mimeType: String,
        val sizeBytes: Long,
    )

    private fun chooseUploadDestination(folders: JSONArray) {
        val ids = mutableListOf<String>()
        val names = mutableListOf<String>()
        for (index in 0 until folders.length()) {
            val folder = folders.optJSONObject(index) ?: continue
            if (folder.optString("treeUri").isBlank()) continue
            if (!folder.optBoolean("writable", false)) continue
            val id = folder.optString("id")
            if (id.isBlank()) continue
            ids += id
            names += folder.optString("name").ifBlank { "Dossier Media" }
        }
        if (ids.isEmpty()) {
            toast(
                "Aucun dossier n'est autorisé en écriture. " +
                    "Sur le routeur, ouvre Shizzi Media et réautorise une fois la source.",
            )
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Destination sur le routeur")
            .setItems(names.toTypedArray()) { _, which ->
                pendingUploadFolderId = ids[which]
                pendingUploadFolderName = names[which]
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                    )
                }
                startActivityForResult(intent, REQUEST_PICK_UPLOAD_FILE)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun startAdminFileUpload(uri: Uri, folderId: String, folderName: String) {
        val source = runCatching { uploadSourceInfo(uri) }
            .getOrElse {
                toast("Impossible de lire ce fichier.")
                return
            }

        uploadCancelled = false
        stopAutoRefresh()

        val progressText = TextView(this).apply {
            text = "Préparation du transfert…"
            setPadding(0, dp(8), 0, dp(8))
        }
        val progressBar = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal,
        ).apply {
            max = 100
            progress = 0
            isIndeterminate = source.sizeBytes <= 0L
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
            addView(progressText, full())
            addView(progressBar, full())
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Envoi vers $folderName")
            .setView(content)
            .setNegativeButton("Annuler") { _, _ ->
                uploadCancelled = true
            }
            .setCancelable(false)
            .create()
        dialog.show()

        thread(name = "shizzi-admin-file-upload") {
            var transferId = ""
            try {
                bindToWifi()
                val start = request(
                    method = "POST",
                    path = "/files/upload/start",
                    body = JSONObject()
                        .put("folderId", folderId)
                        .put("fileName", source.name)
                        .put("mimeType", source.mimeType)
                        .put("sizeBytes", source.sizeBytes)
                        .toString(),
                    readTimeoutMillis = 30_000,
                )
                if (!start.optBoolean("ok")) {
                    error(start.optString("message", "Le routeur a refusé le transfert."))
                }
                transferId = start.getString("id")
                val chunkBytes = start.optInt("chunkBytes", 1024 * 1024)
                    .coerceIn(64 * 1024, 1024 * 1024)
                val buffer = ByteArray(chunkBytes)
                val digest = MessageDigest.getInstance("SHA-256")
                var offset = start.optLong("receivedBytes", 0L)
                if (offset != 0L) error("Le nouveau transfert n'a pas commencé à zéro.")

                val startedNanos = System.nanoTime()
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Fichier source inaccessible." }
                    while (true) {
                        if (uploadCancelled) throw InterruptedException("Transfert annulé.")
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue

                        digest.update(buffer, 0, count)
                        offset = sendUploadChunkWithRetry(
                            transferId = transferId,
                            offset = offset,
                            bytes = buffer.copyOf(count),
                            progressText = progressText,
                        )

                        val elapsedSeconds =
                            ((System.nanoTime() - startedNanos) / 1_000_000_000.0)
                                .coerceAtLeast(0.001)
                        val mbps = (offset * 8.0 / elapsedSeconds) / 1_000_000.0
                        val percent = if (source.sizeBytes > 0L) {
                            ((offset * 100L) / source.sizeBytes)
                                .coerceIn(0L, 100L)
                                .toInt()
                        } else {
                            0
                        }
                        runOnUiThread {
                            if (source.sizeBytes > 0L) {
                                progressBar.isIndeterminate = false
                                progressBar.progress = percent
                                progressText.text =
                                    "$percent % · " +
                                        String.format("%.1f Mbps", mbps) +
                                        "\n" + formatBytes(offset) +
                                        " / " + formatBytes(source.sizeBytes)
                            } else {
                                progressText.text =
                                    formatBytes(offset) +
                                        " envoyés · " +
                                        String.format("%.1f Mbps", mbps)
                            }
                        }
                    }
                }

                if (source.sizeBytes >= 0L && offset != source.sizeBytes) {
                    error(
                        "Taille envoyée incorrecte : " +
                            formatBytes(offset) + " / " + formatBytes(source.sizeBytes),
                    )
                }
                val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
                runOnUiThread { progressText.text = "Vérification SHA-256 sur le routeur…" }

                val finish = request(
                    method = "POST",
                    path = "/files/upload/finish?id=" +
                        URLEncoder.encode(transferId, "UTF-8"),
                    body = JSONObject().put("sha256", sha256).toString(),
                    readTimeoutMillis = 600_000,
                )
                if (!finish.optBoolean("ok")) {
                    error(finish.optString("message", "Finalisation refusée par le routeur."))
                }

                val savedName = finish.optString("fileName", source.name)
                runOnUiThread {
                    if (recentUploads.size >= 5) recentUploads.removeLast()
                    recentUploads.addFirst(
                        savedName + " · " + formatBytes(offset) + " · " + folderName,
                    )
                    dialog.dismiss()
                    toast("Fichier reçu par le routeur. Scan Media lancé.")
                    scheduleAutoRefresh(immediate = true)
                }
            } catch (cancelled: InterruptedException) {
                if (transferId.isNotBlank()) {
                    runCatching {
                        request(
                            "DELETE",
                            "/files/upload?id=" +
                                URLEncoder.encode(transferId, "UTF-8"),
                        )
                    }
                }
                runOnUiThread {
                    dialog.dismiss()
                    toast("Transfert annulé.")
                    scheduleAutoRefresh(immediate = true)
                }
            } catch (failure: Throwable) {
                if (transferId.isNotBlank()) {
                    runCatching {
                        request(
                            "DELETE",
                            "/files/upload?id=" +
                                URLEncoder.encode(transferId, "UTF-8"),
                        )
                    }
                }
                runOnUiThread {
                    dialog.dismiss()
                    toast(failure.message ?: "Échec du transfert.")
                    scheduleAutoRefresh(immediate = true)
                }
            }
        }
    }

    private fun sendUploadChunkWithRetry(
        transferId: String,
        offset: Long,
        bytes: ByteArray,
        progressText: TextView,
    ): Long {
        val expectedNext = offset + bytes.size
        var lastFailure: Throwable? = null

        for (attempt in 1..UPLOAD_RETRY_COUNT) {
            if (uploadCancelled) throw InterruptedException("Transfert annulé.")
            try {
                bindToWifi()
                val response = requestBytes(
                    method = "PUT",
                    path = "/files/upload/chunk?id=" +
                        URLEncoder.encode(transferId, "UTF-8") +
                        "&offset=$offset",
                    body = bytes,
                    contentType = "application/octet-stream",
                )
                val remote = response.optLong("receivedBytes", -1L)
                if (response.optBoolean("ok") && remote == expectedNext) {
                    return remote
                }
                if (remote == expectedNext) return remote
                if (remote != offset && remote >= 0L) {
                    error("Position de reprise incohérente sur le routeur : $remote.")
                }
                error(response.optString("message", "Bloc refusé par le routeur."))
            } catch (failure: Throwable) {
                lastFailure = failure
                if (uploadCancelled) throw InterruptedException("Transfert annulé.")

                val remote = runCatching {
                    bindToWifi()
                    get(
                        "/files/upload/status?id=" +
                            URLEncoder.encode(transferId, "UTF-8"),
                    ).optLong("receivedBytes", -1L)
                }.getOrDefault(-1L)
                if (remote == expectedNext) return remote
                if (remote != -1L && remote != offset) {
                    error("Position de reprise incohérente sur le routeur : $remote.")
                }

                runOnUiThread {
                    progressText.text =
                        "Wi-Fi interrompu · reprise automatique " +
                            "($attempt/$UPLOAD_RETRY_COUNT)…"
                }
                Thread.sleep(1_000L)
            }
        }
        throw lastFailure ?: IllegalStateException("Transfert interrompu.")
    }

    private fun uploadSourceInfo(uri: Uri): UploadSourceInfo {
        var name = "fichier"
        var size = -1L
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    name = cursor.getString(nameIndex).orEmpty().ifBlank { "fichier" }
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }
        return UploadSourceInfo(
            name = name,
            mimeType = contentResolver.getType(uri) ?: "application/octet-stream",
            sizeBytes = size,
        )
    }

    private fun mediaFolderUpdateParams(
        folder: JSONObject,
        name: String = folder.optString("name"),
        kind: String = folder.optString("kind", "films"),
        enabled: Boolean = folder.optBoolean("enabled", true),
        allowedAccounts: JSONArray = folder.optJSONArray("allowedAccounts") ?: JSONArray(),
    ): JSONObject = JSONObject()
        .put("id", folder.optString("id"))
        .put("name", name)
        .put("kind", kind)
        .put("enabled", enabled)
        .put("allowedAccounts", allowedAccounts)

    private fun chooseMediaAccess(folder: JSONObject, accounts: JSONArray) {
        val folderName = folder.optString("name").ifBlank { "Dossier Media" }
        AlertDialog.Builder(this)
            .setTitle("Accès · $folderName")
            .setItems(arrayOf("Tous les comptes", "Choisir certains comptes…")) { _, which ->
                if (which == 0) {
                    command(
                        "media.folder.update",
                        mediaFolderUpdateParams(folder, allowedAccounts = JSONArray()),
                    )
                } else {
                    chooseSpecificMediaAccounts(folder, accounts)
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun chooseSpecificMediaAccounts(folder: JSONObject, accounts: JSONArray) {
        if (accounts.length() == 0) {
            toast("Aucun compte Shizzi disponible.")
            return
        }

        val allowed = folder.optJSONArray("allowedAccounts") ?: JSONArray()
        val selectedNumbers = mutableSetOf<String>()
        for (index in 0 until allowed.length()) {
            allowed.optString(index).takeIf { it.isNotBlank() }?.let(selectedNumbers::add)
        }

        val numbers = mutableListOf<String>()
        val labels = mutableListOf<String>()
        for (index in 0 until accounts.length()) {
            val account = accounts.optJSONObject(index) ?: continue
            val number = account.optString("number")
            if (number.isBlank()) continue
            numbers += number
            val suffix = if (account.optBoolean("enabled", true)) "" else " · suspendu"
            labels += accountLabel(accounts, number) + suffix
        }

        val checked = BooleanArray(numbers.size) { numbers[it] in selectedNumbers }
        AlertDialog.Builder(this)
            .setTitle("Comptes autorisés")
            .setMultiChoiceItems(labels.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Appliquer") { _, _ ->
                val chosen = JSONArray()
                numbers.indices
                    .filter { checked[it] }
                    .forEach { chosen.put(numbers[it]) }
                if (chosen.length() == 0) {
                    toast("Choisis au moins un compte, ou utilise « Tous les comptes ».")
                } else {
                    command(
                        "media.folder.update",
                        mediaFolderUpdateParams(folder, allowedAccounts = chosen),
                    )
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun browseMediaSource(folder: JSONObject, parentUri: String) {
        commandResult(
            action = "media.browse",
            params = JSONObject().put("parentUri", parentUri),
            refreshOnSuccess = false,
        ) { finished ->
            val payload = finished.optJSONObject("payload") ?: JSONObject()
            showMediaSourceDialog(folder, payload)
        }
    }

    private fun showMediaSourceDialog(folder: JSONObject, payload: JSONObject) {
        val currentUri = payload.optString("currentUri")
        val currentName = payload.optString("currentName").ifBlank { "ce dossier" }
        val entries = payload.optJSONArray("entries") ?: JSONArray()

        val labels = mutableListOf<String>()
        val uris = mutableListOf<String>()
        if (currentUri.isNotBlank()) {
            labels += "✓ Utiliser : $currentName"
            uris += currentUri
        }
        for (index in 0 until entries.length()) {
            val item = entries.optJSONObject(index) ?: continue
            labels += "📁 " + item.optString("name").ifBlank { "Dossier" }
            uris += item.optString("uri")
        }

        if (labels.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Source Media")
                .setMessage(
                    "Aucun emplacement Android autorisé n’est disponible. " +
                        "Il faut autoriser au moins une fois un dossier depuis le téléphone routeur.",
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Choisir la source")
            .setItems(labels.toTypedArray()) { _, which ->
                val chosenUri = uris[which]
                if (currentUri.isNotBlank() && which == 0) {
                    command(
                        "media.folder.source",
                        JSONObject()
                            .put("id", folder.optString("id"))
                            .put("treeUri", chosenUri),
                    )
                } else {
                    browseMediaSource(folder, chosenUri)
                }
            }
            .setNegativeButton("Annuler", null)

        if (folder.optString("treeUri").isNotBlank()) {
            dialog.setNeutralButton("Retirer la source") { _, _ ->
                val folderName = folder.optString("name")
                confirm("Retirer la source de $folderName ?") {
                    command(
                        "media.folder.source",
                        JSONObject()
                            .put("id", folder.optString("id"))
                            .put("treeUri", ""),
                    )
                }
            }
        }
        dialog.show()
    }

    private fun accountLabel(accounts: JSONArray, number: String): String {
        for (index in 0 until accounts.length()) {
            val account = accounts.optJSONObject(index) ?: continue
            if (account.optString("number") != number) continue
            val name = account.optString("name")
            return if (name.isBlank()) number else "$name · $number"
        }
        return "$number · compte introuvable"
    }

    private fun nextMediaKind(kind: String): String = when (kind.lowercase()) {
        "films" -> "series"
        "series" -> "music"
        else -> "films"
    }

    private fun mediaKindLabel(kind: String): String = when (kind.lowercase()) {
        "series" -> "Séries"
        "music" -> "Musique"
        else -> "Films"
    }

    private fun mediaSourceLabel(raw: String): String {
        if (raw.isBlank() || raw == "null") return "Non configurée"
        return runCatching {
            Uri.parse(raw).lastPathSegment
                ?.substringAfterLast(':')
                ?.replace("%2F", "/")
                ?.ifBlank { null }
        }.getOrNull() ?: "Source Android autorisée"
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

    private fun renderMediaDiagnostics(events: JSONArray) {
        section("Diagnostic Media")
        if (events.length() == 0) {
            info(
                "Aucun accès Media enregistré pour cette session. " +
                    "Sur un appareil client déjà connecté à un compte Shizzi, ouvrez Media puis appuyez sur Actualiser.",
            )
            return
        }

        val start = maxOf(0, events.length() - 10)
        for (i in events.length() - 1 downTo start) {
            val event = events.optJSONObject(i) ?: continue
            val authenticated = if (event.optBoolean("accountAuthenticated")) "OUI" else "NON"
            val backend = if (event.optBoolean("backendConnected")) "OUI" else "NON"
            val account = event.optString("accountNumber").ifBlank { "—" }
            val target = event.optString("proxyTarget").ifBlank { "—" }
            val backendAddress = event.optString("backend").ifBlank { "—" }
            val result = event.optString("result").ifBlank { "—" }
            val error = event.optString("error")
            val copied = event.optLong("bytesCopied")
            val atMillis = event.optLong("atMillis")
            val whenText = if (atMillis > 0L) {
                java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date(atMillis))
            } else {
                "heure —"
            }

            val detail = buildString {
                append(whenText).append(" · ").append(event.optString("path").ifBlank { "—" })
                append("\nClient : ").append(event.optString("clientIp").ifBlank { "—" })
                append("\nCompte : ").append(account).append(" · authentifié : ").append(authenticated)
                append("\nProxy : ").append(target)
                append("\nBackend : ").append(backendAddress).append(" · connecté : ").append(backend)
                append("\nRésultat : ").append(result)
                if (copied > 0L) append("\nTransféré : ").append(formatBytes(copied))
                if (error.isNotBlank()) append("\nErreur : ").append(error)
            }
            info(detail)
            divider()
        }
        info("Les 10 événements Media les plus récents sont affichés. Utilisez Actualiser après avoir reproduit le problème.")
    }

    private fun command(action: String, params: JSONObject) {
        commandResult(action, params, refreshOnSuccess = true)
    }

    private fun commandResult(
        action: String,
        params: JSONObject,
        refreshOnSuccess: Boolean,
        onSuccess: ((JSONObject) -> Unit)? = null,
    ) {
        runNetwork {
            val start = request(
                "POST",
                "/command",
                JSONObject().put("action", action).put("params", params).toString(),
            )
            if (!start.optBoolean("ok")) error(start.optString("message", "Commande refusée."))
            val id = start.getString("id")
            var result: JSONObject? = null
            for (attempt in 0 until 30) {
                Thread.sleep(300)
                val candidate = get("/result?id=" + URLEncoder.encode(id, "UTF-8"))
                if (!candidate.optBoolean("pending", true)) {
                    result = candidate
                    break
                }
            }
            val finished = result ?: error("La commande est toujours en attente.")
            val success = finished.optBoolean("success")
            val message = finished.optString("message")
            runOnUiThread {
                toast(message.ifBlank { if (success) "OK" else "Échec" })
                if (success) onSuccess?.invoke(finished)
            }
            if (success && refreshOnSuccess) {
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
            runOnUiThread {
                stopAutoRefresh()
                showLogin("Session Admin fermée.")
            }
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
        readTimeoutMillis: Int = 8_000,
    ): JSONObject {
        val connection = URL(BASE_URL + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 5_000
        connection.readTimeout = readTimeoutMillis
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

    private fun requestBytes(
        method: String,
        path: String,
        body: ByteArray,
        contentType: String,
    ): JSONObject {
        val connection = URL(BASE_URL + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 8_000
        connection.readTimeout = 90_000
        connection.useCaches = false
        connection.setRequestProperty("Accept", "application/json")
        if (token.isNotBlank()) {
            connection.setRequestProperty("X-Shizzi-Admin-Token", token)
        }
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", contentType)
        connection.setFixedLengthStreamingMode(body.size)
        connection.outputStream.use { it.write(body) }

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
        const val REQUEST_PICK_UPLOAD_FILE = 4408
        const val UPLOAD_RETRY_COUNT = 60
    }
}
