package com.example.androidbackupgui.ui
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.androidbackupgui.backup.*
import com.example.androidbackupgui.backup.core.AppError
import com.example.androidbackupgui.backup.core.AppResult
import com.example.androidbackupgui.backup.restic.ResticWrapper
import com.example.androidbackupgui.backup.restic.defaultResticWrapper
import com.example.androidbackupgui.backup.AppInfo
import com.example.androidbackupgui.backup.PackageName
import com.example.androidbackupgui.backup.scan.AppScanner
import com.example.androidbackupgui.backup.security.PasswordManager
import com.example.androidbackupgui.backup.security.ResticBinary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun RestoreScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ── State ──
    var backupDir by remember { mutableStateOf<File?>(null) }
    var packages by remember { mutableStateOf<List<String>>(emptyList()) }
    var appInfos by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var selectedPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var resticConfig by remember { mutableStateOf<BackupConfig?>(null) }
    var config by remember { mutableStateOf(BackupConfig()) }
    var selectedSnapshot by remember { mutableStateOf<ResticWrapper.ResticSnapshot?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("请选择备份源") }
    var showSnapshotPicker by remember { mutableStateOf(false) }
    var availableSnapshots by remember { mutableStateOf<List<ResticWrapper.ResticSnapshot>>(emptyList()) }
    val configFile = remember { File(context.filesDir, "backup_settings.conf") }

    // SAF directory picker for selecting external backup dir
    val dirPickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri != null) {
                val resolvedPath = resolveSafTreeUri(uri)
                if (resolvedPath != null) {
                    val dir = File(resolvedPath)
                    backupDir = dir
                    selectedSnapshot = null
                    scope.launch {
                        loadFromDir(context, dir) { pkgs, infos, status ->
                            packages = pkgs
                            appInfos = infos
                            selectedPackages = pkgs.toSet()
                            statusText = status
                        }
                    }
                }
            }
        }

    // Load config
    LaunchedEffect(Unit) {
        config = BackupConfig.fromFile(configFile)
        if (config.resticEnabled == 1 && config.resticRepo.isNotBlank()) {
            resticConfig = config
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Top controls card ──
        Card(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Source buttons row
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                try {
                                    val defaultDir = context.filesDir
                                    val backupDirs =
                                        withContext(Dispatchers.IO) {
                                            defaultDir
                                                .listFiles()
                                                ?.filter { it.isDirectory && it.name.startsWith("Backup_") }
                                                ?: emptyList()
                                        }
                                    if (backupDirs.isNotEmpty()) {
                                        val dir = backupDirs.first()
                                        backupDir = dir
                                        selectedSnapshot = null
                                        loadFromDir(context, dir) { pkgs, infos, status ->
                                            packages = pkgs
                                            appInfos = infos
                                            selectedPackages = pkgs.toSet()
                                            statusText = status
                                        }
                                    } else {
                                        statusText = "未找到备份目录"
                                    }
                                } catch (e: Exception) {
                                    statusText = "选择目录失败: ${e.message}"
                                }
                            }
                        },
                        enabled = !isRunning,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("本地备份")
                    }

                    OutlinedButton(
                        onClick = { dirPickerLauncher.launch(null) },
                        enabled = !isRunning,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("选择目录")
                    }

                    Button(
                        onClick = {
                            val config =
                                resticConfig ?: run {
                                    statusText = "未配置 Restic，请先在设置中配置"
                                    return@Button
                                }
                            scope.launch {
                                isRunning = true
                                statusText = "正在读取快照…"
                                try {
                                    // 配置 ResticWrapper 环境
                                    defaultResticWrapper.cacheDir = context.cacheDir.absolutePath
                                    defaultResticWrapper.backendDomain = config.resticBackendDomain
                                    ResticBinary.prepare(context)?.let { defaultResticWrapper.binaryPath = it }

                                    // 从 PasswordManager 恢复密码（过滤掉占位符）
                                    fun configPw(
                                        key: String?,
                                        fallback: String,
                                    ): String = key?.takeIf { it.isNotEmpty() && it != "stored-in-keystore" } ?: fallback
                                    val realPassword = configPw(PasswordManager.getResticPassword(), config.resticPassword)
                                    val realBackendPass = configPw(PasswordManager.getBackendPass(), config.resticBackendPass)
                                    val result =
                                        withContext(Dispatchers.IO) {
                                            defaultResticWrapper.listSnapshots(
                                                config.resticRepo,
                                                realPassword,
                                                backend = config.resticBackend,
                                                backendUrl = config.resticBackendUrl,
                                                backendUser = config.resticBackendUser,
                                                backendPass = realBackendPass,
                                                backendShare = config.resticBackendShare,
                                            )
                                        }
                                    if (result.isFailure) {
                                        statusText = "读取快照失败: ${result.exceptionOrNull()?.message}"
                                        return@launch
                                    }
                                    val snaps = result.getOrThrow()
                                    if (snaps.isEmpty()) {
                                        statusText = "没有可用的 restic 快照"
                                        return@launch
                                    }
                                    availableSnapshots = snaps
                                    if (snaps.size == 1) {
                                        loadResticSnapshot(context, snaps.first(), resticConfig!!) { pkgs, infos, status ->
                                            backupDir = null
                                            selectedSnapshot = snaps.first()
                                            packages = pkgs
                                            appInfos = infos
                                            selectedPackages = pkgs.toSet()
                                            statusText = status
                                        }
                                    } else {
                                        showSnapshotPicker = true
                                    }
                                } catch (e: Exception) {
                                    statusText = "选择快照失败: ${e.message}"
                                } finally {
                                    isRunning = false
                                }
                            }
                        },
                        enabled = !isRunning && resticConfig != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Restic 快照")
                    }
                }

                // Source info text
                val sourceText =
                    if (backupDir != null) {
                        backupDir!!.absolutePath
                    } else if (selectedSnapshot != null) {
                        "restic: ${selectedSnapshot!!.time.take(19)}"
                    } else {
                        ""
                    }
                if (sourceText.isNotEmpty()) {
                    Text(
                        text = sourceText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
            items(appInfos, key = { it.packageName.value }) { app ->
                Card(
                    onClick = {
                        val pkg = app.packageName.value
                        selectedPackages =
                            if (pkg in selectedPackages) {
                                selectedPackages - pkg
                            } else {
                                selectedPackages + pkg
                            }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = app.packageName.value in selectedPackages,
                            onCheckedChange = { checked ->
                                val pkg = app.packageName.value
                                selectedPackages =
                                    if (checked) {
                                        selectedPackages + pkg
                                    } else {
                                        selectedPackages - pkg
                                    }
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
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
                    }
                }
            }
        }

        // ── Bottom bar ──
        Surface(modifier = Modifier.fillMaxWidth(), tonalElevation = 3.dp) {
            Button(
                onClick = {
                    val toRestore = packages.filter { it in selectedPackages }
                    if (toRestore.isEmpty()) return@Button
                    isRunning = true
                    statusText = "开始恢复 ${toRestore.size} 个应用…"

                    scope.launch {
                        try {
                            if (selectedSnapshot != null && resticConfig != null) {
                                val snapshot = selectedSnapshot!!
                                val config = resticConfig!!
                                val backupPath = snapshot.paths.firstOrNull() ?: return@launch
                                val staging = File(context.cacheDir, "restic_restore_${snapshot.shortId}")
                                staging.mkdirs()

                                try {
                                    statusText = "正在从 restic 快照恢复…"
                                    val restoreResult =
                                        withContext(Dispatchers.IO) {
                                            val rPw =
                                                PasswordManager.getResticPassword()?.takeIf { it != "stored-in-keystore" }
                                                    ?: config.resticPassword
                                            val rBpw =
                                                PasswordManager.getBackendPass()?.takeIf { it != "stored-in-keystore" }
                                                    ?: config.resticBackendPass
                                            defaultResticWrapper.restore(
                                                repoPath = config.resticRepo,
                                                password = rPw,
                                                snapshotId = snapshot.id,
                                                targetPath = staging.absolutePath,
                                                backend = config.resticBackend,
                                                backendUrl = config.resticBackendUrl,
                                                backendUser = config.resticBackendUser,
                                                backendPass = rBpw,
                                                backendShare = config.resticBackendShare,
                                            )
                                        }
                                    if (restoreResult.isFailure) {
                                        statusText = "restic 恢复失败: ${restoreResult.exceptionOrNull()?.message}"
                                        return@launch
                                    }
                                    val restoredDir = File(staging, backupPath.removePrefix("/"))
                                    statusText = "正在从恢复的备份安装应用…"

                                    val result =
                                        withContext(Dispatchers.IO) {
                                            RestoreOperation.restoreApps(
                                                context = context,
                                                backupDir = restoredDir,
                                                userId = config.backupUserId.toString(),
                                                filterPkgs = selectedPackages,
                                                onProgress = { progress ->
                                                    statusText =
                                                        "[${progress.current}/${progress.total}] ${progress.packageName}: ${progress.message}"
                                                },
                                            )
                                        }
                                    WifiManager.restore(restoredDir)
                                    statusText =
                                        buildString {
                                            appendLine("恢复完成！")
                                            appendLine("成功: ${result.successCount} 失败: ${result.failCount}")
                                            append("耗时: ${result.elapsedMs / 1000}秒")
                                        }
                                } finally {
                                    try {
                                        staging.deleteRecursively()
                                    } catch (_: Exception) {
                                    }
                                }
                            } else if (backupDir != null) {
                                val dir = backupDir!!
                                val result =
                                    withContext(Dispatchers.IO) {
                                        RestoreOperation.restoreApps(
                                            context = context,
                                            backupDir = dir,
                                            userId = config.backupUserId.toString(),
                                            filterPkgs = selectedPackages,
                                            onProgress = { progress ->
                                                statusText =
                                                    "[${progress.current}/${progress.total}] ${progress.packageName}: ${progress.message}"
                                            },
                                        )
                                    }
                                WifiManager.restore(dir)
                                statusText =
                                    buildString {
                                        appendLine("恢复完成！")
                                        appendLine("成功: ${result.successCount} 失败: ${result.failCount}")
                                        append("耗时: ${result.elapsedMs / 1000}秒")
                                    }
                            }
                        } catch (e: Exception) {
                            statusText = "恢复异常: ${e.message}"
                        } finally {
                            isRunning = false
                        }
                    }
                },
                enabled = !isRunning && selectedPackages.isNotEmpty() && (backupDir != null || selectedSnapshot != null),
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            ) {
                if (isRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text("开始恢复 (${selectedPackages.size})")
            }
        }
    }

    // ── Snapshot picker dialog ──
    if (showSnapshotPicker && availableSnapshots.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showSnapshotPicker = false },
            title = { Text("选择快照") },
            text = {
                Column {
                    availableSnapshots.forEach { snap ->
                        val label = "${snap.time.take(19)} (${snap.shortId})"
                        TextButton(
                            onClick = {
                                showSnapshotPicker = false
                                scope.launch {
                                    loadResticSnapshot(context, snap, resticConfig!!) { pkgs, infos, status ->
                                        backupDir = null
                                        selectedSnapshot = snap
                                        packages = pkgs
                                        appInfos = infos
                                        selectedPackages = pkgs.toSet()
                                        statusText = status
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(label) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSnapshotPicker = false }) { Text("取消") }
            },
        )
    }
}

private suspend fun loadFromDir(
    context: android.content.Context,
    dir: File,
    onResult: (packages: List<String>, appInfos: List<AppInfo>, status: String) -> Unit,
) {
    withContext(Dispatchers.IO) {
        val appListFile = File(dir, "appList.txt")
        val pkgs =
            BackupOperation.readTextFile(appListFile)?.let { content ->
                content.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            } ?: run {
                BackupOperation.listBackupFiles(dir)
                    ?: emptyList()
            }
        // Filter to only apps that have actual backup data (at least one APK)
        val validPkgs =
            pkgs.filter { pkg ->
                val appDir = File(dir, pkg)
                val files = BackupOperation.listBackupFiles(appDir)
                files?.any { it.endsWith(".apk") } == true
            }
        val skipped = pkgs.size - validPkgs.size
        // Read cached labels from app_details.json (includes uninstalled apps)
        val cachedLabels = readLocalAppDetails(dir)
        val preLabeled =
            validPkgs.map { pkg ->
                AppInfo(packageName = PackageName(pkg), label = cachedLabels[pkg] ?: "")
            }
        // Resolve labels for currently installed apps, keep cached labels for uninstalled
        val resolved = AppScanner.resolveLabels(context, preLabeled)
        // For apps that resolveLabels fell back to package name, restore cached label
        val infos =
            resolved.map { app ->
                val cachedLabel = cachedLabels[app.packageName.value]
                if (cachedLabel != null && app.label == app.packageName.value) {
                    app.copy(label = cachedLabel)
                } else {
                    app
                }
            }
        val suffix = if (skipped > 0) "（${skipped}个应用备份数据缺失已自动跳过）" else ""
        onResult(validPkgs, infos, "共 ${validPkgs.size} 个备份应用$suffix")
    }
}

private suspend fun loadResticSnapshot(
    context: android.content.Context,
    snapshot: ResticWrapper.ResticSnapshot,
    config: BackupConfig,
    onResult: (packages: List<String>, appInfos: List<AppInfo>, status: String) -> Unit,
) {
    val backupPath =
        snapshot.paths.firstOrNull() ?: run {
            onResult(emptyList(), emptyList(), "快照中找不到备份路径")
            return
        }

    fun rp(
        key: String?,
        fallback: String,
    ) = key?.takeIf { it.isNotEmpty() && it != "stored-in-keystore" } ?: fallback
    val realPassword = rp(PasswordManager.getResticPassword(), config.resticPassword)
    val realBackendPass = rp(PasswordManager.getBackendPass(), config.resticBackendPass)

    suspend fun tryDump(path: String) =
        defaultResticWrapper
            .dump(
                config.resticRepo,
                realPassword,
                snapshot.id,
                path,
                backend = config.resticBackend,
                backendUrl = config.resticBackendUrl,
                backendUser = config.resticBackendUser,
                backendPass = realBackendPass,
                backendShare = config.resticBackendShare,
            ).getOrNull()
    // 兼容流式备份（新版：根目录，旧版：meta/）和普通备份
    val content =
        tryDump("$backupPath/appList.txt")
            ?: tryDump("$backupPath/meta/appList.txt")
    if (content == null) {
        onResult(emptyList(), emptyList(), "无法从快照读取应用列表")
        return
    }
    val pkgs =
        content
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

    // Read cached labels from app_details.json in the snapshot
    val cachedLabels = loadResticAppDetails(config, snapshot.id, backupPath)
    val preLabeled =
        pkgs.map { pkg ->
            AppInfo(packageName = PackageName(pkg), label = cachedLabels[pkg] ?: "")
        }
    val resolved = AppScanner.resolveLabels(context, preLabeled)
    val infos =
        resolved.map { app ->
            val cachedLabel = cachedLabels[app.packageName.value]
            if (cachedLabel != null && app.label == app.packageName.value) {
                app.copy(label = cachedLabel)
            } else {
                app
            }
        }
    onResult(pkgs, infos, "restic 快照共 ${pkgs.size} 个应用")
}

/** Read app_details.json from a local backup directory and return a package→label map. */
private suspend fun readLocalAppDetails(dir: File): Map<String, String> =
    withContext(Dispatchers.IO) {
        val metaFile = File(dir, "app_details.json")
        val json = BackupOperation.readTextFile(metaFile) ?: return@withContext emptyMap()
        try {
            defaultResticWrapper.parseAppDetailsJson(json).mapValues { it.value.label }
        } catch (_: Exception) {
            emptyMap()
        }
    }

/** Dump app_details.json from a restic snapshot and return a package→label map. */
private suspend fun loadResticAppDetails(
    config: BackupConfig,
    snapshotId: String,
    backupPath: String,
): Map<String, String> {
    fun rp2(
        key: String?,
        fallback: String,
    ) = key?.takeIf { it.isNotEmpty() && it != "stored-in-keystore" } ?: fallback
    val realPassword = rp2(PasswordManager.getResticPassword(), config.resticPassword)
    val realBackendPass = rp2(PasswordManager.getBackendPass(), config.resticBackendPass)

    suspend fun tryDump(path: String) =
        defaultResticWrapper
            .dump(
                config.resticRepo,
                realPassword,
                snapshotId,
                path,
                backend = config.resticBackend,
                backendUrl = config.resticBackendUrl,
                backendUser = config.resticBackendUser,
                backendPass = realBackendPass,
                backendShare = config.resticBackendShare,
            ).getOrNull()
    val json =
        tryDump("$backupPath/app_details.json")
            ?: tryDump("$backupPath/meta/app_details.json")
            ?: return emptyMap()
    return try {
        defaultResticWrapper.parseAppDetailsJson(json).mapValues { it.value.label }
    } catch (_: Exception) {
        emptyMap()
    }
}

/** Convert SAF tree URI to a filesystem path. */
private fun resolveSafTreeUri(uri: Uri): String? {
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
