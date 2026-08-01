package com.example.androidbackupgui.backup.restic

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.io.File

class RestBridgeRunnerTest : FunSpec({

    test("local backend bypasses bridge and passes repoPath without auth token") {
        val runner = RestBridgeRunner()
        val cacheDir = File("build/test-cache/rest-bridge-local")

        val result = runner.withBridge(
            backend = "local",
            backendUrl = "",
            backendUser = "",
            backendPass = "",
            backendShare = "",
            backendDomain = "",
            repoPath = "/data/local/repo",
            cacheDir = cacheDir,
        ) { bridgeUrl, authToken ->
            "$bridgeUrl|$authToken"
        }

        result shouldBe "/data/local/repo|"
    }

    test("remote backend fails fast when no transport can be created") {
        val runner = RestBridgeRunner()
        val cacheDir = File("build/test-cache/rest-bridge-unsupported")

        shouldThrow<IllegalArgumentException> {
            runTest {
                runner.withBridge(
                    backend = "unsupported",
                    backendUrl = "https://example.invalid",
                    backendUser = "user",
                    backendPass = "pass",
                    backendShare = "share",
                    backendDomain = "domain",
                    repoPath = "repo",
                    cacheDir = cacheDir,
                    transportFactory = { _, _, _, _, _, _ -> null },
                ) { _, _ ->
                    error("block should not run when remote transport is unavailable")
                }
            }
        }.message shouldBe "Unsupported remote backend: unsupported"
    }
})
