package com.example.androidbackupgui.ui

import android.content.Intent
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.androidbackupgui.backup.*
import com.example.androidbackupgui.backup.AppResult
import com.example.androidbackupgui.backup.BackupService.Companion.ACTION_START_BACKUP
import com.example.androidbackupgui.backup.BackupService.Companion.ACTION_STOP_BACKUP
import com.example.androidbackupgui.backup.BackupService.Companion.EXTRA_STATUS_TEXT
import com.example.androidbackupgui.backup.ResticBinary
import com.example.androidbackupgui.backup.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private enum class SortMode { NAME_ASC, SIZE_DESC }

@Composable
fun BackupScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ── State ──
    var config by remember { mutableStateOf(BackupConfig()) }
    var allApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var sortedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var selectedApps by remember { mutableStateOf<Set<String>>(emptySet()) }
    var excludeDataFromBackup by remember { mutableStateOf<Set<String>>(emptySet()) }
    var sortMode by remember { mutableStateOf(SortMode.NAME_ASC) }
    var showSystemApps by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("请先扫描应用") }
    var isRunning by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(false) }

    // Load config
    LaunchedEffect(Unit) {
        config = BackupConfig.fromFile(File(context.filesDir, "backup_settings.conf"))
    }

    // Re-apply sort/filter when dependencies change
    LaunchedEffect(allApps, sortMode, showSystemApps) {
        val filtered = if (showSystemApps) allApps else allApps.filter { !it.isSystem }
        val sorted =
            when (sortMode) {
                SortMode.NAME_ASC -> filtered.sortedBy { it.label.lowercase(Locale.US) }
                SortMode.SIZE_DESC -> filtered.sortedByDescending { it.backupSize }
            }
        sortedApps = sorted
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Top controls card ──
        Card(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Scan button
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            isScanning = true
                            statusText = "正在扫描应用…"
                            scope.launch {
                                try {
                                    val userId = config.backupUserId
                                    val thirdParty =
                                        withContext(Dispatchers.IO) {
                                            AppScanner.scanThirdParty(context, userId = userId)
                                        }
                                    val system =
                                        withContext(Dispatchers.IO) {
                                            AppScanner.scanSystem(context, config, userId = userId)
                                        }
                                    val apps = if (showSystemApps) thirdParty + system else thirdParty
                                    allApps = apps
                                    val allPkgNames = apps.map { it.packageName.value }.toSet()
                                    selectedApps = allPkgNames

                                    // Check for appList.txt with '!' prefix (no-data-backup markers)
                                    val appListFile = File(context.filesDir, "appList.txt")
                                    if (appListFile.exists()) {
                                        val content = appListFile.readText()
                                        val parsed = AppScanner.parseAppList(content)
                                        val excludeFromPrefix =
                                            parsed
                                                .filter { it.first in allPkgNames && !it.second }
                                                .map { it.first }
                                                .toSet()
                                        if (excludeFromPrefix.isNotEmpty()) {
                                            excludeDataFromBackup = excludeFromPrefix
                                            statusText = "共找到 ${apps.size} 个应用，${excludeFromPrefix.size} 个标记为仅APK"
                                        } else {
                                            statusText = "共找到 ${apps.size} 个应用，全部已选中"
                                        }
                                    } else {
                                        statusText = "共找到 ${apps.size} 个应用，全部已选中"
                                    }
                                } catch (e: Exception) {
                                    statusText = "扫描应用失败: ${e.message}"
                                } finally {
                                    isScanning = false
                                }
                            }
                        },
                        enabled = !isScanning && !isRunning,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (isScanning) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("扫描应用")
                    }
                }

                // Sort/filter row
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(
                        selected = sortMode == SortMode.NAME_ASC,
                        onClick = {
                            sortMode = SortMode.NAME_ASC
                        },
                        label = { Text("A-Z") },
                        leadingIcon = {
                            Icon(Icons.Default.SortByAlpha, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                    )
                    FilterChip(
                        selected = sortMode == SortMode.SIZE_DESC,
                        onClick = {
                            sortMode = SortMode.SIZE_DESC
                        },
                        label = { Text("大小") },
                        leadingIcon = {
                            Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        selectedApps = sortedApps.map { it.packageName.value }.toSet()
                    }) { Text("全选") }
                    TextButton(onClick = { selectedApps = emptySet() }) { Text("取消全选") }
                }

                // Show system switch
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("显示系统应用", modifier = Modifier.weight(1f))
                    Switch(checked = showSystemApps, onCheckedChange = { showSystemApps = it })
                }
            }
        }

        // ── Status ──
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )

        // ── App list ──
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(sortedApps, key = { it.packageName.value }) { app ->
                AppListItem(
                    app = app,
                    isSelected = app.packageName.value in selectedApps,
                    isDataExcluded = app.packageName.value in excludeDataFromBackup,
                    onToggle = { checked ->
                        selectedApps =
                            if (checked) {
                                selectedApps + app.packageName.value
                            } else {
                                selectedApps - app.packageName.value
                            }
                    },
                    onExcludeDataToggle = { excluded ->
                        excludeDataFromBackup =
                            if (excluded) {
                                excludeDataFromBackup + app.packageName.value
                            } else {
                                excludeDataFromBackup - app.packageName.value
                            }
                    },
                )
            }
        }

        // ── Bottom bar with backup button ──
        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 3.dp,
        ) {
            Button(
                onClick = {
                    val toBackup = allApps.filter { it.packageName.value in selectedApps }
                    if (toBackup.isEmpty()) return@Button
                    isRunning = true
                    statusText = "开始备份 ${toBackup.size} 个应用…"

                    scope.launch {
                        try {
                            // 1. Start foreground service
                            val serviceIntent =
                                Intent(context, BackupService::class.java).apply {
                                    action = ACTION_START_BACKUP
                                    putExtra(EXTRA_STATUS_TEXT, "正在备份 ${toBackup.size} 个应用…")
                                }
                            try {
                                ContextCompat.startForegroundService(context, serviceIntent)
                            } catch (_: Exception) {
                            }

                            // 2. Execute backup
                            val outputDir =
                                File(
                                    config.outputPath.ifEmpty {
                                        context.filesDir.absolutePath
                                    },
                                )
                            val backupResult =
                                withContext(Dispatchers.IO) {
                                    BackupOperation.backupApps(
                                        context = context,
                                        apps = toBackup,
                                        config = config,
                                        outputDir = outputDir,
                                        userId = config.backupUserId.toString(),
                                        noDataBackup = excludeDataFromBackup,
                                        onProgress = { progress ->
                                            statusText =
                                                "[${progress.current}/${progress.total}] ${progress.packageName}: ${progress.message}"
                                        },
                                    )
                                }
                            statusText =
                                "备份完成！成功: ${backupResult.successCount} 失败: ${backupResult.failCount} 耗时: ${backupResult.elapsedMs / 1000}s"

                            // 3. WiFi 备份
                            WifiManager.backup(File(backupResult.outputDir))

                            // 4. Restic 上传（如启用）
                            if (config.resticEnabled == 1 && config.resticRepo.isNotBlank()) {
                                val binaryPath = ResticBinary.prepare(context)
                                if (binaryPath != null) {
                                    ResticWrapper.binaryPath = binaryPath
                                    ResticWrapper.cacheDir = context.cacheDir.absolutePath
                                    ResticWrapper.backendDomain = config.resticBackendDomain

                                    if (config.useStreaming == 1) {
                                        // ── Streaming path ──
                                        statusText = "正在流式备份到 restic 去重仓库…"
                                        val resticResult =
                                            withContext(Dispatchers.IO) {
                                                ResticWrapper.backupStreaming(
                                                    apps = toBackup,
                                                    noDataBackup = excludeDataFromBackup,
                                                    legacyApps = null,
                                                    userId = config.backupUserId.toString(),
                                                    repoPath = config.resticRepo,
                                                    password = config.resticPassword,
                                                    tags = listOf("backup_${System.currentTimeMillis() / 1000}"),
                                                    hostname = "android-backup-gui",
                                                    backend = config.resticBackend,
                                                    backendUrl = config.resticBackendUrl,
                                                    backendUser = config.resticBackendUser,
                                                    backendPass = config.resticBackendPass,
                                                    backendShare = config.resticBackendShare,
                                                    onProgress = { msg -> statusText = msg },
                                                )
                                            }
                                        when (resticResult) {
                                            is AppResult.Success -> {
                                                val summary = resticResult.getOrNull()
                                                statusText =
                                                    buildString {
                                                        appendLine("流式备份完成！")
                                                        appendLine("Restic ID: ${summary?.snapshotId?.take(8)}…")
                                                        if (summary != null) {
                                                            appendLine("新增: ${summary.dataAdded / 1024 / 1024} MB")
                                                            appendLine("文件: ${summary.totalFilesProcessed}")
                                                        }
                                                    }
                                            }

                                            is AppResult.Failure -> {
                                                statusText = "流式备份失败: ${resticResult.errorOrNull()?.message}"
                                            }
                                        }
                                    } else {
                                        // ── Standard path (staging dir) ──
                                        statusText = "正在写入 restic 去重仓库…"
                                        val resticResult =
                                            withContext(Dispatchers.IO) {
                                                ResticWrapper.backup(
                                                    repoPath = config.resticRepo,
                                                    password = config.resticPassword,
                                                    paths = listOf(backupResult.outputDir),
                                                    tags = listOf("backup_${System.currentTimeMillis() / 1000}"),
                                                    hostname = "android-backup-gui",
                                                    backend = config.resticBackend,
                                                    backendUrl = config.resticBackendUrl,
                                                    backendUser = config.resticBackendUser,
                                                    backendPass = config.resticBackendPass,
                                                    backendShare = config.resticBackendShare,
                                                    onProgress = { progress ->
                                                        if (progress.messageType == "status") {
                                                            statusText =
                                                                "去重仓库: %.0f%% (%d/%d 个文件)".format(
                                                                    progress.percentDone * 100,
                                                                    progress.filesDone,
                                                                    progress.totalFiles,
                                                                )
                                                        }
                                                    },
                                                )
                                            }
                                        when (resticResult) {
                                            is AppResult.Success -> {
                                                val summary = resticResult.getOrNull()
                                                statusText =
                                                    buildString {
                                                        appendLine("备份完成！")
                                                        appendLine("成功: ${backupResult.successCount} 失败: ${backupResult.failCount}")
                                                        appendLine("耗时: ${backupResult.elapsedMs / 1000}秒")
                                                        appendLine("Restic ID: ${summary?.snapshotId?.take(8)}…")
                                                        if (summary != null) {
                                                            appendLine("新增: ${summary.dataAdded / 1024 / 1024} MB")
                                                        }
                                                    }
                                            }

                                            is AppResult.Failure -> {
                                                statusText = "restic 快照失败: ${resticResult.errorOrNull()?.message}"
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            val errMsg = e.message ?: "未知错误"
                            Log.e("BackupScreen", "备份异常", e)
                            val hint =
                                when {
                                    errMsg.contains("EPERM", ignoreCase = true) ||
                                        errMsg.contains("Operation not permitted", ignoreCase = true) -> {
                                        "写入备份目录被拒绝，请检查输出路径权限或改用内置存储"
                                    }

                                    errMsg.contains(
                                        "EACCES",
                                        ignoreCase = true,
                                    ) || errMsg.contains("Permission denied", ignoreCase = true) -> {
                                        "权限不足，请检查存储权限"
                                    }

                                    else -> {
                                        null
                                    }
                                }
                            statusText = if (hint != null) "备份异常: ${e.message} ($hint)" else "备份异常: ${e.message}"
                        } finally {
                            isRunning = false
                            try {
                                val stopIntent =
                                    Intent(context, BackupService::class.java).apply {
                                        action = ACTION_STOP_BACKUP
                                    }
                                context.startService(stopIntent)
                            } catch (_: Exception) {
                            }
                        }
                    }
                },
                enabled = !isRunning && selectedApps.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            ) {
                if (isRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text("开始备份 (${selectedApps.size})")
            }
        }
    }
}

@Composable
private fun AppListItem(
    app: AppInfo,
    isSelected: Boolean,
    isDataExcluded: Boolean,
    onToggle: (Boolean) -> Unit,
    onExcludeDataToggle: (Boolean) -> Unit,
) {
    Card(
        onClick = { onToggle(!isSelected) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = isSelected, onCheckedChange = { onToggle(it) })
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label.ifEmpty { app.packageName.value },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = app.packageName.value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isSelected) {
                TextButton(onClick = { onExcludeDataToggle(!isDataExcluded) }) {
                    Text(
                        "数据",
                        textDecoration = if (isDataExcluded) TextDecoration.LineThrough else TextDecoration.None,
                        color =
                            if (isDataExcluded) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                    )
                }
            }
        }
    }
}
