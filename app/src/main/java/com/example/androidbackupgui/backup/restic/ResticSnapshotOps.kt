package com.example.androidbackupgui.backup.restic

import com.example.androidbackupgui.backup.core.AppError
import com.example.androidbackupgui.backup.core.AppResult
import com.example.androidbackupgui.backup.core.err
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Snapshot listing and retention policy operations.
 *
 * [listSnapshots] is download-only; [forget] removes snapshots from the remote.
 *
 * 使用 [BackendExecutor] 统一处理 local/remote 后端。
 */
class ResticSnapshotOps(
    private val runner: ResticCommandRunner,
    private val envResolver: ResticEnvResolver,
    private val bridgeRunner: RestBridgeRunner,
    private val executor: BackendExecutor = BackendExecutor(),
) {
    var cacheDir: String = ""
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
    ): AppResult<List<ResticWrapper.ResticSnapshot>> =
        withContext(Dispatchers.IO) {
            val args = mutableListOf("snapshots", "--json")
            if (tag != null) {
                args.add("--tag")
                args.add(tag)
            }

            val result =
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
                ) { env -> runner.runRestic(env, args) }

            if (result.exitCode != 0) {
                return@withContext err(AppError.Restic("restic snapshots 失败", result.exitCode, result.stderr))
            }

            try {
                val snapshots =
                    resticJson.decodeFromString<List<ResticWrapper.ResticSnapshot>>(
                        result.stdout.ifEmpty { "[]" },
                    )
                AppResult.Success(snapshots.sortedByDescending { it.time })
            } catch (e: Exception) {
                err(AppError.Parse("解析快照 JSON 失败", e.message ?: ""))
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
    ): AppResult<String> =
        withContext(Dispatchers.IO) {
            val args =
                mutableListOf(
                    "forget",
                    "--keep-daily",
                    keepDaily.toString(),
                    "--keep-weekly",
                    keepWeekly.toString(),
                    "--keep-monthly",
                    keepMonthly.toString(),
                )
            if (dryRun) args.add("--dry-run")

            val result =
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
                ) { env -> runner.runRestic(env, args) }

            if (result.exitCode == 0) {
                AppResult.Success(result.stdout)
            } else {
                err(AppError.Restic("restic forget 失败", result.exitCode, result.stderr))
            }
        }
}
