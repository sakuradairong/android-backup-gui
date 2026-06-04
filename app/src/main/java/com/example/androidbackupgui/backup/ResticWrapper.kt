package com.example.androidbackupgui.backup

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import java.io.File
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import com.example.androidbackupgui.backup.AppError
import com.example.androidbackupgui.backup.AppResult
import com.example.androidbackupgui.backup.err

/**
 * Wraps the restic CLI binary for backup/restore operations.
 *
 * Uses environment variables (RESTIC_REPOSITORY, RESTIC_PASSWORD) rather than
 * command-line flags to avoid leaking secrets in the process list.
 *
 * For SMB/WebDAV backends, restic runs against a local temp directory;
 * RemoteTransport syncs files to/from the remote backend.
 *
 * All public methods are suspend and run on Dispatchers.IO.
 *
 * This object is a facade that delegates to [ResticCommandRunner],
 * [ResticEnvResolver], [RemoteSyncManager], and sub-module classes
 * ([ResticRepoInit], [ResticBackup], [ResticRestore], [ResticSnapshotOps],
 * [ResticMaintenance]).
 */
object ResticWrapper {

    private const val TAG = "ResticWrapper"

    private val runner = ResticCommandRunner()
    private val envResolver = ResticEnvResolver()
    private val syncManager = RemoteSyncManager()

    // ── Sub-module instances ───────────────────────────

    private val repoInit = ResticRepoInit(runner, envResolver, syncManager)
    private val backupOp = ResticBackup(runner, envResolver, syncManager)
    private val restoreOp = ResticRestore(runner, envResolver, syncManager)
    private val snapshotOps = ResticSnapshotOps(runner, envResolver, syncManager)
    private val maintenance = ResticMaintenance(runner, envResolver, syncManager)

    // ── Property delegation ───────────────────────────

    /** Path to the restic binary. Default assumes it's on PATH (e.g. Termux). */
    var binaryPath: String
        get() = runner.binaryPath
        set(v) { runner.binaryPath = v }

    /** Local temp directory used as restic repo for SMB/WebDAV backends. */
    var tempRepoDir: String
        get() = syncManager.tempRepoDir
        set(v) { syncManager.tempRepoDir = v }

    /** Domain for SMB NTLM authentication. */
    var backendDomain: String
        get() = syncManager.backendDomain
        set(v) { syncManager.backendDomain = v }

    // ── Progress data ─────────────────────────────────

    @Serializable
    data class ResticProgress(
        @SerialName("message_type") val messageType: String,       // "status" during backup
        @SerialName("percent_done") val percentDone: Double = 0.0,
        @SerialName("total_files") val totalFiles: Int = 0,
        @SerialName("files_done") val filesDone: Int = 0,
        @SerialName("total_bytes") val totalBytes: Long = 0,
        @SerialName("bytes_done") val bytesDone: Long = 0,
        @SerialName("current_files") val currentFiles: List<String> = emptyList()
    )

    @Serializable
    data class ResticSnapshot(
        val id: String,
        @SerialName("short_id") val shortId: String,
        val time: String,
        val paths: List<String>,
        val tags: List<String>,
        val hostname: String = ""
    )

    // ── Repository lifecycle ─────────────────────────

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
    ): AppResult<Unit> = repoInit.init(
        repoPath, password, backend, backendUrl, backendUser, backendPass, backendShare,
        onSyncProgress, onByteSyncProgress
    )

    // ── Backup ─────────────────────────────────────────

    @Serializable
    data class BackupSummary(
        @SerialName("message_type") val messageType: String = "",
        @SerialName("snapshot_id") val snapshotId: String,
        @SerialName("files_new") val filesNew: Int = 0,
        @SerialName("files_changed") val filesChanged: Int = 0,
        @SerialName("files_unmodified") val filesUnmodified: Int = 0,
        @SerialName("dirs_new") val dirsNew: Int = 0,
        @SerialName("dirs_changed") val dirsChanged: Int = 0,
        @SerialName("dirs_unmodified") val dirsUnmodified: Int = 0,
        @SerialName("data_blobs") val dataBlobs: Int = 0,
        @SerialName("tree_blobs") val treeBlobs: Int = 0,
        @SerialName("data_added") val dataAdded: Long = 0,
        @SerialName("total_files_processed") val totalFilesProcessed: Int = 0,
        @SerialName("total_bytes_processed") val totalBytesProcessed: Long = 0,
        @SerialName("total_duration") val totalDuration: Double = 0.0
    )

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
        onProgress: suspend (ResticProgress) -> Unit = {}
    ): AppResult<BackupSummary> = backupOp.backup(
        repoPath, password, paths, tags, hostname,
        backend, backendUrl, backendUser, backendPass, backendShare,
        onSyncProgress, onByteSyncProgress, onProgress
    )

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
    ): AppResult<Unit> = restoreOp.restore(
        repoPath, password, snapshotId, targetPath, include,
        backend, backendUrl, backendUser, backendPass, backendShare,
        onSyncProgress, onByteSyncProgress, onProgress
    )

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
    ): AppResult<String> = restoreOp.dump(
        repoPath, password, snapshotId, filePath,
        backend, backendUrl, backendUser, backendPass, backendShare,
        onSyncProgress, onByteSyncProgress
    )

    // ── Snapshot management ────────────────────────────

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
    ): AppResult<List<ResticSnapshot>> = snapshotOps.listSnapshots(
        repoPath, password, tag,
        backend, backendUrl, backendUser, backendPass, backendShare,
        onSyncProgress, onByteSyncProgress
    )

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
    ): AppResult<String> = snapshotOps.forget(
        repoPath, password, keepDaily, keepWeekly, keepMonthly, dryRun,
        backend, backendUrl, backendUser, backendPass, backendShare,
        onSyncProgress, onByteSyncProgress
    )

    // ── Maintenance ────────────────────────────────────

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
    ): AppResult<String> = maintenance.prune(
        repoPath, password,
        backend, backendUrl, backendUser, backendPass, backendShare,
        onSyncProgress, onByteSyncProgress
    )

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
    ): AppResult<String> = maintenance.check(
        repoPath, password,
        backend, backendUrl, backendUser, backendPass, backendShare,
        onSyncProgress, onByteSyncProgress
    )

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
    ): AppResult<String> = maintenance.stats(
        repoPath, password,
        backend, backendUrl, backendUser, backendPass, backendShare,
        onSyncProgress, onByteSyncProgress
    )

    // ── Public URL helper ──────────────────────────────

    /** Build a display-friendly repository URL for UI. */
    fun buildRepoUrl(backend: String, repoPath: String, backendUrl: String): String {
        return repoInit.buildRepoUrl(backend, repoPath, backendUrl)
    }

    // ── Lifecycle ──────────────────────────────────────

    /**
     * Public safety-net cleanup called by fragment lifecycle.
     * Waits for any in-progress operation to finish, then deletes temp dirs.
     */
    suspend fun cleanup() {
        syncManager.cleanup()
    }
}
