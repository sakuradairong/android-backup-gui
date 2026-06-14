package com.example.androidbackupgui.backup.core

import kotlinx.serialization.Serializable

/**
 * 类型安全的包名包装。
 *
 * 使用 [value] 获取原始字符串，用于 Android API 调用和 shell 命令。
 *
 * 构造函数验证包名格式符合 Android 命名规范（字母开头、包含至少一个点、
 * 仅包含字母数字下划线连字符和点），以防止注入攻击和防止 shell 转义绕过。
 *
 * 如果包名来源不可信，请使用 [PackageName.safe] 安全创建。
 */
@JvmInline
@Serializable
value class PackageName(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "PackageName must not be blank" }
        require(PACKAGE_NAME_REGEX.matches(value)) {
            "Invalid Android package name: '$value' - must start with a letter, " +
                "contain at least one dot, and only [a-zA-Z0-9_-] characters (dot only as separator)"
        }
    }

    override fun toString(): String = value

    companion object {
        /**
         * Android 包名正则：字母开头、至少一个点、仅允许标准字符。
         * 此正则与 [restoreSsaid] 中的校验一致。
         */
        private val PACKAGE_NAME_REGEX =
            Regex(
                "^[a-zA-Z][a-zA-Z0-9_-]*(\\.[a-zA-Z][a-zA-Z0-9_-]*)+" +
                    "$",
            )

        /**
         * 安全创建 [PackageName]，如果包名无效则返回 null。
         * 适用于外部输入（appList.txt、扫描结果等）的防御性校验。
         */
        fun safe(value: String): PackageName? = if (value.isNotBlank() && PACKAGE_NAME_REGEX.matches(value)) PackageName(value) else null
    }
}

/**
 * 类型安全的用户 ID 包装。
 *
 * 使用 [value] 获取原始整数值。默认值 0 表示主用户 (Owner)。
 */
@JvmInline
@Serializable
value class UserId(
    val value: Int,
) {
    init {
        require(value >= 0) { "UserId must be non-negative, got $value" }
    }

    override fun toString(): String = value.toString()

    companion object {
        val Owner = UserId(0)
    }
}
