package com.example.androidbackupgui.backup.restic

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * REST 桥健康检查器 - 检查 ResticRestBridge 的可用性。
 *
 * 在启动远程备份/恢复操作前检查桥接器是否正常工作，
 * 避免在操作过程中才发现连接问题。
 */
class RestBridgeHealthChecker {
    private val TAG = "RestBridgeHealthChecker"

    /**
     * 健康检查结果。
     */
    data class HealthCheckResult(
        val isHealthy: Boolean,
        val latencyMs: Long,
        val error: String? = null,
    )

    /**
     * 检查 REST 桥是否健康。
     *
     * @param port 桥接器监听端口
     * @param timeoutMs 超时时间（毫秒）
     * @return HealthCheckResult 包含健康状态和延迟
     */
    suspend fun checkHealth(
        port: Int,
        timeoutMs: Long = 5000,
    ): HealthCheckResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        try {
            val url = URL("http://127.0.0.1:$port/")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = timeoutMs.toInt()
            connection.readTimeout = timeoutMs.toInt()
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "AndroidBackupGUI/1.0")

            val responseCode = connection.responseCode
            val latency = System.currentTimeMillis() - startTime

            connection.disconnect()

            if (responseCode in 200..299) {
                Log.d(TAG, "checkHealth: healthy, latency=${latency}ms")
                HealthCheckResult(
                    isHealthy = true,
                    latencyMs = latency,
                )
            } else {
                Log.w(TAG, "checkHealth: unhealthy, responseCode=$responseCode")
                HealthCheckResult(
                    isHealthy = false,
                    latencyMs = latency,
                    error = "HTTP $responseCode",
                )
            }
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime
            Log.e(TAG, "checkHealth: failed", e)
            HealthCheckResult(
                isHealthy = false,
                latencyMs = latency,
                error = e.message ?: "Unknown error",
            )
        }
    }

    /**
     * 等待桥接器就绪。
     *
     * @param port 桥接器监听端口
     * @param maxWaitMs 最大等待时间（毫秒）
     * @param checkIntervalMs 检查间隔（毫秒）
     * @return 是否就绪
     */
    suspend fun waitForReady(
        port: Int,
        maxWaitMs: Long = 30000,
        checkIntervalMs: Long = 1000,
    ): Boolean {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            val result = checkHealth(port)
            if (result.isHealthy) {
                Log.i(TAG, "waitForReady: bridge ready after ${System.currentTimeMillis() - startTime}ms")
                return true
            }
            Log.d(TAG, "waitForReady: waiting...")
            kotlinx.coroutines.delay(checkIntervalMs)
        }

        Log.w(TAG, "waitForReady: bridge not ready after ${maxWaitMs}ms")
        return false
    }

    /**
     * 检查桥接器是否可用（快速检查）。
     *
     * @param port 桥接器监听端口
     * @return 是否可用
     */
    suspend fun isAvailable(port: Int): Boolean {
        return checkHealth(port, 2000).isHealthy
    }

    /**
     * 获取桥接器延迟。
     *
     * @param port 桥接器监听端口
     * @return 延迟（毫秒），如果不可用则返回 -1
     */
    suspend fun getLatency(port: Int): Long {
        val result = checkHealth(port, 3000)
        return if (result.isHealthy) result.latencyMs else -1
    }
}
