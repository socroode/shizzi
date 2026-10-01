package dev.shizzi.conso

import android.app.Activity
import android.graphics.Color
import android.content.pm.ActivityInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
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
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var menu: View
    private lateinit var connectivityManager: ConnectivityManager
    private var boundWifiNetwork: Network? = null
    private var mediaBaseUrl: String? = null
    private var requestedBaseUrl: String? = null
    private var requestedLabel: String = "Shizzi"
    private var fullscreenView: View? = null
    private var fullscreenContainer: FrameLayout? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var previousOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        buildUi()
        detectMedia()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(7, 17, 31))
            setPadding(dp(20), dp(28), dp(20), dp(20))
        }

        menu = LinearLayout(this).apply {
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
                text = "Compte · Conso · Recharge · Media"
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

            addView(primaryButton("Actualiser la détection Shizzi") {
                detectMedia(showFeedback = true)
            })
        }

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
                override fun onPageStarted(
                    view: WebView?,
                    url: String?,
                    favicon: android.graphics.Bitmap?,
                ) {
                    if (!belongsToRequestedTarget(url)) return
                    this@MainActivity.webView.visibility = View.INVISIBLE
                    this@MainActivity.progress.visibility = View.VISIBLE
                    this@MainActivity.status.visibility = View.VISIBLE
                    this@MainActivity.status.text = "Ouverture de $requestedLabel…"
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (!belongsToRequestedTarget(url)) return
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
                LinearLayout.LayoutParams.WRAP_CONTENT,
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

    private fun bindPortalToWifi(): Boolean {
        val wifi = findWifiNetwork() ?: return false
        if (!connectivityManager.bindProcessToNetwork(wifi)) return false
        boundWifiNetwork = wifi
        return true
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
                menu.visibility = View.VISIBLE
                releaseWifiBinding()
                detectMedia()
            }
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (fullscreenView != null) exitVideoFullscreen()
        releaseWifiBinding()
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val PORTAL_URL = "http://192.0.2.1/"
        const val PORTAL_MEDIA_URL = "http://192.0.2.1/media/"
    }
}
