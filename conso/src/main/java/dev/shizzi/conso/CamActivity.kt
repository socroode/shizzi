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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import java.nio.charset.StandardCharsets
import org.json.JSONObject
import kotlin.concurrent.thread

/** Shizzi+ features are unchanged on the 0.4.3.5.11 router: camera is feature-gated. */
class CamActivity:Activity() {
    private lateinit var status:TextView
    private lateinit var quality:Spinner
    @Volatile private var busy=false

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            gravity=Gravity.CENTER_HORIZONTAL
            setPadding(36,48,36,24)
        }
        root.addView(TextView(this).apply{text="Shizzi+Cam";textSize=27f})
        root.addView(TextView(this).apply {
            text="Caméra locale avec vidéo H.264 et son Opus. Connecte ton compte Shizzi avec le portail avant de démarrer. Shizzi 0.4.3.5.11 reste compatible pour les fonctions habituelles, mais ne gère pas la vidéosurveillance."
            textSize=15f
            setPadding(0,24,0,24)
        })
        quality=Spinner(this)
        quality.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item, listOf("Auto","360p","480p","720p"))
        root.addView(quality)
        status=TextView(this).apply {text="Caméra désactivée";textSize=15f}
        root.addView(status)
        root.addView(Button(this).apply {
            text="Activer caméra + microphone"
            setOnClickListener {ensurePermissions()}
        })
        root.addView(Button(this).apply {
            text="Arrêter la caméra"
            setOnClickListener {
                startService(Intent(this@CamActivity,CameraCaptureService::class.java).setAction(CameraCaptureService.ACTION_STOP))
                status.text="Caméra et microphone arrêtés"
            }
        })
        setContentView(root)
    }
    private fun wifi():Network? {
        val cm=getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true
        }
    }
    private fun ensurePermissions() {
        if (busy) return
        val wanted=mutableListOf(Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO)
        if(Build.VERSION.SDK_INT>=33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing=wanted.filter{checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED}
        if(missing.isNotEmpty())requestPermissions(missing.toTypedArray(),713)
        else pairAndStart()
    }
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,grants:IntArray) {
        super.onRequestPermissionsResult(code,permissions,grants)
        if (code==713) {
            if(grants.size>=2 && grants.all{it==PackageManager.PERMISSION_GRANTED}) pairAndStart()
            else status.text="La caméra et le microphone nécessitent ton autorisation."
        }
    }
    private fun pairAndStart() {
        if(busy)return
        busy=true
        val q=listOf("auto","360p","480p","720p")[quality.selectedItemPosition]
        status.text="Vérification de la compatibilité du routeur…"
        thread(name="shizzicam-check") {
            try {
                val net=wifi() ?: error("Connecte le Wi-Fi du routeur Shizzi.")
                val pin=RouterTls.pair(this,net)
                val conn=RouterTls.connection("https://192.0.2.1/api/v1/cam/capabilities",pin,net)
                val supported=try {
                    conn.connectTimeout=3500
                    conn.readTimeout=3500
                    conn.responseCode==200 && runCatching {
                        JSONObject(conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use{it.readText()})
                            .optString("protocol")=="shizzicam-webrtc-v1"
                    }.getOrDefault(false)
                }finally {conn.disconnect()}
                if(!supported)error("Shizzi Hotspot 0.4.3.5.11 : fonctions habituelles disponibles, mais mode caméra IP non pris en charge.")
                runOnUiThread {
                    if(!isFinishing) {
                        startForegroundService(Intent(this,CameraCaptureService::class.java)
                            .putExtra(CameraCaptureService.EXTRA_PIN,pin)
                            .putExtra(CameraCaptureService.EXTRA_QUALITY,q))
                        status.text="Caméra et microphone en démarrage. Notification active."
                    }
                }
            } catch(e:Exception) {
                runOnUiThread {status.text=e.message ?: "Caméra indisponible"}
            } finally {busy=false}
        }
    }
}
