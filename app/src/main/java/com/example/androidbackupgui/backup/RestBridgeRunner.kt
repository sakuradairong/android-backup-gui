package com.example.androidbackupgui.backup

import android.util.Log
import java.io.File

/**
 * Manages [ResticRestBridge] lifecycle: create, start, stop, clean cache.
 *
 * Usage:
 * ```kotlin
 * bridgeRunner.withBridge(backend, url, user, pass, share, domain, repoPath) { bridgeUrl ->
 *     // RESTIC_REPOSITORY = bridgeUrl
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
        block: suspend (bridgeUrl: String) -> T
    ): T {
        if (backend == "local") {
            return block(repoPath)
        }

        // Reuse cached transport (same SMB session) for consistent cross-bridge visibility
        val key = "$backend|$backendUrl|$backendUser|$backendShare|$backendDomain"
        if (cachedTransportKey != key) {
            cachedTransport?.let { Log.d(TAG, "discarding stale cached transport") }
            val t = transportFactory(backend, backendUrl, backendUser, backendPass, backendShare, backendDomain)
                ?: return block(repoPath)
            cachedTransport = t
            cachedTransportKey = key
        }
        val transport = cachedTransport!!

        val remoteBase = buildRemoteBase(backend, backendUrl, backendShare, repoPath)
        val bridge = ResticRestBridge(transport, remoteBase, repoPath, cacheDir)

        try {
            bridge.start(0)
            val port = bridge.listeningPort
            if (port < 0) {
                throw IllegalStateException("REST bridge failed to bind a port")
            }
            val bridgeUrl = "rest:http://127.0.0.1:$port/$repoPath"
            Log.i(TAG, "REST bridge started on port $port for $remoteBase")
            return block(bridgeUrl)
        } finally {
            try {
                bridge.stop()
            } catch (_: Exception) {}
            Log.d(TAG, "REST bridge stopped")
            // Clean up any leftover blob temp files
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
