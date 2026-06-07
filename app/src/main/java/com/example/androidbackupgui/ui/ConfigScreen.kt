package com.example.androidbackupgui.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.androidbackupgui.backup.AppScanner
import com.example.androidbackupgui.backup.BackupConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    viewModel: ConfigViewModel = viewModel(),
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val config = uiState.config
    val backendDisplay = uiState.backendDisplay
    val status = uiState.resticStatus

    // ── Local editing state (initialized from ViewModel on first load) ──
    var backupMode by remember { mutableStateOf(config.backupMode == 1) }
    var backupUserData by remember { mutableStateOf(config.backupUserData == 1) }
    var backupObb by remember { mutableStateOf(config.backupObbData == 1) }
    var backupWifi by remember { mutableStateOf(config.backupWifi == 1) }
    var ignoreRunning by remember { mutableStateOf(config.backgroundAppsIgnore == 1) }
    var outputPath by remember { mutableStateOf(config.outputPath) }
    var compressionMethod by remember { mutableStateOf(config.compressionMethod) }

    var backupUserId by remember { mutableIntStateOf(config.backupUserId) }
    var userList by remember { mutableStateOf<List<Pair<Int, String>>>(listOf(0 to "Owner")) }

    var resticEnabled by remember { mutableStateOf(config.resticEnabled == 1) }
    var resticRepo by remember { mutableStateOf(config.resticRepo) }
    var resticPassword by remember { mutableStateOf(config.resticPassword) }
    var resticBackend by remember { mutableStateOf(config.resticBackend) }
    var resticBackendUrl by remember { mutableStateOf(config.resticBackendUrl) }
    var resticBackendUser by remember { mutableStateOf(config.resticBackendUser) }
    var resticBackendPass by remember { mutableStateOf(config.resticBackendPass) }
    var resticBackendShare by remember { mutableStateOf(config.resticBackendShare) }
    var resticBackendDomain by remember { mutableStateOf(config.resticBackendDomain) }

    // Sync local state from ViewModel when config reloads
    LaunchedEffect(config) {
        backupMode = config.backupMode == 1
        backupUserData = config.backupUserData == 1
        backupObb = config.backupObbData == 1
        backupWifi = config.backupWifi == 1
        ignoreRunning = config.backgroundAppsIgnore == 1
        outputPath = config.outputPath
        compressionMethod = config.compressionMethod
        backupUserId = config.backupUserId
        resticEnabled = config.resticEnabled == 1
        resticRepo = config.resticRepo
        resticPassword = config.resticPassword
        resticBackend = config.resticBackend
        resticBackendUrl = config.resticBackendUrl
        resticBackendUser = config.resticBackendUser
        resticBackendPass = config.resticBackendPass
        resticBackendShare = config.resticBackendShare
        resticBackendDomain = config.resticBackendDomain
    }

    // Load user list for backup user selector
    LaunchedEffect(Unit) {
        val users = withContext(Dispatchers.IO) {
            AppScanner.enumerateUsers()
        }
        userList = users
    }

    // Observe one-shot events → show Snackbar feedback
    LaunchedEffect(snackbarHostState) {
        viewModel.operationEvents.collect { event ->
            val msg = when (event) {
                is OperationEvent.InitCompleted -> "仓库初始化完成"
                is OperationEvent.InitFailed -> "仓库初始化失败"
                is OperationEvent.StatsCompleted -> "统计读取完成"
                is OperationEvent.PruneStarted -> "正在清理快照…"
                is OperationEvent.PruneCompleted -> "清理完成"
                is OperationEvent.PruneFailed -> "清理失败"
                else -> null
            }
            if (msg != null) {
                snackbarHostState.showSnackbar(msg)
            }
        }
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── Backup settings section ──
        Text("备份设置", style = MaterialTheme.typography.titleMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("备份模式", modifier = Modifier.weight(1f))
                    Switch(checked = backupMode, onCheckedChange = { backupMode = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("备份用户数据", modifier = Modifier.weight(1f))
                    Switch(checked = backupUserData, onCheckedChange = { backupUserData = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("备份 OBB 数据", modifier = Modifier.weight(1f))
                    Switch(checked = backupObb, onCheckedChange = { backupObb = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("备份 WiFi 配置", modifier = Modifier.weight(1f))
                    Switch(checked = backupWifi, onCheckedChange = { backupWifi = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("忽略运行中的应用", modifier = Modifier.weight(1f))
                    Switch(checked = ignoreRunning, onCheckedChange = { ignoreRunning = it })
                }
                OutlinedTextField(
                    value = outputPath,
                    onValueChange = { outputPath = it },
                    label = { Text("输出目录") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = compressionMethod,
                    onValueChange = { compressionMethod = it },
                    label = { Text("压缩方式 (tar / zstd)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Backup user selector
                UserSelector(
                    userList = userList,
                    selectedUserId = backupUserId,
                    onUserSelected = { backupUserId = it }
                )
            }
        }

        // ── Restic section ──
        HorizontalDivider()
        Text("Restic 备份", style = MaterialTheme.typography.titleMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用 Restic", modifier = Modifier.weight(1f))
                    Switch(checked = resticEnabled, onCheckedChange = { resticEnabled = it })
                }

                if (resticEnabled) {
                    OutlinedTextField(
                        value = resticRepo,
                        onValueChange = { resticRepo = it; viewModel.onFormChanged(resticBackend, it, resticBackendUrl) },
                        label = { Text("仓库路径") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = resticPassword,
                        onValueChange = { resticPassword = it },
                        label = { Text("仓库密码") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                    )

                    // Backend selection radio group
                    Text("后端类型", style = MaterialTheme.typography.labelLarge)
                    val backends = listOf("local" to "本地", "webdav" to "WebDAV", "smb" to "SMB", "rest-server" to "rest-server")
                    Column(modifier = Modifier.selectableGroup()) {
                        backends.forEach { (value, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = resticBackend == value,
                                        onClick = {
                                            resticBackend = value
                                            viewModel.onFormChanged(value, resticRepo, resticBackendUrl)
                                        },
                                        role = Role.RadioButton
                                    )
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = resticBackend == value,
                                    onClick = null
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(label)
                            }
                        }
                    }

                    // Computed URL
                    if (resticRepo.isNotEmpty()) {
                        Text(
                            text = "实际仓库: ${backendDisplay.computedUrl}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // Remote-specific fields
                    if (resticBackend != "local") {
                            OutlinedTextField(
                                value = resticBackendUrl,
                                onValueChange = { resticBackendUrl = it; viewModel.onFormChanged(resticBackend, resticRepo, it) },
                                label = { Text(backendDisplay.urlHint.ifEmpty { "后端地址" }) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }
                        if (resticBackend == "webdav" || resticBackend == "smb") {
                            OutlinedTextField(
                                value = resticBackendUser,
                                onValueChange = { resticBackendUser = it },
                                label = { Text("用户名") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = resticBackendPass,
                                onValueChange = { resticBackendPass = it },
                                label = { Text("密码") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                            )
                        }
                        if (resticBackend == "smb") {
                            OutlinedTextField(
                                value = resticBackendShare,
                                onValueChange = { resticBackendShare = it },
                                label = { Text("SMB 共享名称") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = resticBackendDomain,
                                onValueChange = { resticBackendDomain = it },
                                label = { Text("SMB 域 (可选)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }

                    Spacer(Modifier.height(8.dp))

                    // Status & action buttons
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = status.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(Modifier.height(8.dp))

                            if (status.initButtonVisible) {
                                Button(
                                    onClick = {
                                        viewModel.initResticRepo(
                                            buildResticForm(resticRepo, resticPassword, resticBackend, resticBackendUrl, resticBackendUser, resticBackendPass, resticBackendShare, resticBackendDomain)
                                        )
                                    },
                                    enabled = status.initButtonEnabled,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("初始化仓库")
                                }
                            }

                            if (status.statsButtonVisible) {
                                Button(
                                    onClick = {
                                        viewModel.showResticStats(
                                            buildResticForm(resticRepo, resticPassword, resticBackend, resticBackendUrl, resticBackendUser, resticBackendPass, resticBackendShare, resticBackendDomain)
                                        )
                                    },
                                    enabled = status.statsButtonEnabled,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("仓库统计")
                                }
                            }

                            if (status.pruneButtonVisible) {
                                OutlinedButton(
                                    onClick = {
                                        viewModel.pruneResticSnapshots(
                                            buildResticForm(resticRepo, resticPassword, resticBackend, resticBackendUrl, resticBackendUser, resticBackendPass, resticBackendShare, resticBackendDomain)
                                        )
                                    },
                                    enabled = status.pruneButtonEnabled,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("清理旧快照")
                                }
                            }

                            if (status.unlockButtonVisible) {
                                Button(
                                    onClick = {
                                        viewModel.unlockResticRepo(
                                            buildResticForm(resticRepo, resticPassword, resticBackend, resticBackendUrl, resticBackendUser, resticBackendPass, resticBackendShare, resticBackendDomain)
                                        )
                                    },
                                    enabled = status.unlockButtonEnabled,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.tertiary
                                    )
                                ) {
                                    Text("解锁仓库")
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Save button ──
        Button(
            onClick = {
                viewModel.save(
                    BackupConfig(
                        backupMode = if (backupMode) 1 else 0,
                        backupUserData = if (backupUserData) 1 else 0,
                        backupObbData = if (backupObb) 1 else 0,
                        backupWifi = if (backupWifi) 1 else 0,
                        backgroundAppsIgnore = if (ignoreRunning) 1 else 0,
                        backupUserId = backupUserId,
                        outputPath = outputPath,
                        compressionMethod = compressionMethod.ifEmpty { "zstd" },
                        resticEnabled = if (resticEnabled) 1 else 0,
                        resticRepo = resticRepo,
                        resticPassword = resticPassword,
                        resticBackend = resticBackend,
                        resticBackendUrl = resticBackendUrl,
                        resticBackendUser = resticBackendUser,
                        resticBackendPass = resticBackendPass,
                        resticBackendShare = resticBackendShare,
                        resticBackendDomain = resticBackendDomain,
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Save, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("保存配置")
        }

        Spacer(Modifier.height(32.dp))
    }
}

// ── User selector ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserSelector(
    userList: List<Pair<Int, String>>,
    selectedUserId: Int,
    onUserSelected: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = userList.find { it.first == selectedUserId }?.let {
        "${it.second} (ID: ${it.first})"
    } ?: "Owner (ID: 0)"

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text("备份用户") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            singleLine = true
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            userList.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text("$name (ID: $id)") },
                    onClick = { onUserSelected(id); expanded = false }
                )
            }
        }
    }
}

/** Build a [ResticForm] from current input values (matches ConfigFragment's readResticForm). */
private fun buildResticForm(
    repo: String, password: String,
    backend: String, backendUrl: String,
    backendUser: String, backendPass: String,
    backendShare: String, backendDomain: String
) = ResticForm(
    repo = repo, password = password,
    backend = backend, backendUrl = backendUrl,
    backendUser = backendUser, backendPass = backendPass,
    backendShare = backendShare, backendDomain = backendDomain
)
