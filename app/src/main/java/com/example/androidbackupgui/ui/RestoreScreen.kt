package com.example.androidbackupgui.ui

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.androidbackupgui.R
import com.example.androidbackupgui.ui.components.AppListItem
import com.example.androidbackupgui.ui.theme.Spacing

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
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.pageHorizontal),
        ) {
            Column(
                modifier = Modifier.padding(Spacing.card),
                verticalArrangement = Arrangement.spacedBy(Spacing.itemGap),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedButton(
                        onClick = { viewModel.loadDefaultDir(context) },
                        enabled = !state.isRunning,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.source_local)) }

                    OutlinedButton(
                        onClick = { dirPickerLauncher.launch(null) },
                        enabled = !state.isRunning,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.source_select_dir)) }

                    Button(
                        onClick = { viewModel.listResticSnapshots(context) },
                        enabled = !state.isRunning && state.resticConfig != null,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.source_restic_snapshots)) }
                }

                val sourceText = when {
                    state.backupDir != null -> state.backupDir!!.absolutePath
                    state.selectedSnapshot != null ->
                        "restic: ${state.selectedSnapshot!!.time.take(19)}"
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.pageHorizontal, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                TextButton(
                    onClick = { viewModel.selectAll() },
                    enabled = !state.isRunning,
                ) { Text(stringResource(R.string.action_select_all_apps)) }
                TextButton(
                    onClick = { viewModel.clearSelection() },
                    enabled = !state.isRunning,
                ) { Text(stringResource(R.string.action_deselect_all_apps)) }
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.restore_wifi),
                    style = MaterialTheme.typography.bodySmall,
                )
                Switch(
                    checked = state.restoreWifi,
                    onCheckedChange = { viewModel.toggleRestoreWifi(it) },
                    enabled = !state.isRunning,
                )
            }
        }

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
            items(state.appInfos, key = { it.packageName.value }) { app ->
                val pkg = app.packageName.value
                AppListItem(
                    app = app,
                    isSelected = pkg in state.selectedPackages,
                    onToggle = { checked -> viewModel.toggleApp(pkg, checked) },
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = Spacing.cardElevation,
        ) {
            if (state.isRunning) {
                OutlinedButton(
                    onClick = { viewModel.cancelRestore() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.card),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text(stringResource(R.string.action_cancel_restore)) }
            } else {
                Button(
                    onClick = { viewModel.requestRestore() },
                    enabled = state.selectedPackages.isNotEmpty() &&
                        (state.backupDir != null || state.selectedSnapshot != null),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.card),
                ) {
                    Text(
                        stringResource(
                            R.string.action_start_restore_count,
                            state.selectedPackages.size,
                        )
                    )
                }
            }
        }
    }

    if (state.showRestoreConfirm) {
        RestoreConfirmDialog(viewModel = viewModel, state = state)
    }

    if (state.showSnapshotPicker && state.availableSnapshots.isNotEmpty()) {
        SnapshotPickerDialog(viewModel = viewModel, state = state)
    }
}

@Composable
private fun RestoreConfirmDialog(
    viewModel: RestoreViewModel,
    state: RestoreUiState,
) {
    val context = LocalContext.current
    val toRestoreCount = state.selectedPackages.size
    val sourceText = when {
        state.backupDir != null ->
            stringResource(R.string.restore_source, state.backupDir.name)
        state.selectedSnapshot != null ->
            stringResource(
                R.string.restore_source,
                "Restic ${state.selectedSnapshot.time.take(19)}",
            )
        else -> stringResource(R.string.restore_source, "未知")
    }

    AlertDialog(
        onDismissRequest = { viewModel.dismissRestoreConfirm() },
        title = { Text(stringResource(R.string.dialog_title_confirm_restore)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(stringResource(R.string.restore_summary, toRestoreCount))
                Text(sourceText)
                Text(stringResource(R.string.restore_target_user, state.config.backupUserId))
                if (state.restoreWifi) {
                    Text(
                        stringResource(R.string.restore_wifi_warning),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (state.isStreamingBackup) {
                    Text(
                        stringResource(R.string.restore_streaming_warning),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    stringResource(R.string.restore_irreversible_warning),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(onClick = { viewModel.confirmRestore(context) }) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.dismissRestoreConfirm() }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun SnapshotPickerDialog(
    viewModel: RestoreViewModel,
    state: RestoreUiState,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { viewModel.dismissSnapshotPicker() },
        title = { Text(stringResource(R.string.dialog_title_select_snapshot)) },
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
            TextButton(onClick = { viewModel.dismissSnapshotPicker() }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
