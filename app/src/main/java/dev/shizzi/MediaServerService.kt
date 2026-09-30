package dev.shizzi

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class MediaServerService : Service() {
    private var server: MediaHttpServer? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        if (server != null) return
        server = MediaHttpServer(applicationContext).also { it.start() }
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification())
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

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MediaServerService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, MediaServerService::class.java).setAction(ACTION_STOP),
            )
        }

        fun restart(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MediaServerService::class.java).setAction(ACTION_RESTART),
            )
        }
    }
}
