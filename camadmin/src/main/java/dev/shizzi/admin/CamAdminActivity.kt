package dev.shizzi.admin

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.AudioRecord
import android.media.MediaRecorder
import android.Manifest
import android.content.pm.PackageManager
import dev.shizzi.camrtc.*
import android.os.SystemClock
import java.io.DataOutputStream
import java.util.concurrent.ArrayBlockingQueue
import dev.shizzi.camrtc.CamOpusEncoder
import dev.shizzi.camrtc.CamOpusDecoder
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.*
import android.view.TextureView
import android.view.Surface
import android.graphics.SurfaceTexture
import android.view.View
import java.io.DataInputStream
import java.io.BufferedInputStream
import java.util.concurrent.atomic.AtomicInteger
import dev.shizzi.camrtc.CamH264Decoder
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/** Multiple independent live camera tiles. All frames and sound come from
 *  ShizziCam Hotspot's authenticated HTTPS media relay, not peer-to-peer.
 */
class CamAdminActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var footer: LinearLayout
    private lateinit var interphoneState: TextView
    private lateinit var talkButton: Button
    private lateinit var stopTalkButton: Button
    private val interphone = InterphoneSession()
    private var permissionAttempt: Long? = null
    private val clock=CamClock()
    private val renderedRates=ConcurrentHashMap<String,CamRate>()
    private val renderedAt=ConcurrentHashMap<String,Long>()
    private val latency=ConcurrentHashMap<String,Double>()
    private val lastDimensions=ConcurrentHashMap<String,String>()
    private val tileLabels=ConcurrentHashMap<String,TextView>()
    private val videoInterruptions=ConcurrentHashMap<String,Long>()
    @Volatile private var audioConnection:HttpsURLConnection?=null
    private val talkConnections=ConcurrentHashMap<Long,HttpsURLConnection>()
    private val activeQualities=ConcurrentHashMap<String,String>()
    private var lastTelemetry=0L
    private var lastClockSync=0L
    private lateinit var status: TextView
    private lateinit var tileHolder: LinearLayout
    private var wifi: Network? = null
    private var pin = ""
    private var admin: AdminApi? = null
    private val cameras = linkedMapOf<String, Int>()
    private val tileImages = mutableMapOf<String, ImageView>()
    private val sessions = ViewerSessions()
    private val fetching = ConcurrentHashMap.newKeySet<String>()
    private val poll = Executors.newSingleThreadScheduledExecutor()
    private val frames = Executors.newFixedThreadPool(4)
    private val videoThreads = Executors.newFixedThreadPool(8)
    private val streamGeneration=AtomicInteger(0)
    private val tileTextures=mutableMapOf<String,TextureView>()
    private val streamConnections=ConcurrentHashMap<String,HttpsURLConnection>()
    private val commands = Executors.newSingleThreadExecutor()
    private val eventPoll = Executors.newSingleThreadScheduledExecutor()
    private val sessionBusy=ConcurrentHashMap.newKeySet<String>()
    private val pendingCommands = ConcurrentHashMap<String,String>()
    private val audioExecutor = Executors.newSingleThreadExecutor()
    private val talkExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var selected = ""
    @Volatile private var audioSelected = ""
    @Volatile private var alive = true
    private var torchOn = false
    private var zoom = 1f
    private var gridCount = 4
    private var fullScreen = false
    private var rotationIndex=0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18,20,18,24)
        }
        val shell=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        shell.addView(ScrollView(this).apply{addView(root)},LinearLayout.LayoutParams(-1,0,1f))
        footer=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(12,4,12,4);visibility=View.GONE}
        shell.addView(footer)
        setContentView(shell)
        loginScreen()
        poll.scheduleWithFixedDelay({ updateTiles() }, 750, 300, TimeUnit.MILLISECONDS)
        audioExecutor.execute { playSelectedAudio() }
        eventPoll.scheduleWithFixedDelay({ pollCommandStatus() }, 1, 1, TimeUnit.SECONDS)
        eventPoll.scheduleWithFixedDelay({ pollInterphoneStatus() }, 500, 500, TimeUnit.MILLISECONDS)
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
    private fun buttonRow(vararg buttons: Button) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        buttons.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
    }
    private fun fitButtons(row:LinearLayout){
        for(i in 0 until row.childCount)row.getChildAt(i).layoutParams=LinearLayout.LayoutParams(0,-2,1f)
    }

    private fun loginScreen() {
        footer.visibility=View.GONE
        audioConnection?.disconnect();stopTalking()
        streamGeneration.incrementAndGet();streamConnections.values.forEach{it.disconnect()}
        selected = ""
        audioSelected = ""
        cameras.clear()
        tileImages.clear()
        sessions.clear()
        pendingCommands.clear()
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
                    val capabilities=this.api("GET","/api/v1/cam/capabilities")
                    check(capabilities.optString("protocol")=="shizzicam-relay-avc-opus-v3"){
                        "Cette Visionneuse nécessite ShizziCam HOTSPOT 0.4.3.5.18 ou un relais v3 compatible"
                    }
                    clock.synchronize{this.api("GET","/api/v1/cam/clock")}
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
            val code=c.responseCode
            if(code!=200)throw AdminApiException(code,"Routeur HTTP $code")
            return JSONObject(c.inputStream.bufferedReader().use{it.readText()})
        } finally {c.disconnect()}
    }

    private fun mosaicScreen() {
        root.removeAllViews()
        root.addView(label("ShizziCam · Mosaïque",25f))
        root.addView(label("H.264 et Opus 128 kbps via le routeur · version de test"))
        val toolbar=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        toolbar.addView(button("Actualiser"){refresh()})
        toolbar.addView(button("Mise à jour"){checkLocalUpdate()})
        toolbar.addView(button("Déconnexion") {
            admin?.clearSession()
            admin=null
            loginScreen()
        })
        fitButtons(toolbar);root.addView(toolbar)
        val modes=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        for(size in listOf(1,2,4,6,8)){
            modes.addView(button("$size") {
                gridCount=size
                fullScreen=false
                renderGrid()
            })
        }
        fitButtons(modes);root.addView(modes)
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
        fitButtons(selection);root.addView(selection)
        root.addView(button("Orientation : Auto / 0° / 90° / 180° / 270°"){
            val choices=listOf("auto","0","90","180","270")
            rotationIndex=(rotationIndex+1)%choices.size
            sendControl("rotation",choices[rotationIndex])
        })
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
        fitButtons(adjust);root.addView(adjust)
        footer.removeAllViews();footer.visibility=View.VISIBLE
        interphoneState=label("Mon micro : coupé",13f).apply{setTextColor(Color.GRAY)}
        footer.addView(interphoneState)
        footer.addView(buttonRow(
            button("Écouter caméra"){audioConnection?.disconnect();audioSelected=selected;status.text="Écoute de la caméra $selected demandée"},
            button("Couper écoute"){audioSelected="";audioConnection?.disconnect();status.text="Écoute coupée"},
        ))
        talkButton=button("Parler"){startTalking()}
        stopTalkButton=button("Couper mon micro"){stopTalking()}
        footer.addView(buttonRow(talkButton,stopTalkButton))
        root.addView(label("Microphone du téléphone caméra"))
        root.addView(buttonRow(
            button("Activer micro caméra"){sendControl("mic","on")},
            button("Couper micro caméra"){sendControl("mic","off")},
        ))
        renderInterphoneState()
        val alarmBar=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        alarmBar.addView(button("Alarme"){
            val target=selected
            if(target.isNotEmpty())AlertDialog.Builder(this).setTitle("Alarme caméra $target")
                .setMessage("Déclencher la sirène pendant 10 secondes maximum ?")
                .setPositiveButton("Déclencher"){_,_->if(selected==target)sendControl("alarm","start")}
                .setNegativeButton("Annuler",null).show()
        })
        alarmBar.addView(button("Arrêter alarme"){sendControl("alarm","stop")})
        fitButtons(alarmBar);root.addView(alarmBar)
        val qualities=listOf("auto","144p","240p","360p","480p","720p")
        val qualitySpinner=Spinner(this).apply {
            adapter=ArrayAdapter(this@CamAdminActivity,android.R.layout.simple_spinner_dropdown_item,
                listOf("Auto","144p","240p","360p","480p","720p"))
        }
        root.addView(qualitySpinner)
        root.addView(button("Appliquer qualité"){
            sendControl("quality",qualities[qualitySpinner.selectedItemPosition])
        })
        refresh()
    }

    private fun checkLocalUpdate(){
        if(admin==null)return
        status.text="Recherche de la version visionneuse sur ShizziCam…"
        Thread {
            try {
                val entry=api("GET","/api/v1/apps/manifest").getJSONObject("camViewerApp")
                val remote=entry.getString("version")
                val local=packageManager.getPackageInfo(packageName,0).versionName.orEmpty()
                val sha=entry.getString("sha256")
                val size=entry.getLong("sizeBytes")
                check(size>0 && sha.matches(Regex("[0-9a-fA-F]{64}"))){"APK non vérifiée"}
                fun numbers(s:String)=s.substringBefore("-").split(".").mapNotNull{it.toIntOrNull()}
                val r=numbers(remote);val l=numbers(local)
                val upgrade=(0 until maxOf(r.size,l.size)).firstOrNull{
                    r.getOrElse(it){0}!=l.getOrElse(it){0}
                }?.let{r.getOrElse(it){0}>l.getOrElse(it){0}} ?: false
                runOnUiThread{
                    if(upgrade){
                        AlertDialog.Builder(this)
                            .setTitle("Mise à jour visionneuse")
                            .setMessage("ShizziCam Visionneuse $remote disponible localement (${size/1048576} Mio).")
                            .setPositiveButton("Télécharger"){_,_->
                                startActivity(Intent(Intent.ACTION_VIEW,
                                    Uri.parse("http://192.0.2.1/shizzicam-admin.apk?sha256=$sha")))
                            }
                            .setNegativeButton("Annuler",null).show()
                    } else status.text="Visionneuse à jour : $local"
                }
            }catch(e:Exception){runOnUiThread{status.text="Mise à jour : "+(e.message?:"indisponible")}}
        }.start()
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
                    if(selected !in cameras) selected=""
                    renderGrid()
                    if(selected.isEmpty() && cameras.isNotEmpty()) selectCam(cameras.keys.first())
                    status.text="Caméras en ligne : ${available.size} · vue ${if(fullScreen) 1 else gridCount}"
                }
            }catch(e:Exception){runOnUiThread{status.text=e.message ?: "Liste indisponible"}}
        }.start()
    }

    private fun renderGrid() {
        val generation=streamGeneration.incrementAndGet()
        streamConnections.values.forEach{runCatching{it.disconnect()}}
        streamConnections.clear()
        tileImages.clear()
        tileTextures.clear()
        tileLabels.clear()
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
            val tileLabel=label("Cam $name · ${cameras[name] ?: "?"}% · connexion…",12f).apply{setTextColor(Color.WHITE)}
            tileLabels[name]=tileLabel;item.addView(tileLabel)
            val image=ImageView(this).apply{
                layoutParams=LinearLayout.LayoutParams(width-8,(width-8)*9/16)
                setBackgroundColor(Color.BLACK)
                scaleType=ImageView.ScaleType.FIT_CENTER
                setOnClickListener {selectCam(name)}
            }
            val layers=FrameLayout(this)
            layers.addView(image)
            val texture=TextureView(this).apply {
                layoutParams=FrameLayout.LayoutParams(width-8,(width-8)*9/16)
                alpha=0f
                isOpaque=true
            }
            layers.addView(texture)
            texture.setOnClickListener{selectCam(name)}
            item.addView(layers)
            tileImages[name]=image
            tileTextures[name]=texture
            texture.surfaceTextureListener=object:TextureView.SurfaceTextureListener{
                override fun onSurfaceTextureAvailable(st:SurfaceTexture,w:Int,h:Int){
                    streamVideo(name,texture,generation,st)
                }
                override fun onSurfaceTextureSizeChanged(st:SurfaceTexture,w:Int,h:Int){}
                override fun onSurfaceTextureUpdated(st:SurfaceTexture){
                    if(generation==streamGeneration.get()){
                        renderedRates.getOrPut(name){CamRate()}.event()
                        renderedAt[name]=SystemClock.elapsedRealtime()
                    }
                }

                override fun onSurfaceTextureDestroyed(st:SurfaceTexture):Boolean=true
            }
            if(texture.isAvailable)texture.surfaceTexture?.let{streamVideo(name,texture,generation,it)}
            grid.addView(item,GridLayout.LayoutParams(
                GridLayout.spec(index/columns),GridLayout.spec(index%columns)).apply{
                this.width=width
                this.height=GridLayout.LayoutParams.WRAP_CONTENT
            })
        }
        tileHolder.addView(grid)
    }


    /** Decode H264 stream from hotspot only; never connect to a camera IP. */
    private fun streamVideo(name:String,texture:TextureView,generation:Int,surfaceTexture:SurfaceTexture){
        videoThreads.execute {
            val surface=Surface(surfaceTexture)
            try {
                while(alive&&generation==streamGeneration.get()&&!Thread.currentThread().isInterrupted){
                    var c:HttpsURLConnection?=null
                    var decoder:CamH264Decoder?=null
                    try{
                        c=url("/api/v1/cam/video-stream?account="+URLEncoder.encode(name,"UTF-8"))
                        streamConnections["$generation:$name"]=c
                        c.readTimeout=12000
                        if(c.responseCode!=200)throw IllegalStateException("H264 HTTP "+c.responseCode)
                        val stream=DataInputStream(BufferedInputStream(c.inputStream))
                        while(alive&&generation==streamGeneration.get()){
                            val length=stream.readInt()
                            if(length<=0 || length>262144)throw IllegalStateException("H264 packet size")
                            val w=stream.readUnsignedShort()
                            val h=stream.readUnsignedShort()
                            val pts=stream.readLong()
                            val flags=stream.readUnsignedByte()
                            if(w<=0||h<=0||w>3840||h>2160)throw IllegalStateException("H264 dimensions")
                            val data=ByteArray(length)
                            stream.readFully(data)
                            if(decoder==null || decoder.width!=w || decoder.height!=h || (flags and 2!=0)){
                                decoder?.close()
                                decoder=CamH264Decoder(surface,w,h,clock){renderedPts,renderedClockUs->
                                    if(generation==streamGeneration.get()){
                                        if(clock.ready)latency[name]=((renderedClockUs-renderedPts)/1000.0).coerceAtLeast(0.0)
                                    }
                                }
                                lastDimensions[name]="$w × $h"
                                runOnUiThread{
                                    val ratio=(w.toFloat()/h)/(texture.width.toFloat()/texture.height.coerceAtLeast(1))
                                    val matrix=android.graphics.Matrix()
                                    matrix.setScale(if(ratio<1f)ratio else 1f,if(ratio>1f)1f/ratio else 1f,
                                        texture.width/2f,texture.height/2f)
                                    texture.setTransform(matrix)
                                }
                            }
                            val shown=decoder.decode(data,flags,pts)
                            if(shown)runOnUiThread {
                                if(generation==streamGeneration.get()&&tileTextures[name]===texture){
                                    texture.alpha=1f
                                    tileImages[name]?.visibility=View.GONE
                                }
                            }
                        }
                    }catch(e:Exception){
                        videoInterruptions.merge(name,1L){a,b->a+b}
                        android.util.Log.w("ShizziCam","H264 stream $name unavailable",e)
                        runOnUiThread {
                            if(generation==streamGeneration.get()&&tileTextures[name]===texture){
                                texture.alpha=0f
                                tileImages[name]?.visibility=View.VISIBLE
                            }
                        }
                        if(alive&&generation==streamGeneration.get())Thread.sleep(800)
                    }finally{
                        decoder?.close()
                        streamConnections.remove("$generation:$name",c)
                        c?.disconnect()
                    }
                }
            }catch(_:InterruptedException){}finally{surface.release()}
        }
    }

    private fun selectCam(account:String){
        if(account !in cameras) return
        stopTalking()
        selected=account
        audioConnection?.disconnect()
        audioSelected=account
        // Select the first camera's microphone automatically. Only one is audible.
        if(audioSelected.isEmpty()) audioSelected=account
        status.text="Caméra $account sélectionnée · association…"
        commands.execute {
            try {
                ensureSession(account)
                runOnUiThread {if(selected==account)status.text="Caméra $account prête · commandes disponibles"}
            }catch(e:Exception){
                runOnUiThread {status.text="Association : "+(e.message ?: "erreur")}
            }
        }
    }
    private fun ensureSession(account:String):String {
        return sessions.ensure(account){
            api("POST","/api/v1/cam/watch?side=viewer",
                JSONObject().put("account",account)).getString("session")
        }
    }
    private fun sendControl(command:String,value:String) {
        val account=selected
        if(account.isEmpty()) {status.text="Aucune caméra sélectionnée";return}
        status.text="Commande $command en cours sur caméra $account…"
        commands.execute {
            try {
                val sid=ensureSession(account)
                val requestId=UUID.randomUUID().toString()
                pendingCommands[requestId]=command
                api("POST","/api/v1/cam/events?side=viewer",
                    JSONObject().put("account",account).put("session",sid)
                        .put("type","control").put("command",command)
                        .put("value",value).put("requestId",requestId))
                runOnUiThread {
                    status.text="Commande $command envoyée · attente confirmation de la caméra…"
                }
            }catch(e:Exception){
                runOnUiThread {status.text="Commande refusée : "+(e.message ?: "erreur")}
            }
        }
    }
    private fun pollCommandStatus(){
        if(admin==null||!alive)return
        val active=selected
        if(active.isEmpty())return
        val sid=sessions.current(active)
        if(sid==null){
            if(sessionBusy.add(active))commands.execute{try{ensureSession(active)}catch(_:Exception){}finally{sessionBusy.remove(active)}}
            return
        }
        try {
            val after=sessions.after(active,sid)
            val events=api("GET","/api/v1/cam/events?side=viewer&account="+
                URLEncoder.encode(active,"UTF-8")+"&session="+URLEncoder.encode(sid,"UTF-8")+
                "&after="+after).optJSONArray("events") ?:return
            for(i in 0 until events.length()){
                val e=events.getJSONObject(i)
                val id=e.optLong("id")
                sessions.advance(active,sid,id)
                if(e.optString("type")=="status"){
                    val command=e.optString("command")
                    val requestId=e.optString("requestId")
                    if(pendingCommands.remove(requestId)!=null){
                        val success=e.optBoolean("success")
                        val detail=e.optString("detail")
                        runOnUiThread {
                            status.text=if(success) "Caméra $active : $detail"
                                else "Échec $command sur $active : $detail"
                        }
                    }
                }
            }
        }catch(e:Exception){
            // A timeout does not revoke a router session. Re-watching would stop a live upload.
            if(sessions.onPollFailure(active,sid,(e as? AdminApiException)?.status)){
                val attempt=interphone.state
                if(attempt.streaming && attempt.target==active)
                    failTalking(attempt.generation,if((e as AdminApiException).status==404)"caméra déconnectée"
                        else "association refusée par le relais (HTTP 403) · réessaie Parler")
            }else if(e is AdminApiException && e.status==401){
                val attempt=interphone.state
                if(attempt.streaming)failTalking(attempt.generation,"session administrateur expirée · reconnecte la Visionneuse")
            }
            android.util.Log.w("ShizziCam","Command acknowledgement unavailable",e)
        }
    }

    private fun updateTiles(){
        if(!alive||admin==null)return
        val now=SystemClock.elapsedRealtime()
        val labels=tileLabels.toMap()
        for((name,view) in labels){
            val age=now-(renderedAt[name]?:0L)
            runOnUiThread{
                view.text="Cam $name · ${cameras[name]?:-1}% · "+
                    if(age<1500)"H.264 en lecture · ${activeQualities[name].orEmpty()} · ${lastDimensions[name].orEmpty()}" else "direct interrompu / en attente"
            }
        }
        if(now-lastTelemetry<2000)return
        lastTelemetry=now
        try{
            if(now-lastClockSync>30000){clock.synchronize{api("GET","/api/v1/cam/clock")};lastClockSync=now}
            for(name in labels.keys){
                val fps=renderedRates[name]?.sample()?:0.0
                val fresh=now-(renderedAt[name]?:0L)<3000
                api("POST","/api/v1/cam/telemetry?side=viewer&account="+URLEncoder.encode(name,"UTF-8"),
                    JSONObject().put("renderedFps",fps).put("latencyMillis",if(fresh)latency[name]?:JSONObject.NULL else JSONObject.NULL)
                        .put("clockUncertaintyMillis",clock.uncertaintyUs/1000.0)
                        .put("interruptions",videoInterruptions[name]?:0L))
            }
            val metrics=api("GET","/api/v1/cam/metrics").optJSONArray("cameras")
            if(metrics!=null)for(i in 0 until metrics.length()){
                val item=metrics.getJSONObject(i)
                activeQualities[item.optString("account")]=item.optString("quality")
            }
        }catch(e:Exception){android.util.Log.w("ShizziCam","Telemetry unavailable",e)}
    }
    private fun renderInterphoneState() = runOnUiThread {
        if (!::interphoneState.isInitialized || !::talkButton.isInitialized) return@runOnUiThread
        val state=interphone.state
        val color=when(state.phase) {
            InterphoneSession.Phase.OFF -> Color.rgb(97,97,97)
            InterphoneSession.Phase.PERMISSION, InterphoneSession.Phase.CONNECTING -> Color.rgb(180,83,9)
            InterphoneSession.Phase.ACCEPTED -> Color.rgb(0,125,55)
            InterphoneSession.Phase.FAILED -> Color.rgb(183,28,28)
        }
        interphoneState.text=state.message
        interphoneState.setTextColor(color)
        talkButton.backgroundTintList=ColorStateList.valueOf(color)
        talkButton.setTextColor(Color.WHITE)
        talkButton.contentDescription="Parler. ${state.message}"
        stopTalkButton.isEnabled=state.streaming || state.phase==InterphoneSession.Phase.PERMISSION
    }
    private fun stopTalking(){
        val generation=interphone.state.generation
        interphone.stop()
        talkConnections[generation]?.disconnect()
        renderInterphoneState()
    }
    private fun failTalking(generation:Long,message:String){
        if(interphone.fail(generation,message)){
            talkConnections[generation]?.disconnect()
            renderInterphoneState()
        }
    }
    private fun pollInterphoneStatus(){
        val attempt=interphone.state
        if(!attempt.streaming || admin==null || !alive)return
        try{
            val list=api("GET","/api/v1/cam/metrics").optJSONArray("cameras")
            interphone.confirmationError(attempt.generation,"")
            if(list!=null)for(i in 0 until list.length()){
                val item=list.getJSONObject(i)
                if(item.optString("account")==attempt.target){
                    if(item.has("intercomAcceptedRouterUs")){
                        interphone.observe(attempt.generation,item.optLong("intercomAcceptedRouterUs"),
                            item.optLong("audioPlaybackRouterUs"),item.optBoolean("audioPlaybackVerified"))
                    }else{
                        interphone.observeLegacy(attempt.generation,clock.nowUs(),item.optDouble("intercomInKbps",0.0),
                            item.optLong("audioPlaybackRouterUs"),item.optBoolean("audioPlaybackVerified"))
                    }
                }
            }
        }catch(e:Exception){
            interphone.confirmationError(attempt.generation,e.message?:"routeur inaccessible")
            android.util.Log.w("ShizziCam","Interphone confirmation unavailable",e)
        }
        if(interphone.expire(clock.nowUs(),attempt.generation))talkConnections[attempt.generation]?.disconnect()
        renderInterphoneState()
    }
    private fun startTalking(){
        val cam=selected
        if(cam.isEmpty()||admin==null){
            status.text="Sélectionne une caméra connectée avant de parler"
            return
        }
        val granted=checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
        val attempt=interphone.request(cam,granted,clock.nowUs())?:return
        renderInterphoneState()
        if(!granted){
            permissionAttempt=attempt.generation
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),1080)
            return
        }
        captureTalk(attempt.generation,cam)
    }
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,grants:IntArray){
        super.onRequestPermissionsResult(code,permissions,grants)
        if(code!=1080)return
        val generation=permissionAttempt?:return
        permissionAttempt=null
        if(grants.isNotEmpty() && grants[0]==PackageManager.PERMISSION_GRANTED){
            if(interphone.permit(generation,clock.nowUs())){
                val attempt=interphone.state
                captureTalk(generation,attempt.target)
            }
        }else failTalking(generation,"autorisation microphone refusée")
        renderInterphoneState()
    }
    private fun captureTalk(generation:Long,cam:String){
        talkExecutor.execute{
            var record:AudioRecord?=null;var encoder:CamOpusEncoder?=null
            var connection:HttpsURLConnection?=null
            val queue=ArrayBlockingQueue<CamAudioPacket>(6)
            val sender=Executors.newSingleThreadExecutor()
            try{
                if(!interphone.streaming(generation))return@execute
                val session=ensureSession(cam)
                if(!interphone.streaming(generation))return@execute
                val endpoint="/api/v1/cam/talk-stream?account="+URLEncoder.encode(cam,"UTF-8")+"&session="+URLEncoder.encode(session,"UTF-8")
                val c=url(endpoint);connection=c;talkConnections[generation]=c
                if(!interphone.streaming(generation))return@execute
                c.requestMethod="POST";c.doOutput=true;c.readTimeout=0
                c.setRequestProperty("Content-Type","application/x-shizzicam-opus-v3");c.setChunkedStreamingMode(1024)
                sender.execute{
                    try{
                        val output=DataOutputStream(c.outputStream)
                        while(interphone.streaming(generation)&&alive){
                            val packet=queue.poll(300,TimeUnit.MILLISECONDS)?:continue
                            if(packet.flags==0&&clock.nowUs()-packet.ptsUs>180000)continue
                            packet.write(output)
                            if(packet.flags==0)interphone.sent(generation)
                        }
                    }catch(e:Exception){failTalking(generation,"échec de transmission : ${e.message}")}
                }
                val min=AudioRecord.getMinBufferSize(48000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
                check(min>0){"microphone 48 kHz indisponible"}
                record=AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,48000,AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,maxOf(3840,min))
                check(record.state==AudioRecord.STATE_INITIALIZED){"initialisation microphone impossible"}
                encoder=CamOpusEncoder();val buffer=ByteArray(1920);record.startRecording()
                check(record.recordingState==AudioRecord.RECORDSTATE_RECORDING){"capture microphone non démarrée"}
                while(interphone.streaming(generation)&&alive){
                    val n=record.read(buffer,0,buffer.size);check(n>0&&n%2==0){"capture microphone interrompue ($n)"}
                    interphone.captured(generation,n/2)
                    for(packet in encoder.encode(buffer.copyOf(n),clock.nowUs()-n*1000000L/96000)){
                        if(packet.flags==0)interphone.encoded(generation)
                        if(!queue.offer(packet)){queue.clear();encoder.configuration?.let{queue.offer(it)};queue.offer(packet)}
                    }
                }
            }catch(e:Exception){failTalking(generation,e.message?:"capture impossible")}
            finally{
                sender.shutdownNow();connection?.disconnect()
                connection?.let{talkConnections.remove(generation,it)}
                runCatching{record?.stop()};record?.release();encoder?.close()
            }
        }
    }
    private fun playSelectedAudio(){
        while(alive&&!Thread.currentThread().isInterrupted){
            val name=audioSelected
            if(admin==null||name.isEmpty()){try{Thread.sleep(150)}catch(_:InterruptedException){break};continue}
            var c:HttpsURLConnection?=null;var player:AudioTrack?=null
            val decoder=CamOpusDecoder()
            try{
                c=url("/api/v1/cam/audio-stream?account="+URLEncoder.encode(name,"UTF-8"));audioConnection=c;c.readTimeout=3000
                check(c.responseCode==200){"Écoute HTTP ${c.responseCode}"}
                player=CamAudioPlayback.create(this);player.play()
                val input=DataInputStream(c.inputStream)
                while(alive&&audioSelected==name){
                    val packet=CamAudioPacket.read(input)
                    val age=clock.nowUs()-packet.ptsUs
                    if(packet.flags==0&&age>350000)continue
                    if(packet.flags==0&&clock.ready&&age<110000)Thread.sleep((110000-age)/1000)
                    for(pcm in decoder.decode(packet))CamAudioPlayback.write(player,pcm)
                }
            }catch(_:InterruptedException){break}
            catch(e:Exception){if(audioSelected==name)runOnUiThread{status.text="Écoute : ${e.message}"};try{Thread.sleep(500)}catch(_:InterruptedException){break}}
            finally{c?.disconnect();if(audioConnection===c)audioConnection=null;decoder.close();player?.let{runCatching{it.stop()};it.release()}}
        }
    }

    override fun onDestroy(){
        interphone.stop()
        audioConnection?.disconnect();talkConnections.values.forEach{it.disconnect()}
        alive=false
        audioSelected=""
        talkExecutor.shutdownNow()
        poll.shutdownNow()
        frames.shutdownNow()
        streamGeneration.incrementAndGet()
        streamConnections.values.forEach{runCatching{it.disconnect()}}
        streamConnections.clear()
        videoThreads.shutdownNow()
        commands.shutdownNow()
        eventPoll.shutdownNow()
        audioExecutor.shutdownNow()
        super.onDestroy()
    }
}
