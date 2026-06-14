package com.example.androidbackupgui.backup.security

import com.example.androidbackupgui.backup.BackupConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * 单元测试 - 验证凭据解析的优先级和占位符检测。
 *
 * 关键性：错误实现可能让配置文件中的"stored-in-keystore"占位符
 * 误作为真实密码使用，或导致 PasswordManager 已设置的密码被覆盖。
 *
 * 注意：本测试不调用 PasswordManager.init()（需要 Android Context），
 * 因此 PasswordManager.getResticPassword() 等会返回 null，
 * 测试的是当 PasswordManager 为空时凭据回退到 config 的逻辑。
 */
class CredentialProviderTest : FunSpec({

    test("PasswordManager 未初始化时回退到 config 中的 resticPassword") {
        val config = BackupConfig(resticPassword = "real-password-123")

        val credentials = CredentialProvider.resolve(config)

        credentials.resticPassword shouldBe "real-password-123"
    }

    test("config 中 resticPassword 为空时使用空字符串") {
        val config = BackupConfig(resticPassword = "")

        val credentials = CredentialProvider.resolve(config)

        credentials.resticPassword shouldBe ""
    }

    test("resticPassword 占位符不应作为真实密码使用") {
        val config = BackupConfig(resticPassword = "stored-in-keystore")

        val credentials = CredentialProvider.resolve(config)

        // 占位符在 PasswordManager 未初始化时应被识别为空
        credentials.resticPassword shouldBe ""
    }

    test("config 中 resticBackendPass 占位符被忽略") {
        val config = BackupConfig(resticBackendPass = "stored-in-keystore")

        val credentials = CredentialProvider.resolve(config)

        credentials.backendPass shouldBe ""
    }

    test("正常的 backend 密码被保留") {
        val config = BackupConfig(resticBackendPass = "secret-backend-pass")

        val credentials = CredentialProvider.resolve(config)

        credentials.backendPass shouldBe "secret-backend-pass"
    }
})
