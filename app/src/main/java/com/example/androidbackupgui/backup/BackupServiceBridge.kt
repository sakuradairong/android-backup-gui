package com.example.androidbackupgui.backup

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * 抽象层：将 ViewModel 与 [BackupService] 之间的通信解耦。
 *
 * 问题背景：重构前 ViewModel 直接构造 Intent 并通过 [BackupService.Companion] 的
 * 10+ 个常量与 Service 通信，导致 ViewModel 与 Service 实现细节严重耦合。
 *
 * 解耦后：ViewModel 仅依赖本接口。测试时可注入 mock；未来切换 Service
 * 实现（如改用 WorkManager）只需替换实现类。
 *
 * 设计原则：
 * - 接口只暴露 ViewModel 真正需要的操作（最小化耦合面）
 * - 实现类 [AndroidBackupServiceBridge] 封装所有 Intent 构造细节
 * - 不向 ViewModel 泄漏 Intent extra 名称、action 字符串
 */
interface BackupServiceBridge {
    /**
     * 启动后台任务（备份/恢复/Restic 同步），显示前台通知。
     *
     * @param context 上下文（用于启动 Service）
     * @param taskId 任务唯一标识（用于取消）
     * @param taskType 任务类型（[TASK_TYPE_BACKUP] / [TASK_TYPE_RESTORE] / [TASK_TYPE_RESTIC]）
     * @param statusText 显示给用户的状态文本
     */
    fun startTask(
        context: Context,
        taskId: String,
        taskType: String,
        statusText: String,
    )

    /**
     * 更新任务进度通知。
     *
     * @param context 上下文
     * @param taskId 任务唯一标识
     * @param taskType 任务类型（同 [startTask]）
     * @param statusText 更新后的状态文本
     * @param current 当前完成项数；与 [total] 一起显示进度条
     * @param total 总项数；<=0 时不显示进度计数
     * @param percent 完成百分比（0f..1f）；非 null 时优先使用进度条模式
     */
    fun updateProgress(
        context: Context,
        taskId: String,
        taskType: String,
        statusText: String,
        current: Int,
        total: Int,
        percent: Float?,
    )

    /**
     * 停止后台任务并移除前台通知。
     */
    fun stopTask(context: Context)

    companion object {
        const val TASK_TYPE_BACKUP = "backup"
        const val TASK_TYPE_RESTORE = "restore"
        const val TASK_TYPE_RESTIC = "restic"
    }
}

/**
 * [BackupServiceBridge] 的 Android 实现，通过构造 [Intent] 与 [BackupService] 通信。
 *
 * 所有 Intent extra / action 字符串集中在此，ViewModel 不再需要直接引用
 * [BackupService.Companion]。
 */
class AndroidBackupServiceBridge : BackupServiceBridge {

    override fun startTask(
        context: Context,
        taskId: String,
        taskType: String,
        statusText: String,
    ) {
        try {
            val intent = Intent(context, BackupService::class.java).apply {
                action = BackupService.ACTION_START_TASK
                putExtra(BackupService.EXTRA_STATUS_TEXT, statusText)
                putExtra(BackupService.EXTRA_TASK_ID, taskId)
                putExtra(BackupService.EXTRA_TASK_TYPE, taskType)
            }
            ContextCompat.startForegroundService(context, intent)
        } catch (_: Exception) {
            // Service 启动失败不应阻塞业务逻辑（fallback 为后台执行）
        }
    }

    override fun updateProgress(
        context: Context,
        taskId: String,
        taskType: String,
        statusText: String,
        current: Int,
        total: Int,
        percent: Float?,
    ) {
        try {
            val intent = Intent(context, BackupService::class.java).apply {
                action = BackupService.ACTION_UPDATE_TASK
                putExtra(BackupService.EXTRA_STATUS_TEXT, statusText)
                putExtra(BackupService.EXTRA_TASK_ID, taskId)
                putExtra(BackupService.EXTRA_TASK_TYPE, taskType)
                putExtra(BackupService.EXTRA_PROGRESS_CURRENT, current)
                putExtra(BackupService.EXTRA_PROGRESS_TOTAL, total)
                percent?.let { putExtra(BackupService.EXTRA_PROGRESS_PERCENT, it) }
            }
            ContextCompat.startForegroundService(context, intent)
        } catch (_: Exception) {
            // 通知更新失败不应阻塞业务逻辑
        }
    }

    override fun stopTask(context: Context) {
        try {
            val intent = Intent(context, BackupService::class.java).apply {
                action = BackupService.ACTION_STOP_TASK
            }
            context.startService(intent)
        } catch (_: Exception) {
            // Service 停止失败时由系统回收
        }
    }
}
