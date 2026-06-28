package com.example.androidbackupgui.backup.core

import android.util.Log
import org.json.JSONException
import org.json.JSONObject

/**
 * 应用元数据 JSON 解析器。
 *
 * 重构背景：[com.example.androidbackupgui.backup.restic.ResticWrapper.parseAppDetailsJson]
 * 原本是 restic 包装类的成员函数，但函数体只做纯 JSON 解析（不涉及 restic 二进制、配置、状态），
 * 与 restic 业务逻辑没有直接关系。外部调用方（如 [com.example.androidbackupgui.ui.RestoreViewModel]）
 * 不得不持有 [com.example.androidbackupgui.backup.restic.ResticWrapper] 单例才能调用一个
 * JSON 解析函数，造成不必要的耦合。
 *
 * 解耦后：本工具类完全独立于 restic（无任何 import 指向 restic 包）。调用方无需 ResticWrapper
 * 实例即可解析应用元数据 JSON。返回类型使用同包下的 [SnapshotAppInfo]，彻底切断 core → restic
 * 的反向依赖。
 *
 * 设计原则：
 *  - 顶层 object，无需实例化
 *  - 解析失败返回空 Map（与原行为一致，不抛异常）
 *  - 完全独立：core 包不依赖 restic 包
 */
object AppDetailsParser {
    private const val TAG = "AppDetailsParser"

    /**
     * 解析 [app_details.json] 内容为 package-name → [SnapshotAppInfo] 映射。
     *
     * 解析失败（JSONException）时返回空 Map，并记录 warn 日志，不抛异常。
     *
     * @param jsonStr 完整的 [app_details.json] 内容
     * @return 解析成功的条目映射；失败或空内容时为空 Map
     */
    fun parse(jsonStr: String): Map<String, SnapshotAppInfo> {
        val map = mutableMapOf<String, SnapshotAppInfo>()
        try {
            val root = JSONObject(jsonStr)
            for (key in root.keys()) {
                val entry = root.optJSONObject(key) ?: continue
                val sizes = mutableListOf<Long>()
                val sizesArr = entry.optJSONArray("apkSizes")
                if (sizesArr != null) {
                    for (i in 0 until sizesArr.length()) {
                        sizes.add(sizesArr.optLong(i, 0L))
                    }
                }
                map[key] =
                    SnapshotAppInfo(
                        label = entry.optString("label", key),
                        isSystem = entry.optBoolean("isSystem", false),
                        apkSizes = sizes,
                    )
            }
        } catch (e: JSONException) {
            Log.w(TAG, "parse: failed to parse JSON: ${e.message}")
        }
        return map
    }
}
