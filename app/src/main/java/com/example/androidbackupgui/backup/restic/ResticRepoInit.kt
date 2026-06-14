package com.example.androidbackupgui.backup.restic

import android.util.Log
import com.example.androidbackupgui.backup.core.AppError
import com.example.androidbackupgui.backup.core.AppResult
import com.example.androidbackupgui.backup.core.err
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val bridgeRunner: RestBridgeRunner,
    private val executor: BackendExecutor = BackendExecutor(),
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
            executor.withBackend(
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
            ) { env -> runInit(env) }
        }

    /** Shared init logic: run restic init, verify on exitCode 1. */
    private suspend fun runInit(env: Map<String, String>): AppResult<Unit> {
        val result = runner.runRestic(env, "init")
        // exitCode 0 = brand new repo created
        if (result.exitCode == 0) {
            return AppResult.Success(Unit)
        }
        // exitCode 1: check if it's "config already exists" or a real error
        if (result.exitCode == 1) {
            if (!isConfigExistsError(result.stderr)) {
                // Exit code 1 from restic can also mean connection/backend errors (500, timeout, etc.)
                return err(AppError.Restic("restic init 失败: ${result.stderr.take(300).trim()}", result.exitCode, result.stderr))
            }
            var verify = runner.runRestic(env, "snapshots", "--json")
            if (verify.exitCode == 0) {
                // Repo is healthy — already initialized with matching password
                Log.i(TAG, "init: repo already initialized and verified")
                return AppResult.Success(Unit)
            }
            // Lock-related failure → try unlock then retry
            if (isLockError(verify.stderr)) {
                Log.w(TAG, "init: stale lock detected, running unlock")
                runner.runRestic(env, "unlock")
                verify = runner.runRestic(env, "snapshots", "--json")
                if (verify.exitCode == 0) {
                    Log.i(TAG, "init: repo verified after unlock")
                    return AppResult.Success(Unit)
                }
            }
            // Config exists but verification failed — diagnose the cause
            val detail = diagnoseInitFailure(verify.stderr)
            return err(
                AppError.Restic("仓库已存在但无法验证: $detail", verify.exitCode, verify.stderr),
            )
        }
        return err(AppError.Restic("restic init 失败", result.exitCode, result.stderr))
    }

    /** Check if [restic init]'s stderr indicates config already exists (vs a real error). */
    private fun isConfigExistsError(stderr: String): Boolean {
        val lower = stderr.lowercase()
        return lower.contains("already exists") ||
            lower.contains("config file already exists")
    }

    /** Check if stderr indicates a stale repository lock. */
    private fun isLockError(stderr: String): Boolean {
        val lower = stderr.lowercase()
        return lower.contains("lock") ||
            lower.contains("unable to create") ||
            lower.contains("already locked")
    }

    /** Parse restic stderr to produce a user-facing diagnosis string. */
    private fun diagnoseInitFailure(stderr: String): String {
        val lower = stderr.lowercase()
        return when {
            lower.contains("wrong password") ||
                lower.contains("password is incorrect") ||
                lower.contains("unable to decrypt") ||
                lower.contains("wrong key") ||
                lower.contains("invalid password") ||
                lower.contains("decryption") -> {
                "密码不正确，请确认仓库密码"
            }

            lower.contains("key") && (lower.contains("not found") || lower.contains("missing")) -> {
                "密钥文件缺失，仓库可能已损坏"
            }

            lower.contains("permission") || lower.contains("access denied") -> {
                "权限不足，请检查目录权限"
            }

            lower.contains("not a directory") || lower.contains("no such file") -> {
                "仓库路径无效或不可访问"
            }

            else -> {
                "仓库可能已损坏或密码不正确（${stderr.take(200).trim()}）"
            }
        }
    }

    // ── Public URL helper ──────────────────────────────

    /** Build a display-friendly repository URL for UI. */
    fun buildRepoUrl(
        backend: String,
        repoPath: String,
        backendUrl: String,
    ): String = envResolver.buildRepoUrl(backend, repoPath, backendUrl)
}
