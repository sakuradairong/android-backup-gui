package com.example.androidbackupgui.backup

/**
 * 备份进度跟踪器 - 提供详细的进度信息和 ETA 估算。
 *
 * 使用指数移动平均 (EMA) 算法估算剩余时间，
 * 平滑处理单个应用备份时间的波动。
 */
class BackupProgressTracker(private val totalApps: Int) {

    data class ProgressInfo(
        val current: Int,
        val total: Int,
        val percent: Float,
        val etaSeconds: Long,
        val packageName: String,
        val stage: String,
        val message: String,
        val elapsedMs: Long,
        val currentAppElapsedMs: Long,
    )

    private var completedApps = 0
    private var currentPackage = ""
    private var currentStage = ""
    private var currentMessage = ""
    private var startTime = 0L
    private var currentAppStartTime = 0L
    private var lastAppDuration = 0L

    // EMA 参数：alpha 越大，对最新观测值越敏感
    private val alpha = 0.3
    private var emaDuration = 0.0

    init {
        startTime = System.currentTimeMillis()
    }

    /**
     * 开始备份新应用。
     */
    fun startApp(packageName: String) {
        currentPackage = packageName
        currentStage = "starting"
        currentMessage = "准备备份..."
        currentAppStartTime = System.currentTimeMillis()
    }

    /**
     * 更新当前阶段。
     */
    fun updateStage(stage: String, message: String) {
        currentStage = stage
        currentMessage = message
    }

    /**
     * 完成当前应用备份。
     */
    fun completeApp() {
        completedApps++
        val appDuration = System.currentTimeMillis() - currentAppStartTime
        lastAppDuration = appDuration

        // 更新 EMA
        emaDuration = if (emaDuration == 0.0) {
            appDuration.toDouble()
        } else {
            alpha * appDuration + (1 - alpha) * emaDuration
        }
    }

    /**
     * 跳过当前应用（增量备份）。
     */
    fun skipApp(packageName: String, reason: String) {
        currentPackage = packageName
        currentStage = "skipped"
        currentMessage = reason
        completedApps++
    }

    /**
     * 获取当前进度信息。
     */
    fun getProgress(): ProgressInfo {
        val now = System.currentTimeMillis()
        val elapsed = now - startTime
        val currentAppElapsed = now - currentAppStartTime

        val percent = if (totalApps > 0) {
            (completedApps.toFloat() / totalApps) * 100f
        } else {
            0f
        }

        val etaSeconds = if (completedApps > 0 && totalApps > completedApps) {
            val remainingApps = totalApps - completedApps
            val avgDuration = emaDuration.toLong()
            val remainingMs = remainingApps * avgDuration
            remainingMs / 1000
        } else {
            0L
        }

        return ProgressInfo(
            current = completedApps,
            total = totalApps,
            percent = percent,
            etaSeconds = etaSeconds,
            packageName = currentPackage,
            stage = currentStage,
            message = currentMessage,
            elapsedMs = elapsed,
            currentAppElapsedMs = currentAppElapsed,
        )
    }

    /**
     * 获取已用时间（秒）。
     */
    fun getElapsedSeconds(): Long {
        return (System.currentTimeMillis() - startTime) / 1000
    }

    /**
     * 获取完成的应用数量。
     */
    fun getCompletedCount(): Int {
        return completedApps
    }

    /**
     * 获取剩余应用数量。
     */
    fun getRemainingCount(): Int {
        return totalApps - completedApps
    }

    /**
     * 检查是否所有应用都已处理。
     */
    fun isComplete(): Boolean {
        return completedApps >= totalApps
    }

    /**
     * 重置跟踪器（用于新的备份会话）。
     */
    fun reset() {
        completedApps = 0
        currentPackage = ""
        currentStage = ""
        currentMessage = ""
        startTime = System.currentTimeMillis()
        currentAppStartTime = 0L
        lastAppDuration = 0L
        emaDuration = 0.0
    }

    /**
     * 格式化 ETA 为人类可读的字符串。
     */
    fun formatEta(seconds: Long): String {
        if (seconds <= 0) return "计算中..."

        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60

        return when {
            hours > 0 -> "${hours}小时${minutes}分${secs}秒"
            minutes > 0 -> "${minutes}分${secs}秒"
            else -> "${secs}秒"
        }
    }

    /**
     * 格式化已用时间。
     */
    fun formatElapsed(ms: Long): String {
        val seconds = ms / 1000
        return formatEta(seconds)
    }

    /**
     * 获取详细的状态字符串。
     */
    fun getStatusString(): String {
        val progress = getProgress()
        val eta = formatEta(progress.etaSeconds)
        val elapsed = formatElapsed(progress.elapsedMs)

        return when {
            isComplete() -> "备份完成！用时 $elapsed"
            completedApps == 0 -> "开始备份 ${totalApps} 个应用..."
            else -> "进度: ${"%.1f".format(progress.percent)}% ($completedApps/$totalApps) | ETA: $eta | 当前: $currentPackage"
        }
    }

    /**
     * 获取简短的状态字符串（用于 UI 显示）。
     */
    fun getShortStatusString(): String {
        val progress = getProgress()

        return when {
            isComplete() -> "备份完成！"
            completedApps == 0 -> "准备备份..."
            else -> "${"%.1f".format(progress.percent)}% - $currentMessage"
        }
    }
}
