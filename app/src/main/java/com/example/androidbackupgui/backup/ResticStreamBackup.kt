package com.example.androidbackupgui.backup

import android.util.Log
import com.example.androidbackupgui.root.RootShell
import com.example.androidbackupgui.root.shellEscape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Streaming backup using a FIFO (named pipe) to pipe app data tar directly
 * into `restic backup --stdin`, eliminating the staging directory.
 *
 * Only invoked when [BackupConfig.useStreaming] is enabled.
 */
object ResticStreamBackup {

    private const val TAG = "ResticStreamBackup"

    /**
     * Run a streaming backup.
     *
     * @param cacheDir scratch directory for FIFO and metadata
     * @param apps apps whose data will be backed up
     * @param noDataBackup set of package names to exclude from data backup
     * @param legacyApps metadata from previous snapshot
     * @param restic wrapper providing env resolvers and command runner
     * @param repoPath restic repository path
     * @param password restic repository password
     * @param tags restic tags for the snapshot
     * @param hostname optional restic hostname
     * @param backend backend type (local / webdav / smb)
     * @param backendUrl remote backend URL
     * @param backendUser remote backend user
     * @param backendPass remote backend password
     * @param backendShare SMB share name
     * @param onProgress progress callback with the restic JSON status line text
     */
    suspend fun backup(
        cacheDir: File,
        apps: List<AppInfo>,
        noDataBackup: Set<String>,
        legacyApps: Map<String, ResticWrapper.SnapshotAppInfo>?,
        restic: ResticWrapper,
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
    ): AppResult<ResticWrapper.BackupSummary> = withContext(Dispatchers.IO) {
        // TODO: Phase 2 (FIFO + producer) + Phase 3 (consumer + restic)
        return@withContext err(AppError.Config("流式备份功能尚未实现"))
    }
}
