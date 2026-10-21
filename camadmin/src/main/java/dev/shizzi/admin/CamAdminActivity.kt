package dev.shizzi.admin

import android.app.Activity
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.json.JSONObject

/** Independent ShizziCam camera viewer. Requires the original router Admin login. */
class CamAdminActivity:Activity() {
    private lateinit var root:LinearLayout
    private var network:Network?=null
    private var pin=""
    private var admin:AdminApi?=null
    private var active=true
    private var viewing=false
    private val polling=AtomicBoolean(false)
    private val handler=Handler(Looper.getMainLooper())
    private lateinit var content:LinearLayout
    private val refresh=object:Runnable {
        override fun run() {
            if (active && viewing) fetch()
            if (active) handler.postDelayed(this,1800)
        }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll=ScrollView(this)
        root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(28,36,28,40)
        }
        scroll.addView(root)
        setContentView(scroll)
        loginScreen()
    }
    override fun onResume() {
        super.onResume()
        active=true
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }
    override fun onPause() {
        active=false
        handler.removeCallbacks(refresh)
        super.onPause()
    }
    private fun wifi():Network? {
        val manager=getSystemService(ConnectivityManager::class.java)
        return manager.allNetworks.firstOrNull {
            manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true
        }
    }
    private fun title(text:String) = root.addView(TextView(this).apply {this.text=text; textSize=26f})
    private fun label(text:String) = root.addView(TextView(this).apply {this.text=text; textSize=15f;setPadding(0,12,0,12)})
    private fun loginScreen() {
        viewing=false
        root.removeAllViews()
        title("ShizziCam · Administration")
        label("Connectez ce téléphone au Wi-Fi ShizziCam et vérifiez l'empreinte TLS du routeur.")
        val user=EditText(this).apply {hint="Identifiant administrateur";setText("admin");setSingleLine()}
        val password=EditText(this).apply {
            hint="Mot de passe administrateur"
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        root.addView(user);root.addView(password)
        val state=TextView(this)
        root.addView(state)
        root.addView(Button(this).apply {
            text="Se connecter aux caméras"
            setOnClickListener {
                state.text="Connexion sécurisée…"
                isEnabled=false
                thread(name="shizzicam-admin-login") {
                    try {
                        val chosen=wifi() ?: error("Aucun Wi-Fi ShizziCam détecté")
                        val certificate=RouterTls.pair(this@CamAdminActivity,chosen)
                        val api=AdminApi("https://192.0.2.1/api/v1/admin",true) { url ->
                            RouterTls.connection(url.toString(),certificate,chosen)
                        }
                        api.login(user.text.toString().trim(),password.text.toString())
                        network=chosen
                        pin=certificate
                        admin=api
                        runOnUiThread {showCameras()}
                    } catch(e:Exception) {
                        runOnUiThread {state.text=e.message ?: "Connexion impossible";isEnabled=true}
                    }
                }
            }
        })
    }
    private fun showCameras() {
        viewing=true
        root.removeAllViews()
        title("ShizziCam · Caméras")
        label("Images locales reçues du hotspot. Un téléphone n'apparaît qu'après activation volontaire dans Shizzi+Cam.")
        root.addView(Button(this).apply {text="Actualiser";setOnClickListener {fetch()} })
        root.addView(Button(this).apply {text="Déconnexion";setOnClickListener {
            admin?.clearSession();admin=null;viewing=false;loginScreen()
        }})
        content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        root.addView(content)
        fetch()
    }
    private fun cameraRequest(path:String):ByteArray {
        val n=network ?: error("Wi-Fi indisponible")
        val api=admin ?: error("Admin non connecté")
        val url=URL("https://192.0.2.1"+path)
        val conn=RouterTls.connection(url.toString(),pin,n)
        try {
            conn.requestMethod="GET"
            conn.connectTimeout=4000
            conn.readTimeout=4500
            conn.setRequestProperty("X-Shizzi-Admin-Token",api.token)
            check(conn.responseCode==200) {"Erreur caméra HTTP "+conn.responseCode}
            return conn.inputStream.use {it.readBytes()}
        } finally {conn.disconnect()}
    }
    private fun fetch() {
        if (!viewing || !polling.compareAndSet(false,true)) return
        thread(name="shizzicam-admin-refresh") {
            try {
                val list=JSONObject(String(cameraRequest("/api/v1/cam/list"),StandardCharsets.UTF_8))
                    .getJSONArray("cameras")
                val entries=mutableListOf<Triple<String,Int,ByteArray>>()
                for (i in 0 until list.length()) {
                    val item=list.getJSONObject(i)
                    val account=item.getString("account")
                    val battery=item.optInt("battery",-1)
                    val bytes=cameraRequest("/api/v1/cam/frame?account="+java.net.URLEncoder.encode(account,"UTF-8"))
                    entries.add(Triple(account,battery,bytes))
                }
                runOnUiThread {
                    if (!viewing) return@runOnUiThread
                    content.removeAllViews()
                    if (entries.isEmpty()) content.addView(TextView(this).apply {
                        text="Aucune caméra active (les images expirent après 15 secondes).";textSize=16f
                    })
                    for ((account,battery,bytes) in entries) {
                        val frame=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
                        content.addView(TextView(this).apply {
                            text="Caméra compte "+account+" · Batterie "+(if(battery<0)"?" else battery.toString()+"%")
                            textSize=17f;setPadding(0,22,0,10)
                        })
                        if (frame!=null) content.addView(ImageView(this).apply {
                            setImageBitmap(frame)
                            adjustViewBounds=true
                            layoutParams=LinearLayout.LayoutParams(-1,-2)
                        })
                    }
                }
            } catch (e:Exception) {
                runOnUiThread {
                    if(viewing) {
                        content.removeAllViews()
                        content.addView(TextView(this).apply {
                            text="Connexion caméra : "+(e.message ?: "Erreur réseau")
                        })
                    }
                }
            } finally {polling.set(false)}
        }
    }
}
