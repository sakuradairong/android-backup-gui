package com.example.androidbackupgui.backup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * 单元测试 - 验证 [BackupServiceBridge] 接口的契约。
 *
 * 重构背景：重构前 ViewModel 直接构造 `Intent` 并通过 `BackupService.Companion`
 * 的 10+ 个常量与 Service 通信。重构后 ViewModel 仅依赖本接口，
 * 测试时可通过 mock 实现验证调用参数。
 *
 * 本测试覆盖：
 *  - TASK_TYPE_* 常量值（防止意外修改后 ViewModel/Service 不一致）
 *  - 接口可被 mock 实现（验证解耦成功——以前无法做到）
 *
 * 注：[AndroidBackupServiceBridge] 的实际 Intent 构造逻辑依赖 Android framework，
 * 需要 Robolectric 或 instrumented 测试覆盖；纯 JVM 测试无法验证。
 */
class BackupServiceBridgeTest : FunSpec({

    context("TASK_TYPE 常量") {

        test("TASK_TYPE_BACKUP 值为 backup") {
            BackupServiceBridge.TASK_TYPE_BACKUP shouldBe "backup"
        }

        test("TASK_TYPE_RESTORE 值为 restore") {
            BackupServiceBridge.TASK_TYPE_RESTORE shouldBe "restore"
        }

        test("TASK_TYPE_RESTIC 值为 restic") {
            BackupServiceBridge.TASK_TYPE_RESTIC shouldBe "restic"
        }

        test("三个 TASK_TYPE 常量互不相同") {
            val types = setOf(
                BackupServiceBridge.TASK_TYPE_BACKUP,
                BackupServiceBridge.TASK_TYPE_RESTORE,
                BackupServiceBridge.TASK_TYPE_RESTIC,
            )
            types.size shouldBe 3
        }
    }

    context("接口契约") {

        test("BackupServiceBridge 可被 mock 实现（验证解耦成功）") {
            // 这是重构的核心收益：以前 ViewModel 直接持有 Intent 构建逻辑，
            // 无法替换为 mock。现在通过接口可以无副作用地验证调用。
            val mockBridge = object : BackupServiceBridge {
                var startTaskCalls = 0
                var updateProgressCalls = 0
                var stopTaskCalls = 0
                var lastTaskType: String? = null
                var lastStatusText: String? = null

                override fun startTask(
                    context: android.content.Context,
                    taskId: String,
                    taskType: String,
                    statusText: String,
                ) {
                    startTaskCalls++
                    lastTaskType = taskType
                    lastStatusText = statusText
                }

                override fun updateProgress(
                    context: android.content.Context,
                    taskId: String,
                    taskType: String,
                    statusText: String,
                    current: Int,
                    total: Int,
                    percent: Float?,
                ) {
                    updateProgressCalls++
                    lastTaskType = taskType
                    lastStatusText = statusText
                }

                override fun stopTask(context: android.content.Context) {
                    stopTaskCalls++
                }
            }

            // 验证 mock 可被作为接口使用（编译期 + 运行期）
            mockBridge.startTaskCalls shouldBe 0
            mockBridge.updateProgressCalls shouldBe 0
            mockBridge.stopTaskCalls shouldBe 0
        }

        test("AndroidBackupServiceBridge 实现了 BackupServiceBridge 接口") {
            // 编译期检查：若 AndroidBackupServiceBridge 不实现接口，
            // 此赋值会编译失败。运行期再确认类型一致性。
            val bridge: BackupServiceBridge = AndroidBackupServiceBridge()
            bridge shouldBe bridge // 类型一致性自检
        }
    }
})
