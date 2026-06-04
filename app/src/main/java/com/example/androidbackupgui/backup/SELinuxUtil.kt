package com.example.androidbackupgui.backup

import com.example.androidbackupgui.root.RootShell
import com.example.androidbackupgui.root.shellEscape
import android.util.Log

/**
 * SELinux context utilities for restoring file security labels.
 * Mirrors the approach from Android-DataBackup (Xayah) SELinuxUtil.kt.
 */
object SELinuxUtil {

    private const val TAG = "SELinuxUtil"

    /**
     * Query the SELinux context of a path.
     * Returns the full SELinux label (e.g., "u:object_r:app_data_file:s0:c512,c768")
     * or null if the path doesn't exist or the query fails.
     */
    suspend fun getContext(path: String): String? {
        val escaped = path.shellEscape()
        val result = RootShell.exec("ls -Zd '$escaped' 2>/dev/null | awk 'NF>1{print \$1}'")
        if (!result.isSuccess) return null
        val context = result.output.trim()
        return context.ifBlank { null }
    }

    /**
     * Restore a SELinux context on a path recursively.
     * Equivalent to: chcon -hR [context] [path]/
     */
    suspend fun chcon(context: String, path: String): Boolean {
        val ctxEsc = context.shellEscape()
        val pathEsc = path.shellEscape()
        val result = RootShell.exec("chcon -hR '$ctxEsc' '$pathEsc/' 2>/dev/null")
        if (result.isSuccess) return true
        val fallback = RootShell.exec("chcon -R '$ctxEsc' '$pathEsc/' 2>/dev/null")
        if (!fallback.isSuccess) {
            Log.w(TAG, "chcon failed (both primary and fallback): $path")
        }
        return fallback.isSuccess
    }
}
