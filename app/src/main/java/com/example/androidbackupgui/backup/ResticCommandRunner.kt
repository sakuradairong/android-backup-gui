package com.example.androidbackupgui.backup

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import com.example.androidbackupgui.backup.AppError
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import kotlin.coroutines.coroutineContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.serialization.Serializable

/**
 * Manages restic binary process execution.
 * Holds the binary path and provides blocking and streaming execution.
 */
class ResticCommandRunner {

    private val TAG = "ResticWrapper"

    /** Path to the restic binary. Default assumes it's on PATH (e.g. Termux). */
    var binaryPath: String = "restic"

    @Serializable
    data class CommandResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int
    )

    /** Build the full command list to run restic. */
    fun buildCommandArgs(args: List<String>): List<String> =
        (listOf(binaryPath) + args).also { cmd ->
            Log.d(TAG, "buildCommandArgs: binaryPath=$binaryPath args=$args -> cmd=$cmd")
        }

    /** Run restic (non-streaming). */
    fun runRestic(env: Map<String, String>, args: List<String>): CommandResult {
        val cmdArgs = buildCommandArgs(args)
        Log.i(TAG, "runRestic cmd=${cmdArgs.joinToString(" ")}")
        Log.d(TAG, "runRestic REPOSITORY=${env["RESTIC_REPOSITORY"]}")
        // NOTE: Do NOT log RESTIC_PASSWORD or any value derived from it.
        // RESTIC_REPOSITORY is safe to log (does not contain secrets).
        env["TMPDIR"]?.let { File(it).mkdirs() }
        return try {
            val pb = ProcessBuilder(cmdArgs)
            pb.environment().putAll(env)
            pb.redirectErrorStream(false)
            val process = pb.start()

            val stdout = process.inputStream.bufferedReader().use(BufferedReader::readText)
            val stderrBytes = process.errorStream.use { it.readAllBytesCompat() }
            val exitCode = try {
                val deadline = System.currentTimeMillis() + 60_000
                var exited = false
                while (System.currentTimeMillis() < deadline && !exited) {
                    try {
                        process.exitValue()
                        exited = true
                    } catch (_: IllegalThreadStateException) {
                        Thread.sleep(100)
                    }
                }
                if (exited) {
                    process.exitValue()
                } else {
                    Log.w(TAG, "runRestic: process did not exit within 60s, destroying")
                    process.destroy()
                    process.waitFor()
                    process.exitValue()
                }
            } catch (_: Exception) { -1 }
            val stderrText = stderrBytes.decodeToString()
            Log.i(TAG, "runRestic exitCode=$exitCode stdout_len=${stdout.length}")
            if (stderrText.isNotEmpty()) Log.w(TAG, "runRestic stderr: ${stderrText.trim()}")
            CommandResult(stdout.trim(), stderrText.trim(), exitCode)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "runRestic exception", e)
            CommandResult("", e.message ?: "Unknown error", -1)
        }
    }

    /** Run restic with single-string args. */
    fun runRestic(env: Map<String, String>, vararg args: String): CommandResult {
        return runRestic(env, args.toList())
    }

    /** Run restic, calling onLine for each stdout line (for streaming progress). */
    suspend fun runResticStreaming(
        env: Map<String, String>,
        args: List<String>,
        onLine: suspend (String) -> Unit
    ): CommandResult = withContext(Dispatchers.IO) {
        val cmdArgs = buildCommandArgs(args)
        Log.i(TAG, "runResticStreaming cmd=${cmdArgs.joinToString(" ")}")
        Log.d(TAG, "runResticStreaming REPOSITORY=${env["RESTIC_REPOSITORY"]}")
        env["TMPDIR"]?.let { File(it).mkdirs() }

        var process: Process? = null
        try {
            val pb = ProcessBuilder(cmdArgs)
            pb.environment().putAll(env)
            pb.redirectErrorStream(false)
            process = pb.start()

            val stdoutText = StringBuilder()
            val reader = process.inputStream.bufferedReader()

            try {
                var line: String
                while (reader.readLine().also { line = it } != null) {
                    if (!coroutineContext.isActive) {
                        process.destroy()
                        break
                    }
                    stdoutText.appendLine(line)
                    onLine(line)
                }
            } finally {
                try { reader.close() } catch (_: Exception) {}
            }
            val stderrBytes = try { process.errorStream.use { it.readAllBytesCompat() } } catch (_: Exception) { byteArrayOf() }
            val stderrText = stderrBytes.decodeToString().trim()
            val exitCode = try {
                // Manual timeout loop (Process.waitFor(timeout,unit) requires API 26+)
                val deadline = System.currentTimeMillis() + 60_000
                var exited = false
                while (System.currentTimeMillis() < deadline && !exited) {
                    try {
                        process.exitValue()
                        exited = true
                    } catch (_: IllegalThreadStateException) {
                        Thread.sleep(100)
                    }
                }
                if (exited) {
                    process.exitValue()
                } else {
                    Log.w(TAG, "runResticStreaming: process did not exit within 60s after stdout EOF, destroying")
                    process.destroy()
                    process.waitFor()
                    process.exitValue()
                }
            } catch (_: Exception) { -1 }

            Log.i(TAG, "runResticStreaming exitCode=$exitCode stdout_len=${stdoutText.length}")
            if (stderrText.isNotEmpty()) Log.w(TAG, "runResticStreaming stderr: ${stderrText}")
            CommandResult(stdoutText.toString().trim(), stderrText.trim(), exitCode)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "runResticStreaming exception", e)
            try { process?.destroy() } catch (_: Exception) {}
            CommandResult("", e.message ?: "Unknown error", -1)
        }
    }
}

/**
 * Compat implementation of InputStream.readAllBytes() for API < 33.
 * Reads the entire stream into a byte array.
 */
private fun InputStream.readAllBytesCompat(): ByteArray {
    val buffer = ByteArrayOutputStream()
    val data = ByteArray(4096)
    while (true) {
        val n = read(data)
        if (n == -1) break
        buffer.write(data, 0, n)
    }
    return buffer.toByteArray()
}
