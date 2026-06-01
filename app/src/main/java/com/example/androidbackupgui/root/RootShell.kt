package com.example.androidbackupgui.root

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.InputStream
import android.util.Log

/**
 * Escape a string for safe use inside single-quoted shell strings.
 * Replaces each ' with '\'' (end quote, escaped quote, restart quote).
 */
fun String.shellEscape(): String = this.replace("'", "'\\''")

/**
 * Persistent root shell session via `su`.
 * Manages a single su process and executes commands sequentially.
 * Thread-safe via Mutex — all session state is guarded by the mutex.
 */
object RootShell {

    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private var reader: BufferedReader? = null
    private var errReader: BufferedReader? = null

    private const val TAG = "RootShell"
    /** Default command timeout in milliseconds. */
    private const val COMMAND_TIMEOUT_MS = 120_000L

    private val mutex = Mutex()

    /** Result of a shell command execution. */
    data class ShellResult(
        val output: String,
        val error: String,
        val exitCode: Int
    ) {
        val isSuccess get() = exitCode == 0
    }

    /** Quick process-alive check. Caller MUST hold the mutex. */
    private fun isAliveUnsafe(): Boolean {
        val p = process ?: return false
        return try { p.exitValue(); false } catch (_: IllegalThreadStateException) { true }
    }

    /**
     * Open (or re-open) the su session and verify root access.
     * Caller MUST hold the mutex.
     */
    private fun ensureSessionUnsafe(): Boolean {
        if (isAliveUnsafe()) return true
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su"))
            writer = OutputStreamWriter(p.outputStream)
            reader = BufferedReader(InputStreamReader(p.inputStream))
            errReader = BufferedReader(InputStreamReader(p.errorStream))
            process = p
            // Drain stderr in background to prevent pipe-buffer deadlock
            Thread({
                try { while (errReader?.readLine() != null) {} } catch (_: Exception) {}
            }, "su-stderr-drain").apply { isDaemon = true; start() }
            // Inline verification — cannot call exec() which would deadlock on mutex
            val sentinel = "ROOT_OK_${System.nanoTime()}"
            writer?.write("echo $sentinel\n"); writer?.flush()
            var line: String?
            while (reader?.readLine().also { line = it } != null) {
                if (line!!.contains(sentinel)) return true
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    /** Ensure a root shell is open. Returns true if root is available. */
    suspend fun ensureSession(): Boolean = mutex.withLock {
        ensureSessionUnsafe()
    }

    /** Cleanup all session state. Caller MUST hold the mutex. */
    private fun closeUnsafe() {
        try { writer?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { errReader?.close() } catch (_: Exception) {}
        try { process?.destroy() } catch (_: Exception) {}
        process = null
        writer = null
        reader = null
        errReader = null
    }

    /** Close the root shell session. */
    suspend fun close() = mutex.withLock {
        closeUnsafe()
    }

    /**
     * Execute a command and return the output.
     * Uses a sentinel delimiter to identify end of output.
     * Timeout is enforced via structured coroutine cancellation:
     * `withTimeout(timeoutMs)` cancels the coroutine, interrupting the
     * blocking readLine() on Dispatchers.IO. If the process cannot be
     * interrupted, closeUnsafe() destroys it in the catch handler.
     */
suspend fun exec(command: String, timeoutMs: Long = COMMAND_TIMEOUT_MS): ShellResult = mutex.withLock {
        if (!isAliveUnsafe() && !ensureSessionUnsafe()) {
            return@exec ShellResult("", "No root access", -1)
        }

        val sentinel = "EXIT_${System.nanoTime()}"
        writer?.write("$command; echo $sentinel \$?\n")
        writer?.flush()

        try {
            withTimeout(timeoutMs) {
                withContext(Dispatchers.IO) {
                    val output = StringBuilder()
                    var line: String?
                    while (reader?.readLine().also { line = it } != null) {
                        val l = line!!
                        if (l.startsWith(sentinel)) {
                            val code = l.removePrefix("$sentinel ").trim().toIntOrNull() ?: -1
                            return@withContext ShellResult(output.toString().trimEnd(), "", code)
                        }
                        output.appendLine(l)
                    }
                    // Process destroyed or readLine returned null naturally
                    ShellResult(output.toString().trimEnd(), "", -1)
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "exec timeout (${timeoutMs}ms) destroying process: $command")
            closeUnsafe()
            ShellResult("", "Command timed out after ${timeoutMs}ms", -1)
        }
    }

    /**
     * Execute a command via `su` and return the stdout as an InputStream
     * for binary-safe streaming. Caller MUST close the stream and call
     * waitForStreamResult() or destroy the returned process.
     */
    class StreamProcess(
        val process: Process,
        val inputStream: InputStream,
        private val command: String
    ) {
        fun waitFor(): Int {
            try { process.waitFor() } catch (_: Exception) {}
            return process.exitValue()
        }
        fun destroy() {
            try { process.destroy() } catch (_: Exception) {}
            try { inputStream.close() } catch (_: Exception) {}
        }
    }

    fun execBinary(command: String): StreamProcess? {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            // Drain stderr to prevent pipe deadlock
            Thread({
                try { p.errorStream.use { it.readBytes() } } catch (_: Exception) {}
            }, "su-binary-stderr").apply { isDaemon = true }.start()
            StreamProcess(p, p.inputStream, command)
        } catch (_: Exception) {
            null
        }
    }
}
