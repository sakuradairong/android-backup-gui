package com.example.androidbackupgui.backup

import com.example.androidbackupgui.root.RootShell
import android.util.Log
import com.example.androidbackupgui.root.shellEscape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import org.json.JSONObject
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.Serializable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Performs backup of apps and WiFi config using root shell.
 * Mirrors the logic from backup_script's modules/backup.sh.
 */
object BackupOperation {

    private const val TAG = "BackupOperation"

    @Serializable
    data class BackupProgress(
        val current: Int,
        val total: Int,
        val packageName: String,
        val stage: String,        // "apk", "data", "obb", "ssaid", "done"
        val message: String
    )

    @Serializable
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

        val semaphore = Semaphore(3)
        val successAtomic = AtomicInteger(0)
        val failAtomic = AtomicInteger(0)
        val skippedAtomic = AtomicInteger(0)

        coroutineScope {
            apps.forEachIndexed { index, app ->
                launch {
                    if (!coroutineContext.isActive) return@launch
                    semaphore.withPermit {
                        val appDir = File(backupRoot, app.packageName)
                        appDir.mkdirs()

                        emit(BackupProgress(index + 1, apps.size, app.packageName, "apk", "正在备份 APK…"))

                        // 1. Backup APK
                        val paths = AppScanner.getApkPaths(app.packageName)
                        val apkOk = if (paths.isNotEmpty()) {
                            paths.withIndex().all { (i, apkPath) ->
                                val destName = if (paths.size > 1) "${app.packageName}_split_$i.apk" else "${app.packageName}.apk"
                                RootShell.exec("cp '${apkPath.shellEscape()}' '${appDir.absolutePath.shellEscape()}/${destName.shellEscape()}'").isSuccess
                            }
                        } else false

                        if (!apkOk) {
                            failAtomic.incrementAndGet()
                            emit(BackupProgress(index + 1, apps.size, app.packageName, "done", "APK 备份失败"))
                            return@withPermit
                        }

                        // 2. Backup user data (if configured)
                        if (config.backupMode == 1 && config.backupUserData == 1) {
                            emit(BackupProgress(index + 1, apps.size, app.packageName, "data", "正在备份数据…"))
                            if (!backupUserData(app.packageName, appDir, userId, config.compressionMethod)) {
                                failAtomic.incrementAndGet()
                                emit(BackupProgress(index + 1, apps.size, app.packageName, "done", "数据备份失败"))
                                return@withPermit
                            }
                        }

                        // 3. Backup OBB (if configured and exists)
                        if (config.backupMode == 1 && config.backupObbData == 1) {
                            val hasObb = AppScanner.hasObbData(app.packageName)
                            if (hasObb) {
                                emit(BackupProgress(index + 1, apps.size, app.packageName, "obb", "正在备份 OBB…"))
                                if (!backupObb(app.packageName, appDir, config.compressionMethod)) {
                                    failAtomic.incrementAndGet()
                                    emit(BackupProgress(index + 1, apps.size, app.packageName, "done", "OBB 备份失败"))
                                    return@withPermit
                                }
                            }
                        }

                        // 4. Backup SSAID
                        emit(BackupProgress(index + 1, apps.size, app.packageName, "ssaid", "正在备份 SSAID…"))
                        backupSsaid(app.packageName, appDir, userId)

                        // 5. Backup runtime permissions
                        backupPermissions(app.packageName, appDir)

                        successAtomic.incrementAndGet()
                        emit(BackupProgress(index + 1, apps.size, app.packageName, "done", "完成"))
                    }
                }
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        RootShell.exec("chmod -R 0755 '${backupRoot.absolutePath}'")

        BackupResult(
            successCount = successAtomic.get(),
            failCount = failAtomic.get(),
            skippedCount = skippedAtomic.get(),
            outputDir = backupRoot.absolutePath,
            elapsedMs = elapsed
        )
    }


    private suspend fun backupUserData(
        packageName: String,
        appDir: File,
        userId: String,
        compression: String
    ): Boolean {
        val pkgEsc = packageName.shellEscape()
        val dataDir = "/data/data/$pkgEsc"
        val userDeDir = "/data/user_de/${userId.shellEscape()}/$pkgEsc"
        val outputFile = "${appDir.absolutePath.shellEscape()}/${pkgEsc}_data.tar"
        Log.d(TAG, "backupUserData: $packageName checking dirs")
        val dirs = mutableListOf<String>()
        val dataOk = RootShell.exec("test -d $dataDir")
        val userDeOk = RootShell.exec("test -d $userDeDir")
        Log.d(TAG, "backupUserData: $packageName test -d dataDir exit=${dataOk.exitCode} userDe exit=${userDeOk.exitCode}")
        if (dataOk.isSuccess) dirs.add(dataDir)
        if (userDeOk.isSuccess) dirs.add(userDeDir)
        Log.d(TAG, "backupUserData: $packageName dirs=$dirs")
        if (dirs.isEmpty()) {
            Log.w(TAG, "backupUserData: $packageName no data dirs found, skipping")
            return true
        }
        // Exclude cache, code_cache, lib
        val excludeArgs = "--exclude='cache' --exclude='code_cache' --exclude='lib' --exclude='no_backup'"
        val result = when (compression) {
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
        if (!result.isSuccess) {
            Log.e(TAG, "Failed to backup data for $packageName: exit=${result.exitCode} err=${result.error}")
            return false
        }
        // Verify the compressed archive integrity
        val verificationOk = when (compression) {
            "zstd" -> RootShell.exec("zstd -t '$outputFile.zst' 2>/dev/null").isSuccess
            else -> RootShell.exec("gzip -t '$outputFile.gz' 2>/dev/null").isSuccess
        }
        if (!verificationOk) {
            Log.e(TAG, "Data archive integrity check FAILED for $packageName")
        }
        return verificationOk
    }

    private suspend fun backupObb(packageName: String, appDir: File, compression: String): Boolean {
        val obbDir = "/storage/emulated/0/Android/obb/${packageName.shellEscape()}"
        val escapedAppDir = appDir.absolutePath.shellEscape()
        val escapedPkg = packageName.shellEscape()
        val result = when (compression) {
            "zstd" -> RootShell.exec("tar -cf - '$obbDir' 2>/dev/null | zstd -T0 -o '$escapedAppDir/${escapedPkg}_obb.tar.zst'")
            else -> RootShell.exec("tar -czf '$escapedAppDir/${escapedPkg}_obb.tar.gz' '$obbDir' 2>/dev/null")
        }
        if (!result.isSuccess) {
            Log.e(TAG, "Failed to backup OBB for $packageName: exit=${result.exitCode} err=${result.error}")
            return false
        }
        val archive = if (compression == "zstd") "$escapedAppDir/${escapedPkg}_obb.tar.zst" else "$escapedAppDir/${escapedPkg}_obb.tar.gz"
        val verifyCmd = if (compression == "zstd") "zstd -t '$archive' 2>/dev/null" else "gzip -t '$archive' 2>/dev/null"
        val verificationOk = RootShell.exec(verifyCmd).isSuccess
        if (!verificationOk) {
            Log.e(TAG, "OBB archive integrity check FAILED for $packageName")
        }
        return verificationOk
    }

    private suspend fun backupSsaid(packageName: String, appDir: File, userId: String) {
        val ssaidFile = "/data/system/users/${userId.shellEscape()}/settings_ssaid.xml"
        val result = RootShell.exec("grep '${packageName.shellEscape()}' '$ssaidFile' 2>/dev/null")
        if (result.output.isNotBlank()) {
            File(appDir, "ssaid.txt").writeText(result.output)
        }
    }

    private suspend fun backupPermissions(packageName: String, appDir: File) {
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
