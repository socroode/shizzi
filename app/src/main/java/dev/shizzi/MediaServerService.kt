package dev.shizzi

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class MediaServerService : Service() {
    private var server: MediaHttpServer? = null
    private var folderSnapshot: List<MediaFolderConfig> = emptyList()

    override fun onCreate() {
        super.onCreate()
        folderSnapshot = MediaFolderStore.load(this)
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!RouterActivation.isActivated(this)) {
            stopServer()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        syncConfig(intent)
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> {
                MediaPrefs.setEnabled(this, false)
                stopServer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_RESTART -> {
                MediaPrefs.setEnabled(this, true)
                stopServer()
                startServer()
            }

            else -> {
                MediaPrefs.setEnabled(this, true)
                startServer()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startServer() {
        val current = server
        if (current?.isListening() == true) return
        if (current != null) {
            Log.w(TAG, "media listener stale; rebuilding loopback server")
            current.stop()
            server = null
        }
        val candidate = MediaHttpServer(
            context = applicationContext,
            folderSnapshot = folderSnapshot,
        )
        if (!candidate.start()) {
            Log.e(TAG, "media server did not start")
            return
        }
        server = candidate
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification())
    }

    private fun syncConfig(intent: Intent?) {
        if (intent == null) return

        intent.getStringExtra(EXTRA_FOLDER_SNAPSHOT)
            ?.takeIf { it.isNotBlank() }
            ?.let { raw ->
                folderSnapshot = MediaFolderStore.decodeSnapshot(raw)
            }

        MediaKind.entries.forEach { kind ->
            val key = extraTree(kind)
            if (!intent.hasExtra(key)) return@forEach
            val value = intent.getStringExtra(key).orEmpty()
            MediaPrefs.setTreeUri(
                this,
                kind,
                value.takeIf { it.isNotBlank() }?.let(Uri::parse),
            )
        }
    }

    private fun stopServer() {
        server?.stop()
        server = null
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Shizzi Media",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Serveur multimédia local Shizzi"
                setShowBadge(false)
            },
        )
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_tile_tethering)
        .setContentTitle("Shizzi Media actif")
        .setContentText("Films, séries et musique disponibles sur le hotspot local")
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MediaActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    companion object {
        private const val CHANNEL_ID = "shizzi_media"
        private const val NOTIFICATION_ID = 43
        private const val ACTION_START = "dev.shizzi.media.START"
        private const val ACTION_STOP = "dev.shizzi.media.STOP"
        private const val ACTION_RESTART = "dev.shizzi.media.RESTART"
        private const val TAG = "ShizziMedia"
        internal const val EXTRA_FOLDER_SNAPSHOT = "media_folder_snapshot_v2"

        private fun extraTree(kind: MediaKind) = "tree_${kind.key}"

        private fun configuredIntent(context: Context, action: String): Intent =
            Intent(context, MediaServerService::class.java)
                .setAction(action)
                .apply {
                    putExtra(
                        EXTRA_FOLDER_SNAPSHOT,
                        MediaFolderStore.encodeSnapshot(MediaFolderStore.load(context)),
                    )
                    MediaKind.entries.forEach { kind ->
                        putExtra(
                            extraTree(kind),
                            MediaPrefs.treeUri(context, kind)?.toString().orEmpty(),
                        )
                    }
                }

        fun start(context: Context) {
            if (!RouterActivation.isActivated(context)) return
            ContextCompat.startForegroundService(
                context,
                configuredIntent(context, ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, MediaServerService::class.java).setAction(ACTION_STOP),
            )
        }

        fun restart(context: Context) {
            if (!RouterActivation.isActivated(context)) return
            ContextCompat.startForegroundService(
                context,
                configuredIntent(context, ACTION_RESTART),
            )
        }
    }
}
