package com.example.androidbackupgui.backup

import android.util.Log
import com.example.androidbackupgui.root.RootShell
import com.example.androidbackupgui.root.shellEscape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlinx.serialization.json.Json
import kotlin.coroutines.coroutineContext

/**
 * Streaming backup using a FIFO (named pipe) to pipe app data tar directly
 * into `restic backup --stdin`, eliminating the staging directory.
 *
 * Only invoked when [BackupConfig.useStreaming] is enabled.
 */
object ResticStreamBackup {

    private const val TAG = "ResticStreamBackup"
    private const val TAR_TIMEOUT_MS = 120_000L

    private val resticJson = Json { ignoreUnknownKeys = true }

    /**
     * Run a streaming backup.
     */
    suspend fun backup(
        cacheDir: File,
        apps: List<AppInfo>,
        noDataBackup: Set<String>,
        legacyApps: Map<String, ResticWrapper.SnapshotAppInfo>?,
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
        onProgress: suspend (String) -> Unit = {}
    ): AppResult<ResticWrapper.BackupSummary> = withContext(Dispatchers.IO) {
        val emit: suspend (String) -> Unit = { msg -> withContext(Dispatchers.Main) { onProgress(msg) } }

        cacheDir.mkdirs()

        // ── 1. Create FIFO ────────────────────────────
        val fifo = File(cacheDir, "stream_data.fifo")
        if (fifo.exists()) RootShell.exec("rm -f '${fifo.absolutePath.shellEscape()}'")
        val mkfifoResult = RootShell.exec("mkfifo '${fifo.absolutePath.shellEscape()}'")
        if (!mkfifoResult.isSuccess) {
            LogUtil.e(TAG, "backup: mkfifo failed: ${mkfifoResult.error}")
            return@withContext err(AppError.Config("无法创建数据管道 (mkfifo)"))
        }
        Log.i(TAG, "FIFO created at ${fifo.absolutePath}")

        try {
            // ── 2. Write metadata ─────────────────────
            val metaDir = File(cacheDir, "stream_meta")
            metaDir.mkdirs()
            File(metaDir, "appList.txt").writeText(apps.joinToString("\n") { it.packageName.value })
            File(metaDir, "app_details.json").writeText(BackupOperation.buildAppDetailsJson(apps, legacyApps))
            Log.i(TAG, "Metadata written to ${metaDir.absolutePath}")

            // ── 3. Collect APK paths ──────────────────
            val apkPaths = mutableListOf<String>()
            for (app in apps) {
                if (!coroutineContext.isActive) return@withContext err(AppError.Cancelled())
                apkPaths.addAll(AppScanner.getApkPaths(app.packageName.value))
            }
            Log.i(TAG, "Collected ${apkPaths.size} APK paths")

            // ── 4. Build restic env and args ──────────
            val extraArgs = mutableListOf<String>()
            extraArgs.addAll(apkPaths)
            extraArgs.add(metaDir.absolutePath)

            val args = mutableListOf("backup", "--stdin", "--json", "--stdin-filename", "app_data.tar")
            for (path in extraArgs) args.add(path)
            for (tag in tags) { args.add("--tag"); args.add(tag) }
            if (hostname != null) { args.add("--host"); args.add(hostname) }

            val cmdArgs = restic.runner.buildCommandArgs(args)
            val env = if (backend == "local") {
                restic.envResolver.buildLocalEnv(repoPath, password, restic.cacheDir)
            } else {
                // Remote backends: need bridge. Use blocking call inside withContext(IO).
                // For now, local only; remote bridge requires async setup not compatible
                // with the coroutineScope timing below. Remote will be added later.
                LogUtil.e(TAG, "backup: remote backend not yet supported for streaming")
                return@withContext err(AppError.Config("流式备份暂不支持远程后端，请使用本地仓库"))
            }

            emit("流式备份开始 (${apps.size} 个应用)")

            // ── 5. Consumer + Producer in coroutineScope ──
            var backupSummary: ResticWrapper.BackupSummary? = null
            var backupError: AppError? = null
            var consumerDone = false

            coroutineScope {
                // Consumer: start restic, pipe stdin from FIFO, read progress
                val consumerJob = launch {
                    try {
                        Log.i(TAG, "Consumer: starting restic ${cmdArgs.joinToString(" ")}")
                        val pb = ProcessBuilder(cmdArgs)
                        pb.environment().putAll(env)
                        pb.redirectErrorStream(false)
                        val process = pb.start()

                        // Daemon thread: pipe FIFO → process stdin
                        val stdinThread = Thread {
                            try {
                                java.io.FileInputStream(fifo).use { fis ->
                                    process.outputStream.use { pos ->
                                        fis.copyTo(pos)
                                    }
                                }
                            } catch (_: Exception) {
                                // FIFO writer closed or process exited
                            }
                        }.apply { isDaemon = true; name = "restic-stdin-pipe" }
                        stdinThread.start()

                        // Read stdout line by line
                        val stdoutLines = mutableListOf<String>()
                        val reader = process.inputStream.bufferedReader()
                        try {
                            var line = reader.readLine()
                            while (line != null) {
                                if (!coroutineContext.isActive) {
                                    process.destroy()
                                    break
                                }
                                stdoutLines.add(line)
                                // Parse JSON progress line
                                try {
                                    val progress = resticJson.decodeFromString<ResticWrapper.ResticProgress>(line)
                                    if (progress.messageType == "status") {
                                        val pct = "%.1f".format(progress.percentDone * 100)
                                        emit("备份进度: $pct% (${progress.filesDone}/${progress.totalFiles} 文件)")
                                    }
                                } catch (_: Exception) {
                                    emit(line.take(120))
                                }
                                line = reader.readLine()
                            }
                        } finally {
                            try { reader.close() } catch (_: Exception) {}
                        }

                        // Drain stderr
                        val stderrBytes = try {
                            process.errorStream.use { stream ->
                                val buf = java.io.ByteArrayOutputStream()
                                val data = ByteArray(4096)
                                var n = stream.read(data)
                                while (n != -1) { buf.write(data, 0, n); n = stream.read(data) }
                                buf.toByteArray()
                            }
                        } catch (_: Exception) { byteArrayOf() }

                        val exitCode = process.waitFor()
                        try { stdinThread.join(2_000) } catch (_: InterruptedException) {}

                        val stderrText = stderrBytes.decodeToString().trim()
                        Log.i(TAG, "Consumer: restic exit=$exitCode stdout_len=${stdoutLines.size}")
                        if (stderrText.isNotEmpty()) Log.w(TAG, "Consumer: restic stderr: ${stderrText.take(500)}")

                        if (exitCode == 0) {
                            // Parse summary from stdout (last JSON line with message_type=summary)
                            val summaryLine = stdoutLines.lastOrNull { line ->
                                line.contains("\"message_type\"") && line.contains("\"summary\"")
                            }
                            if (summaryLine != null) {
                                backupSummary = try {
                                    resticJson.decodeFromString<ResticWrapper.BackupSummary>(summaryLine)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Consumer: failed to parse summary: ${e.message}")
                                    null
                                }
                            }
                            if (backupSummary == null) {
                                backupError = AppError.Parse("restic 未返回摘要信息", "")
                            }
                        } else {
                            backupError = AppError.Restic("restic backup 失败", exitCode, stderrText)
                        }
                    } catch (e: CancellationException) {
                        // CoroutineScope cancellation propagates naturally
                    } catch (e: Exception) {
                        LogUtil.e(TAG, "Consumer: exception: ${e.message}")
                        backupError = AppError.Restic("restic 进程异常: ${e.message}", -1, "")
                    } finally {
                        consumerDone = true
                    }
                }

                // Producer: tar each app → FIFO
                val producerJob = launch {
                    try {
                        // Small delay so consumer has time to start reading the FIFO
                        delay(200)

                        var appIndex = 0
                        for (app in apps) {
                            if (!coroutineContext.isActive || consumerDone) break

                            val pkgName = app.packageName.value
                            if (pkgName in noDataBackup) {
                                Log.d(TAG, "Producer: skipping data for $pkgName (excluded)")
                                appIndex++
                                continue
                            }

                            emit("备份数据: $pkgName (${appIndex + 1}/${apps.size})")

                            // Check data dirs exist
                            val dataDir = "/data/data/$pkgName"
                            val userDeDir = "/data/user_de/0/$pkgName"
                            val dirs = mutableListOf<String>()

                            val dataCheck = RootShell.exec("test -d '${dataDir.shellEscape()}' && echo 1 || echo 0")
                            if (dataCheck.output.trim() == "1") dirs.add(dataDir)

                            val userDeCheck = RootShell.exec("test -d '${userDeDir.shellEscape()}' && echo 1 || echo 0")
                            if (userDeCheck.output.trim() == "1") dirs.add(userDeDir)

                            if (dirs.isEmpty()) {
                                Log.d(TAG, "Producer: no data dirs for $pkgName, skipping")
                                appIndex++
                                continue
                            }

                            // Tar to FIFO with timeout
                            val dirArgs = dirs.joinToString(" ") { "'${it.shellEscape()}'" }
                            val cmd = "tar -cf - $dirArgs --exclude='cache' --exclude='code_cache' --exclude='lib' --exclude='no_backup' --exclude='.ota' 2>/dev/null >> '${fifo.absolutePath.shellEscape()}'"

                            try {
                                withTimeout(TAR_TIMEOUT_MS) {
                                    val result = RootShell.exec(cmd)
                                    if (!result.isSuccess) {
                                        Log.w(TAG, "Producer: tar failed for $pkgName: ${result.error}")
                                    }
                                }
                            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                                Log.w(TAG, "Producer: tar timeout for $pkgName after ${TAR_TIMEOUT_MS}ms")
                                // Consumer may have exited; check and break
                                if (consumerDone) break
                            }

                            appIndex++
                        }

                        Log.i(TAG, "Producer: completed, $appIndex apps streamed")
                    } catch (e: CancellationException) {
                        // Normal cancellation
                    } catch (e: Exception) {
                        LogUtil.e(TAG, "Producer: exception: ${e.message}")
                    }
                }

                // Wait for both to complete (producer finishes first, then consumer)
                producerJob.join()
                consumerJob.join()
            }

            // ── 6. Result ──────────────────────────────
            backupSummary?.let { summary ->
                Log.i(TAG, "backup: completed, snapshot=${summary.snapshotId}")
                AppResult.Success(summary)
            } ?: err(backupError ?: AppError.Restic("流式备份未产生结果", -1, ""))
        } finally {
            // ── 7. Cleanup ─────────────────────────────
            RootShell.exec("rm -f '${fifo.absolutePath.shellEscape()}'")
            Log.d(TAG, "FIFO cleaned up")
        }
    }
}
