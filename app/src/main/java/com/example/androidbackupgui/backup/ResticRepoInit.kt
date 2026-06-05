package com.example.androidbackupgui.backup

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.androidbackupgui.backup.AppError
import com.example.androidbackupgui.backup.AppResult
import com.example.androidbackupgui.backup.err
import java.io.File

/**
 * Repository lifecycle operations: init and repo URL construction.
 *
 * Delegates execution to [ResticCommandRunner], [ResticEnvResolver], and
 * [RestBridgeRunner] which are shared across sub-modules.
 *
 * For "local" backends, invokes restic directly against [repoPath].
 * For remote backends (SMB/WebDAV/rest-server), starts a temporary REST bridge
 * via [RestBridgeRunner.withBridge] and points restic at the bridge URL.
 */
class ResticRepoInit(
    private val runner: ResticCommandRunner,
    private val envResolver: ResticEnvResolver,
    private val bridgeRunner: RestBridgeRunner
) {
    private val TAG = "ResticWrapper"

    /** Cache directory for restic env and bridge temp files. Set by ResticWrapper. */
    var cacheDir: String = ""
    /** NTLM domain for SMB authentication. Set by ResticWrapper. */
    var backendDomain: String = ""

    // ── Repository initialization ──────────────────────

    suspend fun init(
        repoPath: String,
        password: String,
        backend: String = "local",
        backendUrl: String = "",
        backendUser: String = "",
        backendPass: String = "",
        backendShare: String = "",
    ): AppResult<Unit> =
        withContext(Dispatchers.IO) {
            if (backend == "local") {
                val env = envResolver.buildLocalEnv(repoPath, password, cacheDir)
                runInit(env)
            } else {
                bridgeRunner.withBridge(
                    backend, backendUrl, backendUser, backendPass, backendShare,
                    backendDomain, repoPath, File(cacheDir)
                ) { bridgeUrl ->
                    val env = envResolver.buildBridgeEnv(password, bridgeUrl, cacheDir)
                    runInit(env)
                }
            }
        }

    /** Shared init logic: run restic init, verify on exitCode 1. */
    private suspend fun runInit(env: Map<String, String>): AppResult<Unit> {
        val result = runner.runRestic(env, "init")
        // exitCode 0 = brand new repo created
        if (result.exitCode == 0) {
            return AppResult.Success(Unit)
        }
        // exitCode 1 = config already exists; verify the repo is actually usable
        if (result.exitCode == 1) {
            val verify = runner.runRestic(env, "snapshots", "--json")
            if (verify.exitCode == 0) {
                // Repo is healthy — already initialized with matching password
                Log.i(TAG, "init: repo already initialized and verified")
                return AppResult.Success(Unit)
            }
            // Config exists but repo is corrupted (wrong password, missing keys, etc.)
            return err(
                AppError.Restic("仓库已存在但无法验证", verify.exitCode, verify.stderr)
            )
        }
        return err(AppError.Restic("restic init 失败", result.exitCode, result.stderr))
    }

    // ── Public URL helper ──────────────────────────────

    /** Build a display-friendly repository URL for UI. */
    fun buildRepoUrl(backend: String, repoPath: String, backendUrl: String): String {
        return envResolver.buildRepoUrl(backend, repoPath, backendUrl)
    }
}
