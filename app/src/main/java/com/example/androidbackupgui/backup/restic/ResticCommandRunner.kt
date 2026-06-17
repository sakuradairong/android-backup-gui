package com.example.androidbackupgui.backup.restic

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import com.example.androidbackupgui.backup.core.AppError
import com.example.androidbackupgui.backup.core.LogSanitizer
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import kotlin.coroutines.coroutineContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.serialization.Serializable

class ResticCommandRunner {

    private val TAG = "ResticWrapper"

    var binaryPath: String = "restic"

    @Serializable
    data class CommandResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int
    )

    fun buildCommandArgs(args: List<String>): List<String> =
        (listOf(binaryPath) + args)

    private fun Process.waitForCompat(deadlineMs: Long = 60_000): Int {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline) {
            try {
                return exitValue()
            } catch (_: IllegalThreadStateException) {
                Thread.sleep(100)
            }
        }
        Log.w(TAG, "process did not exit within ${deadlineMs}ms, destroying")
        destroy()
        waitFor()
        return exitValue()
    }

    fun runRestic(env: Map<String, String>, args: List<String>): CommandResult {
        val cmdArgs = buildCommandArgs(args)
        Log.i(TAG, "runRestic cmd=${LogSanitizer.redact(cmdArgs.joinToString(" "))}")
        env["TMPDIR"]?.let { File(it).mkdirs() }
        return try {
            val pb = ProcessBuilder(cmdArgs)
            pb.environment().putAll(env)
            pb.redirectErrorStream(false)
            val process = pb.start()

            var stderrBytes = byteArrayOf()
            val stderrThread = Thread {
                try {
                    stderrBytes = process.errorStream.use { it.readAllBytesCompat() }
                } catch (_: Exception) {
                }
            }.apply { isDaemon = true; start() }

            val stdout = process.inputStream.bufferedReader().use(BufferedReader::readText)
            val exitCode = try {
                process.waitForCompat()
            } catch (_: Exception) { -1 }
            try { stderrThread.join(1_000) } catch (_: InterruptedException) {}
            val stderrText = stderrBytes.decodeToString()
            Log.i(TAG, "runRestic exitCode=$exitCode stdout_len=${stdout.length}")
            if (stderrText.isNotEmpty()) Log.w(TAG, "runRestic stderr: ${stderrText.trim().take(500)}")
            CommandResult(stdout.trim(), stderrText.trim(), exitCode)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "runRestic exception", e)
            CommandResult("", e.message ?: "Unknown error", -1)
        }
    }

    fun runRestic(env: Map<String, String>, vararg args: String): CommandResult {
        return runRestic(env, args.toList())
    }

    suspend fun runResticCancellable(
        env: Map<String, String>,
        args: List<String>,
        onBeforeStart: ((Process) -> Unit)? = null,
    ): CommandResult = withContext(Dispatchers.IO) {
        val cmdArgs = buildCommandArgs(args)
        Log.i(TAG, "runResticCancellable cmd=${LogSanitizer.redact(cmdArgs.joinToString(" "))}")
        env["TMPDIR"]?.let { File(it).mkdirs() }

        var process: Process? = null
        try {
            val pb = ProcessBuilder(cmdArgs)
            pb.environment().putAll(env)
            pb.redirectErrorStream(false)
            process = pb.start()
            onBeforeStart?.invoke(process)

            var stderrBytes = byteArrayOf()
            val stderrThread = Thread {
                try {
                    stderrBytes = process.errorStream.use { it.readAllBytesCompat() }
                } catch (_: Exception) {
                }
            }.apply { isDaemon = true; start() }

            val stdout = process.inputStream.bufferedReader().use(BufferedReader::readText)
            val exitCode = try {
                process.waitForCompat()
            } catch (_: Exception) { -1 }
            try { stderrThread.join(1_000) } catch (_: InterruptedException) {}
            val stderrText = stderrBytes.decodeToString().trim()
            Log.i(TAG, "runResticCancellable exitCode=$exitCode stdout_len=${stdout.length}")
            if (stderrText.isNotEmpty()) Log.w(TAG, "runResticCancellable stderr: ${stderrText.take(500)}")
            CommandResult(stdout.trim(), stderrText, exitCode)
        } catch (e: kotlinx.coroutines.CancellationException) {
            try { process?.destroy() } catch (_: Exception) {}
            try {
                Thread.sleep(500)
                if (android.os.Build.VERSION.SDK_INT >= 26 && process?.isAlive == true) process?.destroyForcibly()
            } catch (_: Exception) {}
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "runResticCancellable exception", e)
            try { process?.destroy() } catch (_: Exception) {}
            CommandResult("", e.message ?: "Unknown error", -1)
        }
    }

    suspend fun runResticStreaming(
        env: Map<String, String>,
        args: List<String>,
        onLine: suspend (String) -> Unit
    ): CommandResult = withContext(Dispatchers.IO) {
        val cmdArgs = buildCommandArgs(args)
        Log.i(TAG, "runResticStreaming cmd=${LogSanitizer.redact(cmdArgs.joinToString(" "))}")
        env["TMPDIR"]?.let { File(it).mkdirs() }

        var process: Process? = null
        try {
            val pb = ProcessBuilder(cmdArgs)
            pb.environment().putAll(env)
            pb.redirectErrorStream(false)
            process = pb.start()

            var stderrBytes = byteArrayOf()
            val stderrThread = Thread {
                try {
                    stderrBytes = process.errorStream.use { it.readAllBytesCompat() }
                } catch (_: Exception) {
                }
            }.apply { isDaemon = true; start() }

            val stdoutText = StringBuilder()
            val reader = process.inputStream.bufferedReader()

            try {
                var line = reader.readLine()
                while (line != null) {
                    if (!coroutineContext.isActive) {
                        process.destroy()
                        break
                    }
                    stdoutText.appendLine(line)
                    onLine(line)
                    line = reader.readLine()
                }
            } finally {
                try { reader.close() } catch (_: Exception) {}
            }
            try { stderrThread.join(1_000) } catch (_: InterruptedException) {}
            val stderrText = stderrBytes.decodeToString().trim()
            val exitCode = try {
                process.waitForCompat()
            } catch (_: Exception) { -1 }

            Log.i(TAG, "runResticStreaming exitCode=$exitCode stdout_len=${stdoutText.length}")
            if (stderrText.isNotEmpty()) Log.w(TAG, "runResticStreaming stderr: ${stderrText.take(500)}")
            CommandResult(stdoutText.toString().trim(), stderrText, exitCode)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "runResticStreaming exception", e)
            try { process?.destroy() } catch (_: Exception) {}
            CommandResult("", e.message ?: "Unknown error", -1)
        }
    }

}

internal fun InputStream.readAllBytesCompat(): ByteArray {
    val buffer = ByteArrayOutputStream()
    val data = ByteArray(4096)
    while (true) {
        val n = read(data)
        if (n == -1) break
        buffer.write(data, 0, n)
    }
    return buffer.toByteArray()
}
