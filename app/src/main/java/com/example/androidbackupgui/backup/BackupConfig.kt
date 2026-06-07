package com.example.androidbackupgui.backup

import java.io.File
import kotlinx.serialization.Serializable

/**
 * Mirrors backup_settings.conf from backup_script.
 * All keys correspond 1:1 with the original shell config.
 *
 * This is an immutable data class. Use [copy] to create modified instances.
 */
@Serializable
data class BackupConfig(
    // Operation mode
    val lo: Int = 0,                        // 0=volume key, 1=volume force, 2=keyboard
    val backgroundExecution: Int = 0,       // 0=foreground, 1=background
    val setDisplayPowerMode: Int = 0,       // 1=keep screen on during backup
    val shellLang: String = "",             // ""=auto, "1"=zh-CN, "0"=zh-TW

    // Paths
    val outputPath: String = "",            // Custom output dir
    val listLocation: String = "",          // Custom appList.txt location

    // Update
    val update: Int = 1,                    // 1=auto update
    val cdn: Int = 1,                       // CDN node

    // Filters
    val mountPoint: String = "rannki|0000-1",
    val user: String = "",

    // Backup mode
    val backupMode: Int = 1,                // 1=data+apk, 0=apk only
    val backupUserData: Int = 1,
    val backupObbData: Int = 1,
    val backupMedia: Int = 0,
    val backgroundAppsIgnore: Int = 0,
    val backupUserId: Int = 0,              // Android user ID (0=Owner)

    // Custom paths
    val customPath: List<String> = listOf(
        "/storage/emulated/0/Pictures/",
        "/storage/emulated/0/Download/",
        "/storage/emulated/0/Music",
        "/storage/emulated/0/DCIM/",
        "/data/adb"
    ),

    // Blacklist
    val blacklistMode: Int = 0,             // 1=full ignore, 0=apk only
    val blacklist: List<String> = emptyList(),

    // Whitelists
    val whitelist: List<String> = emptyList(),
    val system: List<String> = emptyList(),

    // Compression
    val compressionMethod: String = "zstd", // zstd or tar

    // Terminal colors
    val rgbA: Int = 226,
    val rgbB: Int = 123,
    val rgbC: Int = 177,

    val backupWifi: Int = 1,

    // Restic deduplicated backup with rclone backend
    val resticEnabled: Int = 0,
    val resticRepo: String = "",
    val resticPassword: String = "",
    val resticBackend: String = "local",    // local / webdav / smb
    val resticBackendUrl: String = "",
    val resticBackendUser: String = "",
    val resticBackendPass: String = "",
    val resticBackendShare: String = "",      // SMB share name
    val resticBackendDomain: String = ""      // SMB domain (optional, for NTLM)
) {
    companion object {
        /**
         * Unescape a quoted config value. Reverses [escapeValue]: turns \\ and \"
         * back into \ and ". Applied only to values that were stored inside quotes.
         */
        private fun unescapeValue(s: String): String {
            if (s.indexOf('\\') < 0) return s
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    sb.append(s[i + 1]); i += 2
                } else {
                    sb.append(c); i++
                }
            }
            return sb.toString()
        }

        /** Escape a value for safe storage inside double quotes. */
        private fun escapeValue(s: String): String =
            s.replace("\\", "\\\\").replace("\"", "\\\"")

        fun fromFile(file: File): BackupConfig {
            if (!file.exists()) return BackupConfig()

            // Quoted-string fields preserve their inner whitespace and may contain
            // escaped characters; bare fields are trimmed as before.
            val quotedKeys = setOf(
                "Output_path", "list_location", "mount_point",
                "restic_repo", "restic_password", "restic_backend_url",
                "restic_backend_user", "restic_backend_pass",
                "restic_backend_share", "restic_backend_domain"
            )

            val props = mutableMapOf<String, String>()
            file.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachLine
                val eq = trimmed.indexOf('=')
                if (eq < 0) return@forEachLine
                val key = trimmed.substring(0, eq).trim()
                val rawValue = trimmed.substring(eq + 1)
                props[key] = if (key in quotedKeys) {
                    // Strip the surrounding quotes (if present) WITHOUT trimming the
                    // inner content, so leading/trailing spaces in e.g. a password
                    // survive a save/load round trip. Then unescape.
                    val v = rawValue
                    if (v.length >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                        unescapeValue(v.substring(1, v.length - 1))
                    } else {
                        // Legacy/unquoted value — fall back to trimmed form.
                        unescapeValue(v.trim().removeSurrounding("\""))
                    }
                } else {
                    rawValue.trim().removeSurrounding("\"")
                }
            }

            fun int(key: String, default: Int = 0) = props[key]?.toIntOrNull() ?: default
            fun str(key: String) = props[key] ?: ""
            fun lines(key: String): List<String> {
                val raw = props[key] ?: return emptyList()
                return raw.split("\\s+".toRegex())
                    .filter { it.isNotBlank() && it != "\"\"" }
                    .map { it.replace("%20", " ") }
            }

            return BackupConfig(
                lo = int("Lo"),
                backgroundExecution = int("background_execution"),
                setDisplayPowerMode = int("setDisplayPowerMode"),
                shellLang = str("Shell_LANG"),
                outputPath = str("Output_path"),
                listLocation = str("list_location"),
                update = int("update", default = 1),
                cdn = int("cdn", default = 1),
                mountPoint = str("mount_point"),
                user = str("user"),
                backupMode = int("Backup_Mode", default = 1),
                backupUserData = int("Backup_user_data", default = 1),
                backupObbData = int("Backup_obb_data", default = 1),
                backupMedia = int("backup_media"),
                backgroundAppsIgnore = int("Background_apps_ignore"),
                backupUserId = int("backup_user_id"),
                customPath = lines("Custom_path"),
                blacklistMode = int("blacklist_mode"),
                blacklist = lines("blacklist"),
                whitelist = lines("whitelist"),
                system = lines("system"),
                compressionMethod = str("Compression_method").ifEmpty { "zstd" },
                rgbA = int("rgb_a").let { if (it == 0) 226 else it },
                rgbB = int("rgb_b").let { if (it == 0) 123 else it },
                rgbC = int("rgb_c").let { if (it == 0) 177 else it },
                backupWifi = int("backup_wifi", default = 1),
                resticEnabled = int("restic_enabled"),
                resticRepo = str("restic_repo"),
                resticPassword = str("restic_password"),
                resticBackend = str("restic_backend").ifEmpty { "local" },
                resticBackendUrl = str("restic_backend_url"),
                resticBackendUser = str("restic_backend_user"),
                resticBackendPass = str("restic_backend_pass"),
                resticBackendShare = str("restic_backend_share"),
                resticBackendDomain = str("restic_backend_domain"),
            )
        }

        fun toFile(config: BackupConfig, file: File) {
            file.parentFile?.mkdirs()
            file.writeText(buildString {
                appendLine("# SpeedBackup Configuration")
                appendLine("Lo=${config.lo}")
                appendLine("background_execution=${config.backgroundExecution}")
                appendLine("setDisplayPowerMode=${config.setDisplayPowerMode}")
                appendLine("Shell_LANG=${config.shellLang}")
                appendLine("Output_path=\"${escapeValue(config.outputPath)}\"")
                appendLine("list_location=\"${escapeValue(config.listLocation)}\"")
                appendLine("update=${config.update}")
                appendLine("cdn=${config.cdn}")
                appendLine("mount_point=\"${escapeValue(config.mountPoint)}\"")
                appendLine("user=${config.user}")
                appendLine("Backup_Mode=${config.backupMode}")
                appendLine("Backup_user_data=${config.backupUserData}")
                appendLine("Backup_obb_data=${config.backupObbData}")
                appendLine("backup_media=${config.backupMedia}")
                appendLine("backup_user_id=${config.backupUserId}")
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
                appendLine("restic_repo=\"${escapeValue(config.resticRepo)}\"")
                appendLine("restic_password=\"${escapeValue(config.resticPassword)}\"")
                appendLine("restic_backend=${config.resticBackend}")
                appendLine("restic_backend_url=\"${escapeValue(config.resticBackendUrl)}\"")
                appendLine("restic_backend_user=\"${escapeValue(config.resticBackendUser)}\"")
                appendLine("restic_backend_pass=\"${escapeValue(config.resticBackendPass)}\"")
                appendLine("restic_backend_share=\"${escapeValue(config.resticBackendShare)}\"")
                appendLine("restic_backend_domain=\"${escapeValue(config.resticBackendDomain)}\"")
            })
            file.setReadable(true, true)   // owner only
            file.setWritable(true, true)   // owner only
        }
    }
}
