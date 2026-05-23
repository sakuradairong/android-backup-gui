package com.example.androidbackupgui.backup

import java.io.File

/**
 * Mirrors backup_settings.conf from backup_script.
 * All keys correspond 1:1 with the original shell config.
 */
data class BackupConfig(
    // Operation mode
    var lo: Int = 0,                        // 0=volume key, 1=volume force, 2=keyboard
    var backgroundExecution: Int = 0,       // 0=foreground, 1=background
    var setDisplayPowerMode: Int = 0,       // 1=keep screen on during backup
    var shellLang: String = "",             // ""=auto, "1"=zh-CN, "0"=zh-TW

    // Paths
    var outputPath: String = "",            // Custom output dir
    var listLocation: String = "",          // Custom appList.txt location

    // Update
    var update: Int = 1,                    // 1=auto update
    var cdn: Int = 1,                       // CDN node

    // Filters
    var mountPoint: String = "rannki|0000-1",
    var user: String = "",

    // Backup mode
    var backupMode: Int = 1,                // 1=data+apk, 0=apk only
    var backupUserData: Int = 1,
    var backupObbData: Int = 1,
    var backupMedia: Int = 0,
    var backgroundAppsIgnore: Int = 0,

    // Custom paths
    var customPath: List<String> = listOf(
        "/storage/emulated/0/Pictures/",
        "/storage/emulated/0/Download/",
        "/storage/emulated/0/Music",
        "/storage/emulated/0/DCIM/",
        "/data/adb"
    ),

    // Blacklist
    var blacklistMode: Int = 0,             // 1=full ignore, 0=apk only
    var blacklist: List<String> = emptyList(),

    // Whitelists
    var whitelist: List<String> = emptyList(),
    var system: List<String> = emptyList(),

    // Compression
    var compressionMethod: String = "zstd", // zstd or tar

    // Terminal colors
    var rgbA: Int = 226,
    var rgbB: Int = 123,
    var rgbC: Int = 177,

    var backupWifi: Int = 1,

    // Restic deduplicated backup with rclone backend
    var resticEnabled: Int = 0,
    var resticRepo: String = "",
    var resticPassword: String = "",
    var resticBackend: String = "local",    // local / webdav / smb
    var resticBackendUrl: String = "",
    var resticBackendUser: String = "",
    var resticBackendPass: String = ""
) {
    companion object {
        fun fromFile(file: File): BackupConfig {
            val config = BackupConfig()
            if (!file.exists()) return config

            val props = mutableMapOf<String, String>()
            file.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachLine
                val eq = trimmed.indexOf('=')
                if (eq < 0) return@forEachLine
                val key = trimmed.substring(0, eq).trim()
                val value = trimmed.substring(eq + 1).trim().removeSurrounding("\"")
                props[key] = value
            }

            fun int(key: String, default: Int = 0) = props[key]?.toIntOrNull() ?: default
            fun str(key: String) = props[key] ?: ""
            fun lines(key: String): List<String> {
                val raw = props[key] ?: return emptyList()
                return raw.split("\\s+".toRegex())
                    .filter { it.isNotBlank() && it != "\"\"" }
                    .map { it.replace("%20", " ") }
            }

            config.lo = int("Lo")
            config.backgroundExecution = int("background_execution")
            config.setDisplayPowerMode = int("setDisplayPowerMode")
            config.shellLang = str("Shell_LANG")
            config.outputPath = str("Output_path")
            config.listLocation = str("list_location")
            config.update = int("update", default = 1)
            config.cdn = int("cdn", default = 1)
            config.mountPoint = str("mount_point")
            config.user = str("user")
            config.backupMode = int("Backup_Mode", default = 1)
            config.backupUserData = int("Backup_user_data", default = 1)
            config.backupObbData = int("Backup_obb_data", default = 1)
            config.backupMedia = int("backup_media")
            config.backgroundAppsIgnore = int("Background_apps_ignore")
            config.customPath = lines("Custom_path")
            config.blacklistMode = int("blacklist_mode")
            config.blacklist = lines("blacklist")
            config.whitelist = lines("whitelist")
            config.system = lines("system")
            config.compressionMethod = str("Compression_method").ifEmpty { "zstd" }
            config.rgbA = int("rgb_a").let { if (it == 0) 226 else it }
            config.rgbB = int("rgb_b").let { if (it == 0) 123 else it }
            config.rgbC = int("rgb_c").let { if (it == 0) 177 else it }
            config.backupWifi = int("backup_wifi", default = 1)
            config.resticEnabled = int("restic_enabled")
            config.resticRepo = str("restic_repo")
            config.resticPassword = str("restic_password")
            config.resticBackend = str("restic_backend").ifEmpty { "local" }
            config.resticBackendUrl = str("restic_backend_url")
            config.resticBackendUser = str("restic_backend_user")
            config.resticBackendPass = str("restic_backend_pass")

            return config
        }

        fun toFile(config: BackupConfig, file: File) {
            file.parentFile?.mkdirs()
            file.writeText(buildString {
                appendLine("# SpeedBackup Configuration")
                appendLine("Lo=${config.lo}")
                appendLine("background_execution=${config.backgroundExecution}")
                appendLine("setDisplayPowerMode=${config.setDisplayPowerMode}")
                appendLine("Shell_LANG=${config.shellLang}")
                appendLine("Output_path=\"${config.outputPath}\"")
                appendLine("list_location=\"${config.listLocation}\"")
                appendLine("update=${config.update}")
                appendLine("cdn=${config.cdn}")
                appendLine("mount_point=\"${config.mountPoint}\"")
                appendLine("user=${config.user}")
                appendLine("Backup_Mode=${config.backupMode}")
                appendLine("Backup_user_data=${config.backupUserData}")
                appendLine("Backup_obb_data=${config.backupObbData}")
                appendLine("backup_media=${config.backupMedia}")
                appendLine("Background_apps_ignore=${config.backgroundAppsIgnore}")
                append("Custom_path=\"")
                config.customPath.forEach { append(" ${it.replace(" ", "%20")}") }
                appendLine(" \"")
                appendLine("blacklist_mode=${config.blacklistMode}")
                append("blacklist=\"")
                config.blacklist.forEach { append(" ${it.replace(" ", "%20")}") }
                appendLine(" \"")
                append("whitelist=\"")
                config.whitelist.forEach { append(" ${it.replace(" ", "%20")}") }
                appendLine(" \"")
                append("system=\"")
                config.system.forEach { append(" ${it.replace(" ", "%20")}") }
                appendLine(" \"")
                appendLine("Compression_method=${config.compressionMethod}")
                appendLine("rgb_a=${config.rgbA}")
                appendLine("rgb_b=${config.rgbB}")
                appendLine("rgb_c=${config.rgbC}")
                appendLine("backup_wifi=${config.backupWifi}")
                appendLine("restic_enabled=${config.resticEnabled}")
                appendLine("restic_repo=\"${config.resticRepo}\"")
                appendLine("restic_password=\"${config.resticPassword}\"")
                appendLine("restic_backend=${config.resticBackend}")
                appendLine("restic_backend_url=\"${config.resticBackendUrl}\"")
                appendLine("restic_backend_user=\"${config.resticBackendUser}\"")
                appendLine("restic_backend_pass=\"${config.resticBackendPass}\"")
            })
        }
    }
}
