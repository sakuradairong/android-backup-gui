package com.example.androidbackupgui.backup
import com.example.androidbackupgui.root.RootShell
import com.example.androidbackupgui.root.shellEscape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.Serializable

/**
 * Performs restore of backed-up apps using root shell.
 * Mirrors the logic from backup_script's modules/restore.sh.
 */
object RestoreOperation {

    @Serializable
    data class RestoreProgress(
        val current: Int,
        val total: Int,
        val packageName: String,
        val stage: String,    // "install", "data", "obb", "ssaid", "permissions", "done"
        val message: String
    )

    @Serializable
    data class RestoreResult(
        val successCount: Int,
        val failCount: Int,
        val elapsedMs: Long
    )

    /**
     * Restore apps from a backup directory.
     * @param filterPkgs if non-null, only restore packages in this set
     */
    suspend fun restoreApps(
        backupDir: File,
        userId: String = "0",
        filterPkgs: Set<String>? = null,
        onProgress: suspend (RestoreProgress) -> Unit = {}
    ): RestoreResult = withContext(Dispatchers.IO) {
        val emit: suspend (RestoreProgress) -> Unit = { p -> withContext(Dispatchers.Main) { onProgress(p) } }
        val startTime = System.currentTimeMillis()

        // Read app list from backup
        val appListFile = File(backupDir, "appList.txt")
        val allPackages = if (appListFile.exists()) {
            appListFile.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
        } else {
            // Fallback: scan subdirectories
            backupDir.listFiles()
                ?.filter { it.isDirectory && File(it, "${it.name}.apk").exists() }
                ?.map { it.name }
                ?: emptyList()
        }

        val packages = if (filterPkgs != null) {
            allPackages.filter { it in filterPkgs }
        } else {
            allPackages
        }

        val successAtomic = AtomicInteger(0)
        val failAtomic = AtomicInteger(0)

        val semaphore = Semaphore(2)
        coroutineScope {
            packages.forEachIndexed { index, pkg ->
                launch {
                    if (!coroutineContext.isActive) return@launch
                    semaphore.withPermit {
                        val appBackupDir = File(backupDir, pkg)
                        if (!appBackupDir.exists()) {
                            failAtomic.incrementAndGet()
                            return@withPermit
                        }

                        // 1. Install APK
                        emit(RestoreProgress(index + 1, packages.size, pkg, "install", "正在安装 APK…"))
                        val installed = installApk(appBackupDir)

                        if (!installed) {
                            failAtomic.incrementAndGet()
                            emit(RestoreProgress(index + 1, packages.size, pkg, "done", "安装失败"))
                            return@withPermit
                        }

                        // 2. Stop the app before restoring data
                        RootShell.exec("am force-stop '${pkg.shellEscape()}'")

                        // 3. Restore data
                        emit(RestoreProgress(index + 1, packages.size, pkg, "data", "正在恢复数据…"))
                        restoreData(appBackupDir)

                        // 4. Restore OBB
                        emit(RestoreProgress(index + 1, packages.size, pkg, "obb", "正在恢复 OBB…"))
                        restoreObb(pkg, appBackupDir)

                        // 5. Restore SSAID
                        emit(RestoreProgress(index + 1, packages.size, pkg, "ssaid", "正在恢复 SSAID…"))
                        restoreSsaid(pkg, appBackupDir, userId)

                        // 6. Restore permissions
                        emit(RestoreProgress(index + 1, packages.size, pkg, "permissions", "正在恢复权限…"))
                        restorePermissions(pkg, appBackupDir)

                        // 7. Fix data ownership and SELinux
                        fixDataOwnership(pkg, userId)

                        successAtomic.incrementAndGet()
                        emit(RestoreProgress(index + 1, packages.size, pkg, "done", "完成"))
                    }
                }
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        RestoreResult(successAtomic.get(), failAtomic.get(), elapsed)
    }

    private suspend fun installApk(appDir: File): Boolean {
        // Find APK files
        val apkFiles = appDir.listFiles()
            ?.filter { it.name.endsWith(".apk") }
            ?.sortedBy { it.name } // main APK first, splits after
            ?: return false

        if (apkFiles.isEmpty()) return false

        // Build install command for multiple APKs (split APK support)
        val apkPaths = apkFiles.joinToString(" ") { "'${it.absolutePath.shellEscape()}'" }

        // Try pm install with multiple session for split APKs
        if (apkFiles.size > 1) {
            val result = RootShell.exec("pm install-create -r -t 2>/dev/null")
            val sessionId = result.output.lines()
                .firstOrNull { it.contains("Success") }
                ?.substringAfter("[")
                ?.substringBefore("]")

            if (sessionId != null) {
                for ((i, apk) in apkFiles.withIndex()) {
                    val sessionName = if (i == 0) "base.apk" else "split_${i}.apk"
                    RootShell.exec("pm install-write '${sessionId.shellEscape()}' '$sessionName' '${apk.absolutePath.shellEscape()}'")
                }
                val commit = RootShell.exec("pm install-commit '${sessionId.shellEscape()}'")
                return commit.isSuccess
            }
        }

        // Single APK install
        val result = RootShell.exec("pm install -r -t $apkPaths")
        return result.isSuccess
    }

    private suspend fun restoreData(appDir: File) {

        // Find data archive
        val dataFiles = appDir.listFiles()
            ?.filter { it.name.contains("_data.tar") }
            ?: return

        for (archive in dataFiles) {
            val archivePath = archive.absolutePath.shellEscape()
            // Verify archive doesn't contain path traversal before extracting
            if (!isArchiveSafe(archive)) continue
            when {
                archive.name.endsWith(".zst") -> {
                    RootShell.exec("zstd -d -c '$archivePath' | tar -xf - -C / 2>/dev/null")
                }
                archive.name.endsWith(".gz") -> {
                    RootShell.exec("tar -xzf '$archivePath' -C / 2>/dev/null")
                }
                archive.name.endsWith(".tar") -> {
                    RootShell.exec("tar -xf '$archivePath' -C / 2>/dev/null")
                }
            }
        }
    }

    /**
     * Check that a tar archive contains no path traversal (..) entries
     * or symbolic links pointing outside the tree.
     * Accepts both absolute and relative paths — tar implementations vary.
     */
    private suspend fun isArchiveSafe(archive: File): Boolean {
        val listCmd = if (archive.name.endsWith(".zst")) {
            "zstd -d -c '${archive.absolutePath.shellEscape()}' | tar tf - 2>/dev/null"
        } else {
            "tar tf '${archive.absolutePath.shellEscape()}' 2>/dev/null"
        }
        val result = RootShell.exec(listCmd)
        if (!result.isSuccess) return false
        return !result.output.lines().any { line ->
            val path = line.substringBefore(" -> ")
            val hasTraversal = path.trimStart('/').split("/").any { segment -> segment == ".." }
            val symlinkTarget = if (" -> " in line) line.substringAfter(" -> ") else ""
            val unsafeSymlink = symlinkTarget.isNotEmpty() &&
                (symlinkTarget.startsWith("/") || symlinkTarget.split("/").any { segment -> segment == ".." })
            hasTraversal || unsafeSymlink
        }
    }

    private suspend fun restoreObb(packageName: String, appDir: File) {
        val obbFiles = appDir.listFiles()
            ?.filter { it.name.contains("_obb.tar") }
            ?: return

        for (archive in obbFiles) {
            if (!isArchiveSafe(archive)) continue
            val archivePath = archive.absolutePath.shellEscape()
            when {
                archive.name.endsWith(".zst") -> {
                    RootShell.exec("zstd -d -c '$archivePath' | tar -xf - -C / 2>/dev/null")
                }
                archive.name.endsWith(".gz") -> {
                    RootShell.exec("tar -xzf '$archivePath' -C / 2>/dev/null")
                }
                archive.name.endsWith(".tar") -> {
                    RootShell.exec("tar -xf '$archivePath' -C / 2>/dev/null")
                }
            }
        }

        // Fix OBB permissions
        RootShell.exec("chown -R 1023:1023 /storage/emulated/0/Android/obb/${packageName.shellEscape()}/ 2>/dev/null")
    }

    private suspend fun restoreSsaid(packageName: String, appDir: File, userId: String) {
        val ssaidFile = File(appDir, "ssaid.txt")
        if (!ssaidFile.exists()) return

        val ssaidLine = ssaidFile.readText().trim()
        if (ssaidLine.isBlank()) return

        val targetFile = "/data/system/users/${userId.shellEscape()}/settings_ssaid.xml"
        val pkgEsc = packageName.shellEscape()
        val ssaidEsc = ssaidLine.shellEscape()

        // Remove existing entry for this package, insert new one before </settings>
        RootShell.exec(
            "grep -v '${pkgEsc}' '$targetFile' > '$targetFile.tmp' && " +
            "sed -i '\$ i ${ssaidEsc}' '$targetFile.tmp' && " +
            "mv '$targetFile.tmp' '$targetFile'"
        )
    }

    private suspend fun restorePermissions(packageName: String, appDir: File) {
        val permFile = File(appDir, "permissions.txt")
        if (!permFile.exists()) return

        val perms = permFile.readLines()
            .filter { it.contains("granted=true") }
            .mapNotNull { line ->
                // Extract permission name from dumpsys output
                // Format: "permission.name: granted=true" or similar
                line.substringBefore(":")
                    .trim()
                    .takeIf { it.isNotEmpty() && it.contains(".") }
            }

        val pkgEsc = packageName.shellEscape()
        for (perm in perms) {
            val result = RootShell.exec("pm grant '$pkgEsc' '${perm.shellEscape()}' 2>&1")
            if (!result.isSuccess) {
                android.util.Log.w("RestoreOperation", "pm grant failed for $packageName: $perm — ${result.output}")
            }
        }
    }

    private suspend fun fixDataOwnership(packageName: String, userId: String) {
        val pkgEsc = packageName.shellEscape()
        val uidEsc = userId.shellEscape()
        val uidResult = RootShell.exec("dumpsys package '$pkgEsc' | grep 'userId=' | head -1")
        val uid = uidResult.output
            .substringAfter("userId=", "")
            .substringBefore(" ")
            .substringBefore(",")
            .trim()
            .toIntOrNull()

        if (uid != null) {
            RootShell.exec("chown -R $uid:$uid /data/data/$pkgEsc/ 2>/dev/null")
            RootShell.exec("chown -R $uid:$uid /data/user_de/$uidEsc/$pkgEsc/ 2>/dev/null")
            RootShell.exec("restorecon -R /data/data/$pkgEsc/ 2>/dev/null")
            RootShell.exec("restorecon -R /data/user_de/$uidEsc/$pkgEsc/ 2>/dev/null")
        }
    }
}
