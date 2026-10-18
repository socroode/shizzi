package dev.shizzi.conso

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import java.net.HttpURLConnection
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Explicitly enabled local router connection. Microphone and camera belong to the visible call UI. */
class CallReceptionService : Service() {
    private lateinit var notifications: NotificationManager
    private lateinit var worker: ScheduledExecutorService
    private val main = Handler(Looper.getMainLooper())
    private val protocol = CallReceptionProtocol()
    private var foreground = false
    private var scheduled = false
    private var displayed = ""
    private var lastStatus = ""
    private var ringtone: Ringtone? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastWakeMillis = 0L
    @Volatile private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        notifications = getSystemService(NotificationManager::class.java)
        worker = Executors.newSingleThreadScheduledExecutor()
        notifications.createNotificationChannel(NotificationChannel(CHANNEL_CONNECTION, "Réception des appels Shizzi", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Connexion locale au routeur pour recevoir les appels en arrière-plan."
            setSound(null, null)
        })
        notifications.createNotificationChannel(NotificationChannel(CHANNEL_INCOMING, "Appels Shizzi entrants", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Sonnerie et boutons pour répondre ou refuser un appel local."
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!isEnabled(this) || !notificationsAllowed(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!foreground) {
            val notification = listeningNotification("Recherche du routeur Shizzi…")
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_CONNECTION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(NOTIFICATION_CONNECTION, notification)
            foreground = true
        }
        if (intent?.action == ACTION_REJECT) {
            val id = intent.getStringExtra(EXTRA_CALL_ID).orEmpty()
            if (id == currentCallId && id.isNotBlank()) {
                protocol.silence()
                stopAlert()
                worker.execute {
                    runCatching { transport().reject(id) }
                    pollOnce()
                }
            }
        }
        if (!scheduled) {
            scheduled = true
            worker.scheduleWithFixedDelay({ pollOnce() }, 0, 1_500, TimeUnit.MILLISECONDS)
        }
        return START_STICKY
    }

    private fun transport(): CallReceptionTransport {
        transportForTests?.let { return it }
        val manager = getSystemService(ConnectivityManager::class.java)
        val network = manager.allNetworks.firstOrNull {
            manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: error("Connecte ce téléphone au Wi-Fi Shizzi.")
        return CallReceptionHttpClient { url -> network.openConnection(url) as HttpURLConnection }
    }

    private fun pollOnce() {
        if (destroyed) return
        try {
            val snapshot = transport().poll()
            main.post {
                if (destroyed) return@post
                try {
                    val incoming = protocol.update(snapshot)
                    ready = true
                    holdConnectionLocks()
                    updateStatus("Réception active · compte " + protocol.account)
                    if (currentCallId != protocol.incoming?.id.orEmpty()) actionToken = UUID.randomUUID().toString()
                    currentCallId = protocol.incoming?.id.orEmpty()
                    if (incoming == null) stopAlert() else showIncoming(incoming)
                } catch (failure: Exception) { connectionFailed(failure.message.orEmpty()) }
            }
        } catch (failure: Exception) {
            main.post { if (!destroyed) connectionFailed(failure.message ?: "Connexion Shizzi interrompue.") }
        }
    }

    private fun connectionFailed(message: String) {
        ready = false
        protocol.reset()
        currentCallId = ""
        actionToken = ""
        stopAlert()
        releaseConnectionLocks()
        updateStatus(message)
    }

    private fun updateStatus(message: String) {
        status = message
        if (lastStatus == message) return
        lastStatus = message
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        notifications.notify(NOTIFICATION_CONNECTION, listeningNotification(message))
    }

    private fun listeningNotification(message: String): Notification = Notification.Builder(this, CHANNEL_CONNECTION)
        .setSmallIcon(R.drawable.ic_shizzi_call)
        .setContentTitle("Shizzi+ · appels en arrière-plan")
        .setContentText(message).setOngoing(true).setOnlyAlertOnce(true)
        .setContentIntent(PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .addAction(Notification.Action.Builder(null, "Arrêter la réception", serviceAction(ACTION_STOP, "", 2)).build())
        .build()

    private fun showIncoming(call: IncomingCall) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (displayed == call.id) return
        stopAlert()
        displayed = call.id
        val answer = activityAction(ACTION_ANSWER, call.id, 3)
        val decline = serviceAction(ACTION_REJECT, call.id, 4)
        val caller = Person.Builder().setName(call.name).setImportant(true).build()
        val builder = Notification.Builder(this, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_shizzi_call)
            .setContentTitle(call.name)
            .setContentText(if (call.video) "Appel vidéo Shizzi" else "Appel vocal Shizzi")
            .setCategory(Notification.CATEGORY_CALL).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(activityAction(ACTION_OPEN, call.id, 5))
        if (Build.VERSION.SDK_INT >= 31) builder.setStyle(Notification.CallStyle.forIncomingCall(caller, decline, answer))
        else builder.addPerson(caller)
            .addAction(Notification.Action.Builder(null, "Refuser", decline).build())
            .addAction(Notification.Action.Builder(null, "Répondre", answer).build())
        notifications.notify(NOTIFICATION_INCOMING, builder.build())

        val audio = getSystemService(AudioManager::class.java)
        if (notifications.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL) {
            if (audio.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                    audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                    isLooping = true
                    play()
                }
            }
            if (audio.ringerMode != AudioManager.RINGER_MODE_SILENT) {
                getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 200, 300, 1_200), 0))
            }
        }
    }

    private fun activityAction(action: String, callId: String, code: Int): PendingIntent = PendingIntent.getActivity(this, code,
        Intent(this, MainActivity::class.java).setAction(action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_CALL_ID, callId).putExtra(EXTRA_ACTION_TOKEN, actionToken),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun serviceAction(action: String, callId: String, code: Int): PendingIntent = PendingIntent.getForegroundService(this, code,
        Intent(this, CallReceptionService::class.java).setAction(action).putExtra(EXTRA_CALL_ID, callId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun stopAlert() {
        runCatching { ringtone?.stop() }; ringtone = null
        getSystemService(Vibrator::class.java)?.cancel()
        notifications.cancel(NOTIFICATION_INCOMING)
        displayed = ""
    }

    private fun holdConnectionLocks() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (wakeLock?.isHeld != true || now - lastWakeMillis > 60_000) {
            wakeLock?.let { if (it.isHeld) it.release() }
            if (wakeLock == null) wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:call-reception").apply { setReferenceCounted(false) }
            wakeLock?.acquire(120_000)
            lastWakeMillis = now
        }
        if (wifiLock == null) wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$packageName:call-wifi").apply { setReferenceCounted(false) }
        if (wifiLock?.isHeld != true) wifiLock?.acquire()
    }

    private fun releaseConnectionLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
    }

    override fun onDestroy() {
        destroyed = true
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
        stopAlert()
        releaseConnectionLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        instance = null
        ready = false
        currentCallId = ""; actionToken = ""; status = "Réception arrêtée"
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_ANSWER = "dev.shizzi.conso.ANSWER_CALL"
        const val ACTION_OPEN = "dev.shizzi.conso.OPEN_CALL"
        private const val ACTION_REJECT = "dev.shizzi.conso.REJECT_CALL"
        private const val ACTION_STOP = "dev.shizzi.conso.STOP_RECEPTION"
        const val EXTRA_CALL_ID = "call_id"
        private const val EXTRA_ACTION_TOKEN = "call_action_token"
        const val NOTIFICATION_CONNECTION = 7101
        const val NOTIFICATION_INCOMING = 7102
        private const val CHANNEL_CONNECTION = "shizzi_call_reception"
        private const val CHANNEL_INCOMING = "shizzi_incoming_calls"
        @Volatile private var instance: CallReceptionService? = null
        @Volatile private var actionToken = ""
        @Volatile var currentCallId = ""; private set
        @Volatile var ready = false; private set
        @Volatile var status = "Réception arrêtée"; private set
        @Volatile internal var transportForTests: CallReceptionTransport? = null
        val running: Boolean get() = instance != null
        fun isEnabled(context: Context): Boolean = context.getSharedPreferences("call_reception", Context.MODE_PRIVATE).getBoolean("enabled", false)
        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences("call_reception", Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
        }
        fun notificationsAllowed(context: Context): Boolean =
            (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
                context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        fun start(context: Context) { context.startForegroundService(Intent(context, CallReceptionService::class.java)) }
        fun stop(context: Context) { context.stopService(Intent(context, CallReceptionService::class.java)) }
        fun silenceCurrent() { instance?.let { service -> service.main.post { service.protocol.silence(); service.stopAlert() } } }
        fun validActivityIntent(intent: Intent): Boolean = currentCallId.isNotBlank() &&
            intent.getStringExtra(EXTRA_CALL_ID) == currentCallId && actionToken.isNotBlank() &&
            intent.getStringExtra(EXTRA_ACTION_TOKEN) == actionToken
    }
}
