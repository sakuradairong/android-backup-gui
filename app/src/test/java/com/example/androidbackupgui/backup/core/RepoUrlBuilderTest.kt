package com.example.androidbackupgui.backup.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * 单元测试 - 验证 [RepoUrlBuilder] 的 URL 拼接行为。
 *
 * 重构背景：[RepoUrlBuilder] 从 [com.example.androidbackupgui.backup.restic.ResticWrapper]
 * 中提取，使纯 URL 拼接逻辑独立于 restic 配置状态。调用方（如
 * [com.example.androidbackupgui.ui.ConfigViewModel]）不再需要持有 `defaultResticWrapper`
 * 单例引用即可生成显示用的仓库 URL。
 *
 * 本测试覆盖：
 *  - 所有支持的 backend 类型（local / rest-server / webdav / smb）
 *  - 未知 backend 回退行为
 *  - backendUrl 尾部 `/` 自动去除
 *  - 空 repoPath / 空 backendUrl 边界
 */
class RepoUrlBuilderTest : FunSpec({

    context("local backend") {

        test("local 后端直接返回 repoPath") {
            RepoUrlBuilder.build("local", "/data/backup", "") shouldBe "/data/backup"
        }

        test("local 后端忽略 backendUrl") {
            RepoUrlBuilder.build("local", "/data/backup", "https://ignored.example.com") shouldBe "/data/backup"
        }
    }

    context("rest-server backend") {

        test("rest-server 拼接 rest:// 前缀（repoPath 无前导 /）") {
            RepoUrlBuilder.build("rest-server", "repo", "http://localhost:8000") shouldBe "rest:http://localhost:8000/repo"
        }

        test("rest-server 自动去除 backendUrl 尾部 /（repoPath 无前导 /）") {
            RepoUrlBuilder.build("rest-server", "repo", "http://localhost:8000/") shouldBe "rest:http://localhost:8000/repo"
        }

        test("rest-server 自动去除 backendUrl 多重尾部 /") {
            RepoUrlBuilder.build("rest-server", "repo", "http://localhost:8000///") shouldBe "rest:http://localhost:8000/repo"
        }

        test("rest-server 保留行为：当 repoPath 含前导 / 时结果有 //（与 ResticEnvResolver 原行为一致）") {
            // 记录当前行为：ResticEnvResolver 原始实现不做规范化，保留 //。
            // 此处仅做快照，未来若改为规范化路径需更新此测试。
            RepoUrlBuilder.build("rest-server", "/repo", "http://localhost:8000") shouldBe "rest:http://localhost:8000//repo"
        }
    }

    context("webdav backend") {

        test("webdav 拼接 backendUrl + repoPath") {
            RepoUrlBuilder.build("webdav", "backup", "https://webdav.example.com") shouldBe "https://webdav.example.com/backup"
        }

        test("webdav 自动去除 backendUrl 尾部 /") {
            RepoUrlBuilder.build("webdav", "backup", "https://webdav.example.com/") shouldBe "https://webdav.example.com/backup"
        }

        test("webdav 带端口号") {
            RepoUrlBuilder.build("webdav", "repo", "https://webdav.example.com:8443") shouldBe "https://webdav.example.com:8443/repo"
        }
    }

    context("smb backend") {

        test("smb 拼接 smb:// 前缀") {
            RepoUrlBuilder.build("smb", "backups", "192.168.1.100") shouldBe "smb:192.168.1.100/backups"
        }

        test("smb 带端口号") {
            RepoUrlBuilder.build("smb", "backups", "192.168.1.100:445") shouldBe "smb:192.168.1.100:445/backups"
        }

        test("smb 自动去除 backendUrl 尾部 /") {
            RepoUrlBuilder.build("smb", "backups", "192.168.1.100:445/") shouldBe "smb:192.168.1.100:445/backups"
        }
    }

    context("边界与未知 backend") {

        test("未知 backend 回退为 repoPath") {
            RepoUrlBuilder.build("unknown-backend", "/repo", "https://example.com") shouldBe "/repo"
        }

        test("空字符串 backend 回退为 repoPath") {
            RepoUrlBuilder.build("", "/repo", "https://example.com") shouldBe "/repo"
        }

        test("空 repoPath 在非 local 后端时仍拼接前缀") {
            RepoUrlBuilder.build("webdav", "", "https://example.com") shouldBe "https://example.com/"
        }

        test("空 backendUrl 在 smb 后端时产生 smb:/repo（保留原行为：trimEnd 后空字符串仍拼接 /）") {
            // 记录当前行为：trimEnd('/')("") 返回 ""，随后拼接 "/$repoPath" 产生前导 /。
            RepoUrlBuilder.build("smb", "repo", "") shouldBe "smb:/repo"
        }
    }
})
