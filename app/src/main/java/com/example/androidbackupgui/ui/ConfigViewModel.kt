package com.example.androidbackupgui.ui
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.androidbackupgui.backup.BackupConfig
import com.example.androidbackupgui.backup.core.RepoUrlBuilder
import com.example.androidbackupgui.backup.restic.DefaultResticSessionFactory
import com.example.androidbackupgui.backup.restic.ResticSessionFactory
import com.example.androidbackupgui.backup.security.LegacyCredentialMigrator
import com.example.androidbackupgui.backup.security.PasswordManager
import kotlinx.coroutines.Dispatchers
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
    /**
     * 封装 restic 会话的配置。隐藏 [defaultResticWrapper] 的可变属性。
     * 默认 [DefaultResticSessionFactory] 保持现状。
     */
    private val resticSessionFactory: ResticSessionFactory = DefaultResticSessionFactory(),
) : AndroidViewModel(application) {
    /**
     * 供 Android [ViewModelProvider] 反射调用的零参（仅 [Application]）构造函数。
     *
     * 审查报告 L6警示：主构造函数带默认参数是为了测试注入 mock，但 [androidx.lifecycle.AndroidViewModelFactory]
     * 只查找签名恰为 `(Application)` 的构造函数 —— *不会*消费默认参数。因此本次构造函数必须保留，
     * 删除会导致运行时 `viewModel()` 无法实例化、直接崩溃。主构造与本次构造不可互删其一。
     */
    constructor(application: Application) : this(application, DefaultResticSessionFactory())

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
            val computedUrl = RepoUrlBuilder.build(backend, repo, backendUrl)
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

    /** 协调 restic 操作（初始化/状态/解锁/统计/清理）。 */
    private val resticOperationsCoordinator =
        ResticOperationsCoordinator(
            viewModelScope = viewModelScope,
            resticSessionFactory = resticSessionFactory,
            application = getApplication(),
            updateResticStatus = { transform ->
                _uiState.update { it.copy(resticStatus = transform(it.resticStatus)) }
            },
            emitEvent = { event -> _operationEvents.emit(event) },
        )

    init {
        load()
    }

    /**
     * Read config from file and refresh restic status.
     *
     * 审查报告 M3：磁盘读 + 凭据迁移移到 IO 调度器，避免在主线程做 I/O；
     * 公共签名保持不变（内部启动协程）。
     */
    fun load() {
        viewModelScope.launch {
            val (migrationResult, config) =
                withContext(Dispatchers.IO) {
                    val m = LegacyCredentialMigrator.migrate(configFile)
                    val c = BackupConfig.fromFile(configFile)
                    m to c
                }
            val backendDisplay = deriveBackendDisplay(config.resticBackend, config.resticRepo, config.resticBackendUrl)
            _uiState.update {
                it.copy(config = config, backendDisplay = backendDisplay)
            }
            if (migrationResult.migratedResticPassword || migrationResult.migratedBackendPass) {
                _uiState.update {
                    it.copy(
                        resticStatus =
                            it.resticStatus.copy(
                                message = "已迁移旧版明文密码到加密存储",
                            ),
                    )
                }
            }
            withContext(Dispatchers.IO) { readResticForm() }.let { refreshResticStatus(it) }
        }
    }

    /**
     * Build a [ResticForm] snapshot from the current state's config values.
     * 密码从 PasswordManager（加密存储）获取，不从配置文件读取。
     *
     * 审查报告 L1：已删除这里的重复密码迁移分支。凭据迁移由 [LegacyCredentialMigrator]
     * （在 [load]）与 [save] 各自负责，此处仅做读取 + 表单字段回填。
     * `c.resticPassword` 回退仅用于 [save] 表单路径（PasswordManager 初始化失败时
     * 仍能拿到用户刚输入的明文密码），属防御性回退。
     */
    private fun readResticForm() =
        _uiState.value.config.let { c ->
            val password = PasswordManager.getResticPassword() ?: c.resticPassword.takeIf { it.isNotEmpty() }
            val backendPass = PasswordManager.getBackendPass() ?: c.resticBackendPass.takeIf { it.isNotEmpty() }
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
     *
     * 当 [resticPassword] / [backendPass] 为 null 时，自动从 [formConfig] 提取密码
     * 并保存到 [PasswordManager]，确保 ConfigScreen 的调用也能正确持久化密码。
     */
    fun save(
        formConfig: BackupConfig,
        resticPassword: String? = null,
        backendPass: String? = null,
    ) {
        viewModelScope.launch {
            // 保存密码到加密存储
            val effectiveResticPassword =
                resticPassword
                    ?: formConfig.resticPassword.takeUnless { it.isNullOrEmpty() || it == "stored-in-keystore" }
            val effectiveBackendPass =
                backendPass
                    ?: formConfig.resticBackendPass.takeUnless { it.isNullOrEmpty() || it == "stored-in-keystore" }
            if (effectiveResticPassword != null && effectiveResticPassword.isNotEmpty()) {
                PasswordManager.setResticPassword(effectiveResticPassword)
            }
            if (effectiveBackendPass != null && effectiveBackendPass.isNotEmpty()) {
                PasswordManager.setBackendPass(effectiveBackendPass)
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
     * Writes the same on-disk config format. Passwords are stored as placeholders
     * in the exported file; actual passwords remain in EncryptedSharedPreferences.
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
                        // 如果 PasswordManager 和配置文件中都没有真实密码（例如跨设备导入），
                        // 置空密码字段，提示用户重新输入
                        val restoredResticPw =
                            realResticPw
                                ?: parsed.resticPassword.takeUnless { it == "stored-in-keystore" }
                                ?: ""
                        val restoredBackendPw =
                            realBackendPw
                                ?: parsed.resticBackendPass.takeUnless { it == "stored-in-keystore" }
                                ?: ""
                        val restoredConfig =
                            parsed.copy(
                                resticPassword = restoredResticPw,
                                resticBackendPass = restoredBackendPw,
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

    // ── Async restic operations (delegated to ResticOperationsCoordinator) ──

    fun initResticRepo(form: ResticForm) = resticOperationsCoordinator.initResticRepo(form)

    fun refreshResticStatus(form: ResticForm) = resticOperationsCoordinator.refreshResticStatus(form)

    fun unlockResticRepo(form: ResticForm) = resticOperationsCoordinator.unlockResticRepo(form)

    fun showResticStats(form: ResticForm) = resticOperationsCoordinator.showResticStats(form)

    fun pruneResticSnapshots(form: ResticForm) = resticOperationsCoordinator.pruneResticSnapshots(form)
}
