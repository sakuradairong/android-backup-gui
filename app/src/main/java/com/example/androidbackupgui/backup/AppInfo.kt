package com.example.androidbackupgui.backup

import kotlinx.serialization.Serializable

/**
 * 应用元数据。
 *
 * 由 [com.example.androidbackupgui.backup.scan.AppScanner] 扫描产生，
 * 作为备份/恢复模块之间的统一应用信息载体。
 */
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
)
