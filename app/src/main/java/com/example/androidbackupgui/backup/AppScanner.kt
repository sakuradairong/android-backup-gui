package com.example.androidbackupgui.backup

import android.content.Context
import android.content.pm.PackageManager
import com.example.androidbackupgui.root.RootShell
import com.example.androidbackupgui.root.shellEscape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
@Serializable
data class DataSizes(
    val apkBytes: Long = 0,
    val userBytes: Long = 0,
    val userDeBytes: Long = 0,
    val dataBytes: Long = 0,
    val obbBytes: Long = 0,
    val mediaBytes: Long = 0,
)

@Serializable
data class AppInfo(
    val packageName: PackageName,
    val label: String = "",
    val isSystem: Boolean = false,
    val apkPaths: List<String> = emptyList(),
    val hasObb: Boolean = false,
    val isRunning: Boolean = false,
    val backupSize: Long = 0,  // estimated from last backup
    // Enhanced fields (multi-user, keystore, icon)
    val userId: UserId = UserId(0),
    val hasKeystore: Boolean = false,
    val iconPath: String? = null,
    val dataSizes: DataSizes = DataSizes(),
)

object AppScanner {

    /** Scan all third-party installed packages. */
    suspend fun scanThirdParty(context: Context, userId: Int = 0): List<AppInfo> = withContext(Dispatchers.IO) {
        val result = RootShell.exec("pm list packages -3 --user $userId")
        if (!result.isSuccess) return@withContext emptyList()

        val packages = result.output.lines()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotEmpty() }
            .map { AppInfo(packageName = PackageName(it), userId = UserId(userId)) }
        resolveLabels(context, packages)
    }

    suspend fun scanSystem(context: Context, config: BackupConfig, userId: Int = 0): List<AppInfo> = withContext(Dispatchers.IO) {
        val result = RootShell.exec("pm list packages -s --user $userId")
        if (!result.isSuccess) return@withContext emptyList()

        val systemWhitelist = config.system.toSet()
        val dataWhitelist = config.whitelist.toSet()
        val blacklist = config.blacklist.toSet()

        val packages = result.output.lines()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotEmpty() }
            .filter { pkg ->
                pkg in systemWhitelist || pkg in dataWhitelist
            }
            .filter { pkg ->
                if (config.blacklistMode == 1) pkg !in blacklist else true
            }
            .map { AppInfo(packageName = PackageName(it), isSystem = true, userId = UserId(userId)) }
        resolveLabels(context, packages)
    }

    /**
     * Resolve human-readable app labels using PackageManager.
     * Requires QUERY_ALL_PACKAGES permission on Android 11+ (declared in manifest).
     * Falls back to package name for unreadable packages.
     * Modifies the list in-place and returns it.
     */
    fun resolveLabels(context: Context, packages: List<AppInfo>): List<AppInfo> {
        if (packages.isEmpty()) return packages
        val pm = context.packageManager
        return packages.map { app ->
            val resolvedLabel = try {
                val ai = pm.getApplicationInfo(app.packageName.value, 0)
                pm.getApplicationLabel(ai).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                app.packageName.value
            }
            app.copy(label = resolvedLabel)
        }
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
    /** Check if an app has keystore entries (critical — keystore keys can be lost on backup). */
    suspend fun hasKeystore(packageName: String): Boolean = withContext(Dispatchers.IO) {
        // Resolve the app's UID first
        val uidResult = RootShell.exec("dumpsys package '${packageName.shellEscape()}' | grep 'userId=' | head -1")
        val uid = uidResult.output
            .substringAfter("userId=", "")
            .substringBefore(" ")
            .substringBefore(",")
            .trim()
            .toIntOrNull() ?: return@withContext false
        // keystore_cli_v2 list as app UID — more than 1 line means has keystore entries
        val ksResult = RootShell.exec("su $uid -c 'keystore_cli_v2 list' 2>/dev/null")
        ksResult.output.lines().count { it.isNotBlank() } > 1
    }
    /** Enumerate all user profiles on the device for multi-user support. */
    suspend fun enumerateUsers(): List<Pair<Int, String>> = withContext(Dispatchers.IO) {
        val result = RootShell.exec("pm list users")
        if (!result.isSuccess) return@withContext listOf(0 to "Owner")

        result.output.lines()
            .filter { it.contains("UserInfo") }
            .mapNotNull { line ->
                val id = line.substringBefore(":").trim().toIntOrNull()
                val name = line.substringAfter(":").substringBefore(":").trim()
                if (id != null) id to name else null
            }
    }

    /** Extract and save an app's icon to the given directory. */
    suspend fun extractIcon(packageName: String, destDir: java.io.File, userId: Int = 0): String? = withContext(Dispatchers.IO) {
        // Try snapshot cache first
        val snapshotDir = "/data/system_ce/$userId/snapshots/$packageName"
        val snapshotResult = RootShell.exec("ls '${snapshotDir.shellEscape()}/' 2>/dev/null | head -1")
        if (snapshotResult.isSuccess && snapshotResult.output.isNotBlank()) {
            val iconName = snapshotResult.output.trim()
            val iconFile = java.io.File(destDir, "app_icon.png")
            val copyResult = RootShell.exec("cp '${snapshotDir.shellEscape()}/${iconName.shellEscape()}' '${iconFile.absolutePath.shellEscape()}' 2>/dev/null")
            if (copyResult.isSuccess && iconFile.exists()) {
                return@withContext iconFile.absolutePath
            }
        }
        // Fallback: extract from APK using aapt
        val apkPaths = getApkPaths(packageName)
        if (apkPaths.isNotEmpty()) {
            val primaryApk = apkPaths.first()
            val badgeResult = RootShell.exec("aapt d badging '$primaryApk' 2>/dev/null | grep '^application:.*icon=' | head -1")
            if (badgeResult.isSuccess) {
                val iconPath = badgeResult.output
                    .substringAfter("icon='")
                    .substringBefore("'")
                    .takeIf { it.isNotBlank() }
                if (iconPath != null) {
                    // The icon path is relative inside the APK, extract using aapt
                    val iconFile = java.io.File(destDir, "app_icon.png")
                    RootShell.exec("aapt d raw '$primaryApk' '$iconPath' > '${iconFile.absolutePath.shellEscape()}' 2>/dev/null")
                    if (iconFile.exists()) {
                        return@withContext iconFile.absolutePath
                    }
                }
            }
        }
        null
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
