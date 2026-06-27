package com.example.androidbackupgui.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

/**
 * Status text that announces changes to screen readers.
 *
 * Use this for progress messages, scan results, and error text that updates
 * while the user remains on the same screen.
 */
@Composable
fun StatusText(
    text: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    Text(
        text = text,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyMedium,
        color = if (isError) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

/**
 * Compact status line with screen-reader live region.
 *
 * Alias for [StatusText] with the same behaviour; use whichever name reads
 * better at the call site.
 */
@Composable
fun StatusLine(
    text: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    StatusText(
        text = text,
        modifier = modifier,
        isError = isError,
    )
}
