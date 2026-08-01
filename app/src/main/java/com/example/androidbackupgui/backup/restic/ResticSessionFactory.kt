package com.example.androidbackupgui.backup.restic

import android.content.Context
import com.example.androidbackupgui.backup.security.ResticBinary

/**
 * 解耦层：封装 [ResticWrapper] 实例的配置（binaryPath / cacheDir / backendDomain）。
 *
 * 问题背景：重构前 ViewModel 直接修改全局单例 [defaultResticWrapper] 的
 * 三个可变属性，造成以下耦合：
 * - ViewModel 知道 restic 二进制如何定位（[ResticBinary]）
 * - ViewModel 知道 cache 目录路径解析方式
 * - ViewModel 知道 SMB NTLM 域名来源
 * - 全局可变状态难以测试（不同 ViewModel 之间会相互覆盖设置）
 *
 * 解耦后：ViewModel 仅调用 [prepare] 获取已配置好的 [ResticWrapper]，无需
 * 关心配置细节。测试时可注入返回固定实例的 mock factory。
 *
 * 设计原则：
 * - 输入只接受 [Context] + [backendDomain]，不暴露 [ResticBinary] 等内部依赖
 * - 返回配置就绪的 [ResticWrapper]，调用方可直接调用业务方法（backup/restore 等）
 */
interface ResticSessionFactory {
    /**
     * 根据当前 [context] 与 SMB NTLM [backendDomain] 准备一个配置就绪的
     * [ResticWrapper] 实例。
     *
     * 实现负责：
     * - 定位 restic 二进制路径（[ResticBinary.prepare]）
     * - 设置 cache 目录（context.cacheDir）
     * - 设置 SMB NTLM 域名
     *
     * @return 配置完成的 [ResticWrapper]；若 restic 二进制不可用则返回 `null`，
     *         调用方应中止 restic 相关流程（与 [ResticBinary.prepare] 的契约一致）
     */
    fun prepare(
        context: Context,
        backendDomain: String,
    ): ResticWrapper?
}

/**
 * [ResticSessionFactory] 的默认实现，复用进程级单例 [defaultResticWrapper]。
 *
 * 注意：实现复用全局单例，因此仍存在不同调用方互相覆盖配置的固有耦合。
 * 若未来需要完全隔离，可改为每次 [prepare] 返回新 [ResticWrapper] 实例。
 */
class DefaultResticSessionFactory : ResticSessionFactory {
    override fun prepare(
        context: Context,
        backendDomain: String,
    ): ResticWrapper? {
        val binaryPath = ResticBinary.prepare(context) ?: return null
        defaultResticWrapper.binaryPath = binaryPath
        defaultResticWrapper.cacheDir = context.cacheDir.absolutePath
        defaultResticWrapper.backendDomain = backendDomain
        return defaultResticWrapper
    }
}
