package com.example.androidbackupgui.backup

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Wraps the restic CLI binary for backup/restore operations.
 *
 * Uses environment variables (RESTIC_REPOSITORY, RESTIC_PASSWORD) rather than
 * command-line flags to avoid leaking secrets in the process list.
 *
 * For SMB/WebDAV backends, restic runs against a local temp directory;
 * RemoteTransport syncs files to/from the remote backend.
 *
 * All public methods are suspend and run on Dispatchers.IO.
 */
object ResticWrapper {

    private const val TAG = "ResticWrapper"

    /** Path to the restic binary. Default assumes it's on PATH (e.g. Termux). */
    var binaryPath: String = "restic"

    /** Local temp directory used as restic repo for SMB/WebDAV backends. */
    var tempRepoDir: String = ""

    // ── Transport cache ──────────────────────────────────
    @Volatile private var transport: RemoteTransport? = null
    private var transportConfigKey: String = ""
    private val transportLock = Any()

    /** Serializes access to tempRepoDir so concurrent operations don't corrupt each other. */
    private val repoSyncMutex = Mutex()

    // ── Progress data ──────────────────────────────────

    data class ResticProgress(
        val messageType: String,       // "status" during backup
        val percentDone: Double = 0.0,
        val totalFiles: Int = 0,
        val filesDone: Int = 0,
        val totalBytes: Long = 0,
        val bytesDone: Long = 0,
        val currentFiles: List<String> = emptyList()
    )

    data class ResticSnapshot(
        val id: String,
        val shortId: String,
        val time: String,
        val paths: List<String>,
        val tags: List<String>,
        val hostname: String = ""
    )

    // ── Repository lifecycle ───────────────────────────

    suspend fun init(
        repoPath: String,
        password: String,
        backend: String = "local",
        backendUrl: String = "",
        backendUser: String = "",
        backendPass: String = "",
        backendShare: String = ""
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            withRemoteSync(backend, backendUrl, backendUser, backendPass, backendShare, repoPath,
                needsDownload = true, needsUpload = true
            ) {
                val env = buildFullEnv(repoPath, password, backend, backendUrl, backendUser, backendPass, backendShare)
                val result = runRestic(env, "init")
                // exitCode 0 = brand new repo created, needs upload
                if (result.exitCode == 0) {
                    return@withRemoteSync Result.success(Unit)
                }
                // exitCode 1 = config already exists; verify the repo is actually usable
                if (result.exitCode == 1) {
                    val verify = runRestic(env, "snapshots", "--json")
                    if (verify.exitCode == 0) {
                        // Repo is healthy — already initialized with matching password
                        Log.i(TAG, "init: repo already initialized and verified")
                        return@withRemoteSync Result.success(Unit)
                    }
                    // Config exists but repo is corrupted (wrong password, missing keys, etc.)
                    return@withRemoteSync Result.failure(
                        Exception("仓库已存在但无法验证: ${verify.stderr.ifEmpty { "密码错误或密钥缺失" }}。请删除远端仓库后重试。")
                    )
                }
                Result.failure(Exception("restic init failed: ${result.stderr}"))
            }
        }

    // ── Backup ─────────────────────────────────────────

    data class BackupSummary(
        val snapshotId: String,
        val filesNew: Int,
        val filesChanged: Int,
        val filesUnmodified: Int,
        val dirsNew: Int,
        val dirsChanged: Int,
        val dirsUnmodified: Int,
        val dataBlobs: Int,
        val treeBlobs: Int,
        val dataAdded: Long,
        val totalFilesProcessed: Int,
        val totalBytesProcessed: Long,
        val totalDuration: Double
    )

    suspend fun backup(
        repoPath: String,
        password: String,
        paths: List<String>,
        tags: List<String> = emptyList(),
        hostname: String? = null,
        backend: String = "local",
        backendUrl: String = "",
        backendUser: String = "",
        backendPass: String = "",
        backendShare: String = "",
        onProgress: suspend (ResticProgress) -> Unit = {}
    ): Result<BackupSummary> = withContext(Dispatchers.IO) {
        val emit: suspend (ResticProgress) -> Unit = { p -> withContext(Dispatchers.Main) { onProgress(p) } }

        withRemoteSync(backend, backendUrl, backendUser, backendPass, backendShare, repoPath,
            needsDownload = true, needsUpload = true
        ) {
            val args = mutableListOf("backup", "--json")
            for (path in paths) args.add(path)
            for (tag in tags) { args.add("--tag"); args.add(tag) }
            if (hostname != null) { args.add("--host"); args.add(hostname) }

            val env = buildFullEnv(repoPath, password, backend, backendUrl, backendUser, backendPass, backendShare)
            val result = runResticStreaming(env, args) { line ->
                if (!coroutineContext.isActive) return@runResticStreaming
                try {
                    val json = JSONObject(line)
                    val msgType = json.optString("message_type", "")
                    if (msgType == "status") {
                        val currentFiles = mutableListOf<String>()
                        json.optJSONArray("current_files")?.let { arr ->
                            for (i in 0 until arr.length()) currentFiles.add(arr.getString(i))
                        }
                        emit(ResticProgress(
                            messageType = "status",
                            percentDone = json.optDouble("percent_done", 0.0),
                            totalFiles = json.optInt("total_files", 0),
                            filesDone = json.optInt("files_done", 0),
                            totalBytes = json.optLong("total_bytes", 0),
                            bytesDone = json.optLong("bytes_done", 0),
                            currentFiles = currentFiles
                        ))
                    }
                } catch (_: Exception) { /* ignore non-JSON lines */ }
            }

            if (result.exitCode != 0) {
                return@withRemoteSync Result.failure(Exception("restic backup failed: ${result.stderr}"))
            }

            parseBackupSummary(result.stdout)
        }
    }

    // ── Restore ────────────────────────────────────────

    suspend fun restore(
        repoPath: String,
        password: String,
        snapshotId: String,
        targetPath: String,
        include: String? = null,
        backend: String = "local",
        backendUrl: String = "",
        backendUser: String = "",
        backendPass: String = "",
        backendShare: String = "",
        onProgress: suspend (String) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val emit: suspend (String) -> Unit = { s -> withContext(Dispatchers.Main) { onProgress(s) } }

        withRemoteSync(backend, backendUrl, backendUser, backendPass, backendShare, repoPath,
            needsDownload = true, needsUpload = false
        ) {
            File(targetPath).mkdirs()

            val args = mutableListOf("restore", snapshotId, "--target", targetPath, "--json")
            if (include != null) { args.add("--include"); args.add(include) }

            val env = buildFullEnv(repoPath, password, backend, backendUrl, backendUser, backendPass, backendShare)
            val result = runResticStreaming(env, args) { line ->
                if (!coroutineContext.isActive) return@runResticStreaming
                try {
                    val json = JSONObject(line)
                    val msgType = json.optString("message_type", "")
                    if (msgType == "status") {
                        val percent = "%.1f".format(json.optDouble("percent_done", 0.0) * 100)
                        emit("恢复进度: $percent%")
                    } else if (msgType == "summary") {
                        emit("恢复完成: ${json.optInt("total_files", 0)} 个文件")
                    }
                } catch (_: Exception) { emit(line) }
            }

            if (result.exitCode == 0) Result.success(Unit)
            else Result.failure(Exception("restic restore failed: ${result.stderr}"))
        }
    }

    // ── File dump ──────────────────────────────────────

    suspend fun dump(
        repoPath: String,
        password: String,
        snapshotId: String,
        filePath: String,
        backend: String = "local",
        backendUrl: String = "",
        backendUser: String = "",
        backendPass: String = "",
        backendShare: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        withRemoteSync(backend, backendUrl, backendUser, backendPass, backendShare, repoPath,
            needsDownload = true, needsUpload = false
        ) {
            val env = buildFullEnv(repoPath, password, backend, backendUrl, backendUser, backendPass, backendShare)
            val result = runRestic(env, "dump", snapshotId, filePath)
            if (result.exitCode == 0) Result.success(result.stdout)
            else Result.failure(Exception(result.stderr.ifEmpty { "restic dump failed with exit code ${result.exitCode}" }))
        }
    }

    // ── Snapshot listing ───────────────────────────────

    suspend fun listSnapshots(
        repoPath: String,
        password: String,
        tag: String? = null,
        backend: String = "local",
        backendUrl: String = "",
        backendUser: String = "",
        backendPass: String = "",
        backendShare: String = ""
    ): Result<List<ResticSnapshot>> = withContext(Dispatchers.IO) {
        withRemoteSync(backend, backendUrl, backendUser, backendPass, backendShare, repoPath,
            needsDownload = true, needsUpload = false
        ) {
            val args = mutableListOf("snapshots", "--json")
            if (tag != null) { args.add("--tag"); args.add(tag) }

            val env = buildFullEnv(repoPath, password, backend, backendUrl, backendUser, backendPass, backendShare)
            val result = runRestic(env, args)

            if (result.exitCode != 0) {
                return@withRemoteSync Result.failure(Exception("restic snapshots failed: ${result.stderr}"))
            }

            try {
                val jsonArray = JSONArray(result.stdout.ifEmpty { "[]" })
                val snapshots = (0 until jsonArray.length()).map { i ->
                    val obj = jsonArray.getJSONObject(i)
                    val pathsArr: JSONArray? = obj.optJSONArray("paths")
                    val tagsArr: JSONArray? = obj.optJSONArray("tags")
                    val pathsList: List<String> = if (pathsArr != null)
                        (0 until pathsArr.length()).map { j -> pathsArr.getString(j) }
                    else emptyList()
                    val tagsList: List<String> = if (tagsArr != null)
                        (0 until tagsArr.length()).map { j -> tagsArr.getString(j) }
                    else emptyList()
                    ResticSnapshot(
                        id = obj.optString("id", ""),
                        shortId = obj.optString("short_id", ""),
                        time = obj.optString("time", ""),
                        paths = pathsList,
                        tags = tagsList,
                        hostname = obj.optString("hostname", "")
                    )
                }
                Result.success(snapshots.sortedByDescending { it.time })
            } catch (e: Exception) {
                Result.failure(Exception("Failed to parse snapshot JSON: ${e.message}"))
            }
        }
    }

    // ── Maintenance ────────────────────────────────────

    suspend fun forget(
        repoPath: String,
        password: String,
        keepDaily: Int = 7,
        keepWeekly: Int = 4,
        keepMonthly: Int = 3,
        dryRun: Boolean = false,
        backend: String = "local",
        backendUrl: String = "",
        backendUser: String = "",
        backendPass: String = "",
        backendShare: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        withRemoteSync(backend, backendUrl, backendUser, backendPass, backendShare, repoPath,
            needsDownload = true, needsUpload = true
        ) {
            val args = mutableListOf(
                "forget",
                "--keep-daily", keepDaily.toString(),
                "--keep-weekly", keepWeekly.toString(),
                "--keep-monthly", keepMonthly.toString()
            )
            if (dryRun) args.add("--dry-run")

            val env = buildFullEnv(repoPath, password, backend, backendUrl, backendUser, backendPass, backendShare)
            val result = runRestic(env, args)

            if (result.exitCode == 0) Result.success(result.stdout)
            else Result.failure(Exception("restic forget failed: ${result.stderr}"))
        }
    }

    suspend fun prune(
        repoPath: String,
        password: String,
        backend: String = "local",
        backendUrl: String = "",
        backendUser: String = "",
        backendPass: String = "",
        backendShare: String = ""
    ): Result<String> =
        withContext(Dispatchers.IO) {
            withRemoteSync(backend, backendUrl, backendUser, backendPass, backendShare, repoPath,
                needsDownload = true, needsUpload = true
            ) {
                val env = buildFullEnv(repoPath, password, backend, backendUrl, backendUser, backendPass, backendShare)
                val result = runRestic(env, "prune")
                if (result.exitCode == 0) Result.success(result.stdout)
                else Result.failure(Exception("restic prune failed: ${result.stderr}"))
            }
        }

    suspend fun check(
        repoPath: String,
        password: String,
        backend: String = "local",
        backendUrl: String = "",
        backendUser: String = "",
        backendPass: String = "",
        backendShare: String = ""
    ): Result<String> =
        withContext(Dispatchers.IO) {
            withRemoteSync(backend, backendUrl, backendUser, backendPass, backendShare, repoPath,
                needsDownload = true, needsUpload = false
            ) {
                val env = buildFullEnv(repoPath, password, backend, backendUrl, backendUser, backendPass, backendShare)
                val result = runRestic(env, "check")
                if (result.exitCode == 0) Result.success(result.stdout)
                else Result.failure(Exception("restic check failed: ${result.stderr}"))
            }
        }

    suspend fun stats(
        repoPath: String,
        password: String,
        backend: String = "local",
        backendUrl: String = "",
        backendUser: String = "",
        backendPass: String = "",
        backendShare: String = ""
    ): Result<String> =
        withContext(Dispatchers.IO) {
            withRemoteSync(backend, backendUrl, backendUser, backendPass, backendShare, repoPath,
                needsDownload = true, needsUpload = false
            ) {
                val env = buildFullEnv(repoPath, password, backend, backendUrl, backendUser, backendPass, backendShare)
                val result = runRestic(env, "stats")
                if (result.exitCode == 0) Result.success(result.stdout)
                else Result.failure(Exception("restic stats failed: ${result.stderr}"))
            }
        }

    // ── Internal helpers ───────────────────────────────

    /** Clean up local temp repo and cache directories. */
    private fun cleanupTempDirs() {
        if (tempRepoDir.isEmpty()) return
        try {
            val repoDir = File(tempRepoDir)
            if (repoDir.exists()) {
                val deleted = repoDir.deleteRecursively()
                Log.i(TAG, "cleanupTempDirs: deleted $tempRepoDir ($deleted)")
            }
            val cacheDir = File(tempRepoDir.substringBeforeLast("/") + "/restic_cache")
            if (cacheDir.exists()) {
                val deleted = cacheDir.deleteRecursively()
                Log.i(TAG, "cleanupTempDirs: deleted cache $cacheDir ($deleted)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "cleanupTempDirs failed: ${e.message}")
        }
    }

    /** True if [tempRepoDir] already contains an initialized restic repository (has a config file). */
    private fun isLocalRepoPopulated(): Boolean {
        if (tempRepoDir.isEmpty()) return false
        return File(tempRepoDir, "config").isFile
    }

    /**
     * Public safety-net cleanup called by fragment lifecycle.
     * Waits for any in-progress operation to finish, then deletes temp dirs.
     */
    suspend fun cleanup() {
        repoSyncMutex.withLock { cleanupTempDirs() }
    }

    /** Build the full command list to run restic. */
    fun buildCommandArgs(args: List<String>): List<String> {
        val cmd = listOf(binaryPath) + args
        Log.d(TAG, "buildCommandArgs: binaryPath=$binaryPath args=$args → cmd=$cmd")
        return cmd
    }

    /** Build environment for restic. For SMB/WebDAV backends, uses local temp dir as repo. */
    fun buildFullEnv(
        repoPath: String,
        password: String,
        backend: String = "local",
        backendUrl: String = "",
        backendUser: String = "",
        backendPass: String = "",
        backendShare: String = ""
    ): Map<String, String> {
        val env = HashMap(System.getenv() ?: emptyMap())
        env["RESTIC_REPOSITORY"] = if (backend == "smb" || backend == "webdav") {
            tempRepoDir
        } else {
            buildRepoUrl(backend, repoPath, backendUrl)
        }
        env["RESTIC_PASSWORD"] = password
        // Provide a cache directory on Android (no $HOME by default)
        if (tempRepoDir.isNotEmpty()) {
            val cacheDir = tempRepoDir.substringBeforeLast("/") + "/restic_cache"
            env["HOME"] = cacheDir
            env["XDG_CACHE_HOME"] = cacheDir
        }
        return env
    }

    /** Build a display-friendly repository URL for UI. */
    fun buildRepoUrl(backend: String, repoPath: String, backendUrl: String): String {
        return when (backend) {
            "local" -> repoPath
            "rest-server" -> "rest:${backendUrl.trimEnd('/')}/$repoPath"
            "webdav" -> "${backendUrl.trimEnd('/')}/$repoPath"
            "smb" -> "smb:${backendUrl.trimEnd('/')}/$repoPath"
            else -> repoPath
        }
    }

    // ── Remote sync helpers ────────────────────────────

    private fun ensureTransport(
        backend: String, url: String, user: String, pass: String, share: String, repoPath: String
    ): RemoteTransport? = synchronized(transportLock) {
        val key = "$backend|$url|$user|$pass|$share|$repoPath"
        if (key != transportConfigKey || transport == null) {
            transport?.let { Log.i(TAG, "transport config changed ($transportConfigKey -> $key), recreating") }
            // Clear local temp repo when backend config changes so
            // syncFromRemote downloads fresh data from the new backend
            if (transportConfigKey.isNotEmpty() && tempRepoDir.isNotEmpty()) {
                val dir = File(tempRepoDir)
                val deleted = dir.deleteRecursively()
                Log.i(TAG, "cleared local temp repo: $tempRepoDir (deleted=$deleted)")
                dir.mkdirs()
            }
            transport = RemoteTransport.create(backend, url, user, pass, share)
            if (transport != null) {
                transportConfigKey = key
                Log.i(TAG, "transport created: $backend @ $url repo=$repoPath")
            } else {
                Log.e(TAG, "transport creation failed for backend=$backend url=$url")
            }
        }
        return transport
    }

    /**
     * Execute [action] with remote repo synced before/after as needed.
     * For local/rest-server backends, executes [action] directly without sync.
     * Protected by [repoSyncMutex] so concurrent operations don't corrupt tempRepoDir.
     *
     * Cleanup strategy:
     * - Write ops (needsUpload=true): cleanup on success (synced to remote) or failure.
     * - Read-only ops (needsUpload=false): keep local cache for subsequent operations.
     * - Read-only ops skip download entirely if local repo is already populated.
     */
    private suspend fun <T> withRemoteSync(
        backend: String,
        backendUrl: String,
        backendUser: String,
        backendPass: String,
        backendShare: String,
        repoPath: String,
        needsDownload: Boolean,
        needsUpload: Boolean,
        action: suspend () -> Result<T>
    ): Result<T> {
        if (backend != "smb" && backend != "webdav") return action()

        return repoSyncMutex.withLock {
            var shouldCleanup = false
            try {
                val t = ensureTransport(backend, backendUrl, backendUser, backendPass, backendShare, repoPath)
                    ?: return@withLock Result.failure(Exception("Failed to create transport for backend: $backend"))

                val localDir = File(tempRepoDir)

                // Write ops always download to avoid overwriting remote changes.
                // Read-only ops skip download if local repo is already present.
                val actualDownload = needsDownload && (needsUpload || !isLocalRepoPopulated())
                if (actualDownload) {
                    Log.i(TAG, "syncFromRemote start: $repoPath -> $tempRepoDir")
                    val syncResult = RemoteTransport.syncFromRemote(t, localDir, repoPath)
                    if (syncResult.isFailure) {
                        shouldCleanup = true
                        Log.e(TAG, "syncFromRemote FAILED: ${syncResult.exceptionOrNull()?.message}")
                        return@withLock Result.failure(
                            Exception("syncFromRemote failed: ${syncResult.exceptionOrNull()?.message}")
                        )
                    }
                    Log.i(TAG, "syncFromRemote complete")
                } else if (needsDownload) {
                    Log.i(TAG, "syncFromRemote skipped: local repo already populated")
                }

                val result = action()

                if (needsUpload && result.isSuccess) {
                    Log.i(TAG, "syncToRemote start: $tempRepoDir -> $repoPath")
                    val uploadResult = RemoteTransport.syncToRemote(t, localDir, repoPath)
                    if (uploadResult.isFailure) {
                        shouldCleanup = true
                        Log.e(TAG, "syncToRemote FAILED: ${uploadResult.exceptionOrNull()?.message}")
                        return@withLock Result.failure(
                            Exception("syncToRemote failed: ${uploadResult.exceptionOrNull()?.message}")
                        )
                    }
                    Log.i(TAG, "syncToRemote complete")
                    shouldCleanup = true
                } else if (result.isFailure) {
                    shouldCleanup = true
                }

                result
            } catch (e: kotlinx.coroutines.CancellationException) {
                shouldCleanup = true
                throw e
            } catch (e: Exception) {
                shouldCleanup = true
                Result.failure(e)
            } finally {
                if (shouldCleanup) {
                    Log.i(TAG, "withRemoteSync: cleaning up temp dirs")
                    cleanupTempDirs()
                } else {
                    Log.d(TAG, "withRemoteSync: keeping local repo for subsequent ops")
                }
            }
        }
    }

    data class CommandResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int
    )

    /** Run restic (non-streaming). */
    private fun runRestic(env: Map<String, String>, args: List<String>): CommandResult {
        val cmdArgs = buildCommandArgs(args)
        Log.i(TAG, "runRestic cmd=${cmdArgs.joinToString(" ")}")
        Log.d(TAG, "runRestic REPOSITORY=${env["RESTIC_REPOSITORY"]}")

        return try {
            val pb = ProcessBuilder(cmdArgs)
            pb.environment().putAll(env)
            pb.redirectErrorStream(false)
            val process = pb.start()

            val stderrText = StringBuilder()
            val stderrThread = Thread({
                try {
                    process.errorStream.bufferedReader().use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            Log.d(TAG, "restic stderr: $line")
                            stderrText.appendLine(line)
                        }
                    }
                } catch (_: Exception) {}
            }, "restic-stderr").apply { isDaemon = true; start() }

            val stdout = process.inputStream.bufferedReader().use(BufferedReader::readText)
            val exitCode = process.waitFor()
            stderrThread.join(5000)
            Log.i(TAG, "runRestic exitCode=$exitCode stdout_len=${stdout.length}")
            if (stderrText.isNotEmpty()) Log.w(TAG, "runRestic stderr: ${stderrText}")
            CommandResult(stdout.trim(), stderrText.toString().trim(), exitCode)
        } catch (e: Exception) {
            Log.e(TAG, "runRestic exception", e)
            CommandResult("", e.message ?: "Unknown error", -1)
        }
    }

    /** Run restic with single-string args. */
    private fun runRestic(env: Map<String, String>, vararg args: String): CommandResult {
        return runRestic(env, args.toList())
    }

    /** Run restic, calling onLine for each stdout line (for streaming progress). */
    private suspend fun runResticStreaming(
        env: Map<String, String>,
        args: List<String>,
        onLine: suspend (String) -> Unit
    ): CommandResult = withContext(Dispatchers.IO) {
        val cmdArgs = buildCommandArgs(args)
        Log.i(TAG, "runResticStreaming cmd=${cmdArgs.joinToString(" ")}")
        Log.d(TAG, "runResticStreaming REPOSITORY=${env["RESTIC_REPOSITORY"]}")

        var process: Process? = null
        try {
            val pb = ProcessBuilder(cmdArgs)
            pb.environment().putAll(env)
            pb.redirectErrorStream(false)
            process = pb.start()

            val stdoutText = StringBuilder()
            val reader = process.inputStream.bufferedReader()
            val stderrReader = process.errorStream.bufferedReader()

            val stderrText = StringBuilder()
            val stderrThread = Thread({
                try { stderrReader.use { stderrText.append(it.readText()) } } catch (_: Exception) {}
            }, "restic-stderr").apply { isDaemon = true; start() }

            try {
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (!coroutineContext.isActive) {
                        process.destroy()
                        break
                    }
                    val l = line!!
                    stdoutText.appendLine(l)
                    onLine(l)
                }
            } finally {
                try { reader.close() } catch (_: Exception) {}
            }

            stderrThread.join(5000)
            val exitCode = try { process.waitFor() } catch (_: Exception) { -1 }

            Log.i(TAG, "runResticStreaming exitCode=$exitCode stdout_len=${stdoutText.length}")
            if (stderrText.isNotEmpty()) Log.w(TAG, "runResticStreaming stderr: ${stderrText}")
            CommandResult(stdoutText.toString().trim(), stderrText.toString().trim(), exitCode)
        } catch (e: Exception) {
            Log.e(TAG, "runResticStreaming exception", e)
            try { process?.destroy() } catch (_: Exception) {}
            CommandResult("", e.message ?: "Unknown error", -1)
        }
    }

    /** Parse the JSON summary from the end of restic backup output. */
    private fun parseBackupSummary(stdout: String): Result<BackupSummary> {
        val lines = stdout.lines()
        for (i in lines.indices.reversed()) {
            val line = lines[i].trim()
            if (!line.startsWith("{")) continue
            try {
                val json = JSONObject(line)
                if (json.optString("message_type", "") == "summary") {
                    return Result.success(BackupSummary(
                        snapshotId = json.optString("snapshot_id", ""),
                        filesNew = json.optInt("files_new", 0),
                        filesChanged = json.optInt("files_changed", 0),
                        filesUnmodified = json.optInt("files_unmodified", 0),
                        dirsNew = json.optInt("dirs_new", 0),
                        dirsChanged = json.optInt("dirs_changed", 0),
                        dirsUnmodified = json.optInt("dirs_unmodified", 0),
                        dataBlobs = json.optInt("data_blobs", 0),
                        treeBlobs = json.optInt("tree_blobs", 0),
                        dataAdded = json.optLong("data_added", 0),
                        totalFilesProcessed = json.optInt("total_files_processed", 0),
                        totalBytesProcessed = json.optLong("total_bytes_processed", 0),
                        totalDuration = json.optDouble("total_duration", 0.0)
                    ))
                }
            } catch (_: Exception) { /* not a valid summary JSON, keep looking */ }
        }
        return Result.failure(Exception("No summary found in restic output"))
    }
}
