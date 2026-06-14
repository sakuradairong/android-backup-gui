package com.example.androidbackupgui.backup.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 安全密码管理器。
 *
 * 使用 Android EncryptedSharedPreferences + AES256 加密存储敏感凭据，
 * 包括 restic 仓库密码和远端后端密码。
 *
 * 构造后应尽早调用 [init] 完成初始化。
 */
object PasswordManager {

    private const val PREF_NAME = "secure_credentials"
    private const val KEY_RESTIC_PASSWORD = "restic_password"
    private const val KEY_BACKEND_PASSWORD = "backend_password"
    private const val KEY_BACKEND_PASS = "backend_pass"

    @Volatile
    private var prefs: SharedPreferences? = null

    /**
     * 初始化加密存储。需要在应用启动时（Application.onCreate 或
     * MainActivity.onCreate）尽早调用。
     */
    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            prefs = EncryptedSharedPreferences.create(
                context,
                PREF_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    // ── Restic 仓库密码 ───────────────────────────────

    /** 获取加密存储的 restic 仓库密码。没有设置时返回 null。 */
    fun getResticPassword(): String? = prefs?.getString(KEY_RESTIC_PASSWORD, null)

    /** 加密保存 restic 仓库密码。传入 null 可清除。 */
    fun setResticPassword(password: String?) {
        if (password == null) {
            prefs?.edit()?.remove(KEY_RESTIC_PASSWORD)?.apply()
        } else {
            prefs?.edit()?.putString(KEY_RESTIC_PASSWORD, password)?.apply()
        }
    }

    // ── 远端后端密码 ─────────────────────────────────

    /** 获取加密存储的远端后端密码（WebDAV/SMB）。 */
    fun getBackendPassword(): String? = prefs?.getString(KEY_BACKEND_PASSWORD, null)

    /** 加密保存远端后端密码。 */
    fun setBackendPassword(password: String?) {
        if (password == null) {
            prefs?.edit()?.remove(KEY_BACKEND_PASSWORD)?.apply()
        } else {
            prefs?.edit()?.putString(KEY_BACKEND_PASSWORD, password)?.apply()
        }
    }

    /** 获取加密存储的远端后端 passphrase（SMB share）。 */
    fun getBackendPass(): String? = prefs?.getString(KEY_BACKEND_PASS, null)

    /** 加密保存远端后端 passphrase。 */
    fun setBackendPass(pass: String?) {
        if (pass == null) {
            prefs?.edit()?.remove(KEY_BACKEND_PASS)?.apply()
        } else {
            prefs?.edit()?.putString(KEY_BACKEND_PASS, pass)?.apply()
        }
    }

    // ── 状态检查 ─────────────────────────────────────

    /** 检查密码管理器是否已初始化。 */
    fun isInitialized(): Boolean = prefs != null

    /** 检查 restic 密码是否已设置。 */
    fun hasResticPassword(): Boolean = getResticPassword() != null

    /** 清除所有存储的凭据。 */
    fun clearAll() {
        prefs?.edit()?.clear()?.apply()
    }
}
