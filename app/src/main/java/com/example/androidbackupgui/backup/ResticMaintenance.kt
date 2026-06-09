package com.example.androidbackupgui.backup

import com.example.androidbackupgui.backup.AppError
import com.example.androidbackupgui.backup.AppResult
import com.example.androidbackupgui.backup.err
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository maintenance operations: prune, unlock, check, stats.
 *
 * [prune] requires both download and upload (it removes pack files from the remote).
 * [check] and [stats] are download-only read operations.
 *
 * 使用 [BackendExecutor] 统一处理 local/remote 后端。
 *
 * Delegates execution to [ResticCommandRunner], [ResticEnvResolver], and
 * [RestBridgeRunner] which are shared across sub-modules.
 */
class ResticMaintenance(
    private val runner: ResticCommandRunner,
    private val envResolver: ResticEnvResolver,
    private val bridgeRunner: RestBridgeRunner,
    private val executor: BackendExecutor = BackendExecutor(),
) {
    /** Cache directory for restic env and bridge temp files. Set by [ResticWrapper]. */
    var cacheDir: String = ""

    /** SMB NTLM domain for remote backend. Set by [ResticWrapper]. */
    var backendDomain: String = ""

    /** Run a one-shot restic command and map the result. */
    private suspend fun runCommand(
        command: String,
        failMessage: String,
        repoPath: String,
        password: String,
        backend: String,
        backendUrl: String,
        backendUser: String,
        backendPass: String,
        backendShare: String,
    ): AppResult<String> =
        withContext(Dispatchers.IO) {
            val result =
                executor.runResticWithBackend(
                    args = listOf(command),
                    repoPath = repoPath,
                    password = password,
                    cacheDir = cacheDir,
                    backend = backend,
                    backendUrl = backendUrl,
                    backendUser = backendUser,
                    backendPass = backendPass,
                    backendShare = backendShare,
                    backendDomain = backendDomain,
                    runner = runner,
                    envResolver = envResolver,
                    bridgeRunner = bridgeRunner,
                )
            if (result.exitCode == 0) {
                AppResult.Success(result.stdout)
            } else {
                err(AppError.Restic(failMessage, result.exitCode, result.stderr))
            }
        }

    suspend fun prune(
        repoPath: String,
        password: String,
        backend: String = "local",
        backendUrl: String = "",
        backendUser: String = "",
        backendPass: String = "",
        backendShare: String = "",
    ): AppResult<String> =
        runCommand(
            "prune",
            "restic prune 失败",
            repoPath,
            password,
            backend,
            backendUrl,
            backendUser,
            backendPass,
            backendShare,
        )

    suspend fun unlock(
        repoPath: String,
        password: String,
        backend: String = "local",
        backendUrl: String = "",
        backendUser: String = "",
        backendPass: String = "",
        backendShare: String = "",
    ): AppResult<String> =
        runCommand(
            "unlock",
            "restic unlock 失败",
            repoPath,
            password,
            backend,
            backendUrl,
            backendUser,
            backendPass,
            backendShare,
        )

    suspend fun check(
        repoPath: String,
        password: String,
        backend: String = "local",
        backendUrl: String = "",
        backendUser: String = "",
        backendPass: String = "",
        backendShare: String = "",
    ): AppResult<String> =
        runCommand(
            "check",
            "restic check 失败",
            repoPath,
            password,
            backend,
            backendUrl,
            backendUser,
            backendPass,
            backendShare,
        )

    suspend fun stats(
        repoPath: String,
        password: String,
        backend: String = "local",
        backendUrl: String = "",
        backendUser: String = "",
        backendPass: String = "",
        backendShare: String = "",
    ): AppResult<String> =
        runCommand(
            "stats",
            "restic stats 失败",
            repoPath,
            password,
            backend,
            backendUrl,
            backendUser,
            backendPass,
            backendShare,
        )
}
