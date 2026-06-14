package com.example.androidbackupgui.backup

import android.util.Log
import com.example.androidbackupgui.backup.AppInfo
import com.example.androidbackupgui.backup.restic.ResticWrapper.SnapshotAppInfo
import com.example.androidbackupgui.backup.core.LogUtil
import com.example.androidbackupgui.backup.restic.ResticWrapper
import com.example.androidbackupgui.backup.scan.AppScanner
import com.example.androidbackupgui.backup.scan.SsaidCache
import com.example.androidbackupgui.root.RootShell
import com.example.androidbackupgui.root.shellEscape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
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
            // emit: forward progress events to caller without forcing a thread switch.
            // The caller (ViewModel) is expected to update StateFlow from its own
            // scope; switching dispatchers here would add hundreds of context
            // switches per backup session. If the caller needs Main-thread
            // delivery, it can wrap its handler accordingly.
            val emit: suspend (BackupProgress) -> Unit = { p -> onProgress(p) }
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

            // Initialize caches for performance optimization
            val appInfoCache = AppInfoCache()
            val ssaidCache = SsaidCache(userId)
            val progressTracker = BackupProgressTracker(apps.size)

            // Pre-warm cache for all apps
            LogUtil.i(TAG, "backupApps: warming cache for ${apps.size} apps...")
            appInfoCache.warmAll(apps.map { it.packageName.value })
            LogUtil.i(TAG, "backupApps: cache warmed, ${appInfoCache.size()} apps cached")

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
            if (!writeFileForBackup(metaFile, buildAppDetailsJson(apps, legacyApps, cache = appInfoCache))) {
                LogUtil.e(TAG, "backupApps: failed to write app_details.json")
                return@withContext BackupResult(0, 0, 0, outputDir.absolutePath, 0)
            }

            val backupTargets = if (includePkgs.isEmpty()) apps else apps.filter { it.packageName.value in includePkgs }
            val totalCount = backupTargets.size
            LogUtil.i(TAG, "backupApps: includePkgs=${includePkgs.size} targets=$totalCount")

            // 智能并发控制：根据设备性能动态调整并发数
            val concurrencyConfig = ConcurrencyController.calculateOptimalConcurrency(context, "backup")
            val semaphore = Semaphore(concurrencyConfig.maxConcurrency)
            LogUtil.i(TAG, "backupApps: ${concurrencyConfig.reason}")

            val successAtomic = AtomicInteger(0)
            val failAtomic = AtomicInteger(0)
            val skippedAtomic = AtomicInteger(0)
            // Collect per-app extra metadata for app_details.json
            val perAppExtraMap = ConcurrentHashMap<String, PerAppExtra>()

            // Use supervisorScope so that one app's backup failure does NOT
            // cancel siblings — each app is independent. Errors are logged
            // and counted via failAtomic, but the overall backup continues.
            supervisorScope {
                backupTargets
                    .mapIndexed { index, app ->
                        async {
                            // Top-level try/catch per async — without it, a throw
                            // would propagate up to supervisorScope (tolerated) but
                            // also crash the coroutine mid-execution leaving state
                            // inconsistent. Catching here keeps per-app failure
                            // contained and the result list complete.
                            try {
                                semaphore.withPermit {
                                    ensureActive()
                                    backupOneApp(
                                        context = context,
                                        index = index,
                                        totalCount = totalCount,
                                        app = app,
                                        backupRoot = backupRoot,
                                        oldMetaJson = oldMetaJson,
                                        config = config,
                                        userId = userId,
                                        noDataBackup = noDataBackup,
                                        appInfoCache = appInfoCache,
                                        ssaidCache = ssaidCache,
                                        skippedAtomic = skippedAtomic,
                                        successAtomic = successAtomic,
                                        failAtomic = failAtomic,
                                        perAppExtraMap = perAppExtraMap,
                                        progressTracker = progressTracker,
                                        emit = emit,
                                    )
                                }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                failAtomic.incrementAndGet()
                                val pkg = app.packageName.value
                                Log.e(TAG, "backupApps: $pkg backup failed: ${e.message}", e)
                                emit(BackupProgress(index + 1, totalCount, pkg, "done", "备份失败: ${e.message}"))
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

            // 备份完整性校验（可选）
            if (successCount > 0) {
                LogUtil.i(TAG, "backupApps: starting integrity check...")
                val integrityReport = BackupIntegrityChecker.checkBackupIntegrity(
                    backupDir = backupRoot,
                    packages = apps.map { it.packageName.value },
                    compression = config.compressionMethod,
                )
                LogUtil.i(TAG, "backupApps: integrity check completed — ${integrityReport.passedPackages}/${integrityReport.checkedPackages} passed")

                // 生成校验和文件
                BackupIntegrityChecker.generateChecksumFile(
                    backupDir = backupRoot,
                    packages = apps.map { it.packageName.value },
                    compression = config.compressionMethod,
                )
            }

            BackupResult(
                successCount = successCount,
                failCount = failCount,
                skippedCount = skippedCount,
                outputDir = backupRoot.absolutePath,
                elapsedMs = elapsed,
            )
        }

    /**
     * Per-app backup body executed inside the supervisorScope / Semaphore in
     * [backupApps]. Extracted as a private method so the concurrency plumbing
     * stays readable; this method only contains the linear per-app flow.
     */
    private suspend fun backupOneApp(
        context: android.content.Context,
        index: Int,
        totalCount: Int,
        app: AppInfo,
        backupRoot: File,
        oldMetaJson: org.json.JSONObject,
        config: BackupConfig,
        userId: String,
        noDataBackup: Set<String>,
        appInfoCache: AppInfoCache,
        ssaidCache: SsaidCache,
        skippedAtomic: java.util.concurrent.atomic.AtomicInteger,
        successAtomic: java.util.concurrent.atomic.AtomicInteger,
        failAtomic: java.util.concurrent.atomic.AtomicInteger,
        perAppExtraMap: ConcurrentHashMap<String, PerAppExtra>,
        progressTracker: BackupProgressTracker,
        emit: suspend (BackupProgress) -> Unit,
    ) {
        val pkgName = app.packageName.value
        val appDir = File(backupRoot, pkgName)
        appDir.mkdirs()

        // ── Incremental check: compare APK version ──
        val oldEntry = oldMetaJson.optJSONObject(pkgName)
        val oldApkVersion = oldEntry?.optString("apk_version", null)
        var installedVersion: String? = null
        var apkChanged = true
        if (oldApkVersion != null) {
            installedVersion = appInfoCache.getVersionCode(pkgName)
            if (installedVersion != null && oldApkVersion == installedVersion) {
                apkChanged = false
                Log.d(TAG, "backupApps: $pkgName APK $oldApkVersion unchanged, skipping")
                progressTracker.skipApp(pkgName, "APK无变化，跳过")
            }
        }

        // 1. Backup APK (only if version changed)
        if (apkChanged) {
            progressTracker.updateStage("apk", "正在备份 APK…")
            emit(BackupProgress(index + 1, totalCount, pkgName, "apk", "正在备份 APK…"))
            val paths = appInfoCache.getApkPaths(pkgName)
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
            progressTracker.skipApp(pkgName, "APK无变化，跳过")
            emit(BackupProgress(index + 1, totalCount, pkgName, "apk", "APK无变化，跳过"))
        }

        // Keystore check - 使用缓存
        val hasKeystore = appInfoCache.hasKeystore(pkgName) ?: false
        if (hasKeystore) emit(BackupProgress(index + 1, totalCount, pkgName, "data", "⚠ 包含密钥库条目"))

        // ── Size-based data incremental skip ──
        var skipData = false
        if (!apkChanged) {
            val oldUserSize =
                try {
                    oldEntry?.optJSONObject("user")?.optString("Size", null)?.toLongOrNull()
                } catch (_: Exception) {
                    null
                }
            val oldObbSize =
                try {
                    oldEntry?.optJSONObject("obb")?.optString("Size", null)?.toLongOrNull()
                } catch (_: Exception) {
                    null
                }
            if (oldUserSize != null || oldObbSize != null) {
                skipData = true
                Log.d(TAG, "backupApps: $pkgName data sizes known from backup, skipping data backup (incremental)")
                progressTracker.skipApp(pkgName, "数据大小已知，跳过数据备份")
            }
        }

        var userSize: Long? = null
        var userDeSize: Long? = null
        var dataSize: Long? = null
        var obbSize: Long? = null

        // Force-stop before data backup for consistency.
        // Exclude the app itself (avoid suicide) and well-known persistent apps.
        if (config.backupMode == 1 && !skipData) {
            if (pkgName !in listOf("bin.mt.plus", "com.termux", "bin.mt.plus.canary", context.packageName)) {
                RootShell.exec("am force-stop --user ${userId.shellEscape()} '${pkgName.shellEscape()}' 2>/dev/null")
            }
        }

        // 2. Backup user data
        if (config.backupMode == 1 && config.backupUserData == 1 && !skipData) {
            if (pkgName in noDataBackup) {
                emit(BackupProgress(index + 1, totalCount, pkgName, "data", "跳过数据备份（已排除）"))
            } else {
                emit(BackupProgress(index + 1, totalCount, pkgName, "data", "正在备份数据…"))
                val udResult = BackupAppDataOps.backupUserData(
                    context, pkgName, appDir, userId, config.compressionMethod,
                )
                userSize = udResult.first
                userDeSize = udResult.second
                if (udResult.first == null) {
                    failAtomic.incrementAndGet()
                    emit(BackupProgress(index + 1, totalCount, pkgName, "done", "数据备份失败"))
                    return
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
                obbSize = BackupAppDataOps.backupObb(pkgName, appDir, config.compressionMethod)
                if (obbSize == null) {
                    failAtomic.incrementAndGet()
                    emit(BackupProgress(index + 1, totalCount, pkgName, "done", "OBB 备份失败"))
                    return
                }
            }
        }

        // 3.5 Backup external data
        if (config.backupMode == 1 && config.backupUserData == 1 && !skipData) {
            if (pkgName !in noDataBackup) {
                emit(BackupProgress(index + 1, totalCount, pkgName, "data", "正在备份外部数据…"))
                dataSize = BackupAppDataOps.backupExternalData(pkgName, appDir, userId, config.compressionMethod)
            }
        }

        // 4. Backup SSAID
        progressTracker.updateStage("ssaid", "正在备份 SSAID…")
        emit(BackupProgress(index + 1, totalCount, pkgName, "ssaid", "正在备份 SSAID…"))
        BackupAppDataOps.backupSsaid(pkgName, appDir, userId, ssaidCache)

        // Icon + permissions
        val iconPath = AppScanner.extractIcon(pkgName, appDir, app.userId.value)
        if (iconPath != null) Log.d(TAG, "backupApps: saved icon for $pkgName -> $iconPath")
        BackupAppDataOps.backupPermissions(pkgName, appDir)

        // Save per-app metadata
        val ssaidValue = BackupFileIO.readTextFile(File(appDir, "ssaid.txt"))?.trim()
        val permText = BackupFileIO.readTextFile(File(appDir, "permissions.txt"))
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

    internal suspend fun buildAppDetailsJson(
        apps: List<AppInfo>,
        legacyApps: Map<String, SnapshotAppInfo>? = null,
        perAppExtra: Map<String, PerAppExtra>? = null,
        cache: AppInfoCache? = null,
    ): String {
        val root = JSONObject()
        val now = java.text.SimpleDateFormat("yyyy.MM.dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        for (app in apps) {
            val entry = JSONObject()
            entry.put("label", app.label)
            entry.put("isSystem", app.isSystem)
            entry.put("PackageName", app.packageName.value)

            // APK versionCode for incremental skip - 使用缓存
            val apkVersion = cache?.getVersionCode(app.packageName.value) ?: run {
                // 回退到直接查询
                val versionResult = RootShell.exec("dumpsys package '${app.packageName.value.shellEscape()}' | grep versionCode | head -1")
                versionResult.output
                    .substringAfter("versionCode=")
                    .substringBefore(" ")
                    .filter { it.isDigit() }
                    .takeIf { it.isNotEmpty() }
            }
            if (apkVersion != null) entry.put("apk_version", apkVersion)

            // APK file sizes - 使用缓存
            val paths = cache?.getApkPaths(app.packageName.value) ?: AppScanner.getApkPaths(app.packageName.value)
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

    // ── Backward-compat delegations ──────────────────────────────────
    // 以下委托方法保留以兼容现有调用方（如 RestoreOperation、ResticStreamBackup、
    // RestoreScreen）。新代码应直接使用 BackupFileIO。
    @Deprecated("Use BackupFileIO.mkdirsForBackup", ReplaceWith("BackupFileIO.mkdirsForBackup(dir)"))
    internal suspend fun mkdirsForBackup(dir: File): Boolean = BackupFileIO.mkdirsForBackup(dir)

    @Deprecated("Use BackupFileIO.writeFileForBackup", ReplaceWith("BackupFileIO.writeFileForBackup(file, text)"))
    internal suspend fun writeFileForBackup(
        file: File,
        text: String,
    ): Boolean = BackupFileIO.writeFileForBackup(file, text)

    @Deprecated("Use BackupFileIO.readTextFile", ReplaceWith("BackupFileIO.readTextFile(file)"))
    internal suspend fun readTextFile(file: File): String? = BackupFileIO.readTextFile(file)

    @Deprecated("Use BackupFileIO.backupIsDirectory", ReplaceWith("BackupFileIO.backupIsDirectory(dir)"))
    internal suspend fun backupIsDirectory(dir: File): Boolean = BackupFileIO.backupIsDirectory(dir)

    @Deprecated("Use BackupFileIO.backupFileSize", ReplaceWith("BackupFileIO.backupFileSize(file)"))
    internal suspend fun backupFileSize(file: File): Long = BackupFileIO.backupFileSize(file)

    @Deprecated("Use BackupFileIO.backupPathExists", ReplaceWith("BackupFileIO.backupPathExists(file)"))
    internal suspend fun backupPathExists(file: File): Boolean = BackupFileIO.backupPathExists(file)

    @Deprecated("Use BackupFileIO.listBackupFiles", ReplaceWith("BackupFileIO.listBackupFiles(dir)"))
    internal suspend fun listBackupFiles(dir: File): List<String>? = BackupFileIO.listBackupFiles(dir)

    @Deprecated("Use BackupAppDataOps.runTar", ReplaceWith("BackupAppDataOps.runTar(dirs, outputFile, isZstd, tarCmd, zstdCmd, excludes)"))
    internal suspend fun runTar(
        dirs: List<String>,
        outputFile: String,
        isZstd: Boolean,
        tarCmd: String = "tar",
        zstdCmd: String = "zstd",
        excludes: List<String> = emptyList(),
    ): RootShell.ShellResult =
        BackupAppDataOps.runTar(dirs, outputFile, isZstd, tarCmd, zstdCmd, excludes)

    @Deprecated("Use BackupAppDataOps.backupUserData", ReplaceWith("BackupAppDataOps.backupUserData(context, packageName, appDir, userId, compression)"))
    internal suspend fun backupUserData(
        context: android.content.Context,
        packageName: String,
        appDir: File,
        userId: String,
        compression: String,
    ): Pair<Long?, Long?> =
        BackupAppDataOps.backupUserData(context, packageName, appDir, userId, compression)

    @Deprecated("Use BackupAppDataOps.backupObb", ReplaceWith("BackupAppDataOps.backupObb(packageName, appDir, compression)"))
    internal suspend fun backupObb(
        packageName: String,
        appDir: File,
        compression: String,
    ): Long? = BackupAppDataOps.backupObb(packageName, appDir, compression)

    @Deprecated("Use BackupAppDataOps.backupExternalData", ReplaceWith("BackupAppDataOps.backupExternalData(packageName, appDir, userId, compression)"))
    internal suspend fun backupExternalData(
        packageName: String,
        appDir: File,
        userId: String,
        compression: String,
    ): Long? = BackupAppDataOps.backupExternalData(packageName, appDir, userId, compression)

    @Deprecated("Use BackupAppDataOps.backupSsaid", ReplaceWith("BackupAppDataOps.backupSsaid(packageName, appDir, userId, ssaidCache)"))
    internal suspend fun backupSsaid(
        packageName: String,
        appDir: File,
        userId: String,
        ssaidCache: SsaidCache? = null,
    ) = BackupAppDataOps.backupSsaid(packageName, appDir, userId, ssaidCache)

    @Deprecated("Use BackupAppDataOps.backupPermissions", ReplaceWith("BackupAppDataOps.backupPermissions(packageName, appDir)"))
    internal suspend fun backupPermissions(
        packageName: String,
        appDir: File,
    ) = BackupAppDataOps.backupPermissions(packageName, appDir)
}
