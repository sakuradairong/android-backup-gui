package com.example.androidbackupgui.backup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

class BackupConfigTest : FunSpec({

    // Helper: write config to temp file, read it back
    fun roundTrip(config: BackupConfig): BackupConfig {
        val tmp = File.createTempFile("cfg_test", ".conf")
        try {
            BackupConfig.toFile(config, tmp)
            return BackupConfig.fromFile(tmp)
        } finally {
            tmp.delete()
        }
    }

    test("plain password survives round trip") {
        val c = BackupConfig(resticPassword = "simple123")
        roundTrip(c).resticPassword shouldBe "simple123"
    }

    test("password with double-quote survives round trip") {
        val c = BackupConfig(resticPassword = "pa\"ss\"word")
        roundTrip(c).resticPassword shouldBe "pa\"ss\"word"
    }

    test("password with backslash survives round trip") {
        val c = BackupConfig(resticPassword = "p\\a\\ss")
        roundTrip(c).resticPassword shouldBe "p\\a\\ss"
    }

    test("password with leading and trailing spaces survives round trip") {
        val c = BackupConfig(resticPassword = " sp ace ")
        roundTrip(c).resticPassword shouldBe " sp ace "
    }

    test("password with special shell characters survives round trip") {
        val c = BackupConfig(resticPassword = "p@\$s#w!ord&")
        roundTrip(c).resticPassword shouldBe "p@\$s#w!ord&"
    }

    test("restic_backend_pass with quote and backslash survives round trip") {
        val c = BackupConfig(resticBackendPass = "a\\\"b")
        roundTrip(c).resticBackendPass shouldBe "a\\\"b"
    }

    test("output path with spaces survives round trip") {
        val c = BackupConfig(outputPath = "/sdcard/my backups/")
        roundTrip(c).outputPath shouldBe "/sdcard/my backups/"
    }

    test("non-restic fields are unaffected") {
        val c = BackupConfig(backupMode = 1, backupWifi = 0, compressionMethod = "zstd")
        val out = roundTrip(c)
        out.backupMode shouldBe 1
        out.backupWifi shouldBe 0
        out.compressionMethod shouldBe "zstd"
    }
})
