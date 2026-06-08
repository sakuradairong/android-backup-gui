package com.example.androidbackupgui.backup

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import java.io.File
import kotlinx.coroutines.withContext
import org.json.JSONObject
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
 * For SMB/WebDAV backends, restic connects via a local REST bridge
 * ([ResticRestBridge]) that translates HTTP requests to [RemoteTransport] calls,
 * eliminating the need for a local staging repo and full-directory sync.
 *
 * All public methods are suspend and run on Dispatchers.IO.
 *
 * This object is a facade that delegates to [ResticCommandRunner],
 * [ResticEnvResolver], [RestBridgeRunner], and sub-module classes
 * ([ResticRepoInit], [ResticBackup], [ResticRestore], [ResticSnapshotOps],
 * [ResticMaintenance]).
 */
object ResticWrapper {

    private const val TAG = "ResticWrapper"

    internal val runner = ResticCommandRunner()
    internal val envResolver = ResticEnvResolver()
    private val bridgeRunner = RestBridgeRunner()

    // ── Sub-module instances ───────────────────────────

    private val repoInit = ResticRepoInit(runner, envResolver, bridgeRunner)
    private val backupOp = ResticBackup(runner, envResolver, bridgeRunner)
    private val restoreOp = ResticRestore(runner, envResolver, bridgeRunner)
    private val snapshotOps = ResticSnapshotOps(runner, envResolver, bridgeRunner)
    private val maintenance = ResticMaintenance(runner, envResolver, bridgeRunner)

    // ── Property delegation ───────────────────────────

    /** Path to the restic binary. Default assumes it's on PATH (e.g. Termux). */
    var binaryPath: String
        get() = runner.binaryPath
        set(v) { runner.binaryPath = v }

    /** Cache directory for restic (XDG_CACHE_HOME) and bridge tmp blobs. */
    var cacheDir: String = ""
        set(v) {
            field = v
            repoInit.cacheDir = v
            backupOp.cacheDir = v
            restoreOp.cacheDir = v
            snapshotOps.cacheDir = v
            maintenance.cacheDir = v
        }


    /** Domain for SMB NTLM authentication. Propagated to sub-modules. */
    var backendDomain: String = ""
        set(v) {
            field = v
            repoInit.backendDomain = v
            backupOp.backendDomain = v
            restoreOp.backendDomain = v
            snapshotOps.backendDomain = v
            maintenance.backendDomain = v
        }
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

    /** App metadata read from a restic snapshot for change detection. */
    data class SnapshotAppInfo(
        val label: String,
        val isSystem: Boolean,
        val apkSizes: List<Long> = emptyList()
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
    ): AppResult<Unit> = repoInit.init(
        repoPath, password, backend, backendUrl, backendUser, backendPass, backendShare
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
        onProgress: suspend (ResticProgress) -> Unit = {}
    ): AppResult<BackupSummary> = backupOp.backup(
        repoPath, password, paths, tags, hostname,
        backend, backendUrl, backendUser, backendPass, backendShare,
        onProgress
    )

    /**
     * Streaming backup: pipes tar data through a FIFO directly into restic --stdin.
     * Avoids writing a staging tarball to disk. Requires [cacheDir] to be set first.
     */
    suspend fun backupStreaming(
        apps: List<AppInfo>,
        noDataBackup: Set<String>,
        legacyApps: Map<String, SnapshotAppInfo>?,
        userId: String = "0",
        repoPath: String,
        password: String,
        tags: List<String>,
        hostname: String?,
        backend: String,
        backendUrl: String,
        backendUser: String,
        backendPass: String,
        backendShare: String,
        onProgress: suspend (String) -> Unit = {}
    ): AppResult<BackupSummary> = ResticStreamBackup.backup(
        cacheDir = File(cacheDir),
        apps = apps,
        noDataBackup = noDataBackup,
        legacyApps = legacyApps,
        userId = userId,
        restic = this,
        repoPath = repoPath,
        password = password,
        tags = tags,
        hostname = hostname,
        backend = backend,
        backendUrl = backendUrl,
        backendUser = backendUser,
        backendPass = backendPass,
        backendShare = backendShare,
        onProgress = onProgress
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
        onProgress: suspend (String) -> Unit = {}
    ): AppResult<Unit> = restoreOp.restore(
        repoPath, password, snapshotId, targetPath, include,
        backend, backendUrl, backendUser, backendPass, backendShare,
        onProgress
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
    ): AppResult<String> = restoreOp.dump(
        repoPath, password, snapshotId, filePath,
        backend, backendUrl, backendUser, backendPass, backendShare
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
    ): AppResult<List<ResticSnapshot>> = snapshotOps.listSnapshots(
        repoPath, password, tag,
        backend, backendUrl, backendUser, backendPass, backendShare
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
    ): AppResult<String> = snapshotOps.forget(
        repoPath, password, keepDaily, keepWeekly, keepMonthly, dryRun,
        backend, backendUrl, backendUser, backendPass, backendShare
    )

    /**
     * Read [app_details.json] from the latest restic snapshot and return a map
     * of package-name → [SnapshotAppInfo]. Returns `null` when no snapshots
     * exist or the file cannot be read (e.g. first backup, legacy format).
     */
    suspend fun getLatestSnapshotAppDetails(
        repoPath: String,
        password: String,
        backend: String = "local",
        backendUrl: String = "",
        backendUser: String = "",
        backendPass: String = "",
        backendShare: String = "",
    ): Map<String, SnapshotAppInfo>? = withContext(Dispatchers.IO) {
        val snapsResult = snapshotOps.listSnapshots(
            repoPath, password, tag = null,
            backend, backendUrl, backendUser, backendPass, backendShare
        )
        val snaps = when (snapsResult) {
            is AppResult.Failure -> {
                Log.w(TAG, "getLatestSnapshotAppDetails: listSnapshots failed: ${snapsResult.error.message}")
                null
            }
            is AppResult.Success -> snapsResult.data
        } ?: return@withContext null

        if (snaps.isEmpty()) return@withContext null

        val latestId = snaps.first().shortId
        val basePath = snaps.first().paths.firstOrNull()?.trimEnd('/') ?: return@withContext null

        val dumpResult = restoreOp.dump(
            repoPath, password, latestId, "$basePath/app_details.json",
            backend, backendUrl, backendUser, backendPass, backendShare
        )

        val jsonStr = when (dumpResult) {
            is AppResult.Failure -> return@withContext null
            is AppResult.Success -> dumpResult.data
        }

        return@withContext parseAppDetailsJson(jsonStr)
    }

    /** Parse [app_details.json] content into a package-name → [SnapshotAppInfo] map. */
    internal fun parseAppDetailsJson(jsonStr: String): Map<String, SnapshotAppInfo> {
        val map = mutableMapOf<String, SnapshotAppInfo>()
        try {
            val root = JSONObject(jsonStr)
            for (key in root.keys()) {
                val entry = root.optJSONObject(key) ?: continue
                val sizes = mutableListOf<Long>()
                val sizesArr = entry.optJSONArray("apkSizes")
                if (sizesArr != null) {
                    for (i in 0 until sizesArr.length()) {
                        sizes.add(sizesArr.optLong(i, 0L))
                    }
                }
                map[key] = SnapshotAppInfo(
                    label = entry.optString("label", key),
                    isSystem = entry.optBoolean("isSystem", false),
                    apkSizes = sizes
                )
            }
        } catch (_: Exception) {
            Log.w(TAG, "parseAppDetailsJson: failed to parse JSON")
        }
        return map
    }

    // ── Maintenance ────────────────────────────────────

    suspend fun prune(
        repoPath: String,
        password: String,
        backend: String = "local",
        backendUrl: String = "",
        backendUser: String = "",
        backendPass: String = "",
        backendShare: String = "",
    ): AppResult<String> = maintenance.prune(
        repoPath, password,
        backend, backendUrl, backendUser, backendPass, backendShare
    )

    suspend fun check(
        repoPath: String,
        password: String,
        backend: String = "local",
        backendUrl: String = "",
        backendUser: String = "",
        backendPass: String = "",
        backendShare: String = "",
    ): AppResult<String> = maintenance.check(
        repoPath, password,
        backend, backendUrl, backendUser, backendPass, backendShare
    )

    suspend fun stats(
        repoPath: String,
        password: String,
        backend: String = "local",
        backendUrl: String = "",
        backendUser: String = "",
        backendPass: String = "",
        backendShare: String = "",
    ): AppResult<String> = maintenance.stats(
        repoPath, password,
        backend, backendUrl, backendUser, backendPass, backendShare
    )

    suspend fun unlock(
        repoPath: String,
        password: String,
        backend: String = "local",
        backendUrl: String = "",
        backendUser: String = "",
        backendPass: String = "",
        backendShare: String = "",
    ): AppResult<String> =
        maintenance.unlock(
            repoPath, password,
            backend, backendUrl, backendUser, backendPass, backendShare,
        )

    // ── Public URL helper ──────────────────────────────

    /** Build a display-friendly repository URL for UI. */
    fun buildRepoUrl(backend: String, repoPath: String, backendUrl: String): String {
        return repoInit.buildRepoUrl(backend, repoPath, backendUrl)
    }
}
