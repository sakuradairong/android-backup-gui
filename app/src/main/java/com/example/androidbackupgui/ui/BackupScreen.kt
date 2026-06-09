package com.example.androidbackupgui.ui

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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.androidbackupgui.backup.AppInfo

/**
 * 备份主页——应用选择、扫描和备份执行。
 *
 * 业务逻辑在 [BackupViewModel] 中，UI 只负责渲染和事件转发。
 */
@Composable
fun BackupScreen(viewModel: BackupViewModel = viewModel()) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.allApps, state.sortMode, state.showSystemApps) {
        viewModel.applySortAndFilter()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Top controls card ──
        Card(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Scan button
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.scanApps(context) },
                        enabled = !state.isScanning && !state.isRunning,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (state.isScanning) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("扫描应用")
                    }
                }

                // Sort/filter row
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(
                        selected = state.sortMode == SortMode.NAME_ASC,
                        onClick = { viewModel.setSortMode(SortMode.NAME_ASC) },
                        label = { Text("A-Z") },
                        leadingIcon = { Icon(Icons.Default.SortByAlpha, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    )
                    FilterChip(
                        selected = state.sortMode == SortMode.SIZE_DESC,
                        onClick = { viewModel.setSortMode(SortMode.SIZE_DESC) },
                        label = { Text("大小") },
                        leadingIcon = { Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { viewModel.selectAll() }) { Text("全选") }
                    TextButton(onClick = { viewModel.clearSelection() }) { Text("取消全选") }
                }

                // Show system switch
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("显示系统应用", modifier = Modifier.weight(1f))
                    Switch(checked = state.showSystemApps, onCheckedChange = { viewModel.toggleShowSystem() })
                }
            }
        }

        // ── Status ──
        Text(
            text = state.statusText,
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
            items(state.sortedApps, key = { it.packageName.value }) { app ->
                AppListItem(
                    app = app,
                    isSelected = app.packageName.value in state.selectedApps,
                    isDataExcluded = app.packageName.value in state.excludeDataFromBackup,
                    onToggle = { checked -> viewModel.toggleApp(app.packageName.value, checked) },
                    onExcludeDataToggle = { excluded -> viewModel.toggleExcludeData(app.packageName.value, excluded) },
                )
            }
        }

        // ── Bottom bar with backup button ──
        Surface(modifier = Modifier.fillMaxWidth(), tonalElevation = 3.dp) {
            Button(
                onClick = { viewModel.executeBackup(context) },
                enabled = !state.isRunning && state.selectedApps.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            ) {
                if (state.isRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text("开始备份 (${state.selectedApps.size})")
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
                        color = if (isDataExcluded) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
