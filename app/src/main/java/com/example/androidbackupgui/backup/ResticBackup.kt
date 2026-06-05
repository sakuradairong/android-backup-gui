package com.example.androidbackupgui.backup

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import com.example.androidbackupgui.backup.AppError
import com.example.androidbackupgui.backup.AppResult
import com.example.androidbackupgui.backup.err
import java.io.File


/**
 * Backup operations: running restic backup and parsing its summary output.
 *
 * Delegates execution to [ResticCommandRunner], [ResticEnvResolver], and
 * [RestBridgeRunner] which are shared across sub-modules.
 */
class ResticBackup(
    private val runner: ResticCommandRunner,
    private val envResolver: ResticEnvResolver,
    private val bridgeRunner: RestBridgeRunner
) {
    private val TAG = "ResticBackup"
    var cacheDir: String = ""
    var backendDomain: String = ""

    // ── Backup ─────────────────────────────────────────

    suspend fun backup(
        repoPath: String,
        password: String,
        paths: List<String>,
        tags: List<String> = emptyList(),
        hostname: String? = null,
        backend: String = "local",
        backendUrl: String = "",
        backendUser: String = "",
        backendPass: String = "",
        backendShare: String = "",
        onProgress: suspend (ResticWrapper.ResticProgress) -> Unit = {}
    ): AppResult<ResticWrapper.BackupSummary> = withContext(Dispatchers.IO) {
        val emit: suspend (ResticWrapper.ResticProgress) -> Unit = { p -> withContext(Dispatchers.Main) { onProgress(p) } }

        if (backend == "local") {
            val args = mutableListOf("backup", "--json")
            for (path in paths) args.add(path)
            for (tag in tags) { args.add("--tag"); args.add(tag) }
            if (hostname != null) { args.add("--host"); args.add(hostname) }

            val env = envResolver.buildLocalEnv(repoPath, password, cacheDir)
            val result = runner.runResticStreaming(env, args) { line ->
                if (!coroutineContext.isActive) return@runResticStreaming
                try {
                    val progress = resticJson.decodeFromString<ResticWrapper.ResticProgress>(line)
                    if (progress.messageType == "status") emit(progress)
                } catch (_: Exception) { }
            }

            if (result.exitCode != 0) return@withContext err(AppError.Restic("restic backup 失败", result.exitCode, result.stderr))
            parseBackupSummary(result.stdout)
        } else {
            bridgeRunner.withBridge(backend, backendUrl, backendUser, backendPass, backendShare, backendDomain, repoPath, File(cacheDir)) { bridgeUrl ->
                val args = mutableListOf("backup", "--json")
                for (path in paths) args.add(path)
                for (tag in tags) { args.add("--tag"); args.add(tag) }
                if (hostname != null) { args.add("--host"); args.add(hostname) }

                val env = envResolver.buildBridgeEnv(password, bridgeUrl, cacheDir)
                val result = runner.runResticStreaming(env, args) { line ->
                    if (!coroutineContext.isActive) return@runResticStreaming
                    try {
                        val progress = resticJson.decodeFromString<ResticWrapper.ResticProgress>(line)
                        if (progress.messageType == "status") emit(progress)
                    } catch (_: Exception) { }
                }

                if (result.exitCode != 0) return@withBridge err(AppError.Restic("restic backup 失败", result.exitCode, result.stderr))
                parseBackupSummary(result.stdout)
            }
        }
    }

    // ── Streaming backup (stdin) ──────────────────────

    /**
     * Run restic backup in --stdin mode, reading tar data from [stdinFile] (FIFO).
     * [extraPaths] are files/directories backed up alongside the streaming data
     * (e.g. APK paths, metadata directory).
     */
    suspend fun backupStdin(
        repoPath: String,
        password: String,
        stdinFile: File,
        extraPaths: List<String>,
        tags: List<String> = emptyList(),
        hostname: String? = null,
        backend: String = "local",
        backendUrl: String = "",
        backendUser: String = "",
        backendPass: String = "",
        backendShare: String = "",
        onProgress: suspend (ResticWrapper.ResticProgress) -> Unit = {}
    ): AppResult<ResticWrapper.BackupSummary> = withContext(Dispatchers.IO) {
        val emit: suspend (ResticWrapper.ResticProgress) -> Unit = { p -> withContext(Dispatchers.Main) { onProgress(p) } }

        val args = mutableListOf("backup", "--json", "--stdin", "--stdin-filename", "app_data.tar")
        for (path in extraPaths) args.add(path)
        for (tag in tags) { args.add("--tag"); args.add(tag) }
        if (hostname != null) { args.add("--host"); args.add(hostname) }

        if (backend == "local") {
            val env = envResolver.buildLocalEnv(repoPath, password, cacheDir)
            val result = runner.runResticWithStdin(env, args, stdinFile) { line ->
                if (!coroutineContext.isActive) return@runResticWithStdin
                try {
                    val progress = resticJson.decodeFromString<ResticWrapper.ResticProgress>(line)
                    if (progress.messageType == "status") emit(progress)
                } catch (_: Exception) { }
            }

            if (result.exitCode != 0) return@withContext err(AppError.Restic("restic stream backup 失败", result.exitCode, result.stderr))
            parseBackupSummary(result.stdout)
        } else {
            bridgeRunner.withBridge(backend, backendUrl, backendUser, backendPass, backendShare, backendDomain, repoPath, File(cacheDir)) { bridgeUrl ->
                val env = envResolver.buildBridgeEnv(password, bridgeUrl, cacheDir)
                val result = runner.runResticWithStdin(env, args, stdinFile) { line ->
                    if (!coroutineContext.isActive) return@runResticWithStdin
                    try {
                        val progress = resticJson.decodeFromString<ResticWrapper.ResticProgress>(line)
                        if (progress.messageType == "status") emit(progress)
                    } catch (_: Exception) { }
                }

                if (result.exitCode != 0) return@withBridge err(AppError.Restic("restic stream backup 失败", result.exitCode, result.stderr))
                parseBackupSummary(result.stdout)
            }
        }
    }

    // ── Internal helpers ───────────────────────────────

    /** Parse the JSON summary from the end of restic backup output. */
    private fun parseBackupSummary(stdout: String): AppResult<ResticWrapper.BackupSummary> {
        val lines = stdout.lines()
        for (i in lines.indices.reversed()) {
            val line = lines[i].trim()
            if (!line.startsWith("{")) continue
            try {
                val summary = resticJson.decodeFromString<ResticWrapper.BackupSummary>(line)
                if (summary.messageType == "summary" && summary.snapshotId.isNotEmpty()) return AppResult.Success(summary)
            } catch (_: Exception) { /* keep looking */ }
        }
        return err(AppError.Parse("restic 备份输出未找到摘要信息", "stdout=" + stdout.length))
    }
}
