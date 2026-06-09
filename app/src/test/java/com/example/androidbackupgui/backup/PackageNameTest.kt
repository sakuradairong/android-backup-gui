package com.example.androidbackupgui.backup

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class PackageNameTest :
    FunSpec({

        context("PackageName constructor validation") {
            test("accepts valid package names") {
                PackageName("com.example.app").value shouldBe "com.example.app"
                PackageName("com.google.android.gms").value shouldBe "com.google.android.gms"
                PackageName("a.b").value shouldBe "a.b"
                PackageName("com.example.app_v2.test").value shouldBe "com.example.app_v2.test"
                PackageName("org.koin.android").value shouldBe "org.koin.android"
            }

            test("rejects blank package names") {
                shouldThrow<IllegalArgumentException> { PackageName("") }
                shouldThrow<IllegalArgumentException> { PackageName("  ") }
            }

            test("rejects package names without dots") {
                shouldThrow<IllegalArgumentException> { PackageName("simple") }
                shouldThrow<IllegalArgumentException> { PackageName("no_dot_at_all") }
            }

            test("rejects package names with invalid characters") {
                shouldThrow<IllegalArgumentException> { PackageName("com.example .app") }
                shouldThrow<IllegalArgumentException> { PackageName("com.example/app") }
                shouldThrow<IllegalArgumentException> { PackageName("com.example\napp") }
            }

            test("rejects package names starting with dot") {
                shouldThrow<IllegalArgumentException> { PackageName(".com.example") }
            }

            test("rejects package names ending with dot") {
                shouldThrow<IllegalArgumentException> { PackageName("com.example.") }
            }
        }

        context("PackageName.safe") {
            test("returns PackageName for valid input") {
                PackageName.safe("com.example.app").shouldNotBeNull()
                PackageName.safe("a.b").shouldNotBeNull()
            }

            test("returns null for invalid input instead of throwing") {
                PackageName.safe("").shouldBeNull()
                PackageName.safe("no_dots").shouldBeNull()
                PackageName.safe("with space").shouldBeNull()
                PackageName.safe("with/slash").shouldBeNull()
            }
        }

        context("PackageName equality and toString") {
            test("value equality works") {
                PackageName("com.example.app") shouldBe PackageName("com.example.app")
            }

            test("toString returns the package name") {
                PackageName("com.example.app").toString() shouldBe "com.example.app"
            }
        }
    })
