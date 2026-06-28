package com.example.androidbackupgui.backup.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty as shouldBeEmptyCollection
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.shouldBe

/**
 * 单元测试 - 验证 [AppDetailsParser] 的 JSON 解析行为。
 *
 * 重构背景：[AppDetailsParser] 从 [com.example.androidbackupgui.backup.restic.ResticWrapper]
 * 中提取，使纯 JSON 解析逻辑独立于 restic 配置状态。
 *
 * 本测试覆盖：
 *  - 正常 JSON 解析（含 label / isSystem / apkSizes 字段）
 *  - 空字符串和畸形 JSON 的容错（返回空 Map，不抛异常）
 *  - 顶层 JSONObject 不是对象时（数组/字符串）的容错
 *  - apkSizes 缺失或非数组时的容错
 */
class AppDetailsParserTest : FunSpec({

    context("正常解析") {

        test("解析包含两个应用的标准 app_details.json") {
            val json = """
                {
                    "com.example.app1": {
                        "label": "Example App 1",
                        "isSystem": false,
                        "apkSizes": [1024, 2048, 4096]
                    },
                    "com.example.app2": {
                        "label": "Example App 2",
                        "isSystem": true,
                        "apkSizes": [512]
                    }
                }
            """.trimIndent()

            val result = AppDetailsParser.parse(json)

            result shouldHaveSize 2
            result["com.example.app1"]?.label shouldBe "Example App 1"
            result["com.example.app1"]?.isSystem shouldBe false
            result["com.example.app1"]?.apkSizes shouldContainExactly listOf(1024L, 2048L, 4096L)
            result["com.example.app2"]?.label shouldBe "Example App 2"
            result["com.example.app2"]?.isSystem shouldBe true
            result["com.example.app2"]?.apkSizes shouldContainExactly listOf(512L)
        }

        test("解析包含中文 label 的应用") {
            val json = """
                {
                    "com.example.chinese": {
                        "label": "微信",
                        "isSystem": false,
                        "apkSizes": [102400]
                    }
                }
            """.trimIndent()

            val result = AppDetailsParser.parse(json)

            result shouldHaveSize 1
            result["com.example.chinese"]?.label shouldBe "微信"
        }
    }

    context("字段容错") {

        test("label 字段缺失时回退为 packageName 作为 label") {
            val json = """
                {
                    "com.example.noLabel": {
                        "isSystem": false,
                        "apkSizes": [1024]
                    }
                }
            """.trimIndent()

            val result = AppDetailsParser.parse(json)

            result shouldHaveSize 1
            result["com.example.noLabel"]?.label shouldBe "com.example.noLabel"
            result["com.example.noLabel"]?.isSystem shouldBe false
        }

        test("isSystem 字段缺失时默认为 false") {
            val json = """
                {
                    "com.example.noFlag": {
                        "label": "No Flag",
                        "apkSizes": [1024]
                    }
                }
            """.trimIndent()

            val result = AppDetailsParser.parse(json)

            result shouldHaveSize 1
            result["com.example.noFlag"]?.isSystem shouldBe false
        }

        test("apkSizes 字段缺失时默认为空列表") {
            val json = """
                {
                    "com.example.noSizes": {
                        "label": "No Sizes",
                        "isSystem": false
                    }
                }
            """.trimIndent()

            val result = AppDetailsParser.parse(json)

            result shouldHaveSize 1
            result["com.example.noSizes"]?.apkSizes.shouldBeEmptyCollection()
        }

        test("apkSizes 字段存在但非数组时被忽略，使用空列表") {
            val json = """
                {
                    "com.example.badSizes": {
                        "label": "Bad Sizes",
                        "isSystem": false,
                        "apkSizes": "not-an-array"
                    }
                }
            """.trimIndent()

            val result = AppDetailsParser.parse(json)

            result shouldHaveSize 1
            result["com.example.badSizes"]?.apkSizes.shouldBeEmptyCollection()
        }

        test("条目不是 JSONObject 时被跳过（不影响其他条目）") {
            val json = """
                {
                    "com.example.good": {
                        "label": "Good",
                        "isSystem": false,
                        "apkSizes": [1024]
                    },
                    "com.example.bad": "not-an-object",
                    "com.example.alsoGood": {
                        "label": "Also Good",
                        "isSystem": true,
                        "apkSizes": [2048]
                    }
                }
            """.trimIndent()

            val result = AppDetailsParser.parse(json)

            result shouldHaveSize 2
            result["com.example.good"]?.label shouldBe "Good"
            result["com.example.alsoGood"]?.label shouldBe "Also Good"
        }
    }

    context("错误输入容错") {

        test("畸形 JSON 返回空 Map（不抛异常）") {
            val malformed = "{ this is not valid JSON }"

            val result = AppDetailsParser.parse(malformed)

            result.shouldBeEmpty()
        }

        test("顶层不是对象（数组）时返回空 Map") {
            val array = """[{"label": "test"}]"""

            val result = AppDetailsParser.parse(array)

            result.shouldBeEmpty()
        }

        test("顶层不是对象（字符串）时返回空 Map") {
            val justString = "\"just a string\""

            val result = AppDetailsParser.parse(justString)

            result.shouldBeEmpty()
        }

        test("空字符串返回空 Map") {
            val result = AppDetailsParser.parse("")

            result.shouldBeEmpty()
        }

        test("空 JSON 对象返回空 Map（不是异常）") {
            val result = AppDetailsParser.parse("{}")

            result.shouldBeEmpty()
        }
    }
})
