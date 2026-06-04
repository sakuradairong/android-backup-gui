package com.example.androidbackupgui.backup

import kotlinx.serialization.Serializable

/**
 * 类型安全的包名包装。
 *
 * 使用 [value] 获取原始字符串，用于 Android API 调用和 shell 命令。
 */
@JvmInline
@Serializable
value class PackageName(val value: String) {
    init {
        require(value.isNotBlank()) { "PackageName must not be blank" }
    }
    override fun toString(): String = value
}

/**
 * 类型安全的用户 ID 包装。
 *
 * 使用 [value] 获取原始整数值。默认值 0 表示主用户 (Owner)。
 */
@JvmInline
@Serializable
value class UserId(val value: Int) {
    init {
        require(value >= 0) { "UserId must be non-negative, got $value" }
    }
    override fun toString(): String = value.toString()

    companion object {
        val Owner = UserId(0)
    }
}
