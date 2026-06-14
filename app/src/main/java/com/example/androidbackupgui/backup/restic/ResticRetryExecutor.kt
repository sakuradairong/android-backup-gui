package com.example.androidbackupgui.backup.restic

import android.util.Log
import kotlinx.coroutines.delay

/**
 * Restic 命令重试执行器 - 为网络操作提供自动重试机制。
 *
 * 主要用于远程后端（SMB/WebDAV）的备份/恢复操作，
 * 处理网络抖动、连接超时等临时性错误。
 */
class ResticRetryExecutor(
    private val runner: ResticCommandRunner,
    private val maxRetries: Int = 3,
    private val initialDelayMs: Long = 1000,
    private val maxDelayMs: Long = 10000,
) {
    private val TAG = "ResticRetryExecutor"

    /**
     * 重试策略。
     */
    data class RetryPolicy(
        val maxRetries: Int,
        val initialDelayMs: Long,
        val maxDelayMs: Long,
        val backoffMultiplier: Double = 2.0,
    )

    /**
     * 重试结果。
     */
    data class RetryResult<T>(
        val result: T,
        val attempts: Int,
        val totalTimeMs: Long,
        val lastError: String? = null,
    )

    /**
     * 执行命令，失败时自动重试。
     *
     * @param env 环境变量
     * @param args 命令参数
     * @param onRetry 重试时的回调（可选）
     * @return RetryResult 包含结果和重试信息
     */
    suspend fun executeWithRetry(
        env: Map<String, String>,
        args: List<String>,
        onRetry: (suspend (attempt: Int, error: String) -> Unit)? = null,
    ): RetryResult<ResticCommandRunner.CommandResult> {
        val startTime = System.currentTimeMillis()
        var lastError: String? = null
        var attempts = 0

        repeat(maxRetries + 1) { attempt ->
            attempts = attempt + 1
            val result = runner.runRestic(env, args)

            if (result.exitCode == 0) {
                return RetryResult(
                    result = result,
                    attempts = attempts,
                    totalTimeMs = System.currentTimeMillis() - startTime,
                    lastError = null,
                )
            }

            lastError = result.stderr.ifEmpty { result.stdout }

            // 检查是否应该重试
            if (attempt < maxRetries && isRetryableError(result)) {
                val delayMs = calculateDelay(attempt)
                Log.w(TAG, "executeWithRetry: attempt ${attempt + 1} failed, retrying in ${delayMs}ms")
                Log.w(TAG, "executeWithRetry: error: ${lastError?.take(200)}")

                onRetry?.invoke(attempt + 1, lastError ?: "Unknown error")
                delay(delayMs)
            }
        }

        // 所有重试都失败了
        val finalResult = runner.runRestic(env, args)
        return RetryResult(
            result = finalResult,
            attempts = attempts,
            totalTimeMs = System.currentTimeMillis() - startTime,
            lastError = lastError,
        )
    }

    /**
     * 执行流式命令，失败时自动重试。
     *
     * @param env 环境变量
     * @param args 命令参数
     * @param onLine 输出行回调
     * @param onRetry 重试时的回调（可选）
     * @return RetryResult 包含结果和重试信息
     */
    suspend fun executeStreamingWithRetry(
        env: Map<String, String>,
        args: List<String>,
        onLine: suspend (String) -> Unit,
        onRetry: (suspend (attempt: Int, error: String) -> Unit)? = null,
    ): RetryResult<ResticCommandRunner.CommandResult> {
        val startTime = System.currentTimeMillis()
        var lastError: String? = null
        var attempts = 0

        repeat(maxRetries + 1) { attempt ->
            attempts = attempt + 1
            val result = runner.runResticStreaming(env, args, onLine)

            if (result.exitCode == 0) {
                return RetryResult(
                    result = result,
                    attempts = attempts,
                    totalTimeMs = System.currentTimeMillis() - startTime,
                    lastError = null,
                )
            }

            lastError = result.stderr.ifEmpty { result.stdout }

            // 检查是否应该重试
            if (attempt < maxRetries && isRetryableError(result)) {
                val delayMs = calculateDelay(attempt)
                Log.w(TAG, "executeStreamingWithRetry: attempt ${attempt + 1} failed, retrying in ${delayMs}ms")
                Log.w(TAG, "executeStreamingWithRetry: error: ${lastError?.take(200)}")

                onRetry?.invoke(attempt + 1, lastError ?: "Unknown error")
                delay(delayMs)
            }
        }

        // 所有重试都失败了
        val finalResult = runner.runResticStreaming(env, args, onLine)
        return RetryResult(
            result = finalResult,
            attempts = attempts,
            totalTimeMs = System.currentTimeMillis() - startTime,
            lastError = lastError,
        )
    }

    /**
     * 判断错误是否可重试。
     *
     * 可重试的错误：
     * - 网络超时
     * - 连接被拒绝
     * - 连接重置
     * - 临时性 DNS 错误
     * - 服务器 5xx 错误
     */
    private fun isRetryableError(result: ResticCommandRunner.CommandResult): Boolean {
        val error = result.stderr.lowercase()
        val stdout = result.stdout.lowercase()

        return when {
            // 网络超时
            error.contains("timeout") || error.contains("timed out") -> true
            // 连接被拒绝
            error.contains("connection refused") -> true
            // 连接重置
            error.contains("connection reset") -> true
            // DNS 错误
            error.contains("dns") || error.contains("name resolution") -> true
            // 服务器错误（5xx）
            error.contains("500") || error.contains("502") ||
                error.contains("503") || error.contains("504") -> true
            // 网络不可达
            error.contains("network unreachable") -> true
            // 连接超时
            error.contains("connection timed out") -> true
            // 临时性错误
            error.contains("temporary") || error.contains("transient") -> true
            // 进程被信号杀死（可能是 OOM）
            result.exitCode == 137 || result.exitCode == 143 -> true
            else -> false
        }
    }

    /**
     * 计算重试延迟（指数退避）。
     */
    private fun calculateDelay(attempt: Int): Long {
        val delay = initialDelayMs * Math.pow(2.0, attempt.toDouble())
        return delay.toLong().coerceAtMost(maxDelayMs)
    }

    /**
     * 创建默认的重试执行器。
     */
    companion object {
        fun createDefault(runner: ResticCommandRunner): ResticRetryExecutor {
            return ResticRetryExecutor(
                runner = runner,
                maxRetries = 3,
                initialDelayMs = 1000,
                maxDelayMs = 10000,
            )
        }
    }
}
