package com.example.androidbackupgui.backup.restic

import android.util.Log
import com.example.androidbackupgui.root.RootShell
import com.example.androidbackupgui.root.shellEscape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * "流式"备份——将应用数据 tar 到临时目录，然后由 restic 统一备份。
 *
 * 原实现使用 FIFO + `restic backup --stdin`，但由于 RootShell 每次 exec
 * 会独立打开/关闭 FIFO，导致 restic 在第一次写入后收到 EOF 退出。
 *
 * 当前实现改为：
 * 1. 创建临时工作目录 stream_data/
 * 2. 将元数据 + APK 文件复制到该目录
 * 3. 对每个应用，tar 数据到该目录下的独立文件
 * 4. 运行 restic backup 指向该目录（无 --stdin，无 FIFO）
 * 5. 备份完成后清理临时目录
 *
 * 和普通备份的区别：临时目录会在备份完成后自动删除，不留本地存档。
 * 仅当 [BackupConfig.useStreaming] 启用时使用。
 */
object ResticStreamBackup {
    private const val TAG = "ResticStreamBackup"

    /** 单个应用跳过备份的数据大小阈值（500MB） */
    private const val MAX_STREAM_APP_SIZE_BYTES = 500L * 1024 * 1024

    /**
     * Run a streaming backup.
     */
    suspend fun backup(
        cacheDir: File,
        ownPackageName: String,
        apps: List<AppInfo>,
        noDataBackup: Set<String>,
        legacyApps: Map<String, ResticWrapper.SnapshotAppInfo>?,
        userId: String,
        restic: ResticWrapper,
        repoPath: String,
        password: String,
        tags: List<String>,
        hostname: String?,
        backend: String,
        backendUrl: String,
        backendUser: String,
        backendPass: String,
        backendShare: String,
        onProgress: suspend (String) -> Unit = {},
    ): AppResult<ResticWrapper.BackupSummary> =
        withContext(Dispatchers.IO) {
            val emit: suspend (String) -> Unit = { msg -> withContext(Dispatchers.Main) { onProgress(msg) } }

            // ── 1. Create temporary work directory ──────
            val workDir = File(cacheDir, "stream_data")
            if (workDir.exists()) RootShell.exec("rm -rf '${workDir.absolutePath.shellEscape()}'")
            workDir.mkdirs()
            Log.i(TAG, "Work dir created at ${workDir.absolutePath}")

            try {
                // ── 2. Write metadata ─────────────────────
                // 文件直接放在 workDir 根下，与普通备份结构一致
                emit("正在准备元数据…")
                BackupOperation.writeFileForBackup(
                    File(workDir, "appList.txt"),
                    apps.joinToString("\n") { it.packageName.value },
                )
                BackupOperation.writeFileForBackup(
                    File(workDir, "app_details.json"),
                    BackupOperation.buildAppDetailsJson(apps, legacyApps),
                )
                Log.i(TAG, "Metadata written to ${workDir.absolutePath}")

                // ── 3. Backup APK files ───────────────────
                // 统一使用 per-app 子目录结构，与普通备份和恢复代码兼容
                emit("正在备份 APK 文件…")
                var apkCount = 0
                for (app in apps) {
                    if (!coroutineContext.isActive) return@withContext err(AppError.Cancelled)
                    val appDir = File(workDir, app.packageName.value)
                    appDir.mkdirs()
                    val paths = AppScanner.getApkPaths(app.packageName.value)
                    for ((i, apkPath) in paths.withIndex()) {
                        val destName = if (paths.size > 1) "${app.packageName.value}_split_$i.apk" else "${app.packageName.value}.apk"
                        val cpOk =
                            RootShell
                                .exec(
                                    "cp '${apkPath.shellEscape()}' '${File(appDir, destName).absolutePath.shellEscape()}' 2>/dev/null",
                                ).isSuccess
                        if (cpOk) apkCount++
                    }
                }
                Log.i(TAG, "Backed up $apkCount APK files")

                // ── 4. Backup app data ────────────────────
                var successCount = 0
                var failCount = 0

                for ((index, app) in apps.withIndex()) {
                    if (!coroutineContext.isActive) return@withContext err(AppError.Cancelled)

                    val pkgName = app.packageName.value
                    if (pkgName in noDataBackup) {
                        Log.d(TAG, "backup: skipping data for $pkgName (excluded)")
                        continue
                    }

                    emit("备份数据: $pkgName (${index + 1}/${apps.size})")

                    // Force-stop app before data backup for consistency
                    if (pkgName !in listOf("bin.mt.plus", "com.termux", "bin.mt.plus.canary", ownPackageName)) {
                        RootShell.exec("am force-stop --user ${userId.shellEscape()} '${pkgName.shellEscape()}' 2>/dev/null")
                    }

                    // Check data dirs exist
                    val dirs = mutableListOf<String>()
                    val dataCheck = RootShell.exec("test -d '/data/data/${pkgName.shellEscape()}' && echo 1 || echo 0")
                    if (dataCheck.output.trim() == "1") dirs.add("/data/data/$pkgName")

                    val userDeCheck =
                        RootShell.exec(
                            "test -d '/data/user_de/${userId.shellEscape()}/${pkgName.shellEscape()}' && echo 1 || echo 0",
                        )
                    if (userDeCheck.output.trim() == "1") dirs.add("/data/user_de/$userId/$pkgName")

                    if (dirs.isEmpty()) {
                        Log.d(TAG, "backup: no data dirs for $pkgName, skipping")
                        continue
                    }

                    // Estimate size, skip oversized apps
                    val dirArgs = dirs.joinToString(" ") { "'${it.shellEscape()}'" }
                    val preCheck =
                        RootShell.exec(
                            "du -sb --exclude='cache' --exclude='code_cache' --exclude='lib' --exclude='no_backup' --exclude='.ota' $dirArgs 2>/dev/null | awk '{s+=\$1} END{print s}'",
                        )
                    val estimatedBytes = preCheck.output.trim().toLongOrNull() ?: 0L
                    if (estimatedBytes > MAX_STREAM_APP_SIZE_BYTES) {
                        emit("⚠ $pkgName 数据过大 (${estimatedBytes / 1024 / 1024}MB)，跳过")
                        Log.w(TAG, "backup: $pkgName too large (${estimatedBytes / 1024 / 1024}MB), skipping")
                        continue
                    }

                    // Tar app data to per-app subdirectory
                    val appDir = File(workDir, pkgName)
                    appDir.mkdirs()
                    val tarFile = File(appDir, "${pkgName}_data.tar.zst")
                    // 使用系统 tar + 捆绑的 zstd（从 cacheDir 推导 filesDir）
                    val filesDir = File(cacheDir.parentFile, "files")
                    val zstdBin = File(File(filesDir, "bin"), "zstd_bin")
                    val zstdCmd = if (zstdBin.canExecute()) zstdBin.absolutePath else "zstd"
                    val tarCmd = "set -o pipefail; tar -cf - $dirArgs --exclude='cache' --exclude='code_cache' --exclude='lib' --exclude='no_backup' --exclude='.ota' 2>/dev/null | $zstdCmd -T0 -o '${tarFile.absolutePath.shellEscape()}'"
                    RootShell.exec("chmod +x '${zstdBin.absolutePath.shellEscape()}' 2>/dev/null")

                    val result = RootShell.exec(tarCmd)
                    if (result.isSuccess && tarFile.length() > 0) {
                        successCount++
                    } else {
                        Log.w(TAG, "backup: tar failed for $pkgName exit=${result.exitCode} err='${result.error.take(200)}'")
                        failCount++
                    }
                }

                emit("数据备份完成 (成功 $successCount, 失败 $failCount)，正在上传至 restic…")

                // ── 5. Run restic backup ──────────────────
                val args = mutableListOf("backup", "--json")
                args.add(workDir.absolutePath)
                for (tag in tags) {
                    args.add("--tag")
                    args.add(tag)
                }
                if (hostname != null) {
                    args.add("--host")
                    args.add(hostname)
                }

                val cmdArgs = restic.runner.buildCommandArgs(args)
                Log.i(TAG, "Running restic ${cmdArgs.joinToString(" ")}")

                val result =
                    restic.executor.runResticStreamingWithBackend(
                        args = args,
                        repoPath = repoPath,
                        password = password,
                        cacheDir = restic.cacheDir,
                        backend = backend,
                        backendUrl = backendUrl,
                        backendUser = backendUser,
                        backendPass = backendPass,
                        backendShare = backendShare,
                        backendDomain = restic.backendDomain,
                        runner = restic.runner,
                        envResolver = restic.envResolver,
                        bridgeRunner = restic.bridgeRunner,
                        onLine = { line ->
                            try {
                                val progress = resticJson.decodeFromString<ResticWrapper.ResticProgress>(line)
                                if (progress.messageType == "status") {
                                    val pct = "%.1f".format(progress.percentDone * 100)
                                    emit(
                                        "上传进度: $pct% (${progress.filesDone}/${progress.totalFiles} 文件, ${progress.bytesDone / 1024 / 1024}/${progress.totalBytes / 1024 / 1024}MB)",
                                    )
                                }
                            } catch (_: Exception) {
                                if (line.length < 200) emit(line)
                            }
                        },
                    )

                if (result.exitCode != 0) {
                    Log.e(TAG, "restic backup failed: exit=${result.exitCode} stderr=${result.stderr.take(500)}")
                    return@withContext err(AppError.Restic("restic 备份失败", result.exitCode, result.stderr))
                }

                // ── 6. Parse summary ─────────────────────
                val summaryLine =
                    result.stdout.lines().lastOrNull { line ->
                        line.contains("\"message_type\"") && line.contains("\"summary\"")
                    }
                val summary =
                    if (summaryLine != null) {
                        try {
                            resticJson.decodeFromString<ResticWrapper.BackupSummary>(summaryLine)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse summary: ${e.message}")
                            null
                        }
                    } else {
                        null
                    }

                if (summary == null) {
                    return@withContext err(AppError.Parse("restic 未返回摘要信息", ""))
                }

                // ── 7. Verify snapshot ───────────────────
                val snapshotId = summary.snapshotId
                emit("正在验证快照 ${snapshotId.take(8)}…")
                try {
                    restic.executor.withBackend(
                        repoPath = repoPath,
                        password = password,
                        cacheDir = restic.cacheDir,
                        backend = backend,
                        backendUrl = backendUrl,
                        backendUser = backendUser,
                        backendPass = backendPass,
                        backendShare = backendShare,
                        backendDomain = restic.backendDomain,
                        runner = restic.runner,
                        envResolver = restic.envResolver,
                        bridgeRunner = restic.bridgeRunner,
                    ) { env ->
                        val verifyResult = restic.runner.runRestic(env, "snapshots", "--json")
                        if (verifyResult.exitCode == 0 && verifyResult.stdout.contains(snapshotId)) {
                            Log.i(TAG, "backup: snapshot $snapshotId verified")
                        } else {
                            Log.w(TAG, "backup: snapshot $snapshotId NOT found in snapshots list!")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "backup: snapshot verification failed: ${e.message}")
                }

                AppResult.Success(summary)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUtil.e(TAG, "backup failed: ${e.message}")
                err(AppError.Restic("流式备份异常: ${e.message}", -1, ""))
            } finally {
                // ── 8. Cleanup ───────────────────────────
                emit("正在清理临时文件…")
                RootShell.exec("rm -rf '${workDir.absolutePath.shellEscape()}'")
                Log.i(TAG, "Work dir cleaned up")
            }
        }
}
