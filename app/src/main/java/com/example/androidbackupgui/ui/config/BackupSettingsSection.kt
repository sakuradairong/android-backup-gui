package com.example.androidbackupgui.ui.config

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.androidbackupgui.R
import com.example.androidbackupgui.ui.components.SectionHeader
import com.example.androidbackupgui.ui.theme.Spacing

/**
 * Backup behaviour and output settings section used by [ConfigScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsSection(
    backupMode: Boolean,
    onBackupModeChange: (Boolean) -> Unit,
    backupUserData: Boolean,
    onBackupUserDataChange: (Boolean) -> Unit,
    backupObb: Boolean,
    onBackupObbChange: (Boolean) -> Unit,
    backupWifi: Boolean,
    onBackupWifiChange: (Boolean) -> Unit,
    ignoreRunning: Boolean,
    onIgnoreRunningChange: (Boolean) -> Unit,
    outputPath: String,
    onOutputPathChange: (String) -> Unit,
    onChooseOutputDir: () -> Unit,
    compressionMethod: String,
    onCompressionMethodChange: (String) -> Unit,
    backupUserId: Int,
    userList: List<Pair<Int, String>>,
    onBackupUserIdChange: (Int) -> Unit,
) {
    SectionHeader(title = stringResource(R.string.config_backup_settings))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(Spacing.card),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            LabeledSwitch(
                label = stringResource(R.string.config_backup_mode),
                checked = backupMode,
                onCheckedChange = onBackupModeChange,
            )
            LabeledSwitch(
                label = stringResource(R.string.config_backup_user_data),
                checked = backupUserData,
                onCheckedChange = onBackupUserDataChange,
            )
            LabeledSwitch(
                label = stringResource(R.string.config_backup_obb),
                checked = backupObb,
                onCheckedChange = onBackupObbChange,
            )
            LabeledSwitch(
                label = stringResource(R.string.config_backup_wifi),
                checked = backupWifi,
                onCheckedChange = onBackupWifiChange,
            )
            LabeledSwitch(
                label = stringResource(R.string.config_ignore_running_apps),
                checked = ignoreRunning,
                onCheckedChange = onIgnoreRunningChange,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = outputPath,
                    onValueChange = onOutputPathChange,
                    label = { Text(stringResource(R.string.config_output_dir)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Spacer(Modifier.width(Spacing.sm))
                Button(
                    onClick = onChooseOutputDir,
                    modifier = Modifier.height(56.dp),
                ) {
                    Text(stringResource(R.string.config_choose))
                }
            }

            OutlinedTextField(
                value = compressionMethod,
                onValueChange = onCompressionMethodChange,
                label = { Text(stringResource(R.string.config_compression_method)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            UserSelector(
                userList = userList,
                selectedUserId = backupUserId,
                onUserSelected = onBackupUserIdChange,
            )
        }
    }
}

@Composable
private fun LabeledSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserSelector(
    userList: List<Pair<Int, String>>,
    selectedUserId: Int,
    onUserSelected: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = userList.find { it.first == selectedUserId }?.let {
        stringResource(R.string.config_user_format, it.second, it.first)
    } ?: stringResource(R.string.config_user_format, "Owner", 0)

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.config_backup_user)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            singleLine = true,
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            userList.forEach { (id, name) ->
                val display = stringResource(R.string.config_user_format, name, id)
                DropdownMenuItem(
                    text = { Text(display) },
                    onClick = {
                        onUserSelected(id)
                        expanded = false
                    },
                    modifier = Modifier.selectable(
                        selected = id == selectedUserId,
                        role = Role.Button,
                        onClick = {},
                    ),
                )
            }
        }
    }
}
