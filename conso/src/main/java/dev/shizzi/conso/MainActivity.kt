package dev.shizzi.conso

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import android.widget.ScrollView
import android.graphics.Color
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import kotlin.concurrent.thread

class MainActivity : Activity() {

    @Volatile private var pendingNotificationAnswer = ""
    private var enableAfterNotificationGrant = false
    private var receptionToggle: Button? = null
    private var receptionStatus: TextView? = null
    private lateinit var root: LinearLayout
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var menu: View
    private lateinit var connectivityManager: ConnectivityManager
    private var boundWifiNetwork: Network? = null
    private var mediaBaseUrl: String? = null
    private var requestedBaseUrl: String? = null
    @Volatile private var routerPin = ""
    private var requestedLabel: String = "Shizzi"
    private var fullscreenView: View? = null
    private var fullscreenContainer: FrameLayout? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var previousOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var pendingWebPermissionRequest: PermissionRequest? = null
    private var pendingWebPermissionResources: Array<String> = emptyArray()
    @Volatile private var secureChatActive = false
    @Volatile private var callTransportActive = false
    private var chatBridgeInstalled = false
    private val chatBridge = ShizziNativeChatBridge()
    private val callToneHandler = Handler(Looper.getMainLooper())
    private var incomingRingtone: Ringtone? = null
    private var outgoingTone: ToneGenerator? = null
    private var ringbackPhaseOn = false
    private val ringbackLoop = object : Runnable {
        override fun run() {
            val tone = outgoingTone ?: return
            if (ringbackPhaseOn) {
                tone.stopTone()
                ringbackPhaseOn = false
                callToneHandler.postDelayed(this, 2_500L)
            } else {
                tone.startTone(ToneGenerator.TONE_SUP_RINGTONE, 1_000)
                ringbackPhaseOn = true
                callToneHandler.postDelayed(this, 1_200L)
            }
        }
    }
    private var wifiCallbackRegistered = false
    private val wifiNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val caps = connectivityManager.getNetworkCapabilities(network)
            if (LocalMediaWebSupport.shouldKeepNetworkAvailable(
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
                )
            ) {
                runOnUiThread {
                    if (::webView.isInitialized) webView.setNetworkAvailable(true)
                }
            }
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            if (LocalMediaWebSupport.shouldKeepNetworkAvailable(
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                )
            ) {
                runOnUiThread {
                    if (::webView.isInitialized) webView.setNetworkAvailable(true)
                }
            }
        }

        override fun onLost(network: Network) {
            if (network != boundWifiNetwork) return
            runOnUiThread {
                val replacement = findWifiNetwork()
                if (replacement != null) {
                    bindPortalToWifi()
                } else if (::webView.isInitialized) {
                    webView.setNetworkAvailable(false)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        buildUi()
        registerLocalWifiCallback()
        detectMedia()
        handleCallNotification(intent)
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(7, 17, 31))
            setPadding(dp(20), dp(28), dp(20), dp(20))
        }

        val menuContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL

            addView(TextView(this@MainActivity).apply {
                text = "Shizzi+"
                textSize = 34f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(6))
            })

            addView(TextView(this@MainActivity).apply {
                text = "Compte · Conso · Recharge · Media · Messagerie · Appels"
                textSize = 15f
                setTextColor(Color.rgb(148, 163, 184))
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(26))
            })

            addView(sectionLabel("MON ACCÈS"))
            addView(primaryButton("Ouvrir ma connexion compte") {
                openPortal(PORTAL_URL)
            })
            addView(primaryButton("Ma consommation / mon forfait") {
                openPortal(PORTAL_URL)
            })
            addView(primaryButton("Recharger avec un voucher") {
                openPortal(PORTAL_URL)
            })

            addView(sectionLabel("SHIZZI MEDIA"))
            addView(primaryButton("Films · Séries · Musique") {
                openMedia()
            })

            addView(TextView(this@MainActivity).apply {
                text = "Shizzi Media passe par le portail sécurisé et nécessite un compte Shizzi connecté."
                textSize = 13f
                setTextColor(Color.rgb(148, 163, 184))
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, 0)
            })

            addView(sectionLabel("MESSAGERIE SHIZZI"))
            addView(primaryButton("Messages · Appels vocaux · Vidéo") {
                openSecureChat()
            })
            addView(TextView(this@MainActivity).apply {
                text = "Messagerie locale entre comptes Shizzi. Les messages restent sur le routeur et n'utilisent pas le quota Internet."
                textSize = 13f
                setTextColor(Color.rgb(148, 163, 184))
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, dp(8))
            })

            addView(sectionLabel("RÉCEPTION DES APPELS"))
            receptionStatus = TextView(this@MainActivity).apply {
                textSize = 13f
                setTextColor(Color.rgb(148, 163, 184))
                setPadding(0, dp(8), 0, dp(8))
            }
            addView(receptionStatus)
            receptionToggle = primaryButton("Activer les appels en arrière-plan") { toggleCallReception() }
            addView(receptionToggle)
            addView(primaryButton("Autoriser la réception écran éteint") { configureCallBattery() })

            addView(primaryButton("Actualiser la détection Shizzi") {
                detectMedia(showFeedback = true)
            })
        }

        menu = ScrollView(this).apply { isFillViewport = true; addView(menuContent) }

        progress = ProgressBar(this).apply {
            visibility = View.GONE
        }

        status = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(148, 163, 184))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
            visibility = View.GONE
        }

        webView = WebView(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.rgb(7, 17, 31))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest?) {
                    request ?: return
                    runOnUiThread { handleWebPermissionRequest(request) }
                }

                override fun onPermissionRequestCanceled(request: PermissionRequest?) {
                    if (request != null && pendingWebPermissionRequest === request) {
                        pendingWebPermissionRequest = null
                        pendingWebPermissionResources = emptyArray()
                    }
                }

                override fun onShowCustomView(
                    view: View?,
                    callback: CustomViewCallback?,
                ) {
                    if (view == null || this@MainActivity.fullscreenView != null) {
                        callback?.onCustomViewHidden()
                        return
                    }
                    enterVideoFullscreen(view, callback)
                }

                override fun onHideCustomView() {
                    exitVideoFullscreen()
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
                    val encoded = error?.certificate?.let(android.net.http.SslCertificate::saveState)?.getByteArray("x509-certificate")
                    val valid = runCatching {
                        val target = URL(error?.url.orEmpty())
                        val certificate = java.security.cert.CertificateFactory.getInstance("X.509")
                            .generateCertificate(encoded!!.inputStream()) as java.security.cert.X509Certificate
                        certificate.checkValidity()
                        target.protocol == "https" && target.host == PORTAL_HOST &&
                            routerPin.isNotBlank() && RouterTls.fingerprint(certificate.encoded) == routerPin
                    }.getOrDefault(false)
                    if (valid) handler?.proceed() else handler?.cancel()
                }

                override fun onPageStarted(
                    view: WebView?,
                    url: String?,
                    favicon: android.graphics.Bitmap?,
                ) {
                    if (!belongsToRequestedTarget(url)) return
                    this@MainActivity.webView.setNetworkAvailable(true)
                    this@MainActivity.webView.visibility = View.INVISIBLE
                    this@MainActivity.progress.visibility = View.VISIBLE
                    this@MainActivity.status.visibility = View.VISIBLE
                    this@MainActivity.status.text = "Ouverture de $requestedLabel…"
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (!belongsToRequestedTarget(url)) return
                    this@MainActivity.webView.setNetworkAvailable(true)
                    if (requestedLabel == "Shizzi Media") {
                        view?.evaluateJavascript(LocalMediaWebSupport.fullscreenScript, null)
                    }
                    this@MainActivity.progress.visibility = View.GONE
                    this@MainActivity.status.visibility = View.GONE
                    this@MainActivity.webView.visibility = View.VISIBLE
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    if (request?.isForMainFrame != true) return
                    if (!belongsToRequestedTarget(request.url?.toString())) return
                    showNavigationFailure(
                        "Impossible d'ouvrir $requestedLabel",
                        error?.description?.toString().orEmpty(),
                    )
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: android.webkit.WebResourceResponse?,
                ) {
                    if (request?.isForMainFrame != true) return
                    if (!belongsToRequestedTarget(request.url?.toString())) return
                    val code = errorResponse?.statusCode ?: 0
                    if (requestedLabel == "Shizzi Media" && code == 401) {
                        return
                    }
                    showNavigationFailure(
                        "Erreur HTTP pour $requestedLabel",
                        if (code > 0) "Code HTTP $code" else "",
                    )
                }
            }
        }

        root.addView(
            menu,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        root.addView(progress)
        root.addView(status)
        root.addView(
            webView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        setContentView(root)
    }

    private fun handleWebPermissionRequest(request: PermissionRequest) {
        val origin = request.origin
        val trusted = SecureChatWebSupport.isTrustedMediaOrigin(
            origin.scheme,
            origin.host,
        )
        if (!trusted) {
            request.deny()
            return
        }

        val requestedResources = request.resources.filter { resource ->
            resource == PermissionRequest.RESOURCE_AUDIO_CAPTURE ||
                resource == PermissionRequest.RESOURCE_VIDEO_CAPTURE
        }
        if (requestedResources.isEmpty()) {
            request.deny()
            return
        }

        val requiredPermissions = requestedResources.mapNotNull { resource ->
            when (resource) {
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
                else -> null
            }
        }.distinct()

        val missing = requiredPermissions.filter { permission ->
            checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            request.grant(requestedResources.toTypedArray())
            return
        }

        pendingWebPermissionRequest?.deny()
        pendingWebPermissionRequest = request
        pendingWebPermissionResources = requestedResources.toTypedArray()
        requestPermissions(missing.toTypedArray(), REQUEST_CALL_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECEPTION_NOTIFICATIONS) {
            if (enableAfterNotificationGrant && CallReceptionService.notificationsAllowed(this)) enableCallReception()
            else Toast.makeText(this, "Autorise les notifications pour recevoir les appels.", Toast.LENGTH_LONG).show()
            enableAfterNotificationGrant = false
            updateReceptionUi()
            return
        }
        if (requestCode != REQUEST_CALL_PERMISSIONS) return

        val request = pendingWebPermissionRequest
        val resources = pendingWebPermissionResources
        pendingWebPermissionRequest = null
        pendingWebPermissionResources = emptyArray()

        if (request == null) return

        val audioOk =
            PermissionRequest.RESOURCE_AUDIO_CAPTURE !in resources ||
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val videoOk =
            PermissionRequest.RESOURCE_VIDEO_CAPTURE !in resources ||
                checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

        if (audioOk && videoOk) {
            request.grant(resources)
        } else {
            request.deny()
        }
    }

    private data class SecureChatPage(
        val html: String? = null,
        val loginRequired: Boolean = false,
        val error: String = "",
    )

    private inner class ShizziNativeChatBridge {
        @JavascriptInterface
        fun startIncomingRingtone() {
            callTransportActive = true
            runOnUiThread { if (!CallReceptionService.ready) startIncomingCallAlert() }
        }

        @JavascriptInterface
        fun consumeIncomingAnswer(callId: String): Boolean {
            if (!secureChatActive || callId.isBlank() || pendingNotificationAnswer != callId) return false
            pendingNotificationAnswer = ""
            return true
        }

        @JavascriptInterface
        fun startOutgoingRingback() {
            callTransportActive = true
            runOnUiThread { startOutgoingCallTone() }
        }

        @JavascriptInterface
        fun stopCallTone() {
            runOnUiThread { stopAllCallTones(); CallReceptionService.silenceCurrent() }
        }

        @JavascriptInterface
        fun markCallTransportActive() {
            callTransportActive = true
        }

        @JavascriptInterface
        fun markCallTransportIdle() {
            callTransportActive = false
            runOnUiThread {
                if (!secureChatActive) {
                    stopAllCallTones()
                    removeSecureChatBridge()
                    if (webView.visibility != View.VISIBLE) {
                        releaseWifiBinding()
                    }
                }
            }
        }

        @JavascriptInterface
        fun request(path: String, method: String, body: String): String {
            val callRequest = SecureChatWebSupport.isCallApiPath(path)
            if (!secureChatActive && !callRequest) {
                return bridgeError("Messagerie Shizzi+ inactive.")
            }
            if (callRequest) {
                // An in-flight call must survive a UI state transition.
                callTransportActive = true
            }

            val target = SecureChatWebSupport.apiUrl(path)
                ?: return bridgeError("Requête locale refusée.")
            val verb = method.trim().uppercase()
            if (verb != "GET" && verb != "POST") {
                return bridgeError("Méthode locale refusée.")
            }

            val network = ensureCallWifiNetwork()
                ?: return bridgeError("Wi-Fi Shizzi indisponible.")

            return runCatching {
                val connection = network.openConnection(URL(target)) as HttpURLConnection
                connection.connectTimeout = 3_000
                connection.readTimeout = 10_000
                connection.useCaches = false
                connection.requestMethod = verb
                connection.setRequestProperty("Accept", "application/json")

                if (verb == "POST") {
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { output -> output.write(bytes) }
                }

                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = stream
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { reader -> reader.readText() }
                    .orEmpty()
                connection.disconnect()

                if (response.isBlank()) {
                    bridgeError("Réponse locale vide.")
                } else {
                    response
                }
            }.getOrElse {
                bridgeError("Messagerie locale indisponible.")
            }
        }
    }

    private fun startIncomingCallAlert() {
        stopAllCallTones()
        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        incomingRingtone = RingtoneManager.getRingtone(this, uri)?.apply {
            audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            isLooping = true
            play()
        }
    }

    private fun startOutgoingCallTone() {
        stopAllCallTones()
        outgoingTone = ToneGenerator(AudioManager.STREAM_MUSIC, 72)
        ringbackPhaseOn = false
        callToneHandler.post(ringbackLoop)
    }

    private fun stopAllCallTones() {
        callToneHandler.removeCallbacks(ringbackLoop)
        ringbackPhaseOn = false

        runCatching { incomingRingtone?.stop() }
        incomingRingtone = null

        runCatching { outgoingTone?.stopTone() }
        runCatching { outgoingTone?.release() }
        outgoingTone = null
    }

    private fun bridgeError(message: String): String =
        JSONObject()
            .put("ok", false)
            .put("message", message)
            .toString()

    private fun installSecureChatBridge() {
        if (chatBridgeInstalled) return
        webView.addJavascriptInterface(chatBridge, "ShizziNativeBridge")
        chatBridgeInstalled = true
    }

    private fun removeSecureChatBridge() {
        if (!chatBridgeInstalled || !::webView.isInitialized) return
        webView.removeJavascriptInterface("ShizziNativeBridge")
        chatBridgeInstalled = false
    }

    private fun deactivateSecureChat(force: Boolean = false) {
        secureChatActive = false
        if (force || !callTransportActive) {
            callTransportActive = false
            stopAllCallTones()
            removeSecureChatBridge()
        }
    }

    private fun fetchSecureChatPage(): SecureChatPage {
        val network = boundWifiNetwork ?: findWifiNetwork()
            ?: return SecureChatPage(error = "Wi-Fi Shizzi indisponible.")

        return runCatching {
            val connection = network.openConnection(URL(SecureChatWebSupport.PORTAL_CHAT_URL)) as HttpURLConnection
            connection.connectTimeout = 3_000
            connection.readTimeout = 10_000
            connection.useCaches = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "text/html")

            val code = connection.responseCode
            val loginRequired =
                connection.getHeaderField("X-Shizzi-Chat-Auth")
                    ?.equals("required", ignoreCase = true) == true
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val html = stream
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { reader -> reader.readText() }
                .orEmpty()
            connection.disconnect()

            when {
                loginRequired -> SecureChatPage(loginRequired = true)
                code !in 200..299 -> SecureChatPage(error = "Erreur HTTP $code.")
                html.isBlank() -> SecureChatPage(error = "Page de messagerie vide.")
                else -> SecureChatPage(html = html)
            }
        }.getOrElse {
            SecureChatPage(error = "Messagerie Shizzi indisponible.")
        }
    }

    private fun openSecureChat() {
        requestedBaseUrl = SecureChatWebSupport.SECURE_BASE_URL
        requestedLabel = "Messagerie Shizzi"
        secureChatActive = true
        installSecureChatBridge()

        menu.visibility = View.GONE
        webView.stopLoading()
        webView.visibility = View.INVISIBLE
        progress.visibility = View.VISIBLE
        status.visibility = View.VISIBLE
        status.text = "Ouverture de la messagerie sécurisée…"

        if (!bindPortalToWifi()) {
            deactivateSecureChat(force = true)
            showNavigationFailure(
                "Aucun réseau Wi-Fi Shizzi utilisable n'a été trouvé.",
                "",
            )
            return
        }

        thread {
            val page = fetchSecureChatPage()
            runOnUiThread {
                if (!secureChatActive) return@runOnUiThread
                when {
                    page.loginRequired -> {
                        deactivateSecureChat(force = true)
                        showNavigationFailure(
                            "Compte Shizzi requis.",
                            " Ouvre d'abord ta connexion compte sur cet appareil.",
                        )
                    }
                    page.html == null -> {
                        deactivateSecureChat(force = true)
                        showNavigationFailure(
                            "Impossible d'ouvrir la messagerie Shizzi.",
                            page.error,
                        )
                    }
                    else -> {
                        webView.clearHistory()
                        webView.loadDataWithBaseURL(
                            SecureChatWebSupport.SECURE_BASE_URL,
                            page.html,
                            "text/html",
                            null,
                            SecureChatWebSupport.SECURE_BASE_URL,
                        )
                    }
                }
            }
        }
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.rgb(96, 165, 250))
        gravity = Gravity.START
        setPadding(0, dp(12), 0, dp(8))
    }

    private fun primaryButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 14f
            isAllCaps = false
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(58),
            ).apply {
                bottomMargin = dp(10)
            }
        }

    private fun findWifiNetwork(): Network? {
        val active = connectivityManager.activeNetwork
        if (active != null) {
            val caps = connectivityManager.getNetworkCapabilities(active)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                return active
            }
        }
        return connectivityManager.allNetworks.firstOrNull { network ->
            connectivityManager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    private fun registerLocalWifiCallback() {
        if (wifiCallbackRegistered) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        runCatching {
            connectivityManager.registerNetworkCallback(request, wifiNetworkCallback)
            wifiCallbackRegistered = true
        }
    }

    private fun unregisterLocalWifiCallback() {
        if (!wifiCallbackRegistered) return
        runCatching { connectivityManager.unregisterNetworkCallback(wifiNetworkCallback) }
        wifiCallbackRegistered = false
    }

    private fun bindPortalToWifi(): Boolean {
        val wifi = findWifiNetwork() ?: return false
        if (!connectivityManager.bindProcessToNetwork(wifi)) return false
        boundWifiNetwork = wifi
        if (::webView.isInitialized) {
            webView.setNetworkAvailable(true)
        }
        return true
    }

    @Synchronized
    private fun ensureCallWifiNetwork(): Network? {
        val current = boundWifiNetwork
        if (current != null) {
            val caps = connectivityManager.getNetworkCapabilities(current)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                return current
            }
            boundWifiNetwork = null
        }

        val wifi = findWifiNetwork() ?: return null
        if (!connectivityManager.bindProcessToNetwork(wifi)) return null
        boundWifiNetwork = wifi
        runOnUiThread {
            if (::webView.isInitialized) webView.setNetworkAvailable(true)
        }
        return wifi
    }

    private fun releaseWifiBinding() {
        if (boundWifiNetwork != null) {
            connectivityManager.bindProcessToNetwork(null)
            boundWifiNetwork = null
        }
    }

    private fun mediaCandidates(network: Network): List<String> {
        @Suppress("UNUSED_PARAMETER")
        val ignored = network
        return listOf(PORTAL_MEDIA_URL)
    }

    private fun detectMedia(showFeedback: Boolean = false) {
        if (showFeedback) {
            status.visibility = View.VISIBLE
            status.text = "Recherche du serveur Shizzi Media…"
        }
        thread {
            val wifi = findWifiNetwork()
            if (wifi == null) {
                mediaBaseUrl = null
                runOnUiThread {
                    if (showFeedback) status.text = "Connecte d'abord cet appareil au Wi-Fi Shizzi."
                }
                return@thread
            }

            val found = mediaCandidates(wifi).firstOrNull { base ->
                runCatching {
                    val connection = wifi.openConnection(URL(base + "health")) as HttpURLConnection
                    connection.connectTimeout = 1_000
                    connection.readTimeout = 1_000
                    connection.useCaches = false
                    val ok = connection.responseCode == 200 || connection.responseCode == 401
                    connection.disconnect()
                    ok
                }.getOrDefault(false)
            }
            mediaBaseUrl = found

            runOnUiThread {
                if (showFeedback) {
                    status.visibility = View.VISIBLE
                    status.text = if (found != null) {
                        "Shizzi Media détecté."
                    } else {
                        "Shizzi Media n'est pas actif sur ce routeur."
                    }
                }
            }
        }
    }

    private fun openMedia() {
        val known = mediaBaseUrl
        if (known != null) {
            openPortal(known, "Shizzi Media")
            return
        }
        status.visibility = View.VISIBLE
        status.text = "Recherche de Shizzi Media…"
        thread {
            val wifi = findWifiNetwork()
            val found = wifi?.let { network ->
                mediaCandidates(network).firstOrNull { base ->
                    runCatching {
                        val connection = network.openConnection(URL(base + "health")) as HttpURLConnection
                        connection.connectTimeout = 1_200
                        connection.readTimeout = 1_200
                        val ok = connection.responseCode == 200 || connection.responseCode == 401
                        connection.disconnect()
                        ok
                    }.getOrDefault(false)
                }
            }
            mediaBaseUrl = found
            runOnUiThread {
                if (found == null) {
                    status.visibility = View.VISIBLE
                    status.text = "Serveur Media indisponible. Vérifie qu'il est activé sur le téléphone Shizzi."
                } else {
                    openPortal(found, "Shizzi Media")
                }
            }
        }
    }

    private fun openPortal(url: String, label: String = "Shizzi") {
        val localRouter = runCatching { URL(url).host == PORTAL_HOST }.getOrDefault(false)
        if (!localRouter) { openPortalPage(url, label); return }
        if (!bindPortalToWifi()) {
            showNavigationFailure("Aucun Wi-Fi Shizzi connecté.", "")
            return
        }
        thread {
            runCatching { RouterTls.pair(this) }.onSuccess { pin ->
                routerPin = pin
                runOnUiThread { openPortalPage(url.replace("http://", "https://"), label) }
            }.onFailure { failure ->
                runOnUiThread { showNavigationFailure("Association du routeur impossible", failure.message.orEmpty()) }
            }
        }
    }

    private fun openPortalPage(url: String, label: String) {
        deactivateSecureChat(force = true)
        requestedBaseUrl = url.substringBeforeLast('/', url) + "/"
        requestedLabel = label

        menu.visibility = View.GONE
        webView.stopLoading()
        webView.visibility = View.INVISIBLE
        progress.visibility = View.VISIBLE
        status.visibility = View.VISIBLE
        status.text = "Ouverture de $label…"

        if (!bindPortalToWifi()) {
            showNavigationFailure(
                "Aucun réseau Wi-Fi Shizzi utilisable n'a été trouvé.",
                "",
            )
            return
        }

        webView.clearHistory()
        webView.loadUrl(url)
    }

    private fun belongsToRequestedTarget(url: String?): Boolean {
        val target = requestedBaseUrl ?: return false
        val candidate = url ?: return false
        return candidate == target.removeSuffix("/") ||
            candidate.startsWith(target)
    }

    private fun showNavigationFailure(title: String, detail: String) {
        progress.visibility = View.GONE
        webView.visibility = View.INVISIBLE
        status.visibility = View.VISIBLE
        status.text = title + detail.trim().takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
    }

    private fun enterVideoFullscreen(
        view: View,
        callback: WebChromeClient.CustomViewCallback?,
    ) {
        previousOrientation = requestedOrientation
        fullscreenView = view
        fullscreenCallback = callback

        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        fullscreenContainer = container

        webView.visibility = View.GONE
        addContentView(
            container,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.insetsController?.hide(WindowInsets.Type.systemBars())
    }

    private fun exitVideoFullscreen() {
        val view = fullscreenView ?: return
        val callback = fullscreenCallback
        val container = fullscreenContainer

        container?.removeView(view)
        (container?.parent as? ViewGroup)?.removeView(container)

        fullscreenView = null
        fullscreenContainer = null
        fullscreenCallback = null

        requestedOrientation = previousOrientation
        window.insetsController?.show(WindowInsets.Type.systemBars())
        webView.visibility = View.VISIBLE

        callback?.onCustomViewHidden()
    }

    override fun onResume() {
        super.onResume()
        if (CallReceptionService.isEnabled(this) && CallReceptionService.notificationsAllowed(this) && !CallReceptionService.running) {
            runCatching { CallReceptionService.start(this) }
        }
        updateReceptionUi()
        if (::webView.isInitialized && webView.visibility == View.VISIBLE) {
            bindPortalToWifi()
        } else {
            detectMedia()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            fullscreenView != null -> exitVideoFullscreen()
            webView.visibility == View.VISIBLE && webView.canGoBack() -> webView.goBack()
            webView.visibility == View.VISIBLE -> {
                webView.visibility = View.GONE
                progress.visibility = View.GONE
                status.visibility = View.GONE
                requestedBaseUrl = null
                requestedLabel = "Shizzi"
                deactivateSecureChat()
                menu.visibility = View.VISIBLE
                if (!callTransportActive) {
                    releaseWifiBinding()
                }
                detectMedia()
            }
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        pendingWebPermissionRequest?.deny()
        pendingWebPermissionRequest = null
        pendingWebPermissionResources = emptyArray()
        if (fullscreenView != null) exitVideoFullscreen()
        deactivateSecureChat(force = true)
        unregisterLocalWifiCallback()
        releaseWifiBinding()
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCallNotification(intent)
    }

    private fun handleCallNotification(intent: Intent?) {
        if (intent == null || intent.action !in setOf(CallReceptionService.ACTION_ANSWER, CallReceptionService.ACTION_OPEN)) return
        if (!CallReceptionService.validActivityIntent(intent)) return
        val id = intent.getStringExtra(CallReceptionService.EXTRA_CALL_ID).orEmpty()
        pendingNotificationAnswer = if (intent.action == CallReceptionService.ACTION_ANSWER) id else ""
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        if (secureChatActive && ::webView.isInitialized) {
            val encoded = JSONObject.quote(id)
            webView.evaluateJavascript("if(window.ShizziCalls){window.ShizziCalls.answerFromNotification(" + encoded + ");}", null)
        } else openSecureChat()
    }

    private fun toggleCallReception() {
        if (CallReceptionService.isEnabled(this)) {
            CallReceptionService.setEnabled(this, false)
            CallReceptionService.stop(this)
            updateReceptionUi()
        } else if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            enableAfterNotificationGrant = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_RECEPTION_NOTIFICATIONS)
        } else if (!CallReceptionService.notificationsAllowed(this)) {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        } else enableCallReception()
    }

    private fun enableCallReception() {
        CallReceptionService.setEnabled(this, true)
        runCatching { CallReceptionService.start(this) }.onFailure {
            CallReceptionService.setEnabled(this, false)
            Toast.makeText(this, "Réception indisponible : " + it.message, Toast.LENGTH_LONG).show()
        }
        updateReceptionUi()
    }

    private fun updateReceptionUi() {
        val enabled = CallReceptionService.isEnabled(this)
        receptionToggle?.text = if (enabled) "Désactiver les appels en arrière-plan" else "Activer les appels en arrière-plan"
        receptionStatus?.text = if (enabled) "Réception activée. Reste connecté au Wi-Fi Shizzi avec ton compte." else "Active ce mode pour sonner pendant que tu utilises une autre application."
    }

    private fun configureCallBattery() {
        if (getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Shizzi+ est déjà autorisé à fonctionner sans optimisation de batterie.", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Recevoir les appels écran éteint")
            .setMessage("Autorise Shizzi+ à fonctionner sans optimisation de batterie pour garder la connexion locale disponible en veille. Ce mode consomme davantage de batterie.")
            .setPositiveButton("Ouvrir le réglage") { _, _ ->
                runCatching { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + packageName))) }
                    .onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }.setNegativeButton("Plus tard", null).show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQUEST_RECEPTION_NOTIFICATIONS = 4303
        const val PORTAL_HOST = "192.0.2.1"
        const val REQUEST_CALL_PERMISSIONS = 4102
        const val PORTAL_URL = "http://192.0.2.1/"
        const val PORTAL_MEDIA_URL = "http://192.0.2.1/media/"
    }
}
