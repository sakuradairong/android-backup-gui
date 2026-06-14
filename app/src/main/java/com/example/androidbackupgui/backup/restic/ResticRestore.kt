package com.example.androidbackupgui.backup.restic

import com.example.androidbackupgui.backup.core.AppError
import com.example.androidbackupgui.backup.core.AppResult
import com.example.androidbackupgui.backup.err
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Restore operations: full directory restore and single-file dump.
 *
 * 使用 [BackendExecutor] 统一处理 local/remote 后端。
 */
class ResticRestore(
    private val runner: ResticCommandRunner,
    private val envResolver: ResticEnvResolver,
    private val bridgeRunner: RestBridgeRunner,
    private val executor: BackendExecutor = BackendExecutor(),
) {
    var cacheDir: String = ""
    var backendDomain: String = ""

    // ── Restore ────────────────────────────────────────

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
        onProgress: suspend (String) -> Unit = {},
    ): AppResult<Unit> =
        withContext(Dispatchers.IO) {
            val emit: suspend (String) -> Unit = { s -> withContext(Dispatchers.Main) { onProgress(s) } }
            File(targetPath).mkdirs()

            val args = mutableListOf("restore", snapshotId, "--target", targetPath, "--json")
            if (include != null) {
                args.add("--include")
                args.add(include)
            }

            val result =
                executor.withBackend(
                    repoPath = repoPath,
                    password = password,
                    cacheDir = cacheDir,
                    backend = backend,
                    backendUrl = backendUrl,
                    backendUser = backendUser,
                    backendPass = backendPass,
                    backendShare = backendShare,
                    backendDomain = backendDomain,
                    runner = runner,
                    envResolver = envResolver,
                    bridgeRunner = bridgeRunner,
                ) { env ->
                    runner.runResticStreaming(env, args) { line ->
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
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            emit(line)
                        }
                    }
                }

            if (result.exitCode == 0) {
                AppResult.Success(Unit)
            } else {
                err(AppError.Restic("restic restore 失败", result.exitCode, result.stderr))
            }
        }

    // ── File dump ──────────────────────────────────────

    suspend fun dump(
        repoPath: String,
        password: String,
        snapshotId: String,
        filePath: String,
        backend: String = "local",
        backendUrl: String = "",
        backendUser: String = "",
        backendPass: String = "",
        backendShare: String = "",
    ): AppResult<String> =
        withContext(Dispatchers.IO) {
            val result =
                executor.withBackend(
                    repoPath = repoPath,
                    password = password,
                    cacheDir = cacheDir,
                    backend = backend,
                    backendUrl = backendUrl,
                    backendUser = backendUser,
                    backendPass = backendPass,
                    backendShare = backendShare,
                    backendDomain = backendDomain,
                    runner = runner,
                    envResolver = envResolver,
                    bridgeRunner = bridgeRunner,
                ) { env -> runner.runRestic(env, "dump", snapshotId, filePath) }

            if (result.exitCode == 0) {
                AppResult.Success(result.stdout)
            } else {
                err(AppError.Restic(result.stderr.ifEmpty { "restic dump 失败" }, result.exitCode, result.stderr))
            }
        }
}
