package com.example.androidbackupgui.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.io.File
import kotlin.coroutines.coroutineContext
import com.example.androidbackupgui.backup.AppError
import com.example.androidbackupgui.backup.AppResult
import com.example.androidbackupgui.backup.err


/**
 * Restore operations: full directory restore and single-file dump.
 *
 * Both are download-only operations (no upload to remote needed).
 * Delegates execution to [ResticCommandRunner], [ResticEnvResolver], and
 * [RestBridgeRunner] which are shared across sub-modules.
 *
 * @property cacheDir Cache directory for restic env and bridge temp files; set by [ResticWrapper].
 * @property backendDomain Domain for SMB NTLM authentication; set by [ResticWrapper].
 */
class ResticRestore(
    private val runner: ResticCommandRunner,
    private val envResolver: ResticEnvResolver,
    private val bridgeRunner: RestBridgeRunner
) {
    /** Cache directory for restic env and bridge temp files. Set by [ResticWrapper]. */
    var cacheDir: String = ""

    /** Domain for SMB NTLM authentication. Set by [ResticWrapper]. */
    var backendDomain: String = ""

    // ── Restore ────────────────────────────────────────

    /**
     * Restore a snapshot to [targetPath], optionally filtered by [include] pattern.
     *
     * For local backends, builds env via [ResticEnvResolver.buildLocalEnv] and runs
     * restic restore directly. For remote backends, proxies through [RestBridgeRunner]
     * using a local REST bridge, building env via [ResticEnvResolver.buildBridgeEnv].
     */
    suspend fun restore(
        repoPath: String,
        password: String,
        snapshotId: String,
        targetPath: String,
        include: String? = null,
        backend: String = "local",
        backendUrl: String = "",
        backendUser: String = "",
        backendPass: String = "",
        backendShare: String = "",
        onProgress: suspend (String) -> Unit = {}
    ): AppResult<Unit> = withContext(Dispatchers.IO) {
        val emit: suspend (String) -> Unit = { s -> withContext(Dispatchers.Main) { onProgress(s) } }

        if (backend == "local") {
            File(targetPath).mkdirs()

            val args = mutableListOf("restore", snapshotId, "--target", targetPath, "--json")
            if (include != null) { args.add("--include"); args.add(include) }

            val env = envResolver.buildLocalEnv(repoPath, password, cacheDir)
            val result = runner.runResticStreaming(env, args) { line ->
                if (!coroutineContext.isActive) return@runResticStreaming
                try {
                    val progress = resticJson.decodeFromString<ResticWrapper.ResticProgress>(line)
                    when (progress.messageType) {
                        "status" -> {
                            val percent = "%.1f".format(progress.percentDone * 100)
                            emit("恢复进度: $percent%")
                        }
                        "summary" -> {
                            emit("恢复完成: ${progress.totalFiles} 个文件")
                        }
                    }
                } catch (e: Exception) { if (e is CancellationException) throw e; emit(line) }
            }

            if (result.exitCode == 0) AppResult.Success(Unit)
            else err(AppError.Restic("restic restore 失败", result.exitCode, result.stderr))
        } else {
            bridgeRunner.withBridge(
                backend, backendUrl, backendUser, backendPass, backendShare, backendDomain,
                repoPath, File(cacheDir)
            ) { bridgeUrl, authToken ->
                File(targetPath).mkdirs()

                val args = mutableListOf("restore", snapshotId, "--target", targetPath, "--json")
                if (include != null) { args.add("--include"); args.add(include) }

                val env = envResolver.buildBridgeEnv(password, bridgeUrl, cacheDir, authToken)
                val result = runner.runResticStreaming(env, args) { line ->
                    if (!coroutineContext.isActive) return@runResticStreaming
                    try {
                        val progress = resticJson.decodeFromString<ResticWrapper.ResticProgress>(line)
                        when (progress.messageType) {
                            "status" -> {
                                val percent = "%.1f".format(progress.percentDone * 100)
                                emit("恢复进度: $percent%")
                            }
                            "summary" -> {
                                emit("恢复完成: ${progress.totalFiles} 个文件")
                            }
                        }
                } catch (e: Exception) { if (e is CancellationException) throw e; emit(line) }
                }

                if (result.exitCode == 0) AppResult.Success(Unit)
                else err(AppError.Restic("restic restore 失败", result.exitCode, result.stderr))
            }
        }
    }

    // ── File dump ──────────────────────────────────────

    /**
     * Dump the contents of a single file from a snapshot.
     *
     * For local backends, builds env via [ResticEnvResolver.buildLocalEnv] and runs
     * restic dump directly. For remote backends, proxies through [RestBridgeRunner]
     * using a local REST bridge, building env via [ResticEnvResolver.buildBridgeEnv].
     */
    suspend fun dump(
        repoPath: String,
        password: String,
        snapshotId: String,
        filePath: String,
        backend: String = "local",
        backendUrl: String = "",
        backendUser: String = "",
        backendPass: String = "",
        backendShare: String = ""
    ): AppResult<String> = withContext(Dispatchers.IO) {
        if (backend == "local") {
            val env = envResolver.buildLocalEnv(repoPath, password, cacheDir)
            val result = runner.runRestic(env, "dump", snapshotId, filePath)
            if (result.exitCode == 0) AppResult.Success(result.stdout)
            else err(AppError.Restic(result.stderr.ifEmpty { "restic dump 失败" }, result.exitCode, result.stderr))
        } else {
            bridgeRunner.withBridge(
                backend, backendUrl, backendUser, backendPass, backendShare, backendDomain,
                repoPath, File(cacheDir)
            ) { bridgeUrl, authToken ->
                val env = envResolver.buildBridgeEnv(password, bridgeUrl, cacheDir, authToken)
                val result = runner.runRestic(env, "dump", snapshotId, filePath)
                if (result.exitCode == 0) AppResult.Success(result.stdout)
                else err(AppError.Restic(result.stderr.ifEmpty { "restic dump 失败" }, result.exitCode, result.stderr))
            }
        }
    }
}
