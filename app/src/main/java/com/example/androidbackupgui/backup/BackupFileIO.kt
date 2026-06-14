package com.example.androidbackupgui.backup

import android.util.Log
import com.example.androidbackupgui.root.RootShell
import com.example.androidbackupgui.root.shellEscape
import java.io.File

/**
 * 文件 I/O 工具 - 在 RootShell 上提供 Java File 操作的回退路径。
 *
 * 设计动机：FUSE 挂载（如 SD 卡、Termux 用户家目录）上 Java `File.length()`、
 * `File.listFiles()`、`File.exists()` 经常返回 0/null，因为底层驱动不实现 stat。
 * 这些工具先尝试 Java API，失败时回退到 root shell 以获得可靠的结果。
 *
 * 该类原为 BackupOperation 的 internal 工具，因 RestoreOperation、RestoreScreen、
 * ResticStreamBackup 等多个调用方需要而被提取为独立 object 以便复用。
 */
object BackupFileIO {
    private const val TAG = "BackupFileIO"

    /** Create directory, falling back to root shell [mkdir -p]. */
    suspend fun mkdirsForBackup(dir: File): Boolean {
        if (dir.isDirectory) return true
        if (dir.mkdirs()) return true
        val result = RootShell.exec("mkdir -p '${dir.absolutePath.shellEscape()}'")
        return result.isSuccess && dir.isDirectory
    }

    /**
     * Write text to a file, falling back to root shell (base64 + cat) when the
     * Java write fails (typical on FUSE-mounted or read-only file systems).
     */
    suspend fun writeFileForBackup(
        file: File,
        text: String,
    ): Boolean {
        try {
            mkdirsForBackup(file.parentFile ?: return false)
            file.writeText(text)
            return true
        } catch (_: Exception) {
            // fall through to root-shell fallback
        }
        try {
            mkdirsForBackup(file.parentFile ?: return false)
            val b64 = android.util.Base64.encodeToString(text.toByteArray(), android.util.Base64.NO_WRAP)
            val result = RootShell.exec(
                "echo '${b64.shellEscape()}' | base64 -d > '${file.absolutePath.shellEscape()}'",
            )
            return result.isSuccess
        } catch (e: Exception) {
            Log.w(TAG, "writeFileForBackup: all methods failed for ${file.absolutePath}", e)
            return false
        }
    }

    /** Read file content, falling back to root shell [cat]. Returns null on failure. */
    suspend fun readTextFile(file: File): String? {
        try {
            if (file.exists()) return file.readText()
        } catch (_: Exception) {
            // fall through to root-shell fallback
        }
        try {
            val result = RootShell.exec("cat '${file.absolutePath.shellEscape()}' 2>/dev/null")
            if (result.isSuccess && result.output.isNotBlank()) return result.output
        } catch (_: Exception) {
            // fall through
        }
        return null
    }

    /** Check if a path is a directory, falling back to root shell [test -d]. */
    suspend fun backupIsDirectory(dir: File): Boolean {
        if (dir.isDirectory()) return true
        val result = RootShell.exec("test -d '${dir.absolutePath.shellEscape()}' && echo 1 || echo 0")
        return result.output.trim() == "1"
    }

    /** Get file size via root shell [stat] when Java File.length() returns 0 on FUSE. */
    suspend fun backupFileSize(file: File): Long {
        val javaSize = file.length()
        if (javaSize > 0L) return javaSize
        val result = RootShell.exec("stat -c%s '${file.absolutePath.shellEscape()}' 2>/dev/null")
        return result.output.trim().toLongOrNull() ?: 0L
    }

    /** Check if a file/directory exists, falling back to root shell [test -e]. */
    suspend fun backupPathExists(file: File): Boolean {
        if (file.exists()) return true
        val result = RootShell.exec("test -e '${file.absolutePath.shellEscape()}' && echo 1 || echo 0")
        return result.output.trim() == "1"
    }

    /**
     * List immediate children in a directory, falling back to root shell [ls -1].
     * Returns relative names only (not full paths). Returns null on total failure.
     */
    suspend fun listBackupFiles(dir: File): List<String>? {
        try {
            val javaFiles = dir.listFiles()
            if (javaFiles != null) {
                val names = javaFiles.map { it.name }
                if (names.isNotEmpty()) return names
            }
        } catch (_: Exception) {
            // fall through to root-shell fallback
        }
        try {
            val result = RootShell.exec("ls -1 '${dir.absolutePath.shellEscape()}' 2>/dev/null")
            if (!result.isSuccess || result.output.isBlank()) return null
            return result.output.lines().filter { it.isNotBlank() }
        } catch (_: Exception) {
            return null
        }
    }
}
