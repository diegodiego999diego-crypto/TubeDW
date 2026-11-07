package com.opentube.tubedw.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.opentube.tubedw.R
import com.opentube.tubedw.data.DownloadManager
import com.opentube.tubedw.data.DownloadTask
import com.opentube.tubedw.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Mantiene viva la app mientras hay descargas, con una notificación de progreso.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var collectJob: Job? = null
    private var foreground = false
    private var sawActive = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        collectJob = scope.launch {
            DownloadManager.state.collect { render(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foreground) startAsForeground(progressNotification(-1, getString(R.string.notify_downloading)))
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        collectJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun render(tasks: List<DownloadTask>) {
        val active = tasks.filter { it.active }

        if (active.isNotEmpty()) {
            sawActive = true
            val current = active.firstOrNull { it.status == com.opentube.tubedw.data.DownloadStatus.RUNNING }
                ?: active.first()
            val label = if (active.size > 1) {
                "${active.size} descargas en curso"
            } else {
                current.title
            }
            val progress = if (current.progress in 0f..99.99f) current.progress.toInt() else -1
            show(progressNotification(progress, label))
            return
        }

        if (sawActive) {
            sawActive = false
            val failed = tasks.count {
                it.status == com.opentube.tubedw.data.DownloadStatus.FAILED
            }
            val text = when {
                failed > 0 -> "$failed descarga${if (failed > 1) "s" else ""} con error"
                else -> getString(R.string.download_done)
            }
            show(doneNotification(text))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            stopSelf()
        }
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
        foreground = true
    }

    private fun progressNotification(progress: Int, text: String): Notification {
        val builder = baseNotification(text)
            .setContentTitle(getString(R.string.notify_downloading))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (progress >= 0) builder.setProgress(100, progress, false)
        else builder.setProgress(0, 0, true)
        return builder.build()
    }

    private fun doneNotification(text: String): Notification =
        baseNotification(text)
            .setContentTitle(getString(R.string.download_done))
            .setOngoing(false)
            .setAutoCancel(true)
            .build()

    private fun baseNotification(text: String): NotificationCompat.Builder {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
    }

    private fun show(notification: Notification) {
        val manager = NotificationManagerCompat.from(this)
        // Sin permiso de notificaciones Android la descarta sola; no hace falta
        // cancelar nada porque la del servicio en primer plano la gestiona el SO.
        if (Build.VERSION.SDK_INT < 33 || manager.areNotificationsEnabled()) {
            manager.notify(NOTIF_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notify_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notify_channel_desc)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "tubedw_downloads"
        private const val NOTIF_ID = 4211

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
