package com.example.androidbackupgui.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.androidbackupgui.backup.*
import com.example.androidbackupgui.backup.core.AppError
import com.example.androidbackupgui.backup.core.AppResult
import com.example.androidbackupgui.backup.core.ErrorSuggestionFactory
import com.example.androidbackupgui.backup.restic.defaultResticWrapper
import com.example.androidbackupgui.backup.scan.AppScanner
import com.example.androidbackupgui.backup.security.CredentialProvider
import com.example.androidbackupgui.backup.security.ResticBinary
import com.example.androidbackupgui.backup.BackupService.Companion.ACTION_START_TASK
import com.example.androidbackupgui.backup.BackupService.Companion.ACTION_STOP_TASK
import com.example.androidbackupgui.backup.BackupService.Companion.EXTRA_STATUS_TEXT
import com.example.androidbackupgui.backup.BackupService.Companion.EXTRA_TASK_ID
import com.example.androidbackupgui.backup.BackupService.Companion.EXTRA_TASK_TYPE
import com.example.androidbackupgui.backup.BackupService.Companion.EXTRA_PROGRESS_CURRENT
import com.example.androidbackupgui.backup.BackupService.Companion.EXTRA_PROGRESS_TOTAL
import com.example.androidbackupgui.backup.BackupService.Companion.EXTRA_PROGRESS_PERCENT
import com.example.androidbackupgui.backup.BackupService.Companion.TASK_TYPE_BACKUP
import com.example.androidbackupgui.backup.BackupService.Companion.TASK_TYPE_RESTIC
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
) : AndroidViewModel(application) {
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

        val registration = TaskCancellationRegistry.register(taskId) {
            currentJob?.cancel()
        }

        currentJob =
            viewModelScope.launch {
                try {
                    val serviceIntent =
                        Intent(context, BackupService::class.java).apply {
                            action = ACTION_START_TASK
                            putExtra(EXTRA_STATUS_TEXT, "正在备份 ${toBackup.size} 个应用…")
                            putExtra(EXTRA_TASK_ID, taskId)
                            putExtra(EXTRA_TASK_TYPE, TASK_TYPE_BACKUP)
                        }
                    try {
                        ContextCompat.startForegroundService(context, serviceIntent)
                    } catch (_: Exception) {
                    }

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
                                    updateServiceNotification(context, taskId, TASK_TYPE_BACKUP,
                                        "[${progress.current}/${progress.total}] ${progress.packageName}",
                                        progress.current, progress.total, null)
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
                } catch (e: Exception) {
                    val error = when {
                        e.message?.contains("EPERM", ignoreCase = true) == true ->
                            AppError.LocalIO("写入备份目录被拒绝", s.config.outputPath)
                        e.message?.contains("EACCES", ignoreCase = true) == true ->
                            AppError.LocalIO("权限不足", s.config.outputPath)
                        e.message?.contains("timeout", ignoreCase = true) == true ->
                            AppError.Network("网络超时", cause = e)
                        else ->
                            AppError.LocalIO("备份异常: ${e.message}", s.config.outputPath, cause = e)
                    }
                    val errorInfo = ErrorSuggestionFactory.createSuggestion(error, "备份操作")
                    val errorMessage = buildString {
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
                    try {
                        context.startService(Intent(context, BackupService::class.java).apply { action = ACTION_STOP_TASK })
                    } catch (_: Exception) {
                    }
                }
            }
    }

    fun cancelBackup(context: Context) {
        val taskId = _state.value.taskId
        if (taskId.isNotEmpty()) {
            TaskCancellationRegistry.cancel(taskId)
        }
    }

    private fun updateServiceNotification(
        context: Context,
        taskId: String,
        taskType: String,
        statusText: String,
        current: Int,
        total: Int,
        percent: Float?,
    ) {
        try {
            val intent = Intent(context, BackupService::class.java).apply {
                action = BackupService.ACTION_UPDATE_TASK
                putExtra(EXTRA_STATUS_TEXT, statusText)
                putExtra(EXTRA_TASK_ID, taskId)
                putExtra(EXTRA_TASK_TYPE, taskType)
                putExtra(EXTRA_PROGRESS_CURRENT, current)
                putExtra(EXTRA_PROGRESS_TOTAL, total)
                percent?.let { putExtra(EXTRA_PROGRESS_PERCENT, it) }
            }
            ContextCompat.startForegroundService(context, intent)
        } catch (_: Exception) {
        }
    }

    private suspend fun executeResticBackup(
        context: Context,
        toBackup: List<AppInfo>,
        s: BackupUiState,
        backupResult: BackupOperation.BackupResult,
        taskId: String,
    ) {
        val binaryPath = ResticBinary.prepare(context) ?: return
        defaultResticWrapper.binaryPath = binaryPath
        defaultResticWrapper.cacheDir = context.cacheDir.absolutePath
        defaultResticWrapper.backendDomain = s.config.resticBackendDomain
        val credentials = CredentialProvider.resolve(s.config)
        val password = credentials.resticPassword
        val backendPass = credentials.backendPass

        if (s.config.useStreaming == 1) {
            defaultResticWrapper
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
                        updateServiceNotification(context, taskId, TASK_TYPE_RESTIC, msg, 0, 0, pct)
                    },
                ).let { result ->
                    when (result) {
                        is AppResult.Success -> {
                            val summary = result.getOrNull()
                            _state.update {
                                it.copy(
                                    statusText = "流式备份完成！ID: ${summary?.snapshotId?.take(
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
            defaultResticWrapper
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
                            updateServiceNotification(context, taskId, TASK_TYPE_RESTIC,
                                "上传中: %.0f%%".format(progress.percentDone * 100),
                                progress.filesDone, progress.totalFiles, progress.percentDone.toFloat())
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
