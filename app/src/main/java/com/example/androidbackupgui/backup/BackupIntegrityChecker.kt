package com.example.androidbackupgui.backup

import android.util.Log
import java.io.File

/**
 * 备份完整性校验器 - 验证备份数据的完整性。
 *
 * 功能：
 * 1. 验证归档文件完整性（压缩校验 + tar 结构校验）
 * 2. 生成校验和文件
 * 3. 验证校验和
 * 4. 提供详细的校验报告
 */
object BackupIntegrityChecker {
    private const val TAG = "BackupIntegrityChecker"

    /**
     * 校验结果。
     */
    data class IntegrityCheckResult(
        val packageName: String,
        val archivePath: String,
        val compressionOk: Boolean,
        val tarStructureOk: Boolean,
        val checksumOk: Boolean,
        val checksum: String?,
        val error: String? = null,
    ) {
        val isComplete: Boolean
            get() = compressionOk && tarStructureOk && checksumOk
    }

    /**
     * 校验报告。
     */
    data class IntegrityReport(
        val totalPackages: Int,
        val checkedPackages: Int,
        val passedPackages: Int,
        val failedPackages: Int,
        val results: List<IntegrityCheckResult>,
        val elapsedTimeMs: Long,
    ) {
        val successRate: Double
            get() = if (checkedPackages > 0) passedPackages.toDouble() / checkedPackages else 0.0
    }

    /**
     * 校验单个归档文件的完整性。
     *
     * @param archivePath 归档文件路径
     * @param isZstd 是否使用 zstd 压缩
     * @param expectedChecksum 期望的校验和（可选）
     * @return IntegrityCheckResult 校验结果
     */
    suspend fun checkArchive(
        archivePath: String,
        isZstd: Boolean,
        expectedChecksum: String? = null,
    ): IntegrityCheckResult {
        val packageName = File(archivePath).nameWithoutExtension
        Log.d(TAG, "checkArchive: checking $archivePath")

        // 1. 压缩完整性检查
        val compressionOk = checkCompressionIntegrity(archivePath, isZstd)
        if (!compressionOk) {
            return IntegrityCheckResult(
                packageName = packageName,
                archivePath = archivePath,
                compressionOk = false,
                tarStructureOk = false,
                checksumOk = false,
                checksum = null,
                error = "压缩完整性检查失败",
            )
        }

        // 2. tar 结构验证
        val tarStructureOk = checkTarStructure(archivePath, isZstd)
        if (!tarStructureOk) {
            return IntegrityCheckResult(
                packageName = packageName,
                archivePath = archivePath,
                compressionOk = true,
                tarStructureOk = false,
                checksumOk = false,
                checksum = null,
                error = "tar 结构验证失败",
            )
        }

        // 3. 校验和验证
        val checksum = calculateChecksum(archivePath)
        val checksumOk = if (expectedChecksum != null) {
            checksum == expectedChecksum
        } else {
            true // 没有期望值时默认通过
        }

        return IntegrityCheckResult(
            packageName = packageName,
            archivePath = archivePath,
            compressionOk = true,
            tarStructureOk = true,
            checksumOk = checksumOk,
            checksum = checksum,
            error = if (!checksumOk) "校验和不匹配" else null,
        )
    }

    /**
     * 批量校验备份目录的完整性。
     *
     * @param backupDir 备份目录
     * @param packages 要校验的包列表
     * @param compression 压缩方式（"zstd" 或 "gzip"）
     * @return IntegrityReport 校验报告
     */
    suspend fun checkBackupIntegrity(
        backupDir: File,
        packages: List<String>,
        compression: String = "zstd",
    ): IntegrityReport {
        val startTime = System.currentTimeMillis()
        val results = mutableListOf<IntegrityCheckResult>()
        val isZstd = compression == "zstd"

        Log.i(TAG, "checkBackupIntegrity: checking ${packages.size} packages in ${backupDir.absolutePath}")

        for (pkg in packages) {
            val appDir = File(backupDir, pkg)
            if (!appDir.exists()) {
                results.add(IntegrityCheckResult(
                    packageName = pkg,
                    archivePath = appDir.absolutePath,
                    compressionOk = false,
                    tarStructureOk = false,
                    checksumOk = false,
                    checksum = null,
                    error = "备份目录不存在",
                ))
                continue
            }

            // 检查用户数据归档
            val dataArchive = findArchive(appDir, pkg, "data", isZstd)
            if (dataArchive != null) {
                val result = checkArchive(dataArchive.absolutePath, isZstd)
                results.add(result)
            }

            // 检查 OBB 归档
            val obbArchive = findArchive(appDir, pkg, "obb", isZstd)
            if (obbArchive != null) {
                val result = checkArchive(obbArchive.absolutePath, isZstd)
                results.add(result)
            }

            // 检查外部数据归档
            val extArchive = findArchive(appDir, pkg, "external_data", isZstd)
            if (extArchive != null) {
                val result = checkArchive(extArchive.absolutePath, isZstd)
                results.add(result)
            }
        }

        val elapsedTime = System.currentTimeMillis() - startTime
        val passed = results.count { it.isComplete }
        val failed = results.size - passed

        Log.i(TAG, "checkBackupIntegrity: completed in ${elapsedTime}ms, passed=$passed, failed=$failed")

        return IntegrityReport(
            totalPackages = packages.size,
            checkedPackages = results.size,
            passedPackages = passed,
            failedPackages = failed,
            results = results,
            elapsedTimeMs = elapsedTime,
        )
    }

    /**
     * 生成校验和文件。
     *
     * @param backupDir 备份目录
     * @param packages 包列表
     * @param compression 压缩方式
     * @return 是否成功
     */
    suspend fun generateChecksumFile(
        backupDir: File,
        packages: List<String>,
        compression: String = "zstd",
    ): Boolean {
        val checksumFile = File(backupDir, "checksums.sha256")
        val isZstd = compression == "zstd"
        val checksums = mutableListOf<String>()

        for (pkg in packages) {
            val appDir = File(backupDir, pkg)
            if (!appDir.exists()) continue

            // 计算数据归档校验和
            val dataArchive = findArchive(appDir, pkg, "data", isZstd)
            if (dataArchive != null) {
                val checksum = calculateChecksum(dataArchive.absolutePath)
                checksums.add("$checksum  ${dataArchive.name}")
            }

            // 计算 OBB 归档校验和
            val obbArchive = findArchive(appDir, pkg, "obb", isZstd)
            if (obbArchive != null) {
                val checksum = calculateChecksum(obbArchive.absolutePath)
                checksums.add("$checksum  ${obbArchive.name}")
            }

            // 计算外部数据归档校验和
            val extArchive = findArchive(appDir, pkg, "external_data", isZstd)
            if (extArchive != null) {
                val checksum = calculateChecksum(extArchive.absolutePath)
                checksums.add("$checksum  ${extArchive.name}")
            }
        }

        return try {
            checksumFile.writeText(checksums.joinToString("\n"))
            Log.i(TAG, "generateChecksumFile: wrote ${checksums.size} checksums to ${checksumFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "generateChecksumFile: failed", e)
            false
        }
    }

    // ── 内部实现 ─────────────────────────────────────

    /**
     * 检查压缩完整性。
     */
    private suspend fun checkCompressionIntegrity(
        archivePath: String,
        isZstd: Boolean,
    ): Boolean {
        val escapedPath = archivePath.shellEscape()
        val command = if (isZstd) {
            "zstd -t '$escapedPath' 2>/dev/null"
        } else {
            "gzip -t '$escapedPath' 2>/dev/null"
        }
        return RootShell.exec(command).isSuccess
    }

    /**
     * 检查 tar 结构。
     */
    private suspend fun checkTarStructure(
        archivePath: String,
        isZstd: Boolean,
    ): Boolean {
        val escapedPath = archivePath.shellEscape()
        val command = if (isZstd) {
            "zstd -d -c '$escapedPath' 2>/dev/null | tar -tf - > /dev/null 2>&1"
        } else {
            "tar -tf '$escapedPath' > /dev/null 2>&1"
        }
        return RootShell.exec(command).isSuccess
    }

    /**
     * 计算文件校验和。
     */
    private suspend fun calculateChecksum(filePath: String): String {
        val escapedPath = filePath.shellEscape()
        val command = "sha256sum '$escapedPath' 2>/dev/null | cut -d' ' -f1"
        val result = RootShell.exec(command)
        return if (result.isSuccess) result.output.trim() else ""
    }

    /**
     * 查找归档文件。
     */
    private fun findArchive(
        appDir: File,
        packageName: String,
        type: String,
        isZstd: Boolean,
    ): File? {
        val ext = if (isZstd) ".zst" else ".gz"
        val archive = File(appDir, "${packageName}_$type.tar$ext")
        return if (archive.exists()) archive else null
    }

    /**
     * 格式化校验报告。
     */
    fun formatReport(report: IntegrityReport): String {
        return buildString {
            appendLine("备份完整性校验报告")
            appendLine("==================")
            appendLine("总包数: ${report.totalPackages}")
            appendLine("已检查: ${report.checkedPackages}")
            appendLine("通过: ${report.passedPackages}")
            appendLine("失败: ${report.failedPackages}")
            appendLine("成功率: ${"%.1f".format(report.successRate * 100)}%")
            appendLine("耗时: ${report.elapsedTimeMs}ms")
            appendLine()

            if (report.failedPackages > 0) {
                appendLine("失败详情:")
                report.results.filter { !it.isComplete }.forEach { result ->
                    appendLine("- ${result.packageName}: ${result.error}")
                }
            }
        }
    }
}
