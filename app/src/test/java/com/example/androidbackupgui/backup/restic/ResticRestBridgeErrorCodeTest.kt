package com.example.androidbackupgui.backup.restic

import com.example.androidbackupgui.backup.core.AppError
import com.example.androidbackupgui.backup.core.AppResult
import fi.iki.elonen.NanoHTTPD
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import java.io.File

/**
 * 验证 [ResticRestBridge] 对后端错误的 HTTP 状态码映射。
 *
 * 关键场景：当 RemoteTransport 返回 AppResult.Failure 时，
 * 只有 AppError.Remote(isNotFound = true) 应该映射为 404；
 * 其他后端错误（网络、IO、鉴权等）应该映射为 500，
 * 避免 restic 把后端错误误判为"blob 不存在"。
 */
class ResticRestBridgeErrorCodeTest : FunSpec({

    val cacheDir = File(System.getProperty("java.io.tmpdir"), "restic_bridge_test_${System.nanoTime()}")
    val transport = mockk<RemoteTransport>()

    beforeTest {
        cacheDir.mkdirs()
    }

    afterTest {
        cacheDir.deleteRecursively()
    }

    fun createBridge(repoPath: String = "backup"): ResticRestBridge {
        return ResticRestBridge(transport, "/remote/base", repoPath, cacheDir)
    }

    fun invokeHandleConfig(method: NanoHTTPD.Method): NanoHTTPD.Response {
        val bridge = createBridge()
        val handleConfig = ResticRestBridge::class.java.getDeclaredMethod(
            "handleConfig",
            NanoHTTPD.Method::class.java,
            Map::class.java,
            NanoHTTPD.IHTTPSession::class.java,
        )
        handleConfig.isAccessible = true
        return handleConfig.invoke(bridge, method, emptyMap<String, String>(), null) as NanoHTTPD.Response
    }

    fun invokeHandleListBlobs(type: String = "data"): NanoHTTPD.Response {
        val bridge = createBridge()
        val method = ResticRestBridge::class.java.getDeclaredMethod(
            "handleListBlobs",
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(bridge, type) as NanoHTTPD.Response
    }

    fun invokeHandleHeadBlob(type: String = "data", name: String = "blob"): NanoHTTPD.Response {
        val bridge = createBridge()
        val method = ResticRestBridge::class.java.getDeclaredMethod(
            "handleHeadBlob",
            String::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(bridge, type, name) as NanoHTTPD.Response
    }

    fun invokeHandleGetBlob(type: String = "data", name: String = "blob"): NanoHTTPD.Response {
        val bridge = createBridge()
        val method = ResticRestBridge::class.java.getDeclaredMethod(
            "handleGetBlob",
            String::class.java,
            String::class.java,
            Map::class.java,
        )
        method.isAccessible = true
        return method.invoke(bridge, type, name, emptyMap<String, String>()) as NanoHTTPD.Response
    }

    context("handleConfig GET") {
        test("AppError.Remote(isNotFound=true) 返回 404") {
            coEvery { transport.download(any(), any(), any(), any()) } returns AppResult.Failure(
                AppError.Remote("not found", "download", isNotFound = true),
            )

            val response = invokeHandleConfig(NanoHTTPD.Method.GET)
            response.status shouldBe NanoHTTPD.Response.Status.NOT_FOUND
        }

        test("AppError.Remote(isNotFound=false) 返回 500") {
            coEvery { transport.download(any(), any(), any(), any()) } returns AppResult.Failure(
                AppError.Remote("backend error", "download", isNotFound = false),
            )

            val response = invokeHandleConfig(NanoHTTPD.Method.GET)
            response.status shouldBe NanoHTTPD.Response.Status.INTERNAL_ERROR
        }
    }

    context("handleConfig HEAD fallback") {
        test("exists 返回 false 时直接返回 404") {
            coEvery { transport.exists(any()) } returns AppResult.Success(false)

            val response = invokeHandleConfig(NanoHTTPD.Method.HEAD)
            response.status shouldBe NanoHTTPD.Response.Status.NOT_FOUND
        }

        test("exists 失败且 download 返回 isNotFound=true 时返回 404") {
            coEvery { transport.exists(any()) } returns AppResult.Failure(
                AppError.Remote("exists failed", "exists", isNotFound = false),
            )
            coEvery { transport.download(any(), any(), any(), any()) } returns AppResult.Failure(
                AppError.Remote("not found", "download", isNotFound = true),
            )

            val response = invokeHandleConfig(NanoHTTPD.Method.HEAD)
            response.status shouldBe NanoHTTPD.Response.Status.NOT_FOUND
        }

        test("exists 失败且 download 返回 isNotFound=false 时返回 500") {
            coEvery { transport.exists(any()) } returns AppResult.Failure(
                AppError.Remote("exists failed", "exists", isNotFound = false),
            )
            coEvery { transport.download(any(), any(), any(), any()) } returns AppResult.Failure(
                AppError.Remote("backend error", "download", isNotFound = false),
            )

            val response = invokeHandleConfig(NanoHTTPD.Method.HEAD)
            response.status shouldBe NanoHTTPD.Response.Status.INTERNAL_ERROR
        }
    }

    context("handleListBlobs") {
        test("AppError.Remote(isNotFound=true) 返回 404") {
            coEvery { transport.listFiles(any()) } returns AppResult.Failure(
                AppError.Remote("not found", "list", isNotFound = true),
            )

            val response = invokeHandleListBlobs()
            response.status shouldBe NanoHTTPD.Response.Status.NOT_FOUND
        }

        test("AppError.Remote(isNotFound=false) 返回 500") {
            coEvery { transport.listFiles(any()) } returns AppResult.Failure(
                AppError.Remote("backend error", "list", isNotFound = false),
            )

            val response = invokeHandleListBlobs()
            response.status shouldBe NanoHTTPD.Response.Status.INTERNAL_ERROR
        }
    }

    context("handleHeadBlob") {
        test("AppError.Remote(isNotFound=true) 返回 404") {
            coEvery { transport.exists(any()) } returns AppResult.Failure(
                AppError.Remote("not found", "exists", isNotFound = true),
            )

            val response = invokeHandleHeadBlob()
            response.status shouldBe NanoHTTPD.Response.Status.NOT_FOUND
        }

        test("AppError.Remote(isNotFound=false) 返回 500") {
            coEvery { transport.exists(any()) } returns AppResult.Failure(
                AppError.Remote("backend error", "exists", isNotFound = false),
            )

            val response = invokeHandleHeadBlob()
            response.status shouldBe NanoHTTPD.Response.Status.INTERNAL_ERROR
        }
    }

    context("handleGetBlob") {
        test("AppError.Remote(isNotFound=true) 返回 404") {
            coEvery { transport.download(any(), any(), any(), any()) } returns AppResult.Failure(
                AppError.Remote("not found", "download", isNotFound = true),
            )

            val response = invokeHandleGetBlob()
            response.status shouldBe NanoHTTPD.Response.Status.NOT_FOUND
        }

        test("AppError.Remote(isNotFound=false) 返回 500") {
            coEvery { transport.download(any(), any(), any(), any()) } returns AppResult.Failure(
                AppError.Remote("backend error", "download", isNotFound = false),
            )

            val response = invokeHandleGetBlob()
            response.status shouldBe NanoHTTPD.Response.Status.INTERNAL_ERROR
        }
    }
})
