package com.example.androidbackupgui.backup

import android.content.Context
import android.content.pm.PackageManager
import com.example.androidbackupgui.root.RootShell
import com.example.androidbackupgui.root.shellEscape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppInfo(
    val packageName: String,
    var label: String = "",
    val isSystem: Boolean = false,
    val apkPaths: List<String> = emptyList(),
    val hasObb: Boolean = false,
    val isRunning: Boolean = false,
    val backupSize: Long = 0  // estimated from last backup
)

object AppScanner {

    /** Scan all third-party installed packages. */
    suspend fun scanThirdParty(context: Context): List<AppInfo> = withContext(Dispatchers.IO) {
        val result = RootShell.exec("pm list packages -3")
        if (!result.isSuccess) return@withContext emptyList()

        val packages = result.output.lines()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotEmpty() }
            .map { AppInfo(packageName = it) }
        resolveLabels(context, packages)
    }

    /** Scan all system packages. */
    suspend fun scanSystem(context: Context, config: BackupConfig): List<AppInfo> = withContext(Dispatchers.IO) {
        val result = RootShell.exec("pm list packages -s")
        if (!result.isSuccess) return@withContext emptyList()

        val systemWhitelist = config.system.toSet()
        val dataWhitelist = config.whitelist.toSet()
        val blacklist = config.blacklist.toSet()

        val packages = result.output.lines()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotEmpty() }
            .filter { pkg ->
                // Allow if in system whitelist or data whitelist
                pkg in systemWhitelist || pkg in dataWhitelist
            }
            .filter { pkg ->
                // Exclude if in blacklist (when blacklistMode=1, full ignore)
                if (config.blacklistMode == 1) pkg !in blacklist else true
            }
            .map { AppInfo(packageName = it, isSystem = true) }
        resolveLabels(context, packages)
    }

    /**
     * Resolve human-readable app labels using PackageManager (fast, no root).
     * Falls back to dumpsys for packages that PackageManager can't resolve.
     * Modifies the list in-place and returns it.
     */
    fun resolveLabels(context: Context, packages: List<AppInfo>): List<AppInfo> {
        val pm = context.packageManager
        for (app in packages) {
            app.label = try {
                val ai = pm.getApplicationInfo(app.packageName, 0)
                pm.getApplicationLabel(ai).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                // PackageManager can't resolve (e.g. some system packages) —
                // leave empty; caller can use getAppLabel() for root-based fallback
                ""
            }
        }
        return packages
    }

    /** Get APK paths for a package. */
    suspend fun getApkPaths(packageName: String): List<String> = withContext(Dispatchers.IO) {
        val result = RootShell.exec("pm path '${packageName.shellEscape()}'")
        if (!result.isSuccess) return@withContext emptyList()

        result.output.lines()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotEmpty() }
    }

    /** Get the app label/name. */
    suspend fun getAppLabel(packageName: String): String = withContext(Dispatchers.IO) {
        val result = RootShell.exec("dumpsys package '${packageName.shellEscape()}' | grep -A1 'ApplicationInfo' | grep 'label=' | head -1")
        val label = result.output
            .substringAfter("label=", "")
            .substringBefore(" ")
            .removeSurrounding("\"")
            .trim()
        label.ifEmpty { packageName }
    }

    /** Check if a package has OBB data. */
    suspend fun hasObbData(packageName: String): Boolean = withContext(Dispatchers.IO) {
        val result = RootShell.exec("ls /storage/emulated/0/Android/obb/${packageName.shellEscape()}/ 2>/dev/null")
        result.output.isNotBlank()
    }

    /** Check if a package is currently running. */
    suspend fun isPackageRunning(packageName: String): Boolean = withContext(Dispatchers.IO) {
        val result = RootShell.exec("pidof '${packageName.shellEscape()}'")
        result.output.isNotBlank()
    }

    /** Apply appList.txt-style filters. Lines starting with # are ignored, ! means apk-only. */
    fun parseAppList(content: String): List<Pair<String, Boolean>> {
        return content.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                if (line.startsWith("!")) {
                    line.removePrefix("!").trim() to false // apk only (no data)
                } else {
                    line.trim() to true // full backup
                }
            }
    }
}
