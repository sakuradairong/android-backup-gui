package com.example.androidbackupgui.backup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

class BackupConfigTest :
    FunSpec({

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

        test("password is stored as placeholder (actual password in PasswordManager)") {
            val c = BackupConfig(resticPassword = "simple123")
            // Password is no longer in config file; toFile writes "stored-in-keystore"
            roundTrip(c).resticPassword shouldBe ""
        }

        test("backend pass is stored as placeholder (actual pass in PasswordManager)") {
            val c = BackupConfig(resticBackendPass = "secret")
            roundTrip(c).resticBackendPass shouldBe ""
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
