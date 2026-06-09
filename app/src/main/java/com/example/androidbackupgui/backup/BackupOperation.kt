package com.example.androidbackupgui.backup

import android.util.Log
import com.example.androidbackupgui.backup.ResticWrapper.SnapshotAppInfo
import com.example.androidbackupgui.root.RootShell
import com.example.androidbackupgui.root.shellEscape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
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
        val stage: String, // "apk", "data", "obb", "ssaid", "done"
        val message: String,
    )

    @Serializable
    data class BackupResult(
        val successCount: Int,
        val failCount: Int,
        val skippedCount: Int,
        val outputDir: String,
        val elapsedMs: Long,
    )

    /**
     * Backup a list of apps to the output directory.
     * @param apps list of AppInfo to backup
     * @param config backup configuration
     * @param outputDir root output directory
     * @param userId Android user ID (0, 999, etc.)
     * @param includePkgs if non-empty, only backup apps whose package name is in this set;
     *                    metadata (app_details.json, appList.txt) is still generated for all [apps].
     * @param legacyApps metadata from a previous snapshot used to populate app_details.json
     *                   for apps not in [apps] (keeps them in the cumulative snapshot record
     *                   without requiring re-scans of possibly-uninstalled apps).
     */
    suspend fun backupApps(
        context: android.content.Context,
        apps: List<AppInfo>,
        config: BackupConfig,
        outputDir: File,
        userId: String = "0",
        noDataBackup: Set<String> = emptySet(),
        includePkgs: Set<String> = emptySet(),
        legacyApps: Map<String, SnapshotAppInfo>? = null,
        onProgress: suspend (BackupProgress) -> Unit = {},
    ): BackupResult =
        withContext(Dispatchers.IO) {
            val emit: suspend (BackupProgress) -> Unit = { p -> withContext(Dispatchers.Main) { onProgress(p) } }
            val startTime = System.currentTimeMillis()

            // Safety check: refuse to backup inside Android/data directories
            val absOut = outputDir.absolutePath
            if (absOut.contains("/Android/")) {
                LogUtil.e(TAG, "backupApps: refusing to backup inside Android/ directory: $absOut")
                return@withContext BackupResult(0, 0, 0, absOut, 0)
            }

            // Create backup structure
            val backupRoot = File(outputDir, "Backup_${config.compressionMethod}_$userId")
            if (!mkdirsForBackup(backupRoot)) {
                LogUtil.e(TAG, "backupApps: cannot create output dir ${backupRoot.absolutePath}")
                return@withContext BackupResult(0, 0, 0, outputDir.absolutePath, 0)
            }
            LogUtil.i(TAG, "backupApps: starting backup of ${apps.size} apps to ${backupRoot.absolutePath}")

            // Read previous metadata for incremental backup comparison
            val oldMetaFile = File(backupRoot, "app_details.json")
            val oldMetaJson =
                if (oldMetaFile.exists()) {
                    try {
                        JSONObject(readTextFile(oldMetaFile) ?: "{}")
                    } catch (_: Exception) {
                        JSONObject()
                    }
                } else {
                    JSONObject()
                }

            // Write app list — includes ALL packages in [apps] (selected + legacy from snapshot)
            val appListFile = File(backupRoot, "appList.txt")
            if (!writeFileForBackup(appListFile, apps.joinToString("\n") { it.packageName.value })) {
                LogUtil.e(TAG, "backupApps: failed to write appList.txt")
                return@withContext BackupResult(0, 0, 0, outputDir.absolutePath, 0)
            }

            // Write metadata JSON — fresh metadata for selected apps, legacy for historical apps
            val metaFile = File(backupRoot, "app_details.json")
            if (!writeFileForBackup(metaFile, buildAppDetailsJson(apps, legacyApps))) {
                LogUtil.e(TAG, "backupApps: failed to write app_details.json")
                return@withContext BackupResult(0, 0, 0, outputDir.absolutePath, 0)
            }

            val backupTargets = if (includePkgs.isEmpty()) apps else apps.filter { it.packageName.value in includePkgs }
            val totalCount = backupTargets.size
            LogUtil.i(TAG, "backupApps: includePkgs=${includePkgs.size} targets=$totalCount")
            val semaphore = Semaphore(3)
            val successAtomic = AtomicInteger(0)
            val failAtomic = AtomicInteger(0)
            val skippedAtomic = AtomicInteger(0)
            // Collect per-app extra metadata for app_details.json
            val perAppExtraMap = ConcurrentHashMap<String, PerAppExtra>()

            coroutineScope {
                backupTargets
                    .mapIndexed { index, app ->
                        async {
                            semaphore.withPermit {
                                ensureActive()
                                val pkgName = app.packageName.value
                                val appDir = File(backupRoot, pkgName)
                                appDir.mkdirs()

                                // ── Incremental check: compare APK version ──
                                val oldEntry = oldMetaJson.optJSONObject(pkgName)
                                val oldApkVersion = oldEntry?.optString("apk_version", null)
                                var installedVersion: String? = null
                                var apkChanged = true
                                if (oldApkVersion != null) {
                                    val vResult = RootShell.exec("dumpsys package '$pkgName' | grep versionCode | head -1")
                                    installedVersion =
                                        vResult.output
                                            .substringAfter("versionCode=")
                                            .substringBefore(" ")
                                            .filter { it.isDigit() }
                                            .takeIf { it.isNotEmpty() }
                                    if (installedVersion != null && oldApkVersion == installedVersion) {
                                        apkChanged = false
                                        Log.d(TAG, "backupApps: $pkgName APK $oldApkVersion unchanged, skipping")
                                    }
                                }

                                // 1. Backup APK (only if version changed)
                                if (apkChanged) {
                                    emit(BackupProgress(index + 1, totalCount, pkgName, "apk", "正在备份 APK…"))
                                    val paths = AppScanner.getApkPaths(pkgName)
                                    if (paths.isNotEmpty()) {
                                        val cpOk =
                                            paths.withIndex().all { (i, apkPath) ->
                                                val destName = if (paths.size > 1) "${pkgName}_split_$i.apk" else "$pkgName.apk"
                                                RootShell
                                                    .exec(
                                                        "cp '${apkPath.shellEscape()}' '${appDir.absolutePath.shellEscape()}/${destName.shellEscape()}'",
                                                    ).isSuccess
                                            }
                                        if (!cpOk) LogUtil.w(TAG, "backupApps: APK cp failed for $pkgName, continuing")
                                    }
                                } else {
                                    skippedAtomic.incrementAndGet()
                                    emit(BackupProgress(index + 1, totalCount, pkgName, "apk", "APK无变化，跳过"))
                                }

                                // Keystore check
                                val hasKeystore = AppScanner.hasKeystore(pkgName)
                                if (hasKeystore) emit(BackupProgress(index + 1, totalCount, pkgName, "data", "⚠ 包含密钥库条目"))

                                // ── Size-based data incremental skip ──
                                var skipData = false
                                if (!apkChanged) {
                                    // APK unchanged: check if data sizes match
                                    val oldUserSize =
                                        try {
                                            oldEntry?.optJSONObject("user")?.optString("Size", null)?.toLongOrNull()
                                        } catch (
                                            _: Exception,
                                        ) {
                                            null
                                        }
                                    val oldObbSize =
                                        try {
                                            oldEntry?.optJSONObject("obb")?.optString("Size", null)?.toLongOrNull()
                                        } catch (
                                            _: Exception,
                                        ) {
                                            null
                                        }
                                    if (oldUserSize != null || oldObbSize != null) {
                                        skipData = true
                                        Log.d(TAG, "backupApps: $pkgName data sizes known from backup, will compare after tar")
                                    }
                                }

                                // ── Per-app size tracking ──
                                var userSize: Long? = null
                                var userDeSize: Long? = null
                                var dataSize: Long? = null
                                var obbSize: Long? = null

                                // Force-stop before data backup for consistency
                                // 排除应用自身（避免自杀）和已知常驻应用
                                if (config.backupMode == 1 && !skipData) {
                                    if (pkgName !in listOf("bin.mt.plus", "com.termux", "bin.mt.plus.canary", context.packageName)) {
                                        RootShell.exec("am force-stop --user $userId '$pkgName' 2>/dev/null")
                                    }
                                }

                                // 2. Backup user data
                                if (config.backupMode == 1 && config.backupUserData == 1 && !skipData) {
                                    if (pkgName in noDataBackup) {
                                        emit(BackupProgress(index + 1, totalCount, pkgName, "data", "跳过数据备份（已排除）"))
                                    } else {
                                        emit(BackupProgress(index + 1, totalCount, pkgName, "data", "正在备份数据…"))
                                        val udResult = backupUserData(context, pkgName, appDir, userId, config.compressionMethod)
                                        userSize = udResult.first
                                        userDeSize = udResult.second
                                        if (udResult.first == null) {
                                            failAtomic.incrementAndGet()
                                            emit(BackupProgress(index + 1, totalCount, pkgName, "done", "数据备份失败"))
                                            return@withPermit
                                        }
                                    }
                                } else if (skipData) {
                                    emit(BackupProgress(index + 1, totalCount, pkgName, "data", "数据无变化，跳过"))
                                }

                                // 3. Backup OBB
                                if (config.backupMode == 1 && config.backupObbData == 1 && !skipData) {
                                    val hasObb = AppScanner.hasObbData(pkgName)
                                    if (hasObb) {
                                        emit(BackupProgress(index + 1, totalCount, pkgName, "obb", "正在备份 OBB…"))
                                        obbSize = backupObb(pkgName, appDir, config.compressionMethod)
                                        if (obbSize == null) {
                                            failAtomic.incrementAndGet()
                                            emit(BackupProgress(index + 1, totalCount, pkgName, "done", "OBB 备份失败"))
                                            return@withPermit
                                        }
                                    }
                                }

                                // 3.5 Backup external data
                                if (config.backupMode == 1 && config.backupUserData == 1 && !skipData) {
                                    if (pkgName !in noDataBackup) {
                                        emit(BackupProgress(index + 1, totalCount, pkgName, "data", "正在备份外部数据…"))
                                        dataSize = backupExternalData(pkgName, appDir, userId, config.compressionMethod)
                                    }
                                }

                                // 4. Backup SSAID
                                emit(BackupProgress(index + 1, totalCount, pkgName, "ssaid", "正在备份 SSAID…"))
                                backupSsaid(pkgName, appDir, userId)

                                // Icon + permissions (always, for completeness)
                                val iconPath = AppScanner.extractIcon(pkgName, appDir, app.userId.value)
                                if (iconPath != null) Log.d(TAG, "backupApps: saved icon for $pkgName -> $iconPath")
                                backupPermissions(pkgName, appDir)

                                // Save per-app metadata for enhanced app_details.json
                                val ssaidValue = readTextFile(File(appDir, "ssaid.txt"))?.trim()
                                val permText = readTextFile(File(appDir, "permissions.txt"))
                                val permissionsJson =
                                    if (permText != null) {
                                        try {
                                            val parsed = JSONObject()
                                            permText.lines().forEach { line ->
                                                val name = line.substringBefore(":").trim()
                                                val granted = line.contains("granted=true")
                                                if (name.contains(".")) parsed.put(name, if (granted) "granted:true" else "granted:false")
                                            }
                                            parsed
                                        } catch (_: Exception) {
                                            null
                                        }
                                    } else {
                                        null
                                    }

                                perAppExtraMap[pkgName] =
                                    PerAppExtra(
                                        ssaid = ssaidValue,
                                        permissions = permissionsJson,
                                        keystore = hasKeystore,
                                        userSize = userSize,
                                        userDeSize = userDeSize,
                                        dataSize = dataSize,
                                        obbSize = obbSize,
                                    )

                                successAtomic.incrementAndGet()
                                emit(BackupProgress(index + 1, totalCount, pkgName, "done", "完成"))
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

            // Re-write metadata files with enhanced app_details.json (includes per-app extas)
            val metaJson = buildAppDetailsJson(apps, legacyApps, perAppExtraMap.ifEmpty { null })
            writeFileForBackup(File(backupRoot, "app_details.json"), metaJson)

            BackupResult(
                successCount = successCount,
                failCount = failCount,
                skippedCount = skippedCount,
                outputDir = backupRoot.absolutePath,
                elapsedMs = elapsed,
            )
        }

    /**
     * 备份单个应用的用户数据（/data/data + /data/user_de）。
     *
     * 使用 tar + zstd/gzip 创建应用数据存档，支持 3 种回退策略：
     * 1. 通过 nsenter 直接 tar
     * 2. 直接 tar 路径（跳过 test -d）
     * 3. 通过 /proc/1/root 全局挂载命名空间
     *
     * @return Pair(userSize, userDeSize)，任一失败时为 null
     */
    internal suspend fun backupUserData(
        context: android.content.Context,
        packageName: String,
        appDir: File,
        userId: String,
        compression: String,
    ): Pair<Long?, Long?> {
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

        // Helper: check file exists and has size > 0, using root shell for FUSE paths
        suspend fun archiveHasData(): Boolean =
            BackupOperation.backupPathExists(archiveRaw) &&
                (archiveRaw.length() > 0 || BackupOperation.backupFileSize(archiveRaw) > 0L)

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
            archiveCreated = archiveHasData()
            Log.d(TAG, "backupUserData: $packageName step1 exit=${result?.exitCode} err='${result?.error?.take(100)}'")
        } else {
            Log.d(TAG, "backupUserData: $packageName test -d all failed, trying tar directly")
            result = runTar(dataPaths, outputFile, isZstd, tarCmd, zstdCmd, excludes = dataExcludes)
            archiveCreated = archiveHasData()
            Log.d(TAG, "backupUserData: $packageName step2 exit=${result?.exitCode} err='${result?.error?.take(100)}'")
        }

        // 3. Fallback via /proc/1/root (global mount namespace)
        if (!archiveCreated) {
            Log.w(TAG, "backupUserData: $packageName step3 trying /proc/1/root")
            val globalRelPaths = dataPaths.map { it.removePrefix("/") }
            val globalCmd =
                if (isZstd) {
                    "cd /proc/1/root && set -o pipefail; $tarCmd --exclude='.ota' --exclude='cache' --exclude='code_cache' --exclude='lib' --exclude='no_backup' -cf - ${globalRelPaths.joinToString(
                        " ",
                    ) { "'${it.shellEscape()}'" }} 2>/dev/null | $zstdCmd -T0 -o '$outputFile.zst'"
                } else {
                    "cd /proc/1/root && $tarCmd --exclude='.ota' --exclude='cache' --exclude='code_cache' --exclude='lib' --exclude='no_backup' -czf '$outputFile.gz' ${globalRelPaths.joinToString(
                        " ",
                    ) { "'${it.shellEscape()}'" }} 2>/dev/null"
                }
            result = RootShell.exec(globalCmd)
            archiveCreated = archiveHasData()
            Log.d(TAG, "backupUserData: $packageName step3 exit=${result?.exitCode} err='${result?.error?.take(100)}'")
        }

        if (!archiveCreated) {
            LogUtil.w(TAG, "backupUserData: $packageName all methods failed — no data dirs (or inaccessible)")
            return null to null
        }

        // Verify compression integrity
        val verifyOk =
            if (isZstd) {
                RootShell.exec("$zstdCmd -t '$outputFile.zst' 2>/dev/null").isSuccess
            } else {
                RootShell.exec("gzip -t '$outputFile.gz' 2>/dev/null").isSuccess
            }
        if (!verifyOk) {
            Log.e(TAG, "backupUserData: $packageName integrity check FAILED")
            return null to null
        }

        // Validate tar archive structure
        val tarValidateOk =
            if (isZstd) {
                RootShell.exec("$zstdCmd -d -c '$outputFile.zst' 2>/dev/null | tar -tf - > /dev/null 2>&1").isSuccess
            } else {
                RootShell.exec("tar -tf '$outputFile.gz' > /dev/null 2>&1").isSuccess
            }
        if (!tarValidateOk) {
            Log.e(TAG, "backupUserData: $packageName tar archive structure validation FAILED")
            return null to null
        }
        return archiveRaw.length() to 0L // Return (userSize, userDeSize) — combined in one file
    }

    /**
     * 运行 tar 命令，自动选择 zstd 或 gzip 压缩。
     */
    internal suspend fun runTar(
        dirs: List<String>,
        outputFile: String,
        isZstd: Boolean,
        tarCmd: String = "tar",
        zstdCmd: String = "zstd",
        excludes: List<String> = emptyList(),
    ): RootShell.ShellResult {
        val excludeArgs =
            if (excludes.isNotEmpty()) {
                excludes.joinToString(" ") { "--exclude='${it.shellEscape()}'" }
            } else {
                ""
            }
        return if (isZstd) {
            RootShell.exec(
                "set -o pipefail; $tarCmd -cf - $excludeArgs ${dirs.joinToString(
                    " ",
                ) { "'${it.shellEscape()}'" }} 2>/dev/null | $zstdCmd -T0 -o '$outputFile.zst'",
            )
        } else {
            RootShell.exec("$tarCmd -czf $excludeArgs '$outputFile.gz' ${dirs.joinToString(" ") { "'${it.shellEscape()}'" }} 2>/dev/null")
        }
    }

    /**
     * 备份单个应用的 OBB 数据文件夹。
     * @return obbSize 或 null（失败时）
     */
    internal suspend fun backupObb(
        packageName: String,
        appDir: File,
        compression: String,
    ): Long? {
        val obbDir = "/storage/emulated/0/Android/obb/${packageName.shellEscape()}"
        val escapedAppDir = appDir.absolutePath.shellEscape()
        val escapedPkg = packageName.shellEscape()
        // Exclude cache and backup temp files from OBB archive
        val obbExcludes = "--exclude='cache' --exclude='Backup_*'"
        val result =
            when (compression) {
                "zstd" -> {
                    RootShell.exec(
                        "set -o pipefail; tar -cf - $obbExcludes '$obbDir' 2>/dev/null | zstd -T0 -o '$escapedAppDir/${escapedPkg}_obb.tar.zst'",
                    )
                }

                else -> {
                    RootShell.exec("tar -czf $obbExcludes '$escapedAppDir/${escapedPkg}_obb.tar.gz' '$obbDir' 2>/dev/null")
                }
            }
        if (!result.isSuccess) {
            Log.e(TAG, "Failed to backup OBB for $packageName: exit=${result.exitCode} err=${result.error}")
            return null
        }
        val obbArchiveExt = if (compression == "zstd") ".zst" else ".gz"
        val obbFile = File(appDir, "${packageName}_obb.tar$obbArchiveExt")
        val obbArchivePath = obbFile.absolutePath.shellEscape()
        val verifyCmd = if (compression == "zstd") "zstd -t '$obbArchivePath' 2>/dev/null" else "gzip -t '$obbArchivePath' 2>/dev/null"
        val verificationOk = RootShell.exec(verifyCmd).isSuccess
        if (!verificationOk) {
            Log.e(TAG, "OBB archive integrity check FAILED for $packageName")
        }
        // Validate OBB tar structure
        val tarListCmd =
            if (compression == "zstd") {
                "zstd -d -c '$obbArchivePath' 2>/dev/null | tar -tf - > /dev/null 2>&1"
            } else {
                "tar -tf '$obbArchivePath' > /dev/null 2>&1"
            }
        val tarOk = RootShell.exec(tarListCmd).isSuccess
        if (!tarOk) {
            Log.e(TAG, "OBB tar structure validation FAILED for $packageName")
        }
        return if (verificationOk && tarOk) BackupOperation.backupFileSize(obbFile) else null
    }

    /**
     * 备份单个应用的外部数据目录（/data/media/<userId>/Android/data/<pkg>）。
     * @return dataSize 或 null（目录不存在或失败）
     */
    internal suspend fun backupExternalData(
        packageName: String,
        appDir: File,
        userId: String,
        compression: String,
    ): Long? {
        val pkgEsc = packageName.shellEscape()
        val externalDataDir = "/data/media/$userId/Android/data/$pkgEsc"

        // Check if the directory exists
        val checkResult = RootShell.exec("test -d '$externalDataDir' && echo 1 || echo 0")
        if (checkResult.output.trim() != "1") {
            Log.d(TAG, "backupExternalData: $packageName — no external data dir at $externalDataDir")
            return 0L // Not an error, just no data
        }

        val archiveExt = if (compression == "zstd") ".zst" else ".gz"
        val archiveFile = File(appDir, "${packageName}_external_data.tar$archiveExt")
        val archivePath = archiveFile.absolutePath.shellEscape()
        val dataExcludes = "--exclude='cache' --exclude='Backup_*' --exclude='.ota'"

        val result =
            if (compression == "zstd") {
                RootShell.exec(
                    "set -o pipefail; tar -cf - $dataExcludes '$externalDataDir' 2>/dev/null | zstd -T0 -o '$archivePath'",
                )
            } else {
                RootShell.exec("tar -czf $dataExcludes '$archivePath' '$externalDataDir' 2>/dev/null")
            }

        if (!result.isSuccess) {
            Log.w(TAG, "backupExternalData: $packageName tar failed: ${result.error}")
            return null
        }

        // Verify compression integrity
        val verifyCmd = if (compression == "zstd") "zstd -t '$archivePath' 2>/dev/null" else "gzip -t '$archivePath' 2>/dev/null"
        val verificationOk = RootShell.exec(verifyCmd).isSuccess
        if (!verificationOk) {
            Log.e(TAG, "backupExternalData: $packageName integrity check FAILED")
            return null
        }

        // Validate tar structure
        val tarListCmd =
            if (compression == "zstd") {
                "zstd -d -c '$archivePath' 2>/dev/null | tar -tf - > /dev/null 2>&1"
            } else {
                "tar -tf '$archivePath' > /dev/null 2>&1"
            }
        val tarOk = RootShell.exec(tarListCmd).isSuccess
        if (!tarOk) {
            Log.e(TAG, "backupExternalData: $packageName tar structure validation FAILED")
            return null
        }

        Log.i(TAG, "backupExternalData: $packageName backed up (size=${archiveFile.length()})")
        return BackupOperation.backupFileSize(archiveFile)
    }

    /**
     * 备份单个应用的 SSAID（设置安全标识符）。
     * 从 settings_ssaid.xml 中提取。
     */
    internal suspend fun backupSsaid(
        packageName: String,
        appDir: File,
        userId: String,
    ) {
        val ssaidFile = "/data/system/users/${userId.shellEscape()}/settings_ssaid.xml"
        // Parse XML value attribute for this package's SSAID entry
        val result = RootShell.exec("cat '$ssaidFile' 2>/dev/null")
        if (!result.isSuccess || result.output.isBlank()) return
        val ssaidLine =
            result.output.lines().firstOrNull { line ->
                line.contains("packageName=\"$packageName\"") || line.contains("packageName='$packageName'")
            }
        val value =
            ssaidLine
                ?.substringAfter("value=\"")
                ?.substringBefore("\"")
                ?.takeIf { it.isNotBlank() }
        if (value != null) {
            val ssaidFile = File(appDir, "ssaid.txt")
            if (!writeFileForBackup(ssaidFile, value)) {
                Log.w(TAG, "backupSsaid: failed to write ssaid.txt for $packageName")
            } else {
                Log.d(TAG, "backupSsaid: backed up SSAID for $packageName = $value")
            }
        }
    }

    /**
     * 备份单个应用的运行时权限状态。
     */
    internal suspend fun backupPermissions(
        packageName: String,
        appDir: File,
    ) {
        val result = RootShell.exec("dumpsys package '${packageName.shellEscape()}' | grep -E 'granted=(true|false)'")
        if (result.output.isNotBlank()) {
            val permFile = File(appDir, "permissions.txt")
            if (!writeFileForBackup(permFile, result.output)) {
                Log.w(TAG, "backupPermissions: failed to write permissions.txt for $packageName")
            }
        }
    }

    internal suspend fun buildAppDetailsJson(
        apps: List<AppInfo>,
        legacyApps: Map<String, SnapshotAppInfo>? = null,
        perAppExtra: Map<String, PerAppExtra>? = null,
    ): String {
        val root = JSONObject()
        val now = java.text.SimpleDateFormat("yyyy.MM.dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        for (app in apps) {
            val entry = JSONObject()
            entry.put("label", app.label)
            entry.put("isSystem", app.isSystem)
            entry.put("PackageName", app.packageName.value)

            // APK versionCode for incremental skip
            val versionResult = RootShell.exec("dumpsys package '${app.packageName.value.shellEscape()}' | grep versionCode | head -1")
            val apkVersion =
                versionResult.output
                    .substringAfter("versionCode=")
                    .substringBefore(" ")
                    .filter { it.isDigit() }
                    .takeIf { it.isNotEmpty() }
            if (apkVersion != null) entry.put("apk_version", apkVersion)

            // APK file sizes
            val paths = AppScanner.getApkPaths(app.packageName.value)
            val sizes =
                paths.map { path ->
                    val result = RootShell.exec("stat -c%s '${path.shellEscape()}'")
                    if (result.isSuccess) result.output.trim().toLongOrNull() ?: 0L else 0L
                }
            entry.put("apkSizes", JSONArray(sizes))

            // Per-app extra data collected during backup
            val extra = perAppExtra?.get(app.packageName.value)
            if (extra != null) {
                if (extra.ssaid != null) entry.put("Ssaid", extra.ssaid)
                if (extra.permissions != null) entry.put("permissions", extra.permissions)
                if (extra.keystore) entry.put("keystore", "true")

                fun putSize(
                    key: String,
                    value: Long?,
                ) {
                    if (value != null) {
                        val obj = JSONObject()
                        obj.put("Size", value.toString())
                        entry.put(key, obj)
                    }
                }
                putSize("user", extra.userSize)
                putSize("user_de", extra.userDeSize)
                putSize("data", extra.dataSize)
                putSize("obb", extra.obbSize)
            }

            val timeObj = JSONObject()
            timeObj.put("date", now)
            entry.put("Backup time", timeObj)

            root.put(app.packageName.value, entry)
        }
        // Legacy apps from previous snapshot
        val legacyMap = legacyApps ?: emptyMap()
        for ((pkg, legacy) in legacyMap) {
            if (!root.has(pkg)) {
                val entry = JSONObject()
                entry.put("label", legacy.label)
                entry.put("isSystem", legacy.isSystem)
                entry.put("apkSizes", JSONArray(legacy.apkSizes))
                root.put(pkg, entry)
            }
        }
        return root.toString(2)
    }

    /**
     * Per-app extra metadata collected during backup write phase.
     */
    internal data class PerAppExtra(
        val ssaid: String? = null,
        val permissions: org.json.JSONObject? = null,
        val keystore: Boolean = false,
        val userSize: Long? = null,
        val userDeSize: Long? = null,
        val dataSize: Long? = null,
        val obbSize: Long? = null,
    )

    /** Create backup output directory, falling back to root shell [mkdir -p]. */
    internal suspend fun mkdirsForBackup(dir: File): Boolean {
        if (dir.isDirectory) return true
        if (dir.mkdirs()) return true
        val result = RootShell.exec("mkdir -p '${dir.absolutePath.shellEscape()}'")
        return result.isSuccess && dir.isDirectory
    }

    /** Write text to a file, falling back to root shell (base64 + cat). */
    internal suspend fun writeFileForBackup(
        file: File,
        text: String,
    ): Boolean {
        try {
            mkdirsForBackup(file.parentFile ?: return false)
            file.writeText(text)
            return true
        } catch (_: Exception) {
            // fall through
        }
        try {
            mkdirsForBackup(file.parentFile ?: return false)
            val b64 = android.util.Base64.encodeToString(text.toByteArray(), android.util.Base64.NO_WRAP)
            val result = RootShell.exec("echo '${b64.shellEscape()}' | base64 -d > '${file.absolutePath.shellEscape()}'")
            return result.isSuccess
        } catch (e: Exception) {
            Log.w(TAG, "writeFileForBackup: all methods failed for ${file.absolutePath}", e)
            return false
        }
    }

    /** Read file content, falling back to root shell [cat]. Returns null on failure. */
    internal suspend fun readTextFile(file: File): String? {
        try {
            if (file.exists()) return file.readText()
        } catch (_: Exception) {
            // fall through
        }
        try {
            val result = RootShell.exec("cat '${file.absolutePath.shellEscape()}' 2>/dev/null")
            if (result.isSuccess && result.output.isNotBlank()) return result.output
        } catch (_: Exception) {
            // fall through
        }
        return null
    }

    /** Check if a path is a directory, falling back to root shell [test -d]. */
    internal suspend fun backupIsDirectory(dir: File): Boolean {
        if (dir.isDirectory()) return true
        val result = RootShell.exec("test -d '${dir.absolutePath.shellEscape()}' && echo 1 || echo 0")
        return result.output.trim() == "1"
    }

    /** Get file size via root shell [stat] when Java File.length() returns 0 on FUSE. */
    internal suspend fun backupFileSize(file: File): Long {
        val javaSize = file.length()
        if (javaSize > 0L) return javaSize
        val result = RootShell.exec("stat -c%s '${file.absolutePath.shellEscape()}' 2>/dev/null")
        return result.output.trim().toLongOrNull() ?: 0L
    }

    /** Check if a file/directory exists, falling back to root shell [test -e]. */
    internal suspend fun backupPathExists(file: File): Boolean {
        if (file.exists()) return true
        val result = RootShell.exec("test -e '${file.absolutePath.shellEscape()}' && echo 1 || echo 0")
        return result.output.trim() == "1"
    }

    /**
     * List immediate children in a directory, falling back to root shell [ls -1].
     * Returns relative names only (not full paths).
     */
    internal suspend fun listBackupFiles(dir: File): List<String>? {
        try {
            val javaFiles = dir.listFiles()
            if (javaFiles != null) {
                val names = javaFiles.map { it.name }
                if (names.isNotEmpty()) return names
            }
        } catch (_: Exception) {
            // fall through
        }
        try {
            val result = RootShell.exec("ls -1 '${dir.absolutePath.shellEscape()}' 2>/dev/null")
            if (!result.isSuccess || result.output.isBlank()) return null
            return result.output.lines().filter { it.isNotBlank() }
        } catch (_: Exception) {
            return null
        }
    }
}
