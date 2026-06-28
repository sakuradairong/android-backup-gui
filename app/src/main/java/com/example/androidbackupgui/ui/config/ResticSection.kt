package com.example.androidbackupgui.ui.config

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.example.androidbackupgui.R
import com.example.androidbackupgui.ui.BackendDisplay
import com.example.androidbackupgui.ui.ResticStatus
import com.example.androidbackupgui.ui.components.SectionHeader
import com.example.androidbackupgui.ui.theme.Spacing

/**
 * Restic repository and remote-backend configuration section used by [ConfigScreen].
 */
@Composable
fun ResticSection(
    resticEnabled: Boolean,
    onResticEnabledChange: (Boolean) -> Unit,
    resticRepo: String,
    onResticRepoChange: (String) -> Unit,
    resticPassword: String,
    onResticPasswordChange: (String) -> Unit,
    resticBackend: String,
    onResticBackendChange: (String) -> Unit,
    resticBackendUrl: String,
    onResticBackendUrlChange: (String) -> Unit,
    resticBackendUser: String,
    onResticBackendUserChange: (String) -> Unit,
    resticBackendPass: String,
    onResticBackendPassChange: (String) -> Unit,
    resticBackendShare: String,
    onResticBackendShareChange: (String) -> Unit,
    resticBackendDomain: String,
    onResticBackendDomainChange: (String) -> Unit,
    streamingEnabled: Boolean,
    onStreamingEnabledChange: (Boolean) -> Unit,
    backendDisplay: BackendDisplay,
    resticStatus: ResticStatus,
    onInitRepo: () -> Unit,
    onShowStats: () -> Unit,
    onPruneSnapshots: () -> Unit,
    onUnlockRepo: () -> Unit,
) {
    SectionHeader(title = stringResource(R.string.config_restic_backup))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(Spacing.card),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            LabeledSwitch(
                label = stringResource(R.string.config_enable_restic),
                checked = resticEnabled,
                onCheckedChange = onResticEnabledChange,
            )

            if (resticEnabled) {
                OutlinedTextField(
                    value = resticRepo,
                    onValueChange = onResticRepoChange,
                    label = { Text(stringResource(R.string.config_repo_path)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                OutlinedTextField(
                    value = resticPassword,
                    onValueChange = onResticPasswordChange,
                    label = { Text(stringResource(R.string.config_repo_password)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )

                Text(
                    text = stringResource(R.string.config_backend_type),
                    style = MaterialTheme.typography.labelLarge,
                )

                BackendSelector(
                    selectedBackend = resticBackend,
                    onBackendSelected = onResticBackendChange,
                )

                if (resticRepo.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.config_actual_repo, backendDisplay.computedUrl),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (backendDisplay.isRemote) {
                    val isError = resticBackend == "webdav" &&
                        resticBackendUrl.startsWith("http://") &&
                        resticBackendUser.isNotEmpty()
                    OutlinedTextField(
                        value = resticBackendUrl,
                        onValueChange = onResticBackendUrlChange,
                        label = {
                            Text(
                                backendDisplay.urlHint.ifEmpty {
                                    stringResource(R.string.config_repo_path)
                                },
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = isError,
                        supportingText = {
                            when {
                                resticBackend == "webdav" && isError -> {
                                    Text(
                                        stringResource(R.string.config_http_auth_not_allowed),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                resticBackend == "webdav" && resticBackendUrl.startsWith("http://") -> {
                                    Text(
                                        stringResource(R.string.config_http_insecure),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        },
                    )
                }

                if (backendDisplay.needsAuth) {
                    OutlinedTextField(
                        value = resticBackendUser,
                        onValueChange = onResticBackendUserChange,
                        label = { Text(stringResource(R.string.config_username)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = resticBackendPass,
                        onValueChange = onResticBackendPassChange,
                        label = { Text(stringResource(R.string.config_password)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }

                if (backendDisplay.isSmb) {
                    OutlinedTextField(
                        value = resticBackendShare,
                        onValueChange = onResticBackendShareChange,
                        label = { Text(stringResource(R.string.config_smb_share)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = resticBackendDomain,
                        onValueChange = onResticBackendDomainChange,
                        label = { Text(stringResource(R.string.config_smb_domain)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    LabeledSwitch(
                        label = stringResource(R.string.config_streaming_backup),
                        checked = streamingEnabled,
                        onCheckedChange = onStreamingEnabledChange,
                    )
                    Text(
                        text = stringResource(R.string.config_streaming_backup_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                ResticStatusCard(
                    status = resticStatus,
                    onInitRepo = onInitRepo,
                    onShowStats = onShowStats,
                    onPruneSnapshots = onPruneSnapshots,
                    onUnlockRepo = onUnlockRepo,
                )
            }
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

@Composable
private fun BackendSelector(
    selectedBackend: String,
    onBackendSelected: (String) -> Unit,
) {
    val backends = listOf(
        "local" to R.string.backend_local,
        "webdav" to R.string.backend_webdav,
        "smb" to R.string.backend_smb,
        "rest-server" to R.string.backend_rest_server,
    )

    Column(modifier = Modifier.selectableGroup()) {
        backends.forEach { (value, labelRes) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selectedBackend == value,
                        onClick = { onBackendSelected(value) },
                        role = Role.RadioButton,
                    )
                    .padding(vertical = Spacing.radioRow),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = selectedBackend == value,
                    onClick = null,
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(stringResource(labelRes))
            }
        }
    }
}
