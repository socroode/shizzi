package dev.shizzi.conso

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var menu: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
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
                text = "Shizzi Conso"
                textSize = 30f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(8))
            })

            addView(TextView(this@MainActivity).apply {
                text = "Compte, consommation et recharge"
                textSize = 15f
                setTextColor(Color.rgb(148, 163, 184))
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(28))
            })

            addView(primaryButton("OUVRIR LA CONNEXION COMPTE") {
                openPortal(PORTAL_URL)
            })

            addView(primaryButton("VOIR / RECHARGER MON FORFAIT") {
                openPortal(PORTAL_URL)
            })

            addView(TextView(this@MainActivity).apply {
                text = "Connectez d'abord ce téléphone au Wi-Fi Shizzi."
                textSize = 13f
                setTextColor(Color.rgb(148, 163, 184))
                gravity = Gravity.CENTER
                setPadding(0, dp(22), 0, 0)
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
            settings.javaScriptEnabled = false
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(
                    view: WebView?,
                    url: String?,
                    favicon: android.graphics.Bitmap?,
                ) {
                    progress.visibility = View.VISIBLE
                    status.visibility = View.GONE
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    progress.visibility = View.GONE
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        progress.visibility = View.GONE
                        status.visibility = View.VISIBLE
                        status.text =
                            "Shizzi Hotspot n'est pas joignable. Vérifiez la connexion Wi-Fi."
                    }
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
        root.addView(
            progress,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(
            status,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
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
                bottomMargin = dp(12)
            }
        }

    private fun openPortal(url: String) {
        menu.visibility = View.GONE
        webView.visibility = View.VISIBLE
        progress.visibility = View.VISIBLE
        webView.loadUrl(url)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            webView.visibility == View.VISIBLE && webView.canGoBack() -> webView.goBack()
            webView.visibility == View.VISIBLE -> {
                webView.visibility = View.GONE
                progress.visibility = View.GONE
                status.visibility = View.GONE
                menu.visibility = View.VISIBLE
            }
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val PORTAL_URL = "http://192.0.2.1/"
    }
}
