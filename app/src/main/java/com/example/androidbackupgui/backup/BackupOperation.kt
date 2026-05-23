package com.example.androidbackupgui.backup

import com.example.androidbackupgui.root.RootShell
import com.example.androidbackupgui.root.shellEscape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

/**
 * Performs backup of apps and WiFi config using root shell.
 * Mirrors the logic from backup_script's modules/backup.sh.
 */
object BackupOperation {

    data class BackupProgress(
        val current: Int,
        val total: Int,
        val packageName: String,
        val stage: String,        // "apk", "data", "obb", "ssaid", "done"
        val message: String
    )

    data class BackupResult(
        val successCount: Int,
        val failCount: Int,
        val skippedCount: Int,
        val outputDir: String,
        val elapsedMs: Long
    )

    /**
     * Backup a list of apps to the output directory.
     * @param apps list of AppInfo to backup
     * @param config backup configuration
     * @param outputDir root output directory
     * @param userId Android user ID (0, 999, etc.)
     * @param onProgress callback for UI updates
     */
    suspend fun backupApps(
        apps: List<AppInfo>,
        config: BackupConfig,
        outputDir: File,
        userId: String = "0",
        onProgress: suspend (BackupProgress) -> Unit = {}
    ): BackupResult = withContext(Dispatchers.IO) {
        val emit: suspend (BackupProgress) -> Unit = { p -> withContext(Dispatchers.Main) { onProgress(p) } }
        val startTime = System.currentTimeMillis()

        // Create backup structure
        val backupRoot = File(outputDir, "Backup_${config.compressionMethod}_$userId")
        backupRoot.mkdirs()

        // Write app list
        val appListFile = File(backupRoot, "appList.txt")
        appListFile.writeText(apps.joinToString("\n") { it.packageName })

        // Write metadata JSON
        val metaFile = File(backupRoot, "app_details.json")
        metaFile.writeText(buildAppDetailsJson(apps))

        var success = 0
        var fail = 0
        var skipped = 0

        for ((index, app) in apps.withIndex()) {
            if (!coroutineContext.isActive) break

            val appDir = File(backupRoot, app.packageName)
            appDir.mkdirs()

            emit(BackupProgress(index + 1, apps.size, app.packageName, "apk", "正在備份 APK…"))

            // 1. Backup APK
            val paths = AppScanner.getApkPaths(app.packageName)
            val apkOk = if (paths.isNotEmpty()) {
                paths.withIndex().all { (i, apkPath) ->
                    val destName = if (paths.size > 1) "${app.packageName}_split_$i.apk" else "${app.packageName}.apk"
                    RootShell.exec("cp '${apkPath.shellEscape()}' '${appDir.absolutePath.shellEscape()}/${destName.shellEscape()}'").isSuccess
                }
            } else false

            if (!apkOk) {
                fail++
                emit(BackupProgress(index + 1, apps.size, app.packageName, "done", "APK 備份失敗"))
                continue
            }

            // 2. Backup user data (if configured)
            if (config.backupMode == 1 && config.backupUserData == 1) {
                emit(BackupProgress(index + 1, apps.size, app.packageName, "data", "正在備份數據…"))
                backupUserData(app.packageName, appDir, userId, config.compressionMethod)
            }

            // 3. Backup OBB (if configured and exists)
            if (config.backupMode == 1 && config.backupObbData == 1) {
                val hasObb = AppScanner.hasObbData(app.packageName)
                if (hasObb) {
                    emit(BackupProgress(index + 1, apps.size, app.packageName, "obb", "正在備份 OBB…"))
                    backupObb(app.packageName, appDir, config.compressionMethod)
                }
            }

            // 4. Backup SSAID
            emit(BackupProgress(index + 1, apps.size, app.packageName, "ssaid", "正在備份 SSAID…"))
            backupSsaid(app.packageName, appDir, userId)

            // 5. Backup runtime permissions
            backupPermissions(app.packageName, appDir)

            success++
            emit(BackupProgress(index + 1, apps.size, app.packageName, "done", "完成"))
        }

        val elapsed = System.currentTimeMillis() - startTime
        RootShell.exec("chmod -R 0755 '${backupRoot.absolutePath}'")

        BackupResult(
            successCount = success,
            failCount = fail,
            skippedCount = skipped,
            outputDir = backupRoot.absolutePath,
            elapsedMs = elapsed
        )
    }


    private fun backupUserData(
        packageName: String,
        appDir: File,
        userId: String,
        compression: String
    ) {
        val pkgEsc = packageName.shellEscape()
        val dataDir = "/data/data/$pkgEsc"
        val userDeDir = "/data/user_de/${userId.shellEscape()}/$pkgEsc"
        val outputFile = "${appDir.absolutePath.shellEscape()}/${pkgEsc}_data.tar"

        // Build a list of dirs that exist
        val dirs = mutableListOf<String>()
        if (RootShell.exec("test -d $dataDir").isSuccess) dirs.add(dataDir)
        if (RootShell.exec("test -d $userDeDir").isSuccess) dirs.add(userDeDir)

        if (dirs.isEmpty()) return

        // Exclude cache, code_cache, lib
        val excludeArgs = "--exclude='cache' --exclude='code_cache' --exclude='lib' --exclude='no_backup'"

        when (compression) {
            "zstd" -> {
                val dirList = dirs.joinToString(" ")
                RootShell.exec(
                    "tar $excludeArgs -cf - $dirList 2>/dev/null | zstd -T0 -o '$outputFile.zst'"
                )
            }
            else -> {
                val dirList = dirs.joinToString(" ")
                RootShell.exec(
                    "tar $excludeArgs -czf '$outputFile.gz' $dirList 2>/dev/null"
                )
            }
        }
    }

    private fun backupObb(packageName: String, appDir: File, compression: String) {
        val obbDir = "/storage/emulated/0/Android/obb/${packageName.shellEscape()}"
        val escapedAppDir = appDir.absolutePath.shellEscape()
        val escapedPkg = packageName.shellEscape()

        when (compression) {
            "zstd" -> RootShell.exec("tar -cf - '$obbDir' 2>/dev/null | zstd -T0 -o '$escapedAppDir/${escapedPkg}_obb.tar.zst'")
            else -> RootShell.exec("tar -czf '$escapedAppDir/${escapedPkg}_obb.tar.gz' '$obbDir' 2>/dev/null")
        }
    }

    private fun backupSsaid(packageName: String, appDir: File, userId: String) {
        val ssaidFile = "/data/system/users/${userId.shellEscape()}/settings_ssaid.xml"
        val result = RootShell.exec("grep '${packageName.shellEscape()}' '$ssaidFile' 2>/dev/null")
        if (result.output.isNotBlank()) {
            File(appDir, "ssaid.txt").writeText(result.output)
        }
    }

    private fun backupPermissions(packageName: String, appDir: File) {
        val result = RootShell.exec("dumpsys package '${packageName.shellEscape()}' | grep -E 'granted=(true|false)'")
        if (result.output.isNotBlank()) {
            File(appDir, "permissions.txt").writeText(result.output)
        }
    }

    private fun buildAppDetailsJson(apps: List<AppInfo>): String {
        val root = JSONObject()
        for (app in apps) {
            val entry = JSONObject()
            entry.put("label", app.label)
            entry.put("isSystem", app.isSystem)
            root.put(app.packageName, entry)
        }
        return root.toString(2)
    }
}
