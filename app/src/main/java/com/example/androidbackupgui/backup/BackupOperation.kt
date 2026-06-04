package com.example.androidbackupgui.backup

import com.example.androidbackupgui.root.RootShell
import android.util.Log
import com.example.androidbackupgui.root.shellEscape
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import org.json.JSONObject
import kotlinx.serialization.Serializable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
        context: android.content.Context,
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
        LogUtil.i(TAG, "backupApps: starting backup of ${apps.size} apps to ${backupRoot.absolutePath}")

        // Write app list
        val appListFile = File(backupRoot, "appList.txt")
        appListFile.writeText(apps.joinToString("\n") { it.packageName.value })

        // Write metadata JSON
        val metaFile = File(backupRoot, "app_details.json")
        metaFile.writeText(buildAppDetailsJson(apps))

        val semaphore = Semaphore(3)
        val successAtomic = AtomicInteger(0)
        val failAtomic = AtomicInteger(0)
        val skippedAtomic = AtomicInteger(0)

        coroutineScope {
            apps.mapIndexed { index, app ->
                async {
                    semaphore.withPermit {
                        ensureActive()
                        val appDir = File(backupRoot, app.packageName.value)
                        appDir.mkdirs()

                        emit(BackupProgress(index + 1, apps.size, app.packageName.value, "apk", "正在备份 APK…"))

                        // 1. Backup APK
                        val paths = AppScanner.getApkPaths(app.packageName.value)
                        val apkOk = if (paths.isNotEmpty()) {
                            paths.withIndex().all { (i, apkPath) ->
                                val destName = if (paths.size > 1) "${app.packageName}_split_$i.apk" else "${app.packageName}.apk"
                                RootShell.exec("cp '${apkPath.shellEscape()}' '${appDir.absolutePath.shellEscape()}/${destName.shellEscape()}'").isSuccess
                            }
                        } else false

                        if (!apkOk) {
                            failAtomic.incrementAndGet()
                            emit(BackupProgress(index + 1, apps.size, app.packageName.value, "done", "APK 备份失败"))
                            return@withPermit
                        }

                        // 1.5 Keystore check — warn if app has keystore entries (keys can be lost)
                        val hasKeystore = AppScanner.hasKeystore(app.packageName.value)
                        if (hasKeystore) {
                            emit(BackupProgress(index + 1, apps.size, app.packageName.value, "data", "⚠ 此应用包含密钥库条目，备份后密钥可能会丢失"))
                        }

                        // 2. Backup user data (if configured)
                        if (config.backupMode == 1 && config.backupUserData == 1) {
                            emit(BackupProgress(index + 1, apps.size, app.packageName.value, "data", "正在备份数据…"))
                            if (!backupUserData(context, app.packageName.value, appDir, userId, config.compressionMethod)) {
                                failAtomic.incrementAndGet()
                                emit(BackupProgress(index + 1, apps.size, app.packageName.value, "done", "数据备份失败"))
                                return@withPermit
                            }
                        }

                        // 3. Backup OBB (if configured and exists)
                        if (config.backupMode == 1 && config.backupObbData == 1) {
                            val hasObb = AppScanner.hasObbData(app.packageName.value)
                            if (hasObb) {
                                emit(BackupProgress(index + 1, apps.size, app.packageName.value, "obb", "正在备份 OBB…"))
                                if (!backupObb(app.packageName.value, appDir, config.compressionMethod)) {
                                    failAtomic.incrementAndGet()
                                    emit(BackupProgress(index + 1, apps.size, app.packageName.value, "done", "OBB 备份失败"))
                                    return@withPermit
                                }
                            }
                        }

                        // 4. Backup SSAID
                        emit(BackupProgress(index + 1, apps.size, app.packageName.value, "ssaid", "正在备份 SSAID…"))
                        backupSsaid(app.packageName.value, appDir, userId)

                        // 4.5 Backup app icon
                        val iconPath = AppScanner.extractIcon(app.packageName.value, appDir, app.userId.value)
                        if (iconPath != null) {
                            Log.d(TAG, "backupApps: saved icon for ${app.packageName} -> $iconPath")
                        }

                        // 5. Backup runtime permissions
                        backupPermissions(app.packageName.value, appDir)

                        successAtomic.incrementAndGet()
                        emit(BackupProgress(index + 1, apps.size, app.packageName.value, "done", "完成"))
                    }
                }
            }.awaitAll()
        }

        val elapsed = System.currentTimeMillis() - startTime
        RootShell.exec("chmod -R 0755 '${backupRoot.absolutePath}'")

        val successCount = successAtomic.get()
        val failCount = failAtomic.get()
        val skippedCount = skippedAtomic.get()

        LogUtil.i(TAG, "backupApps: completed — success=$successCount fail=$failCount skipped=$skippedCount elapsed=${elapsed}ms")

        BackupResult(
            successCount = successCount,
            failCount = failCount,
            skippedCount = skippedCount,
            outputDir = backupRoot.absolutePath,
            elapsedMs = elapsed
        )
    }


    private suspend fun backupUserData(
        context: android.content.Context,
        packageName: String,
        appDir: File,
        userId: String,
        compression: String
    ): Boolean {
        val pkgEsc = packageName.shellEscape()
        val outputFile = "${appDir.absolutePath.shellEscape()}/${pkgEsc}_data.tar"

        // Resolve bundled binary paths (fall back to system PATH if not bundled)
        val bundledTar = BinaryResolver.tarPath(context)
        val tarCmd = bundledTar ?: "tar"

        var isZstd = compression == "zstd"
        val bundledZstd = if (isZstd) BinaryResolver.zstdPath(context) else null
        val zstdCmd = bundledZstd ?: "zstd"
        if (isZstd && bundledZstd == null) {
            val zstdCheck = RootShell.exec("$zstdCmd --version 2>/dev/null")
            if (!zstdCheck.isSuccess) {
                Log.w(TAG, "backupUserData: zstd not available, falling back to gzip")
                isZstd = false
            }
        }
        val archiveExt = if (isZstd) ".zst" else ".gz"
        val archiveRaw = File(appDir, "${packageName}_data.tar$archiveExt")

        Log.d(TAG, "backupUserData: $packageName checking dirs (tar=$tarCmd zstd=$zstdCmd)")

        val rawPkg = packageName
        val dataPaths = listOf("/data/data/$rawPkg", "/data/user_de/$userId/$rawPkg")
        val dataExcludes = listOf(".ota", "cache", "lib", "code_cache", "no_backup")

        // 1. Try direct paths after nsenter namespace switch
        var archiveCreated = false
        var result: RootShell.ShellResult? = null

        val dirs = dataPaths.filter { RootShell.exec("test -d '${it.shellEscape()}'").isSuccess }.toMutableList()
        if (dirs.isNotEmpty()) {
            Log.d(TAG, "backupUserData: $packageName test -d found dirs=$dirs")
            result = runTar(dirs, outputFile, isZstd, tarCmd, zstdCmd, excludes = dataExcludes)
            archiveCreated = archiveCreated || (archiveRaw.exists() && archiveRaw.length() > 0)
            Log.d(TAG, "backupUserData: $packageName step1 exit=${result?.exitCode} err='${result?.error?.take(100)}'")
        } else {
            Log.d(TAG, "backupUserData: $packageName test -d all failed, trying tar directly")
            result = runTar(dataPaths, outputFile, isZstd, tarCmd, zstdCmd, excludes = dataExcludes)
            archiveCreated = archiveCreated || (archiveRaw.exists() && archiveRaw.length() > 0)
            Log.d(TAG, "backupUserData: $packageName step2 exit=${result?.exitCode} err='${result?.error?.take(100)}'")
        }

        // 3. Fallback via /proc/1/root (global mount namespace)
        if (!archiveCreated) {
            Log.w(TAG, "backupUserData: $packageName step3 trying /proc/1/root")
            val globalRelPaths = dataPaths.map { it.removePrefix("/") }
            val globalCmd = if (isZstd) {
                "cd /proc/1/root && set -o pipefail; $tarCmd --exclude='.ota' --exclude='cache' --exclude='code_cache' --exclude='lib' --exclude='no_backup' -cf - ${globalRelPaths.joinToString(" ") { "'${it.shellEscape()}'" }} 2>/dev/null | $zstdCmd -T0 -o '$outputFile.zst'"
            } else {
                "cd /proc/1/root && $tarCmd --exclude='.ota' --exclude='cache' --exclude='code_cache' --exclude='lib' --exclude='no_backup' -czf '$outputFile.gz' ${globalRelPaths.joinToString(" ") { "'${it.shellEscape()}'" }} 2>/dev/null"
            }
            result = RootShell.exec(globalCmd)
            archiveCreated = archiveCreated || (archiveRaw.exists() && archiveRaw.length() > 0)
            Log.d(TAG, "backupUserData: $packageName step3 exit=${result?.exitCode} err='${result?.error?.take(100)}'")
        }

        if (!archiveCreated) {
            Log.w(TAG, "backupUserData: $packageName all methods failed — no data dirs (or inaccessible)")
            return true
        }

        // Verify compression integrity
        val verifyOk = if (isZstd) {
            RootShell.exec("$zstdCmd -t '$outputFile.zst' 2>/dev/null").isSuccess
        } else {
            RootShell.exec("gzip -t '$outputFile.gz' 2>/dev/null").isSuccess
        }
        if (!verifyOk) {
            Log.e(TAG, "backupUserData: $packageName integrity check FAILED")
            return false
        }

        // Validate tar archive structure (Android-DataBackup Tar.test() pattern)
        val tarValidateOk = if (isZstd) {
            RootShell.exec("$zstdCmd -d -c '$outputFile.zst' 2>/dev/null | tar -tf - > /dev/null 2>&1").isSuccess
        } else {
            RootShell.exec("tar -tf '$outputFile.gz' > /dev/null 2>&1").isSuccess
        }
        if (!tarValidateOk) {
            Log.e(TAG, "backupUserData: $packageName tar archive structure validation FAILED")
            return false
        }
        return true
    }

    /** Run tar for given paths, building the appropriate zstd/gzip command. */
    private suspend fun runTar(
        dirs: List<String>,
        outputFile: String,
        isZstd: Boolean,
        tarCmd: String = "tar",
        zstdCmd: String = "zstd",
        excludes: List<String> = emptyList()
    ): RootShell.ShellResult {
        val excludeArgs = if (excludes.isNotEmpty()) {
            excludes.joinToString(" ") { "--exclude='${it.shellEscape()}'" }
        } else ""
        return if (isZstd) {
            RootShell.exec("set -o pipefail; $tarCmd -cf - $excludeArgs ${dirs.joinToString(" ") { "'${it.shellEscape()}'" }} 2>/dev/null | $zstdCmd -T0 -o '$outputFile.zst'")
        } else {
            RootShell.exec("$tarCmd -czf $excludeArgs '$outputFile.gz' ${dirs.joinToString(" ") { "'${it.shellEscape()}'" }} 2>/dev/null")
        }
    }
    private suspend fun backupObb(packageName: String, appDir: File, compression: String): Boolean {
        val obbDir = "/storage/emulated/0/Android/obb/${packageName.shellEscape()}"
        val escapedAppDir = appDir.absolutePath.shellEscape()
        val escapedPkg = packageName.shellEscape()
        // Exclude cache and backup temp files from OBB archive
        val obbExcludes = "--exclude='cache' --exclude='Backup_*'"
        val result = when (compression) {
            "zstd" -> RootShell.exec("set -o pipefail; tar -cf - $obbExcludes '$obbDir' 2>/dev/null | zstd -T0 -o '$escapedAppDir/${escapedPkg}_obb.tar.zst'")
            else -> RootShell.exec("tar -czf $obbExcludes '$escapedAppDir/${escapedPkg}_obb.tar.gz' '$obbDir' 2>/dev/null")
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
        // Validate OBB tar structure
        val tarListCmd = if (compression == "zstd") "zstd -d -c '$archive' 2>/dev/null | tar -tf - > /dev/null 2>&1" else "tar -tf '$archive' > /dev/null 2>&1"
        val tarOk = RootShell.exec(tarListCmd).isSuccess
        if (!tarOk) {
            Log.e(TAG, "OBB tar structure validation FAILED for $packageName")
        }
        return verificationOk && tarOk
    }

    private suspend fun backupSsaid(packageName: String, appDir: File, userId: String) {
        val ssaidFile = "/data/system/users/${userId.shellEscape()}/settings_ssaid.xml"
        // Parse XML value attribute for this package's SSAID entry
        val result = RootShell.exec("cat '$ssaidFile' 2>/dev/null")
        if (!result.isSuccess || result.output.isBlank()) return
        val ssaidLine = result.output.lines().firstOrNull { line ->
            line.contains("packageName=\"$packageName\"") || line.contains("packageName='$packageName'")
        }
        val value = ssaidLine
            ?.substringAfter("value=\"")
            ?.substringBefore("\"")
            ?.takeIf { it.isNotBlank() }
        if (value != null) {
            File(appDir, "ssaid.txt").writeText(value)
            Log.d(TAG, "backupSsaid: backed up SSAID for $packageName = $value")
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
            root.put(app.packageName.value, entry)
        }
        return root.toString(2)
    }
}
