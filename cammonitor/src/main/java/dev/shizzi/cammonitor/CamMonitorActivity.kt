package dev.shizzi.cammonitor

import android.app.Activity
import android.os.Bundle
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.shizzi.admin.RouterTls
import dev.shizzi.admin.AdminApi
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Router-side local traffic counters. Not a Wi-Fi speedtest and not Starlink data. */
class CamMonitorActivity : Activity() {
 private val poll=Executors.newSingleThreadScheduledExecutor()
 private lateinit var pane:LinearLayout
 private lateinit var status:TextView
 @Volatile private var admin:AdminApi?=null
 @Volatile private var wifi:Network?=null
 @Volatile private var fingerprint=""
 @Volatile private var busy=false

 override fun onCreate(state:Bundle?) {
  super.onCreate(state)
  pane=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(22,24,22,24)}
  setContentView(ScrollView(this).apply{addView(pane)})
  showLogin()
  poll.scheduleWithFixedDelay({refresh()},1,1,TimeUnit.SECONDS)
 }
 private fun label(s:String,size:Float=16f)=TextView(this).apply{
  text=s;textSize=size;setPadding(0,7,0,7)
 }
 private fun showLogin() {
  pane.removeAllViews()
  pane.addView(label("ShizziCam Débit",26f))
  pane.addView(label("Mesures des caméras sur le réseau local du routeur. Aucun test Internet.",14f))
  val user=EditText(this).apply{hint="Identifiant Admin";setText("admin");setSingleLine()}
  val password=EditText(this).apply{
   hint="Mot de passe Admin"
   inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
  }
  pane.addView(user);pane.addView(password)
  status=label("Connexion requise")
  pane.addView(status)
  pane.addView(Button(this).apply{
   text="Connexion au routeur"
   setOnClickListener {
    status.text="Authentification et vérification du certificat…"
    Thread {
     try {
      val cm=getSystemService(ConnectivityManager::class.java)
      val net=cm.allNetworks.firstOrNull {
       cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true
      } ?: error("Connecte-toi au Wi-Fi ShizziCam")
      val cert=RouterTls.pair(this@CamMonitorActivity,net)
      val api=AdminApi("https://192.0.2.1/api/v1/admin",true){url->
       RouterTls.connection(url.toString(),cert,net)
      }
      api.login(user.text.toString().trim(),password.text.toString())
      wifi=net
      fingerprint=cert
      admin=api
      runOnUiThread {showMetrics()}
     }catch(e:Exception){runOnUiThread{status.text=e.message?:"Connexion impossible"}}
    }.start()
   }
  })
 }
 private fun showMetrics(){
  pane.removeAllViews()
  pane.addView(label("ShizziCam Débit",26f))
  pane.addView(label("Trafic vidéo et audio mesuré au relais, sans réseau Internet.",14f))
  pane.addView(label("Le débit disponible du Wi-Fi n'est PAS déduit du débit consommé.",13f))
  pane.addView(Button(this).apply{text="Actualiser";setOnClickListener{refresh()}})
  pane.addView(Button(this).apply{text="Déconnexion";setOnClickListener{
   admin?.clearSession();admin=null;showLogin()
  }})
  status=label("Récupération des compteurs…")
  pane.addView(status)
  refresh()
 }
 private fun refresh(){
  val active=admin ?:return
  if(busy)return
  busy=true
  try{
   val net=wifi?:return
   val conn=RouterTls.connection("https://192.0.2.1/api/v1/cam/metrics",fingerprint,net)
   val root=try {
    conn.connectTimeout=2500
    conn.readTimeout=2500
    conn.setRequestProperty("X-Shizzi-Admin-Token",active.token)
    if(conn.responseCode!=200)error("Statistiques HTTP ${conn.responseCode}")
    JSONObject(conn.inputStream.bufferedReader().use{it.readText()})
   }finally{conn.disconnect()}
   val entries=root.getJSONArray("cameras")
   val text=StringBuilder()
   if(entries.length()==0)text.append("Aucune caméra avec trafic récent.")
   for(i in 0 until entries.length()){
    if(i>0)text.append("\n\n")
    text.append(CamMonitorMetric.render(entries.getJSONObject(i)))
   }
   text.append("\n\nMesures sur deux secondes complètes. Le nombre de FPS affichés ici correspond aux trames relayées, pas au décodage réel.")
   runOnUiThread{if(admin===active)status.text=text.toString()}
  }catch(e:Exception){
   runOnUiThread{if(admin===active)status.text="Diagnostic local : ${e.message?:"indisponible"}"}
  }finally{busy=false}
 }
 override fun onDestroy(){
  admin=null
  poll.shutdownNow()
  super.onDestroy()
 }
}
