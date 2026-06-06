package com.example.androidbackupgui.backup

import android.util.Log
import java.io.File
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
    private var cachedTransport: RemoteTransport? = null
    private var cachedTransportKey: String? = null

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
            domain: String
        ) -> RemoteTransport? = ::createTransport,
        block: suspend (bridgeUrl: String, authToken: String) -> T
    ): T {
        if (backend == "local") {
            return block(repoPath, "")
        }

        val authToken = UUID.randomUUID().toString().replace("-", "").take(32)

        val key = "$backend|$backendUrl|$backendUser|$backendShare|$backendDomain"
        if (cachedTransportKey != key) {
            cachedTransport?.let { Log.d(TAG, "discarding stale cached transport") }
            val t = transportFactory(backend, backendUrl, backendUser, backendPass, backendShare, backendDomain)
                ?: return block(repoPath, "")
            cachedTransport = t
            cachedTransportKey = key
        }
        val transport = cachedTransport!!

        val remoteBase = buildRemoteBase(backend, backendUrl, backendShare, repoPath)
        val bridge = ResticRestBridge(transport, remoteBase, repoPath, cacheDir, authToken)

        try {
            bridge.start(0)
            val port = bridge.listeningPort
            if (port < 0) {
                throw IllegalStateException("REST bridge failed to bind a port")
            }
            val bridgeUrl = "rest:http://127.0.0.1:$port/$repoPath"
            Log.i(TAG, "REST bridge started on port $port for $remoteBase (auth=${authToken.take(8)}…)")
            return block(bridgeUrl, authToken)
        } finally {
            try {
                bridge.stop()
            } catch (_: Exception) {}
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
        repoPath: String
    ): String {
        return when (backend) {
            "smb" -> "smb://${backendUrl.trimEnd('/')}/$backendShare/$repoPath"
            "webdav" -> "${backendUrl.trimEnd('/')}/${repoPath.trimStart('/')}"
            else -> repoPath
        }
    }

    companion object {
        /** Default transport factory: delegates to [RemoteTransport.create]. */
        fun createTransport(
            backend: String,
            url: String,
            user: String,
            pass: String,
            share: String,
            domain: String
        ): RemoteTransport? {
            return RemoteTransport.create(backend, url, user, pass, share, domain)
        }
    }
}
