package com.example.androidbackupgui.backup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service to keep the process alive during long backup/restore operations.
 * Prevents Android from killing the app during extended operations.
 */
class BackupService : Service() {

    companion object {
        const val CHANNEL_ID = "backup_service_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START_BACKUP = "com.example.androidbackupgui.action.START_BACKUP"
        const val ACTION_STOP_BACKUP = "com.example.androidbackupgui.action.STOP_BACKUP"
        const val EXTRA_STATUS_TEXT = "status_text"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_BACKUP -> {
                val statusText = intent.getStringExtra(EXTRA_STATUS_TEXT) ?: "正在备份…"
                val notification = createNotification(statusText)
                startForeground(NOTIFICATION_ID, notification)
            }
            ACTION_STOP_BACKUP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "备份服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "后台备份任务持续运行通知"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Android Backup")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
