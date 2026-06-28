package com.example.androidbackupgui.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.androidbackupgui.R
import com.example.androidbackupgui.backup.scan.AppScanner
import com.example.androidbackupgui.backup.BackupConfig
import com.example.androidbackupgui.ui.config.BackupSettingsSection
import com.example.androidbackupgui.ui.config.ResticSection
import com.example.androidbackupgui.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLDecoder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    viewModel: ConfigViewModel = viewModel(),
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
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
    var streamingEnabled by remember { mutableStateOf(config.useStreaming == 1) }

    // Sync local state from ViewModel when config reloads
    LaunchedEffect(config) {
        backupMode = config.backupMode == 1
        backupUserData = config.backupUserData == 1
        backupObb = config.backupObbData == 1
        backupWifi = config.backupWifi == 1
        ignoreRunning = config.backgroundAppsIgnore == 1
        outputPath = config.outputPath
        compressionMethod = BackupConfig.normalizeCompressionMethod(config.compressionMethod)
        backupUserId = config.backupUserId
        resticEnabled = config.resticEnabled == 1
        resticRepo = config.resticRepo
        // 避免密码占位符显示在 UI 中
        resticPassword = config.resticPassword.takeIf { it != "stored-in-keystore" } ?: ""
        resticBackend = config.resticBackend
        resticBackendUrl = config.resticBackendUrl
        resticBackendUser = config.resticBackendUser
        resticBackendPass = config.resticBackendPass.takeIf { it != "stored-in-keystore" } ?: ""
        resticBackendShare = config.resticBackendShare
        resticBackendDomain = config.resticBackendDomain
        streamingEnabled = config.useStreaming == 1
    }

    // Load user list for backup user selector
    LaunchedEffect(Unit) {
        val users =
            withContext(Dispatchers.IO) {
                AppScanner.enumerateUsers()
            }
        userList = users
    }

    // Observe one-shot events → show Snackbar feedback
    val context = LocalContext.current
    LaunchedEffect(snackbarHostState) {
        viewModel.operationEvents.collect { event ->
            val msg =
                when (event) {
                    is OperationEvent.InitCompleted -> context.getString(R.string.event_init_completed)
                    is OperationEvent.InitFailed -> context.getString(R.string.event_init_failed)
                    is OperationEvent.StatsCompleted -> context.getString(R.string.event_stats_completed)
                    is OperationEvent.PruneStarted -> context.getString(R.string.event_prune_started)
                    is OperationEvent.PruneCompleted -> context.getString(R.string.event_prune_completed)
                    is OperationEvent.PruneFailed -> context.getString(R.string.event_prune_failed)
                    is OperationEvent.ConfigExported -> context.getString(R.string.event_config_exported)
                    is OperationEvent.ConfigExportFailed -> context.getString(R.string.event_config_export_failed)
                    is OperationEvent.ConfigImported -> context.getString(R.string.event_config_imported)
                    is OperationEvent.ConfigImportFailed -> context.getString(R.string.event_config_import_failed)
                    else -> null
                }
            if (msg != null) {
                snackbarHostState.showSnackbar(msg)
            }
        }
    }

    val scrollState = rememberScrollState()

    // SAF launcher: create a .conf document at a user-chosen location, then export.
    val exportLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("text/plain"),
        ) { uri ->
            if (uri != null) viewModel.exportConfig(uri)
        }

    // SAF launcher: pick a .conf file to import.
    val importLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) viewModel.importConfig(uri)
        }

    // SAF directory picker for output path
    val dirPickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri != null) {
                val resolvedPath = resolveSafTreeUri(uri)
                if (resolvedPath != null) {
                    outputPath = resolvedPath
                }
            }
        }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(Spacing.pageHorizontal),
        verticalArrangement = Arrangement.spacedBy(Spacing.sectionGap),
    ) {
        BackupSettingsSection(
            backupMode = backupMode,
            onBackupModeChange = { backupMode = it },
            backupUserData = backupUserData,
            onBackupUserDataChange = { backupUserData = it },
            backupObb = backupObb,
            onBackupObbChange = { backupObb = it },
            backupWifi = backupWifi,
            onBackupWifiChange = { backupWifi = it },
            ignoreRunning = ignoreRunning,
            onIgnoreRunningChange = { ignoreRunning = it },
            outputPath = outputPath,
            onOutputPathChange = { outputPath = it },
            onChooseOutputDir = { dirPickerLauncher.launch(null) },
            compressionMethod = compressionMethod,
            onCompressionMethodChange = { compressionMethod = it },
            backupUserId = backupUserId,
            userList = userList,
            onBackupUserIdChange = { backupUserId = it },
        )

        HorizontalDivider()

        // ── Restic section ──
        ResticSection(
            resticEnabled = resticEnabled,
            onResticEnabledChange = { resticEnabled = it },
            resticRepo = resticRepo,
            onResticRepoChange = {
                resticRepo = it
                viewModel.onFormChanged(resticBackend, it, resticBackendUrl)
            },
            resticPassword = resticPassword,
            onResticPasswordChange = { resticPassword = it },
            resticBackend = resticBackend,
            onResticBackendChange = {
                resticBackend = it
                viewModel.onFormChanged(it, resticRepo, resticBackendUrl)
            },
            resticBackendUrl = resticBackendUrl,
            onResticBackendUrlChange = {
                resticBackendUrl = it
                viewModel.onFormChanged(resticBackend, resticRepo, it)
            },
            resticBackendUser = resticBackendUser,
            onResticBackendUserChange = { resticBackendUser = it },
            resticBackendPass = resticBackendPass,
            onResticBackendPassChange = { resticBackendPass = it },
            resticBackendShare = resticBackendShare,
            onResticBackendShareChange = { resticBackendShare = it },
            resticBackendDomain = resticBackendDomain,
            onResticBackendDomainChange = { resticBackendDomain = it },
            streamingEnabled = streamingEnabled,
            onStreamingEnabledChange = { streamingEnabled = it },
            backendDisplay = backendDisplay,
            resticStatus = status,
            onInitRepo = {
                viewModel.initResticRepo(
                    buildResticForm(
                        resticRepo,
                        resticPassword,
                        resticBackend,
                        resticBackendUrl,
                        resticBackendUser,
                        resticBackendPass,
                        resticBackendShare,
                        resticBackendDomain,
                    ),
                )
            },
            onShowStats = {
                viewModel.showResticStats(
                    buildResticForm(
                        resticRepo,
                        resticPassword,
                        resticBackend,
                        resticBackendUrl,
                        resticBackendUser,
                        resticBackendPass,
                        resticBackendShare,
                        resticBackendDomain,
                    ),
                )
            },
            onPruneSnapshots = {
                viewModel.pruneResticSnapshots(
                    buildResticForm(
                        resticRepo,
                        resticPassword,
                        resticBackend,
                        resticBackendUrl,
                        resticBackendUser,
                        resticBackendPass,
                        resticBackendShare,
                        resticBackendDomain,
                    ),
                )
            },
            onUnlockRepo = {
                viewModel.unlockResticRepo(
                    buildResticForm(
                        resticRepo,
                        resticPassword,
                        resticBackend,
                        resticBackendUrl,
                        resticBackendUser,
                        resticBackendPass,
                        resticBackendShare,
                        resticBackendDomain,
                    ),
                )
            },
        )

        Spacer(Modifier.height(Spacing.sm))

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
                        compressionMethod = BackupConfig.normalizeCompressionMethod(compressionMethod),
                        resticEnabled = if (resticEnabled) 1 else 0,
                        resticRepo = resticRepo,
                        resticPassword = resticPassword,
                        resticBackend = resticBackend,
                        resticBackendUrl = resticBackendUrl,
                        resticBackendUser = resticBackendUser,
                        resticBackendPass = resticBackendPass,
                        resticBackendShare = resticBackendShare,
                        resticBackendDomain = resticBackendDomain,
                        useStreaming = if (streamingEnabled) 1 else 0,
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Save, contentDescription = null)
            Spacer(Modifier.width(Spacing.sm))
            Text(stringResource(R.string.config_save))
        }

        // ── Import / Export config buttons ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("text/plain", "*/*")) },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.config_import))
            }
            OutlinedButton(
                onClick = { exportLauncher.launch("backup_settings.conf") },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.FileUpload, contentDescription = null)
                Spacer(Modifier.width(Spacing.sm))
                Text(stringResource(R.string.config_export))
            }
        }
        if (resticEnabled && resticPassword.isNotEmpty()) {
            Text(
                text = stringResource(R.string.config_export_password_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(Spacing.xxl))
    }
}

/** Build a [ResticForm] from current input values (matches ConfigFragment's readResticForm). */
private fun buildResticForm(
    repo: String,
    password: String,
    backend: String,
    backendUrl: String,
    backendUser: String,
    backendPass: String,
    backendShare: String,
    backendDomain: String,
) = ResticForm(
    repo = repo,
    password = password,
    backend = backend,
    backendUrl = backendUrl,
    backendUser = backendUser,
    backendPass = backendPass,
    backendShare = backendShare,
    backendDomain = backendDomain,
)

/**
 * 将 SAF OpenDocumentTree 的 content:// URI 转换为可用的文件系统路径。
 * SAF URI 示例: content://com.android.externalstorage.documents/tree/primary%3ADownload%2FBackup
 * 返回: /storage/emulated/0/Download/Backup
 */
private fun resolveSafTreeUri(uri: Uri): String? {
    // SAF tree URI 格式:
    //   content://com.android.externalstorage.documents/tree/primary%3ADownload%2FBackup
    // lastPathSegment = primary%3ADownload%2FBackup 或 XXXX-XXXX%3Apath
    val docId = uri.lastPathSegment?.let { URLDecoder.decode(it, "UTF-8") } ?: return null

    // docId 格式: primary:path/to/dir 或 XXXX-XXXX:path/to/dir
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
