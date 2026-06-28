package com.example.androidbackupgui.backup.core

import kotlinx.serialization.Serializable

/**
 * 应用快照元数据（从 restic snapshot 的 `app_details.json` 中读取）。
 *
 * 重构背景：原 [com.example.androidbackupgui.backup.restic.ResticWrapper.SnapshotAppInfo]
 * 是 `ResticWrapper` 的嵌套 data class，但这是一个纯数据结构（label / isSystem /
 * apkSizes），与 restic 业务无关。[AppDetailsParser]（位于 `backup/core/`）需要使用此
 * 类型，原实现导致 `core/` 反向依赖 `restic/`，违反依赖方向。
 *
 * 解耦后：本文件将 `SnapshotAppInfo` 提升为 `core/` 包下的顶层类型。
 * [com.example.androidbackupgui.backup.restic.ResticWrapper] 通过 typealias 引用，
 * 保持对现有调用方的向后兼容（`ResticWrapper.SnapshotAppInfo` 仍可访问）。
 *
 * 设计原则：
 *  - 顶层 data class，无任何 restic 依赖
 *  - @Serializable 支持未来通过 kotlinx-serialization 直接序列化
 *  - 字段含义与原嵌套类一致：label（应用中文名）/ isSystem（是否系统应用）/ apkSizes（APK 大小列表）
 */
@Serializable
data class SnapshotAppInfo(
    val label: String,
    val isSystem: Boolean,
    val apkSizes: List<Long> = emptyList(),
)
