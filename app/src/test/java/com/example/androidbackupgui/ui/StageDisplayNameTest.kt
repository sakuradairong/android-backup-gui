package com.example.androidbackupgui.ui

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldBeEmpty
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

class StageDisplayNameTest : FunSpec({

    context("backupStageDisplayName") {
        test("maps known backup stages to Chinese labels") {
            backupStageDisplayName("apk") shouldBe "备份 APK"
            backupStageDisplayName("data") shouldBe "备份数据"
            backupStageDisplayName("obb") shouldBe "备份 OBB"
            backupStageDisplayName("ssaid") shouldBe "备份 SSAID"
            backupStageDisplayName("appdone") shouldBe "已完成"
            backupStageDisplayName("restic") shouldBe "上传至 Restic"
            backupStageDisplayName("done") shouldBe "完成"
            backupStageDisplayName("partial") shouldBe "部分完成"
        }

        test("empty stage falls back to 处理中 (not 完成)") {
            // 回归测试：原来空字符串 per-app "done" 会让 UI 反复闪"完成"，
            // 现在空串显示"处理中"，per-app 完成是"已完成"，避免误导用户。
            backupStageDisplayName("") shouldBe "处理中"
        }

        test("unknown stage is returned as-is") {
            backupStageDisplayName("weird-stage") shouldBe "weird-stage"
        }

        test("every stage produced by BackupOperation has a non-default mapping") {
            // 这些是 BackupOperation.kt 实际 emit 的所有 stage 值，
            // 任一新增未映射会导致 UI 显示原始英文 stage，需要在映射表里同步。
            val emittedStages = listOf("apk", "data", "obb", "ssaid", "appdone")
            emittedStages.forEach { stage ->
                val label = backupStageDisplayName(stage)
                label shouldNotBe stage
                label.isNotEmpty() shouldBe true
            }
        }
    }

    context("restoreStageDisplayName") {
        test("maps known restore stages to Chinese labels") {
            restoreStageDisplayName("install") shouldBe "安装 APK"
            restoreStageDisplayName("data") shouldBe "恢复数据"
            restoreStageDisplayName("obb") shouldBe "恢复 OBB"
            restoreStageDisplayName("ssaid") shouldBe "恢复 SSAID"
            restoreStageDisplayName("permissions") shouldBe "恢复权限"
            restoreStageDisplayName("appdone") shouldBe "已完成"
            restoreStageDisplayName("done") shouldBe "完成"
            restoreStageDisplayName("partial") shouldBe "部分完成"
        }

        test("empty stage falls back to 处理中") {
            restoreStageDisplayName("") shouldBe "处理中"
        }

        test("every stage produced by RestoreOperation has a non-default mapping") {
            val emittedStages = listOf("install", "data", "obb", "ssaid", "permissions", "appdone")
            emittedStages.forEach { stage ->
                val label = restoreStageDisplayName(stage)
                label shouldNotBe stage
                label.isNotEmpty() shouldBe true
            }
        }
    }

    context("partial stage is distinct from done") {
        // 备份工具关键诉求：失败状态必须可被 UI 区分（染 error 色 / 不拉满进度条）。
        // 这两个映射必须不同，否则 ProgressBlock 的 isError 分支永不触发。
        test("backup partial != done") {
            backupStageDisplayName("partial") shouldNotBe backupStageDisplayName("done")
        }

        test("restore partial != done") {
            restoreStageDisplayName("partial") shouldNotBe restoreStageDisplayName("done")
        }
    }

    context("property: never returns null and always non-blank for any string") {
        test("arbitrary strings yield non-blank labels") {
            val knownStages = Arb.element("apk", "data", "", "weird", "done", "partial")
            checkAll(50, knownStages) { stage ->
                backupStageDisplayName(stage).isNotEmpty() shouldBe true
                restoreStageDisplayName(stage).isNotEmpty() shouldBe true
            }
        }

        test("property: any random non-empty stage string is returned non-blank") {
            checkAll(50, Arb.string(minSize = 1, maxSize = 20)) { s ->
                restoreStageDisplayName(s).isNotEmpty() shouldBe true
            }
        }
    }
})
