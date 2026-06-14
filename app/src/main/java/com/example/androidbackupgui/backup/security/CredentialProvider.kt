package com.example.androidbackupgui.backup.security

import com.example.androidbackupgui.backup.BackupConfig

/**
 * 统一密码提供者 - 消除重复的密码获取逻辑。
 *
 * 从 PasswordManager (EncryptedSharedPreferences) 获取密码，
 * 支持从旧版配置文件迁移密码，并提供回退逻辑。
 */
object CredentialProvider {

    data class Credentials(
        val resticPassword: String,
        val backendPassword: String,
        val backendPass: String,
    )

    /**
     * 从 PasswordManager 获取凭据，支持旧版配置回退。
     *
     * 优先级：
     * 1. PasswordManager (EncryptedSharedPreferences)
     * 2. BackupConfig 中的旧版密码字段
     * 3. 空字符串（默认值）
     */
    fun resolve(config: BackupConfig): Credentials {
        val resticPassword = PasswordManager.getResticPassword()
            ?: config.resticPassword.takeIf {
                // Reject the "stored-in-keystore" placeholder so it never reaches
                // the restic CLI as the literal repository password. The real
                // password is held by PasswordManager; this config field is
                // only a migration artifact.
                it.isNotEmpty() && it != "stored-in-keystore"
            }
            ?: ""

        val backendPassword = PasswordManager.getBackendPassword()
            ?: config.resticBackendPass.takeIf {
                it.isNotEmpty() && it != "stored-in-keystore"
            }
            ?: ""

        val backendPass = PasswordManager.getBackendPass()
            ?: config.resticBackendPass.takeIf {
                it.isNotEmpty() && it != "stored-in-keystore"
            }
            ?: ""

        // 尝试迁移旧版密码到 PasswordManager
        migrateLegacyPasswords(config, resticPassword, backendPass)

        return Credentials(
            resticPassword = resticPassword,
            backendPassword = backendPassword,
            backendPass = backendPass,
        )
    }

    /**
     * 保存凭据到 PasswordManager。
     */
    fun save(
        resticPassword: String?,
        backendPassword: String?,
        backendPass: String?,
    ) {
        resticPassword?.let { PasswordManager.setResticPassword(it) }
        backendPassword?.let { PasswordManager.setBackendPassword(it) }
        backendPass?.let { PasswordManager.setBackendPass(it) }
    }

    /**
     * 检查 restic 密码是否已设置。
     */
    fun hasResticPassword(): Boolean {
        return PasswordManager.hasResticPassword()
    }

    /**
     * 清除所有存储的凭据。
     */
    fun clearAll() {
        PasswordManager.clearAll()
    }

    /**
     * 迁移旧版配置文件中的密码到 PasswordManager。
     *
     * 条件：
     * - PasswordManager 中尚未设置密码
     * - 配置文件中有有效密码（不是 "stored-in-keystore" 占位符）
     */
    private fun migrateLegacyPasswords(
        config: BackupConfig,
        currentResticPassword: String,
        currentBackendPass: String,
    ) {
        // 迁移 restic 密码
        if (currentResticPassword.isNotEmpty() &&
            !PasswordManager.hasResticPassword() &&
            currentResticPassword != "stored-in-keystore"
        ) {
            PasswordManager.setResticPassword(currentResticPassword)
        }

        // 迁移后端密码
        val backendPass = config.resticBackendPass
        if (backendPass.isNotEmpty() &&
            PasswordManager.getBackendPass() == null &&
            backendPass != "stored-in-keystore"
        ) {
            PasswordManager.setBackendPass(backendPass)
        }
    }
}
