package com.example.androidbackupgui.backup.core

/**
 * 错误建议工厂 - 为不同类型的错误生成友好的解决建议。
 *
 * 根据错误类型、错误消息和上下文，提供用户友好的错误提示和解决方案。
 */
object ErrorSuggestionFactory {

    /**
     * 为错误生成友好的建议。
     *
     * @param error 错误对象
     * @param context 错误上下文（可选）
     * @return 包含错误消息和建议的 ErrorInfo
     */
    fun createSuggestion(
        error: AppError,
        context: String? = null,
    ): ErrorInfo {
        return when (error) {
            is AppError.Network -> createNetworkSuggestion(error, context)
            is AppError.Shell -> createShellSuggestion(error, context)
            is AppError.Remote -> createRemoteSuggestion(error, context)
            is AppError.LocalIO -> createLocalIOSuggestion(error, context)
            is AppError.Restic -> createResticSuggestion(error, context)
            is AppError.Parse -> createParseSuggestion(error, context)
            is AppError.Cancelled -> ErrorInfo(
                message = "操作被取消",
                suggestion = "用户取消了操作",
                isRetryable = false,
            )
        }
    }

    /**
     * 错误信息。
     */
    data class ErrorInfo(
        val message: String,
        val suggestion: String,
        val isRetryable: Boolean,
        val detailedMessage: String? = null,
    )

    // ── 网络错误建议 ─────────────────────────────────

    private fun createNetworkSuggestion(
        error: AppError.Network,
        context: String?,
    ): ErrorInfo {
        val message = error.message
        val suggestion = when {
            message.contains("timeout", ignoreCase = true) ->
                "网络连接超时。请检查网络连接是否正常，或稍后重试。"
            message.contains("connection refused", ignoreCase = true) ->
                "连接被拒绝。请检查服务器地址和端口是否正确。"
            message.contains("dns", ignoreCase = true) ->
                "DNS 解析失败。请检查网络连接和服务器地址。"
            message.contains("unreachable", ignoreCase = true) ->
                "网络不可达。请检查网络连接。"
            else ->
                "网络错误。请检查网络连接后重试。"
        }

        return ErrorInfo(
            message = message,
            suggestion = suggestion,
            isRetryable = error.retryable,
        )
    }

    // ── Shell 错误建议 ─────────────────────────────────

    private fun createShellSuggestion(
        error: AppError.Shell,
        context: String?,
    ): ErrorInfo {
        val message = error.message
        val command = error.command
        val exitCode = error.exitCode

        val suggestion = when {
            message.contains("Permission denied", ignoreCase = true) ->
                "权限不足。请确保应用已获得 root 权限。"
            message.contains("No such file", ignoreCase = true) ->
                "文件或目录不存在。请检查路径是否正确。"
            message.contains("Disk full", ignoreCase = true) ->
                "磁盘空间不足。请清理存储空间后重试。"
            exitCode == 137 || exitCode == 143 ->
                "进程被系统杀死。可能是内存不足，请关闭其他应用后重试。"
            command.contains("dumpsys") ->
                "系统服务查询失败。请稍后重试。"
            command.contains("pm") ->
                "包管理器命令失败。请检查应用是否已安装。"
            else ->
                "命令执行失败 (exit=$exitCode)。请检查日志获取详细信息。"
        }

        return ErrorInfo(
            message = message,
            suggestion = suggestion,
            isRetryable = false,
            detailedMessage = "命令: $command\n退出码: $exitCode\n错误: ${error.stderr}",
        )
    }

    // ── 远程错误建议 ─────────────────────────────────

    private fun createRemoteSuggestion(
        error: AppError.Remote,
        context: String?,
    ): ErrorInfo {
        val message = error.message
        val phase = error.phase

        val suggestion = when {
            phase == "connecting" ->
                "无法连接到远程服务器。请检查服务器地址、端口和网络连接。"
            phase == "transferring" && message.contains("timeout") ->
                "数据传输超时。请检查网络连接或稍后重试。"
            phase == "transferring" ->
                "数据传输失败。请检查网络连接和存储空间。"
            phase == "list" ->
                "无法列出远程文件。请检查服务器权限和路径。"
            phase == "delete" ->
                "无法删除远程文件。请检查服务器权限。"
            error.isNotFound ->
                "远程文件或目录不存在。请检查路径是否正确。"
            message.contains("authentication", ignoreCase = true) ->
                "认证失败。请检查用户名和密码。"
            message.contains("permission", ignoreCase = true) ->
                "权限不足。请检查服务器权限设置。"
            else ->
                "远程操作失败。请检查服务器配置。"
        }

        return ErrorInfo(
            message = message,
            suggestion = suggestion,
            isRetryable = error.retryable,
        )
    }

    // ── 本地 IO 错误建议 ─────────────────────────────────

    private fun createLocalIOSuggestion(
        error: AppError.LocalIO,
        context: String?,
    ): ErrorInfo {
        val message = error.message
        val path = error.path

        val suggestion = when {
            message.contains("No space left", ignoreCase = true) ->
                "存储空间不足。请清理存储空间后重试。"
            message.contains("Permission denied", ignoreCase = true) ->
                "权限不足。请检查应用存储权限。"
            message.contains("Read-only", ignoreCase = true) ->
                "文件系统只读。请检查存储设备状态。"
            path.contains("/sdcard") || path.contains("/storage") ->
                "外部存储访问失败。请检查存储设备是否已挂载。"
            else ->
                "文件操作失败。请检查文件路径和权限。"
        }

        return ErrorInfo(
            message = message,
            suggestion = suggestion,
            isRetryable = false,
        )
    }

    // ── Restic 错误建议 ─────────────────────────────────

    private fun createResticSuggestion(
        error: AppError.Restic,
        context: String?,
    ): ErrorInfo {
        val message = error.message
        val stderr = error.stderr

        val suggestion = when {
            stderr.contains("password") || stderr.contains("key") ->
                "密码错误或密钥不匹配。请检查 restic 仓库密码。"
            stderr.contains("repository") || stderr.contains("repo") ->
                "仓库不存在或已损坏。请检查仓库路径或重新初始化。"
            stderr.contains("lock") ->
                "仓库被锁定。请先解锁仓库。"
            stderr.contains("permission") || stderr.contains("access") ->
                "权限不足。请检查仓库访问权限。"
            stderr.contains("network") || stderr.contains("connection") ->
                "网络连接失败。请检查网络连接。"
            stderr.contains("disk") || stderr.contains("space") ->
                "磁盘空间不足。请清理存储空间。"
            stderr.contains("timeout") ->
                "操作超时。请检查网络连接或稍后重试。"
            error.exitCode == 1 ->
                "restic 命令执行失败。请检查日志获取详细信息。"
            else ->
                "Restic 操作失败。请检查日志获取详细信息。"
        }

        return ErrorInfo(
            message = message,
            suggestion = suggestion,
            isRetryable = false,
            detailedMessage = "退出码: ${error.exitCode}\n错误: $stderr",
        )
    }

    // ── 解析错误建议 ─────────────────────────────────

    private fun createParseSuggestion(
        error: AppError.Parse,
        context: String?,
    ): ErrorInfo {
        val message = error.message
        val detail = error.detail

        val suggestion = when {
            message.contains("JSON", ignoreCase = true) ->
                "JSON 解析失败。请检查配置文件格式是否正确。"
            message.contains("config", ignoreCase = true) ->
                "配置文件格式错误。请检查配置文件或重新配置。"
            detail.contains("unexpected character") ->
                "配置文件包含非法字符。请检查配置文件。"
            else ->
                "数据解析失败。请检查输入数据格式。"
        }

        return ErrorInfo(
            message = message,
            suggestion = suggestion,
            isRetryable = false,
        )
    }

    /**
     * 格式化错误信息为用户友好的字符串。
     *
     * @param error 错误对象
     * @param context 错误上下文（可选）
     * @return 格式化的错误字符串
     */
    fun formatErrorMessage(
        error: AppError,
        context: String? = null,
    ): String {
        val errorInfo = createSuggestion(error, context)
        return buildString {
            append(errorInfo.message)
            if (errorInfo.suggestion.isNotEmpty()) {
                append("\n建议: ${errorInfo.suggestion}")
            }
            if (errorInfo.detailedMessage != null) {
                append("\n详细信息: ${errorInfo.detailedMessage}")
            }
        }
    }
}
