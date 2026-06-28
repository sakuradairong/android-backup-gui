package com.example.androidbackupgui.backup

import android.content.Context
import com.example.androidbackupgui.backup.core.AppDetailsBuilder
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
 * 验证 [BackupOperation.backupApps] 在输出目录非法、APK 路径为空、
 * 数据备份失败时的失败计数和 early return 行为。
 *
 * 这些路径是近期改动引入的核心行为，不依赖真实 root shell 或 restic。
 */
class BackupOperationFailureTest : FunSpec({

    lateinit var tempDir: File
    lateinit var context: Context

    beforeTest {
        tempDir = File(System.getProperty("java.io.tmpdir"), "backup_op_test_${System.nanoTime()}")
        tempDir.mkdirs()
        context = mockk(relaxed = true)

        mockkObject(RootShell)
        mockkObject(BinaryResolver)
        mockkObject(ConcurrencyController)
        mockkObject(BackupAppDataOps)
        mockkObject(AppDetailsBuilder)
        mockkObject(BackupFileIO)
        coEvery { AppDetailsBuilder.buildAppDetailsJson(any(), any(), any(), any()) } returns "{}"
        coEvery { BackupFileIO.backupPathExists(any()) } returns true
        coEvery { BackupFileIO.backupFileSize(any()) } returns 1024L

        coEvery { RootShell.exec(any()) } returns RootShell.ShellResult("", "", 0)
        every { ConcurrencyController.calculateOptimalConcurrency(any(), any()) } returns
            ConcurrencyController.ConcurrencyConfig(maxConcurrency = 2, reason = "test")
        coEvery { BinaryResolver.tarPath(any()) } returns "tar"
        coEvery { BinaryResolver.zstdPath(any()) } returns "zstd"
        coEvery { BackupAppDataOps.backupUserData(any(), any(), any(), any(), any()) } returns (1024L to 0L)
        coEvery { BackupAppDataOps.backupObb(any(), any(), any()) } returns 512L
        coEvery { BackupAppDataOps.backupExternalData(any(), any(), any(), any()) } returns 256L
        coEvery { BackupAppDataOps.backupSsaid(any(), any(), any(), any()) } returns Unit
        coEvery { BackupAppDataOps.backupPermissions(any(), any()) } returns Unit
    }

    afterTest {
        unmockkObject(RootShell)
        unmockkObject(BinaryResolver)
        unmockkObject(ConcurrencyController)
        unmockkObject(BackupAppDataOps)
        unmockkObject(AppDetailsBuilder)
        unmockkObject(BackupFileIO)
        tempDir.deleteRecursively()
    }

    fun appInfo(pkg: String) = AppInfo(packageName = PackageName(pkg))

    context("early failures") {
        test("输出目录包含 /Android/ 时直接返回零结果") {
            val result = BackupOperation.backupApps(
                context = context,
                apps = listOf(appInfo("com.example.app")),
                config = BackupConfig(),
                outputDir = File("/storage/emulated/0/Android/data/test"),
            )

            result.failCount shouldBe 0
            result.successCount shouldBe 0
            result.outputDir shouldBe "/storage/emulated/0/Android/data/test"
        }
    }

    context("per-app failures") {
        test("APK 路径为空时 failCount 递增且跳过该应用") {
            val pkg = "com.example.app"
            val infoCache = mockk<AppInfoCache>(relaxed = true)
            coEvery { infoCache.getApkPaths(pkg) } returns emptyList()
            coEvery { infoCache.getVersionCode(pkg) } returns null
            coEvery { infoCache.hasKeystore(pkg) } returns false
            coEvery { infoCache.warmAll(any()) } returns Unit
            every { infoCache.size() } returns 1

            val progress = mutableListOf<BackupOperation.BackupProgress>()
            val result = BackupOperation.backupApps(
                context = context,
                apps = listOf(appInfo(pkg)),
                config = BackupConfig(backupMode = 1, backupUserData = 1, backupObbData = 1),
                outputDir = tempDir,
                appInfoCache = infoCache,
                onProgress = { progress.add(it) },
            )

            result.failCount shouldBe 1
            result.successCount shouldBe 0
            progress.any { it.stage == "appdone" && it.packageName == pkg } shouldBe true
        }

        test("数据备份失败时 failCount 递增且该应用被标记为失败") {
            val pkg = "com.example.app"
            val infoCache = mockk<AppInfoCache>(relaxed = true)
            coEvery { infoCache.getApkPaths(pkg) } returns listOf("/data/app/$pkg/base.apk")
            coEvery { infoCache.getVersionCode(pkg) } returns null
            coEvery { infoCache.hasKeystore(pkg) } returns false
            coEvery { infoCache.warmAll(any()) } returns Unit
            every { infoCache.size() } returns 1
            coEvery { BackupAppDataOps.backupUserData(any(), any(), any(), any(), any()) } returns (null to null)

            val progress = mutableListOf<BackupOperation.BackupProgress>()
            val result = BackupOperation.backupApps(
                context = context,
                apps = listOf(appInfo(pkg)),
                config = BackupConfig(backupMode = 1, backupUserData = 1, backupObbData = 0),
                outputDir = tempDir,
                appInfoCache = infoCache,
                onProgress = { progress.add(it) },
            )

            result.failCount shouldBe 1
            result.successCount shouldBe 0
            progress.any { it.stage == "appdone" && it.message.contains("数据备份失败") } shouldBe true
        }
    }
})
