package com.example.androidbackupgui.backup

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.coroutines.coroutineContext

/** Shared Json instance configured for restic's snake_case output via @SerialName. */
private val resticJson = Json { ignoreUnknownKeys = true }

/**
 * Backup operations: running restic backup and parsing its summary output.
 *
 * Delegates execution to [ResticCommandRunner], [ResticEnvResolver], and
 * [RemoteSyncManager] which are shared across sub-modules.
 */
class ResticBackup(
    private val runner: ResticCommandRunner,
    private val envResolver: ResticEnvResolver,
    private val syncManager: RemoteSyncManager
) {
    private val TAG = "ResticWrapper"

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
        onSyncProgress: suspend (RemoteTransport.TransferProgress) -> Unit = {},
        onByteSyncProgress: suspend (RemoteTransport.ByteProgress) -> Unit = {},
        onProgress: suspend (ResticWrapper.ResticProgress) -> Unit = {}
    ): Result<ResticWrapper.BackupSummary> = withContext(Dispatchers.IO) {
        val emit: suspend (ResticWrapper.ResticProgress) -> Unit = { p -> withContext(Dispatchers.Main) { onProgress(p) } }
        syncManager.withRemoteSync(backend, backendUrl, backendUser, backendPass, backendShare, repoPath,
            needsDownload = true, needsUpload = true,
            onProgress = onSyncProgress,
            onByteProgress = onByteSyncProgress,
        ) {
            val args = mutableListOf("backup", "--json")
            for (path in paths) args.add(path)
            for (tag in tags) { args.add("--tag"); args.add(tag) }
            if (hostname != null) { args.add("--host"); args.add(hostname) }

            val env = envResolver.buildFullEnv(repoPath, password, backend, backendUrl, backendUser, backendPass, backendShare, syncManager.tempRepoDir)
            val result = runner.runResticStreaming(env, args) { line ->
                if (!coroutineContext.isActive) return@runResticStreaming
                try {
                    val progress = resticJson.decodeFromString<ResticWrapper.ResticProgress>(line)
                    if (progress.messageType == "status") emit(progress)
                } catch (_: Exception) { /* ignore non-JSON lines */ }
            }

            if (result.exitCode != 0) {
                return@withRemoteSync Result.failure(Exception("restic backup failed: ${result.stderr}"))
            }

            parseBackupSummary(result.stdout)
        }
    }

    // ── Internal helpers ───────────────────────────────

    /** Parse the JSON summary from the end of restic backup output. */
    private fun parseBackupSummary(stdout: String): Result<ResticWrapper.BackupSummary> {
        val lines = stdout.lines()
        for (i in lines.indices.reversed()) {
            val line = lines[i].trim()
            if (!line.startsWith("{")) continue
            try {
                val summary = resticJson.decodeFromString<ResticWrapper.BackupSummary>(line)
                if (summary.messageType == "summary" && summary.snapshotId.isNotEmpty()) return Result.success(summary)
            } catch (_: Exception) { /* keep looking */ }
        }
        return Result.failure(Exception("No summary found in restic output"))
    }
}
