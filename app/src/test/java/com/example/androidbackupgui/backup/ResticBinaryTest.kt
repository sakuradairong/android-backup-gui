package com.example.androidbackupgui.backup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ResticBinaryTest : FunSpec({

    context("ResticBinary") {
        test("isReady returns false before prepare is called") {
            ResticBinary.isReady() shouldBe false
        }
    }
})
