package com.example.androidbackupgui.backup

import com.example.androidbackupgui.backup.restic.ResticCommandRunner

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ResticCommandRunnerTest : FunSpec({

    val defaultRunner = ResticCommandRunner()

    context("buildCommandArgs") {
        test("prepends default binary path") {
            val args = defaultRunner.buildCommandArgs(listOf("init", "--json"))
            args shouldBe listOf("restic", "init", "--json")
        }

        test("uses custom binary path") {
            val runner = ResticCommandRunner()
            runner.binaryPath = "/data/data/com.termux/files/usr/bin/restic"
            val args = runner.buildCommandArgs(listOf("backup", "/sdcard"))
            args shouldBe
                listOf(
                    "/data/data/com.termux/files/usr/bin/restic",
                    "backup",
                    "/sdcard",
                )
        }

        test("returns empty args list when called with empty list") {
            val args = defaultRunner.buildCommandArgs(emptyList())
            args shouldBe listOf("restic")
        }

        test("preserves argument order") {
            val runner = ResticCommandRunner()
            runner.binaryPath = "restic"
            val args = runner.buildCommandArgs(listOf("a", "b", "c"))
            args shouldBe listOf("restic", "a", "b", "c")
        }

        test("property: any list of string args mainatains length") {
            checkAll(Arb.list(Arb.string(1..20), 0..10)) { inputArgs ->
                val args = defaultRunner.buildCommandArgs(inputArgs)
                args shouldHaveSize (inputArgs.size + 1)
                args[0] shouldBe "restic"
            }
        }
    }

    context("runRestic(vararg)") {
        test("delegates to runRestic(List) and returns failure on nonexistent binary") {
            val runner = ResticCommandRunner()
            runner.binaryPath = "/nonexistent/restic"
            val result = runner.runRestic(mapOf("RESTIC_REPOSITORY" to "/tmp/repo"), "version")
            result.exitCode shouldBe -1
            result.stdout shouldBe ""
        }
    }

    context("CommandResult serialization") {
        test("serializes and deserializes correctly") {
            val original =
                ResticCommandRunner.CommandResult(
                    stdout = "some output",
                    stderr = "",
                    exitCode = 0,
                )
            val json = Json.encodeToString(original)
            val decoded = Json.decodeFromString<ResticCommandRunner.CommandResult>(json)
            decoded.stdout shouldBe "some output"
            decoded.stderr shouldBe ""
            decoded.exitCode shouldBe 0
        }

        test("roundtrip property: preserves exit code") {
            checkAll(Arb.int()) { code ->
                val original =
                    ResticCommandRunner.CommandResult(
                        stdout = "out",
                        stderr = code.toString(),
                        exitCode = code,
                    )
                val json = Json.encodeToString(original)
                val decoded = Json.decodeFromString<ResticCommandRunner.CommandResult>(json)
                decoded.exitCode shouldBe code
                decoded.stderr shouldBe code.toString()
            }
        }
    }

    context("ResticCommandRunner instantiation") {
        test("default binary path is restic") {
            defaultRunner.binaryPath shouldBe "restic"
        }

        test("can set custom binary path") {
            val runner = ResticCommandRunner()
            runner.binaryPath = "/custom/path/restic"
            runner.binaryPath shouldBe "/custom/path/restic"
        }
    }
})
