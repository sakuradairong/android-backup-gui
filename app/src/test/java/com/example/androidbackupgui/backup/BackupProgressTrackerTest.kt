package com.example.androidbackupgui.backup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan

/**
 * 单元测试 - 验证 [BackupProgressTracker] 的 EMA 平滑算法和 ETA 估算。
 *
 * 关键性：ETA 显示给用户看，错误会让用户误判剩余时间。
 * 测试不依赖 RootShell，可纯 JVM 运行。
 */
class BackupProgressTrackerTest : FunSpec({

    test("初始状态 - 0 完成 0 ETA") {
        val tracker = BackupProgressTracker(totalApps = 10)
        val progress = tracker.getProgress()

        progress.current shouldBe 0
        progress.total shouldBe 10
        progress.percent shouldBe 0f
        progress.etaSeconds shouldBe 0L
    }

    test("第一个应用完成后 ETA 大于 0") {
        val tracker = BackupProgressTracker(totalApps = 10)
        tracker.startApp("com.app1")
        Thread.sleep(1500) // 模拟备份耗时，确保 ETA 计算可观测
        tracker.completeApp()

        val progress = tracker.getProgress()
        progress.current shouldBe 1
        progress.percent shouldBe 10f
        progress.etaSeconds shouldBeGreaterThan 0L
    }

    test("所有应用完成后 isComplete = true") {
        val tracker = BackupProgressTracker(totalApps = 2)
        tracker.startApp("com.app1")
        tracker.completeApp()
        tracker.startApp("com.app2")
        tracker.completeApp()

        tracker.isComplete() shouldBe true
    }

    test("skipApp 也算作完成") {
        val tracker = BackupProgressTracker(totalApps = 3)
        tracker.startApp("com.app1")
        tracker.skipApp("com.app1", "APK无变化")
        tracker.startApp("com.app2")
        tracker.skipApp("com.app2", "数据无变化")
        tracker.startApp("com.app3")
        tracker.skipApp("com.app3", "APK无变化")

        tracker.getCompletedCount() shouldBe 3
        tracker.isComplete() shouldBe true
    }

    test("ETA 在所有应用完成后为 0") {
        val tracker = BackupProgressTracker(totalApps = 2)
        tracker.startApp("a")
        tracker.completeApp()
        tracker.startApp("b")
        tracker.completeApp()

        tracker.getProgress().etaSeconds shouldBe 0L
    }

    test("百分比正确") {
        val tracker = BackupProgressTracker(totalApps = 4)
        tracker.startApp("a")
        tracker.completeApp()
        tracker.getProgress().percent shouldBe 25f

        tracker.startApp("b")
        tracker.completeApp()
        tracker.getProgress().percent shouldBe 50f

        tracker.startApp("c")
        tracker.completeApp()
        tracker.getProgress().percent shouldBe 75f

        tracker.startApp("d")
        tracker.completeApp()
        tracker.getProgress().percent shouldBe 100f
    }

    test("formatEta 格式化") {
        val tracker = BackupProgressTracker(totalApps = 1)

        tracker.formatEta(0) shouldBe "计算中..."
        tracker.formatEta(45) shouldBe "45秒"
        tracker.formatEta(60) shouldBe "1分0秒"
        tracker.formatEta(125) shouldBe "2分5秒"
        tracker.formatEta(3600) shouldBe "1小时0分0秒"
        tracker.formatEta(3661) shouldBe "1小时1分1秒"
    }
})
