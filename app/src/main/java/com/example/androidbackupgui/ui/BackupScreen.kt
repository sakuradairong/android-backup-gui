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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.androidbackupgui.R
import com.example.androidbackupgui.ui.components.AppListItem
import com.example.androidbackupgui.ui.theme.Spacing

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
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.pageHorizontal),
        ) {
            Column(
                modifier = Modifier.padding(Spacing.card),
                verticalArrangement = Arrangement.spacedBy(Spacing.itemGap),
            ) {
                Button(
                    onClick = { viewModel.scanApps(context) },
                    enabled = !state.isScanning && !state.isRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(Spacing.sm))
                    }
                    Text(stringResource(R.string.action_scan))
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    FilterChip(
                        selected = state.sortMode == SortMode.NAME_ASC,
                        onClick = { viewModel.setSortMode(SortMode.NAME_ASC) },
                        label = { Text(stringResource(R.string.sort_az)) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.SortByAlpha,
                                contentDescription = stringResource(R.string.cd_sort_by_name),
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                    FilterChip(
                        selected = state.sortMode == SortMode.SIZE_DESC,
                        onClick = { viewModel.setSortMode(SortMode.SIZE_DESC) },
                        label = { Text(stringResource(R.string.sort_size)) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Storage,
                                contentDescription = stringResource(R.string.cd_sort_by_size),
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    TextButton(onClick = { viewModel.selectAll() }) {
                        Text(stringResource(R.string.action_select_all))
                    }
                    TextButton(onClick = { viewModel.clearSelection() }) {
                        Text(stringResource(R.string.action_deselect_all))
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.show_system_apps),
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = state.showSystemApps,
                        onCheckedChange = { viewModel.toggleShowSystem() },
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
            stageDisplayName = ::backupStageDisplayName,
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(
                horizontal = Spacing.pageHorizontal,
                vertical = Spacing.xs,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            items(state.sortedApps, key = { it.packageName.value }) { app ->
                AppListItem(
                    app = app,
                    isSelected = app.packageName.value in state.selectedApps,
                    isDataExcluded = app.packageName.value in state.excludeDataFromBackup,
                    onToggle = { checked ->
                        viewModel.toggleApp(app.packageName.value, checked)
                    },
                    onExcludeDataToggle = { excluded ->
                        viewModel.toggleExcludeData(app.packageName.value, excluded)
                    },
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = Spacing.cardElevation,
        ) {
            if (state.isRunning) {
                OutlinedButton(
                    onClick = { viewModel.cancelBackup(context) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.card),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.action_cancel_backup))
                }
            } else {
                Button(
                    onClick = { viewModel.executeBackup(context) },
                    enabled = state.selectedApps.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.card),
                ) {
                    Text(
                        stringResource(
                            R.string.action_start_backup_count,
                            state.selectedApps.size,
                        )
                    )
                }
            }
        }
    }
}
