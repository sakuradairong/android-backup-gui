package com.example.androidbackupgui.backup

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.coroutines.coroutineContext

/**
 * Wraps the restic CLI binary for backup/restore operations.
 *
 * Uses environment variables (RESTIC_REPOSITORY, RESTIC_PASSWORD) rather than
 * command-line flags to avoid leaking secrets in the process list.
 *
 * All public methods are suspend and run on Dispatchers.IO.
 */
object ResticWrapper {

    private const val TAG = "ResticWrapper"

    /** Path to the restic binary. Default assumes it's on PATH (e.g. Termux). */
    var binaryPath: String = "restic"

    /** Path to the rclone binary (required for rclone: backends). */
    var rcloneBinaryPath: String = "rclone"

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
        backendPass: String = ""
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            val env = buildFullEnv(repoPath, password, backend, backendUrl, backendUser, backendPass)
            val result = runRestic(env, "init")
            if (result.exitCode == 0 || result.exitCode == 1) {
                // exit code 1 = already initialized, which is fine
                Result.success(Unit)
            } else {
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
        onProgress: suspend (ResticProgress) -> Unit = {}
    ): Result<BackupSummary> = withContext(Dispatchers.IO) {
        val emit: suspend (ResticProgress) -> Unit = { p -> withContext(Dispatchers.Main) { onProgress(p) } }
        val args = mutableListOf("backup", "--json")
        for (path in paths) args.add(path)
        for (tag in tags) { args.add("--tag"); args.add(tag) }
        if (hostname != null) { args.add("--host"); args.add(hostname) }

        val env = buildFullEnv(repoPath, password, backend, backendUrl, backendUser, backendPass)
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
            return@withContext Result.failure(Exception("restic backup failed: ${result.stderr}"))
        }

        // Parse the summary JSON on the last line(s) of stdout
        parseBackupSummary(result.stdout).fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it) }
        )
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
        onProgress: suspend (String) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val emit: suspend (String) -> Unit = { s -> withContext(Dispatchers.Main) { onProgress(s) } }

        File(targetPath).mkdirs()

        val args = mutableListOf("restore", snapshotId, "--target", targetPath, "--json")
        if (include != null) { args.add("--include"); args.add(include) }

        val env = buildFullEnv(repoPath, password, backend, backendUrl, backendUser, backendPass)
        val result = runResticStreaming(env, args) { line ->
            if (!coroutineContext.isActive) return@runResticStreaming
            try {
                val json = JSONObject(line)
                val msgType = json.optString("message_type", "")
                if (msgType == "status") {
                    val percent = "%.1f".format(json.optDouble("percent_done", 0.0) * 100)
                    emit("恢復進度: $percent%")
                } else if (msgType == "summary") {
                    emit("恢復完成: ${json.optInt("total_files", 0)} 個檔案")
                }
            } catch (_: Exception) { emit(line) }
        }

        if (result.exitCode == 0) Result.success(Unit)
        else Result.failure(Exception("restic restore failed: ${result.stderr}"))
    }

    // ── Snapshot listing ───────────────────────────────

    suspend fun listSnapshots(
        repoPath: String,
        password: String,
        tag: String? = null,
        backend: String = "local",
        backendUrl: String = "",
        backendUser: String = "",
        backendPass: String = ""
    ): Result<List<ResticSnapshot>> = withContext(Dispatchers.IO) {
        val args = mutableListOf("snapshots", "--json")
        if (tag != null) { args.add("--tag"); args.add(tag) }

        val env = buildFullEnv(repoPath, password, backend, backendUrl, backendUser, backendPass)
        val result = runRestic(env, args)

        if (result.exitCode != 0) {
            return@withContext Result.failure(Exception("restic snapshots failed: ${result.stderr}"))
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
        backendPass: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        val args = mutableListOf(
            "forget",
            "--keep-daily", keepDaily.toString(),
            "--keep-weekly", keepWeekly.toString(),
            "--keep-monthly", keepMonthly.toString()
        )
        if (dryRun) args.add("--dry-run")

        val env = buildFullEnv(repoPath, password, backend, backendUrl, backendUser, backendPass)
        val result = runRestic(env, args)

        if (result.exitCode == 0) Result.success(result.stdout)
        else Result.failure(Exception("restic forget failed: ${result.stderr}"))
    }

    suspend fun prune(
        repoPath: String,
        password: String,
        backend: String = "local",
        backendUrl: String = "",
        backendUser: String = "",
        backendPass: String = ""
    ): Result<String> =
        withContext(Dispatchers.IO) {
            val env = buildFullEnv(repoPath, password, backend, backendUrl, backendUser, backendPass)
            val result = runRestic(env, "prune")
            if (result.exitCode == 0) Result.success(result.stdout)
            else Result.failure(Exception("restic prune failed: ${result.stderr}"))
        }

    suspend fun check(
        repoPath: String,
        password: String,
        backend: String = "local",
        backendUrl: String = "",
        backendUser: String = "",
        backendPass: String = ""
    ): Result<String> =
        withContext(Dispatchers.IO) {
            val env = buildFullEnv(repoPath, password, backend, backendUrl, backendUser, backendPass)
            val result = runRestic(env, "check")
            if (result.exitCode == 0) Result.success(result.stdout)
            else Result.failure(Exception("restic check failed: ${result.stderr}"))
        }

    suspend fun stats(
        repoPath: String,
        password: String,
        backend: String = "local",
        backendUrl: String = "",
        backendUser: String = "",
        backendPass: String = ""
    ): Result<String> =
        withContext(Dispatchers.IO) {
            val env = buildFullEnv(repoPath, password, backend, backendUrl, backendUser, backendPass)
            val result = runRestic(env, "stats")
            if (result.exitCode == 0) Result.success(result.stdout)
            else Result.failure(Exception("restic stats failed: ${result.stderr}"))
        }

    // ── Internal helpers ───────────────────────────────

    /** Build the full command list to run restic. */
    fun buildCommandArgs(args: List<String>): List<String> {
        val cmd = listOf(binaryPath) + args
        Log.d(TAG, "buildCommandArgs: binaryPath=$binaryPath args=$args → cmd=$cmd")
        return cmd
    }

    /** Build environment for restic, with optional rclone backend config. */
    fun buildFullEnv(
        repoPath: String,
        password: String,
        backend: String = "local",
        backendUrl: String = "",
        backendUser: String = "",
        backendPass: String = ""
    ): Map<String, String> {
        val env = HashMap(System.getenv() ?: emptyMap())
        env["RESTIC_REPOSITORY"] = buildRepoUrl(backend, repoPath, backendUrl)
        env["RESTIC_PASSWORD"] = password
        if (backend != "local") {
            env.putAll(buildRcloneEnv(backend, backendUrl, backendUser, backendPass))
            // restic shells out to `rclone` via PATH (RCLONE_PROGRAM not reliable)
            val rcloneDir = File(rcloneBinaryPath).parent ?: ""
            val currentPath = env.getOrDefault("PATH", "")
            val newPath = if (rcloneDir.isNotEmpty()) "$rcloneDir:$currentPath" else currentPath
            env["PATH"] = newPath
            Log.d(TAG, "buildFullEnv backend=$backend rcloneBinaryPath=$rcloneBinaryPath rcloneDir=$rcloneDir oldPATH=$currentPath newPATH=$newPath")
        }
        return env
    }

    /** Build the restic repository URL based on backend config. */
    fun buildRepoUrl(backend: String, repoPath: String, backendUrl: String): String {
        return if (backend == "local") repoPath
        else "rclone:myremote:$repoPath"
    }

    /** Build RCLONE_CONFIG_* environment variables for restic's built-in rclone backend. */
    private fun buildRcloneEnv(
        backend: String,
        url: String,
        user: String,
        pass: String
    ): Map<String, String> {
        val env = HashMap<String, String>()
        when (backend) {
            "webdav" -> {
                env["RCLONE_CONFIG_MYREMOTE_TYPE"] = "webdav"
                env["RCLONE_CONFIG_MYREMOTE_URL"] = url.trimEnd('/')
                env["RCLONE_CONFIG_MYREMOTE_VENDOR"] = "other"
                if (user.isNotBlank()) env["RCLONE_CONFIG_MYREMOTE_USER"] = user
                if (pass.isNotBlank()) env["RCLONE_CONFIG_MYREMOTE_PASS"] = pass
            }
            "smb" -> {
                env["RCLONE_CONFIG_MYREMOTE_TYPE"] = "smb"
                env["RCLONE_CONFIG_MYREMOTE_HOST"] = url
                if (user.isNotBlank()) env["RCLONE_CONFIG_MYREMOTE_USER"] = user
                if (pass.isNotBlank()) env["RCLONE_CONFIG_MYREMOTE_PASS"] = pass
            }
        }
        return env
    }

    data class CommandResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int
    )

    /** Run restic and capture full output. */
    private fun runRestic(env: Map<String, String>, args: List<String>): CommandResult {
        val cmdArgs = buildCommandArgs(args)
        Log.i(TAG, "runRestic cmd=${cmdArgs.joinToString(" ")}")
        // Log env keys and PATH (but NOT passwords)
        Log.d(TAG, "runRestic PATH=${env["PATH"]} REPOSITORY=${env["RESTIC_REPOSITORY"]} rcloneBinaryPath=$rcloneBinaryPath")
        return try {
            val pb = ProcessBuilder(cmdArgs)
            pb.environment().putAll(env)
            pb.redirectErrorStream(false)
            val process = pb.start()

            // Drain stderr in background to prevent pipe-buffer deadlock
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
            stderrThread.join(5000)
            val exitCode = process.waitFor()
            Log.i(TAG, "runRestic exitCode=$exitCode stdout_len=${stdout.length} stderr_len=${stderrText.length}")
            if (stderrText.isNotEmpty()) Log.w(TAG, "runRestic stderr: ${stderrText}")
            CommandResult(stdout.trim(), stderrText.toString().trim(), exitCode)
        } catch (e: Exception) {
            Log.e(TAG, "runRestic exception", e)
            CommandResult("", e.message ?: "Unknown error", -1)
        }
    }

    /** Run restic with single-string args (no spaces in args). */
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
        Log.d(TAG, "runResticStreaming env keys: ${env.keys}")
        try {
            val pb = ProcessBuilder(cmdArgs)
            pb.environment().putAll(env)
            pb.redirectErrorStream(false)
            val process = pb.start()

            val stdoutLines = StringBuilder()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val stderrReader = BufferedReader(InputStreamReader(process.errorStream))

            // Drain stderr in background
            val stderrText = StringBuilder()
            val stderrThread = Thread({
                try { stderrReader.use { stderrText.append(it.readText()) } } catch (_: Exception) {}
            }, "restic-stderr").apply { isDaemon = true; start() }

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (!coroutineContext.isActive) {
                    process.destroy()
                    break
                }
                val l = line!!
                stdoutLines.appendLine(l)
                onLine(l)
            }

            stderrThread.join(5000)
            val exitCode = try { process.waitFor() } catch (_: Exception) { -1 }
            reader.close()

            Log.i(TAG, "runResticStreaming exitCode=$exitCode stdout_len=${stdoutLines.length}")
            if (stderrText.isNotEmpty()) Log.w(TAG, "runResticStreaming stderr: ${stderrText}")
            CommandResult(stdoutLines.toString().trim(), stderrText.toString().trim(), exitCode)
        } catch (e: Exception) {
            Log.e(TAG, "runResticStreaming exception", e)
            CommandResult("", e.message ?: "Unknown error", -1)
        }
    }

    /** Parse the JSON summary from the end of restic backup output. */
    private fun parseBackupSummary(stdout: String): Result<BackupSummary> {
        // The summary is the last JSON object in the output (after status lines)
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
