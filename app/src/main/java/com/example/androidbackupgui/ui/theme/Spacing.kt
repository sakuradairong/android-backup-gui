package com.example.androidbackupgui.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Design tokens for spacing and sizing in the Android Backup GUI UI.
 *
 * These values replace hard-coded `dp` literals across screens so the layout
 * stays consistent and easier to adjust globally.
 */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp

    /** Horizontal page padding used by scrollable screen content. */
    val pageHorizontal = lg

    /** Vertical padding at the top/bottom of a scrollable screen. */
    val pageVertical = lg

    /** Padding inside elevated cards and surface containers. */
    val card = lg

    /** Vertical gap between major sections on a screen. */
    val sectionGap = lg

    /** Vertical gap between related items inside a section. */
    val itemGap = sm

    /** Vertical padding for a row containing a radio button. */
    val radioRow = xs

    /** Minimum touch target size for small tappable elements. */
    val minTouchTarget = 48.dp

    /** Height of a single-line list item. */
    val listItemHeight = 56.dp

    /** Elevation for elevated cards. */
    val cardElevation = 2.dp
}
