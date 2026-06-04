package com.example.androidbackupgui.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.androidbackupgui.backup.AppError
import com.example.androidbackupgui.backup.AppResult
import com.example.androidbackupgui.backup.err

/**
 * Repository maintenance operations: prune, check, stats.
 *
 * [prune] requires both download and upload (it removes pack files from the remote).
 * [check] and [stats] are download-only read operations.
 * Delegates execution to [ResticCommandRunner], [ResticEnvResolver], and
 * [RemoteSyncManager] which are shared across sub-modules.
 */
class ResticMaintenance(
    private val runner: ResticCommandRunner,
    private val envResolver: ResticEnvResolver,
    private val syncManager: RemoteSyncManager
) {
    // ── Prune ──────────────────────────────────────────

    suspend fun prune(
        repoPath: String,
        password: String,
        backend: String = "local",
        backendUrl: String = "",
        backendUser: String = "",
        backendPass: String = "",
        backendShare: String = "",
        onSyncProgress: suspend (RemoteTransport.TransferProgress) -> Unit = {},
        onByteSyncProgress: suspend (RemoteTransport.ByteProgress) -> Unit = {},
    ): AppResult<String> =
        withContext(Dispatchers.IO) {
            syncManager.withRemoteSync(backend, backendUrl, backendUser, backendPass, backendShare, repoPath,
                needsDownload = true, needsUpload = true,
                onProgress = onSyncProgress,
                onByteProgress = onByteSyncProgress,
            ) {
                val env = envResolver.buildFullEnv(repoPath, password, backend, backendUrl, backendUser, backendPass, backendShare, syncManager.tempRepoDir)
                val result = runner.runRestic(env, "prune")
                if (result.exitCode == 0) AppResult.Success(result.stdout)
                else err(AppError.Restic("restic prune 失败", result.exitCode, result.stderr))
            }
        }

    // ── Check ──────────────────────────────────────────

    suspend fun check(
        repoPath: String,
        password: String,
        backend: String = "local",
        backendUrl: String = "",
        backendUser: String = "",
        backendPass: String = "",
        backendShare: String = "",
        onSyncProgress: suspend (RemoteTransport.TransferProgress) -> Unit = {},
        onByteSyncProgress: suspend (RemoteTransport.ByteProgress) -> Unit = {},
    ): AppResult<String> =
        withContext(Dispatchers.IO) {
            syncManager.withRemoteSync(backend, backendUrl, backendUser, backendPass, backendShare, repoPath,
                needsDownload = true, needsUpload = false,
                onProgress = onSyncProgress,
                onByteProgress = onByteSyncProgress,
            ) {
                val env = envResolver.buildFullEnv(repoPath, password, backend, backendUrl, backendUser, backendPass, backendShare, syncManager.tempRepoDir)
                val result = runner.runRestic(env, "check")
                if (result.exitCode == 0) AppResult.Success(result.stdout)
                else err(AppError.Restic("restic check 失败", result.exitCode, result.stderr))
            }
        }

    // ── Stats ──────────────────────────────────────────

    suspend fun stats(
        repoPath: String,
        password: String,
        backend: String = "local",
        backendUrl: String = "",
        backendUser: String = "",
        backendPass: String = "",
        backendShare: String = "",
        onSyncProgress: suspend (RemoteTransport.TransferProgress) -> Unit = {},
        onByteSyncProgress: suspend (RemoteTransport.ByteProgress) -> Unit = {},
    ): AppResult<String> =
        withContext(Dispatchers.IO) {
            syncManager.withRemoteSync(backend, backendUrl, backendUser, backendPass, backendShare, repoPath,
                needsDownload = true, needsUpload = false,
                onProgress = onSyncProgress,
                onByteProgress = onByteSyncProgress,
            ) {
                val env = envResolver.buildFullEnv(repoPath, password, backend, backendUrl, backendUser, backendPass, backendShare, syncManager.tempRepoDir)
                val result = runner.runRestic(env, "stats")
                if (result.exitCode == 0) AppResult.Success(result.stdout)
                else err(AppError.Restic("restic stats 失败", result.exitCode, result.stderr))
            }
        }
}
