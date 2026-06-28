package com.example.androidbackupgui.backup

import com.example.androidbackupgui.root.RootShell
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.File

class RestoreApkInstallerTest : FunSpec({

    lateinit var appDir: File
    lateinit var cacheDir: File

    beforeTest {
        val tempRoot = File(System.getProperty("java.io.tmpdir"), "restore_apk_installer_test_${System.nanoTime()}")
        appDir = File(tempRoot, "app").apply { mkdirs() }
        cacheDir = File(tempRoot, "cache").apply { mkdirs() }
        File(appDir, "base.apk").writeText("base")
        File(appDir, "config.en.apk").writeText("split")

        mockkObject(RootShell)
        mockkObject(BackupFileIO)
        coEvery { BackupFileIO.listBackupFiles(appDir) } returns listOf("base.apk", "config.en.apk")
        coEvery { BackupFileIO.backupPathExists(any()) } returns true
        coEvery { BackupFileIO.backupFileSize(any()) } returns 123L
    }

    afterTest {
        unmockkObject(RootShell)
        unmockkObject(BackupFileIO)
        cacheDir.parentFile?.deleteRecursively()
    }

    test("split APK install-write failure abandons session and does not commit") {
        val commands = mutableListOf<String>()
        coEvery { RootShell.exec(capture(commands), any()) } answers {
            val command = firstArg<String>()
            when {
                command.startsWith("pm install-create") -> RootShell.ShellResult("Success: created install session [42]", "", 0)
                command.contains("pm install-write") && command.contains("split_1.apk") -> RootShell.ShellResult("", "write failed", 1)
                else -> RootShell.ShellResult("", "", 0)
            }
        }

        val installed = RestoreApkInstaller.installApk("com.example.app", appDir, cacheDir)

        installed shouldBe false
        commands shouldContain "pm install-abandon '42'"
        commands.any { it.startsWith("pm install-commit") } shouldBe false
        coVerify(exactly = 0) { RootShell.exec(match { it.startsWith("pm install-commit") }, any()) }
    }
})
