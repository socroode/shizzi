package dev.shizzi.accounts

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.InputType
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URLEncoder
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var connectivity: ConnectivityManager
    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView

    private var boundWifi: Network? = null
    @Volatile private var api: AdminApi? = null
    private val token: String get() = api?.token.orEmpty()
    private var capabilities = AdminCapabilities(JSONObject())
    private val unavailableActions = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var routerName: String = "Shizzi"
    private var lastState: JSONObject? = null
    private var routerBatteryView: TextView? = null
    private var lastRenderedStateJson: String = ""
    private var showAccountCreation = false
    private val liveTraffic = AccountLiveTraffic()
    private var liveRates: Map<String, AccountLiveRate?> = emptyMap()
    private data class AccountCard(
        val heading: TextView,
        val label: String,
        val planText: TextView,
        val rateText: TextView,
        val usageText: TextView,
        val sessionText: TextView,
        val detailsButton: Button,
        var session: AccountSessionSummary? = null,
    )
    private val accountCards = linkedMapOf<String, AccountCard>()
    private var activityResumed = false
    @Volatile private var refreshInFlight = false
    @Volatile private var loginInFlight = false
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

    override fun onResume() {
        super.onResume()
        activityResumed = true
        bindToWifi()
        if (token.isNotBlank()) scheduleAutoRefresh(immediate = true)
    }

    override fun onPause() {
        activityResumed = false
        stopAutoRefresh()
        clearLiveTraffic()
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
                selectWifi(active)
                return true
            }
        }
        for (network in connectivity.allNetworks) {
            val caps = connectivity.getNetworkCapabilities(network)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                selectWifi(network)
                return true
            }
        }
        if (boundWifi != null) {
            invalidateSession()
            boundWifi = null
            connectivity.bindProcessToNetwork(null)
        }
        return false
    }

    private fun selectWifi(network: Network) {
        if (boundWifi != null && boundWifi != network) invalidateSession()
        connectivity.bindProcessToNetwork(network)
        boundWifi = network
    }

    private fun invalidateSession() {
        api?.clearSession()
        api = null
        lastState = null
        runOnUiThread { showLogin("Le Wi-Fi a changé. Connectez-vous au routeur choisi.") }
    }

    private fun showLogin(message: String = "") {
        accountCards.clear()
        clearLiveTraffic()
        lastState = null
        stopAutoRefresh()
        lastRenderedStateJson = ""
        root.removeAllViews()
        routerBatteryView = null
        title("Shizzi Comptes")
        info(
            "Comptes, forfaits et débits Shizzi. " +
                "Connectez ce téléphone au Wi-Fi Shizzi à administrer. " +
                "Détection automatique des anciennes et nouvelles versions.",
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
        if (loginInFlight) return
        loginInFlight = true
        api?.clearSession()
        api = null
        unavailableActions.clear()
        runNetwork {
            try {
                if (!bindToWifi()) error("Aucun Wi-Fi connecté.")
                val wifi = boundWifi ?: error("Aucun Wi-Fi connecté.")
                val (connected, result) = AdminConnector.connect(
                    username, password,
                    secureFactory = {
                        val pin = RouterTls.pair(this, wifi)
                        AdminApi(AdminConnector.HTTPS, secure = true) { url ->
                            RouterTls.connection(url.toString(), pin, wifi)
                        }
                    },
                    legacyFactory = {
                        AdminApi(AdminConnector.HTTP, secure = false) { url ->
                            wifi.openConnection(url) as HttpURLConnection
                        }
                    },
                )
                check(boundWifi == wifi) { "Le Wi-Fi a changé. Reconnectez-vous au routeur." }
                api = connected
                routerName = result.optString("routerName", "Shizzi")
                refresh()
                runOnUiThread { scheduleAutoRefresh() }
            } finally { loginInFlight = false }
        }
    }

    private fun refresh(manual: Boolean = true) {
        if (refreshInFlight) return
        val session = api ?: return
        refreshInFlight = true
        thread {
            try {
                val response = session.request("GET", "/state")
                if (api !== session) return@thread
                if (!response.optBoolean("ok")) {
                    api?.clearSession()
                    runOnUiThread { showLogin("Session Admin expirée.") }
                    return@thread
                }
                lastState = response
                val receivedAt = SystemClock.elapsedRealtime()
                runOnUiThread {
                    if (api !== session) return@runOnUiThread
                    sampleAccountRates(response, receivedAt)
                    val snapshot = AccountDashboardLayout.key(response, true)
                    val shouldRender = snapshot != lastRenderedStateJson &&
                        (manual || (hasWindowFocus() && !hasPendingInput(root)))
                    if (shouldRender) {
                        val oldScrollY = scroll.scrollY
                        showDashboard(response)
                        lastRenderedStateJson = snapshot
                        scroll.post { scroll.scrollTo(0, oldScrollY) }
                    }
                    // The router battery is refreshed in-place even when the form stays open.
                    updateRouterBattery(response)
                    // Presence continues refreshing while a form is being edited.
                    updateAccountSessions(response.optJSONObject("traffic"))
                    updateAccountPlans(response.optJSONObject("state"))
                    updateAccountRates(response.optJSONObject("traffic"))
                }
            } catch (failure: Throwable) {
                if (api !== session) return@thread
                if (failure is AdminApiException && failure.status == 401) {
                    runOnUiThread { if (api === session) showLogin("Session Admin expirée.") }
                } else {
                    runOnUiThread {
                        if (api !== session) return@runOnUiThread
                        updateAccountSessions(null)
                        updateRouterBattery(null)
                        updateAccountPlans(null)
                        clearLiveTraffic()
                        updateAccountRates(null)
                        if (manual) toast(failure.message ?: failure.javaClass.simpleName)
                    }
                }
            } finally {
                refreshInFlight = false
            }
        }
    }

    private fun hasPendingInput(view: View): Boolean = when (view) {
        is EditText -> view.text.toString() != (view.tag as? String).orEmpty() ||
            (view.hasFocus() && view.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true)
        is android.view.ViewGroup -> (0 until view.childCount).any { hasPendingInput(view.getChildAt(it)) }
        else -> false
    }

    private fun scheduleAutoRefresh(immediate: Boolean = false) {
        refreshHandler.removeCallbacks(autoRefreshRunnable)
        if (!activityResumed || token.isBlank()) return
        refreshHandler.postDelayed(
            autoRefreshRunnable,
            if (immediate) 0L else AdminRefreshPolicy.LIVE_INTERVAL_MS,
        )
    }

    private fun stopAutoRefresh() {
        refreshHandler.removeCallbacks(autoRefreshRunnable)
    }

    private fun updateRouterBattery(response: JSONObject?) {
        routerBatteryView?.text = RouterBatteryLabel.fromResponse(response)
    }

    private fun showDashboard(response: JSONObject) {
        lastState = response
        accountCards.clear()
        root.removeAllViews()
        routerName = response.optString("routerName", routerName)
        title("Shizzi Comptes — $routerName")
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(makeButton("Actualiser") { refresh() }, weighted())
        header.addView(makeButton("Déconnexion") { logout() }, weighted())
        root.addView(header)
        routerBatteryView = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(8), 0, dp(8))
            tag = "router:battery"
        }
        root.addView(routerBatteryView)
        updateRouterBattery(response)
        info("Comptes · débits mesurés chaque seconde lorsque cet écran est visible.")
        val state = response.optJSONObject("state") ?: JSONObject()
        capabilities = AdminCapabilities(response)
        if (capabilities.accounts) renderAccounts(state.getJSONArray("accounts"))
        else info("Comptes indisponibles sur cette version de Shizzi.")
        updateAccountSessions(response.optJSONObject("traffic"))
        updateAccountPlans(response.optJSONObject("state"))
        updateAccountRates(response.optJSONObject("traffic"))
    }

    private fun renderAccounts(accounts: JSONArray) {
        section("Comptes")
        button(if (showAccountCreation) "Fermer la création de compte" else "Créer un compte") {
            showAccountCreation = !showAccountCreation
            lastRenderedStateJson = ""
            lastState?.let { showDashboard(it) }
        }
        if (showAccountCreation) {
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
        }

        for (i in 0 until accounts.length()) {
            val account = accounts.optJSONObject(i) ?: continue
            val n = account.optString("number")
            val label = account.optString("name").ifBlank { "Compte" }
            val enabled = account.optBoolean("enabled", true)
            val heading = TextView(this).apply {
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(6), 0, dp(4))
            }
            heading.tag = "account:$n:heading"
            root.addView(heading)
            val rateText = TextView(this).apply {
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(4), 0, dp(4))
                tag = "account:$n:rate"
            }
            root.addView(rateText)
            val planText = TextView(this).apply {
                textSize = 14f
                setPadding(0, dp(4), 0, dp(6))
                tag = "account:$n:plan"
            }
            root.addView(planText)
            val usageText = TextView(this).apply {
                textSize = 14f
                setPadding(0, dp(4), 0, dp(8))
                tag = "account:$n:usage"
            }
            root.addView(usageText)
            val sessionText = TextView(this).apply {
                textSize = 14f
                setPadding(0, 0, 0, dp(6))
                tag = "account:$n:session"
            }
            root.addView(sessionText)
            val details = makeButton("Détails") { showAccountSessionDetails(n) }.apply {
                tag = "account:$n:details"
            }
            accountCards[n] = AccountCard(heading, "$label — N° $n", planText, rateText, usageText, sessionText, details)
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
            row.addView(details, weighted())
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

    private fun updateAccountSessions(traffic: JSONObject?) {
        for ((number, card) in accountCards) {
            val summary = AccountSessionSummary.fromTraffic(number, traffic)
            card.session = summary
            val prefix = "${card.label} · "
            val color = when (summary.connection) {
                AccountConnectionStatus.CONNECTED -> 0xFF1B5E20.toInt()
                AccountConnectionStatus.DISCONNECTED -> 0xFFB71C1C.toInt()
                AccountConnectionStatus.CHECKING -> 0xFF795500.toInt()
                AccountConnectionStatus.UNAVAILABLE -> 0xFF616161.toInt()
            }
            card.heading.text = SpannableString(prefix + summary.headingLabel).apply {
                setSpan(ForegroundColorSpan(color), prefix.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            card.sessionText.text = summary.information
            card.detailsButton.isEnabled = summary.sessions.isNotEmpty()
        }
    }

    private fun updateAccountPlans(state: JSONObject?) {
        val accounts = state?.optJSONArray("accounts")
        val byNumber = if (accounts == null) emptyMap() else (0 until accounts.length())
            .mapNotNull { accounts.optJSONObject(it) }.associateBy { it.optString("number") }
        val now = System.currentTimeMillis()
        for ((number, card) in accountCards) {
            val account = byNumber[number]
            card.planText.text = AccountPlanLabel.format(
                account, state?.optJSONArray("offers"), state?.optJSONArray("vouchers"),
                now, ::formatBytes, ::formatDateTime,
            )
            card.usageText.text = if (account == null) "Trafic cumulé : information indisponible" else {
                val enabled = account.optBoolean("enabled", true)
                val mediaUntil = account.optLong("mediaUntilMillis")
                (if (enabled) "Compte autorisé" else "Compte suspendu") + " · trafic cumulé " +
                    formatBytes(account.optLong("totalUpBytes") + account.optLong("totalDownBytes")) +
                    if (account.has("mediaUntilMillis")) "\n" +
                        (if (enabled && mediaUntil > now) "Media actif jusqu'au " + formatDateTime(mediaUntil) else "Media inactif") else ""
            }
        }
    }

    private fun sampleAccountRates(response: JSONObject, elapsedMillis: Long) {
        val accounts = response.optJSONObject("state")?.optJSONArray("accounts")
        val numbers = if (accounts == null) emptySet() else (0 until accounts.length())
            .mapNotNull { accounts.optJSONObject(it)?.optString("number")?.takeIf { it.isNotBlank() } }.toSet()
        liveRates = liveTraffic.sample(response.optJSONObject("traffic"), numbers, elapsedMillis)
    }

    private fun clearLiveTraffic() {
        liveTraffic.clear()
        liveRates = emptyMap()
    }

    private fun updateAccountRates(traffic: JSONObject?) {
        for ((number, card) in accountCards) {
            card.rateText.text = when {
                traffic == null -> "Débit : information indisponible"
                liveRates[number] != null -> liveRates.getValue(number)!!.label()
                !liveTraffic.hasCounters(traffic, number) -> "Débit : non fourni par ce Shizzi"
                else -> "Débit : mesure en cours…"
            }
        }
    }

    private fun showAccountSessionDetails(number: String) {
        val summary = accountCards[number]?.session ?: return
        if (summary.sessions.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Détails de l'appareil")
            .setMessage(summary.details)
            .setPositiveButton("Fermer", null)
            .show()
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
        if (!AccountCommandScope.supports(action) || !capabilities.supports(action) || action in unavailableActions) {
            toast("Cette fonction n'est pas disponible sur ce Shizzi.")
            return
        }
        val session = api ?: return
        runNetwork {
            val start = try { session.request(
                "POST",
                "/command",
                JSONObject().put("action", action).put("params", params).toString(),
            ) } catch (failure: AdminApiException) {
                if (failure.status == 403) unavailableActions.add(action)
                throw failure
            }
            if (!start.optBoolean("ok")) error(start.optString("message", "Commande refusée."))
            val id = start.getString("id")
            var result: JSONObject? = null
            for (attempt in 0 until 30) {
                Thread.sleep(300)
                if (api !== session) return@runNetwork
                val candidate = session.request("GET", "/result?id=" + URLEncoder.encode(id, "UTF-8"))
                if (!candidate.optBoolean("pending", true)) {
                    result = candidate
                    break
                }
            }
            val finished = result ?: error("La commande est toujours en attente.")
            val success = finished.optBoolean("success")
            val message = finished.optString("message")
            runOnUiThread {
                if (api !== session) return@runOnUiThread
                toast(message.ifBlank { if (success) "OK" else "Échec" })
                if (success) onSuccess?.invoke(finished)
            }
            if (success && refreshOnSuccess && api === session) {
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
            api?.clearSession()
            runOnUiThread {
                stopAutoRefresh()
                showLogin("Session Admin fermée.")
            }
        }
    }

    private fun request(
        method: String,
        path: String,
        body: String? = null,
        contentType: String = "application/json",
        authenticated: Boolean = true,
        readTimeoutMillis: Int = 8_000,
    ): JSONObject = (api ?: error("Connectez-vous au compte Admin.")).request(
        method, path, body, contentType, authenticated, readTimeoutMillis,
    )

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
            tag = initial
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

    private fun formatDateTime(epochMillis: Long): String =
        java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.SHORT,
            java.text.DateFormat.SHORT,
        ).format(java.util.Date(epochMillis))

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format("%.2f Go", bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> String.format("%.1f Mo", bytes / 1_000_000.0)
        else -> "$bytes o"
    }

}
