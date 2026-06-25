package com.example.androidbackupgui.backup

import android.content.Context
import com.example.androidbackupgui.backup.security.BinaryResolver
import com.example.androidbackupgui.root.RootShell
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.File

/**
 * 验证 [RestoreOperation.restoreApps] 在 OBB / 外部数据恢复失败时正确计数。
 */
class RestoreOperationPartialFailureTest : FunSpec({

    lateinit var tempDir: File
    lateinit var context: Context

    beforeTest {
        tempDir = File(System.getProperty("java.io.tmpdir"), "restore_op_test_${System.nanoTime()}")
        tempDir.mkdirs()
        context = mockk(relaxed = true)

        mockkObject(RestoreAppDataOps)
        mockkObject(RestoreApkInstaller)
        mockkObject(RootShell)
        mockkObject(BinaryResolver)
        mockkObject(ConcurrencyController)

        coEvery { RootShell.exec(any()) } returns RootShell.ShellResult("", "", 0)
        every { ConcurrencyController.calculateOptimalConcurrency(any(), any()) } returns
            ConcurrencyController.ConcurrencyConfig(maxConcurrency = 2, reason = "test")
        coEvery { BinaryResolver.tarPath(any()) } returns "tar"
        coEvery { BinaryResolver.zstdPath(any()) } returns "zstd"
        coEvery { RestoreApkInstaller.installApk(any(), any(), any()) } returns true
        coEvery { RestoreAppDataOps.restoreData(any(), any(), any(), any(), any()) } returns true
        coEvery { RestoreAppDataOps.restoreSsaid(any(), any(), any()) } returns Unit
        coEvery { RestoreAppDataOps.restorePermissions(any(), any()) } returns Unit
        coEvery { RestoreAppDataOps.fixDataOwnership(any(), any(), any()) } returns Unit
    }

    afterTest {
        unmockkObject(RestoreAppDataOps)
        unmockkObject(RestoreApkInstaller)
        unmockkObject(RootShell)
        unmockkObject(BinaryResolver)
        unmockkObject(ConcurrencyController)
        tempDir.deleteRecursively()
    }

    fun setupBackupFor(pkg: String) {
        File(tempDir, "appList.txt").writeText(pkg)
        val appDir = File(tempDir, pkg)
        appDir.mkdirs()
        File(appDir, "$pkg.apk").writeText("fake apk")
    }

    context("partial failures") {
        test("restoreObb 失败时 failCount 递增") {
            val pkg = "com.example.app"
            setupBackupFor(pkg)
            coEvery { RestoreAppDataOps.restoreObb(any(), any(), any(), any(), any()) } returns false
            coEvery { RestoreAppDataOps.restoreExternalData(any(), any(), any(), any(), any()) } returns true

            val result = RestoreOperation.restoreApps(
                context = context,
                backupDir = tempDir,
                userId = "0",
            )

            result.failCount shouldBe 1
            result.successCount shouldBe 1
        }

        test("restoreExternalData 失败时 failCount 递增") {
            val pkg = "com.example.app"
            setupBackupFor(pkg)
            coEvery { RestoreAppDataOps.restoreObb(any(), any(), any(), any(), any()) } returns true
            coEvery { RestoreAppDataOps.restoreExternalData(any(), any(), any(), any(), any()) } returns false

            val result = RestoreOperation.restoreApps(
                context = context,
                backupDir = tempDir,
                userId = "0",
            )

            result.failCount shouldBe 1
            result.successCount shouldBe 1
        }

        test("两者都失败时 failCount 递增 2") {
            val pkg = "com.example.app"
            setupBackupFor(pkg)
            coEvery { RestoreAppDataOps.restoreObb(any(), any(), any(), any(), any()) } returns false
            coEvery { RestoreAppDataOps.restoreExternalData(any(), any(), any(), any(), any()) } returns false

            val result = RestoreOperation.restoreApps(
                context = context,
                backupDir = tempDir,
                userId = "0",
            )

            result.failCount shouldBe 2
            result.successCount shouldBe 1
        }
    }
})
