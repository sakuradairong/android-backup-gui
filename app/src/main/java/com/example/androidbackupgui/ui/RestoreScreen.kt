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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.androidbackupgui.backup.restic.ResticWrapper

@Composable
fun RestoreScreen(viewModel: RestoreViewModel = viewModel()) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()

    val dirPickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri != null) {
                viewModel.loadFromSafUri(context, uri)
            }
        }

    Column(modifier = Modifier.fillMaxSize()) {
        Card(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { viewModel.loadDefaultDir(context) },
                        enabled = !state.isRunning,
                        modifier = Modifier.weight(1f),
                    ) { Text("本地备份") }

                    OutlinedButton(
                        onClick = { dirPickerLauncher.launch(null) },
                        enabled = !state.isRunning,
                        modifier = Modifier.weight(1f),
                    ) { Text("选择目录") }

                    Button(
                        onClick = { viewModel.listResticSnapshots(context) },
                        enabled = !state.isRunning && state.resticConfig != null,
                        modifier = Modifier.weight(1f),
                    ) { Text("Restic 快照") }
                }

                val sourceText = when {
                    state.backupDir != null -> state.backupDir!!.absolutePath
                    state.selectedSnapshot != null -> "restic: ${state.selectedSnapshot!!.time.take(19)}"
                    else -> ""
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

        ProgressBlock(
            isRunning = state.isRunning,
            statusText = state.statusText,
            progressCurrent = state.progressCurrent,
            progressTotal = state.progressTotal,
            progressStage = state.progressStage,
            progressPackageName = state.progressPackageName,
            progressMessage = state.progressMessage,
            progressPercent = state.progressPercent,
            stageDisplayName = ::restoreStageDisplayName,
        )

        if (state.packages.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { viewModel.selectAll() }, enabled = !state.isRunning) { Text("全选应用") }
                TextButton(onClick = { viewModel.clearSelection() }, enabled = !state.isRunning) { Text("取消全选") }
                Spacer(Modifier.weight(1f))
                Text("恢复 Wi-Fi", style = MaterialTheme.typography.bodySmall)
                Switch(checked = state.restoreWifi, onCheckedChange = { viewModel.toggleRestoreWifi(it) }, enabled = !state.isRunning)
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(state.appInfos, key = { it.packageName.value }) { app ->
                Card(
                    onClick = {
                        val pkg = app.packageName.value
                        viewModel.toggleApp(pkg, pkg !in state.selectedPackages)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = app.packageName.value in state.selectedPackages,
                            onCheckedChange = { checked -> viewModel.toggleApp(app.packageName.value, checked) },
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

        Surface(modifier = Modifier.fillMaxWidth(), tonalElevation = 3.dp) {
            if (state.isRunning) {
                OutlinedButton(
                    onClick = { viewModel.cancelRestore() },
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("取消恢复") }
            } else {
                Button(
                    onClick = { viewModel.requestRestore() },
                    enabled = state.selectedPackages.isNotEmpty() && (state.backupDir != null || state.selectedSnapshot != null),
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                ) { Text("开始恢复 (${state.selectedPackages.size})") }
            }
        }
    }

    if (state.showRestoreConfirm) {
        val toRestore = state.packages.filter { it in state.selectedPackages }
        val sourceText = when {
            state.backupDir != null -> "本地目录: ${state.backupDir!!.name}"
            state.selectedSnapshot != null -> "Restic 快照: ${state.selectedSnapshot!!.time.take(19)}"
            else -> "未知"
        }
        AlertDialog(
            onDismissRequest = { viewModel.dismissRestoreConfirm() },
            title = { Text("确认恢复") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("即将恢复 ${toRestore.size} 个应用")
                    Text("备份源: $sourceText")
                    Text("目标用户: ${state.config.backupUserId}")
                    if (state.restoreWifi) {
                        Text("将恢复 Wi-Fi 配置", color = MaterialTheme.colorScheme.error)
                    }
                    if (state.isStreamingBackup) {
                        Text(
                            "这是实验性不完整备份，不会恢复 OBB、外部数据、权限、SSAID、Wi-Fi",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "⚠️ 警告：这将覆盖现有应用数据，操作不可撤销。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.confirmRestore(context) }) { Text("确认恢复") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissRestoreConfirm() }) { Text("取消") }
            },
        )
    }

    if (state.showSnapshotPicker && state.availableSnapshots.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissSnapshotPicker() },
            title = { Text("选择快照") },
            text = {
                Column {
                    state.availableSnapshots.forEach { snap ->
                        val label = "${snap.time.take(19)} (${snap.shortId})"
                        TextButton(
                            onClick = { viewModel.selectSnapshot(context, snap) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(label) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissSnapshotPicker() }) { Text("取消") }
            },
        )
    }
}
