package dev.shizzi.admin

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
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
import android.view.MotionEvent
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
    private lateinit var status: TextView
    private lateinit var tileHolder: LinearLayout
    private var wifi: Network? = null
    private var pin = ""
    private var admin: AdminApi? = null
    private val cameras = linkedMapOf<String, Int>()
    private val tileImages = mutableMapOf<String, ImageView>()
    private val sessions = mutableMapOf<String, String>()
    private val fetching = ConcurrentHashMap.newKeySet<String>()
    private val poll = Executors.newSingleThreadScheduledExecutor()
    private val frames = Executors.newFixedThreadPool(4)
    private val videoThreads = Executors.newFixedThreadPool(8)
    private val streamGeneration=AtomicInteger(0)
    private val tileTextures=mutableMapOf<String,TextureView>()
    private val streamConnections=ConcurrentHashMap<String,HttpsURLConnection>()
    private val commands = Executors.newSingleThreadExecutor()
    private val eventPoll = Executors.newSingleThreadScheduledExecutor()
    private val eventCursors = mutableMapOf<String,Long>()
    private val pendingCommands = ConcurrentHashMap<String,String>()
    private val audioExecutor = Executors.newSingleThreadExecutor()
    private val talkExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var talking = false
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
        poll.scheduleWithFixedDelay({ updateTiles() }, 750, 300, TimeUnit.MILLISECONDS)
        audioExecutor.execute { playSelectedAudio() }
        eventPoll.scheduleWithFixedDelay({ pollCommandStatus() }, 1, 1, TimeUnit.SECONDS)
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
        synchronized(sessions){sessions.clear();eventCursors.clear()}
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

    private fun mosaicScreen() {
        root.removeAllViews()
        root.addView(label("ShizziCam · Mosaïque",25f))
        root.addView(label("Images JPEG et audio Opus 64 kbps via le routeur — candidate de test"))
        val toolbar=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        toolbar.addView(button("Actualiser"){refresh()})
        toolbar.addView(button("Mise à jour"){checkLocalUpdate()})
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
        sound.addView(button("Écouter caméra"){audioSelected=selected;status.text="Écoute de la caméra $selected demandée"})
        sound.addView(button("Couper écoute"){audioSelected="";status.text="Écoute coupée"})
        sound.addView(button("Micro caméra"){
            micOn=!micOn
            sendControl("mic",if(micOn)"on" else "off")
        })
        sound.addView(Button(this).apply {
            text="Maintenir pour parler"
            setOnTouchListener { _,e ->
                when(e.actionMasked){
                    MotionEvent.ACTION_DOWN -> {startTalking();true}
                    MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> {talking=false;true}
                    else -> true
                }
            }
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
                check(size>1024*1024 && sha.matches(Regex("[0-9a-fA-F]{64}"))){"APK non vérifiée"}
                fun numbers(s:String)=s.substringBefore("-").split(".").mapNotNull{it.toIntOrNull()}
                val r=numbers(remote);val l=numbers(local)
                val upgrade=(0 until maxOf(r.size,l.size)).firstOrNull{
                    r.getOrElse(it){0}!=l.getOrElse(it){0}
                }?.let{r.getOrElse(it){0}>l.getOrElse(it){0}} ?: false
                runOnUiThread{
                    if(upgrade){
                        AlertDialog.Builder(this)
                            .setTitle("Mise à jour visionneuse")
                            .setMessage("ShizziCam Admin $remote disponible localement (${size/1048576} Mio).")
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
                override fun onSurfaceTextureUpdated(st:SurfaceTexture){}
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
                                decoder=CamH264Decoder(surface,w,h)
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
        selected=account
        // Select the first camera's microphone automatically. Only one is audible.
        if(audioSelected.isEmpty()) audioSelected=account
        status.text="Caméra $account sélectionnée · association…"
        renderGrid()
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
        synchronized(sessions){sessions[account]?.let{return it}}
        val sid=api("POST","/api/v1/cam/watch?side=viewer",
            JSONObject().put("account",account)).getString("session")
        synchronized(sessions){
            sessions[account]=sid
            eventCursors[account]=0L
        }
        return sid
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
        val sid= synchronized(sessions){sessions[active]} ?:return
        try {
            val after=synchronized(sessions){eventCursors[active] ?:0L}
            val events=api("GET","/api/v1/cam/events?side=viewer&account="+
                URLEncoder.encode(active,"UTF-8")+"&session="+URLEncoder.encode(sid,"UTF-8")+
                "&after="+after).optJSONArray("events") ?:return
            for(i in 0 until events.length()){
                val e=events.getJSONObject(i)
                val id=e.optLong("id")
                synchronized(sessions){eventCursors[active]=maxOf(eventCursors[active] ?:0,id)}
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
            android.util.Log.w("ShizziCam","Command acknowledgement unavailable",e)
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

    /** Push-to-talk, 64 kbps Opus via router. One chosen camera at a time. */
    private fun startTalking(){
        val cam=selected
        if(cam.isEmpty() || admin==null) {
            status.text="Sélectionne une caméra avant de parler"
            return
        }
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            status.text="Autorise le microphone pour utiliser l'interphone"
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),1080)
            return
        }
        if(talking)return
        talking=true
        talkExecutor.execute {
            var record:AudioRecord?=null
            var encoder:CamOpusEncoder?=null
            try {
                val session=ensureSession(cam)
                if(!talking || !alive)return@execute
                val rate=48000
                val min=AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT)
                check(min>0){"Microphone 48 kHz indisponible"}
                record=AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,rate,
                    AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,maxOf(3840,min*2))
                check(record.state==AudioRecord.STATE_INITIALIZED){"Microphone interphone indisponible"}
                encoder=CamOpusEncoder()
                val buffer=ByteArray(1920)
                record.startRecording()
                runOnUiThread{status.text="Interphone actif : $cam (Opus 64 kbps)"}
                while(talking&&alive&&!Thread.currentThread().isInterrupted){
                    val n=record.read(buffer,0,buffer.size)
                    if(n<=0)continue
                    for(packet in encoder.encode(buffer.copyOf(n))) {
                        if(!talking || packet.isEmpty())break
                        val endpoint="/api/v1/cam/talk?account="+URLEncoder.encode(cam,"UTF-8")+
                            "&session="+URLEncoder.encode(session,"UTF-8")
                        val c=url(endpoint)
                        try {
                            c.requestMethod="POST"
                            c.doOutput=true
                            c.setRequestProperty("Content-Type","audio/opus")
                            c.setFixedLengthStreamingMode(packet.size)
                            c.outputStream.use{it.write(packet)}
                            if(c.responseCode!=200)error("Interphone : HTTP "+c.responseCode)
                        }finally{c.disconnect()}
                    }
                }
            }catch(e:Exception){
                android.util.Log.w("ShizziCam","Talk transmission failed",e)
                runOnUiThread{status.text="Interphone : "+(e.message?:"indisponible")}
            }finally{
                talking=false
                runCatching{record?.stop()}
                record?.release()
                encoder?.close()
            }
        }
    }

    private fun playSelectedAudio(){
        val rate=48000
        val decoder=try { CamOpusDecoder() }catch(e:Exception){
            android.util.Log.e("ShizziCam","Audio Opus indisponible",e)
            runOnUiThread {status.text="Décodage Opus indisponible sur cet appareil"}
            return
        }
        val required=AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT)
        if(required<=0)return
        val player=try {
            AudioTrack(AudioManager.STREAM_MUSIC,rate,AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,maxOf(required,12800),AudioTrack.MODE_STREAM)
        }catch(e:Exception){android.util.Log.e("ShizziCam","AudioTrack unavailable",e);return}
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
                    if(previous.isNotEmpty()) player.pause()
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
                            if(seq>after && bytes.isNotEmpty() && bytes.size<=4096) {
                                after=seq
                                for(pcm in decoder.decode(bytes)){
                                    val wrote=player.write(pcm,0,pcm.size)
                                    if(wrote<0)android.util.Log.e("ShizziCam","AudioTrack error $wrote")
                                }
                            }
                        } else if(c.responseCode!=204) {
                            android.util.Log.w("ShizziCam","Audio HTTP "+c.responseCode)
                            if(c.responseCode==401 || c.responseCode==403)runOnUiThread {status.text="Son : accès refusé (HTTP "+c.responseCode+")"}
                        }
                    }finally{c.disconnect()}
                }catch(e:Exception){
                    android.util.Log.w("ShizziCam","Audio relay read failed",e)
                }
                Thread.sleep(25)
            }
        }catch(_:InterruptedException){}finally{
            try{player.pause();player.flush();player.release()}catch(_:Exception){}
            decoder.close()
        }
    }
    override fun onDestroy(){
        alive=false
        audioSelected=""
        talking=false
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
