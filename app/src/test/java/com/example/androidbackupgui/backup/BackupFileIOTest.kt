package com.example.androidbackupgui.backup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files

/**
 * 单元测试 - 验证 [BackupFileIO] 中可以纯 JVM 验证的部分。
 *
 * 关键性：FUSE 挂载下 Java File API 行为异常，备份操作依赖 root shell 回退。
 * 这里测试本地（tmp）目录场景下基本行为的正确性。
 *
 * 注：依赖 RootShell.exec() 的回退路径需要真机测试覆盖。
 */
class BackupFileIOTest : FunSpec({

    lateinit var tempDir: File

    beforeTest {
        tempDir = Files.createTempDirectory("backup_fileio_test").toFile()
    }

    afterTest {
        tempDir.deleteRecursively()
    }

    test("listBackupFiles - 列出真实文件") {
        File(tempDir, "app1.apk").writeText("dummy")
        File(tempDir, "app2.apk").writeText("dummy")
        File(tempDir, "metadata.json").writeText("{}")

        kotlinx.coroutines.runBlocking {
            val files = BackupFileIO.listBackupFiles(tempDir)
            files?.toSet() shouldBe setOf("app1.apk", "app2.apk", "metadata.json")
        }
    }

    test("listBackupFiles - 空目录返回 null（依赖 root shell 回退）") {
        // Java listFiles() 在空目录返回 []，不返回 null，所以会返回空列表
        // 这里只验证不抛异常
        kotlinx.coroutines.runBlocking {
            val files = BackupFileIO.listBackupFiles(tempDir)
            // 实际结果取决于实现细节：可能是 [] 也可能是 null
            // 关键是不抛异常
            (files == null || files.isEmpty()) shouldBe true
        }
    }

    test("backupFileSize - 现有文件返回正大小") {
        val file = File(tempDir, "test.bin")
        file.writeBytes(ByteArray(1024))

        kotlinx.coroutines.runBlocking {
            val size = BackupFileIO.backupFileSize(file)
            size shouldBe 1024L
        }
    }

    test("backupPathExists - 存在文件返回 true") {
        val file = File(tempDir, "exists.txt")
        file.writeText("hello")

        kotlinx.coroutines.runBlocking {
            BackupFileIO.backupPathExists(file) shouldBe true
        }
    }

    test("backupPathExists - 不存在文件返回 false") {
        val file = File(tempDir, "not_exists.txt")
        kotlinx.coroutines.runBlocking {
            BackupFileIO.backupPathExists(file) shouldBe false
        }
    }

    test("mkdirsForBackup - 创建新目录") {
        val newDir = File(tempDir, "new_subdir")
        newDir.exists() shouldBe false

        kotlinx.coroutines.runBlocking {
            BackupFileIO.mkdirsForBackup(newDir) shouldBe true
            newDir.isDirectory shouldBe true
        }
    }

    test("mkdirsForBackup - 目录已存在也返回 true") {
        val existingDir = File(tempDir, "already_exists")
        existingDir.mkdirs()

        kotlinx.coroutines.runBlocking {
            BackupFileIO.mkdirsForBackup(existingDir) shouldBe true
        }
    }
})
