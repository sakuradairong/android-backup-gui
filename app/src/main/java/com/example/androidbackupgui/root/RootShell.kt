package com.example.androidbackupgui.root

import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
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
     * libsu shell initializer: enter global mount namespace via nsenter.
     * Preserves the original PATH so that tar/zstd (from Termux etc.) remain accessible.
     * Ref: DataBackup (XayahSuSuSu) uses the same nsenter pattern.
     */
    private class GlobalNamespaceInitializer : Shell.Initializer() {
        override fun onInit(context: android.content.Context, shell: Shell): Boolean {
            shell.newJob()
                .add("nsenter --mount=/proc/1/ns/mnt sh")
                .add("set -o pipefail")
                .exec()
            return true
        }
    }

    /** Call once at app startup to configure libsu. */
    fun configure() {
        Shell.enableVerboseLogging = true
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setInitializers(GlobalNamespaceInitializer::class.java)
                .setTimeout(30)
        )
    }

    suspend fun ensureSession(): Boolean = withContext(Dispatchers.IO) {
        try {
            Shell.getShell().isRoot
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) { false }
    }

    suspend fun exec(command: String, timeoutMs: Long = COMMAND_TIMEOUT_MS): ShellResult =
        withContext(Dispatchers.IO) {
            ensureActive()
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "exec failed: $command", e)
                ShellResult("", e.message ?: "Unknown error", -1)
            }
        }

    /**
     * 安全执行 root shell 命令，自动 shellEscape 每个参数。
     * @param parts 命令和参数列表，第一个元素是命令本身
     * @param timeoutMs 超时毫秒
     */
    suspend fun execSafe(
        parts: List<String>,
        timeoutMs: Long = COMMAND_TIMEOUT_MS
    ): ShellResult = exec(
        command = parts.joinToString(" ") { "'${it.shellEscape()}'" },
        timeoutMs = timeoutMs
    )
}
