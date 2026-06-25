package com.example.androidbackupgui.backup.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
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

    private const val TAG = "PasswordManager"
    private const val PREF_NAME = "secure_credentials"
    private const val KEY_RESTIC_PASSWORD = "restic_password"
    private const val KEY_BACKEND_PASSWORD = "backend_password"
    private const val KEY_BACKEND_PASS = "backend_pass"

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var lastInitError: Throwable? = null

    /**
     * 初始化加密存储。需要在应用启动时（Application.onCreate 或
     * MainActivity.onCreate）尽早调用。
     *
     * 不会向上抛异常：若 EncryptedSharedPreferences 不可用（设备未设锁屏、
     * KeyStore 不可用、StrongBox 异常等），异常被捕获并记录到 [lastInitError]，
     * [prefs] 保持 null，调用方可继续运行（后续 getXxx 返回 null、setXxx no-op），
     * 凭据解析会自动回退到 BackupConfig 字段。
     *
     * 调用方可通过 [lastInitError] / [isInitialized] 判定是否真正初始化成功。
     */
    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            try {
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
                lastInitError = null
                Log.i(TAG, "EncryptedSharedPreferences ready (${PREF_NAME})")
            } catch (e: Throwable) {
                // 捕获 Throwable 而非 Exception：EncryptedSharedPreferences.create 内部
                // 可能抛出 java.security.KeyStoreException / GeneralSecurityException /
                // IOException / 罕见的 Error（如 OutOfMemoryError 的子类型）。捕获
                // Throwable 后向上抛出会导致 app 启动崩溃（v1.17 阶段 1-3 引入）。
                // 这里改为吞掉并记录，调用方可通过 isInitialized()/lastInitError 感知。
                lastInitError = e
                prefs = null
                Log.e(TAG, "init: failed to create EncryptedSharedPreferences, falling back to plaintext config", e)
            }
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

    /** 检查密码管理器是否已初始化。失败时返回 false。 */
    fun isInitialized(): Boolean = prefs != null

    /** 返回 init() 上一次的失败原因（成功时为 null）。 */
    fun lastInitError(): Throwable? = lastInitError

    /** 检查 restic 密码是否已设置。 */
    fun hasResticPassword(): Boolean = getResticPassword() != null

    /** 清除所有存储的凭据。 */
    fun clearAll() {
        prefs?.edit()?.clear()?.apply()
    }
}
