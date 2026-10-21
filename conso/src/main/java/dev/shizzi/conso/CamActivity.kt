package dev.shizzi.conso

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

/** User-initiated camera activation; never silently starts from background/boot. */
class CamActivity : Activity() {
    private lateinit var status: TextView
    private var busy = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 48, 36, 24)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(TextView(this).apply { text = "Shizzi+Cam"; textSize = 27f })
        root.addView(TextView(this).apply {
            text = "Connectez-vous au compte Shizzi depuis le portail avant d'activer la caméra. Les images seront envoyées au routeur local en HTTPS. L'administrateur autorisé pourra les consulter."
            textSize = 15f
            setPadding(0, 24, 0, 24)
        })
        status = TextView(this).apply { text = "Caméra désactivée"; textSize = 15f }
        root.addView(status)
        root.addView(Button(this).apply {
            text = "Activer la caméra"
            setOnClickListener { ensurePermissions() }
        })
        root.addView(Button(this).apply {
            text = "Arrêter la caméra"
            setOnClickListener {
                startService(Intent(this@CamActivity, CameraCaptureService::class.java).setAction(CameraCaptureService.ACTION_STOP))
                status.text = "Caméra arrêtée"
            }
        })
        setContentView(root)
    }
    private fun wifi(): Network? {
        val cm = getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }
    private fun ensurePermissions() {
        if (busy) return
        val required = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) required.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 712)
        else pairAndStart()
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if (requestCode == 712) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) pairAndStart()
            else status.text = "Autorisation caméra et notification nécessaire."
        }
    }
    private fun pairAndStart() {
        if (busy) return
        busy = true
        status.text = "Vérification de l'identité du routeur…"
        thread(name="shizzicam-pair") {
            try {
                val network = wifi() ?: error("Connectez-vous au Wi-Fi ShizziCam.")
                val pin = RouterTls.pair(this,network)
                runOnUiThread {
                    if (!isFinishing) {
                        startForegroundService(Intent(this, CameraCaptureService::class.java)
                            .putExtra(CameraCaptureService.EXTRA_PIN,pin))
                        status.text = "Démarrage demandé. Vérifiez la notification caméra."
                    }
                }
            } catch (e:Exception) {
                runOnUiThread { status.text = "Échec : " + (e.message ?: e.javaClass.simpleName) }
            } finally {
                busy = false
            }
        }
    }
}
