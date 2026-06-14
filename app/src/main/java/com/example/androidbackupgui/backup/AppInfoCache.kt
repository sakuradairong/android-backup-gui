package com.example.androidbackupgui.backup

import com.example.androidbackupgui.root.RootShell
import com.example.androidbackupgui.root.shellEscape
import java.util.concurrent.ConcurrentHashMap

/**
 * 应用信息缓存 - 消除重复的 dumpsys package 和 pm path 调用。
 *
 * 在单次备份会话中缓存每个包的元数据（版本、APK 路径、UID 等），
 * 避免在备份每个应用时重复查询相同信息。
 *
 * 线程安全：使用 ConcurrentHashMap，支持 Semaphore(3) 并发访问。
 */
class AppInfoCache {

    data class PackageMeta(
        val versionCode: String?,
        val apkPaths: List<String>,
        val uid: Int?,
        val hasKeystore: Boolean?,
    )

    private val cache = ConcurrentHashMap<String, PackageMeta>()

    /**
     * 预热缓存 - 批量查询所有应用的信息。
     *
     * 使用 pm list packages -U 单次调用获取所有 UID，
     * 然后为每个包查询版本和 APK 路径。
     */
    suspend fun warmAll(packages: List<String>) {
        // 1. 批量获取所有 UID
        val uidMap = batchGetUids(packages)

        // 2. 为每个包查询版本和 APK 路径
        for (pkg in packages) {
            val versionCode = getVersionCodeDirect(pkg)
            val apkPaths = getApkPathsDirect(pkg)
            val uid = uidMap[pkg]
            val hasKeystore = checkHasKeystore(pkg, uid)

            cache[pkg] = PackageMeta(
                versionCode = versionCode,
                apkPaths = apkPaths,
                uid = uid,
                hasKeystore = hasKeystore,
            )
        }
    }

    /**
     * 获取应用版本号。
     */
    suspend fun getVersionCode(pkg: String): String? {
        return cache[pkg]?.versionCode ?: getVersionCodeDirect(pkg)
    }

    /**
     * 获取 APK 路径列表。
     */
    suspend fun getApkPaths(pkg: String): List<String> {
        return cache[pkg]?.apkPaths ?: getApkPathsDirect(pkg)
    }

    /**
     * 获取应用 UID。
     */
    suspend fun getUid(pkg: String): Int? {
        return cache[pkg]?.uid
    }

    /**
     * 检查是否有 keystore。
     */
    suspend fun hasKeystore(pkg: String): Boolean? {
        return cache[pkg]?.hasKeystore
    }

    /**
     * 使指定包的缓存失效。
     */
    fun invalidate(pkg: String) {
        cache.remove(pkg)
    }

    /**
     * 清空所有缓存。
     */
    fun clear() {
        cache.clear()
    }

    /**
     * 获取缓存的包数量。
     */
    fun size(): Int {
        return cache.size
    }

    // ── 内部实现 ─────────────────────────────────────

    /**
     * 批量获取所有包的 UID。
     *
     * 使用 pm list packages -U 单次调用，比每个包单独查询快得多。
     */
    private suspend fun batchGetUids(packages: List<String>): Map<String, Int> {
        val result = RootShell.exec("pm list packages -U 2>/dev/null")
        if (!result.isSuccess) return emptyMap()

        val uidMap = mutableMapOf<String, Int>()
        val packageSet = packages.toSet()

        result.output.lines().forEach { line ->
            // 格式: package:com.example.app uid:12345
            if (line.startsWith("package:") && line.contains("uid:")) {
                val pkg = line.substringAfter("package:").substringBefore(" ")
                val uid = line.substringAfter("uid:").trim().toIntOrNull()

                if (pkg in packageSet && uid != null) {
                    uidMap[pkg] = uid
                }
            }
        }

        return uidMap
    }

    /**
     * 直接查询应用版本号（不使用缓存）。
     */
    private suspend fun getVersionCodeDirect(pkg: String): String? {
        val result = RootShell.exec(
            "dumpsys package '${pkg.shellEscape()}' | grep versionCode | head -1"
        )
        if (!result.isSuccess) return null

        return result.output
            .substringAfter("versionCode=")
            .substringBefore(" ")
            .filter { it.isDigit() }
            .takeIf { it.isNotEmpty() }
    }

    /**
     * 直接查询 APK 路径（不使用缓存）。
     */
    private suspend fun getApkPathsDirect(pkg: String): List<String> {
        val result = RootShell.exec("pm path '${pkg.shellEscape()}'")
        if (!result.isSuccess) return emptyList()

        return result.output.lines()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:") }
    }

    /**
     * 检查应用是否有 keystore 条目。
     */
    private suspend fun checkHasKeystore(pkg: String, uid: Int?): Boolean? {
        if (uid == null) return null

        val result = RootShell.exec("su $uid -c 'keystore_cli_v2 list' 2>/dev/null")
        if (!result.isSuccess) return null

        return result.output.isNotBlank()
    }
}
