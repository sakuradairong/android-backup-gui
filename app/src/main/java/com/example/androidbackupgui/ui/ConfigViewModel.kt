package com.example.androidbackupgui.ui
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.androidbackupgui.backup.BackupConfig
import com.example.androidbackupgui.backup.PasswordManager
import com.example.androidbackupgui.backup.ResticBinary
import com.example.androidbackupgui.backup.ResticWrapper
import com.example.androidbackupgui.backup.defaultResticWrapper
import com.example.androidbackupgui.backup.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** UI-visible state driven by [ConfigViewModel]. */
data class ConfigUiState(
    val config: BackupConfig = BackupConfig(),
    val backendDisplay: BackendDisplay = BackendDisplay(),
    val resticStatus: ResticStatus = ResticStatus(),
)

data class BackendDisplay(
    val isRemote: Boolean = false,
    val needsAuth: Boolean = false,
    val isSmb: Boolean = false,
    val computedUrl: String = "",
    val urlHint: String = "",
)

data class ResticStatus(
    val message: String = "",
    val snapshotCount: Int = 0,
    val initButtonVisible: Boolean = true,
    val initButtonEnabled: Boolean = true,
    val statsButtonVisible: Boolean = false,
    val statsButtonEnabled: Boolean = true,
    val pruneButtonVisible: Boolean = false,
    val pruneButtonEnabled: Boolean = true,
    val unlockButtonVisible: Boolean = false,
    val unlockButtonEnabled: Boolean = true,
)

/** Restic credential/form snapshot passed from Fragment on every user interaction. */
data class ResticForm(
    val repo: String,
    val password: String,
    val backend: String,
    val backendUrl: String,
    val backendUser: String,
    val backendPass: String,
    val backendShare: String,
    val backendDomain: String,
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

    data object ConfigExported : OperationEvent

    data object ConfigExportFailed : OperationEvent

    data object ConfigImported : OperationEvent

    data object ConfigImportFailed : OperationEvent
}

class ConfigViewModel(
    application: Application,
) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "ConfigViewModel"
        private const val CONFIG_FILE_NAME = "backup_settings.conf"

        fun deriveBackendDisplay(
            backend: String,
            repo: String,
            backendUrl: String,
        ): BackendDisplay {
            val isRemote = backend != "local"
            val needsAuth = backend == "webdav" || backend == "smb"
            val isSmb = backend == "smb"
            val urlHint =
                when (backend) {
                    "webdav" -> "WebDAV 地址 (https://host:port/path)"
                    "smb" -> "SMB 主机地址 (host 或 host:port)"
                    "rest-server" -> "rest-server 地址 (http://host:port)"
                    else -> ""
                }
            val computedUrl = defaultResticWrapper.buildRepoUrl(backend, repo, backendUrl)
            return BackendDisplay(
                isRemote = isRemote,
                needsAuth = needsAuth,
                isSmb = isSmb,
                computedUrl = computedUrl,
                urlHint = urlHint,
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

    /** Guards against concurrent [initResticRepo] calls. */
    private val initGuard = AtomicBoolean(false)

    /** Guards against stale [refreshResticStatus] coroutines. */
    private var refreshJob: Job? = null

    init {
        load()
    }

    /** Read config from file and refresh restic status. */
    fun load() {
        val config = BackupConfig.fromFile(configFile)
        val backendDisplay = deriveBackendDisplay(config.resticBackend, config.resticRepo, config.resticBackendUrl)
        _uiState.update {
            it.copy(config = config, backendDisplay = backendDisplay)
        }
        refreshResticStatus(readResticForm())
    }

    /**
     * Build a [ResticForm] snapshot from the current state's config values.
     * 密码从 PasswordManager（加密存储）获取，不从配置文件读取。
     */
    private fun readResticForm() =
        _uiState.value.config.let { c ->
            // 从加密存储获取密码，如尚未设置则尝试从旧配置迁移
            val password = PasswordManager.getResticPassword() ?: c.resticPassword.takeIf { it.isNotEmpty() }
            val backendPass = PasswordManager.getBackendPass() ?: c.resticBackendPass.takeIf { it.isNotEmpty() }
            // 如果发现旧配置中有密码但 PasswordManager 还没有，迁移过去
            if (password != null && !PasswordManager.hasResticPassword() && password != "stored-in-keystore") {
                PasswordManager.setResticPassword(password)
            }
            if (backendPass != null && backendPass != "stored-in-keystore" && PasswordManager.getBackendPass() == null) {
                PasswordManager.setBackendPass(backendPass)
            }
            ResticForm(
                repo = c.resticRepo,
                password = password ?: "",
                backend = c.resticBackend,
                backendUrl = c.resticBackendUrl,
                backendUser = c.resticBackendUser,
                backendPass = backendPass ?: "",
                backendShare = c.resticBackendShare,
                backendDomain = c.resticBackendDomain,
            )
        }

    /** Update derived display state when backend/repo/url form fields change. */
    fun onFormChanged(
        backend: String,
        repo: String,
        backendUrl: String,
    ) {
        val bd = deriveBackendDisplay(backend, repo, backendUrl)
        _uiState.update { it.copy(backendDisplay = bd) }
    }

    /**
     * Save config to file on IO and update status message.
     * The caller passes the current form values as a [BackupConfig] copy.
     * 密码单独通过 [PasswordManager] 安全存储，不入配置文件。
     */
    fun save(
        formConfig: BackupConfig,
        resticPassword: String? = null,
        backendPass: String? = null,
    ) {
        viewModelScope.launch {
            // 保存密码到加密存储
            if (resticPassword != null && resticPassword.isNotEmpty()) {
                PasswordManager.setResticPassword(resticPassword)
            }
            if (backendPass != null && backendPass.isNotEmpty()) {
                PasswordManager.setBackendPass(backendPass)
            }
            withContext(Dispatchers.IO) {
                BackupConfig.toFile(formConfig, configFile)
            }
            _uiState.update {
                it.copy(
                    config = formConfig,
                    backendDisplay =
                        deriveBackendDisplay(
                            formConfig.resticBackend,
                            formConfig.resticRepo,
                            formConfig.resticBackendUrl,
                        ),
                    resticStatus = it.resticStatus.copy(message = "配置已保存到 $configFile"),
                )
            }
            refreshResticStatus(readResticForm())
        }
    }

    /**
     * Export the current saved config to a user-selected destination [Uri] (SAF).
     * Writes the same on-disk config format, including the plaintext restic password,
     * so the warning is surfaced in the UI before export.
     */
    fun exportConfig(uri: android.net.Uri) {
        viewModelScope.launch {
            val ok =
                withContext(Dispatchers.IO) {
                    try {
                        // Ensure the latest saved config exists; serialize current UI config
                        // if the file isn't there yet.
                        val content =
                            if (configFile.exists()) {
                                configFile.readText()
                            } else {
                                val tmp = File.createTempFile("cfg", ".conf", getApplication<Application>().cacheDir)
                                BackupConfig.toFile(_uiState.value.config, tmp)
                                tmp.readText().also { tmp.delete() }
                            }
                        getApplication<Application>()
                            .contentResolver
                            .openOutputStream(uri)
                            ?.use { out ->
                                out.write(content.toByteArray())
                                out.flush()
                            } ?: return@withContext false
                        true
                    } catch (e: Exception) {
                        Log.e(TAG, "exportConfig failed", e)
                        false
                    }
                }
            if (ok) {
                _operationEvents.emit(OperationEvent.ConfigExported)
                _uiState.update {
                    it.copy(
                        resticStatus =
                            it.resticStatus.copy(
                                message = "配置已导出（密码未包含，需在目标设备上通过应用重新输入）",
                            ),
                    )
                }
            } else {
                _operationEvents.emit(OperationEvent.ConfigExportFailed)
                _uiState.update { it.copy(resticStatus = it.resticStatus.copy(message = "配置导出失败")) }
            }
        }
    }

    /**
     * Import config from a user-selected [Uri] (SAF).
     * Reads the content, writes to configFile, and reloads UI state.
     */
    fun importConfig(uri: android.net.Uri) {
        viewModelScope.launch {
            val ok =
                withContext(Dispatchers.IO) {
                    try {
                        val content =
                            getApplication<Application>()
                                .contentResolver
                                .openInputStream(uri)
                                ?.use { input -> input.reader().readText() }
                                ?: return@withContext false
                        configFile.writeText(content)
                        val parsed = BackupConfig.fromFile(configFile)
                        // 导入的配置中密码是 "stored-in-keystore" 占位符，
                        // 需要从 PasswordManager 恢复真实密码，避免被覆盖
                        val realResticPw = PasswordManager.getResticPassword()
                        val realBackendPw = PasswordManager.getBackendPass()
                        val restoredConfig =
                            parsed.copy(
                                resticPassword = realResticPw ?: parsed.resticPassword,
                                resticBackendPass = realBackendPw ?: parsed.resticBackendPass,
                            )
                        _uiState.update { it.copy(config = restoredConfig) }
                        Log.i(TAG, "importConfig: loaded config from SAF")
                        true
                    } catch (e: Exception) {
                        Log.e(TAG, "importConfig failed", e)
                        false
                    }
                }
            if (ok) {
                _operationEvents.emit(OperationEvent.ConfigImported)
                _uiState.update {
                    it.copy(
                        resticStatus =
                            it.resticStatus.copy(
                                message = "配置已导入，请检查各项设置并保存",
                            ),
                    )
                }
                // Reload UI state from imported config，保留已有的密码
                val s = _uiState.value
                refreshResticStatus(
                    ResticForm(
                        repo = s.config.resticRepo,
                        password = PasswordManager.getResticPassword() ?: "",
                        backend = s.config.resticBackend,
                        backendUrl = s.config.resticBackendUrl,
                        backendUser = s.config.resticBackendUser,
                        backendPass = PasswordManager.getBackendPass() ?: "",
                        backendShare = s.config.resticBackendShare,
                        backendDomain = s.config.resticBackendDomain,
                    ),
                )
            } else {
                _operationEvents.emit(OperationEvent.ConfigImportFailed)
                _uiState.update { it.copy(resticStatus = it.resticStatus.copy(message = "配置导入失败")) }
            }
        }
    }

    /** Prepare ResticWrapper (binary, temp dir, domain) from application context. */
    private fun prepareRestic(): Boolean {
        val ctx = getApplication<Application>()
        val binaryPath = ResticBinary.prepare(ctx)
        if (binaryPath == null) return false
        defaultResticWrapper.binaryPath = binaryPath
        defaultResticWrapper.cacheDir = ctx.cacheDir.absolutePath
        return true
    }

    // ── Async restic operations ──────────────────────────────────────

    fun initResticRepo(form: ResticForm) {
        if (!initGuard.compareAndSet(false, true)) {
            Log.w(TAG, "initResticRepo: already in progress, ignoring")
            return
        }
        Log.i(TAG, "initResticRepo called: repo=${form.repo} backend=${form.backend}")

        if (!prepareRestic()) {
            _uiState.update {
                it.copy(
                    resticStatus =
                        it.resticStatus.copy(
                            message = "restic 二进制未就绪，请确保已安装 restic 于 Termux 或 APK 内置版本可用",
                        ),
                )
            }
            return
        }
        defaultResticWrapper.backendDomain = form.backendDomain
        Log.i(TAG, "initResticRepo: repo=${form.repo} backend=${form.backend} url=${form.backendUrl}")

        if (form.repo.isEmpty() || form.password.isEmpty()) {
            _uiState.update { it.copy(resticStatus = it.resticStatus.copy(message = "请填写仓库路径和密码")) }
            return
        }

        _uiState.update {
            it.copy(
                resticStatus =
                    it.resticStatus.copy(
                        message = "正在初始化 restic 仓库…",
                        initButtonEnabled = false,
                    ),
            )
        }

        viewModelScope.launch {
            try {
                _operationEvents.emit(OperationEvent.InitStarted)
                val result =
                    defaultResticWrapper.init(
                        form.repo,
                        form.password,
                        backend = form.backend,
                        backendUrl = form.backendUrl,
                        backendUser = form.backendUser,
                        backendPass = form.backendPass,
                        backendShare = form.backendShare,
                    )
                if (result.isSuccess) {
                    _operationEvents.emit(OperationEvent.InitCompleted)
                    _uiState.update {
                        it.copy(
                            resticStatus =
                                it.resticStatus.copy(
                                    message = "仓库初始化成功: ${form.repo}",
                                ),
                        )
                    }
                    refreshResticStatus(form)
                } else {
                    _operationEvents.emit(OperationEvent.InitFailed)
                    Log.e(TAG, "initResticRepo failed: ${result.exceptionOrNull()?.message}")
                    _uiState.update {
                        it.copy(
                            resticStatus =
                                it.resticStatus.copy(
                                    message = "初始化失败: ${result.exceptionOrNull()?.message}",
                                ),
                        )
                    }
                    refreshResticStatus(form)
                }
            } finally {
                initGuard.set(false)
            }
        }
    }

    fun refreshResticStatus(form: ResticForm) {
        if (form.repo.isBlank()) {
            _uiState.update {
                it.copy(
                    resticStatus =
                        ResticStatus(
                            message = "请填写仓库路径和密码后初始化",
                            initButtonVisible = true,
                            statsButtonVisible = false,
                            pruneButtonVisible = false,
                        ),
                )
            }
            return
        }

        if (!prepareRestic()) {
            _uiState.update {
                it.copy(
                    resticStatus =
                        ResticStatus(
                            message = "restic 二进制未就绪",
                            initButtonVisible = true,
                            statsButtonVisible = false,
                            pruneButtonVisible = false,
                        ),
                )
            }
            return
        }
        defaultResticWrapper.backendDomain = form.backendDomain

        _uiState.update { it.copy(resticStatus = it.resticStatus.copy(message = "正在检测仓库状态…")) }

        // Cancel any stale status check so a slow old coroutine doesn't overwrite new results
        refreshJob?.cancel()
        refreshJob =
            viewModelScope.launch {
                val snapshotsResult =
                    defaultResticWrapper.listSnapshots(
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
                    _uiState.update {
                        it.copy(
                            resticStatus =
                                ResticStatus(
                                    message = "仓库就绪，${snapshots.size} 个快照",
                                    snapshotCount = snapshots.size,
                                    initButtonVisible = false,
                                    statsButtonVisible = true,
                                    pruneButtonVisible = true,
                                    unlockButtonVisible = true,
                                ),
                        )
                    }
                } else {
                    val errMsg = snapshotsResult.errorOrNull()?.message ?: ""
                    val hasLock = errMsg.contains("lock", ignoreCase = true) || errMsg.contains("already locked", ignoreCase = true)

                    if (hasLock) {
                        _uiState.update {
                            it.copy(
                                resticStatus =
                                    ResticStatus(
                                        message = "仓库被锁定，请先解锁",
                                        initButtonVisible = false,
                                        statsButtonVisible = false,
                                        pruneButtonVisible = false,
                                        unlockButtonVisible = true,
                                    ),
                            )
                        }
                    } else {
                        // snapshots 失败时自动尝试 init（处理已初始化的旧仓库）
                        val initResult =
                            defaultResticWrapper.init(
                                form.repo,
                                form.password,
                                backend = form.backend,
                                backendUrl = form.backendUrl,
                                backendUser = form.backendUser,
                                backendPass = form.backendPass,
                                backendShare = form.backendShare,
                            )
                        if (initResult.isSuccess) {
                            val snaps =
                                defaultResticWrapper
                                    .listSnapshots(
                                        form.repo,
                                        form.password,
                                        backend = form.backend,
                                        backendUrl = form.backendUrl,
                                        backendUser = form.backendUser,
                                        backendPass = form.backendPass,
                                        backendShare = form.backendShare,
                                    ).getOrDefault(emptyList())
                            _uiState.update {
                                it.copy(
                                    resticStatus =
                                        ResticStatus(
                                            message = "仓库就绪，${snaps.size} 个快照",
                                            snapshotCount = snaps.size,
                                            initButtonVisible = false,
                                            statsButtonVisible = true,
                                            pruneButtonVisible = true,
                                            unlockButtonVisible = true,
                                        ),
                                )
                            }
                        } else {
                            _uiState.update {
                                it.copy(
                                    resticStatus =
                                        ResticStatus(
                                            message = "仓库未初始化或认证失败",
                                            initButtonVisible = true,
                                            statsButtonVisible = false,
                                            pruneButtonVisible = false,
                                            unlockButtonVisible = false,
                                        ),
                                )
                            }
                        }
                    }
                }
            }
    }

    fun unlockResticRepo(form: ResticForm) {
        _uiState.update {
            it.copy(
                resticStatus =
                    it.resticStatus.copy(
                        message = "正在解锁仓库…",
                        unlockButtonEnabled = false,
                    ),
            )
        }
        viewModelScope.launch {
            defaultResticWrapper.backendDomain = form.backendDomain
            val result =
                defaultResticWrapper.unlock(
                    form.repo,
                    form.password,
                    backend = form.backend,
                    backendUrl = form.backendUrl,
                    backendUser = form.backendUser,
                    backendPass = form.backendPass,
                    backendShare = form.backendShare,
                )
            _uiState.update {
                it.copy(
                    resticStatus =
                        it.resticStatus.copy(
                            message = if (result.isSuccess) "解锁完成" else "解锁失败: ${result.errorOrNull()?.message}",
                            unlockButtonEnabled = true,
                        ),
                )
            }
            refreshResticStatus(form)
        }
    }

    fun showResticStats(form: ResticForm) {
        _uiState.update {
            it.copy(
                resticStatus =
                    it.resticStatus.copy(
                        message = "正在读取统计…",
                        statsButtonEnabled = false,
                    ),
            )
        }

        viewModelScope.launch {
            try {
                _operationEvents.emit(OperationEvent.StatsStarted)
                val statsResult =
                    defaultResticWrapper.stats(
                        form.repo,
                        form.password,
                        backend = form.backend,
                        backendUrl = form.backendUrl,
                        backendUser = form.backendUser,
                        backendPass = form.backendPass,
                        backendShare = form.backendShare,
                    )
                val snapshotsResult =
                    defaultResticWrapper.listSnapshots(
                        form.repo,
                        form.password,
                        backend = form.backend,
                        backendUrl = form.backendUrl,
                        backendUser = form.backendUser,
                        backendPass = form.backendPass,
                        backendShare = form.backendShare,
                    )

                val snapshotCount = snapshotsResult.getOrDefault(emptyList()).size
                _uiState.update {
                    it.copy(
                        resticStatus =
                            it.resticStatus.copy(
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
                            ),
                    )
                }
                _operationEvents.emit(OperationEvent.StatsCompleted)
            } finally {
                _uiState.update { it.copy(resticStatus = it.resticStatus.copy(statsButtonEnabled = true)) }
            }
        }
    }

    fun pruneResticSnapshots(form: ResticForm) {
        _uiState.update {
            it.copy(
                resticStatus =
                    it.resticStatus.copy(
                        message = "正在清理旧快照 (保留 7 天 / 4 周 / 3 月)…",
                        pruneButtonEnabled = false,
                    ),
            )
        }

        viewModelScope.launch {
            try {
                _operationEvents.emit(OperationEvent.PruneStarted)

                // Remove stale locks before forget/prune
                defaultResticWrapper.backendDomain = form.backendDomain
                defaultResticWrapper.unlock(
                    form.repo,
                    form.password,
                    backend = form.backend,
                    backendUrl = form.backendUrl,
                    backendUser = form.backendUser,
                    backendPass = form.backendPass,
                    backendShare = form.backendShare,
                )

                val forgetResult =
                    defaultResticWrapper.forget(
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
                if (forgetResult.isFailure) {
                    _operationEvents.emit(OperationEvent.PruneFailed)
                    _uiState.update {
                        it.copy(
                            resticStatus =
                                it.resticStatus.copy(
                                    message = "forget 失败: ${forgetResult.exceptionOrNull()?.message}",
                                    pruneButtonEnabled = true,
                                ),
                        )
                    }
                    return@launch
                }

                _uiState.update { it.copy(resticStatus = it.resticStatus.copy(message = "正在回收空间…")) }

                val pruneResult =
                    defaultResticWrapper.prune(
                        form.repo,
                        form.password,
                        backend = form.backend,
                        backendUrl = form.backendUrl,
                        backendUser = form.backendUser,
                        backendPass = form.backendPass,
                        backendShare = form.backendShare,
                    )
                _uiState.update {
                    it.copy(
                        resticStatus =
                            it.resticStatus.copy(
                                message =
                                    if (pruneResult.isSuccess) {
                                        "清理完成！建议执行完整性检查 (check --read-data-subset=5%)"
                                    } else {
                                        "prune 失败: ${pruneResult.exceptionOrNull()?.message}"
                                    },
                                pruneButtonEnabled = true,
                            ),
                    )
                }
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
}
