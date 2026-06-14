package com.example.androidbackupgui.backup.core

import java.util.Locale

/** Format byte count to human-readable string (e.g. "1.5 MB"). */
fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    val exp = (63 - bytes.countLeadingZeroBits()) / 10
    val value = bytes.toDouble() / (1L shl (exp * 10))
    return "%.1f %s".format(Locale.US, value, units[exp - 1].coerceAtMost(units.last()))
}
