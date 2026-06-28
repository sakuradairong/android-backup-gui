package com.example.androidbackupgui.ui.config

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.androidbackupgui.R
import com.example.androidbackupgui.ui.ResticStatus
import com.example.androidbackupgui.ui.theme.Spacing

/**
 * Status and action card for the Restic section.
 */
@Composable
fun ResticStatusCard(
    status: ResticStatus,
    onInitRepo: () -> Unit,
    onShowStats: () -> Unit,
    onPruneSnapshots: () -> Unit,
    onUnlockRepo: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(Spacing.card)) {
            Text(
                text = status.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(Spacing.md))

            if (status.initButtonVisible) {
                Button(
                    onClick = onInitRepo,
                    enabled = status.initButtonEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.config_init_repo))
                }
            }

            if (status.statsButtonVisible) {
                Button(
                    onClick = onShowStats,
                    enabled = status.statsButtonEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.config_repo_stats))
                }
            }

            if (status.pruneButtonVisible) {
                OutlinedButton(
                    onClick = onPruneSnapshots,
                    enabled = status.pruneButtonEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.config_prune_snapshots))
                }
            }

            if (status.unlockButtonVisible) {
                Button(
                    onClick = onUnlockRepo,
                    enabled = status.unlockButtonEnabled,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                    ),
                ) {
                    Text(stringResource(R.string.config_unlock_repo))
                }
            }
        }
    }
}
