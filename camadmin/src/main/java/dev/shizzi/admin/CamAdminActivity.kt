package dev.shizzi.admin

import android.app.Activity
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.text.InputType
import android.widget.*
import dev.shizzi.camrtc.CamPeer
import org.webrtc.SurfaceViewRenderer
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Read-only ShizziCam viewer: original Admin credentials, pinned TLS, WebRTC H264/Opus. */
class CamAdminActivity:Activity() {
    private lateinit var root:LinearLayout
    private lateinit var status:TextView
    private lateinit var cameraButtons:LinearLayout
    private var wifi:Network?=null
    private var pin=""
    private var admin:AdminApi?=null
    private var account=""
    private var session=""
    private var cursor=0L
    private var peer:CamPeer?=null
    private val polling=Executors.newSingleThreadScheduledExecutor()
    private val outgoing=Executors.newSingleThreadExecutor()
    @Volatile private var watching=false
    private var audioMode=AudioManager.MODE_NORMAL
    private var speaker=false

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(24,32,24,32)}
        setContentView(ScrollView(this).apply{addView(root)})
        loginScreen()
        polling.scheduleWithFixedDelay({poll()},1200,700,TimeUnit.MILLISECONDS)
    }
    private fun selectWifi():Network? {
        val cm=getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true
        }
    }
    private fun label(text:String,size:Float=16f):TextView=TextView(this).apply{
        this.text=text;textSize=size;setPadding(0,12,0,12)
    }
    private fun loginScreen() {
        stopViewing()
        root.removeAllViews()
        root.addView(label("ShizziCam · Surveillance",25f))
        root.addView(label("Vidéo H.264 et audio Opus sur le Wi-Fi local. Connexion Admin sécurisée."))
        val login=EditText(this).apply{hint="Identifiant Admin";setText("admin");setSingleLine()}
        val pass=EditText(this).apply{
            hint="Mot de passe Admin";inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(login);root.addView(pass)
        status=label("")
        root.addView(status)
        root.addView(Button(this).apply {
            text="Se connecter"
            setOnClickListener {
                isEnabled=false
                val button=this
                status.text="Vérification du certificat du routeur…"
                Thread{
                    try {
                        val net=selectWifi() ?: error("Connecte le Wi-Fi ShizziCam")
                        val cert=RouterTls.pair(this@CamAdminActivity,net)
                        val api=AdminApi("https://192.0.2.1/api/v1/admin",true){url->
                            RouterTls.connection(url.toString(),cert,net)
                        }
                        api.login(login.text.toString().trim(),pass.text.toString())
                        wifi=net;pin=cert;admin=api
                        runOnUiThread{listScreen()}
                    }catch(e:Exception){
                        runOnUiThread{status.text=e.message ?: "Connexion impossible";button.isEnabled=true}
                    }
                }.start()
            }
        })
    }
    private fun api(method:String,path:String,body:JSONObject?=null):JSONObject {
        val network=wifi ?: error("Wi-Fi non connecté")
        val token=admin?.token.orEmpty()
        check(token.isNotEmpty()){"Session Admin absente"}
        val c=RouterTls.connection("https://192.0.2.1"+path,pin,network)
        try {
            c.requestMethod=method;c.connectTimeout=3500;c.readTimeout=3500
            c.setRequestProperty("X-Shizzi-Admin-Token",token)
            if(body!=null){
                c.doOutput=true;c.setRequestProperty("Content-Type","application/json")
                val bytes=body.toString().toByteArray(Charsets.UTF_8)
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use{it.write(bytes)}
            }
            check(c.responseCode==200){"Caméra HTTP "+c.responseCode}
            return JSONObject(c.inputStream.bufferedReader().use{it.readText()})
        }finally{c.disconnect()}
    }
    private fun send(message:JSONObject) {
        if(!watching)return
        val which=account
        outgoing.execute {
            runCatching{api("POST","/api/v1/cam/events?side=viewer",message.put("account",which))}
                .onFailure{android.util.Log.w("ShizziCam","Signal caméra non remis",it)}
        }
    }
    private fun listScreen() {
        stopViewing()
        root.removeAllViews()
        root.addView(label("ShizziCam · Caméras",25f))
        root.addView(label("Caméras visibles uniquement après activation volontaire sur le téléphone."))
        status=label("")
        root.addView(status)
        root.addView(Button(this).apply{text="Actualiser";setOnClickListener{refresh()}})
        root.addView(Button(this).apply{text="Déconnexion";setOnClickListener{admin?.clearSession();admin=null;loginScreen()}})
        cameraButtons=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        root.addView(cameraButtons)
        refresh()
    }
    private fun refresh() {
        Thread {
            try {
                val arr=api("GET","/api/v1/cam/list").getJSONArray("cameras")
                val list=mutableListOf<Pair<String,Int>>()
                for(i in 0 until arr.length()){
                    val obj=arr.getJSONObject(i)
                    list.add(obj.getString("account") to obj.optInt("battery",-1))
                }
                runOnUiThread{
                    cameraButtons.removeAllViews()
                    if(list.isEmpty())cameraButtons.addView(label("Aucune caméra en ligne"))
                    for ((number,battery) in list){
                        cameraButtons.addView(Button(this).apply{
                            text="Caméra "+number+" · "+(if(battery<0)"?" else "$battery%")
                            setOnClickListener{watch(number)}
                        })
                    }
                    status.text="Caméras en ligne : "+list.size
                }
            }catch(e:Exception){runOnUiThread{status.text=e.message ?: "Liste indisponible"}}
        }.start()
    }
    private fun watch(number:String) {
        stopViewing()
        root.removeAllViews()
        root.addView(label("Direct · "+number,25f))
        root.addView(Button(this).apply{text="Retour";setOnClickListener{listScreen()}})
        status=label("Association caméra…")
        root.addView(status)
        val renderer=SurfaceViewRenderer(this)
        renderer.layoutParams=LinearLayout.LayoutParams(-1,resources.displayMetrics.widthPixels*9/16)
        root.addView(renderer)
        root.addView(label("Le son est transmis par WebRTC et diffusé sur le haut-parleur."))
        val qualities=listOf("auto","360p","480p","720p")
        val selector=Spinner(this).apply {
            adapter=ArrayAdapter(this@CamAdminActivity,android.R.layout.simple_spinner_dropdown_item,
                listOf("Auto","360p","480p","720p"))
        }
        root.addView(selector)
        root.addView(Button(this).apply{
            text="Appliquer qualité"
            setOnClickListener{
                send(JSONObject().put("session",session).put("type","quality")
                    .put("quality",qualities[selector.selectedItemPosition]))
            }
        })
        watching=true;account=number;cursor=0
        val audio=getSystemService(AudioManager::class.java)
        audioMode=audio.mode;speaker=audio.isSpeakerphoneOn
        audio.mode=AudioManager.MODE_IN_COMMUNICATION;audio.isSpeakerphoneOn=true
        try {
            peer=CamPeer(this,renderer,signal={send(it)},status={message->
                runOnUiThread{if(watching)status.text=message}
            })
        }catch(e:Exception){
            watching=false;status.text=e.message ?: "Décodage indisponible";return
        }
        Thread {
            try {
                val sid=api("POST","/api/v1/cam/watch?side=viewer",
                    JSONObject().put("account",number)).getString("session")
                runOnUiThread{
                    if(watching && account==number) {
                        session=sid;peer?.beginViewer(sid)
                        status.text="Connexion WebRTC H.264 / Opus…"
                    }
                }
            }catch(e:Exception){runOnUiThread{status.text=e.message ?: "Caméra inaccessible"}}
        }.start()
    }
    private fun poll() {
        if(!watching || session.isEmpty())return
        try {
            val q="/api/v1/cam/events?side=viewer&account="+URLEncoder.encode(account,"UTF-8")+
                "&session="+URLEncoder.encode(session,"UTF-8")+"&after="+cursor
            val events=api("GET",q).getJSONArray("events")
            for(i in 0 until events.length()){
                val event=events.getJSONObject(i)
                cursor=maxOf(cursor,event.optLong("id"))
                peer?.accept(event)
            }
        }catch(e:Exception){android.util.Log.w("ShizziCam","Signalisation interrompue",e)}
    }
    private fun stopViewing() {
        watching=false;session="";account="";cursor=0
        peer?.close();peer=null
        val a=getSystemService(AudioManager::class.java)
        a.mode=audioMode;a.isSpeakerphoneOn=speaker
    }
    override fun onDestroy(){
        stopViewing();polling.shutdownNow();outgoing.shutdownNow()
        super.onDestroy()
    }
}
