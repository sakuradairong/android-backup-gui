package com.example.androidbackupgui.backup.core

import com.example.androidbackupgui.backup.AppInfo
import com.example.androidbackupgui.backup.AppInfoCache
import com.example.androidbackupgui.backup.scan.AppScanner
import com.example.androidbackupgui.root.RootShell
import com.example.androidbackupgui.root.shellEscape
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * app_details.json 构建器。
 *
 * 重构背景：[com.example.androidbackupgui.backup.BackupOperation.buildAppDetailsJson]
 * 原本是 `BackupOperation`（god class，524 行）的内部函数，但被
 * `backup.restic.ResticStreamBackup`（restic 子模块）通过 `BackupOperation.buildAppDetailsJson(...)` 跨包调用——这是反方向的耦合：`restic` 不应依赖 `backup`（god class）。
 *
 * 解耦后：本工具类独立于 `BackupOperation`，`ResticStreamBackup` 与 `BackupOperation`
 * 都通过 `core` 包调用，消除 `restic → backup` 的反向依赖。
 *
 * 设计原则：
 *  - 顶层 object，无需实例化
 *  - 与原函数行为完全一致（grep `BackupOperation.buildAppDetailsJson` 已无代码调用）
 *  - 接受必要的运行时依赖（RootShell / AppScanner / AppInfoCache），但集中在 core 包
 */
object AppDetailsBuilder {

    /**
     * 构建 [app_details.json] 内容。
     *
     * @param apps 当前备份的应用列表
     * @param legacyApps 从上一次快照继承的应用元数据（增量备份时填充）
     * @param perAppExtra 备份过程中收集的 per-app 额外数据（如 ssaid/permissions/sizes）
     * @param cache APK 版本码与路径缓存（为 null 时回退到 RootShell 直接查询）
     * @return 格式化（2 空格缩进）的 JSON 字符串
     */
    suspend fun buildAppDetailsJson(
        apps: List<AppInfo>,
        legacyApps: Map<String, SnapshotAppInfo>? = null,
        perAppExtra: Map<String, PerAppExtra>? = null,
        cache: AppInfoCache? = null,
    ): String {
        val root = JSONObject()
        val now = SimpleDateFormat("yyyy.MM.dd HH:mm:ss", Locale.US).format(Date())
        for (app in apps) {
            val entry = JSONObject()
            entry.put("label", app.label)
            entry.put("isSystem", app.isSystem)
            entry.put("PackageName", app.packageName.value)

            // APK versionCode for incremental skip - 使用缓存
            val apkVersion = cache?.getVersionCode(app.packageName.value) ?: run {
                // 回退到直接查询
                val versionResult = RootShell.exec(
                    "dumpsys package '${app.packageName.value.shellEscape()}' | grep versionCode | head -1",
                )
                versionResult.output
                    .substringAfter("versionCode=")
                    .substringBefore(" ")
                    .filter { it.isDigit() }
                    .takeIf { it.isNotEmpty() }
            }
            if (apkVersion != null) entry.put("apk_version", apkVersion)

            // APK file sizes - 使用缓存
            val paths = cache?.getApkPaths(app.packageName.value)
                ?: AppScanner.getApkPaths(app.packageName.value)
            val sizes =
                paths.map { path ->
                    val result = RootShell.exec("stat -c%s '${path.shellEscape()}'")
                    if (result.isSuccess) result.output.trim().toLongOrNull() ?: 0L else 0L
                }
            entry.put("apkSizes", JSONArray(sizes))

            // Per-app extra data collected during backup
            val extra = perAppExtra?.get(app.packageName.value)
            if (extra != null) {
                if (extra.ssaid != null) entry.put("Ssaid", extra.ssaid)
                if (extra.permissions != null) entry.put("permissions", extra.permissions)
                if (extra.keystore) entry.put("keystore", "true")

                fun putSize(
                    key: String,
                    value: Long?,
                ) {
                    if (value != null) {
                        val obj = JSONObject()
                        obj.put("Size", value.toString())
                        entry.put(key, obj)
                    }
                }
                putSize("user", extra.userSize)
                putSize("user_de", extra.userDeSize)
                putSize("data", extra.dataSize)
                putSize("obb", extra.obbSize)
            }

            val timeObj = JSONObject()
            timeObj.put("date", now)
            entry.put("Backup time", timeObj)

            root.put(app.packageName.value, entry)
        }
        // Legacy apps from previous snapshot
        val legacyMap = legacyApps ?: emptyMap()
        for ((pkg, legacy) in legacyMap) {
            if (!root.has(pkg)) {
                val entry = JSONObject()
                entry.put("label", legacy.label)
                entry.put("isSystem", legacy.isSystem)
                entry.put("apkSizes", JSONArray(legacy.apkSizes))
                root.put(pkg, entry)
            }
        }
        return root.toString(2)
    }

    /**
     * 备份写入阶段收集的 per-app 额外元数据。
     */
    data class PerAppExtra(
        val ssaid: String? = null,
        val permissions: JSONObject? = null,
        val keystore: Boolean = false,
        val userSize: Long? = null,
        val userDeSize: Long? = null,
        val dataSize: Long? = null,
        val obbSize: Long? = null,
    )
}
