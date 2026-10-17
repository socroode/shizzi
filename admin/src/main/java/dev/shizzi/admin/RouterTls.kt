package dev.shizzi.admin

import android.app.Activity
import android.app.AlertDialog
import android.net.Network
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** Pins are approved by comparing with the fingerprint on the router screen. */
internal object RouterTls {
    fun fingerprint(certificate: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(certificate)
            .joinToString("") { "%02x".format(it) }

    private fun context(pin: String?): SSLContext {
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                throw CertificateException("Client certificate unsupported")
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty()) throw CertificateException("Missing router certificate")
                chain[0].checkValidity()
                if (pin != null && fingerprint(chain[0].encoded) != pin) {
                    throw CertificateException("L'identité du routeur a changé.")
                }
            }
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
    }

    fun connection(url: String, pin: String, network: Network? = null): HttpsURLConnection {
        val target = URL(url)
        check(target.protocol == "https" && target.host == "192.0.2.1")
        check(pin.matches(Regex("[0-9a-f]{64}"))) { "Routeur non associé." }
        return ((network?.openConnection(target) ?: target.openConnection()) as HttpsURLConnection).apply {
            sslSocketFactory = context(pin).socketFactory
            instanceFollowRedirects = false
        }
    }

    /** The untrusted probe sends no credentials, cookies, tokens or form data. */
    fun pair(activity: Activity, network: Network? = null): String {
        val target = URL("https://192.0.2.1/status.json")
        val probe = (network?.openConnection(target) ?: target.openConnection()) as HttpsURLConnection
        val pin = try {
            probe.sslSocketFactory = context(null).socketFactory
            probe.instanceFollowRedirects = false
            probe.connectTimeout = 5_000
            probe.readTimeout = 5_000
            probe.connect()
            fingerprint(probe.serverCertificates[0].encoded)
        } finally { probe.disconnect() }
        val prefs = activity.getSharedPreferences("router_tls_pins", Activity.MODE_PRIVATE)
        val known = prefs.getStringSet("pins", emptySet()).orEmpty()
        if (pin in known) return pin
        val ready = CountDownLatch(1)
        var approved = false
        val displayed = pin.uppercase().chunked(8).joinToString(" ")
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) { ready.countDown(); return@runOnUiThread }
            AlertDialog.Builder(activity)
                .setTitle("Vérifier le routeur Shizzi")
                .setMessage("Comparez ce code avec celui affiché sur le téléphone routeur, dans Admin :\n\n$displayed\n\nConfirmez seulement si les deux codes sont identiques.")
                .setPositiveButton("Codes identiques") { _, _ -> approved = true; ready.countDown() }
                .setNegativeButton("Annuler") { _, _ -> ready.countDown() }
                .setOnCancelListener { ready.countDown() }
                .show()
        }
        check(ready.await(90, TimeUnit.SECONDS) && approved) { "Association du routeur annulée." }
        check(prefs.edit().putStringSet("pins", known + pin).commit()) { "Association impossible à enregistrer." }
        return pin
    }
}
