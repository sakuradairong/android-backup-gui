package com.example.androidbackupgui.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.example.androidbackupgui.backup.core.AppResult
import com.example.androidbackupgui.backup.restic.ResticSessionFactory
import com.example.androidbackupgui.backup.restic.ResticWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 将 [ConfigViewModel] 中的 5 个 restic 操作提取到独立协调器。
 *
 * 提取后 [ConfigViewModel] 只负责配置状态管理（load/save/import/export/form），
 * 而仓库初始化、状态刷新、解锁、统计、清理等 restic 交互逻辑集中于此。
 *
 * 设计原则：
 *  - 通过回调更新 UI 状态和发送一次性事件，不直接持有 MutableStateFlow/MutableSharedFlow
 *  - restic 会话准备仍由 [ResticSessionFactory] 封装
 *  - 保留原 [ConfigViewModel] 的公共方法签名，外部调用方无需修改
 */
class ResticOperationsCoordinator(
    private val viewModelScope: CoroutineScope,
    private val resticSessionFactory: ResticSessionFactory,
    private val application: Application,
    private val updateResticStatus: ((ResticStatus) -> ResticStatus) -> Unit,
    private val emitEvent: suspend (OperationEvent) -> Unit,
) {
    private companion object {
        const val TAG = "ResticOperationsCoordinator"
    }

    /** Guards against concurrent [initResticRepo] calls. */
    private val initGuard = AtomicBoolean(false)

    /** Guards against stale [refreshResticStatus] coroutines. */
    private var refreshJob: Job? = null

    /**
     * 通过 [resticSessionFactory] 准备配置就绪的 restic 会话。
     * @return 可用的 [ResticWrapper]，若 restic 二进制不可用则返回 `null`
     */
    private fun prepareRestic(backendDomain: String): ResticWrapper? = resticSessionFactory.prepare(application, backendDomain)

    fun initResticRepo(form: ResticForm) {
        if (!initGuard.compareAndSet(false, true)) {
            Log.w(TAG, "initResticRepo: already in progress, ignoring")
            return
        }
        Log.i(TAG, "initResticRepo called: repo=${form.repo} backend=${form.backend}")
        val restic = prepareRestic(form.backendDomain)
        if (restic == null) {
            updateResticStatus {
                it.copy(
                    message = "restic 二进制未就绪，请确保已安装 restic 于 Termux 或 APK 内置版本可用",
                )
            }
            initGuard.set(false)
            return
        }
        Log.i(TAG, "initResticRepo: repo=${form.repo} backend=${form.backend} url=${form.backendUrl}")

        if (form.repo.isEmpty() || form.password.isEmpty()) {
            updateResticStatus { it.copy(message = "请填写仓库路径和密码") }
            initGuard.set(false)
            return
        }

        updateResticStatus {
            it.copy(
                message = "正在初始化 restic 仓库…",
                initButtonEnabled = false,
            )
        }

        // guard 由协程在完成时重置；此前的早退路径已在各自分支显式重置，
        // 避免初始化按钮永久失效（审查报告 H1）。launch 非阻塞，故此处不再重置。
        viewModelScope.launch {
            try {
                emitEvent(OperationEvent.InitStarted)
                val result =
                    restic.init(
                        form.repo,
                        form.password,
                        backend = form.backend,
                        backendUrl = form.backendUrl,
                        backendUser = form.backendUser,
                        backendPass = form.backendPass,
                        backendShare = form.backendShare,
                    )
                if (result.isSuccess) {
                    emitEvent(OperationEvent.InitCompleted)
                    updateResticStatus {
                        it.copy(
                            message = "仓库初始化成功: ${form.repo}",
                        )
                    }
                    refreshResticStatus(form)
                } else {
                    emitEvent(OperationEvent.InitFailed)
                    Log.e(TAG, "initResticRepo failed: ${result.exceptionOrNull()?.message}")
                    updateResticStatus {
                        it.copy(
                            message = "初始化失败: ${result.exceptionOrNull()?.message}",
                        )
                    }
                    refreshResticStatus(form)
                }
            } catch (e: Exception) {
                updateResticStatus { it.copy(message = "初始化异常: ${e.message ?: "未知错误"}") }
            } finally {
                initGuard.set(false)
            }
        }
    }

    fun refreshResticStatus(form: ResticForm) {
        if (form.repo.isBlank()) {
            updateResticStatus {
                ResticStatus(
                    message = "请填写仓库路径和密码后初始化",
                    initButtonVisible = true,
                    statsButtonVisible = false,
                    pruneButtonVisible = false,
                )
            }
            return
        }

        if (prepareRestic(form.backendDomain) == null) {
            updateResticStatus {
                ResticStatus(
                    message = "restic 二进制未就绪",
                    initButtonVisible = true,
                    statsButtonVisible = false,
                    pruneButtonVisible = false,
                )
            }
            return
        }
        val restic = prepareRestic(form.backendDomain)!!

        updateResticStatus { it.copy(message = "正在检测仓库状态…") }

        // Cancel any stale status check so a slow old coroutine doesn't overwrite new results
        refreshJob?.cancel()
        refreshJob =
            viewModelScope.launch {
                val snapshotsResult =
                    restic.listSnapshots(
                        form.repo,
                        form.password,
                        backend = form.backend,
                        backendUrl = form.backendUrl,
                        backendUser = form.backendUser,
                        backendPass = form.backendPass,
                        backendShare = form.backendShare,
                    )
                if (snapshotsResult.isSuccess) {
                    val snapshots = snapshotsResult.getOrDefault(emptyList())
                    updateResticStatus {
                        ResticStatus(
                            message = "仓库就绪，${snapshots.size} 个快照",
                            snapshotCount = snapshots.size,
                            initButtonVisible = false,
                            statsButtonVisible = true,
                            pruneButtonVisible = true,
                            unlockButtonVisible = true,
                        )
                    }
                } else {
                    val errMsg = snapshotsResult.errorOrNull()?.message ?: ""
                    val hasLock = errMsg.contains("lock", ignoreCase = true) || errMsg.contains("already locked", ignoreCase = true)

                    if (hasLock) {
                        updateResticStatus {
                            ResticStatus(
                                message = "仓库被锁定，请先解锁",
                                initButtonVisible = false,
                                statsButtonVisible = false,
                                pruneButtonVisible = false,
                                unlockButtonVisible = true,
                            )
                        }
                    } else {
                        // 审查报告 M1：不再自动 init。listSnapshots 失败可能是密码错误、
                        // 网络故障或仓库确实未初始化，自动 init 对远端后端有覆盖风险。
                        // 统一提示用户显式点「初始化仓库」按钮判断。
                        updateResticStatus {
                            ResticStatus(
                                message = "仓库未初始化或认证失败，请点击「初始化仓库」",
                                initButtonVisible = true,
                                statsButtonVisible = false,
                                pruneButtonVisible = false,
                                unlockButtonVisible = false,
                            )
                        }
                    }
                }
            }
    }

    fun unlockResticRepo(form: ResticForm) {
        updateResticStatus {
            it.copy(
                message = "正在解锁仓库…",
                unlockButtonEnabled = false,
            )
        }
        viewModelScope.launch {
            val restic =
                prepareRestic(form.backendDomain) ?: run {
                    updateResticStatus {
                        it.copy(
                            message = "restic 不可用",
                            unlockButtonEnabled = true,
                        )
                    }
                    return@launch
                }
            val result =
                restic.unlock(
                    form.repo,
                    form.password,
                    backend = form.backend,
                    backendUrl = form.backendUrl,
                    backendUser = form.backendUser,
                    backendPass = form.backendPass,
                    backendShare = form.backendShare,
                )
            updateResticStatus {
                it.copy(
                    message = if (result.isSuccess) "解锁完成" else "解锁失败: ${result.errorOrNull()?.message}",
                    unlockButtonEnabled = true,
                )
            }
            refreshResticStatus(form)
        }
    }

    fun showResticStats(form: ResticForm) {
        updateResticStatus {
            it.copy(
                message = "正在读取统计…",
                statsButtonEnabled = false,
            )
        }

        viewModelScope.launch {
            try {
                emitEvent(OperationEvent.StatsStarted)
                val restic =
                    prepareRestic(form.backendDomain) ?: run {
                        updateResticStatus { it.copy(message = "restic 不可用", statsButtonEnabled = true) }
                        return@launch
                    }
                val statsResult =
                    restic.stats(
                        form.repo,
                        form.password,
                        backend = form.backend,
                        backendUrl = form.backendUrl,
                        backendUser = form.backendUser,
                        backendPass = form.backendPass,
                        backendShare = form.backendShare,
                    )
                val snapshotsResult =
                    restic.listSnapshots(
                        form.repo,
                        form.password,
                        backend = form.backend,
                        backendUrl = form.backendUrl,
                        backendUser = form.backendUser,
                        backendPass = form.backendPass,
                        backendShare = form.backendShare,
                    )

                val snapshotCount = snapshotsResult.getOrDefault(emptyList()).size
                updateResticStatus {
                    it.copy(
                        message =
                            buildString {
                                appendLine("快照数: $snapshotCount")
                                if (statsResult.isSuccess) {
                                    appendLine(statsResult.getOrDefault(""))
                                } else {
                                    appendLine("统计读取失败: ${statsResult.errorOrNull()?.message}")
                                }
                            },
                        snapshotCount = snapshotCount,
                        statsButtonEnabled = true,
                    )
                }
                emitEvent(OperationEvent.StatsCompleted)
            } finally {
                updateResticStatus { it.copy(statsButtonEnabled = true) }
            }
        }
    }

    fun pruneResticSnapshots(form: ResticForm) {
        updateResticStatus {
            it.copy(
                message = "正在清理旧快照 (保留 7 天 / 4 周 / 3 月)…",
                pruneButtonEnabled = false,
            )
        }

        viewModelScope.launch {
            try {
                emitEvent(OperationEvent.PruneStarted)

                // 审查报告 M2：不再无条件 unlock。无条件 clear lock 会清掉正在运行的备份任务的
                // 活动锁导致损坏；仅当 forget 因锁失败时才主动解锁并重试一次。
                val restic =
                    prepareRestic(form.backendDomain) ?: run {
                        updateResticStatus { it.copy(message = "restic 不可用", pruneButtonEnabled = true) }
                        return@launch
                    }

                val forgetOnce: suspend () -> AppResult<String> = {
                    restic.forget(
                        form.repo,
                        form.password,
                        keepDaily = 7,
                        keepWeekly = 4,
                        keepMonthly = 3,
                        backend = form.backend,
                        backendUrl = form.backendUrl,
                        backendUser = form.backendUser,
                        backendPass = form.backendPass,
                        backendShare = form.backendShare,
                    )
                }

                var forgetResult = forgetOnce()
                if (forgetResult.isFailure) {
                    val errMsg = forgetResult.exceptionOrNull()?.message.orEmpty()
                    val isLockError =
                        errMsg.contains("lock", ignoreCase = true) ||
                            errMsg.contains("already locked", ignoreCase = true)
                    if (isLockError) {
                        updateResticStatus { it.copy(message = "检测到锁，正在解锁后重试 forget…") }
                        restic.unlock(
                            form.repo,
                            form.password,
                            backend = form.backend,
                            backendUrl = form.backendUrl,
                            backendUser = form.backendUser,
                            backendPass = form.backendPass,
                            backendShare = form.backendShare,
                        )
                        forgetResult = forgetOnce()
                    }
                }

                if (forgetResult.isFailure) {
                    emitEvent(OperationEvent.PruneFailed)
                    updateResticStatus {
                        it.copy(
                            message = "forget 失败: ${forgetResult.exceptionOrNull()?.message}",
                            pruneButtonEnabled = true,
                        )
                    }
                    return@launch
                }

                updateResticStatus { it.copy(message = "正在回收空间…") }

                val pruneResult =
                    restic.prune(
                        form.repo,
                        form.password,
                        backend = form.backend,
                        backendUrl = form.backendUrl,
                        backendUser = form.backendUser,
                        backendPass = form.backendPass,
                        backendShare = form.backendShare,
                    )
                updateResticStatus {
                    it.copy(
                        message =
                            if (pruneResult.isSuccess) {
                                "清理完成！建议执行完整性检查 (check --read-data-subset=5%)"
                            } else {
                                "prune 失败: ${pruneResult.exceptionOrNull()?.message}"
                            },
                        pruneButtonEnabled = true,
                    )
                }
                if (pruneResult.isSuccess) {
                    emitEvent(OperationEvent.PruneCompleted)
                } else {
                    emitEvent(OperationEvent.PruneFailed)
                }
            } finally {
                updateResticStatus { it.copy(pruneButtonEnabled = true) }
            }
        }
    }
}
