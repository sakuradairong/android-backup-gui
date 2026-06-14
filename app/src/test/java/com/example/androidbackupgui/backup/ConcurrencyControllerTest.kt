package com.example.androidbackupgui.backup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * 单元测试 - 验证 [ConcurrencyController] 的数据结构和合理边界。
 *
 * 关键性：错误的并发数会导致低端设备 OOM 或高端设备性能未充分利用。
 *
 * 注：calculateOptimalConcurrency 需要真实的 ActivityManager 调用，
 * 仅在 Android 设备上可运行。纯 JVM 单元测试只能验证数据结构。
 * 设备分级算法的完整覆盖需要 Robolectric 或 instrumented 测试。
 */
class ConcurrencyControllerTest : FunSpec({

    test("ConcurrencyConfig 数据类的字段") {
        val config = ConcurrencyController.ConcurrencyConfig(
            maxConcurrency = 3,
            reason = "test reason",
        )
        config.maxConcurrency shouldBeInRange (1..10)
        config.reason shouldBe "test reason"
    }

    test("ConcurrencyConfig 数据类相等性") {
        val a = ConcurrencyController.ConcurrencyConfig(maxConcurrency = 3, reason = "r")
        val b = ConcurrencyController.ConcurrencyConfig(maxConcurrency = 3, reason = "r")
        val c = ConcurrencyController.ConcurrencyConfig(maxConcurrency = 4, reason = "r")

        a shouldBe b
        a shouldNotBe c
    }

    test("ConcurrencyConfig 数据类 copy 修改字段") {
        val original = ConcurrencyController.ConcurrencyConfig(maxConcurrency = 3, reason = "r")
        val modified = original.copy(maxConcurrency = 5)
        modified.maxConcurrency shouldBe 5
        modified.reason shouldBe "r"
    }
})
