package dev.shizzi.admin

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.*
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection

/** Multiple independent live camera tiles. All frames and sound come from
 *  ShizziCam Hotspot's authenticated HTTPS media relay, not peer-to-peer.
 */
class CamAdminActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var tileHolder: LinearLayout
    private var wifi: Network? = null
    private var pin = ""
    private var admin: AdminApi? = null
    private val cameras = linkedMapOf<String, Int>()
    private val tileImages = mutableMapOf<String, ImageView>()
    private val sessions = mutableMapOf<String, String>()
    private val cursors = mutableMapOf<String, Long>()
    private val pending = mutableMapOf<String, String>()
    private val nextCommand = AtomicLong(1)
    @Volatile private var audioPacketAt = 0L
    private val fetching = ConcurrentHashMap.newKeySet<String>()
    private val poll = Executors.newSingleThreadScheduledExecutor()
    private val frames = Executors.newFixedThreadPool(4)
    private val commands = Executors.newSingleThreadExecutor()
    private val audioExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var selected = ""
    @Volatile private var audioSelected = ""
    @Volatile private var alive = true
    private var torchOn = false
    private var micOn = true
    private var zoom = 1f
    private var gridCount = 4
    private var fullScreen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18,20,18,24)
        }
        setContentView(ScrollView(this).apply { addView(root) })
        loginScreen()
        poll.scheduleWithFixedDelay({ updateTiles() }, 750, 350, TimeUnit.MILLISECONDS)
        poll.scheduleWithFixedDelay({ pollCommandResults() }, 1100, 800, TimeUnit.MILLISECONDS)
        audioExecutor.execute { playSelectedAudio() }
    }
    private fun selectWifi(): Network? {
        val cm = getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }
    private fun label(text: String, size: Float = 16f) = TextView(this).apply {
        this.text=text
        textSize=size
        setPadding(0,9,0,9)
    }
    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text=text
        setOnClickListener { action() }
    }

    private fun loginScreen() {
        selected = ""
        audioSelected = ""
        cameras.clear()
        tileImages.clear()
        sessions.clear()
        cursors.clear()
        pending.clear()
        root.removeAllViews()
        root.addView(label("ShizziCam · Visionneuse",25f))
        root.addView(label("Caméras indépendantes · Mosaïque en relais local HTTPS"))
        val login = EditText(this).apply {
            hint="Identifiant Admin"
            setText("admin")
            setSingleLine()
        }
        val pass = EditText(this).apply {
            hint="Mot de passe Admin"
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(login)
        root.addView(pass)
        status = label("")
        root.addView(status)
        root.addView(button("Se connecter") {
            status.text = "Vérification du certificat du routeur…"
            Thread {
                try {
                    val net=selectWifi() ?: error("Connecte le Wi-Fi ShizziCam")
                    val cert=RouterTls.pair(this,net)
                    val api=AdminApi("https://192.0.2.1/api/v1/admin",true) { url ->
                        RouterTls.connection(url.toString(),cert,net)
                    }
                    api.login(login.text.toString().trim(),pass.text.toString())
                    wifi=net
                    pin=cert
                    admin=api
                    runOnUiThread { mosaicScreen() }
                } catch (e: Exception) {
                    runOnUiThread { status.text=e.message ?: "Connexion impossible" }
                }
            }.start()
        })
    }

    private fun url(path:String): HttpsURLConnection =
        RouterTls.connection("https://192.0.2.1"+path,pin,wifi ?: error("Wi-Fi non connecté"))
            .apply {
                connectTimeout=2500
                readTimeout=2500
                instanceFollowRedirects=false
                setRequestProperty("X-Shizzi-Admin-Token",admin?.token.orEmpty())
            }

    private fun api(method:String,path:String,body:JSONObject?=null):JSONObject {
        val c=url(path)
        try {
            c.requestMethod=method
            if(body!=null){
                c.doOutput=true
                c.setRequestProperty("Content-Type","application/json")
                val bytes=body.toString().toByteArray(Charsets.UTF_8)
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use{it.write(bytes)}
            }
            if(c.responseCode!=200)error("Caméra HTTP "+c.responseCode)
            return JSONObject(c.inputStream.bufferedReader().use{it.readText()})
        } finally {c.disconnect()}
    }

    private fun checkLocalUpdate() {
        val checkedPin=pin
        val checkedWifi=wifi
        if(checkedPin.isEmpty() || checkedWifi==null){
            status.text="Connecte-toi d'abord à ShizziCam"
            return
        }
        Thread {
            try {
                LocalCamUpdater.checkAndOffer(this,"viewer",checkedPin,checkedWifi,admin?.token.orEmpty()) {
                    msg -> runOnUiThread { status.text=msg }
                }
            }catch(e:Exception){runOnUiThread{status.text="Mise à jour : "+(e.message ?: "indisponible")}}
        }.start()
    }
    private fun mosaicScreen() {
        root.removeAllViews()
        root.addView(label("ShizziCam · Mosaïque",25f))
        root.addView(label("Images JPEG et son PCM16 via le routeur — candidate de test"))
        root.addView(button("Vérifier une mise à jour locale"){ checkLocalUpdate() })
        val toolbar=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        toolbar.addView(button("Actualiser"){refresh()})
        toolbar.addView(button("Déconnexion") {
            admin?.clearSession()
            admin=null
            loginScreen()
        })
        root.addView(toolbar)
        val modes=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        for(size in listOf(1,2,4,6,8)){
            modes.addView(button("$size") {
                gridCount=size
                fullScreen=false
                renderGrid()
            })
        }
        root.addView(modes)
        status=label("Recherche de caméras…")
        root.addView(status)
        tileHolder=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        root.addView(tileHolder)
        val controlTitle=label("Sélectionne une caméra pour les commandes")
        root.addView(controlTitle)
        val selection=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        selection.addView(button("Dos"){sendControl("camera","back")})
        selection.addView(button("Selfie"){sendControl("camera","front")})
        selection.addView(button("Agrandir / Mosaïque") {
            if(selected.isNotEmpty()) {
                fullScreen=!fullScreen
                renderGrid()
            }
        })
        root.addView(selection)
        val adjust=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        val zoomLabel=label("1.0×")
        adjust.addView(button("Zoom −"){
            zoom=(zoom-0.5f).coerceAtLeast(1f)
            zoomLabel.text="%.1f×".format(zoom)
            sendControl("zoom",zoom.toString())
        })
        adjust.addView(zoomLabel)
        adjust.addView(button("Zoom +"){
            zoom=(zoom+0.5f).coerceAtMost(8f)
            zoomLabel.text="%.1f×".format(zoom)
            sendControl("zoom",zoom.toString())
        })
        adjust.addView(button("Flash"){
            torchOn=!torchOn
            sendControl("torch",if(torchOn)"on" else "off")
        })
        root.addView(adjust)
        val sound=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        sound.addView(button("Écouter caméra"){
            audioSelected=selected
            status.text=if (selected.isNotEmpty()) "Écoute caméra $selected activée…" else "Sélectionne une caméra"
        })
        sound.addView(button("Couper écoute"){audioSelected="";status.text="Écoute désactivée"})
        sound.addView(button("Micro caméra"){
            micOn=!micOn
            sendControl("mic",if(micOn)"on" else "off")
        })
        root.addView(sound)
        val qualities=listOf("auto","360p","480p","720p")
        val qualitySpinner=Spinner(this).apply {
            adapter=ArrayAdapter(this@CamAdminActivity,android.R.layout.simple_spinner_dropdown_item,
                listOf("Auto","360p","480p","720p"))
        }
        root.addView(qualitySpinner)
        root.addView(button("Appliquer qualité"){
            sendControl("quality",qualities[qualitySpinner.selectedItemPosition])
        })
        refresh()
    }

    private fun refresh() {
        if(admin==null)return
        Thread {
            try {
                val arr=api("GET","/api/v1/cam/list").getJSONArray("cameras")
                val available=linkedMapOf<String,Int>()
                for(i in 0 until arr.length()){
                    val item=arr.getJSONObject(i)
                    available[item.getString("account")]=item.optInt("battery",-1)
                }
                runOnUiThread {
                    cameras.clear()
                    cameras.putAll(available)
                    if(selected !in cameras) {
                        selected=""
                        audioSelected=""
                        synchronized(sessions) { sessions.clear();cursors.clear() }
                    }
                    renderGrid()
                    if(selected.isEmpty() && available.isNotEmpty()) {
                        selectCam(available.keys.first())
                        audioSelected=available.keys.first()
                    } else {
                        status.text="Caméras en ligne : ${available.size} · vue ${if(fullScreen) 1 else gridCount}"
                    }
                }
            }catch(e:Exception){runOnUiThread{status.text=e.message ?: "Liste indisponible"}}
        }.start()
    }

    private fun renderGrid() {
        tileImages.clear()
        tileHolder.removeAllViews()
        val names=if(fullScreen && selected.isNotEmpty()) listOf(selected)
            else cameras.keys.take(gridCount)
        if(names.isEmpty()){
            tileHolder.addView(label("Aucune caméra en ligne. Démarre Shizzi+Cam, puis Actualiser."))
            return
        }
        val columns=if(names.size==1)1 else 2
        val width=(resources.displayMetrics.widthPixels-100)/columns
        val grid=GridLayout(this).apply {columnCount=columns}
        for((index,name) in names.withIndex()){
            val item=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL
                setPadding(3,3,3,3)
                setBackgroundColor(if(name==selected)Color.rgb(50,85,105) else Color.DKGRAY)
            }
            item.addView(label("Cam $name · ${cameras[name] ?: "?"}%",12f).apply{
                setTextColor(Color.WHITE)
            })
            val image=ImageView(this).apply{
                layoutParams=LinearLayout.LayoutParams(width-8,(width-8)*9/16)
                setBackgroundColor(Color.BLACK)
                scaleType=ImageView.ScaleType.FIT_CENTER
                setOnClickListener {selectCam(name)}
            }
            item.addView(image)
            tileImages[name]=image
            grid.addView(item,GridLayout.LayoutParams(
                GridLayout.spec(index/columns),GridLayout.spec(index%columns)).apply{
                this.width=width
                this.height=GridLayout.LayoutParams.WRAP_CONTENT
            })
        }
        tileHolder.addView(grid)
    }

    private fun selectCam(account:String) {
        selected=account
        status.text="Caméra $account sélectionnée · association…"
        renderGrid()
        commands.execute {
            try {
                ensureSession(account)
                runOnUiThread { status.text="Caméra $account sélectionnée · commandes prêtes" }
            } catch(e:Exception) {
                runOnUiThread { status.text="Association : "+(e.message ?: "erreur") }
            }
        }
    }

    private fun ensureSession(account:String):String {
        synchronized(sessions){sessions[account]?.let{return it}}
        val sid=api("POST","/api/v1/cam/watch?side=viewer",
            JSONObject().put("account",account)).getString("session")
        synchronized(sessions) {
            sessions[account]=sid
            cursors[account]=0L
        }
        return sid
    }

    private fun sendControl(command:String,value:String) {
        val account=selected.ifEmpty{cameras.keys.firstOrNull().orEmpty()}
        if(account.isEmpty()) {status.text="Aucune caméra connectée";return}
        selected=account
        val commandID="cam-"+nextCommand.getAndIncrement()
        synchronized(pending){pending[commandID]=account}
        status.text="Envoi de $command à la caméra $account…"
        commands.execute {
            try {
                val sid=ensureSession(account)
                api("POST","/api/v1/cam/events?side=viewer",
                    JSONObject().put("account",account).put("session",sid)
                        .put("type","control").put("command",command)
                        .put("value",value).put("commandId",commandID))
                runOnUiThread { status.text="$command envoyé · attente de la confirmation caméra…" }
            }catch(e:Exception) {
                synchronized(pending){pending.remove(commandID)}
                runOnUiThread {status.text="Commande $command refusée : "+(e.message ?: "erreur")}
            }
        }
    }

    private fun pollCommandResults() {
        if(!alive || admin==null)return
        val watch=synchronized(sessions){sessions.toMap()}
        for((account,sid) in watch) {
            val cursor=synchronized(sessions){cursors[account] ?: 0L}
            try {
                val path="/api/v1/cam/events?side=viewer&account="+
                    URLEncoder.encode(account,"UTF-8")+"&session="+sid+"&after="+cursor
                val events=api("GET",path).optJSONArray("events") ?: continue
                var last=cursor
                for (i in 0 until events.length()) {
                    val event=events.getJSONObject(i)
                    last=maxOf(last,event.optLong("id"))
                    if (event.optString("type")!="status") continue
                    val commandID=event.optString("commandId")
                    val expected=synchronized(pending){pending.remove(commandID)}
                    if (expected!=account) continue
                    val applied=event.optBoolean("accepted")
                    val command=event.optString("command")
                    val value=event.optString("value")
                    val reason=event.optString("reason")
                    runOnUiThread {
                        status.text=if(applied) "✓ Cam $account : $command → $value exécuté"
                        else "Cam $account : $command refusé — $reason"
                    }
                }
                synchronized(sessions){if(sessions[account]==sid) cursors[account]=last}
            } catch(e:Exception){
                android.util.Log.w("ShizziCam","Camera acknowledgements unavailable for $account",e)
                if(synchronized(sessions){sessions[account]}==sid && e.message?.contains("403")==true){
                    synchronized(sessions){sessions.remove(account);cursors.remove(account)}
                    runOnUiThread{status.text="Session de caméra expirée : nouvelle association au prochain ordre"}
                }
            }
        }
    }

    private fun updateTiles() {
        if(!alive||admin==null)return
        val names=tileImages.keys.toList()
        for(name in names) {
            if (!fetching.add(name)) continue
            frames.execute {
                try {
                    val c=url("/api/v1/cam/frame?account="+URLEncoder.encode(name,"UTF-8"))
                    try {
                        if(c.responseCode==200) {
                            val bytes=c.inputStream.use { it.readBytes() }
                            if(bytes.isNotEmpty() && bytes.size<=180000){
                                val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
                                if(bitmap!=null) runOnUiThread {
                                    tileImages[name]?.setImageBitmap(bitmap)
                                }
                            }
                        }
                    }finally{c.disconnect()}
                }catch(e:Exception){android.util.Log.w("ShizziCam","Image $name inaccessible",e)}
                finally{fetching.remove(name)}
            }
        }
    }

    private fun playSelectedAudio(){
        val rate=16000
        val required=AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT)
        if(required<=0){runOnUiThread{status.text="Sortie audio Android indisponible"};return}
        val player=try {
            AudioTrack(AudioManager.STREAM_MUSIC,rate,AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,maxOf(required,12800),AudioTrack.MODE_STREAM)
        }catch(e:Exception){android.util.Log.e("ShizziCam","AudioTrack unavailable",e);return}
        if(player.state!=AudioTrack.STATE_INITIALIZED){
            runOnUiThread{status.text="AudioTrack non initialisé"}
            player.release()
            return
        }
        var previous=""
        var after=0L
        try {
            while(alive){
                val name=audioSelected
                if(admin==null||name.isEmpty()){
                    if(previous.isNotEmpty()) {
                        player.pause()
                        player.flush()
                    }
                    previous=""
                    Thread.sleep(200)
                    continue
                }
                if(name!=previous){
                    audioPacketAt=0L
                    player.pause()
                    player.flush()
                    after=0
                    previous=name
                    player.play()
                }
                try{
                    val c=url("/api/v1/cam/audio?account="+URLEncoder.encode(name,"UTF-8")+
                        "&after="+after)
                    try{
                        if(c.responseCode==200){
                            val seq=c.getHeaderField("X-Shizzi-Cam-Audio-Seq")?.toLongOrNull() ?: 0
                            val bytes=c.inputStream.use{it.readBytes()}
                            if(seq>after && bytes.isNotEmpty() && bytes.size<=16384) {
                                after=seq
                                val written=player.write(bytes,0,bytes.size)
                                if(written>0)audioPacketAt=android.os.SystemClock.elapsedRealtime()
                                else runOnUiThread{status.text="Erreur audio de la tablette : $written"}
                            }
                        }
                    }finally{c.disconnect()}
                }catch(e:Exception){
                    android.util.Log.w("ShizziCam","Audio relay read failed",e)
                    runOnUiThread { status.text="Réception audio impossible : "+(e.message ?: "erreur") }
                }
                if(audioPacketAt==0L || android.os.SystemClock.elapsedRealtime()-audioPacketAt>3000L){
                    runOnUiThread { if(audioSelected==name) status.text="Écoute $name : aucun paquet audio récent (micro/réseau à vérifier)" }
                }
                Thread.sleep(80)
            }
        }catch(_:InterruptedException){}finally{
            try{player.pause();player.flush();player.release()}catch(_:Exception){}
        }
    }
    override fun onDestroy(){
        alive=false
        audioSelected=""
        poll.shutdownNow()
        frames.shutdownNow()
        commands.shutdownNow()
        audioExecutor.shutdownNow()
        super.onDestroy()
    }
}
