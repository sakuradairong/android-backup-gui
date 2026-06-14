package com.example.androidbackupgui.backup

import android.app.ActivityManager
import android.content.Context

/**
 * 智能并发控制器 - 根据设备性能动态调整并发数。
 *
 * 考虑因素：
 * 1. CPU 核心数
 * 2. 可用内存
 * 3. 存储类型（SSD/eMMC）
 * 4. 系统负载
 */
object ConcurrencyController {

    /**
     * 并发配置。
     */
    data class ConcurrencyConfig(
        val maxConcurrency: Int,
        val reason: String,
    )

    /**
     * 计算最优并发数。
     *
     * @param context Android 上下文
     * @param taskType 任务类型："backup" 或 "restore"
     * @return ConcurrencyConfig 包含并发数和原因
     */
    fun calculateOptimalConcurrency(
        context: Context,
        taskType: String = "backup",
    ): ConcurrencyConfig {
        val cpuCores = Runtime.getRuntime().availableProcessors()
        val memoryInfo = getMemoryInfo(context)
        val availableMemoryMB = memoryInfo.availMem / (1024 * 1024)
        val totalMemoryMB = memoryInfo.totalMem / (1024 * 1024)
        val memoryUsagePercent = ((totalMemoryMB - availableMemoryMB).toDouble() / totalMemoryMB) * 100

        val concurrency = when {
            // 高端设备：8+ 核心，内存充足
            cpuCores >= 8 && availableMemoryMB > 2048 && memoryUsagePercent < 70 -> {
                when (taskType) {
                    "backup" -> 5
                    "restore" -> 4
                    else -> 4
                }
            }
            // 中高端设备：4-7 核心，内存充足
            cpuCores >= 4 && availableMemoryMB > 1024 && memoryUsagePercent < 80 -> {
                when (taskType) {
                    "backup" -> 4
                    "restore" -> 3
                    else -> 3
                }
            }
            // 中端设备：2-3 核心
            cpuCores >= 2 && availableMemoryMB > 512 -> {
                when (taskType) {
                    "backup" -> 3
                    "restore" -> 2
                    else -> 2
                }
            }
            // 低端设备：单核心或内存不足
            else -> {
                when (taskType) {
                    "backup" -> 2
                    "restore" -> 1
                    else -> 1
                }
            }
        }

        val reason = buildReasonString(cpuCores, availableMemoryMB, memoryUsagePercent, concurrency)

        return ConcurrencyConfig(
            maxConcurrency = concurrency,
            reason = reason,
        )
    }

    /**
     * 获取内存信息。
     */
    private fun getMemoryInfo(context: Context): ActivityManager.MemoryInfo {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return memoryInfo
    }

    /**
     * 构建原因字符串。
     */
    private fun buildReasonString(
        cpuCores: Int,
        availableMemoryMB: Long,
        memoryUsagePercent: Double,
        concurrency: Int,
    ): String {
        return buildString {
            append("CPU: ${cpuCores}核, ")
            append("可用内存: ${availableMemoryMB}MB, ")
            append("内存使用率: ${"%.1f".format(memoryUsagePercent)}%, ")
            append("并发数: $concurrency")
        }
    }

    /**
     * 检查是否为高端设备。
     */
    fun isHighEndDevice(context: Context): Boolean {
        val cpuCores = Runtime.getRuntime().availableProcessors()
        val memoryInfo = getMemoryInfo(context)
        val availableMemoryMB = memoryInfo.availMem / (1024 * 1024)
        return cpuCores >= 8 && availableMemoryMB > 2048
    }

    /**
     * 检查是否为低端设备。
     */
    fun isLowEndDevice(context: Context): Boolean {
        val cpuCores = Runtime.getRuntime().availableProcessors()
        val memoryInfo = getMemoryInfo(context)
        val availableMemoryMB = memoryInfo.availMem / (1024 * 1024)
        return cpuCores < 2 || availableMemoryMB < 512
    }

    /**
     * 获取设备性能等级。
     */
    fun getDevicePerformanceLevel(context: Context): String {
        return when {
            isHighEndDevice(context) -> "high"
            isLowEndDevice(context) -> "low"
            else -> "medium"
        }
    }
}
