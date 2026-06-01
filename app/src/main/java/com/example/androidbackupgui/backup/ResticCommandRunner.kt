package com.example.androidbackupgui.backup

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import kotlin.coroutines.coroutineContext
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
    fun buildCommandArgs(args: List<String>): List<String> {
        val cmd = listOf(binaryPath) + args
        Log.d(TAG, "buildCommandArgs: binaryPath=$binaryPath args=$args → cmd=$cmd")
        return cmd
    }

    /** Run restic (non-streaming). */
    fun runRestic(env: Map<String, String>, args: List<String>): CommandResult {
        val cmdArgs = buildCommandArgs(args)
        Log.i(TAG, "runRestic cmd=${cmdArgs.joinToString(" ")}")
        Log.d(TAG, "runRestic REPOSITORY=${env["RESTIC_REPOSITORY"]}")

        return try {
            val pb = ProcessBuilder(cmdArgs)
            pb.environment().putAll(env)
            pb.redirectErrorStream(false)
            val process = pb.start()

            val stderrText = StringBuilder()
            val stderrThread = Thread({
                try {
                    process.errorStream.bufferedReader().use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            Log.d(TAG, "restic stderr: $line")
                            stderrText.appendLine(line)
                        }
                    }
                } catch (_: Exception) {}
            }, "restic-stderr").apply { isDaemon = true; start() }

            val stdout = process.inputStream.bufferedReader().use(BufferedReader::readText)
            val exitCode = process.waitFor()
            stderrThread.join(5000)
            Log.i(TAG, "runRestic exitCode=$exitCode stdout_len=${stdout.length}")
            if (stderrText.isNotEmpty()) Log.w(TAG, "runRestic stderr: ${stderrText}")
            CommandResult(stdout.trim(), stderrText.toString().trim(), exitCode)
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

        var process: Process? = null
        try {
            val pb = ProcessBuilder(cmdArgs)
            pb.environment().putAll(env)
            pb.redirectErrorStream(false)
            process = pb.start()

            val stdoutText = StringBuilder()
            val reader = process.inputStream.bufferedReader()
            val stderrReader = process.errorStream.bufferedReader()

            val stderrText = StringBuilder()
            val stderrThread = Thread({
                try { stderrReader.use { stderrText.append(it.readText()) } } catch (_: Exception) {}
            }, "restic-stderr").apply { isDaemon = true; start() }

            try {
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (!coroutineContext.isActive) {
                        process.destroy()
                        break
                    }
                    val l = line!!
                    stdoutText.appendLine(l)
                    onLine(l)
                }
            } finally {
                try { reader.close() } catch (_: Exception) {}
            }

            stderrThread.join(5000)
            val exitCode = try { process.waitFor() } catch (_: Exception) { -1 }

            Log.i(TAG, "runResticStreaming exitCode=$exitCode stdout_len=${stdoutText.length}")
            if (stderrText.isNotEmpty()) Log.w(TAG, "runResticStreaming stderr: ${stderrText}")
            CommandResult(stdoutText.toString().trim(), stderrText.toString().trim(), exitCode)
        } catch (e: Exception) {
            Log.e(TAG, "runResticStreaming exception", e)
            try { process?.destroy() } catch (_: Exception) {}
            CommandResult("", e.message ?: "Unknown error", -1)
        }
    }
}
