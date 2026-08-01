package com.example.androidbackupgui.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.androidbackupgui.backup.AndroidBackupServiceBridge
import com.example.androidbackupgui.backup.AppInfo
import com.example.androidbackupgui.backup.BackupConfig
import com.example.androidbackupgui.backup.BackupOperation
import com.example.androidbackupgui.backup.BackupProgressTracker
import com.example.androidbackupgui.backup.BackupServiceBridge
import com.example.androidbackupgui.backup.PackageName
import com.example.androidbackupgui.backup.TaskCancellationRegistry
import com.example.androidbackupgui.backup.WifiManager
import com.example.androidbackupgui.backup.core.AppError
import com.example.androidbackupgui.backup.core.AppResult
import com.example.androidbackupgui.backup.core.ErrorSuggestionFactory
import com.example.androidbackupgui.backup.restic.DefaultResticSessionFactory
import com.example.androidbackupgui.backup.restic.ResticSessionFactory
import com.example.androidbackupgui.backup.scan.AppScanner
import com.example.androidbackupgui.backup.security.CredentialProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

enum class SortMode { NAME_ASC, SIZE_DESC }

data class BackupUiState(
    val config: BackupConfig = BackupConfig(),
    val allApps: List<AppInfo> = emptyList(),
    val sortedApps: List<AppInfo> = emptyList(),
    val selectedApps: Set<String> = emptySet(),
    val excludeDataFromBackup: Set<String> = emptySet(),
    val sortMode: SortMode = SortMode.NAME_ASC,
    val showSystemApps: Boolean = false,
    val statusText: String = "请先扫描应用",
    val isRunning: Boolean = false,
    val isScanning: Boolean = false,
    val progressCurrent: Int = 0,
    val progressTotal: Int = 0,
    val progressStage: String = "",
    val progressPackageName: String = "",
    val progressMessage: String = "",
    val progressPercent: Float? = null,
    val taskId: String = "",
)

sealed interface BackupEvent {
    data class Error(
        val message: String,
    ) : BackupEvent

    data class BackupCompleted(
        val result: BackupOperation.BackupResult,
    ) : BackupEvent
}

class BackupViewModel(
    application: Application,
    /**
     * 与 [com.example.androidbackupgui.backup.BackupService] 通信的桥接器。
     *
     * 通过接口隔离 ViewModel 与 Service 实现细节：
     * - 测试时可注入 mock 验证调用参数
     * - 未来切换 Service 实现（如改用 WorkManager）无需改动 ViewModel
     *
     * 默认值 [AndroidBackupServiceBridge] 保持现有调用语义不变。
     */
    private val serviceBridge: BackupServiceBridge = AndroidBackupServiceBridge(),
    /**
     * 封装 restic 会话的配置。隐藏 [defaultResticWrapper] 的可变属性，
     * 测试时可注入返回固定实例的 mock。默认 [DefaultResticSessionFactory] 保持现状。
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
    constructor(application: Application) : this(
        application,
        AndroidBackupServiceBridge(),
        DefaultResticSessionFactory(),
    )

    companion object {
        private const val TAG = "BackupViewModel"
    }

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    private var currentJob: Job? = null

    init {
        val cfg = BackupConfig.fromFile(File(application.filesDir, "backup_settings.conf"))
        _state.update { it.copy(config = cfg) }
    }

    fun applySortAndFilter() {
        val s = _state.value
        val filtered = if (s.showSystemApps) s.allApps else s.allApps.filter { !it.isSystem }
        val sorted =
            when (s.sortMode) {
                SortMode.NAME_ASC -> filtered.sortedBy { it.label.lowercase(Locale.US) }
                SortMode.SIZE_DESC -> filtered.sortedByDescending { it.backupSize }
            }
        _state.update { it.copy(sortedApps = sorted) }
    }

    fun setSortMode(mode: SortMode) {
        _state.update { it.copy(sortMode = mode) }
        applySortAndFilter()
    }

    fun toggleShowSystem() {
        _state.update { it.copy(showSystemApps = !it.showSystemApps) }
        applySortAndFilter()
    }

    fun selectAll() {
        val pkgs =
            _state.value.sortedApps
                .map { it.packageName.value }
                .toSet()
        _state.update { it.copy(selectedApps = pkgs) }
    }

    fun clearSelection() {
        _state.update { it.copy(selectedApps = emptySet()) }
    }

    fun toggleApp(
        packageName: String,
        checked: Boolean,
    ) {
        _state.update { s ->
            s.copy(selectedApps = if (checked) s.selectedApps + packageName else s.selectedApps - packageName)
        }
    }

    fun toggleExcludeData(
        packageName: String,
        excluded: Boolean,
    ) {
        _state.update { s ->
            s.copy(excludeDataFromBackup = if (excluded) s.excludeDataFromBackup + packageName else s.excludeDataFromBackup - packageName)
        }
    }

    fun scanApps(context: Context) {
        if (_state.value.isScanning) return
        _state.update { it.copy(isScanning = true, statusText = "正在扫描应用…") }
        val config = _state.value.config

        currentJob =
            viewModelScope.launch {
                try {
                    val userId = config.backupUserId
                    val thirdParty = withContext(Dispatchers.IO) { AppScanner.scanThirdParty(context, userId = userId) }
                    val system = withContext(Dispatchers.IO) { AppScanner.scanSystem(context, config, userId = userId) }
                    val apps = if (_state.value.showSystemApps) thirdParty + system else thirdParty

                    val allPkgNames = apps.map { it.packageName.value }.toSet()
                    var excludeSet = emptySet<String>()

                    val appListFile = File(context.filesDir, "appList.txt")
                    if (appListFile.exists()) {
                        val content = appListFile.readText()
                        val parsed = AppScanner.parseAppList(content)
                        val fromPrefix = parsed.filter { it.first in allPkgNames && !it.second }.map { it.first }.toSet()
                        if (fromPrefix.isNotEmpty()) excludeSet = fromPrefix
                    }

                    _state.update {
                        it.copy(
                            allApps = apps,
                            sortedApps = apps,
                            selectedApps = allPkgNames,
                            excludeDataFromBackup = excludeSet,
                            statusText =
                                if (excludeSet.isNotEmpty()) {
                                    "共找到 ${apps.size} 个应用，${excludeSet.size} 个标记为仅APK"
                                } else {
                                    "共找到 ${apps.size} 个应用，全部已选中"
                                },
                            isScanning = false,
                        )
                    }
                } catch (e: Exception) {
                    _state.update { it.copy(statusText = "扫描应用失败: ${e.message}", isScanning = false) }
                }
            }
    }

    fun executeBackup(context: Context) {
        val s = _state.value
        val toBackup = s.allApps.filter { it.packageName.value in s.selectedApps }
        if (toBackup.isEmpty()) return

        val taskId = "backup_${UUID.randomUUID().toString().take(8)}"

        _state.update {
            it.copy(
                isRunning = true,
                taskId = taskId,
                statusText = "开始备份 ${toBackup.size} 个应用…",
                progressCurrent = 0,
                progressTotal = toBackup.size,
                progressStage = "",
                progressPackageName = "",
                progressMessage = "",
                progressPercent = null,
            )
        }

        val registration =
            TaskCancellationRegistry.register(taskId) {
                currentJob?.cancel()
            }

        currentJob =
            viewModelScope.launch {
                try {
                    serviceBridge.startTask(
                        context = context,
                        taskId = taskId,
                        taskType = BackupServiceBridge.TASK_TYPE_BACKUP,
                        statusText = "正在备份 ${toBackup.size} 个应用…",
                    )

                    val outputDir = File(s.config.outputPath.ifEmpty { context.filesDir.absolutePath })
                    val backupResult =
                        withContext(Dispatchers.IO) {
                            BackupOperation.backupApps(
                                context = context,
                                apps = toBackup,
                                config = s.config,
                                outputDir = outputDir,
                                userId = s.config.backupUserId.toString(),
                                noDataBackup = s.excludeDataFromBackup,
                                onProgress = { progress ->
                                    if (registration.cancelled.get()) {
                                        throw TaskCancellationRegistry.CancellationException(taskId)
                                    }
                                    _state.update {
                                        it.copy(
                                            statusText = "[${progress.current}/${progress.total}] ${progress.packageName}: ${progress.message}",
                                            progressCurrent = progress.current,
                                            progressTotal = progress.total,
                                            progressStage = progress.stage,
                                            progressPackageName = progress.packageName,
                                            progressMessage = progress.message,
                                            progressPercent = null,
                                        )
                                    }
                                    serviceBridge.updateProgress(
                                        context = context,
                                        taskId = taskId,
                                        taskType = BackupServiceBridge.TASK_TYPE_BACKUP,
                                        statusText = "[${progress.current}/${progress.total}] ${progress.packageName}",
                                        current = progress.current,
                                        total = progress.total,
                                        percent = null,
                                    )
                                },
                            )
                        }
                    val failed = backupResult.failCount
                    _state.update {
                        it.copy(
                            statusText = "备份${if (failed > 0) "完成（部分失败）" else "完成"}！成功: ${backupResult.successCount} 失败: $failed 耗时: ${backupResult.elapsedMs / 1000}s",
                            progressCurrent = backupResult.successCount,
                            progressTotal = toBackup.size,
                            progressStage = if (failed > 0) "partial" else "done",
                            progressPackageName = "",
                            progressMessage = if (failed > 0) "失败 $failed 个" else "完成",
                            progressPercent = null,
                        )
                    }

                    if (s.config.backupWifi == 1) {
                        WifiManager.backup(File(backupResult.outputDir))
                    }

                    if (s.config.resticEnabled == 1 && s.config.resticRepo.isNotBlank()) {
                        executeResticBackup(context, toBackup, s, backupResult, taskId)
                    }
                } catch (e: TaskCancellationRegistry.CancellationException) {
                    _state.update {
                        it.copy(
                            statusText = "备份已取消",
                            progressStage = "cancelled",
                            progressMessage = "已取消",
                        )
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    _state.update {
                        it.copy(
                            statusText = "备份已取消",
                            progressStage = "cancelled",
                            progressMessage = "已取消",
                        )
                    }
                    throw e
                } catch (e: Exception) {
                    val error =
                        when {
                            e.message?.contains("EPERM", ignoreCase = true) == true -> {
                                AppError.LocalIO("写入备份目录被拒绝", s.config.outputPath)
                            }

                            e.message?.contains("EACCES", ignoreCase = true) == true -> {
                                AppError.LocalIO("权限不足", s.config.outputPath)
                            }

                            e.message?.contains("timeout", ignoreCase = true) == true -> {
                                AppError.Network("网络超时", cause = e)
                            }

                            else -> {
                                AppError.LocalIO("备份异常: ${e.message}", s.config.outputPath, cause = e)
                            }
                        }
                    val errorInfo = ErrorSuggestionFactory.createSuggestion(error, "备份操作")
                    val errorMessage =
                        buildString {
                            append(errorInfo.message)
                            if (errorInfo.suggestion.isNotEmpty()) {
                                append("\n建议: ${errorInfo.suggestion}")
                            }
                        }
                    _state.update {
                        it.copy(
                            statusText = errorMessage,
                            progressStage = "partial",
                            progressMessage = e.message ?: "异常",
                            progressPercent = null,
                        )
                    }
                } finally {
                    _state.update {
                        it.copy(
                            isRunning = false,
                            progressPercent = null,
                        )
                    }
                    TaskCancellationRegistry.unregister(taskId)
                    serviceBridge.stopTask(context)
                }
            }
    }

    fun cancelBackup(context: Context) {
        val taskId = _state.value.taskId
        if (taskId.isNotEmpty()) {
            TaskCancellationRegistry.cancel(taskId)
        }
    }

    private suspend fun executeResticBackup(
        context: Context,
        toBackup: List<AppInfo>,
        s: BackupUiState,
        backupResult: BackupOperation.BackupResult,
        taskId: String,
    ) {
        val restic = resticSessionFactory.prepare(context, s.config.resticBackendDomain) ?: return
        val credentials = CredentialProvider.resolve(s.config)
        val password = credentials.resticPassword
        val backendPass = credentials.backendPass

        if (s.config.useStreaming == 1) {
            restic
                .backupStreaming(
                    apps = toBackup,
                    noDataBackup = s.excludeDataFromBackup,
                    legacyApps = null,
                    ownPackageName = context.packageName,
                    userId = s.config.backupUserId.toString(),
                    repoPath = s.config.resticRepo,
                    password = password,
                    tags = listOf("backup_${System.currentTimeMillis() / 1000}"),
                    hostname = "android-backup-gui",
                    backend = s.config.resticBackend,
                    backendUrl = s.config.resticBackendUrl,
                    backendUser = s.config.resticBackendUser,
                    backendPass = backendPass,
                    backendShare = s.config.resticBackendShare,
                    onProgress = { msg ->
                        val pct =
                            Regex("""(\d{1,3})(?:\.\d+)?%""")
                                .find(msg)
                                ?.groupValues
                                ?.get(1)
                                ?.toFloatOrNull()
                                ?.div(100f)
                                ?.coerceIn(0f, 1f)
                        _state.update {
                            it.copy(
                                statusText = msg,
                                progressStage = "restic",
                                progressMessage = msg,
                                progressPercent = pct,
                            )
                        }
                        serviceBridge.updateProgress(
                            context = context,
                            taskId = taskId,
                            taskType = BackupServiceBridge.TASK_TYPE_RESTIC,
                            statusText = msg,
                            current = 0,
                            total = 0,
                            percent = pct,
                        )
                    },
                ).let { result ->
                    when (result) {
                        is AppResult.Success -> {
                            val summary = result.getOrNull()
                            _state.update {
                                it.copy(
                                    statusText = "流式备份完成（不完整备份，仅包含部分数据）！ID: ${summary?.snapshotId?.take(
                                        8,
                                    )}… 新增: ${(summary?.dataAdded ?: 0) / 1024 / 1024} MB",
                                )
                            }
                        }

                        is AppResult.Failure -> {
                            _state.update {
                                it.copy(
                                    statusText = "流式备份失败: ${result.errorOrNull()?.message}",
                                    progressStage = "partial",
                                    progressMessage = "上传失败",
                                    progressPercent = null,
                                )
                            }
                        }
                    }
                }
        } else {
            restic
                .backup(
                    repoPath = s.config.resticRepo,
                    password = password,
                    paths = listOf(backupResult.outputDir),
                    tags = listOf("backup_${System.currentTimeMillis() / 1000}"),
                    hostname = "android-backup-gui",
                    backend = s.config.resticBackend,
                    backendUrl = s.config.resticBackendUrl,
                    backendUser = s.config.resticBackendUser,
                    backendPass = backendPass,
                    backendShare = s.config.resticBackendShare,
                    onProgress = { progress ->
                        if (progress.messageType == "status") {
                            _state.update {
                                it.copy(
                                    statusText =
                                        "去重仓库: %.0f%% (%d/%d 个文件)".format(
                                            progress.percentDone * 100,
                                            progress.filesDone,
                                            progress.totalFiles,
                                        ),
                                    progressStage = "restic",
                                    progressMessage = "上传中: %.0f%%".format(progress.percentDone * 100),
                                    progressPercent = progress.percentDone.toFloat(),
                                )
                            }
                            serviceBridge.updateProgress(
                                context = context,
                                taskId = taskId,
                                taskType = BackupServiceBridge.TASK_TYPE_RESTIC,
                                statusText = "上传中: %.0f%%".format(progress.percentDone * 100),
                                current = progress.filesDone,
                                total = progress.totalFiles,
                                percent = progress.percentDone.toFloat(),
                            )
                        }
                    },
                ).let { result ->
                    when (result) {
                        is AppResult.Success -> {
                            val summary = result.getOrNull()
                            _state.update {
                                it.copy(
                                    statusText = "备份完成！Restic ID: ${summary?.snapshotId?.take(
                                        8,
                                    )}… 新增: ${(summary?.dataAdded ?: 0) / 1024 / 1024} MB",
                                )
                            }
                        }

                        is AppResult.Failure -> {
                            _state.update {
                                it.copy(
                                    statusText = "restic 快照失败: ${result.errorOrNull()?.message}",
                                    progressStage = "partial",
                                    progressMessage = "上传失败",
                                    progressPercent = null,
                                )
                            }
                        }
                    }
                }
        }
    }
}
