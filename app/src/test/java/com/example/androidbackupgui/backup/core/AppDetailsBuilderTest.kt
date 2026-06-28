package com.example.androidbackupgui.backup.core

import com.example.androidbackupgui.backup.AppInfo
import com.example.androidbackupgui.backup.AppInfoCache
import com.example.androidbackupgui.backup.PackageName
import com.example.androidbackupgui.backup.scan.AppScanner
import com.example.androidbackupgui.root.RootShell
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.json.JSONObject

/**
 * 单元测试 - 验证 [AppDetailsBuilder] 的 JSON 构建行为。
 *
 * 重构背景：[AppDetailsBuilder] 从 [com.example.androidbackupgui.backup.BackupOperation]
 * 中提取，消除 `restic` 子模块对 `backup` god class 的反向依赖。
 *
 * 本测试覆盖：
 *  - 正常 apps 列表渲染
 *  - 空 apps 列表
 *  - legacy apps 合并逻辑（含同名覆盖）
 *  - perAppExtra 字段渲染（ssaid / permissions / keystore / sizes）
 *  - cache 命中 vs 回退到 RootShell.exec / AppScanner.getApkPaths
 *  - JSON 输出格式（2 空格缩进）
 */
class AppDetailsBuilderTest : FunSpec({

    beforeTest {
        mockkObject(RootShell)
        mockkObject(AppScanner)
        coEvery { RootShell.exec(any()) } returns RootShell.ShellResult("", "", 0)
    }

    afterTest {
        unmockkObject(RootShell)
        unmockkObject(AppScanner)
    }

    fun appInfo(
        pkg: String,
        label: String = "",
        isSystem: Boolean = false,
    ) = AppInfo(
        packageName = PackageName(pkg),
        label = label,
        isSystem = isSystem,
    )

    fun parseApp(root: JSONObject, pkg: String) = root.getJSONObject(pkg)

    context("正常 apps 列表") {

        test("单个应用渲染所有基本字段") {
            val cache = mockk<AppInfoCache>()
            coEvery { cache.getVersionCode("com.example.app") } returns "12345"
            coEvery { cache.getApkPaths("com.example.app") } returns listOf("/data/app/com.example.app/base.apk")
            coEvery { RootShell.exec("stat -c%s '/data/app/com.example.app/base.apk'") } returns
                RootShell.ShellResult("9876543210", "", 0)

            val json = AppDetailsBuilder.buildAppDetailsJson(
                apps = listOf(appInfo("com.example.app", label = "Example App", isSystem = false)),
                cache = cache,
            )

            val root = JSONObject(json)
            root.length() shouldBe 1
            val app = parseApp(root, "com.example.app")
            app.getString("label") shouldBe "Example App"
            app.getBoolean("isSystem") shouldBe false
            app.getString("PackageName") shouldBe "com.example.app"
            app.getString("apk_version") shouldBe "12345"
            app.getJSONArray("apkSizes").let {
                it.length() shouldBe 1
                it.getLong(0) shouldBe 9876543210L
            }
            app.getJSONObject("Backup time").getString("date") shouldNotBe ""
        }

        test("多个应用分别渲染各自字段") {
            val cache = mockk<AppInfoCache>()
            coEvery { cache.getVersionCode(any()) } returns "1"
            coEvery { cache.getApkPaths(any()) } returns listOf("/data/app/base.apk")
            coEvery { RootShell.exec("stat -c%s '/data/app/base.apk'") } returns RootShell.ShellResult("100", "", 0)

            val json = AppDetailsBuilder.buildAppDetailsJson(
                apps = listOf(
                    appInfo("com.example.a", label = "App A", isSystem = true),
                    appInfo("com.example.b", label = "App B", isSystem = false),
                ),
                cache = cache,
            )

            val root = JSONObject(json)
            root.length() shouldBe 2
            parseApp(root, "com.example.a").getBoolean("isSystem") shouldBe true
            parseApp(root, "com.example.b").getBoolean("isSystem") shouldBe false
            parseApp(root, "com.example.a").getString("label") shouldBe "App A"
            parseApp(root, "com.example.b").getString("label") shouldBe "App B"
        }
    }

    context("空 apps 列表") {

        test("空列表返回空 JSON 对象") {
            val json = AppDetailsBuilder.buildAppDetailsJson(apps = emptyList())

            json shouldBe "{}"
            JSONObject(json).length() shouldBe 0
        }

        test("空列表但包含 legacy apps 时只返回 legacy 条目") {
            val legacy = mapOf(
                "com.legacy.app" to SnapshotAppInfo(label = "Legacy App", isSystem = true, apkSizes = listOf(111L, 222L)),
            )

            val json = AppDetailsBuilder.buildAppDetailsJson(apps = emptyList(), legacyApps = legacy)

            val root = JSONObject(json)
            root.length() shouldBe 1
            val app = parseApp(root, "com.legacy.app")
            app.getString("label") shouldBe "Legacy App"
            app.getBoolean("isSystem") shouldBe true
            app.getJSONArray("apkSizes").toLongList() shouldContainExactly listOf(111L, 222L)
        }
    }

    context("legacy apps 合并逻辑") {

        test("legacy apps 与当前 apps 同时存在时合并输出") {
            val cache = mockk<AppInfoCache>()
            coEvery { cache.getVersionCode("com.current.app") } returns "2"
            coEvery { cache.getApkPaths("com.current.app") } returns listOf("/data/app/current.apk")
            coEvery { RootShell.exec("stat -c%s '/data/app/current.apk'") } returns RootShell.ShellResult("200", "", 0)

            val legacy = mapOf(
                "com.legacy.app" to SnapshotAppInfo(label = "Legacy", isSystem = false, apkSizes = emptyList()),
            )

            val json = AppDetailsBuilder.buildAppDetailsJson(
                apps = listOf(appInfo("com.current.app", label = "Current")),
                legacyApps = legacy,
                cache = cache,
            )

            val root = JSONObject(json)
            root.length() shouldBe 2
            parseApp(root, "com.current.app").getString("label") shouldBe "Current"
            parseApp(root, "com.legacy.app").getString("label") shouldBe "Legacy"
        }

        test("当前 apps 与 legacy 同名时当前 apps 覆盖 legacy") {
            val cache = mockk<AppInfoCache>()
            coEvery { cache.getVersionCode("com.same.app") } returns "3"
            coEvery { cache.getApkPaths("com.same.app") } returns listOf("/data/app/same.apk")
            coEvery { RootShell.exec("stat -c%s '/data/app/same.apk'") } returns RootShell.ShellResult("300", "", 0)

            val legacy = mapOf(
                "com.same.app" to SnapshotAppInfo(label = "Old Label", isSystem = true, apkSizes = listOf(1L)),
            )

            val json = AppDetailsBuilder.buildAppDetailsJson(
                apps = listOf(appInfo("com.same.app", label = "New Label", isSystem = false)),
                legacyApps = legacy,
                cache = cache,
            )

            val root = JSONObject(json)
            root.length() shouldBe 1
            val app = parseApp(root, "com.same.app")
            app.getString("label") shouldBe "New Label"
            app.getBoolean("isSystem") shouldBe false
        }
    }

    context("perAppExtra 字段渲染") {

        test("perAppExtra 的 ssaid / permissions / keystore 被渲染") {
            val cache = mockk<AppInfoCache>()
            coEvery { cache.getVersionCode("com.extra.app") } returns null
            coEvery { cache.getApkPaths("com.extra.app") } returns emptyList()

            val permissions = JSONObject().apply { put("android.permission.INTERNET", true) }
            val extras = mapOf(
                "com.extra.app" to AppDetailsBuilder.PerAppExtra(
                    ssaid = "abc-123",
                    permissions = permissions,
                    keystore = true,
                ),
            )

            val json = AppDetailsBuilder.buildAppDetailsJson(
                apps = listOf(appInfo("com.extra.app", label = "Extra")),
                perAppExtra = extras,
                cache = cache,
            )

            val app = parseApp(JSONObject(json), "com.extra.app")
            app.getString("Ssaid") shouldBe "abc-123"
            app.getJSONObject("permissions").getBoolean("android.permission.INTERNET") shouldBe true
            app.getString("keystore") shouldBe "true"
        }

        test("perAppExtra 的 sizes 被包装为 {Size: value} 对象") {
            val cache = mockk<AppInfoCache>()
            coEvery { cache.getVersionCode("com.sizes.app") } returns null
            coEvery { cache.getApkPaths("com.sizes.app") } returns emptyList()

            val extras = mapOf(
                "com.sizes.app" to AppDetailsBuilder.PerAppExtra(
                    userSize = 100L,
                    userDeSize = 200L,
                    dataSize = 300L,
                    obbSize = 400L,
                ),
            )

            val json = AppDetailsBuilder.buildAppDetailsJson(
                apps = listOf(appInfo("com.sizes.app")),
                perAppExtra = extras,
                cache = cache,
            )

            val app = parseApp(JSONObject(json), "com.sizes.app")
            app.getJSONObject("user").getString("Size") shouldBe "100"
            app.getJSONObject("user_de").getString("Size") shouldBe "200"
            app.getJSONObject("data").getString("Size") shouldBe "300"
            app.getJSONObject("obb").getString("Size") shouldBe "400"
        }

        test("perAppExtra 字段为 null 或 false 时被省略") {
            val cache = mockk<AppInfoCache>()
            coEvery { cache.getVersionCode("com.minimal.app") } returns null
            coEvery { cache.getApkPaths("com.minimal.app") } returns emptyList()

            val extras = mapOf(
                "com.minimal.app" to AppDetailsBuilder.PerAppExtra(),
            )

            val json = AppDetailsBuilder.buildAppDetailsJson(
                apps = listOf(appInfo("com.minimal.app")),
                perAppExtra = extras,
                cache = cache,
            )

            val app = parseApp(JSONObject(json), "com.minimal.app")
            app.has("Ssaid") shouldBe false
            app.has("permissions") shouldBe false
            app.has("keystore") shouldBe false
            app.has("user") shouldBe false
        }
    }

    context("cache 命中 vs 回退") {

        test("cache 命中时使用缓存的版本号和 APK 路径") {
            val cache = mockk<AppInfoCache>()
            coEvery { cache.getVersionCode("com.cache.app") } returns "999"
            coEvery { cache.getApkPaths("com.cache.app") } returns listOf("/cache/split.apk")
            coEvery { RootShell.exec("stat -c%s '/cache/split.apk'") } returns RootShell.ShellResult("5555", "", 0)
            // 回退路径返回错误值，若被使用则测试会失败
            coEvery { RootShell.exec("dumpsys package 'com.cache.app' | grep versionCode | head -1") } returns
                RootShell.ShellResult("versionCode=000", "", 0)
            coEvery { AppScanner.getApkPaths("com.cache.app") } returns listOf("/wrong/path.apk")

            val json = AppDetailsBuilder.buildAppDetailsJson(
                apps = listOf(appInfo("com.cache.app", label = "Cache")),
                cache = cache,
            )

            val app = parseApp(JSONObject(json), "com.cache.app")
            app.getString("apk_version") shouldBe "999"
            app.getJSONArray("apkSizes").getLong(0) shouldBe 5555L
        }

        test("cache 缺失时回退到 RootShell.exec 与 AppScanner.getApkPaths") {
            coEvery { RootShell.exec("dumpsys package 'com.fallback.app' | grep versionCode | head -1") } returns
                RootShell.ShellResult("versionCode=42 targetSdk=34", "", 0)
            coEvery { AppScanner.getApkPaths("com.fallback.app") } returns listOf("/fallback/a.apk", "/fallback/b.apk")
            coEvery { RootShell.exec("stat -c%s '/fallback/a.apk'") } returns RootShell.ShellResult("111", "", 0)
            coEvery { RootShell.exec("stat -c%s '/fallback/b.apk'") } returns RootShell.ShellResult("222", "", 0)

            val json = AppDetailsBuilder.buildAppDetailsJson(
                apps = listOf(appInfo("com.fallback.app", label = "Fallback")),
                cache = null,
            )

            val app = parseApp(JSONObject(json), "com.fallback.app")
            app.getString("apk_version") shouldBe "42"
            app.getJSONArray("apkSizes").toLongList() shouldContainExactly listOf(111L, 222L)
        }
    }

    context("JSON 输出格式") {

        test("使用 2 空格缩进") {
            val cache = mockk<AppInfoCache>()
            coEvery { cache.getVersionCode("com.format.app") } returns null
            coEvery { cache.getApkPaths("com.format.app") } returns emptyList()

            val json = AppDetailsBuilder.buildAppDetailsJson(
                apps = listOf(appInfo("com.format.app")),
                cache = cache,
            )

            json shouldContain "\n  \""
            json shouldNotBe "{\"com.format.app\":{..."
        }
    }
})

private fun org.json.JSONArray.toLongList(): List<Long> =
    (0 until length()).map { getLong(it) }
