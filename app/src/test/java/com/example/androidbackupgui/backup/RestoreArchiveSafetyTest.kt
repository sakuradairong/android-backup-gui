package com.example.androidbackupgui.backup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * 单元测试 - 覆盖 [RestoreArchiveSafety.isPathAllowed] 纯函数。
 *
 * 关键性：该函数是 tar 路径遍历防护的核心。如果错误地放行绝对路径
 * (例如 /system/、/etc/)，恶意备份归档可能在恢复时写入系统文件。
 */
class RestoreArchiveSafetyTest : FunSpec({

    context("内置白名单（无需额外前缀）") {
        test("允许 /data/data/ 前缀下的应用数据") {
            RestoreArchiveSafety.isPathAllowed(
                "/data/data/com.example.app/",
                additionalAllowedPrefixes = emptyList(),
            ) shouldBe true
        }

        test("允许 /data/data/ 下的具体子路径") {
            RestoreArchiveSafety.isPathAllowed(
                "/data/data/com.example.app/files/secret.txt",
                additionalAllowedPrefixes = emptyList(),
            ) shouldBe true
        }

        test("允许 /data/user_de/ 前缀") {
            RestoreArchiveSafety.isPathAllowed(
                "/data/user_de/0/com.example.app/databases/db.sqlite",
                additionalAllowedPrefixes = emptyList(),
            ) shouldBe true
        }

        test("拒绝 /data/ 之外的系统路径") {
            val dangerous = listOf(
                "/system/lib/libc.so",
                "/etc/passwd",
                "/sdcard/Download/evil.tar",
                "/storage/emulated/0/Android/data/com.example.app/",
            )
            for (path in dangerous) {
                RestoreArchiveSafety.isPathAllowed(path, emptyList()) shouldBe false
            }
        }

        test("拒绝根级别路径") {
            RestoreArchiveSafety.isPathAllowed("/bin/sh", emptyList()) shouldBe false
            RestoreArchiveSafety.isPathAllowed("/", emptyList()) shouldBe false
        }
    }

    context("额外白名单（OBB / 外部数据）") {
        test("OBB 路径在额外白名单时允许") {
            RestoreArchiveSafety.isPathAllowed(
                "/storage/emulated/0/Android/obb/com.example.app/main.obb",
                additionalAllowedPrefixes = listOf("/storage/emulated/0/Android/obb/"),
            ) shouldBe true
        }

        test("外部数据路径在额外白名单时允许") {
            RestoreArchiveSafety.isPathAllowed(
                "/data/media/0/Android/data/com.example.app/files/large.bin",
                additionalAllowedPrefixes = listOf("/data/media/0/Android/data/"),
            ) shouldBe true
        }

        test("额外的白名单不影响内置白名单") {
            // 即便调用方传入了 OBB 白名单，内置 /data/data 仍应允许
            RestoreArchiveSafety.isPathAllowed(
                "/data/data/com.example.app/files/db",
                additionalAllowedPrefixes = listOf("/storage/emulated/0/Android/obb/"),
            ) shouldBe true
        }

        test("额外白名单之外的路径仍然被拒绝") {
            RestoreArchiveSafety.isPathAllowed(
                "/storage/emulated/0/Pictures/photo.jpg",
                additionalAllowedPrefixes = listOf("/storage/emulated/0/Android/obb/"),
            ) shouldBe false
        }
    }

    context("边界情况") {
        test("空字符串被拒绝") {
            RestoreArchiveSafety.isPathAllowed("", emptyList()) shouldBe false
        }

        test("非绝对路径被拒绝（防御相对路径穿越）") {
            // isPathAllowed 只对绝对路径白名单，调用方应先检测 ..
            // 但相对路径作为 rawPath 也不应通过（白名单前缀不匹配）
            RestoreArchiveSafety.isPathAllowed("./data/data/foo", emptyList()) shouldBe false
        }

        test("前缀相似但非匹配的路径被拒绝") {
            // /data/dataX 攻击向量
            RestoreArchiveSafety.isPathAllowed("/data/dataX/evil", emptyList()) shouldBe false
            // /data/user_deX 攻击向量
            RestoreArchiveSafety.isPathAllowed("/data/user_deX/evil", emptyList()) shouldBe false
        }
    }
})
