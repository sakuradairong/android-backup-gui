package com.example.androidbackupgui.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.androidbackupgui.backup.AppInfo
import com.example.androidbackupgui.backup.BackupConfig
import com.example.androidbackupgui.backup.BackupFileIO
import com.example.androidbackupgui.backup.BackupOperation
import com.example.androidbackupgui.backup.BackupServiceBridge
import com.example.androidbackupgui.backup.AndroidBackupServiceBridge
import com.example.androidbackupgui.backup.PackageName
import com.example.androidbackupgui.backup.RestoreOperation
import com.example.androidbackupgui.backup.TaskCancellationRegistry
import com.example.androidbackupgui.backup.WifiManager
import com.example.androidbackupgui.backup.core.AppDetailsParser
import com.example.androidbackupgui.backup.restic.ResticSessionFactory
import com.example.androidbackupgui.backup.restic.DefaultResticSessionFactory
import com.example.androidbackupgui.backup.restic.ResticWrapper
import com.example.androidbackupgui.backup.scan.AppScanner
import com.example.androidbackupgui.backup.security.PasswordManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class RestoreUiState(
    val config: BackupConfig = BackupConfig(),
    val backupDir: File? = null,
    val packages: List<String> = emptyList(),
    val appInfos: List<AppInfo> = emptyList(),
    val selectedPackages: Set<String> = emptySet(),
    val resticConfig: BackupConfig? = null,
    val selectedSnapshot: ResticWrapper.ResticSnapshot? = null,
    val isRunning: Boolean = false,
    val statusText: String = "请选择备份源",
    val showSnapshotPicker: Boolean = false,
    val availableSnapshots: List<ResticWrapper.ResticSnapshot> = emptyList(),
    val progressCurrent: Int = 0,
    val progressTotal: Int = 0,
    val progressStage: String = "",
    val progressPackageName: String = "",
    val progressMessage: String = "",
    val progressPercent: Float? = null,
    val restoreWifi: Boolean = false,
    val showRestoreConfirm: Boolean = false,
    val taskId: String = "",
    val isStreamingBackup: Boolean = false,
)

class RestoreViewModel(
    application: Application,
    /**
     * 与 [com.example.androidbackupgui.backup.BackupService] 通信的桥接器。
     * 与 [BackupViewModel] 共用同一接口，便于测试时统一替换 mock。
     * 默认值 [AndroidBackupServiceBridge] 保持现有调用语义不变。
     */
    private val serviceBridge: BackupServiceBridge = AndroidBackupServiceBridge(),
    /**
     * 封装 restic 会话的配置。隐藏 [defaultResticWrapper] 的可变属性。
     * 默认 [DefaultResticSessionFactory] 保持现状。
     */
    private val resticSessionFactory: ResticSessionFactory = DefaultResticSessionFactory(),
) : AndroidViewModel(application) {
    /**
     * 供 Android [ViewModelProvider] 使用的无参注入构造函数。
     * 主构造函数保留默认参数以便测试注入 mock；运行时框架只识别此构造函数。
     */
    constructor(application: Application) : this(
        application,
        AndroidBackupServiceBridge(),
        DefaultResticSessionFactory(),
    )

    private val _state = MutableStateFlow(RestoreUiState())
    val state: StateFlow<RestoreUiState> = _state.asStateFlow()

    private var currentJob: Job? = null
    private val configFile = File(application.filesDir, "backup_settings.conf")

    init {
        val config = BackupConfig.fromFile(configFile)
        _state.update { it.copy(config = config) }
        if (config.resticEnabled == 1 && config.resticRepo.isNotBlank()) {
            _state.update { it.copy(resticConfig = config) }
        }
    }

    fun loadDefaultDir(context: Context) {
        viewModelScope.launch {
            try {
                val defaultDir = context.filesDir
                val backupDirs = withContext(Dispatchers.IO) {
                    defaultDir.listFiles()
                        ?.filter { it.isDirectory && it.name.startsWith("Backup_") }
                        ?: emptyList()
                }
                if (backupDirs.isNotEmpty()) {
                    val dir = backupDirs.first()
                    loadFromDir(context, dir)
                } else {
                    _state.update { it.copy(statusText = "未找到备份目录") }
                }
            } catch (e: Exception) {
                _state.update { it.copy(statusText = "选择目录失败: ${e.message}") }
            }
        }
    }

    fun loadFromSafUri(context: Context, uri: Uri) {
        val resolvedPath = resolveSafTreeUri(uri) ?: return
        val dir = File(resolvedPath)
        loadFromDir(context, dir)
    }

    private fun loadFromDir(context: Context, dir: File) {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    backupDir = dir,
                    selectedSnapshot = null,
                    packages = emptyList(),
                    appInfos = emptyList(),
                    selectedPackages = emptySet(),
                    restoreWifi = false,
                )
            }
            withContext(Dispatchers.IO) {
                loadFromDirSync(context, dir)
            }
        }
    }

    private suspend fun loadFromDirSync(context: Context, dir: File) {
        val appListFile = File(dir, "appList.txt")
        val pkgs = BackupFileIO.readTextFile(appListFile)?.let { content ->
            content.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { PackageName.safe(it)?.value }
        } ?: run {
            BackupFileIO.listBackupFiles(dir)
                ?.mapNotNull { PackageName.safe(it)?.value }
                ?: emptyList()
        }

        val validPkgs = pkgs.filter { pkg ->
            val apkFile = File(File(dir, pkg), "$pkg.apk")
            BackupFileIO.backupPathExists(apkFile)
        }

        val infos = withContext(Dispatchers.IO) {
            val cached = readLocalAppDetails(dir)
            val preLabeled = validPkgs.map { AppInfo(packageName = PackageName(it), label = cached[it] ?: "") }
            val resolved = AppScanner.resolveLabels(context, preLabeled)
            resolved.map { app ->
                val cachedLabel = cached[app.packageName.value]
                if (cachedLabel != null && app.label == app.packageName.value) {
                    app.copy(label = cachedLabel)
                } else {
                    app
                }
            }
        }

        _state.update {
            it.copy(
                packages = validPkgs,
                appInfos = infos,
                selectedPackages = emptySet(),
                restoreWifi = false,
                statusText = "共 ${validPkgs.size} 个备份应用",
                isStreamingBackup = File(dir, "streaming_manifest.json").exists(),
            )
        }
    }

    fun listResticSnapshots(context: Context) {
        val rc = _state.value.resticConfig ?: run {
            _state.update { it.copy(statusText = "未配置 Restic，请先在设置中配置") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isRunning = true, statusText = "正在读取快照…") }
            try {
                val restic = resticSessionFactory.prepare(context, rc.resticBackendDomain) ?: run {
                    _state.update { it.copy(statusText = "restic 不可用", isRunning = false) }
                    return@launch
                }

                val realPassword = configPw(PasswordManager.getResticPassword(), rc.resticPassword)
                val realBackendPass = configPw(PasswordManager.getBackendPass(), rc.resticBackendPass)
                val result = withContext(Dispatchers.IO) {
                    restic.listSnapshots(
                        rc.resticRepo, realPassword,
                        backend = rc.resticBackend, backendUrl = rc.resticBackendUrl,
                        backendUser = rc.resticBackendUser, backendPass = realBackendPass,
                        backendShare = rc.resticBackendShare,
                    )
                }
                if (result.isFailure) {
                    _state.update { it.copy(statusText = "读取快照失败: ${result.exceptionOrNull()?.message}", isRunning = false) }
                    return@launch
                }
                val snaps = result.getOrThrow()
                if (snaps.isEmpty()) {
                    _state.update { it.copy(statusText = "没有可用的 restic 快照", isRunning = false) }
                    return@launch
                }
                if (snaps.size == 1) {
                    loadResticSnapshot(context, snaps.first())
                } else {
                    _state.update {
                        it.copy(availableSnapshots = snaps, showSnapshotPicker = true, isRunning = false)
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(statusText = "选择快照失败: ${e.message}", isRunning = false) }
            }
        }
    }

    fun selectSnapshot(context: Context, snapshot: ResticWrapper.ResticSnapshot) {
        _state.update { it.copy(showSnapshotPicker = false, isRunning = true) }
        loadResticSnapshot(context, snapshot)
    }

    fun dismissSnapshotPicker() {
        _state.update { it.copy(showSnapshotPicker = false) }
    }

    private fun loadResticSnapshot(context: Context, snapshot: ResticWrapper.ResticSnapshot) {
        viewModelScope.launch {
            try {
                val rc = _state.value.resticConfig ?: return@launch
                val backupPath = snapshot.paths.firstOrNull() ?: run {
                    _state.update { it.copy(statusText = "快照中找不到备份路径", isRunning = false) }
                    return@launch
                }

                val realPassword = configPw(PasswordManager.getResticPassword(), rc.resticPassword)
                val realBackendPass = configPw(PasswordManager.getBackendPass(), rc.resticBackendPass)

                val restic = resticSessionFactory.prepare(context, rc.resticBackendDomain) ?: run {
                    _state.update { it.copy(statusText = "restic 不可用", isRunning = false) }
                    return@launch
                }

                suspend fun tryDump(path: String) = restic.dump(
                    rc.resticRepo, realPassword, snapshot.id, path,
                    backend = rc.resticBackend, backendUrl = rc.resticBackendUrl,
                    backendUser = rc.resticBackendUser, backendPass = realBackendPass,
                    backendShare = rc.resticBackendShare,
                ).getOrNull()

                val content = tryDump("$backupPath/appList.txt") ?: tryDump("$backupPath/meta/appList.txt")
                if (content == null) {
                    _state.update { it.copy(statusText = "无法从快照读取应用列表", isRunning = false) }
                    return@launch
                }
                val pkgs = content.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .mapNotNull { PackageName.safe(it)?.value }

                val cachedLabels = loadResticAppDetails(context, rc, snapshot.id, backupPath)
                val preLabeled = pkgs.map { AppInfo(packageName = PackageName(it), label = cachedLabels[it] ?: "") }
                val resolved = AppScanner.resolveLabels(context, preLabeled)
                val infos = resolved.map { app ->
                    val cachedLabel = cachedLabels[app.packageName.value]
                    if (cachedLabel != null && app.label == app.packageName.value) {
                        app.copy(label = cachedLabel)
                    } else {
                        app
                    }
                }

                _state.update {
                    it.copy(
                        backupDir = null,
                        selectedSnapshot = snapshot,
                        packages = pkgs,
                        appInfos = infos,
                        selectedPackages = emptySet(),
                        restoreWifi = false,
                        statusText = "restic 快照共 ${pkgs.size} 个应用",
                        isRunning = false,
                        isStreamingBackup = false,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(statusText = "加载快照失败: ${e.message}", isRunning = false) }
            }
        }
    }

    fun toggleApp(packageName: String, checked: Boolean) {
        _state.update { s ->
            s.copy(selectedPackages = if (checked) s.selectedPackages + packageName else s.selectedPackages - packageName)
        }
    }

    fun selectAll() {
        _state.update { it.copy(selectedPackages = it.packages.toSet()) }
    }

    fun clearSelection() {
        _state.update { it.copy(selectedPackages = emptySet()) }
    }

    fun toggleRestoreWifi(enabled: Boolean) {
        _state.update { it.copy(restoreWifi = enabled) }
    }

    fun requestRestore() {
        val s = _state.value
        val toRestore = s.packages.filter { it in s.selectedPackages }
        if (toRestore.isEmpty()) return
        if (s.backupDir == null && s.selectedSnapshot == null) return
        _state.update { it.copy(showRestoreConfirm = true) }
    }

    fun dismissRestoreConfirm() {
        _state.update { it.copy(showRestoreConfirm = false) }
    }

    fun confirmRestore(context: Context) {
        val s = _state.value
        val toRestore = s.packages.filter { it in s.selectedPackages }
        if (toRestore.isEmpty()) return

        _state.update { it.copy(showRestoreConfirm = false) }

        val taskId = "restore_${UUID.randomUUID().toString().take(8)}"

        _state.update {
            it.copy(
                isRunning = true,
                taskId = taskId,
                statusText = "开始恢复 ${toRestore.size} 个应用…",
                progressCurrent = 0,
                progressTotal = toRestore.size,
                progressStage = "",
                progressPackageName = "",
                progressMessage = "",
                progressPercent = null,
            )
        }

        val registration = TaskCancellationRegistry.register(taskId) {
            currentJob?.cancel()
        }

        currentJob = viewModelScope.launch {
            try {
                serviceBridge.startTask(
                    context = context,
                    taskId = taskId,
                    taskType = BackupServiceBridge.TASK_TYPE_RESTORE,
                    statusText = "正在恢复 ${toRestore.size} 个应用…",
                )

                if (s.selectedSnapshot != null && s.resticConfig != null) {
                    executeResticRestore(context, s, taskId, registration)
                } else if (s.backupDir != null) {
                    executeLocalRestore(context, s, taskId, registration)
                }
            } catch (e: TaskCancellationRegistry.CancellationException) {
                _state.update {
                    it.copy(statusText = "恢复已取消", progressStage = "cancelled", progressMessage = "已取消")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.update {
                    it.copy(statusText = "恢复已取消", progressStage = "cancelled", progressMessage = "已取消")
                }
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        statusText = "恢复异常: ${e.message}",
                        progressMessage = e.message ?: "异常",
                        progressStage = "partial",
                    )
                }
            } finally {
                _state.update { it.copy(isRunning = false, progressPercent = null) }
                TaskCancellationRegistry.unregister(taskId)
                serviceBridge.stopTask(context)
            }
        }
    }

    private suspend fun executeResticRestore(
        context: Context,
        s: RestoreUiState,
        taskId: String,
        registration: TaskCancellationRegistry.Registration,
    ) {
        val snapshot = s.selectedSnapshot!!
        val config = s.resticConfig!!
        val backupPath = snapshot.paths.firstOrNull() ?: return
        val staging = File(context.cacheDir, "restic_restore_${snapshot.shortId}")
        staging.mkdirs()

        try {
            _state.update {
                it.copy(statusText = "正在从 restic 快照恢复…", progressStage = "restic", progressMessage = "正在拉取快照…", progressPercent = null)
            }
            updateServiceNotification(
                context = context,
                taskId = taskId,
                taskType = BackupServiceBridge.TASK_TYPE_RESTIC,
                statusText = "正在拉取快照…",
                current = 0,
                total = 0,
                percent = null,
            )

            val restic = resticSessionFactory.prepare(context, config.resticBackendDomain) ?: run {
                _state.update {
                    it.copy(
                        statusText = "restic 不可用",
                        progressMessage = "restic 不可用",
                        progressStage = "partial",
                    )
                }
                return
            }

            val restoreResult = withContext(Dispatchers.IO) {
                val rPw = PasswordManager.getResticPassword()?.takeIf { it != "stored-in-keystore" } ?: config.resticPassword
                val rBpw = PasswordManager.getBackendPass()?.takeIf { it != "stored-in-keystore" } ?: config.resticBackendPass
                restic.restore(
                    repoPath = config.resticRepo, password = rPw,
                    snapshotId = snapshot.id, targetPath = staging.absolutePath,
                    backend = config.resticBackend, backendUrl = config.resticBackendUrl,
                    backendUser = config.resticBackendUser, backendPass = rBpw,
                    backendShare = config.resticBackendShare,
                    onProgress = { msg ->
                        if (registration.cancelled.get()) throw TaskCancellationRegistry.CancellationException(taskId)
                        _state.update { it.copy(statusText = msg, progressMessage = msg) }
                        val pct = Regex("""(\d{1,3})(?:\.\d+)?%""").find(msg)
                            ?.groupValues?.get(1)?.toFloatOrNull()?.div(100f)?.coerceIn(0f, 1f)
                        _state.update { it.copy(progressPercent = pct) }
                        updateServiceNotification(
                            context = context,
                            taskId = taskId,
                            taskType = BackupServiceBridge.TASK_TYPE_RESTIC,
                            statusText = msg,
                            current = 0,
                            total = 0,
                            percent = pct,
                        )
                    },
                )
            }
            if (restoreResult.isFailure) {
                _state.update {
                    it.copy(
                        statusText = "restic 恢复失败: ${restoreResult.exceptionOrNull()?.message}",
                        progressMessage = "restic 恢复失败",
                        selectedSnapshot = null, packages = emptyList(), appInfos = emptyList(), selectedPackages = emptySet(),
                    )
                }
                return
            }

            val restoredDir = File(staging, backupPath.removePrefix("/"))
            _state.update { it.copy(statusText = "正在从恢复的备份安装应用…", progressPercent = null) }

            val result = withContext(Dispatchers.IO) {
                RestoreOperation.restoreApps(
                    context = context, backupDir = restoredDir,
                    userId = config.backupUserId.toString(), filterPkgs = s.selectedPackages,
                    onProgress = { progress ->
                        if (registration.cancelled.get()) throw TaskCancellationRegistry.CancellationException(taskId)
                        _state.update {
                            it.copy(
                                statusText = "[${progress.current}/${progress.total}] ${progress.packageName}: ${progress.message}",
                                progressCurrent = progress.current, progressTotal = progress.total,
                                progressStage = progress.stage, progressPackageName = progress.packageName,
                                progressMessage = progress.message,
                            )
                        }
                        updateServiceNotification(
                            context = context,
                            taskId = taskId,
                            taskType = BackupServiceBridge.TASK_TYPE_RESTORE,
                            statusText = "[${progress.current}/${progress.total}] ${progress.packageName}",
                            current = progress.current,
                            total = progress.total,
                            percent = null,
                        )
                    },
                )
            }
            val wifiOk = if (s.restoreWifi) WifiManager.restore(restoredDir) else true
            val failed = result.failCount
            _state.update {
                it.copy(
                    statusText = buildString {
                        appendLine("恢复${if (failed > 0) "完成（部分失败）" else "完成！"}")
                        appendLine("成功: ${result.successCount} 失败: $failed")
                        if (s.restoreWifi && !wifiOk) appendLine("Wi-Fi 恢复失败")
                        append("耗时: ${result.elapsedMs / 1000}秒")
                    },
                    progressCurrent = result.successCount,
                    progressStage = if (failed > 0) "partial" else "done",
                    progressMessage = if (failed > 0) "失败 $failed 个" else "完成",
                    progressPercent = null,
                )
            }
        } finally {
            try { staging.deleteRecursively() } catch (_: Exception) {}
        }
    }

    private suspend fun executeLocalRestore(
        context: Context,
        s: RestoreUiState,
        taskId: String,
        registration: TaskCancellationRegistry.Registration,
    ) {
        val dir = s.backupDir!!
        val result = withContext(Dispatchers.IO) {
            RestoreOperation.restoreApps(
                context = context, backupDir = dir,
                userId = s.config.backupUserId.toString(), filterPkgs = s.selectedPackages,
                onProgress = { progress ->
                    if (registration.cancelled.get()) throw TaskCancellationRegistry.CancellationException(taskId)
                    _state.update {
                        it.copy(
                            statusText = "[${progress.current}/${progress.total}] ${progress.packageName}: ${progress.message}",
                            progressCurrent = progress.current, progressTotal = progress.total,
                            progressStage = progress.stage, progressPackageName = progress.packageName,
                            progressMessage = progress.message,
                        )
                    }
                    updateServiceNotification(
                        context = context,
                        taskId = taskId,
                        taskType = BackupServiceBridge.TASK_TYPE_RESTORE,
                        statusText = "[${progress.current}/${progress.total}] ${progress.packageName}",
                        current = progress.current,
                        total = progress.total,
                        percent = null,
                    )
                },
            )
        }
        val wifiOk = if (s.restoreWifi) WifiManager.restore(dir) else true
        val failed = result.failCount
        _state.update {
            it.copy(
                statusText = buildString {
                    appendLine("恢复${if (failed > 0) "完成（部分失败）" else "完成！"}")
                    appendLine("成功: ${result.successCount} 失败: $failed")
                    if (s.restoreWifi && !wifiOk) appendLine("Wi-Fi 恢复失败")
                    append("耗时: ${result.elapsedMs / 1000}秒")
                },
                progressCurrent = result.successCount,
                progressStage = if (failed > 0) "partial" else "done",
                progressMessage = if (failed > 0) "失败 $failed 个" else "完成",
                progressPercent = null,
            )
        }
    }

    fun cancelRestore() {
        val taskId = _state.value.taskId
        if (taskId.isNotEmpty()) {
            TaskCancellationRegistry.cancel(taskId)
        }
    }

    private fun updateServiceNotification(
        context: Context, taskId: String, taskType: String,
        statusText: String, current: Int, total: Int, percent: Float?,
    ) {
        serviceBridge.updateProgress(
            context = context,
            taskId = taskId,
            taskType = taskType,
            statusText = statusText,
            current = current,
            total = total,
            percent = percent,
        )
    }

    private fun configPw(key: String?, fallback: String): String =
        key?.takeIf { it.isNotEmpty() && it != "stored-in-keystore" } ?: fallback

    private suspend fun readLocalAppDetails(dir: File): Map<String, String> =
        withContext(Dispatchers.IO) {
            val metaFile = File(dir, "app_details.json")
            val json = BackupFileIO.readTextFile(metaFile) ?: return@withContext emptyMap()
            try {
                AppDetailsParser.parse(json).mapValues { it.value.label }
            } catch (_: Exception) {
                emptyMap()
            }
        }

    private suspend fun loadResticAppDetails(
        context: Context,
        config: BackupConfig,
        snapshotId: String,
        backupPath: String,
    ): Map<String, String> {
        val restic = resticSessionFactory.prepare(context, config.resticBackendDomain) ?: return emptyMap()
        val realPassword = configPw(PasswordManager.getResticPassword(), config.resticPassword)
        val realBackendPass = configPw(PasswordManager.getBackendPass(), config.resticBackendPass)

        suspend fun tryDump(path: String) = restic.dump(
            config.resticRepo, realPassword, snapshotId, path,
            backend = config.resticBackend, backendUrl = config.resticBackendUrl,
            backendUser = config.resticBackendUser, backendPass = realBackendPass,
            backendShare = config.resticBackendShare,
        ).getOrNull()

        val json = tryDump("$backupPath/app_details.json") ?: tryDump("$backupPath/meta/app_details.json") ?: return emptyMap()
        return try {
            AppDetailsParser.parse(json).mapValues { it.value.label }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    companion object {
        fun resolveSafTreeUri(uri: Uri): String? {
            val docId = uri.lastPathSegment?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: return null
            val colonIdx = docId.indexOf(':')
            if (colonIdx < 0) return null
            val storageId = docId.substring(0, colonIdx)
            val relPath = docId.substring(colonIdx + 1).trim('/')
            return if (storageId.equals("primary", ignoreCase = true)) {
                "/storage/emulated/0/$relPath"
            } else {
                "/storage/$storageId/$relPath"
            }
        }
    }
}
