package com.example.androidbackupgui.ui

/** Navigation destinations */
enum class Screen(val label: String, val icon: String) {
    BACKUP("应用备份", "backup"),
    RESTORE("应用恢复", "restore"),
    CONFIG("备份配置", "settings")
}
