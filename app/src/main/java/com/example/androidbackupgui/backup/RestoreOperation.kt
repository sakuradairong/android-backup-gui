package com.example.androidbackupgui.backup
import android.content.Context
import android.util.Log
import com.example.androidbackupgui.backup.core.LogUtil
import com.example.androidbackupgui.backup.security.BinaryResolver
import com.example.androidbackupgui.root.RootShell
import com.example.androidbackupgui.root.shellEscape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Performs restore of backed-up apps using root shell.
 * Mirrors the logic from backup_script's modules/restore.sh.
 */
object RestoreOperation {
    private const val TAG = "RestoreOperation"

    @Serializable
    data class RestoreProgress(
        val current: Int,
        val total: Int,
        val packageName: String,
        val stage: String, // "install", "data", "obb", "ssaid", "permissions", "appdone" (per-app finish), "done" (reserved for overall)
        val message: String,
    )

    @Serializable
    data class RestoreResult(
        val successCount: Int,
        val failCount: Int,
        val elapsedMs: Long,
    )

    /**
     * Restore apps from a backup directory.
     * @param filterPkgs if non-null, only restore packages in this set
     */
    suspend fun restoreApps(
        context: Context,
        backupDir: File,
        userId: String = "0",
        filterPkgs: Set<String>? = null,
        onProgress: suspend (RestoreProgress) -> Unit = {},
    ): RestoreResult =
        withContext(Dispatchers.IO) {
            // Caller is responsible for thread context for the progress callback.
            // The ViewModel updates StateFlow from its own scope, so we don't
            // force a Main switch here (would add hundreds of context switches
            // per restore session).
            val emit: suspend (RestoreProgress) -> Unit = { p -> onProgress(p) }
            val startTime = System.currentTimeMillis()

            // Resolve bundled binary paths for tar/zstd (backup used them, restore must too)
            val tarCmd = BinaryResolver.tarPath(context) ?: "tar"
            val bundledZstd = BinaryResolver.zstdPath(context)
            val zstdCmd = bundledZstd ?: "zstd"

            // Read app list from backup
            val appListFile = File(backupDir, "appList.txt")
            val appListContent = BackupOperation.readTextFile(appListFile)
            LogUtil.i(TAG, "restoreApps: appListContent=${appListContent?.substringBefore("\n")?.take(100)}")
            val allPackages =
                appListContent?.let { content ->
                    content.lines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .mapNotNull { PackageName.safe(it)?.value }
                } ?: run {
                    LogUtil.i(TAG, "restoreApps: readTextFile returned null, trying listBackupFiles")
                    val children = BackupOperation.listBackupFiles(backupDir)
                    LogUtil.i(TAG, "restoreApps: listBackupFiles returned ${children?.size} children")
                    children?.mapNotNull { name -> PackageName.safe(name)?.value }?.filter { name ->
                        val apkFile = File(File(backupDir, name), "$name.apk")
                        val exists = BackupOperation.backupPathExists(apkFile)
                        LogUtil.i(TAG, "restoreApps: child $name apkExists=$exists")
                        exists
                    } ?: emptyList()
                }

            val packages =
                if (filterPkgs != null) {
                    allPackages.filter { it in filterPkgs }
                } else {
                    allPackages
                }
            LogUtil.i(
                TAG,
                "restoreApps: starting restore of ${packages.size} packages (all=${allPackages.size}) from ${backupDir.absolutePath}",
            )
            if (packages.isEmpty()) {
                LogUtil.w(TAG, "restoreApps: packages list is empty, nothing to restore")
            }

            val successAtomic = AtomicInteger(0)
            val failAtomic = AtomicInteger(0)

            // 智能并发控制：根据设备性能动态调整并发数
            val concurrencyConfig = ConcurrencyController.calculateOptimalConcurrency(context, "restore")
            val semaphore = Semaphore(concurrencyConfig.maxConcurrency)
            LogUtil.i(TAG, "restoreApps: ${concurrencyConfig.reason}")

            val backupCanonical = backupDir.canonicalFile

            supervisorScope {
                packages.forEachIndexed { index, pkg ->
                    launch {
                        if (!coroutineContext.isActive) return@launch
                        semaphore.withPermit {
                            val appBackupDir = File(backupCanonical, pkg).canonicalFile
                            if (!appBackupDir.path.startsWith(backupCanonical.path + File.separator)) {
                                failAtomic.incrementAndGet()
                                emit(RestoreProgress(index + 1, packages.size, pkg, "appdone", "备份目录路径非法"))
                                return@withPermit
                            }
                            val dirExists = BackupFileIO.backupPathExists(appBackupDir)
                            LogUtil.i(TAG, "restoreApps: pkg=$pkg appBackupDir=${appBackupDir.absolutePath} exists=$dirExists")
                            if (!dirExists) {
                                failAtomic.incrementAndGet()
                                emit(RestoreProgress(index + 1, packages.size, pkg, "appdone", "备份目录不存在"))
                                return@withPermit
                            }

                            // 1. Install APK
                            emit(RestoreProgress(index + 1, packages.size, pkg, "install", "正在安装 APK…"))
                            val installed = RestoreApkInstaller.installApk(pkg, appBackupDir, context.cacheDir)
                            LogUtil.i(TAG, "restoreApps: pkg=$pkg installApk result=$installed")

                            if (!installed) {
                                failAtomic.incrementAndGet()
                                emit(RestoreProgress(index + 1, packages.size, pkg, "appdone", "安装失败"))
                                return@withPermit
                            }

                            // 2. Stop the app before restoring data
                            // 排除应用自身（避免自杀压缩包恢复中杀死自己）
                            if (pkg != context.packageName) {
                                RootShell.exec("am force-stop '${pkg.shellEscape()}'")
                            }

                            // 3. Restore data
                            emit(RestoreProgress(index + 1, packages.size, pkg, "data", "正在恢复数据…"))
                            val dataOk = RestoreAppDataOps.restoreData(pkg, userId, appBackupDir, tarCmd, zstdCmd)
                            if (!dataOk) {
                                failAtomic.incrementAndGet()
                                emit(RestoreProgress(index + 1, packages.size, pkg, "appdone", "数据恢复失败"))
                                return@withPermit
                            }

                            // 4. Restore OBB
                            emit(RestoreProgress(index + 1, packages.size, pkg, "obb", "正在恢复 OBB…"))
                            val obbOk = RestoreAppDataOps.restoreObb(pkg, appBackupDir, tarCmd, zstdCmd, userId)
                            if (!obbOk) {
                                Log.w(TAG, "restoreApps: OBB restore failed for $pkg, continuing")
                            }

                            // 4.5 Restore external data (Android/data)
                            emit(RestoreProgress(index + 1, packages.size, pkg, "data", "正在恢复外部数据…"))
                            val extDataOk = RestoreAppDataOps.restoreExternalData(pkg, appBackupDir, tarCmd, zstdCmd, userId)
                            if (!extDataOk) {
                                Log.w(TAG, "restoreApps: external data restore failed for $pkg, continuing")
                            }

                            // 5. Restore SSAID
                            emit(RestoreProgress(index + 1, packages.size, pkg, "ssaid", "正在恢复 SSAID…"))
                            RestoreAppDataOps.restoreSsaid(pkg, appBackupDir, userId)

                            // 6. Restore permissions
                            emit(RestoreProgress(index + 1, packages.size, pkg, "permissions", "正在恢复权限…"))
                            RestoreAppDataOps.restorePermissions(pkg, appBackupDir)

                            // 7. Fix data ownership and SELinux
                            RestoreAppDataOps.fixDataOwnership(pkg, userId) { pkgName -> resolveAppUid(pkgName) }

                            successAtomic.incrementAndGet()
                            emit(RestoreProgress(index + 1, packages.size, pkg, "appdone", "完成"))
                        }
                    }
                }
            }

            val elapsed = System.currentTimeMillis() - startTime
            val successCount = successAtomic.get()
            val failCount = failAtomic.get()
            LogUtil.i(TAG, "restoreApps: completed — success=$successCount fail=$failCount elapsed=${elapsed}ms")
            RestoreResult(successCount, failCount, elapsed)
        }


    /** Resolve app UID using multiple methods for robustness across Android versions. */
    private suspend fun resolveAppUid(packageName: String): Int? {
        val pkgEsc = packageName.shellEscape()
        // Method 1: pm list packages -U (reliable, consistent output format)
        val pmResult = RootShell.exec("pm list packages -U 2>/dev/null | grep '$pkgEsc$'")
        val pmUid =
            pmResult.output
                .substringAfter(" uid:")
                .trim()
                .toIntOrNull()
        if (pmUid != null) return pmUid

        // Method 2: dumpsys package (fallback for older Android)
        val dsResult = RootShell.exec("dumpsys package '$pkgEsc' | grep 'userId=' | head -1")
        val dsUid =
            dsResult.output
                .substringAfter("userId=", "")
                .substringBefore(" ")
                .substringBefore(",")
                .trim()
                .toIntOrNull()
        if (dsUid != null) return dsUid

        // Method 3: dumpsys with userId: separator (AOSP variant)
        val ds2Result = RootShell.exec("dumpsys package '$pkgEsc' | grep 'userId:' | head -1")
        val ds2Uid =
            ds2Result.output
                .substringAfter("userId:", "")
                .substringBefore(" ")
                .trim()
                .toIntOrNull()
        return ds2Uid
    }
}
