package com.example.androidbackupgui.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.androidbackupgui.backup.AppError
import com.example.androidbackupgui.backup.AppResult
import com.example.androidbackupgui.backup.err
import java.io.File

/**
 * Repository maintenance operations: prune, check, stats.
 *
 * [prune] requires both download and upload (it removes pack files from the remote).
 * [check] and [stats] are download-only read operations.
 *
 * For remote backends, uses [RestBridgeRunner] to serve the backend via REST,
 * so restic always sees a local rest-server repository. For local backends,
 * operates directly on the repo path.
 *
 * Delegates execution to [ResticCommandRunner], [ResticEnvResolver], and
 * [RestBridgeRunner] which are shared across sub-modules.
 */
class ResticMaintenance(
    private val runner: ResticCommandRunner,
    private val envResolver: ResticEnvResolver,
    private val bridgeRunner: RestBridgeRunner
) {
    /** Cache directory for restic env and bridge temp files. Set by [ResticWrapper]. */
    var cacheDir: String = ""

    /** SMB NTLM domain for remote backend. Set by [ResticWrapper]. */
    var backendDomain: String = ""

    // ── Prune ──────────────────────────────────────────

    suspend fun prune(
        repoPath: String,
        password: String,
        backend: String = "local",
        backendUrl: String = "",
        backendUser: String = "",
        backendPass: String = "",
        backendShare: String = "",
    ): AppResult<String> =
        withContext(Dispatchers.IO) {
            if (backend == "local") {
                val env = envResolver.buildLocalEnv(repoPath, password, cacheDir)
                val result = runner.runRestic(env, "prune")
                if (result.exitCode == 0) AppResult.Success(result.stdout)
                else err(AppError.Restic("restic prune 失败", result.exitCode, result.stderr))
            } else {
                bridgeRunner.withBridge(
                    backend, backendUrl, backendUser, backendPass, backendShare,
                    backendDomain, repoPath, File(cacheDir)
                ) { bridgeUrl, authToken ->
                    val env = envResolver.buildBridgeEnv(password, bridgeUrl, cacheDir, authToken)
                    val result = runner.runRestic(env, "prune")
                    if (result.exitCode == 0) AppResult.Success(result.stdout)
                    else err(AppError.Restic("restic prune 失败", result.exitCode, result.stderr))
                }
            }
        }

    // ── Unlock ──────────────────────────────────────────

    suspend fun unlock(
        repoPath: String,
        password: String,
        backend: String = "local",
        backendUrl: String = "",
        backendUser: String = "",
        backendPass: String = "",
        backendShare: String = "",
    ): AppResult<String> =
        withContext(Dispatchers.IO) {
            if (backend == "local") {
                val env = envResolver.buildLocalEnv(repoPath, password, cacheDir)
                val result = runner.runRestic(env, "unlock")
                if (result.exitCode == 0) AppResult.Success(result.stdout)
                else err(AppError.Restic("restic unlock 失败", result.exitCode, result.stderr))
            } else {
                bridgeRunner.withBridge(
                    backend, backendUrl, backendUser, backendPass, backendShare,
                    backendDomain, repoPath, File(cacheDir)
                ) { bridgeUrl, authToken ->
                    val env = envResolver.buildBridgeEnv(password, bridgeUrl, cacheDir, authToken)
                    val result = runner.runRestic(env, "unlock")
                    if (result.exitCode == 0) AppResult.Success(result.stdout)
                    else err(AppError.Restic("restic unlock 失败", result.exitCode, result.stderr))
                }
            }
        }

    // ── Check ──────────────────────────────────────────

    suspend fun check(
        repoPath: String,
        password: String,
        backend: String = "local",
        backendUrl: String = "",
        backendUser: String = "",
        backendPass: String = "",
        backendShare: String = "",
    ): AppResult<String> =
        withContext(Dispatchers.IO) {
            if (backend == "local") {
                val env = envResolver.buildLocalEnv(repoPath, password, cacheDir)
                val result = runner.runRestic(env, "check")
                if (result.exitCode == 0) AppResult.Success(result.stdout)
                else err(AppError.Restic("restic check 失败", result.exitCode, result.stderr))
            } else {
                bridgeRunner.withBridge(
                    backend, backendUrl, backendUser, backendPass, backendShare,
                    backendDomain, repoPath, File(cacheDir)
                ) { bridgeUrl, authToken ->
                    val env = envResolver.buildBridgeEnv(password, bridgeUrl, cacheDir, authToken)
                    val result = runner.runRestic(env, "check")
                    if (result.exitCode == 0) AppResult.Success(result.stdout)
                    else err(AppError.Restic("restic check 失败", result.exitCode, result.stderr))
                }
            }
        }

    // ── Stats ──────────────────────────────────────────

    suspend fun stats(
        repoPath: String,
        password: String,
        backend: String = "local",
        backendUrl: String = "",
        backendUser: String = "",
        backendPass: String = "",
        backendShare: String = "",
    ): AppResult<String> =
        withContext(Dispatchers.IO) {
            if (backend == "local") {
                val env = envResolver.buildLocalEnv(repoPath, password, cacheDir)
                val result = runner.runRestic(env, "stats")
                if (result.exitCode == 0) AppResult.Success(result.stdout)
                else err(AppError.Restic("restic stats 失败", result.exitCode, result.stderr))
            } else {
                bridgeRunner.withBridge(
                    backend, backendUrl, backendUser, backendPass, backendShare,
                    backendDomain, repoPath, File(cacheDir)
                ) { bridgeUrl, authToken ->
                    val env = envResolver.buildBridgeEnv(password, bridgeUrl, cacheDir, authToken)
                    val result = runner.runRestic(env, "stats")
                    if (result.exitCode == 0) AppResult.Success(result.stdout)
                    else err(AppError.Restic("restic stats 失败", result.exitCode, result.stderr))
                }
            }
        }
}
