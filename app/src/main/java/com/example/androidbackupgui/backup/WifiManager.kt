package com.example.androidbackupgui.backup

import com.example.androidbackupgui.root.RootShell
import com.example.androidbackupgui.root.shellEscape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Backup and restore WiFi configuration.
 * Mirrors backup_script WiFi backup/restore logic.
 */
object WifiManager {

    // Possible WiFi config paths on different Android versions
    private val WIFI_PATHS = listOf(
        "/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml",
        "/data/misc/wifi/WifiConfigStore.xml",
        "/data/misc/wifi/wpa_supplicant.conf",
        "/data/vendor/wifi/wpa/wpa_supplicant.conf"
    )

    /**
     * Find the active WiFi config file path. Public for use by BackupOperation.
     */
    fun findWifiConfigPath(): String? {
        for (path in WIFI_PATHS) {
            val result = RootShell.exec("test -f '$path' && echo 'FOUND'")
            if (result.output.contains("FOUND")) return path
        }
        return null
    }

    /**
     * Backup WiFi configuration to a file.
     * @return the backup file path, or null on failure.
     */
    suspend fun backup(outputDir: File): File? = withContext(Dispatchers.IO) {
        val wifiSource = findWifiConfigPath() ?: return@withContext null
        val wifiDest = File(outputDir, "WifiConfigStore.xml")

        val result = RootShell.exec("cp '$wifiSource' '${wifiDest.absolutePath.shellEscape()}'")
        if (result.isSuccess) wifiDest else null
    }

    /**
     * Restore WiFi configuration from a backup file.
     * @return true on success.
     */
    suspend fun restore(backupDir: File): Boolean = withContext(Dispatchers.IO) {
        val backupFile = File(backupDir, "WifiConfigStore.xml")
        if (!backupFile.exists()) return@withContext false

        val backupPath = backupFile.absolutePath.shellEscape()
        val wifiTarget = findWifiConfigPath()
        if (wifiTarget == null) {
            // Try the most common path
            val fallback = "/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml"
            val parent = File(fallback).parentFile?.absolutePath?.shellEscape() ?: return@withContext false
            RootShell.exec("mkdir -p '$parent'")
            val result = RootShell.exec("cp '$backupPath' '$fallback'")
            if (!result.isSuccess) return@withContext false
            RootShell.exec("chown system:wifi '$fallback'")
            RootShell.exec("chmod 0660 '$fallback'")
        } else {
            val result = RootShell.exec("cp '$backupPath' '$wifiTarget'")
            if (!result.isSuccess) return@withContext false
            RootShell.exec("chown system:wifi '$wifiTarget'")
            RootShell.exec("chmod 0660 '$wifiTarget'")
        }

        // WiFi backup only takes effect after reboot, but we can try reloading
        RootShell.exec("svc wifi disable 2>/dev/null")
        RootShell.exec("svc wifi enable 2>/dev/null")
        true
    }
}
