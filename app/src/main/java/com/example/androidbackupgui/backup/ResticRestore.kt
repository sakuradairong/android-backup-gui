package com.example.androidbackupgui.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.serialization.json.Json
import kotlin.coroutines.coroutineContext

/** Shared Json instance configured for restic's snake_case output via @SerialName. */
private val resticJson = Json { ignoreUnknownKeys = true }

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
    ): Result<Unit> = withContext(Dispatchers.IO) {
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

            if (result.exitCode == 0) Result.success(Unit)
            else Result.failure(Exception("restic restore failed: ${result.stderr}"))
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
    ): Result<String> = withContext(Dispatchers.IO) {
        syncManager.withRemoteSync(backend, backendUrl, backendUser, backendPass, backendShare, repoPath,
            needsDownload = true, needsUpload = false,
            onProgress = onSyncProgress,
            onByteProgress = onByteSyncProgress,
        ) {
            val env = envResolver.buildFullEnv(repoPath, password, backend, backendUrl, backendUser, backendPass, backendShare, syncManager.tempRepoDir)
            val result = runner.runRestic(env, "dump", snapshotId, filePath)
            if (result.exitCode == 0) Result.success(result.stdout)
            else Result.failure(Exception(result.stderr.ifEmpty { "restic dump failed with exit code ${result.exitCode}" }))
        }
    }
}
