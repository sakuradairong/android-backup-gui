package com.example.androidbackupgui.backup

import com.example.androidbackupgui.root.RootShell
import com.example.androidbackupgui.root.shellEscape
import java.io.File

/**
 * 归档安全检查 - 验证 tar 归档在提取前不包含路径遍历或越界符号链接。
 *
 * 抽出动机：原 RestoreOperation.isArchiveSafe 包含两件事：
 * 1. 调用 tar tf 解压目录列表
 * 2. 应用白名单规则验证每个条目
 *
 * 独立化后允许单元测试独立覆盖"路径白名单"逻辑（无需构造真实 tar 归档），
 * 也使调用方（restoreData/restoreObb/restoreExternalData）共享同一份白名单规则。
 */
object RestoreArchiveSafety {

    /**
     * 内置允许的路径前缀。无论调用方传入什么额外白名单，这两个前缀始终允许。
     * - /data/data/    : 标准应用数据
     * - /data/user_de/ : 设备加密用户数据（Android 10+）
     */
    val BUILTIN_ALLOWED_PREFIXES: List<String> = listOf(
        "/data/data/",
        "/data/user_de/",
    )

    /**
     * Check that a tar archive contains no path traversal (..) entries
     * or symbolic links pointing outside the tree.
     * Accepts both absolute and relative paths — tar implementations vary.
     *
     * @param additionalAllowedPrefixes extra absolute path prefixes that are
     *        considered safe for the caller's context (e.g. OBB, external data).
     *        The built-in app data prefixes are always allowed.
     */
    suspend fun isArchiveSafe(
        archive: File,
        zstdCmd: String = "zstd",
        additionalAllowedPrefixes: List<String> = emptyList(),
    ): Boolean {
        val listCmd =
            if (archive.name.endsWith(".zst")) {
                "set -o pipefail; $zstdCmd -d -c '${archive.absolutePath.shellEscape()}' | tar tf - 2>/dev/null"
            } else {
                "tar tf '${archive.absolutePath.shellEscape()}' 2>/dev/null"
            }
        var result = RootShell.exec(listCmd)
        // Fallback: try without pipefail (some Android shells don't support it)
        if (!result.isSuccess && archive.name.endsWith(".zst")) {
            val fallbackCmd = "$zstdCmd -d -c '${archive.absolutePath.shellEscape()}' 2>/dev/null | tar tf - 2>/dev/null"
            result = RootShell.exec(fallbackCmd)
        }
        if (!result.isSuccess) return false
        return !result.output.lines().any { line ->
            val parts = line.split(" -> ", limit = 2)
            val rawPath = parts[0]
            val path = rawPath.trimStart('/')
            val linkTarget = parts.getOrNull(1)

            // 1. 拒绝绝对路径（以 / 开头）——防止 tar -C / 写入系统文件
            //    但允许内置的 app data 前缀和调用方指定的额外前缀。
            if (rawPath.startsWith("/") && !isPathAllowed(rawPath, additionalAllowedPrefixes)) {
                return@any true
            }

            // 2. 拒绝路径遍历
            if (path.split("/").any { it == ".." }) return@any true

            // 3. 拒绝以 ./ 开头的路径（某些 tar 变体会将其解释为相对路径穿越）
            if (rawPath.startsWith("./")) return@any true

            // 4. 拒绝符号链接指向绝对路径或含 .. 的目标
            if (linkTarget != null) {
                if (linkTarget.startsWith("/")) return@any true
                if (linkTarget.split("/").any { it == ".." }) return@any true
            }
            false
        }
    }

    /**
     * 检查绝对路径是否在允许的提取白名单内。
     * 内置允许 /data/data/、/data/user_de/，调用方可传入额外前缀。
     */
    fun isPathAllowed(
        rawPath: String,
        additionalAllowedPrefixes: List<String>,
    ): Boolean {
        return (BUILTIN_ALLOWED_PREFIXES + additionalAllowedPrefixes).any { prefix ->
            rawPath == prefix.dropLast(1) || rawPath.startsWith(prefix)
        }
    }
}
