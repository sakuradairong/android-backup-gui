package com.example.androidbackupgui.backup.core

/**
 * restic 仓库 URL 构建器。
 *
 * 重构背景：[com.example.androidbackupgui.backup.restic.ResticEnvResolver.buildRepoUrl]
 * 和 [com.example.androidbackupgui.backup.restic.ResticWrapper.buildRepoUrl] 都是纯字符串
 * 拼接函数（不涉及 restic 二进制、配置、状态），但调用方（[com.example.androidbackupgui.ui.ConfigViewModel]）
 * 不得不持有 [com.example.androidbackupgui.backup.restic.defaultResticWrapper] 单例才能调用，
 * 造成"名实不符"的耦合。
 *
 * 解耦后：本工具类独立于 restic 任何状态。调用方无需 ResticWrapper 实例即可拼接
 * 显示用的 URL 字符串。
 *
 * 设计原则：
 *  - 顶层 object，无需实例化
 *  - 支持的后端类型：local / rest-server / webdav / smb
 *  - 未知 backend 回退为 repoPath（与原行为一致）
 *  - backendUrl 尾部 `/` 自动去除（避免双斜杠）
 */
object RepoUrlBuilder {

    /**
     * 根据 backend 类型拼接显示用的仓库 URL。
     *
     * @param backend 后端类型：`local` / `rest-server` / `webdav` / `smb` / 其他
     * @param repoPath 仓库路径（local 后端时直接作为 URL 返回）
     * @param backendUrl 后端地址（rest-server/webdav/smb 时作为 URL 前缀）
     * @return 拼接后的 URL 字符串
     */
    fun build(
        backend: String,
        repoPath: String,
        backendUrl: String,
    ): String =
        when (backend) {
            "local" -> repoPath
            "rest-server" -> "rest:${backendUrl.trimEnd('/')}/$repoPath"
            "webdav" -> "${backendUrl.trimEnd('/')}/$repoPath"
            "smb" -> "smb:${backendUrl.trimEnd('/')}/$repoPath"
            else -> repoPath
        }
}
