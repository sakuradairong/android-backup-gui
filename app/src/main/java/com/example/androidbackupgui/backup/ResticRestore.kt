package com.example.androidbackupgui.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
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
 * [RemoteSyncManager] which are shared across sub-modules.
 */
class ResticRestore(
    private val runner: ResticCommandRunner,
    private val envResolver: ResticEnvResolver,
    private val syncManager: RemoteSyncManager
) {
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
        onSyncProgress: suspend (RemoteTransport.TransferProgress) -> Unit = {},
        onByteSyncProgress: suspend (RemoteTransport.ByteProgress) -> Unit = {},
        onProgress: suspend (String) -> Unit = {}
    ): AppResult<Unit> = withContext(Dispatchers.IO) {
        val emit: suspend (String) -> Unit = { s -> withContext(Dispatchers.Main) { onProgress(s) } }
        syncManager.withRemoteSync(backend, backendUrl, backendUser, backendPass, backendShare, repoPath,
            needsDownload = true, needsUpload = false,
            onProgress = onSyncProgress,
            onByteProgress = onByteSyncProgress,
        ) {
            File(targetPath).mkdirs()

            val args = mutableListOf("restore", snapshotId, "--target", targetPath, "--json")
            if (include != null) { args.add("--include"); args.add(include) }

            val env = envResolver.buildFullEnv(repoPath, password, backend, backendUrl, backendUser, backendPass, backendShare, syncManager.tempRepoDir)
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
                } catch (_: Exception) { emit(line) }
            }

            if (result.exitCode == 0) AppResult.Success(Unit)
            else err(AppError.Restic("restic restore 失败", result.exitCode, result.stderr))
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
        onSyncProgress: suspend (RemoteTransport.TransferProgress) -> Unit = {},
        onByteSyncProgress: suspend (RemoteTransport.ByteProgress) -> Unit = {},
    ): AppResult<String> = withContext(Dispatchers.IO) {
        syncManager.withRemoteSync(backend, backendUrl, backendUser, backendPass, backendShare, repoPath,
            needsDownload = true, needsUpload = false,
            onProgress = onSyncProgress,
            onByteProgress = onByteSyncProgress,
        ) {
            val env = envResolver.buildFullEnv(repoPath, password, backend, backendUrl, backendUser, backendPass, backendShare, syncManager.tempRepoDir)
            val result = runner.runRestic(env, "dump", snapshotId, filePath)
            if (result.exitCode == 0) AppResult.Success(result.stdout)
            else err(AppError.Restic(result.stderr.ifEmpty { "restic dump 失败" }, result.exitCode, result.stderr))
        }
    }
}
