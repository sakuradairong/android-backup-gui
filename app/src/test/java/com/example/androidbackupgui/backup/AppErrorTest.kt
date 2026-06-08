package com.example.androidbackupgui.backup

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import java.io.IOException

class AppErrorTest : FunSpec({

    context("AppError.Network") {
        test("has correct defaults") {
            val error = AppError.Network("connection timeout")
            error.message shouldBe "connection timeout"
            error.cause.shouldBeNull()
            error.retryable shouldBe true
        }

        test("preserves cause and retryable overrides") {
            val cause = RuntimeException("DNS failed")
            val error = AppError.Network("DNS resolution failed", cause = cause, retryable = false)
            error.cause shouldBe cause
            error.retryable shouldBe false
        }

        test("property: message is preserved") {
            checkAll(Arb.string(1..200)) { msg ->
                val error = AppError.Network(msg)
                error.message shouldBe msg
            }
        }
    }

    context("AppError.Remote") {
        test("preserves phase, cause, isNotFound, retryable") {
            val cause = RuntimeException("underlying error")
            val error = AppError.Remote("upload failed", "upload", cause = cause)
            error.message shouldBe "upload failed"
            error.phase shouldBe "upload"
            error.cause shouldBe cause
            error.isNotFound shouldBe false
            error.retryable shouldBe false
        }

        test("with isNotFound=true") {
            val error = AppError.Remote("not found", "list", isNotFound = true)
            error.isNotFound shouldBe true
        }
    }

    context("AppError.Shell") {
        test("preserves command, exitCode, and stderr") {
            val error = AppError.Shell("cp failed", "cp /a /b", 1, "permission denied")
            error.message shouldBe "cp failed"
            error.command shouldBe "cp /a /b"
            error.exitCode shouldBe 1
            error.stderr shouldBe "permission denied"
        }
    }

    context("AppError.LocalIO") {
        test("preserves path and optional cause") {
            val error = AppError.LocalIO("file not found", "/data/test.txt")
            error.message shouldBe "file not found"
            error.path shouldBe "/data/test.txt"
            error.cause.shouldBeNull()
        }

        test("preserves cause when provided") {
            val cause = IOException("disk full")
            val error = AppError.LocalIO("write failed", "/data/test.txt", cause = cause)
            error.cause shouldBe cause
        }
    }

    context("AppError.Restic") {
        test("preserves exit code and stderr") {
            val error = AppError.Restic("restic failed", 1, "permission denied")
            error.message shouldBe "restic failed"
            error.exitCode shouldBe 1
            error.stderr shouldBe "permission denied"
        }

        test("property: any exit code is preserved") {
            checkAll(Arb.int()) { code ->
                val error = AppError.Restic("err", code, "stderr output")
                error.exitCode shouldBe code
            }
        }
    }

    context("AppError.Parse") {
        test("preserves message and detail") {
            val error = AppError.Parse("bad json", "expected '{'")
            error.message shouldBe "bad json"
            error.detail shouldBe "expected '{'"
        }

        test("detail defaults to empty string") {
            val error = AppError.Parse("bad json")
            error.detail shouldBe ""
        }
    }

    context("AppError.Cancelled") {
        test("is a data object with fixed message") {
            val error = AppError.Cancelled
            error.message shouldBe "操作被取消"
            // Verify singleton behavior
            val error2 = AppError.Cancelled
            error shouldBe error2
        }
    }

    context("AppResult.Success") {
        test("holds a value") {
            val result: AppResult<String> = AppResult.Success("hello")
            result.isSuccess shouldBe true
            result.isFailure shouldBe false
            result.getOrNull() shouldBe "hello"
            result.getOrDefault("fallback") shouldBe "hello"
            result.getOrThrow() shouldBe "hello"
            result.exceptionOrNull().shouldBeNull()
            result.errorOrNull().shouldBeNull()
        }

        test("fold calls onSuccess") {
            val result: AppResult<Int> = AppResult.Success(42)
            val folded =
                result.fold(
                    onSuccess = { it * 2 },
                    onFailure = { 0 },
                )
            folded shouldBe 84
        }

        test("map transforms value") {
            val result: AppResult<Int> = AppResult.Success(42)
            val mapped = result.map { it.toString() }
            mapped shouldBe AppResult.Success("42")
        }

        test("mapError passes through success") {
            val result: AppResult<Int> = AppResult.Success(42)
            val mapped = result.mapError { AppError.Parse("should not happen") }
            mapped shouldBe AppResult.Success(42)
        }
    }

    context("AppResult.Failure via err()") {
        test("creates failure result") {
            val result: AppResult<String> = err(AppError.Parse("bad json"))
            result.isSuccess shouldBe false
            result.isFailure shouldBe true
            result.getOrNull().shouldBeNull()
            result.getOrDefault("fallback") shouldBe "fallback"
            result.errorOrNull() shouldBe AppError.Parse("bad json")
            result.errorOrNull()?.message shouldBe "bad json"
        }

        test("exceptionOrNull returns RuntimeException with AppError message") {
            val result: AppResult<String> = err(AppError.Parse("bad json"))
            val ex = result.exceptionOrNull()
            ex.shouldBeInstanceOf<RuntimeException>()
            ex?.message shouldBe "bad json"
        }

        test("getOrThrow throws RuntimeException") {
            val result: AppResult<String> = err(AppError.Parse("bad json"))
            val ex = shouldThrow<RuntimeException> { result.getOrThrow() }
            ex.message shouldBe "bad json"
        }

        test("wraps any AppError subtype") {
            val errors =
                listOf(
                    AppError.Network("net err"),
                    AppError.Remote("remote err", "connect"),
                    AppError.Shell("shell err", "ls", 1, ""),
                    AppError.LocalIO("io err", "/tmp"),
                    AppError.Restic("restic err", 1, ""),
                    AppError.Parse("parse err"),
                    AppError.Cancelled,
                )
            errors.forEach { error ->
                val result: AppResult<Unit> = err(error)
                result.isFailure shouldBe true
                result.errorOrNull()?.message shouldBe error.message
            }
        }
    }

    context("AppResult.Failure direct") {
        test("holds an error") {
            val error = AppError.Network("network error")
            val result: AppResult<String> = AppResult.Failure(error)
            result.isSuccess shouldBe false
            result.isFailure shouldBe true
            result.errorOrNull() shouldBe error
        }

        test("fold calls onFailure") {
            val result: AppResult<Int> = AppResult.Failure(AppError.Parse("parse failed"))
            val folded =
                result.fold(
                    onSuccess = { 0 },
                    onFailure = { error -> error.message.length },
                )
            folded shouldBe "parse failed".length
        }

        test("map passes through failure") {
            val error = AppError.Parse("no data")
            val result: AppResult<Int> = AppResult.Failure(error)
            val mapped = result.map { it + 1 }
            mapped shouldBe AppResult.Failure(error)
        }

        test("mapError transforms error") {
            val result: AppResult<Int> = AppResult.Failure(AppError.Parse("old error"))
            val mapped = result.mapError { AppError.Remote("mapped: ${it.message}", "transform") }
            mapped.errorOrNull()?.message shouldBe "mapped: old error"
        }
    }

    context("AppResult exhaustive when") {
        test("can pattern match with is AppResult.Success") {
            val result: AppResult<String> = AppResult.Success("data")
            val output =
                when (result) {
                    is AppResult.Success -> "got: ${result.data}"
                    is AppResult.Failure -> "err: ${result.error.message}"
                }
            output shouldBe "got: data"
        }

        test("can pattern match with is AppResult.Failure") {
            val result: AppResult<String> = AppResult.Failure(AppError.Cancelled)
            val output =
                when (result) {
                    is AppResult.Success -> "got: ${result.data}"
                    is AppResult.Failure -> "err: ${result.error.message}"
                }
            output shouldBe "err: 操作被取消"
        }
    }

    context("AppResult type inference") {
        test("AppResult.Success with Unit") {
            val result: AppResult<Unit> = AppResult.Success(Unit)
            result.isSuccess shouldBe true
            result.getOrDefault(Unit) shouldBe Unit
        }

        test("AppResult.Failure with Nothing") {
            val result: AppResult<Int> = AppResult.Failure(AppError.Cancelled)
            result.isFailure shouldBe true
        }
    }

    context("err function short-form") {
        test("err() returns AppResult.Failure") {
            val result: AppResult<String> = err(AppError.Remote("upload failed", "upload"))
            result.shouldBeInstanceOf<AppResult.Failure>()
            (result as AppResult.Failure).error shouldBe AppError.Remote("upload failed", "upload")
        }
    }
})
