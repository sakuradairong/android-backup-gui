package com.example.androidbackupgui.backup.scan

import com.example.androidbackupgui.root.RootShell
import com.example.androidbackupgui.root.shellEscape

/**
 * SSAID 缓存 - 读取一次 settings_ssaid.xml 文件并缓存。
 *
 * 原实现中，每个应用备份都会读取整个 settings_ssaid.xml 文件，
 * 导致 N 个应用 = N 次完整文件读取。
 *
 * 优化后：在备份开始时读取一次，然后按包名分发 SSAID 值。
 * 对于 100 个应用，节省 99 次 RootShell 调用。
 */
class SsaidCache(userId: String) {

    private val ssaidMap: Map<String, String>

    init {
        // RootShell.exec is suspend; init { } blocks cannot call suspend functions.
        // Use runBlocking to bridge — this class is only constructed during the
        // backup's preheat phase, on a background dispatcher, so blocking here
        // for the duration of one shell exec is acceptable.
        val result = kotlinx.coroutines.runBlocking {
            RootShell.exec(
                "cat '/data/system/users/${userId.shellEscape()}/settings_ssaid.xml' 2>/dev/null"
            )
        }

        ssaidMap = if (result.isSuccess && result.output.isNotBlank()) {
            parseSsaidXml(result.output)
        } else {
            emptyMap()
        }
    }

    /**
     * 获取指定包的 SSAID 值。
     *
     * @param packageName 包名
     * @return SSAID 值，如果未找到则返回 null
     */
    fun getSsaid(packageName: String): String? {
        return ssaidMap[packageName]
    }

    /**
     * 检查缓存是否包含指定包。
     */
    fun hasPackage(packageName: String): Boolean {
        return ssaidMap.containsKey(packageName)
    }

    /**
     * 获取缓存的包数量。
     */
    fun size(): Int {
        return ssaidMap.size
    }

    /**
     * 检查缓存是否为空（可能文件读取失败）。
     */
    fun isEmpty(): Boolean {
        return ssaidMap.isEmpty()
    }

    // ── 内部实现 ─────────────────────────────────────

    /**
     * 解析 settings_ssaid.xml 文件。
     *
     * XML 格式示例：
     * ```xml
     * <settings version="160">
     *   <setting id="1" name="ssaid" value="abc123" package="com.example.app" />
     * </settings>
     * ```
     *
     * 使用正则解析，兼容不同 Android 版本的 XML 格式变化。
     */
    private fun parseSsaidXml(xml: String): Map<String, String> {
        val map = mutableMapOf<String, String>()

        // 正则匹配 package 和 value 属性
        val regex = Regex("""package="([^"]+)".*?value="([^"]+)"""")
        val regex2 = Regex("""value="([^"]+)".*?package="([^"]+)"""")

        xml.lines().forEach { line ->
            val trimmed = line.trim()

            // 尝试第一种格式: package 在 value 前面
            val match1 = regex.find(trimmed)
            if (match1 != null) {
                val (pkg, value) = match1.destructured
                if (pkg.isNotBlank() && value.isNotBlank()) {
                    map[pkg] = value
                    return@forEach
                }
            }

            // 尝试第二种格式: value 在 package 前面
            val match2 = regex2.find(trimmed)
            if (match2 != null) {
                val (value, pkg) = match2.destructured
                if (pkg.isNotBlank() && value.isNotBlank()) {
                    map[pkg] = value
                }
            }
        }

        return map
    }
}
