package com.example.androidbackupgui.backup

import android.content.Context
import android.util.Log
import com.example.androidbackupgui.backup.scan.SsaidCache
import com.example.androidbackupgui.backup.security.BinaryResolver
import com.example.androidbackupgui.root.RootShell
import com.example.androidbackupgui.root.shellEscape
import java.io.File

/**
 * 单应用数据备份子流程 - 将原 BackupOperation 中按应用粒度的子操作抽离。
 *
 * 包括：
 * - 数据备份 (backupUserData)
 * - OBB 备份 (backupObb)
 * - 外部数据备份 (backupExternalData)
 * - SSAID 备份 (backupSsaid)
 * - 权限备份 (backupPermissions)
 * - tar 工具 (runTar)
 *
 * 这些函数被 BackupOperation.backupApps 编排调用，本身不发起协程或调度并发。
 * 抽出后，BackupOperation 的核心职责（编排 + 元数据）更加清晰。
 */
object BackupAppDataOps {
    private const val TAG = "BackupAppDataOps"

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
    suspend fun backupUserData(
        context: Context,
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

        val compressionMethod = BackupConfig.normalizeCompressionMethod(compression)
        var isZstd = compressionMethod == "zstd"
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
            BackupFileIO.backupPathExists(archiveRaw) &&
                (archiveRaw.length() > 0 || BackupFileIO.backupFileSize(archiveRaw) > 0L)

        Log.d(TAG, "backupUserData: $packageName checking dirs (tar=$tarCmd zstd=$zstdCmd)")

        val rawPkg = packageName
        val dataPaths = listOf("/data/data/$rawPkg", "/data/user_de/$userId/$rawPkg")
        val dataExcludes = listOf(".ota", "cache", "lib", "code_cache", "no_backup")

        // 1. Try direct paths after nsenter namespace switch
        var archiveCreated = false
        var result: RootShell.ShellResult? = null

        // 使用 BatchShellExecutor 合并目录检查（2次调用 → 1次）
        val dirExistsMap = com.example.androidbackupgui.root.BatchShellExecutor.checkDirsExist(dataPaths)
        val dirs = dataPaths.filter { dirExistsMap[it] == true }.toMutableList()
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
            Log.w(TAG, "backupUserData: $packageName all methods failed — no data dirs (or inaccessible)")
            return null to null
        }

        // 使用 BatchShellExecutor 合并验证（2次调用 → 1次）
        val archivePath = if (isZstd) "$outputFile.zst" else "$outputFile.gz"
        val (compressOk, tarOk) = com.example.androidbackupgui.root.BatchShellExecutor.verifyArchive(archivePath, isZstd)

        if (!compressOk) {
            Log.e(TAG, "backupUserData: $packageName compression integrity check FAILED")
            return null to null
        }

        if (!tarOk) {
            Log.e(TAG, "backupUserData: $packageName tar archive structure validation FAILED")
            return null to null
        }

        return archiveRaw.length() to 0L // Return (userSize, userDeSize) — combined in one file
    }

    /**
     * 运行 tar 命令，自动选择 zstd 或 gzip 压缩。
     */
    suspend fun runTar(
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
            RootShell.exec("$tarCmd -czf '$outputFile.gz' $excludeArgs ${dirs.joinToString(" ") { "'${it.shellEscape()}'" }} 2>/dev/null")
        }
    }

    /**
     * 备份单个应用的 OBB 数据文件夹。
     * @return obbSize 或 null（失败时）
     */
    suspend fun backupObb(
        packageName: String,
        appDir: File,
        compression: String,
    ): Long? {
        val obbDir = "/storage/emulated/0/Android/obb/${packageName.shellEscape()}"
        val escapedAppDir = appDir.absolutePath.shellEscape()
        val escapedPkg = packageName.shellEscape()
        // Exclude cache and backup temp files from OBB archive
        val obbExcludes = "--exclude='cache' --exclude='Backup_*'"
        val compressionMethod = BackupConfig.normalizeCompressionMethod(compression)
        val result =
            when (compressionMethod) {
                "zstd" -> {
                    RootShell.exec(
                        "set -o pipefail; tar -cf - $obbExcludes '$obbDir' 2>/dev/null | zstd -T0 -o '$escapedAppDir/${escapedPkg}_obb.tar.zst'",
                    )
                }

                else -> {
                    RootShell.exec("tar -czf '$escapedAppDir/${escapedPkg}_obb.tar.gz' $obbExcludes '$obbDir' 2>/dev/null")
                }
            }
        if (!result.isSuccess) {
            Log.e(TAG, "Failed to backup OBB for $packageName: exit=${result.exitCode} err=${result.error}")
            return null
        }
        val obbArchiveExt = if (compressionMethod == "zstd") ".zst" else ".gz"
        val obbFile = File(appDir, "${packageName}_obb.tar$obbArchiveExt")
        val obbArchivePath = obbFile.absolutePath.shellEscape()
        val verifyCmd = if (compressionMethod == "zstd") "zstd -t '$obbArchivePath' 2>/dev/null" else "gzip -t '$obbArchivePath' 2>/dev/null"
        val verificationOk = RootShell.exec(verifyCmd).isSuccess
        if (!verificationOk) {
            Log.e(TAG, "OBB archive integrity check FAILED for $packageName")
        }
        // Validate OBB tar structure
        val tarListCmd =
            if (compressionMethod == "zstd") {
                "zstd -d -c '$obbArchivePath' 2>/dev/null | tar -tf - > /dev/null 2>&1"
            } else {
                "tar -tf '$obbArchivePath' > /dev/null 2>&1"
            }
        val tarOk = RootShell.exec(tarListCmd).isSuccess
        if (!tarOk) {
            Log.e(TAG, "OBB tar structure validation FAILED for $packageName")
        }
        return if (verificationOk && tarOk) BackupFileIO.backupFileSize(obbFile) else null
    }

    /**
     * 备份单个应用的外部数据目录（/data/media/<userId>/Android/data/<pkg>）。
     * @return dataSize 或 null（目录不存在或失败）
     */
    suspend fun backupExternalData(
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

        val compressionMethod = BackupConfig.normalizeCompressionMethod(compression)
        val archiveExt = if (compressionMethod == "zstd") ".zst" else ".gz"
        val archiveFile = File(appDir, "${packageName}_external_data.tar$archiveExt")
        val archivePath = archiveFile.absolutePath.shellEscape()
        val dataExcludes = "--exclude='cache' --exclude='Backup_*' --exclude='.ota'"

        val result =
            if (compressionMethod == "zstd") {
                RootShell.exec(
                    "set -o pipefail; tar -cf - $dataExcludes '$externalDataDir' 2>/dev/null | zstd -T0 -o '$archivePath'",
                )
            } else {
                RootShell.exec("tar -czf '$archivePath' $dataExcludes '$externalDataDir' 2>/dev/null")
            }

        if (!result.isSuccess) {
            Log.w(TAG, "backupExternalData: $packageName tar failed: ${result.error}")
            return null
        }

        // Verify compression integrity
        val verifyCmd = if (compressionMethod == "zstd") "zstd -t '$archivePath' 2>/dev/null" else "gzip -t '$archivePath' 2>/dev/null"
        val verificationOk = RootShell.exec(verifyCmd).isSuccess
        if (!verificationOk) {
            Log.e(TAG, "backupExternalData: $packageName integrity check FAILED")
            return null
        }

        // Validate tar structure
        val tarListCmd =
            if (compressionMethod == "zstd") {
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
        return BackupFileIO.backupFileSize(archiveFile)
    }

    /**
     * 备份单个应用的 SSAID（设置安全标识符）。
     * 使用 SsaidCache 避免重复读取整个 XML 文件。
     */
    suspend fun backupSsaid(
        packageName: String,
        appDir: File,
        userId: String,
        ssaidCache: SsaidCache? = null,
    ) {
        // 优先使用缓存，如果缓存为空则回退到直接读取
        val value = ssaidCache?.getSsaid(packageName) ?: run {
            // 回退到直接读取（兼容旧逻辑）
            val ssaidFile = "/data/system/users/${userId.shellEscape()}/settings_ssaid.xml"
            val result = RootShell.exec("cat '$ssaidFile' 2>/dev/null")
            if (!result.isSuccess || result.output.isBlank()) return
            result.output.lines().firstOrNull { line ->
                line.contains("packageName=\"$packageName\"") || line.contains("packageName='$packageName'")
            }?.substringAfter("value=\"")
                ?.substringBefore("\"")
                ?.takeIf { it.isNotBlank() }
        }

        if (value != null) {
            val ssaidFile = File(appDir, "ssaid.txt")
            if (!BackupFileIO.writeFileForBackup(ssaidFile, value)) {
                Log.w(TAG, "backupSsaid: failed to write ssaid.txt for $packageName")
            } else {
                Log.d(TAG, "backupSsaid: backed up SSAID for $packageName = $value")
            }
        }
    }

    /**
     * 备份单个应用的运行时权限状态。
     */
    suspend fun backupPermissions(
        packageName: String,
        appDir: File,
    ) {
        val result = RootShell.exec("dumpsys package '${packageName.shellEscape()}' | grep -E 'granted=(true|false)'")
        if (result.output.isNotBlank()) {
            val permFile = File(appDir, "permissions.txt")
            if (!BackupFileIO.writeFileForBackup(permFile, result.output)) {
                Log.w(TAG, "backupPermissions: failed to write permissions.txt for $packageName")
            }
        }
    }
}
