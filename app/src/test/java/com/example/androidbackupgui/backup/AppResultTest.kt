package com.example.androidbackupgui.backup

import com.example.androidbackupgui.backup.core.AppError
import com.example.androidbackupgui.backup.core.AppResult
import com.example.androidbackupgui.backup.core.err

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class AppResultTest :
    FunSpec({

        context("AppResult.Success") {
            test("holds value correctly") {
                val result: AppResult<String> = AppResult.Success("hello")
                result.isSuccess shouldBe true
                result.isFailure shouldBe false
                result.getOrNull() shouldBe "hello"
                result.getOrDefault("default") shouldBe "hello"
            }

            test("fold maps success branch") {
                val result: AppResult<Int> = AppResult.Success(42)
                val output = result.fold({ it * 2 }, { -1 })
                output shouldBe 84
            }

            test("map transforms value") {
                val result = AppResult.Success(42)
                val mapped = result.map { it.toString() }
                mapped.shouldBeInstanceOf<AppResult.Success<String>>()
                mapped.getOrNull() shouldBe "42"
            }

            test("getOrThrow returns value") {
                val result = AppResult.Success(99)
                result.getOrThrow() shouldBe 99
            }
        }

        context("AppResult.Failure") {
            val error = AppError.Network("connection lost")

            test("holds error correctly") {
                val result: AppResult<Int> = AppResult.Failure(error)
                result.isSuccess shouldBe false
                result.isFailure shouldBe true
                result.getOrNull().shouldBeNull()
                result.getOrDefault(0) shouldBe 0
                result.errorOrNull() shouldBe error
            }

            test("fold maps failure branch") {
                val result: AppResult<Int> = AppResult.Failure(error)
                val output = result.fold({ it }, { err -> -1 })
                output shouldBe -1
            }

            test("map passes through failure") {
                val result: AppResult<Int> = AppResult.Failure(error)
                val mapped = result.map { it * 2 }
                mapped.shouldBeInstanceOf<AppResult.Failure>()
                mapped.errorOrNull() shouldBe error
            }

            test("getOrThrow throws") {
                val result = AppResult.Failure(error)
                shouldThrow<RuntimeException> { result.getOrThrow() }
            }

            test("mapError transforms the error") {
                val result: AppResult<Int> = AppResult.Failure(error)
                val mapped = result.mapError { AppError.Parse("wrapped: ${it.message}") }
                mapped.shouldBeInstanceOf<AppResult.Failure>()
                (mapped.errorOrNull() as? AppError.Parse)?.let {
                    it.message shouldBe "wrapped: connection lost"
                }
            }
        }

        context("err helper") {
            test("creates Failure") {
                val result = err<String>(AppError.Cancelled)
                result.shouldBeInstanceOf<AppResult.Failure>()
                result.errorOrNull() shouldBe AppError.Cancelled
            }
        }
    })
