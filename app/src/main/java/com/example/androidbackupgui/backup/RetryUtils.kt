package com.example.androidbackupgui.backup

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Retry [block] up to [maxRetries] times with exponential backoff.
 * Propagates [CancellationException] immediately.
 * Returns the first [AppResult.Success], or the last [AppResult.Failure] after all retries.
 */
suspend fun <T> retryWithBackoff(
    tag: String,
    operation: String,
    maxRetries: Int = 3,
    initialDelayMs: Long = 1000,
    block: suspend () -> AppResult<T>
): AppResult<T> {
    var lastError: AppResult.Failure? = null
    repeat(maxRetries) { attempt ->
        try {
            val result = block()
            if (result is AppResult.Success) return result
            lastError = result as AppResult.Failure
            if (attempt < maxRetries - 1) {
                val delayMs = initialDelayMs * (1L shl attempt)
                Log.w(tag, "$operation 失败 (第${attempt+1}次), ${maxRetries-attempt-1}次重试剩余, 等待${delayMs}ms: ${result.error.message}")
                delay(delayMs)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (attempt < maxRetries - 1) {
                val delayMs = initialDelayMs * (1L shl attempt)
                Log.e(tag, "$operation 异常 (第${attempt+1}次), ${maxRetries-attempt-1}次重试剩余", e)
                delay(delayMs)
            } else {
                Log.e(tag, "$operation 最终失败", e)
                return err(AppError.Remote("$operation 失败 (重试${maxRetries}次后)", operation, cause = e))
            }
        }
    }
    return lastError ?: err(AppError.Remote("$operation 失败", operation))
}
