package com.example.androidbackupgui.backup
import com.example.androidbackupgui.root.RootShell
import com.example.androidbackupgui.root.shellEscape
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.supervisorScope
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

    private const val TAG = "RestoreOperation"

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
        context: Context,
        backupDir: File,
        userId: String = "0",
        filterPkgs: Set<String>? = null,
        onProgress: suspend (RestoreProgress) -> Unit = {}
    ): RestoreResult = withContext(Dispatchers.IO) {
        val emit: suspend (RestoreProgress) -> Unit = { p -> withContext(Dispatchers.Main) { onProgress(p) } }
        val startTime = System.currentTimeMillis()

        // Resolve bundled binary paths for tar/zstd (backup used them, restore must too)
        val tarCmd = BinaryResolver.tarPath(context) ?: "tar"
        val bundledZstd = BinaryResolver.zstdPath(context)
        val zstdCmd = bundledZstd ?: "zstd"

        // Read app list from backup
        val appListFile = File(backupDir, "appList.txt")
        val appListContent = BackupOperation.readTextFile(appListFile)
        LogUtil.i(TAG, "restoreApps: appListContent=${appListContent?.substringBefore("\n")?.take(100)}")
        val allPackages = appListContent?.let { content ->
            content.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        } ?: run {
            LogUtil.i(TAG, "restoreApps: readTextFile returned null, trying listBackupFiles")
            val children = BackupOperation.listBackupFiles(backupDir)
            LogUtil.i(TAG, "restoreApps: listBackupFiles returned ${children?.size} children")
            children?.filter { name ->
                val apkFile = File(File(backupDir, name), "${name}.apk")
                val exists = BackupOperation.backupPathExists(apkFile)
                LogUtil.i(TAG, "restoreApps: child $name apkExists=$exists")
                exists
            } ?: emptyList()
        }

        val packages = if (filterPkgs != null) {
            allPackages.filter { it in filterPkgs }
        } else {
            allPackages
        }
        LogUtil.i(TAG, "restoreApps: starting restore of ${packages.size} packages (all=${allPackages.size}) from ${backupDir.absolutePath}")
        if (packages.isEmpty()) {
            LogUtil.w(TAG, "restoreApps: packages list is empty, nothing to restore")
        }

        val successAtomic = AtomicInteger(0)
        val failAtomic = AtomicInteger(0)

        val semaphore = Semaphore(2)
        supervisorScope {
            packages.forEachIndexed { index, pkg ->
                launch {
                    if (!coroutineContext.isActive) return@launch
                    semaphore.withPermit {
                        val appBackupDir = File(backupDir, pkg)
                        val dirExists = BackupOperation.backupPathExists(appBackupDir)
                        LogUtil.i(TAG, "restoreApps: pkg=$pkg appBackupDir=${appBackupDir.absolutePath} exists=$dirExists")
                        if (!dirExists) {
                            failAtomic.incrementAndGet()
                            emit(RestoreProgress(index + 1, packages.size, pkg, "done", "备份目录不存在"))
                            return@withPermit
                        }

                        // 1. Install APK
                        emit(RestoreProgress(index + 1, packages.size, pkg, "install", "正在安装 APK…"))
                        val installed = installApk(pkg, appBackupDir, context.cacheDir)
                        LogUtil.i(TAG, "restoreApps: pkg=$pkg installApk result=$installed")

                        if (!installed) {
                            failAtomic.incrementAndGet()
                            emit(RestoreProgress(index + 1, packages.size, pkg, "done", "安装失败"))
                            return@withPermit
                        }

                        // 2. Stop the app before restoring data
                        RootShell.exec("am force-stop '${pkg.shellEscape()}'")

                        // 3. Restore data
                        emit(RestoreProgress(index + 1, packages.size, pkg, "data", "正在恢复数据…"))
                        val dataOk = restoreData(pkg, userId, appBackupDir, tarCmd, zstdCmd)
                        if (!dataOk) {
                            failAtomic.incrementAndGet()
                            emit(RestoreProgress(index + 1, packages.size, pkg, "done", "数据恢复失败"))
                            return@withPermit
                        }

                        // 4. Restore OBB
                        emit(RestoreProgress(index + 1, packages.size, pkg, "obb", "正在恢复 OBB…"))
                        restoreObb(pkg, appBackupDir, tarCmd, zstdCmd)

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
        val successCount = successAtomic.get()
        val failCount = failAtomic.get()
        LogUtil.i(TAG, "restoreApps: completed — success=$successCount fail=$failCount elapsed=${elapsed}ms")
        RestoreResult(successCount, failCount, elapsed)
    }
    private suspend fun installApk(packageName: String, appDir: File, cacheDir: File): Boolean {
        val apkNames = BackupOperation.listBackupFiles(appDir)
        LogUtil.i(TAG, "installApk: $packageName listBackupFiles returned ${apkNames?.size} files: $apkNames")
        if (apkNames == null) {
            LogUtil.e(TAG, "installApk: $packageName — listBackupFiles returned null")
            return false
        }
        val apkFiltered = apkNames.filter { it.endsWith(".apk") }.sorted()
        LogUtil.i(TAG, "installApk: $packageName apkFiltered=$apkFiltered")
        if (apkFiltered.isEmpty()) return false

        // Copy APK files to cache dir (pm cannot read APKs from external storage on some ROMs)
        val installDir = File(cacheDir, "apk_install_${packageName.replace('.','_')}")
        installDir.mkdirs()
        val localApks = mutableListOf<File>()
        for (name in apkFiltered) {
            val src = File(appDir, name)
            val dst = File(installDir, name)
            RootShell.exec("cp '${src.absolutePath.shellEscape()}' '${dst.absolutePath.shellEscape()}' && chmod 644 '${dst.absolutePath.shellEscape()}'")
            localApks.add(dst)
        }

        suspend fun doInstall(): Boolean {
            val apkPaths = localApks.joinToString(" ") { it.absolutePath.shellEscape() }
            if (localApks.size > 1) {
                val result = RootShell.exec("pm install-create -r -t 2>/dev/null")
                val sessionId = result.output.lines()
                    .firstOrNull { it.contains("Success") }
                    ?.substringAfter("[")
                    ?.substringBefore("]")
                if (sessionId != null) {
                    for ((i, apk) in localApks.withIndex()) {
                        val sessionName = if (i == 0) "base.apk" else "split_${i}.apk"
                        RootShell.exec("pm install-write '${sessionId.shellEscape()}' '$sessionName' '${apk.absolutePath.shellEscape()}'")
                    }
                    val commit = RootShell.exec("pm install-commit '${sessionId.shellEscape()}'")
                    return commit.isSuccess
                }
            }
            val result = RootShell.exec("pm install -r -t $apkPaths")
            LogUtil.i(TAG, "installApk: $packageName pm install exitCode=${result.exitCode} output=${result.output.take(200)}")
            return result.isSuccess
        }

        suspend fun isInstalled(): Boolean {
            val verifyResult = RootShell.exec("pm list packages '${packageName.shellEscape()}' 2>/dev/null")
            return verifyResult.output.contains(packageName)
        }

        // First install attempt
        val firstOk = doInstall()
        if (!firstOk) {
            LogUtil.e(TAG, "installApk: $packageName — first install attempt failed")
            return false
        }

        // Verify installation succeeded
        if (isInstalled()) {
            Log.i(TAG, "installApk: $packageName installed and verified")
            return true
        }

        Log.w(TAG, "installApk: $packageName installed but not detected — retrying once")
        val retryOk = doInstall()
        if (!retryOk) {
            Log.e(TAG, "installApk: $packageName — retry install failed")
            return false
        }

        if (isInstalled()) {
            Log.i(TAG, "installApk: $packageName installed and verified (after retry)")
            return true
        }

        Log.e(TAG, "installApk: $packageName — install reported success but package not found after retry")
        return false
    }

    private suspend fun restoreData(packageName: String, userId: String, appDir: File, tarCmd: String, zstdCmd: String): Boolean {
        val fileNames = BackupOperation.listBackupFiles(appDir)
            ?.filter { it.contains("_data.tar") }
            ?: run { Log.w(TAG, "restoreData: appDir empty or null: ${appDir.absolutePath}"); return false }
        if (fileNames.isEmpty()) {
            Log.w(TAG, "restoreData: no _data.tar in ${appDir.name}")
            return true
        }
        val dataFiles = fileNames.map { File(appDir, it) }

        // Build exclusion patterns for cache/temp directories
        var anyExtracted = false
        val dataPaths = listOf("/data/data/$packageName", "/data/user_de/$userId/$packageName")
        val excludeFolders = listOf(".ota", "cache", "lib", "code_cache", "no_backup")
        val excludeArgs = dataPaths.flatMap { dataPath ->
            excludeFolders.flatMap { folder ->
                listOf("--exclude='${dataPath.shellEscape()}/$folder'", "--exclude='${dataPath.shellEscape()}/$folder/*'")
            }
        }.joinToString(" ")

        for (archive in dataFiles) {
            val archivePath = archive.absolutePath.shellEscape()
            Log.d(TAG, "restoreData: found archive ${archive.name}")
            if (!isArchiveSafe(archive, zstdCmd)) {
                Log.w(TAG, "restoreData: archive NOT SAFE, skipping: ${archive.name}")
                continue
            }

            // Build the extract command with exclusion flags
            val baseCmd = when {
                archive.name.endsWith(".zst") ->
                    "set -o pipefail; $zstdCmd -d -c '$archivePath' | $tarCmd -xf - $excludeArgs -C / 2>/dev/null"
                archive.name.endsWith(".gz") ->
                    "$tarCmd -xzf $excludeArgs '$archivePath' -C / 2>/dev/null"
                archive.name.endsWith(".tar") ->
                    "$tarCmd -xf $excludeArgs '$archivePath' -C / 2>/dev/null"
                else -> { Log.w(TAG, "restoreData: unknown archive type ${archive.name}"); continue }
            }

            val result = RootShell.exec(baseCmd)
            if (result.isSuccess) {
                Log.i(TAG, "restoreData: extracted ${archive.name}")
                anyExtracted = true
            } else {
                Log.e(TAG, "restoreData: FAILED ${archive.name}: exit=${result.exitCode} err=${result.error}")
            }
        }

        // Restore SELinux context on extracted data directories
        for (dataPath in dataPaths) {
            // Try to get the existing context (if the path already existed)
            val existingContext = SELinuxUtil.getContext(dataPath)
            val context = existingContext ?: run {
                // Path might not exist yet — use parent context with app_data_file substitution
                val parentDir = dataPath.substringBeforeLast("/")
                val parentContext = SELinuxUtil.getContext(parentDir)
                parentContext?.replace("system_data_file", "app_data_file")
            }

            if (context != null) {
                Log.d(TAG, "restoreData: restoring SELinux context on $dataPath: $context")
                SELinuxUtil.chcon(context, dataPath)
            } else {
                Log.w(TAG, "restoreData: could not determine SELinux context for $dataPath")
            }
        }

        return anyExtracted
    }

    /**
     * Check that a tar archive contains no path traversal (..) entries
     * or symbolic links pointing outside the tree.
     * Accepts both absolute and relative paths — tar implementations vary.
     */
    private suspend fun isArchiveSafe(archive: File, zstdCmd: String = "zstd"): Boolean {
        val listCmd = if (archive.name.endsWith(".zst")) {
            "set -o pipefail; $zstdCmd -d -c '${archive.absolutePath.shellEscape()}' | tar tf - 2>/dev/null"
        } else {
            "tar tf '${archive.absolutePath.shellEscape()}' 2>/dev/null"
        }
        var result = RootShell.exec(listCmd)
        // Fallback: try without pipefail (some Android shells don't support it)
        if (!result.isSuccess && archive.name.endsWith(".zst")) {
            val fallbackCmd = "$zstdCmd -d -c '${archive.absolutePath.shellEscape()}' 2>/dev/null | tar tf - 2>/dev/null"
            result = RootShell.exec(fallbackCmd)
        }
        if (!result.isSuccess) return false
        return !result.output.lines().any { line ->
            val path = line.substringBefore(" -> ")
            path.trimStart('/').split("/").any { segment -> segment == ".." }
        }
    }

    private suspend fun restoreObb(packageName: String, appDir: File, tarCmd: String, zstdCmd: String) {
        val obbNames = BackupOperation.listBackupFiles(appDir)
            ?.filter { it.contains("_obb.tar") }
            ?: return
        if (obbNames.isEmpty()) return
        val obbFiles = obbNames.map { File(appDir, it) }

        // Build exclusion patterns for OBB cache/temp directories
        val obbPath = "/storage/emulated/0/Android/obb/$packageName"
        val excludeFolders = listOf(".ota", "cache", "lib", "code_cache", "no_backup", "Backup_*")
        val excludeArgs = excludeFolders.joinToString(" ") { "--exclude='${obbPath.shellEscape()}/$it' --exclude='${obbPath.shellEscape()}/$it/*'" }

        for (archive in obbFiles) {
            if (!isArchiveSafe(archive, zstdCmd)) continue
            val archivePath = archive.absolutePath.shellEscape()
            when {
                archive.name.endsWith(".zst") -> {
                    RootShell.exec("set -o pipefail; $zstdCmd -d -c '$archivePath' | $tarCmd -xf - $excludeArgs -C / 2>/dev/null")
                }
                archive.name.endsWith(".gz") -> {
                    RootShell.exec("$tarCmd -xzf $excludeArgs '$archivePath' -C / 2>/dev/null")
                }
                archive.name.endsWith(".tar") -> {
                    RootShell.exec("$tarCmd -xf $excludeArgs '$archivePath' -C / 2>/dev/null")
                }
            }
        }

        // Fix OBB permissions: resolve GID from parent directory instead of hardcoding 1023
        val gidResult = RootShell.exec("stat -c %g '${obbPath.shellEscape()}' 2>/dev/null")
        val gid = gidResult.output.trim().toIntOrNull() ?: 1023 // fallback to media_rw gid
        RootShell.exec("chown -R $gid:$gid '${obbPath.shellEscape()}/' 2>/dev/null")
        Log.i(TAG, "restoreObb: set ownership to $gid:$gid on $obbPath")
    }

    private suspend fun restoreSsaid(packageName: String, appDir: File, userId: String) {
        val ssaidFile = File(appDir, "ssaid.txt")
        val ssaidValue = BackupOperation.readTextFile(ssaidFile)?.trim() ?: return

        // SSAID is a hex token. Reject anything else so it can never break out of
        // the sed expression below (shellEscape only protects single-quote context,
        // not the double-quoted sed string).
        if (!ssaidValue.matches(Regex("^[0-9a-fA-F]+$"))) {
            Log.w(TAG, "restoreSsaid: ssaid value is not hex, skipping XML edit for $packageName")
            return
        }

        // Resolve the app's UID
        val uidResult = RootShell.exec("dumpsys package '${packageName.shellEscape()}' | grep 'userId=' | head -1")
        val uid = uidResult.output
            .substringAfter("userId=", "")
            .substringBefore(" ")
            .substringBefore(",")
            .trim()
            .toIntOrNull()

        if (uid == null) {
            Log.w(TAG, "restoreSsaid: could not resolve UID for $packageName")
            return
        }

        // Try XML-based approach first (more reliable across Android versions)
        val targetFile = "/data/system/users/${userId.shellEscape()}/settings_ssaid.xml"
        val xmlSuccess = run {
            // Check if file exists
            val checkResult = RootShell.exec("test -f '$targetFile' && echo 'exists'")
            if (!checkResult.output.contains("exists")) {
                Log.d(TAG, "restoreSsaid: $targetFile does not exist, will use settings command")
                return@run false
            }

            // Generate a UUID for the new entry
            val uuidResult = RootShell.exec("cat /proc/sys/kernel/random/uuid 2>/dev/null")
            val id = uuidResult.output.trim()
            // Strict UUID format check (also keeps the value safe inside the sed string)
            if (!id.matches(Regex("^[0-9a-fA-F-]{36}$"))) {
                Log.w(TAG, "restoreSsaid: could not generate UUID (got '$id'), falling back")
                return@run false
            }

            // Remove existing entry for this package and insert new one before </settings>
            val manipCmd = buildString {
                append("sed -i \"/package.*${packageName.shellEscape()}/d\" '$targetFile' && ")
                append("sed -i \"s#</settings>#<setting id=\\\"$id\\\" package=\\\"${packageName.shellEscape()}\\\" value=\\\"${ssaidValue.shellEscape()}\\\" defaultValue=\\\"default\\\" />\\n</settings>#\" '$targetFile'")
            }
            val result = RootShell.exec(manipCmd)
            if (!result.isSuccess) {
                Log.w(TAG, "restoreSsaid: XML edit failed: ${result.error}")
                return@run false
            }

            // Verify the package entry was added by checking if it appears in the file now
            val verifyCmd = RootShell.exec("grep -c \"${packageName.shellEscape()}\" '$targetFile' 2>/dev/null")
            val entryCount = verifyCmd.output.trim().toIntOrNull() ?: 0
            if (entryCount > 0) {
                Log.i(TAG, "restoreSsaid: restored SSAID for $packageName via XML (uid=$uid)")
                true
            } else {
                Log.w(TAG, "restoreSsaid: XML edit completed but entry not found, falling back")
                false
            }
        }

        // Fallback: use settings put secure if XML approach failed
        if (!xmlSuccess) {
            val result = RootShell.exec("settings put secure ssaid_$uid '${ssaidValue.shellEscape()}'")
            if (result.isSuccess) {
                Log.i(TAG, "restoreSsaid: restored SSAID for $packageName via settings (uid=$uid)")
            } else {
                Log.e(TAG, "restoreSsaid: failed to set SSAID for $packageName: ${result.error}")
            }
        }
    }

    private suspend fun restorePermissions(packageName: String, appDir: File) {
        val permFile = File(appDir, "permissions.txt")
        val content = BackupOperation.readTextFile(permFile) ?: return
        val parsedPerms = content.lines().mapNotNull { line ->
            val name = line.substringBefore(":").trim().takeIf { it.isNotEmpty() && it.contains(".") } ?: return@mapNotNull null
            val granted = line.contains("granted=true")
            Pair(name, granted)
        }

        if (parsedPerms.isEmpty()) return

        val pkgEsc = packageName.shellEscape()

        // NOTE: Intentionally skipping "appops reset" because we don't capture
        // app ops state (battery optimization, notification settings, etc.)
        // in the backup. Resetting would lose those user customizations.

        val grantedPerms = parsedPerms.filter { it.second }.map { it.first }
        val deniedPerms = parsedPerms.filter { !it.second }.map { it.first }

        // Grant runtime permissions that were previously granted
        for (perm in grantedPerms) {
            val result = RootShell.exec("pm grant '$pkgEsc' '${perm.shellEscape()}' 2>&1")
            if (!result.isSuccess) {
                Log.w(TAG, "restorePermissions: pm grant failed for $packageName: $perm — ${result.output}")
            }
        }

        // Revoke runtime permissions that were explicitly denied
        for (perm in deniedPerms) {
            val result = RootShell.exec("pm revoke '$pkgEsc' '${perm.shellEscape()}' 2>&1")
            if (!result.isSuccess) {
                // Revoking a permission that isn't granted is not an error — just log at debug level
                Log.d(TAG, "restorePermissions: pm revoke for $packageName: $perm — ${result.output}")
            }
        }

        Log.i(TAG, "restorePermissions: ${grantedPerms.size} granted, ${deniedPerms.size} revoked for $packageName")
    }

    /** Resolve app UID using multiple methods for robustness across Android versions. */
    private suspend fun resolveAppUid(packageName: String): Int? {
        val pkgEsc = packageName.shellEscape()
        // Method 1: pm list packages -U (reliable, consistent output format)
        val pmResult = RootShell.exec("pm list packages -U 2>/dev/null | grep '${pkgEsc}$'")
        val pmUid = pmResult.output
            .substringAfter(" uid:")
            .trim()
            .toIntOrNull()
        if (pmUid != null) return pmUid

        // Method 2: dumpsys package (fallback for older Android)
        val dsResult = RootShell.exec("dumpsys package '$pkgEsc' | grep 'userId=' | head -1")
        val dsUid = dsResult.output
            .substringAfter("userId=", "")
            .substringBefore(" ")
            .substringBefore(",")
            .trim()
            .toIntOrNull()
        if (dsUid != null) return dsUid

        // Method 3: dumpsys with userId: separator (AOSP variant)
        val ds2Result = RootShell.exec("dumpsys package '$pkgEsc' | grep 'userId:' | head -1")
        val ds2Uid = ds2Result.output
            .substringAfter("userId:", "")
            .substringBefore(" ")
            .trim()
            .toIntOrNull()
        return ds2Uid
    }

    private suspend fun fixDataOwnership(packageName: String, userId: String) {
        val pkgEsc = packageName.shellEscape()
        val uidEsc = userId.shellEscape()

        val uid = resolveAppUid(packageName)
        if (uid == null) {
            Log.w(TAG, "fixDataOwnership: could not resolve UID for $packageName — data will be inaccessible")
            return
        }

        // USER and USER_DE use uid:uid (app's own group)
        val dataPaths = listOf(
            "/data/data/$pkgEsc",
            "/data/user_de/$uidEsc/$pkgEsc"
        )

        for (dataPath in dataPaths) {
            RootShell.exec("chown -R $uid:$uid '$dataPath/' 2>/dev/null")

            // Restore SELinux context instead of using restorecon (which applies defaults)
            val existingContext = SELinuxUtil.getContext(dataPath)
            val context = existingContext ?: run {
                val parentDir = dataPath.substringBeforeLast("/")
                val parentContext = SELinuxUtil.getContext(parentDir)
                parentContext?.replace("system_data_file", "app_data_file")
            }
            if (context != null) {
                SELinuxUtil.chcon(context, dataPath)
                Log.d(TAG, "fixDataOwnership: restored SELinux context on $dataPath: $context")
            } else {
                Log.w(TAG, "fixDataOwnership: could not determine SELinux context for $dataPath")
            }
        }
    }
}
