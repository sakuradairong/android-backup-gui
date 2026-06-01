package com.example.androidbackupgui.root

import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Escape a string for safe use inside single-quoted shell strings.
 * Replaces each ' with '\'' (end quote, escaped quote, restart quote).
 */
fun String.shellEscape(): String = this.replace("'", "'\\''")

/**
 * Root shell access via libsu.
 * Shell.cmd internally manages su sessions, compatible with Magisk/KernelSU/APatch.
 * All shell operations are thread-safe through coroutine dispatchers.
 */
object RootShell {

    private const val TAG = "RootShell"
    /** Default command timeout in milliseconds. */
    private const val COMMAND_TIMEOUT_MS = 120_000L

    /** Result of a shell command execution. */
    data class ShellResult(
        val output: String,
        val error: String,
        val exitCode: Int
    ) {
        val isSuccess get() = exitCode == 0
    }

    /**
     * Trigger root shell pre-initialization.
     * Returns true if root is available.
     * Note: Shell.cmd() also auto-initializes on first use, so this is optional.
     */
    suspend fun ensureSession(): Boolean = withContext(Dispatchers.IO) {
        try {
            Shell.getShell().isRoot
        } catch (_: Exception) { false }
    }

    /**
     * Execute a shell command and return the result.
     * libsu internally runs via `su`, compatible with Magisk/KernelSU/APatch.
     * Commands are passed verbatim to `su -c`, so pipes and redirects work normally.
     * Timeout is enforced via structured coroutine cancellation.
     */
    suspend fun exec(command: String, timeoutMs: Long = COMMAND_TIMEOUT_MS): ShellResult =
        withContext(Dispatchers.IO) {
            try {
                val result = withTimeout(timeoutMs) {
                    Shell.cmd(command).exec()
                }
                ShellResult(
                    output = result.out.joinToString("\n"),
                    error = result.err.joinToString("\n"),
                    exitCode = result.code,
                )
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "exec timeout (${timeoutMs}ms): $command")
                ShellResult("", "Command timed out after ${timeoutMs}ms", -1)
            } catch (e: Exception) {
                Log.e(TAG, "exec failed: $command", e)
                ShellResult("", e.message ?: "Unknown error", -1)
            }
        }
}
