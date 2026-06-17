package com.example.androidbackupgui.root

import android.util.Log
import com.example.androidbackupgui.backup.core.LogSanitizer
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

fun String.shellEscape(): String = this.replace("'", "'\\''")

object RootShell {

    private const val TAG = "RootShell"
    private const val COMMAND_TIMEOUT_MS = 120_000L
    private const val PID_DIR = "/data/local/tmp"

    private val activePids = ConcurrentHashMap<String, String>()

    data class ShellResult(
        val output: String,
        val error: String,
        val exitCode: Int
    ) {
        val isSuccess get() = exitCode == 0
    }

    private class GlobalNamespaceInitializer : Shell.Initializer() {
        override fun onInit(context: android.content.Context, shell: Shell): Boolean {
            shell.newJob()
                .add("nsenter --mount=/proc/1/ns/mnt sh")
                .add("set -o pipefail")
                .exec()
            return true
        }
    }

    fun configure() {
        Shell.enableVerboseLogging = false
        try {
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_MOUNT_MASTER)
                    .setInitializers(GlobalNamespaceInitializer::class.java)
                    .setTimeout(30)
            )
        } catch (_: IllegalStateException) {
        } catch (e: Exception) {
            Log.w(TAG, "configure: failed to set default builder")
        }
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
                Log.w(TAG, "exec timeout (${timeoutMs}ms)")
                ShellResult("", "Command timed out after ${timeoutMs}ms", -1)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "exec failed")
                ShellResult("", e.message ?: "Unknown error", -1)
            }
        }

    suspend fun execCancellable(
        command: String,
        taskId: String,
        timeoutMs: Long = COMMAND_TIMEOUT_MS
    ): ShellResult =
        withContext(Dispatchers.IO) {
            ensureActive()
            val token = "${taskId}_${UUID.randomUUID().toString().take(8)}"
            val pidFile = "$PID_DIR/abg_${token}.pid"
            val wrapped = "( $command ) & pid=\$!; echo \$pid > '$pidFile'; wait \$pid; code=\$?; rm -f '$pidFile'; exit \$code"

            try {
                val result = withTimeout(timeoutMs) {
                    Shell.cmd(wrapped).exec()
                }
                ShellResult(
                    output = result.out.joinToString("\n"),
                    error = result.err.joinToString("\n"),
                    exitCode = result.code,
                )
            } catch (e: TimeoutCancellationException) {
                killByPidFile(pidFile)
                Log.w(TAG, "execCancellable timeout (${timeoutMs}ms)")
                ShellResult("", "Command timed out after ${timeoutMs}ms", -1)
            } catch (e: CancellationException) {
                killByPidFile(pidFile)
                throw e
            } catch (e: Exception) {
                killByPidFile(pidFile)
                Log.e(TAG, "execCancellable failed")
                ShellResult("", e.message ?: "Unknown error", -1)
            }
        }

    private fun killByPidFile(pidFile: String) {
        try {
            Shell.cmd("cat '$pidFile' 2>/dev/null").exec().out.firstOrNull()?.trim()?.toIntOrNull()?.let { pid ->
                Shell.cmd("kill -TERM $pid 2>/dev/null").exec()
                Thread.sleep(500)
                Shell.cmd("kill -KILL $pid 2>/dev/null").exec()
                Shell.cmd("pkill -KILL -P $pid 2>/dev/null").exec()
            }
            Shell.cmd("rm -f '$pidFile'").exec()
        } catch (_: Exception) {
        }
    }

    suspend fun execSafe(
        parts: List<String>,
        timeoutMs: Long = COMMAND_TIMEOUT_MS
    ): ShellResult = exec(
        command = parts.joinToString(" ") { "'${it.shellEscape()}'" },
        timeoutMs = timeoutMs
    )
}
