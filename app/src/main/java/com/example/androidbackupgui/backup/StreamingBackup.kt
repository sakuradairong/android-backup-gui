package com.example.androidbackupgui.backup

import android.util.Log
import com.example.androidbackupgui.root.RootShell
import com.example.androidbackupgui.root.shellEscape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Streaming backup orchestrator.
 *
 * Uses a FIFO (named pipe) to pipe app data tar output directly into
 * `restic backup --stdin`, eliminating the staging directory for large
 * data backups.
 */
object StreamingBackup {

    private const val TAG = "StreamingBackup"

    data class StreamingResult(
        val apkPaths: List<String>,     // APK paths (backed up directly by restic)
        val dataFifo: File,             // FIFO path for app data tar
        val metaDir: File               // Metadata directory (~1MB)
    )

    /**
     * Prepare streaming backup configuration.
     *
     * Creates the FIFO and metadata directory, collects APK paths.
     *
     * @param cacheDir Directory to place FIFO and temp files
     * @param apps List of apps being backed up
     * @param legacyApps Metadata from previous snapshot
     */
    suspend fun prepareStreaming(
        cacheDir: File,
        apps: List<AppInfo>,
        legacyApps: Map<String, ResticWrapper.SnapshotAppInfo>?
    ): StreamingResult = withContext(Dispatchers.IO) {
        cacheDir.mkdirs()

        // Create FIFO for data pipe
        val fifo = File(cacheDir, "app_data_stream.fifo")
        // Remove stale FIFO if present
        if (fifo.exists()) fifo.delete()
        // mkfifo requires root on Android
        RootShell.exec("mkfifo '${fifo.absolutePath.shellEscape()}'")
        Log.i(TAG, "FIFO created at ${fifo.absolutePath}")

        // Collect APK paths
        val apkPaths = mutableListOf<String>()
        for (app in apps) {
            val paths = AppScanner.getApkPaths(app.packageName.value)
            apkPaths.addAll(paths)
        }

        // Create metadata directory
        val metaDir = File(cacheDir, "streaming_meta")
        metaDir.mkdirs()

        // Write app list
        val appListFile = File(metaDir, "appList.txt")
        appListFile.writeText(apps.joinToString("\n") { it.packageName.value })

        // Write app_details.json
        val metaFile = File(metaDir, "app_details.json")
        metaFile.writeText(BackupOperation.buildAppDetailsJson(apps, legacyApps))

        Log.i(TAG, "Streaming prepared: ${apkPaths.size} APKs, FIFO at ${fifo.absolutePath}")
        StreamingResult(apkPaths, fifo, metaDir)
    }

    /**
     * Launch the data producer in a root shell background process.
     *
     * For each app, runs `tar -cf - /data/data/pkg 2>/dev/null` and appends
     * to the FIFO. The FIFO is consumed by `restic backup --stdin`.
     *
     * @param apps Apps whose data directories to tar
     * @param noDataBackup Set of package names to exclude from data backup
     * @param userId Android user ID
     * @param fifoPath Path to the FIFO
     */
    suspend fun launchDataProducer(
        apps: List<AppInfo>,
        noDataBackup: Set<String>,
        @Suppress("UNUSED_PARAMETER") userId: String,
        fifoPath: String
    ): Boolean = withContext(Dispatchers.IO) {
        val fifoEsc = fifoPath.shellEscape()

        for (app in apps) {
            if (!coroutineContext.isActive) return@withContext false

            val pkgName = app.packageName.value
            if (pkgName in noDataBackup) {
                Log.d(TAG, "Skipping data for $pkgName (excluded)")
                continue
            }

            val dataDir = "/data/data/$pkgName"
            // Check if data directory exists
            val existsResult = RootShell.exec("[ -d '${dataDir.shellEscape()}' ] && echo 1 || echo 0")
            if (existsResult.output.trim() != "1") {
                Log.d(TAG, "No data directory for $pkgName, skipping")
                continue
            }

            // Append tar output to FIFO. `>>` blocks until consumer reads.
            val cmd = "tar -cf - '$dataDir' 2>/dev/null >> '$fifoEsc'"
            Log.d(TAG, "Streaming data for $pkgName: $cmd")
            val result = RootShell.exec(cmd)
            if (!result.isSuccess) {
                Log.w(TAG, "Data backup failed for $pkgName: ${result.error}")
            }
        }

        Log.i(TAG, "Data producer completed")
        true
    }
}
