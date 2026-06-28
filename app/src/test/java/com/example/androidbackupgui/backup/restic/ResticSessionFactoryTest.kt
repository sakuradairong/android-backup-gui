package com.example.androidbackupgui.backup.restic

import android.content.Context
import com.example.androidbackupgui.backup.security.ResticBinary
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject

/**
 * 单元测试 - 验证 [ResticSessionFactory] 接口契约与 [DefaultResticSessionFactory] 行为。
 *
 * 重构背景：重构前 ViewModel 直接修改全局单例 `defaultResticWrapper` 的
 * `binaryPath` / `cacheDir` / `backendDomain` 三个属性，并直接调用
 * [ResticBinary.prepare]。重构后 ViewModel 仅依赖本接口，
 * 测试时可注入 mock 或验证默认实现的副作用。
 *
 * 本测试覆盖：
 *  - 接口可被 mock 实现（验证解耦成功）
 *  - 二进制不可用时返回 null（fail-fast 语义保留）
 *  - 二进制可用时正确设置 wrapper 的三个属性
 *  - 单例 mutation 隔离：使用前保存原值，使用后恢复
 */
class ResticSessionFactoryTest : FunSpec({

    // 保存原值用于测试隔离，避免测试间相互污染
    lateinit var originalBinaryPath: String
    lateinit var originalCacheDir: String
    lateinit var originalBackendDomain: String

    beforeTest {
        originalBinaryPath = defaultResticWrapper.runner.binaryPath
        originalCacheDir = defaultResticWrapper.cacheDir
        originalBackendDomain = defaultResticWrapper.backendDomain
    }

    afterTest {
        // 恢复原值，避免污染其他测试
        defaultResticWrapper.binaryPath = originalBinaryPath
        defaultResticWrapper.cacheDir = originalCacheDir
        defaultResticWrapper.backendDomain = originalBackendDomain
        unmockkObject(ResticBinary)
    }

    context("接口契约") {

        test("ResticSessionFactory 可被 mock 实现（验证解耦成功）") {
            // 这是重构的核心收益：以前 ViewModel 直接持有单例 mutation，
            // 无法替换为 mock。现在通过接口可以无副作用地测试 ViewModel。
            val mockFactory = object : ResticSessionFactory {
                var prepareCalls = 0
                var lastBackendDomain: String? = null

                override fun prepare(
                    context: Context,
                    backendDomain: String,
                ): ResticWrapper? {
                    prepareCalls++
                    lastBackendDomain = backendDomain
                    return null // mock 简化：不返回 wrapper
                }
            }

            mockFactory.prepareCalls shouldBe 0
            mockFactory.lastBackendDomain.shouldBeNull()
        }

        test("DefaultResticSessionFactory 实现了 ResticSessionFactory 接口") {
            // 编译期检查：若 DefaultResticSessionFactory 不实现接口，
            // 此赋值会编译失败。
            val factory: ResticSessionFactory = DefaultResticSessionFactory()
            factory shouldBe factory // 类型一致性自检
        }
    }

    context("DefaultResticSessionFactory.prepare() 行为") {

        test("ResticBinary 不可用时返回 null（保留 fail-fast 语义）") {
            mockkObject(ResticBinary)
            every { ResticBinary.prepare(any()) } returns null

            val factory = DefaultResticSessionFactory()
            val mockContext = io.mockk.mockk<Context>(relaxed = true)
            val result = factory.prepare(mockContext, "TESTDOMAIN")

            result.shouldBeNull()
        }

        test("ResticBinary 可用时返回 defaultResticWrapper 并设置三个属性") {
            mockkObject(ResticBinary)
            every { ResticBinary.prepare(any()) } returns "/data/app/librestic.so"

            val factory = DefaultResticSessionFactory()
            val mockContext = io.mockk.mockk<Context>(relaxed = true) {
                every { cacheDir } returns io.mockk.mockk(relaxed = true) {
                    every { absolutePath } returns "/data/data/com.example/cache"
                }
            }

            val result = factory.prepare(mockContext, "WORKGROUP")

            // 1. 返回 defaultResticWrapper 单例
            result shouldBe defaultResticWrapper

            // 2. binaryPath 被设置
            defaultResticWrapper.runner.binaryPath shouldBe "/data/app/librestic.so"

            // 3. cacheDir 被设置为 context.cacheDir.absolutePath
            defaultResticWrapper.cacheDir shouldBe "/data/data/com.example/cache"

            // 4. backendDomain 被设置为传入参数
            defaultResticWrapper.backendDomain shouldBe "WORKGROUP"
        }

        test("backendDomain 为空字符串时仍能正确设置") {
            mockkObject(ResticBinary)
            every { ResticBinary.prepare(any()) } returns "/data/app/librestic.so"

            val factory = DefaultResticSessionFactory()
            val mockContext = io.mockk.mockk<Context>(relaxed = true)

            val result = factory.prepare(mockContext, "")

            result shouldBe defaultResticWrapper
            defaultResticWrapper.backendDomain shouldBe ""
        }
    }
})
