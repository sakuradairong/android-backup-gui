package com.example.androidbackupgui.backup

import android.util.Log
import com.example.androidbackupgui.backup.core.LogUtil
import com.example.androidbackupgui.root.RootShell
import com.example.androidbackupgui.root.shellEscape
import kotlinx.coroutines.delay
import java.io.File

/**
 * APK 安装器 - 处理 pm install 的安装、重试与安装验证。
 *
 * 抽出动机：原 RestoreOperation.installApk 内部有：
 * 1. 复制 APK 到 cacheDir（pm 在某些 ROM 上无法直接读 external storage）
 * 2. 处理 split APK（多 APK 安装 session）
 * 3. 安装后 4 秒轮询 pm list packages
 * 4. 失败重试
 *
 * 独立化后可以单独测试安装逻辑（mock RootShell.exec），也方便将来支持
 * 其他 APK 源（如直接从 restic 快照 dump 出 APK 再安装）。
 */
object RestoreApkInstaller {
    private const val TAG = "RestoreApkInstaller"

    /**
     * Copy APKs to cache dir and run pm install.
     *
     * @return true on successful install (verified by `pm list packages`).
     */
    suspend fun installApk(
        packageName: String,
        appDir: File,
        cacheDir: File,
    ): Boolean {
        val apkNames = BackupFileIO.listBackupFiles(appDir)
        LogUtil.i(TAG, "installApk: $packageName listBackupFiles returned ${apkNames?.size} files: $apkNames")
        if (apkNames == null) {
            LogUtil.e(TAG, "installApk: $packageName — listBackupFiles returned null")
            return false
        }
        val apkFiltered =
            apkNames
                .filter { it.endsWith(".apk") && !it.contains('/') && !it.contains('\\') && it != "." && it != ".." }
                .sorted()
        LogUtil.i(TAG, "installApk: $packageName apkFiltered=$apkFiltered")
        if (apkFiltered.isEmpty()) return false

        // Copy APK files to cache dir (pm cannot read APKs from external storage on some ROMs)
        val installDir = File(cacheDir, "apk_install_${packageName.replace('.', '_')}")
        installDir.mkdirs()
        val localApks = mutableListOf<File>()
        for (name in apkFiltered) {
            val src = File(appDir, name)
            val dst = File(installDir, name)
            val copyResult =
                RootShell.exec(
                    "cp '${src.absolutePath.shellEscape()}' '${dst.absolutePath.shellEscape()}' && chmod 644 '${dst.absolutePath.shellEscape()}'",
                )
            if (copyResult.isSuccess && BackupFileIO.backupPathExists(dst) && BackupFileIO.backupFileSize(dst) > 0L) {
                localApks.add(dst)
            } else {
                Log.w(TAG, "installApk: failed to copy APK $name, skipping")
            }
        }

        suspend fun doInstall(): Boolean {
            val apkPaths = localApks.joinToString(" ") { "'${it.absolutePath.shellEscape()}'" }
            if (localApks.size > 1) {
                val result = RootShell.exec("pm install-create -r -t 2>/dev/null")
                val sessionId =
                    result.output
                        .lines()
                        .firstOrNull { it.contains("Success") }
                        ?.substringAfter("[")
                        ?.substringBefore("]")
                if (sessionId != null) {
                    for ((i, apk) in localApks.withIndex()) {
                        val sessionName = if (i == 0) "base.apk" else "split_$i.apk"
                        RootShell.exec("pm install-write '${sessionId.shellEscape()}' '$sessionName' '${apk.absolutePath.shellEscape()}'")
                    }
                    val commit = RootShell.exec("pm install-commit '${sessionId.shellEscape()}'")
                    return commit.isSuccess
                }
            }
            val result = RootShell.exec("pm install -r -t $apkPaths")
            LogUtil.i(TAG, "installApk: $packageName pm install exitCode=${result.exitCode} output=${result.output.take(200)}")
            return result.isSuccess
        }

        suspend fun isInstalled(): Boolean {
            val verifyResult = RootShell.exec("pm list packages '${packageName.shellEscape()}' 2>/dev/null")
            return verifyResult.output.contains(packageName)
        }

        // First install attempt
        val firstOk = doInstall()
        if (!firstOk) {
            LogUtil.e(TAG, "installApk: $packageName — first install attempt failed")
            return false
        }

        // Verify installation succeeded
        if (isInstalled()) {
            Log.i(TAG, "installApk: $packageName installed and verified")
            return true
        }

        // pm list packages may lag behind pm install; poll before retrying
        Log.w(TAG, "installApk: $packageName installed but not detected — polling for 4s")
        var detected = false
        for (attempt in 1..4) {
            delay(1000)
            if (isInstalled()) {
                detected = true
                Log.i(TAG, "installApk: $packageName detected after ${attempt}s")
                break
            }
        }

        if (detected) return true

        Log.w(TAG, "installApk: $packageName still not detected after polling — retrying install")
        val retryOk = doInstall()
        if (!retryOk) {
            Log.e(TAG, "installApk: $packageName — retry install failed")
            return false
        }

        if (isInstalled()) {
            Log.i(TAG, "installApk: $packageName installed and verified (after retry)")
            return true
        }

        Log.e(TAG, "installApk: $packageName — install reported success but package not found after retry")
        return false
    }
}
