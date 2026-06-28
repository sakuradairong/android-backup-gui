package com.example.androidbackupgui.backup.restic

import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Manages [ResticRestBridge] lifecycle: create, start, stop, clean cache.
 *
 * Usage:
 * bridgeRunner.withBridge(backend, url, user, pass, share, domain, repoPath) { bridgeUrl, authToken ->
 *     // RESTIC_REPOSITORY = bridgeUrl
 *     // RESTIC_REST_USERNAME/PASSWORD = authToken (set via buildBridgeEnv)
 *     restic commands go here
 * }
 * // bridge stopped + cache cleaned automatically
 * ```
 */
class RestBridgeRunner {
    private val TAG = "RestBridgeRunner"

    /** Cached transport to reuse SMB sessions across bridge instances. */
    @Volatile
    private var cachedTransport: RemoteTransport? = null

    @Volatile
    private var cachedTransportKey: String? = null
    private val cacheLock = Any()

    /**
     * Start a REST bridge for the given [backend], execute [block] with the
     * bridge URL, then stop and clean up.
     *
     * For [backend] == "local", the bridge is not started and [block] receives
     * `null`.
     */
    suspend fun <T> withBridge(
        backend: String,
        backendUrl: String,
        backendUser: String,
        backendPass: String,
        backendShare: String,
        backendDomain: String,
        repoPath: String,
        cacheDir: File,
        transportFactory: (
            backend: String,
            url: String,
            user: String,
            pass: String,
            share: String,
            domain: String,
        ) -> RemoteTransport? = ::createTransport,
        block: suspend (bridgeUrl: String, authToken: String) -> T,
    ): T {
        if (backend == "local") {
            return block(repoPath, "")
        }

        val authToken =
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(32)

        // 审查报告 L2：缓存 key 不应包含明文密码（内存转储/崩溃报告泄漏面）。
        // 用密码的 SHA-256 摘要参与判等，保留“密码变化即失效缓存”语义。
        val passDigest = sha256Hex(backendPass)
        val key = "$backend|$backendUrl|$backendUser|$passDigest|$backendShare|$backendDomain"
        // 审查报告 L3：并发 restic 调用可能跨线程读写缓存，加锁保护。
        val transport =
            synchronized(cacheLock) {
                if (cachedTransportKey != key) {
                    cachedTransport?.let { Log.d(TAG, "discarding stale cached transport") }
                    val t =
                        transportFactory(backend, backendUrl, backendUser, backendPass, backendShare, backendDomain)
                            ?: throw IllegalArgumentException("Unsupported remote backend: $backend")
                    cachedTransport = t
                    cachedTransportKey = key
                }
                cachedTransport!!
            }

        val remoteBase = buildRemoteBase(backend, backendUrl, backendShare, repoPath)
        val bridge = ResticRestBridge(transport, remoteBase, repoPath, cacheDir, authToken)
        val healthChecker = RestBridgeHealthChecker()

        try {
            bridge.start(0)
            val port = bridge.listeningPort
            if (port < 0) {
                throw IllegalStateException("REST bridge failed to bind a port")
            }

            // 健康检查：等待桥接器就绪（携带 authToken，避免 401 误判为未就绪）
            Log.i(TAG, "REST bridge started on port $port, waiting for health check...")
            val isReady = healthChecker.waitForReady(port, maxWaitMs = 10000, authToken = authToken)
            if (!isReady) {
                throw IllegalStateException("REST bridge did not become ready within 10000ms")
            } else {
                val latency = healthChecker.getLatency(port, authToken)
                Log.i(TAG, "REST bridge healthy, latency=${latency}ms")
            }

            val bridgeUrl = "rest:http://127.0.0.1:$port/$repoPath"
            Log.i(TAG, "REST bridge ready on port $port for $remoteBase")
            return block(bridgeUrl, authToken)
        } finally {
            try {
                bridge.stop()
            } catch (_: Exception) {
            }
            Log.d(TAG, "REST bridge stopped")
            val blobs = cacheDir.listFiles { f -> f.name.startsWith("restic_blob_") }
            if (blobs != null) {
                for (f in blobs) f.delete()
            }
        }
    }

    /** Build the remote base path for the REST bridge. */
    private fun buildRemoteBase(
        backend: String,
        backendUrl: String,
        backendShare: String,
        repoPath: String,
    ): String =
        when (backend) {
            "smb" -> "smb://${backendUrl.trimEnd('/')}/$backendShare/$repoPath"
            "webdav" -> "${backendUrl.trimEnd('/')}/${repoPath.trimStart('/')}"
            else -> repoPath
        }

    companion object {
        /** Default transport factory: delegates to [RemoteTransport.create]. */
        fun createTransport(
            backend: String,
            url: String,
            user: String,
            pass: String,
            share: String,
            domain: String,
        ): RemoteTransport? = RemoteTransport.create(backend, url, user, pass, share, domain)

        /**
         * 计算字符串 SHA-256 十六进制摘要。缓存 key 用于避免明文密码进内存比较/日志
         * （审查报告 L2）。空输入返回空字符串，保持判等语义。
         */
        private fun sha256Hex(input: String): String {
            if (input.isEmpty()) return ""
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
