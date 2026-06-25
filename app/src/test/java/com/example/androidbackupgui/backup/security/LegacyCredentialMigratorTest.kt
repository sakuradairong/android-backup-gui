package com.example.androidbackupgui.backup.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.File
import java.nio.file.Files

/**
 * 单元测试 - 验证 [LegacyCredentialMigrator] 的原子迁移与字段独立替换行为。
 *
 * 关键性：v1.17 阶段 1-3 引入的凭据安全代码存在两个 HIGH 问题：
 *  1. 迁移非原子性：PasswordManager.setPassword 成功但 configFile.writeText 失败
 *     时明文残留。
 *  2. 重写逻辑误覆盖：当 migratedRestic=true 但 migratedBackend=false 时，
 *     旧的实现仍会替换 restic_backend_pass 为占位符，导致 backend 明文丢失。
 *
 * 本测试在不依赖 Android Context/KeyStore 的前提下覆盖修复后的行为：
 *  - PasswordManager 未初始化时不重写配置，避免明文凭据丢失
 *  - 字段独立 replace（按字段名 + 开关）
 *  - 临时文件 + renameTo 原子写入
 */
class LegacyCredentialMigratorTest : FunSpec({

    lateinit var tempDir: File

    beforeTest {
        tempDir = Files.createTempDirectory("legacy_cred_migrator_test").toFile()
    }

    afterTest {
        unmockkObject(PasswordManager)
        tempDir.deleteRecursively()
    }

    // ── HIGH 2 修复：原子写入 + 字段独立 ─────────────

    test("迁移成功后配置文件不含明文密码") {
        mockInitializedPasswordManager()
        val config = writeConfig(tempDir, 
            """
            output_path=/storage/emulated/0/Backup
            restic_password="real-restic-pass"
            restic_backend_pass="real-backend-pass"
            """.trimIndent()
        )

        val result = LegacyCredentialMigrator.migrate(config)

        val rewritten = config.readText()
        (rewritten.contains("real-restic-pass")) shouldBe false
        (rewritten.contains("real-backend-pass")) shouldBe false
        rewritten.contains("stored-in-keystore") shouldBe true
        result.rewroteFile shouldBe true
    }

    // ── HIGH 3 修复：仅迁移 restic 时，backend 明文必须保留 ─────

    test("仅迁移 restic 时，backend 明文必须保留") {
        mockInitializedPasswordManager(backendPass = "already-secure")
        val config = writeConfig(tempDir, 
            """
            restic_password="plain-restic"
            restic_backend_pass="plain-backend"
            """.trimIndent()
        )

        val result = LegacyCredentialMigrator.migrate(config)

        val content = config.readText()
        (content.contains("plain-restic")) shouldBe false
        content.contains("plain-backend") shouldBe true
        content.contains("restic_password=\"stored-in-keystore\"") shouldBe true
        content.contains("restic_backend_pass=\"plain-backend\"") shouldBe true
        result.migratedResticPassword shouldBe true
        result.migratedBackendPass shouldBe false
        result.rewroteFile shouldBe true
    }

    // ── 端到端：两个字段都迁移时被正确 redact ───────────

    test("两个字段都被迁移时都正确 redact 为 stored-in-keystore") {
        mockInitializedPasswordManager()
        val config = writeConfig(tempDir, 
            """
            # legacy config
            output_path=/sdcard/Backup
            restic_password="restic-plain"
            restic_backend_pass="backend-plain"
            """.trimIndent()
        )

        LegacyCredentialMigrator.migrate(config)

        val content = config.readText()
        content.contains("restic-plain") shouldBe false
        content.contains("backend-plain") shouldBe false
        content.contains("restic_password=\"stored-in-keystore\"") shouldBe true
        content.contains("restic_backend_pass=\"stored-in-keystore\"") shouldBe true
        // 非密码字段保持不变
        content.contains("output_path=/sdcard/Backup") shouldBe true
    }

    // ── 端到端：无明文密码时不重写文件 ─────────────

    test("无明文密码时不会重写配置文件") {
        val config = writeConfig(tempDir,
            """
            output_path=/sdcard/Backup
            # 注释里没有密码
            """.trimIndent()
        )

        val result = LegacyCredentialMigrator.migrate(config)

        result.rewroteFile shouldBe false
        // 文件应保持原样
        config.readText().contains("# 注释里没有密码") shouldBe true
    }

    // ── 端到端：占位符不被当作明文处理 ───────────────

    test("stored-in-keystore 占位符不被当作明文重写") {
        val config = writeConfig(tempDir, 
            """
            restic_password="stored-in-keystore"
            restic_backend_pass="stored-in-keystore"
            """.trimIndent()
        )

        // 第一个调用：migrate 看到占位符但 PasswordManager 未初始化，
        // 且 hasResticPassword() 返回 false，会尝试迁移占位符为占位符（无效），
        // migratedRestic 仍为 false（条件 resticPassword != "stored-in-keystore" 失败）。
        val result = LegacyCredentialMigrator.migrate(config)

        result.migratedResticPassword shouldBe false
        result.migratedBackendPass shouldBe false
        result.rewroteFile shouldBe false
        // 原内容不变
        config.readText().contains("stored-in-keystore") shouldBe true
    }

    // ── 端到端：未加引号的明文密码也能正确 redact ────

    test("未加引号的密码也能被正确 redact") {
        mockInitializedPasswordManager()
        val config = writeConfig(tempDir, 
            """
            restic_password=plain-no-quotes
            restic_backend_pass=plain-backend-no-quotes
            """.trimIndent()
        )

        LegacyCredentialMigrator.migrate(config)

        val content = config.readText()
        content.contains("plain-no-quotes") shouldBe false
        content.contains("plain-backend-no-quotes") shouldBe false
        content.contains("restic_password=\"stored-in-keystore\"") shouldBe true
    }

    test("未加引号的 restic 密码 redact 时不能吞掉后续配置行") {
        mockInitializedPasswordManager()
        val config = writeConfig(tempDir,
            """
            restic_password=plain-no-quotes
            restic_backend_pass=plain-backend-no-quotes
            output_path=/sdcard/Backup
            """.trimIndent()
        )

        LegacyCredentialMigrator.migrate(config)

        val content = config.readText()
        content.contains("restic_password=\"stored-in-keystore\"") shouldBe true
        content.contains("restic_backend_pass=\"stored-in-keystore\"") shouldBe true
        content.contains("output_path=/sdcard/Backup") shouldBe true
    }

    test("PasswordManager 未初始化时不会迁移或重写明文配置") {
        val config = writeConfig(tempDir,
            """
            restic_password=plain-restic
            restic_backend_pass=plain-backend
            """.trimIndent()
        )

        val result = LegacyCredentialMigrator.migrate(config)

        result.migratedResticPassword shouldBe false
        result.migratedBackendPass shouldBe false
        result.rewroteFile shouldBe false
        config.readText().contains("plain-restic") shouldBe true
        config.readText().contains("plain-backend") shouldBe true
    }

    // ── 端到端：原子写入不残留临时文件 ───────────────

    test("原子写入后不残留临时文件") {
        mockInitializedPasswordManager()
        val config = writeConfig(tempDir, 
            """
            restic_password="plain"
            restic_backend_pass="plain"
            """.trimIndent()
        )

        LegacyCredentialMigrator.migrate(config)

        val leftovers = config.parentFile?.listFiles { f -> f.name.startsWith(config.name + ".tmp.") }
        (leftovers?.size ?: 0) shouldBe 0
    }

    // ── 端到端：配置文件不存在时安全返回 ─────────────

    test("配置文件不存在时安全返回 (migratedXxx=false, rewroteFile=false)") {
        val nonExistent = File(tempDir, "does_not_exist.conf")

        val result = LegacyCredentialMigrator.migrate(nonExistent)

        result.migratedResticPassword shouldBe false
        result.migratedBackendPass shouldBe false
        result.rewroteFile shouldBe false
        result.error shouldBe null
    }

    // ── Helper ──────────────────────────────────────
}) {
    companion object {
        internal fun writeConfig(parent: File, content: String): File {
            val file = File(parent, "backup_settings.conf")
            file.writeText(content)
            return file
        }

        internal fun mockInitializedPasswordManager(
            resticPassword: String? = null,
            backendPass: String? = null,
        ) {
            var storedResticPassword = resticPassword
            var storedBackendPass = backendPass
            mockkObject(PasswordManager)
            every { PasswordManager.isInitialized() } returns true
            every { PasswordManager.getResticPassword() } answers { storedResticPassword }
            every { PasswordManager.hasResticPassword() } answers { storedResticPassword != null }
            every { PasswordManager.setResticPassword(any()) } answers {
                storedResticPassword = firstArg()
            }
            every { PasswordManager.getBackendPass() } answers { storedBackendPass }
            every { PasswordManager.setBackendPass(any()) } answers {
                storedBackendPass = firstArg()
            }
        }
    }
}
