package com.example.androidbackupgui.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.androidbackupgui.backup.BackupConfig
import com.example.androidbackupgui.backup.formatSize
import com.example.androidbackupgui.backup.ResticBinary
import com.example.androidbackupgui.backup.ResticWrapper
import com.example.androidbackupgui.backup.RemoteTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/** UI-visible state driven by [ConfigViewModel]. */
data class ConfigUiState(
    val config: BackupConfig = BackupConfig(),
    val backendDisplay: BackendDisplay = BackendDisplay(),
    val resticStatus: ResticStatus = ResticStatus()
)

data class BackendDisplay(
    val isRemote: Boolean = false,
    val needsAuth: Boolean = false,
    val isSmb: Boolean = false,
    val computedUrl: String = "",
    val urlHint: String = ""
)

data class ResticStatus(
    val message: String = "",
    val snapshotCount: Int = 0,
    val initButtonVisible: Boolean = true,
    val initButtonEnabled: Boolean = true,
    val statsButtonVisible: Boolean = false,
    val statsButtonEnabled: Boolean = true,
    val pruneButtonVisible: Boolean = false,
    val pruneButtonEnabled: Boolean = true
)

/** Restic credential/form snapshot passed from Fragment on every user interaction. */
data class ResticForm(
    val repo: String, val password: String,
    val backend: String, val backendUrl: String,
    val backendUser: String, val backendPass: String,
    val backendShare: String, val backendDomain: String
)

/**
 * 类型安全的一键操作生命周期事件。
 * [ConfigFragment] 应对此进行收集以触发一次性 UI 效果。
 */
sealed interface OperationEvent {
    data object InitStarted : OperationEvent
    data object InitCompleted : OperationEvent
    data object InitFailed : OperationEvent
    data object StatsStarted : OperationEvent
    data object StatsCompleted : OperationEvent
    data object PruneStarted : OperationEvent
    data object PruneFailed : OperationEvent
    data object PruneCompleted : OperationEvent
}

class ConfigViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ConfigViewModel"
        private const val CONFIG_FILE_NAME = "backup_settings.conf"

        fun deriveBackendDisplay(backend: String, repo: String, backendUrl: String): BackendDisplay {
            val isRemote = backend != "local"
            val needsAuth = backend == "webdav" || backend == "smb"
            val isSmb = backend == "smb"
            val urlHint = when (backend) {
                "webdav" -> "WebDAV 地址 (https://host:port/path)"
                "smb" -> "SMB 主机地址 (host 或 host:port)"
                "rest-server" -> "rest-server 地址 (http://host:port)"
                else -> ""
            }
            val computedUrl = ResticWrapper.buildRepoUrl(backend, repo, backendUrl)
            return BackendDisplay(
                isRemote = isRemote, needsAuth = needsAuth, isSmb = isSmb,
                computedUrl = computedUrl, urlHint = urlHint
            )
        }

    }

    private val configFile: File by lazy {
        File(getApplication<Application>().filesDir, CONFIG_FILE_NAME)
    }

    /** One-shot operation lifecycle events (e.g. "operation started", "operation completed"). */
    private val _operationEvents = MutableSharedFlow<OperationEvent>(extraBufferCapacity = 16)
    val operationEvents: SharedFlow<OperationEvent> = _operationEvents.asSharedFlow()

    private val _uiState = MutableStateFlow(ConfigUiState())
    val uiState: StateFlow<ConfigUiState> = _uiState.asStateFlow()

    /** Read config from file and refresh restic status. */
    fun load() {
        val config = BackupConfig.fromFile(configFile)
        val backendDisplay = deriveBackendDisplay(config.resticBackend, config.resticRepo, config.resticBackendUrl)
        _uiState.update {
            it.copy(config = config, backendDisplay = backendDisplay)
        }
        refreshResticStatus(readResticForm())
    }

    /** Build a [ResticForm] snapshot from the current state's config values. */
    private fun readResticForm() = _uiState.value.config.let { c ->
        ResticForm(
            repo = c.resticRepo, password = c.resticPassword,
            backend = c.resticBackend, backendUrl = c.resticBackendUrl,
            backendUser = c.resticBackendUser, backendPass = c.resticBackendPass,
            backendShare = c.resticBackendShare, backendDomain = c.resticBackendDomain
        )
    }

    /** Update derived display state when backend/repo/url form fields change. */
    fun onFormChanged(backend: String, repo: String, backendUrl: String) {
        val bd = deriveBackendDisplay(backend, repo, backendUrl)
        _uiState.update { it.copy(backendDisplay = bd) }
    }

    /**
     * Save config to file on IO and update status message.
     * The caller passes the current form values as a [BackupConfig] copy.
     */
    fun save(formConfig: BackupConfig) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                BackupConfig.toFile(formConfig, configFile)
            }
            _uiState.update {
                it.copy(resticStatus = it.resticStatus.copy(message = "配置已保存到 $configFile"))
            }
        }
    }

    /** Prepare ResticWrapper (binary, temp dir, domain) from application context. */
    private fun prepareRestic(): Boolean {
        val ctx = getApplication<Application>()
        val binaryPath = ResticBinary.prepare(ctx)
        if (binaryPath == null) return false
        ResticWrapper.binaryPath = binaryPath
        ResticWrapper.tempRepoDir = ResticBinary.getTempRepoDir(ctx)
        return true
    }

    // ── Async restic operations ──────────────────────────────────────

    fun initResticRepo(form: ResticForm) {
        Log.i(TAG, "initResticRepo called: repo=${form.repo} backend=${form.backend}")

        if (!prepareRestic()) {
            _uiState.update { it.copy(resticStatus = it.resticStatus.copy(
                message = "restic 二进制未就绪，请确保已安装 restic 于 Termux 或 APK 内置版本可用"
            ))}
            return
        }
        ResticWrapper.backendDomain = form.backendDomain
        Log.i(TAG, "initResticRepo: repo=${form.repo} backend=${form.backend} url=${form.backendUrl}")

        if (form.repo.isEmpty() || form.password.isEmpty()) {
            _uiState.update { it.copy(resticStatus = it.resticStatus.copy(message = "请填写仓库路径和密码")) }
            return
        }

        _uiState.update { it.copy(resticStatus = it.resticStatus.copy(
            message = "正在初始化 restic 仓库…", initButtonEnabled = false
        ))}

        viewModelScope.launch {
            try {
                _operationEvents.emit(OperationEvent.InitStarted)
                val result = ResticWrapper.init(form.repo, form.password,
                    backend = form.backend, backendUrl = form.backendUrl,
                    backendUser = form.backendUser, backendPass = form.backendPass,
                    backendShare = form.backendShare,
                    onSyncProgress = { p -> onSyncProgress(p) },
                    onByteSyncProgress = { p -> onByteProgress(p) },
                )
                if (result.isSuccess) {
                    _operationEvents.emit(OperationEvent.InitCompleted)
                    _uiState.update { it.copy(resticStatus = it.resticStatus.copy(
                        message = "仓库初始化成功: ${form.repo}", initButtonEnabled = true
                    ))}
                    refreshResticStatus(form)
                } else {
                    _operationEvents.emit(OperationEvent.InitFailed)
                    Log.e(TAG, "initResticRepo failed: ${result.exceptionOrNull()?.message}")
                    _uiState.update { it.copy(resticStatus = it.resticStatus.copy(
                        message = "初始化失败: ${result.exceptionOrNull()?.message}", initButtonEnabled = true
                    ))}
                }
            } finally {
                _uiState.update { it.copy(resticStatus = it.resticStatus.copy(initButtonEnabled = true)) }
            }
        }
    }

    fun refreshResticStatus(form: ResticForm) {
        if (form.repo.isBlank()) {
            _uiState.update { it.copy(resticStatus = ResticStatus(
                message = "请填写仓库路径和密码后初始化",
                initButtonVisible = true, statsButtonVisible = false, pruneButtonVisible = false
            ))}
            return
        }

        if (!prepareRestic()) {
            _uiState.update { it.copy(resticStatus = ResticStatus(
                message = "restic 二进制未就绪",
                initButtonVisible = true, statsButtonVisible = false, pruneButtonVisible = false
            ))}
            return
        }
        ResticWrapper.backendDomain = form.backendDomain

        _uiState.update { it.copy(resticStatus = it.resticStatus.copy(message = "正在检测仓库状态…")) }

        viewModelScope.launch {
            val snapshotsResult = ResticWrapper.listSnapshots(form.repo, form.password,
                backend = form.backend, backendUrl = form.backendUrl,
                backendUser = form.backendUser, backendPass = form.backendPass,
                backendShare = form.backendShare,
                onSyncProgress = { p -> onSyncProgress(p) },
                onByteSyncProgress = { p -> onByteProgress(p) },
            )
            if (snapshotsResult.isSuccess) {
                val snapshots = snapshotsResult.getOrDefault(emptyList())
                _uiState.update { it.copy(resticStatus = ResticStatus(
                    message = "仓库就绪，${snapshots.size} 个快照",
                    snapshotCount = snapshots.size,
                    initButtonVisible = false, statsButtonVisible = true, pruneButtonVisible = true
                ))}
            } else {
                _uiState.update { it.copy(resticStatus = ResticStatus(
                    message = "仓库未初始化或认证失败",
                    initButtonVisible = true, statsButtonVisible = false, pruneButtonVisible = false
                ))}
            }
        }
    }
    fun showResticStats(form: ResticForm) {
        _uiState.update { it.copy(resticStatus = it.resticStatus.copy(
            message = "正在读取统计…", statsButtonEnabled = false
        ))}

        viewModelScope.launch {
            try {
                _operationEvents.emit(OperationEvent.StatsStarted)
                val statsResult = ResticWrapper.stats(form.repo, form.password,
                    backend = form.backend, backendUrl = form.backendUrl,
                    backendUser = form.backendUser, backendPass = form.backendPass,
                    backendShare = form.backendShare,
                    onSyncProgress = { p -> onSyncProgress(p) },
                    onByteSyncProgress = { p -> onByteProgress(p) },
                )
                val snapshotsResult = ResticWrapper.listSnapshots(form.repo, form.password,
                    backend = form.backend, backendUrl = form.backendUrl,
                    backendUser = form.backendUser, backendPass = form.backendPass,
                    backendShare = form.backendShare,
                    onSyncProgress = { p -> onSyncProgress(p) },
                    onByteSyncProgress = { p -> onByteProgress(p) },
                )

                val snapshotCount = snapshotsResult.getOrDefault(emptyList()).size
                _uiState.update { it.copy(resticStatus = it.resticStatus.copy(
                    message = buildString {
                        appendLine("快照数: $snapshotCount")
                        if (statsResult.isSuccess) {
                            appendLine(statsResult.getOrDefault(""))
                        } else {
                            appendLine("统计读取失败: ${statsResult.errorOrNull()?.message}")
                        }
                    },
                    snapshotCount = snapshotCount,
                    statsButtonEnabled = true
                ))}
                _operationEvents.emit(OperationEvent.StatsCompleted)
            } finally {
                _uiState.update { it.copy(resticStatus = it.resticStatus.copy(statsButtonEnabled = true)) }
            }
        }
    }

    fun pruneResticSnapshots(form: ResticForm) {
        _uiState.update { it.copy(resticStatus = it.resticStatus.copy(
            message = "正在清理旧快照 (保留 7 天 / 4 周 / 3 月)…",
            pruneButtonEnabled = false
        ))}

        viewModelScope.launch {
            try {
                _operationEvents.emit(OperationEvent.PruneStarted)
                val forgetResult = ResticWrapper.forget(form.repo, form.password,
                    keepDaily = 7, keepWeekly = 4, keepMonthly = 3,
                    backend = form.backend, backendUrl = form.backendUrl,
                    backendUser = form.backendUser, backendPass = form.backendPass,
                    backendShare = form.backendShare,
                    onSyncProgress = { p -> onSyncProgress(p) },
                    onByteSyncProgress = { p -> onByteProgress(p) },
                )
                if (forgetResult.isFailure) {
                    _operationEvents.emit(OperationEvent.PruneFailed)
                    _uiState.update { it.copy(resticStatus = it.resticStatus.copy(
                        message = "forget 失败: ${forgetResult.exceptionOrNull()?.message}",
                        pruneButtonEnabled = true
                    ))}
                    return@launch
                }

                _uiState.update { it.copy(resticStatus = it.resticStatus.copy(message = "正在回收空间…")) }

                val pruneResult = ResticWrapper.prune(form.repo, form.password,
                    backend = form.backend, backendUrl = form.backendUrl,
                    backendUser = form.backendUser, backendPass = form.backendPass,
                    backendShare = form.backendShare,
                    onSyncProgress = { p -> onSyncProgress(p) },
                    onByteSyncProgress = { p -> onByteProgress(p) },
                )
                _uiState.update { it.copy(resticStatus = it.resticStatus.copy(
                    message = if (pruneResult.isSuccess)
                        "清理完成！\n${pruneResult.getOrDefault("")}"
                    else
                        "prune 失败: ${pruneResult.exceptionOrNull()?.message}",
                    pruneButtonEnabled = true
                ))}
                if (pruneResult.isSuccess) {
                    _operationEvents.emit(OperationEvent.PruneCompleted)
                } else {
                    _operationEvents.emit(OperationEvent.PruneFailed)
                }
            } finally {
                _uiState.update { it.copy(resticStatus = it.resticStatus.copy(pruneButtonEnabled = true)) }
            }
        }
    }

    // ── Internal progress helpers ─────────────────────────────────────

    private fun onSyncProgress(p: RemoteTransport.TransferProgress) {
        _uiState.update {
            it.copy(resticStatus = it.resticStatus.copy(
                message = "同步中: ${p.current}/${p.total} 个文件"
            ))
        }
    }

    private fun onByteProgress(p: RemoteTransport.ByteProgress) {
        _uiState.update {
            it.copy(resticStatus = it.resticStatus.copy(
                message = "同步中: ${p.currentFile}\n${formatSize(p.bytesTransferred)} / ${formatSize(p.totalBytes)}"
            ))
        }
    }

    /** Cleanup ResticWrapper resources when ViewModel is cleared. */
    override fun onCleared() {
        super.onCleared()
        runBlocking(Dispatchers.IO) {
            ResticWrapper.cleanup()
        }
    }
}
