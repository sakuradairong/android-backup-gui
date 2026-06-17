package com.example.androidbackupgui.backup

import android.util.Log
import com.example.androidbackupgui.root.RootShell
import com.example.androidbackupgui.root.shellEscape
import java.io.File

/**
 * 单应用数据恢复子流程 - 将原 RestoreOperation 中按应用粒度的子操作抽离。
 *
 * 包括：
 * - 数据恢复 (restoreData)
 * - OBB 恢复 (restoreObb)
 * - 外部数据恢复 (restoreExternalData)
 * - SSAID 恢复 (restoreSsaid)
 * - 权限恢复 (restorePermissions)
 * - 所有权/SELinux 修复 (fixDataOwnership)
 *
 * 这些函数被 RestoreOperation.restoreApps 编排调用，本身不发起协程或调度并发。
 */
object RestoreAppDataOps {
    private const val TAG = "RestoreAppDataOps"

    /**
     * Restore data archive contents to /data/data/<pkg> and /data/user_de/<userId>/<pkg>.
     * Returns true on success (anyExtracted or no archives present).
     */
    suspend fun restoreData(
        packageName: String,
        userId: String,
        appDir: File,
        tarCmd: String,
        zstdCmd: String,
    ): Boolean {
        val fileNames =
            BackupFileIO
                .listBackupFiles(appDir)
                ?.filter { it.contains("_data.tar") }
                ?: run {
                    Log.w(TAG, "restoreData: appDir empty or null: ${appDir.absolutePath}")
                    return false
                }
        if (fileNames.isEmpty()) {
            Log.w(TAG, "restoreData: no _data.tar in ${appDir.name}")
            return true
        }
        val dataFiles = fileNames.map { File(appDir, it) }

        // 安全预检：验证目标数据目录路径合法，防止 tar -C / 写入意外位置
        val dataPaths = listOf("/data/data/$packageName", "/data/user_de/$userId/$packageName")
        for (dp in dataPaths) {
            if (!dp.startsWith("/data/")) {
                Log.e(TAG, "restoreData: REFUSING to extract to unexpected path: $dp")
                return false
            }
        }

        // Build exclusion patterns for cache/temp directories
        var anyExtracted = false
        val excludeFolders = listOf(".ota", "cache", "lib", "code_cache", "no_backup")
        val excludeArgs =
            dataPaths
                .flatMap { dataPath ->
                    excludeFolders.flatMap { folder ->
                        listOf("--exclude='${dataPath.shellEscape()}/$folder'", "--exclude='${dataPath.shellEscape()}/$folder/*'")
                    }
                }.joinToString(" ")

        for (archive in dataFiles) {
            val archivePath = archive.absolutePath.shellEscape()
            Log.d(TAG, "restoreData: found archive ${archive.name}")
            if (!RestoreArchiveSafety.isArchiveSafe(
                    archive,
                    zstdCmd,
                    additionalAllowedPrefixes = dataPaths.map { "$it/" },
                )) {
                Log.e(TAG, "restoreData: archive UNSAFE, ABORTING restore for $packageName: ${archive.name}")
                return false
            }

            // Build the extract command with exclusion flags
            val baseCmd =
                when {
                    archive.name.endsWith(".zst") -> {
                        "set -o pipefail; $zstdCmd -d -c '$archivePath' | $tarCmd -xf - $excludeArgs -C / 2>/dev/null"
                    }

                    archive.name.endsWith(".gz") -> {
                        "$tarCmd -xzf '$archivePath' $excludeArgs -C / 2>/dev/null"
                    }

                    archive.name.endsWith(".tar") -> {
                        "$tarCmd -xf '$archivePath' $excludeArgs -C / 2>/dev/null"
                    }

                    else -> {
                        Log.w(TAG, "restoreData: unknown archive type ${archive.name}")
                        continue
                    }
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
            val context =
                existingContext ?: run {
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
     * Restore OBB archive to /storage/emulated/0/Android/obb/<pkg>.
     */
    suspend fun restoreObb(
        packageName: String,
        appDir: File,
        tarCmd: String,
        zstdCmd: String,
        userId: String = "0",
    ): Boolean {
        val obbNames =
            BackupFileIO
                .listBackupFiles(appDir)
                ?.filter { it.contains("_obb.tar") }
                ?: return true
        if (obbNames.isEmpty()) return true
        val obbFiles = obbNames.map { File(appDir, it) }

        // Build exclusion patterns for OBB cache/temp directories
        val obbPath = "/storage/emulated/0/Android/obb/$packageName"
        val excludeFolders = listOf(".ota", "cache", "lib", "code_cache", "no_backup", "Backup_*")
        val excludeArgs =
            excludeFolders.joinToString(
                " ",
            ) { "--exclude='${obbPath.shellEscape()}/$it' --exclude='${obbPath.shellEscape()}/$it/*'" }

        var anyExtracted = false
        for (archive in obbFiles) {
            if (!RestoreArchiveSafety.isArchiveSafe(archive, zstdCmd, additionalAllowedPrefixes = listOf(
                    "/storage/emulated/0/Android/obb/$packageName/",
                    "/data/media/$userId/Android/obb/$packageName/",
                ))) {
                Log.e(TAG, "restoreObb: archive UNSAFE, ABORTING OBB restore for $packageName: ${archive.name}")
                return false
            }
            val archivePath = archive.absolutePath.shellEscape()
            val result =
                when {
                    archive.name.endsWith(".zst") -> {
                        RootShell.exec("set -o pipefail; $zstdCmd -d -c '$archivePath' | $tarCmd -xf - $excludeArgs -C / 2>/dev/null")
                    }

                    archive.name.endsWith(".gz") -> {
                        RootShell.exec("$tarCmd -xzf '$archivePath' $excludeArgs -C / 2>/dev/null")
                    }

                    archive.name.endsWith(".tar") -> {
                        RootShell.exec("$tarCmd -xf '$archivePath' $excludeArgs -C / 2>/dev/null")
                    }

                    else -> {
                        Log.w(TAG, "restoreObb: unknown archive type ${archive.name}")
                        continue
                    }
                }
            if (result.isSuccess) {
                Log.i(TAG, "restoreObb: extracted ${archive.name}")
                anyExtracted = true
            } else {
                Log.e(TAG, "restoreObb: FAILED ${archive.name}: exit=${result.exitCode} err=${result.error}")
            }
        }

        // Fix OBB permissions: resolve GID from parent directory instead of hardcoding 1023
        val gidResult = RootShell.exec("stat -c %g '${obbPath.shellEscape()}' 2>/dev/null")
        val gid = gidResult.output.trim().toIntOrNull() ?: 1023 // fallback to media_rw gid
        RootShell.exec("chown -R $gid:$gid '${obbPath.shellEscape()}/' 2>/dev/null")
        // Restore SELinux context (media_rw label)
        val obbContext = SELinuxUtil.getContext(obbPath.substringBeforeLast("/"))
        if (obbContext != null) {
            SELinuxUtil.chcon(obbContext, obbPath)
            Log.i(TAG, "restoreObb: restored SELinux context on $obbPath")
        }

        Log.i(TAG, "restoreObb: set ownership to $gid:$gid on $obbPath")

        return anyExtracted
    }

    /**
     * Restore external app data (/data/media/<userId>/Android/data/<pkg>).
     */
    suspend fun restoreExternalData(
        packageName: String,
        appDir: File,
        tarCmd: String,
        zstdCmd: String,
        userId: String = "0",
    ): Boolean {
        val extNames =
            BackupFileIO
                .listBackupFiles(appDir)
                ?.filter { it.contains("_external_data.tar") }
                ?: return true
        if (extNames.isEmpty()) return true

        var anyExtracted = false
        for (name in extNames) {
            val archive = File(appDir, name)
            if (!RestoreArchiveSafety.isArchiveSafe(archive, zstdCmd, additionalAllowedPrefixes = listOf(
                    "/data/media/$userId/Android/data/$packageName/",
                    "/storage/emulated/0/Android/data/$packageName/",
                ))) {
                Log.e(TAG, "restoreExternalData: archive UNSAFE, ABORTING external data restore for $packageName: $name")
                return false
            }
            val archivePath = archive.absolutePath.shellEscape()
            val result =
                when {
                    name.endsWith(".zst") -> {
                        RootShell.exec("set -o pipefail; $zstdCmd -d -c '$archivePath' | $tarCmd -xf - -C / 2>/dev/null")
                    }

                    name.endsWith(".gz") -> {
                        RootShell.exec("$tarCmd -xzf '$archivePath' -C / 2>/dev/null")
                    }

                    name.endsWith(".tar") -> {
                        RootShell.exec("$tarCmd -xf '$archivePath' -C / 2>/dev/null")
                    }

                    else -> {
                        Log.w(TAG, "restoreExternalData: unknown archive type ${archive.name}")
                        continue
                    }
                }
            if (result.isSuccess) {
                Log.i(TAG, "restoreExternalData: extracted ${archive.name}")
                anyExtracted = true
            } else {
                Log.e(TAG, "restoreExternalData: FAILED ${archive.name}: exit=${result.exitCode} err=${result.error}")
            }
        }

        // Fix ownership: same as OBB (media_rw group)
        val extPath = "/data/media/$userId/Android/data/$packageName"
        val gidResult = RootShell.exec("stat -c %g '${extPath.shellEscape()}' 2>/dev/null")
        val gid = gidResult.output.trim().toIntOrNull() ?: 1023
        RootShell.exec("chown -R $gid:$gid '${extPath.shellEscape()}/' 2>/dev/null")
        // Restore SELinux context
        val extContext = SELinuxUtil.getContext(extPath.substringBeforeLast("/"))
        if (extContext != null) {
            SELinuxUtil.chcon(extContext, extPath)
            Log.i(TAG, "restoreExternalData: restored SELinux context on $extPath")
        }

        Log.i(TAG, "restoreExternalData: set ownership to $gid:$gid on $extPath")

        return anyExtracted
    }

    /**
     * Restore SSAID for the given package.
     * - First tries XML edit of /data/system/users/<userId>/settings_ssaid.xml.
     * - Falls back to `settings put secure ssaid_<uid> <value>` if XML edit fails.
     */
    suspend fun restoreSsaid(
        packageName: String,
        appDir: File,
        userId: String,
    ) {
        // Reject package names with special characters — they cannot be valid
        // Android package names and would be unsafe in sed expressions below.
        if (!packageName.matches(Regex("^[a-zA-Z][a-zA-Z0-9._-]*(\\.[a-zA-Z][a-zA-Z0-9._-]*)+$"))) {
            Log.w(TAG, "restoreSsaid: packageName contains invalid characters, skipping: $packageName")
            return
        }

        val ssaidFile = File(appDir, "ssaid.txt")
        val ssaidValue = BackupFileIO.readTextFile(ssaidFile)?.trim() ?: return

        // SSAID is a hex token. Reject anything else so it can never break out of
        // the sed expression below (shellEscape only protects single-quote context,
        // not the double-quoted sed string).
        if (!ssaidValue.matches(Regex("^[0-9a-fA-F]+$"))) {
            Log.w(TAG, "restoreSsaid: ssaid value is not hex, skipping XML edit for $packageName")
            return
        }

        // Resolve the app's UID
        val uidResult = RootShell.exec("dumpsys package '${packageName.shellEscape()}' | grep 'userId=' | head -1")
        val uid =
            uidResult.output
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
        val xmlSuccess =
            run {
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
                val manipCmd =
                    buildString {
                        append("sed -i \"/package.*${packageName.shellEscape()}/d\" '$targetFile' && ")
                        append(
                            "sed -i \"s#</settings>#<setting id=\\\"$id\\\" package=\\\"${packageName.shellEscape()}\\\" value=\\\"${ssaidValue.shellEscape()}\\\" defaultValue=\\\"default\\\" />\\n</settings>#\" '$targetFile'",
                        )
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

    /**
     * Restore runtime permissions from the backup's permissions.txt.
     * Splits the dumpsys output into granted/denied lists and applies via `pm grant/revoke`.
     */
    suspend fun restorePermissions(
        packageName: String,
        appDir: File,
    ) {
        val permFile = File(appDir, "permissions.txt")
        val content = BackupFileIO.readTextFile(permFile) ?: return
        val parsedPerms =
            content.lines().mapNotNull { line ->
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

    /**
     * Restore ownership and SELinux context for all data paths of a package.
     * Called after data/obb/external-data restore to ensure the app can read its data.
     */
    suspend fun fixDataOwnership(
        packageName: String,
        userId: String,
        resolveUid: suspend (String) -> Int?,
    ) {
        val pkgEsc = packageName.shellEscape()
        val uidEsc = userId.shellEscape()

        val uid = resolveUid(packageName)
        if (uid == null) {
            Log.w(TAG, "fixDataOwnership: could not resolve UID for $packageName — data will be inaccessible")
            return
        }

        // USER, USER_DE, and external data paths
        val dataPaths =
            listOf(
                "/data/data/$pkgEsc",
                "/data/user_de/$uidEsc/$pkgEsc",
                "/data/media/$uidEsc/Android/data/$pkgEsc",
                "/storage/emulated/0/Android/obb/$pkgEsc",
                "/data/media/$uidEsc/Android/obb/$pkgEsc",
            )

        for (dataPath in dataPaths) {
            RootShell.exec("chown -R $uid:$uid '$dataPath/' 2>/dev/null")

            // Restore SELinux context instead of using restorecon (which applies defaults)
            val existingContext = SELinuxUtil.getContext(dataPath)
            val context =
                existingContext ?: run {
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
