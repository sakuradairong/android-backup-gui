package com.example.androidbackupgui.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json


/** Shared Json instance configured for restic's snake_case output via @SerialName. */
private val resticJson = Json { ignoreUnknownKeys = true }
/**
 * Snapshot listing and retention policy operations.
 *
 * [listSnapshots] is download-only; [forget] requires both download and upload
 * (forget removes snapshots from the remote).
 * Delegates execution to [ResticCommandRunner], [ResticEnvResolver], and
 * [RemoteSyncManager] which are shared across sub-modules.
 */
class ResticSnapshotOps(
    private val runner: ResticCommandRunner,
    private val envResolver: ResticEnvResolver,
    private val syncManager: RemoteSyncManager
) {
    // ── List snapshots ─────────────────────────────────

    suspend fun listSnapshots(
        repoPath: String,
        password: String,
        tag: String? = null,
        backend: String = "local",
        backendUrl: String = "",
        backendUser: String = "",
        backendPass: String = "",
        backendShare: String = "",
        onSyncProgress: suspend (RemoteTransport.TransferProgress) -> Unit = {},
        onByteSyncProgress: suspend (RemoteTransport.ByteProgress) -> Unit = {},
    ): Result<List<ResticWrapper.ResticSnapshot>> = withContext(Dispatchers.IO) {
        syncManager.withRemoteSync(backend, backendUrl, backendUser, backendPass, backendShare, repoPath,
            needsDownload = true, needsUpload = false,
            onProgress = onSyncProgress,
            onByteProgress = onByteSyncProgress,
        ) {
            val args = mutableListOf("snapshots", "--json")
            if (tag != null) { args.add("--tag"); args.add(tag) }

            val env = envResolver.buildFullEnv(repoPath, password, backend, backendUrl, backendUser, backendPass, backendShare, syncManager.tempRepoDir)
            val result = runner.runRestic(env, args)

            if (result.exitCode != 0) {
                return@withRemoteSync Result.failure(Exception("restic snapshots failed: ${result.stderr}"))
            }

            try {
                val snapshots = resticJson.decodeFromString<List<ResticWrapper.ResticSnapshot>>(
                    result.stdout.ifEmpty { "[]" }
                )
                Result.success(snapshots.sortedByDescending { it.time })
            } catch (e: Exception) {
                Result.failure(Exception("Failed to parse snapshot JSON: ${e.message}"))
            }
        }
    }

    // ── Forget (retention policy) ──────────────────────

    suspend fun forget(
        repoPath: String,
        password: String,
        keepDaily: Int = 7,
        keepWeekly: Int = 4,
        keepMonthly: Int = 3,
        dryRun: Boolean = false,
        backend: String = "local",
        backendUrl: String = "",
        backendUser: String = "",
        backendPass: String = "",
        backendShare: String = "",
        onSyncProgress: suspend (RemoteTransport.TransferProgress) -> Unit = {},
        onByteSyncProgress: suspend (RemoteTransport.ByteProgress) -> Unit = {},
    ): Result<String> = withContext(Dispatchers.IO) {
        syncManager.withRemoteSync(backend, backendUrl, backendUser, backendPass, backendShare, repoPath,
            needsDownload = true, needsUpload = true,
            onProgress = onSyncProgress,
            onByteProgress = onByteSyncProgress,
        ) {
            val args = mutableListOf(
                "forget",
                "--keep-daily", keepDaily.toString(),
                "--keep-weekly", keepWeekly.toString(),
                "--keep-monthly", keepMonthly.toString()
            )
            if (dryRun) args.add("--dry-run")

            val env = envResolver.buildFullEnv(repoPath, password, backend, backendUrl, backendUser, backendPass, backendShare, syncManager.tempRepoDir)
            val result = runner.runRestic(env, args)

            if (result.exitCode == 0) Result.success(result.stdout)
            else Result.failure(Exception("restic forget failed: ${result.stderr}"))
        }
    }
}
