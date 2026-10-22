package dev.shizzi.conso

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.ImageView
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

/** Optional camera feature. Legacy Shizzi 0.4.3.5.11 login/chat/media remain available. */
class CamActivity:Activity() {
    private lateinit var status:TextView
    private lateinit var quality:Spinner
    private lateinit var preview:ImageView
    private var zoomLevel=1f
    private var torchEnabled=false
    @Volatile private var busy=false
    private var service:CameraCaptureService?=null
    private var bound=false
    private val handler=Handler(Looper.getMainLooper())
    private val statusRefresh=object:Runnable{
        override fun run(){
            service?.let{status.text=it.captureStatus}
            if(!isFinishing)handler.postDelayed(this,900)
        }
    }
    private val serviceConnection=object:ServiceConnection{
        override fun onServiceConnected(name:ComponentName,binder:IBinder){
            val local=binder as? CameraCaptureService.LocalBinder ?:return
            service=local.service()
            service?.attachPreview(preview)
            status.text=service?.captureStatus ?: "Caméra indisponible"
        }
        override fun onServiceDisconnected(name:ComponentName){service=null}
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        val root=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            gravity=Gravity.CENTER_HORIZONTAL
            setPadding(24,32,24,20)
        }
        root.addView(TextView(this).apply{text="Shizzi+Cam";textSize=26f})
        root.addView(TextView(this).apply{
            text="Aperçu réel sur ce téléphone. Images et son transmis uniquement au téléphone routeur ShizziCam par HTTPS. Mode relais de test : JPEG + PCM16. Le compte doit être connecté."
            textSize=14f
        })
        preview=ImageView(this).apply{
            scaleType=ImageView.ScaleType.FIT_CENTER
            adjustViewBounds=true
            layoutParams=LinearLayout.LayoutParams(-1,resources.displayMetrics.widthPixels*9/16)
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        root.addView(preview)
        val buttons=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        buttons.addView(Button(this).apply{text="Dos";setOnClickListener{service?.controlLocal("camera","back")}})
        buttons.addView(Button(this).apply{text="Selfie";setOnClickListener{service?.controlLocal("camera","front")}})
        root.addView(buttons)
        val controls=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        val zoomText=TextView(this).apply{text="Zoom 1.0×";gravity=Gravity.CENTER}
        controls.addView(Button(this).apply{text="−";setOnClickListener{
            zoomLevel=(zoomLevel-0.5f).coerceAtLeast(1f)
            zoomText.text="Zoom %.1f×".format(zoomLevel)
            service?.controlLocal("zoom",zoomLevel.toString())
        }})
        controls.addView(zoomText)
        controls.addView(Button(this).apply{text="+";setOnClickListener{
            zoomLevel=(zoomLevel+0.5f).coerceAtMost(8f)
            zoomText.text="Zoom %.1f×".format(zoomLevel)
            service?.controlLocal("zoom",zoomLevel.toString())
        }})
        controls.addView(Button(this).apply{text="Flash";setOnClickListener{
            torchEnabled=!torchEnabled
            service?.controlLocal("torch",if(torchEnabled)"on" else "off")
        }})
        root.addView(controls)
        quality=Spinner(this).apply{
            adapter=ArrayAdapter(this@CamActivity,android.R.layout.simple_spinner_dropdown_item,
                listOf("Auto","360p","480p","720p"))
        }
        root.addView(quality)
        root.addView(Button(this).apply {
            text="Vérifier les mises à jour locales"
            setOnClickListener { checkLocalUpdate() }
        })
        status=TextView(this).apply{text="Caméra désactivée";textSize=15f;setPadding(0,14,0,14)}
        root.addView(status)
        root.addView(Button(this).apply{
            text="Activer caméra + microphone"
            setOnClickListener{ensurePermissions()}
        })
        root.addView(Button(this).apply{
            text="Arrêter caméra + microphone"
            setOnClickListener{
                startService(Intent(this@CamActivity,CameraCaptureService::class.java)
                    .setAction(CameraCaptureService.ACTION_STOP))
                status.text="Arrêt demandé"
            }
        })
        setContentView(android.widget.ScrollView(this).apply{addView(root)})
    }
    override fun onStart(){
        super.onStart()
        bindExisting()
        handler.post(statusRefresh)
    }
    override fun onStop(){
        handler.removeCallbacks(statusRefresh)
        if(bound){
            service?.detachPreview(preview)
            unbindService(serviceConnection)
            service=null
            bound=false
        }
        super.onStop()
    }
    private fun bindExisting(){
        if(bound)return
        bound=bindService(Intent(this,CameraCaptureService::class.java),serviceConnection,0)
    }
    private fun bindStarted(){
        if(bound)return
        bound=bindService(Intent(this,CameraCaptureService::class.java),
            serviceConnection,Context.BIND_AUTO_CREATE)
    }
    private fun wifi():Network?{
        val cm=getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.firstOrNull{
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true
        }
    }
    private fun ensurePermissions(){
        if(busy)return
        // Notification permission is optional on Android 13+: do not block FGS/camera
        // for vendor-specific notification behavior.
        val needed=listOf(Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO)
        val missing=needed.filter{checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED}
        if(missing.isNotEmpty())requestPermissions(missing.toTypedArray(),713)
        else pairAndStart()
    }
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,grants:IntArray){
        super.onRequestPermissionsResult(code,permissions,grants)
        if(code==713){
            if(grants.size==permissions.size && grants.all{it==PackageManager.PERMISSION_GRANTED})pairAndStart()
            else status.text="Autorise la caméra et le microphone dans les paramètres Android."
        }
    }
    private fun checkLocalUpdate() {
        thread(name="shizzicam-check-update") {
            try {
                val network=wifi() ?: error("Connexion Wi-Fi Shizzi requise")
                val cert=RouterTls.pair(this,network)
                LocalCamUpdater.checkAndOffer(this,"client",cert,network){ msg ->
                    runOnUiThread { status.text=msg }
                }
            } catch(e:Exception){
                runOnUiThread{status.text="Mise à jour : "+(e.message ?: "indisponible")}
            }
        }
    }
    private fun pairAndStart(){
        if(busy)return
        busy=true
        val requested=listOf("auto","360p","480p","720p")[quality.selectedItemPosition]
        status.text="Contrôle de la compatibilité du routeur…"
        thread(name="shizzicam-compat"){
            try {
                val network=wifi() ?:error("Connecte-toi au Wi-Fi Shizzi.")
                val pin=RouterTls.pair(this,network)
                val c=RouterTls.connection("https://192.0.2.1/api/v1/cam/capabilities",pin,network)
                val supported=try{
                    c.connectTimeout=5000;c.readTimeout=5000
                    val httpStatus=c.responseCode
                    CamCapability.supports(httpStatus,if(httpStatus==200)c.inputStream.bufferedReader(StandardCharsets.UTF_8).use{it.readText()} else "")
                }finally{c.disconnect()}
                if(!supported)error("Mode caméra indisponible sur ce routeur. Shizzi+ continue de fonctionner normalement.")
                runOnUiThread{
                    if(!isFinishing){
                        startForegroundService(Intent(this,CameraCaptureService::class.java)
                            .putExtra(CameraCaptureService.EXTRA_PIN,pin)
                            .putExtra(CameraCaptureService.EXTRA_QUALITY,requested))
                        bindStarted()
                        status.text="Démarrage caméra + microphone demandé"
                    }
                }
            }catch(e:Exception){
                runOnUiThread{status.text=e.message ?: "Caméra indisponible"}
            }finally{busy=false}
        }
    }
}
