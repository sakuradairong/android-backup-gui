package com.example.androidbackupgui.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.androidbackupgui.backup.AppError
import com.example.androidbackupgui.backup.AppResult
import com.example.androidbackupgui.backup.err
import java.io.File


/**
 * Snapshot listing and retention policy operations.
 *
 * [listSnapshots] is download-only; [forget] removes snapshots from the remote.
 *
 * For "local" backends, invokes restic directly against [repoPath].
 * For remote backends (SMB/WebDAV/rest-server), starts a temporary REST bridge
 * via [RestBridgeRunner.withBridge] and points restic at the bridge URL.
 *
 * Delegates execution to [ResticCommandRunner], [ResticEnvResolver], and
 * [RestBridgeRunner] which are shared across sub-modules.
 */
class ResticSnapshotOps(
    private val runner: ResticCommandRunner,
    private val envResolver: ResticEnvResolver,
    private val bridgeRunner: RestBridgeRunner
) {
    /** Cache directory for restic env and bridge temp files. Set by ResticWrapper. */
    var cacheDir: String = ""
    /** NTLM domain for SMB authentication. Set by ResticWrapper. */
    var backendDomain: String = ""

    // ── List snapshots ─────────────────────────────────

    suspend fun listSnapshots(
        repoPath: String,
        password: String,
        tag: String? = null,
        backend: String = "local",
        backendUrl: String = "",
        backendUser: String = "",
        backendPass: String = "",
        backendShare: String = "",
    ): AppResult<List<ResticWrapper.ResticSnapshot>> = withContext(Dispatchers.IO) {
        if (backend == "local") {
            val args = mutableListOf("snapshots", "--json")
            if (tag != null) { args.add("--tag"); args.add(tag) }

            val env = envResolver.buildLocalEnv(repoPath, password, cacheDir)
            val result = runner.runRestic(env, args)

            if (result.exitCode != 0) {
                return@withContext err(AppError.Restic("restic snapshots 失败", result.exitCode, result.stderr))
            }

            try {
                val snapshots = resticJson.decodeFromString<List<ResticWrapper.ResticSnapshot>>(
                    result.stdout.ifEmpty { "[]" }
                )
                AppResult.Success(snapshots.sortedByDescending { it.time })
            } catch (e: Exception) {
                err(AppError.Parse("解析快照 JSON 失败", e.message ?: ""))
            }
        } else {
            bridgeRunner.withBridge(
                backend, backendUrl, backendUser, backendPass, backendShare,
                backendDomain, repoPath, File(cacheDir)
            ) { bridgeUrl ->
                val args = mutableListOf("snapshots", "--json")
                if (tag != null) { args.add("--tag"); args.add(tag) }

                val env = envResolver.buildBridgeEnv(password, bridgeUrl, cacheDir)
                val result = runner.runRestic(env, args)

                if (result.exitCode != 0) {
                    return@withBridge err(AppError.Restic("restic snapshots 失败", result.exitCode, result.stderr))
                }

                try {
                    val snapshots = resticJson.decodeFromString<List<ResticWrapper.ResticSnapshot>>(
                        result.stdout.ifEmpty { "[]" }
                    )
                    AppResult.Success(snapshots.sortedByDescending { it.time })
                } catch (e: Exception) {
                    err(AppError.Parse("解析快照 JSON 失败", e.message ?: ""))
                }
            }
        }
    }

    // ── Forget (retention policy) ──────────────────────

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
        backendShare: String = "",
    ): AppResult<String> = withContext(Dispatchers.IO) {
        if (backend == "local") {
            val args = mutableListOf(
                "forget",
                "--keep-daily", keepDaily.toString(),
                "--keep-weekly", keepWeekly.toString(),
                "--keep-monthly", keepMonthly.toString()
            )
            if (dryRun) args.add("--dry-run")

            val env = envResolver.buildLocalEnv(repoPath, password, cacheDir)
            val result = runner.runRestic(env, args)

            if (result.exitCode == 0) AppResult.Success(result.stdout)
            else err(AppError.Restic("restic forget 失败", result.exitCode, result.stderr))
        } else {
            bridgeRunner.withBridge(
                backend, backendUrl, backendUser, backendPass, backendShare,
                backendDomain, repoPath, File(cacheDir)
            ) { bridgeUrl ->
                val args = mutableListOf(
                    "forget",
                    "--keep-daily", keepDaily.toString(),
                    "--keep-weekly", keepWeekly.toString(),
                    "--keep-monthly", keepMonthly.toString()
                )
                if (dryRun) args.add("--dry-run")

                val env = envResolver.buildBridgeEnv(password, bridgeUrl, cacheDir)
                val result = runner.runRestic(env, args)

                if (result.exitCode == 0) AppResult.Success(result.stdout)
                else err(AppError.Restic("restic forget 失败", result.exitCode, result.stderr))
            }
        }
    }
}
