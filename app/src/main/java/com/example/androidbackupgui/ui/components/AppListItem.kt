package com.example.androidbackupgui.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDecoration
import com.example.androidbackupgui.R
import com.example.androidbackupgui.backup.AppInfo
import com.example.androidbackupgui.ui.theme.Spacing

/**
 * Shared selectable app row used by Backup and Restore screens.
 *
 * The whole row is one accessibility focusable with a clear state description.
 * The inner Checkbox is hidden from the accessibility tree so screen readers
 * do not announce two separate elements.
 */
@Composable
fun AppListItem(
    app: AppInfo,
    isSelected: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    isDataExcluded: Boolean = false,
    onExcludeDataToggle: ((Boolean) -> Unit)? = null,
) {
    val label = app.label.ifEmpty { app.packageName.value }
    val stateDesc = stringResource(
        if (isSelected) R.string.cd_selected else R.string.cd_not_selected,
    )

    Card(
        onClick = { onToggle(!isSelected) },
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .padding(Spacing.card)
                .semantics(mergeDescendants = true) {
                    role = Role.Checkbox
                    contentDescription = label
                    stateDescription = stateDesc
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggle(it) },
                modifier = Modifier.clearAndSetSemantics { },
            )
            Spacer(Modifier.width(Spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = app.packageName.value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isSelected && onExcludeDataToggle != null) {
                DataToggle(
                    label = label,
                    isExcluded = isDataExcluded,
                    onToggle = onExcludeDataToggle,
                )
            }
        }
    }
}

@Composable
private fun DataToggle(
    label: String,
    isExcluded: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val stateDesc = stringResource(
        if (isExcluded) R.string.app_list_data_excluded else R.string.app_list_data_included,
        label,
    )

    TextButton(
        onClick = { onToggle(!isExcluded) },
        modifier = Modifier.semantics {
            stateDescription = stateDesc
        },
    ) {
        Text(
            text = stringResource(R.string.app_list_data_toggle),
            textDecoration = if (isExcluded) TextDecoration.LineThrough else TextDecoration.None,
            color = if (isExcluded) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
    }
}
