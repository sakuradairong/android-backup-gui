package com.example.androidbackupgui.root

import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.InputStream

/**
 * Escape a string for safe use inside single-quoted shell strings.
 * Replaces each ' with '\'' (end quote, escaped quote, restart quote).
 */
fun String.shellEscape(): String = this.replace("'", "'\\''")

/**
 * Persistent root shell session via `su`.
 * Manages a single su process and executes commands sequentially.
 */
object RootShell {

    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private var reader: BufferedReader? = null
    private var errReader: BufferedReader? = null
    private val lock = Any()

    /** Result of a shell command execution. */
    data class ShellResult(
        val output: String,
        val error: String,
        val exitCode: Int
    ) {
        val isSuccess get() = exitCode == 0
    }

    /** Ensure a root shell is open. Returns true if root is available. */
    fun ensureSession(): Boolean = synchronized(lock) {
        if (isAlive()) return true
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su"))
            writer = OutputStreamWriter(p.outputStream)
            reader = BufferedReader(InputStreamReader(p.inputStream))
            errReader = BufferedReader(InputStreamReader(p.errorStream))
            process = p
            // Drain stderr in background to prevent pipe-buffer deadlock
            Thread({
                try { while (errReader?.readLine() != null) {} } catch (_: Exception) {}
            }, "su-stderr-drain").apply { isDaemon = true }.start()
            // Consume the initial (possibly empty) output
            exec("echo ROOT_OK").output.contains("ROOT_OK")
        } catch (_: Exception) {
            false
        }
    }

    fun isAlive(): Boolean = synchronized(lock) {
        process?.isAlive == true
    }

    fun close() = synchronized(lock) {
        try { writer?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { errReader?.close() } catch (_: Exception) {}
        try { process?.destroy() } catch (_: Exception) {}
        process = null
        writer = null
        reader = null
        errReader = null
    }

    /**
     * Execute a command and return the output.
     * Uses a sentinel delimiter to identify end of output.
     */
    fun exec(command: String): ShellResult = synchronized(lock) {
        if (!isAlive() && !ensureSession()) {
            return ShellResult("", "No root access", -1)
        }

        val sentinel = "EXIT_${System.nanoTime()}"
        val fullCmd = "$command; echo $sentinel \$?"

        writer?.write("$fullCmd\n")
        writer?.flush()

        val output = StringBuilder()
        val error = StringBuilder()
        var line: String?

        while (reader?.readLine().also { line = it } != null) {
            val l = line!!
            if (l.startsWith(sentinel)) {
                val code = l.removePrefix("$sentinel ").trim().toIntOrNull() ?: -1
                return@exec ShellResult(
                    output = output.toString().trimEnd(),
                    error = error.toString().trimEnd(),
                    exitCode = code
                )
            }
            output.appendLine(l)
        }

        ShellResult(output.toString().trimEnd(), error.toString().trimEnd(), -1)
    }

    /**
     * Execute command in coroutine context on Dispatchers.IO.
     */
    suspend fun execAsync(command: String): ShellResult =
        withContext(Dispatchers.IO) { exec(command) }

    /**
     * Stream output line by line. Returns exit code.
     */
    suspend fun execStreaming(
        command: String,
        onLine: suspend (String) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val lines = mutableListOf<String>()
        var exitCode = -1
        synchronized(lock) {
            if (!isAlive() && !ensureSession()) return@withContext -1

            val sentinel = "EXIT_${System.nanoTime()}"
            val fullCmd = "$command; echo $sentinel \$?"

            writer?.write("$fullCmd\n")
            writer?.flush()

            var line: String?
            while (reader?.readLine().also { line = it } != null) {
                val l = line!!
                if (l.startsWith(sentinel)) {
                    exitCode = l.removePrefix("$sentinel ").trim().toIntOrNull() ?: -1
                    break
                }
                lines.add(l)
            }
        }
        for (l in lines) {
            onLine(l)
        }
        exitCode
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
