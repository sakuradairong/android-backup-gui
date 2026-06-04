package com.example.androidbackupgui.backup

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.androidbackupgui.backup.AppError
import com.example.androidbackupgui.backup.AppResult
import com.example.androidbackupgui.backup.err

/**
 * Repository lifecycle operations: init and repo URL construction.
 *
 * Delegates execution to [ResticCommandRunner], [ResticEnvResolver], and
 * [RemoteSyncManager] which are shared across sub-modules.
 */
class ResticRepoInit(
    private val runner: ResticCommandRunner,
    private val envResolver: ResticEnvResolver,
    private val syncManager: RemoteSyncManager
) {
    private val TAG = "ResticWrapper"

    // ── Repository initialization ──────────────────────

    suspend fun init(
        repoPath: String,
        password: String,
        backend: String = "local",
        backendUrl: String = "",
        backendUser: String = "",
        backendPass: String = "",
        backendShare: String = "",
        onSyncProgress: suspend (RemoteTransport.TransferProgress) -> Unit = {},
        onByteSyncProgress: suspend (RemoteTransport.ByteProgress) -> Unit = {},
    ): AppResult<Unit> =
        withContext(Dispatchers.IO) {
            syncManager.withRemoteSync(backend, backendUrl, backendUser, backendPass, backendShare, repoPath,
                needsDownload = true, needsUpload = true,
                onProgress = onSyncProgress,
                onByteProgress = onByteSyncProgress,
            ) {
                val env = envResolver.buildFullEnv(repoPath, password, backend, backendUrl, backendUser, backendPass, backendShare, syncManager.tempRepoDir)
                val result = runner.runRestic(env, "init")
                // exitCode 0 = brand new repo created, needs upload
                if (result.exitCode == 0) {
                    return@withRemoteSync AppResult.Success(Unit)
                }
                // exitCode 1 = config already exists; verify the repo is actually usable
                if (result.exitCode == 1) {
                    val verify = runner.runRestic(env, "snapshots", "--json")
                    if (verify.exitCode == 0) {
                        // Repo is healthy — already initialized with matching password
                        Log.i(TAG, "init: repo already initialized and verified")
                        return@withRemoteSync AppResult.Success(Unit)
                    }
                    // Config exists but repo is corrupted (wrong password, missing keys, etc.)
                    return@withRemoteSync err(
                        AppError.Restic("仓库已存在但无法验证", verify.exitCode, verify.stderr)
                    )
                }
                err(AppError.Restic("restic init 失败", result.exitCode, result.stderr))
            }
        }

    // ── Public URL helper ──────────────────────────────

    /** Build a display-friendly repository URL for UI. */
    fun buildRepoUrl(backend: String, repoPath: String, backendUrl: String): String {
        return envResolver.buildRepoUrl(backend, repoPath, backendUrl)
    }
}
