package com.example.androidbackupgui.backup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class BackupService : Service() {

    companion object {
        const val CHANNEL_ID = "backup_service_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START_BACKUP = "com.example.androidbackupgui.action.START_BACKUP"
        const val ACTION_STOP_BACKUP = "com.example.androidbackupgui.action.STOP_BACKUP"
        const val ACTION_START_TASK = "com.example.androidbackupgui.action.START_TASK"
        const val ACTION_UPDATE_TASK = "com.example.androidbackupgui.action.UPDATE_TASK"
        const val ACTION_CANCEL_TASK = "com.example.androidbackupgui.action.CANCEL_TASK"
        const val ACTION_STOP_TASK = "com.example.androidbackupgui.action.STOP_TASK"
        const val EXTRA_STATUS_TEXT = "status_text"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_TASK_TYPE = "task_type"
        const val EXTRA_PROGRESS_CURRENT = "progress_current"
        const val EXTRA_PROGRESS_TOTAL = "progress_total"
        const val EXTRA_PROGRESS_PERCENT = "progress_percent"

        const val TASK_TYPE_BACKUP = "backup"
        const val TASK_TYPE_RESTORE = "restore"
        const val TASK_TYPE_RESTIC = "restic"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_BACKUP -> {
                val statusText = intent.getStringExtra(EXTRA_STATUS_TEXT) ?: "正在备份…"
                startForeground(NOTIFICATION_ID, createNotification(statusText, TASK_TYPE_BACKUP))
            }
            ACTION_START_TASK -> {
                val statusText = intent.getStringExtra(EXTRA_STATUS_TEXT) ?: "正在处理…"
                val taskType = intent.getStringExtra(EXTRA_TASK_TYPE) ?: TASK_TYPE_BACKUP
                startForeground(NOTIFICATION_ID, createNotification(statusText, taskType))
            }
            ACTION_UPDATE_TASK -> {
                val statusText = intent.getStringExtra(EXTRA_STATUS_TEXT) ?: "正在处理…"
                val taskType = intent.getStringExtra(EXTRA_TASK_TYPE) ?: TASK_TYPE_BACKUP
                val current = intent.getIntExtra(EXTRA_PROGRESS_CURRENT, 0)
                val total = intent.getIntExtra(EXTRA_PROGRESS_TOTAL, 0)
                val percent = if (intent.hasExtra(EXTRA_PROGRESS_PERCENT)) {
                    intent.getFloatExtra(EXTRA_PROGRESS_PERCENT, 0f)
                } else null
                val notification = createNotification(statusText, taskType, current, total, percent)
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, notification)
            }
            ACTION_CANCEL_TASK -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID)
                if (taskId != null) {
                    TaskCancellationRegistry.cancel(taskId)
                }
            }
            ACTION_STOP_BACKUP, ACTION_STOP_TASK -> {
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
                description = "后台任务持续运行通知"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(
        text: String,
        taskType: String = TASK_TYPE_BACKUP,
        current: Int = 0,
        total: Int = 0,
        percent: Float? = null,
    ): Notification {
        val title = when (taskType) {
            TASK_TYPE_BACKUP -> "Android Backup - 备份中"
            TASK_TYPE_RESTORE -> "Android Backup - 恢复中"
            TASK_TYPE_RESTIC -> "Android Backup - Restic 同步中"
            else -> "Android Backup"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (total > 0 && current > 0) {
            builder.setProgress(total, current, false)
        } else if (percent != null) {
            builder.setProgress(100, (percent * 100).toInt(), false)
        } else {
            builder.setProgress(0, 0, true)
        }

        val cancelIntent = Intent(this, BackupService::class.java).apply {
            action = ACTION_CANCEL_TASK
        }
        val cancelFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val cancelPendingIntent = PendingIntent.getService(this, 0, cancelIntent, cancelFlags)
        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            "取消",
            cancelPendingIntent
        )

        return builder.build()
    }
}
