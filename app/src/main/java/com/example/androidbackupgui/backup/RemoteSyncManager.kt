package com.example.androidbackupgui.backup

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Manages remote transport lifecycle (SMB/WebDAV) and local temp repo sync.
 *
 * For SMB/WebDAV backends, restic runs against a local temp directory;
 * [RemoteTransport] syncs files to/from the remote backend.
 *
 * All sync operations are serialized via [repoSyncMutex] so concurrent
 * operations don't corrupt the local temp repo.
 */
class RemoteSyncManager {

    private val TAG = "ResticWrapper"

    /** Local temp directory used as restic repo for SMB/WebDAV backends. */
    @Volatile
    var tempRepoDir: String = ""

    /** Domain for SMB NTLM authentication. */
    @Volatile
    var backendDomain: String = ""

    // ── Transport cache ──────────────────────────────────
    @Volatile private var transport: RemoteTransport? = null
    private var transportConfigKey: String = ""
    private val transportLock = Any()

    /** Serializes access to tempRepoDir so concurrent operations don't corrupt each other. */
    private val repoSyncMutex = Mutex()

    // ── Transport lifecycle ──────────────────────────────

    private fun ensureTransport(
        backend: String, url: String, user: String, pass: String, share: String, repoPath: String
    ): RemoteTransport? = synchronized(transportLock) {
        val key = "$backend|$url|$user|$pass|$share|$backendDomain|$repoPath"
        if (key != transportConfigKey || transport == null) {
            transport?.let { Log.i(TAG, "transport config changed ($transportConfigKey -> $key), recreating") }
            // Clear local temp repo when backend config changes so
            // syncFromRemote downloads fresh data from the new backend
            if (transportConfigKey.isNotEmpty() && tempRepoDir.isNotEmpty()) {
                val dir = File(tempRepoDir)
                val deleted = dir.deleteRecursively()
                Log.i(TAG, "cleared local temp repo: $tempRepoDir (deleted=$deleted)")
                dir.mkdirs()
            }
            transport = RemoteTransport.create(backend, url, user, pass, share, backendDomain)
            if (transport != null) {
                transportConfigKey = key
                Log.i(TAG, "transport created: $backend @ $url repo=$repoPath domain=$backendDomain")
            } else {
                Log.e(TAG, "transport creation failed for backend=$backend url=$url")
            }
        }
        return transport
    }

    // ── Temp dir lifecycle ───────────────────────────────

    /** Clean up local temp repo and cache directories. */
    private fun cleanupTempDirs() {
        if (tempRepoDir.isEmpty()) return
        try {
            val repoDir = File(tempRepoDir)
            if (repoDir.exists()) {
                val deleted = repoDir.deleteRecursively()
                Log.i(TAG, "cleanupTempDirs: deleted $tempRepoDir ($deleted)")
            }
            val cacheDir = File(tempRepoDir.substringBeforeLast("/") + "/restic_cache")
            if (cacheDir.exists()) {
                val deleted = cacheDir.deleteRecursively()
                Log.i(TAG, "cleanupTempDirs: deleted cache $cacheDir ($deleted)")
            }
            val tmpDir = File(tempRepoDir.substringBeforeLast("/") + "/restic_tmp")
            if (tmpDir.exists()) {
                val deleted = tmpDir.deleteRecursively()
                Log.i(TAG, "cleanupTempDirs: deleted tmp $tmpDir ($deleted)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "cleanupTempDirs failed: ${e.message}")
        }
    }


    /** True if [tempRepoDir] already contains an initialized restic repository (has a config file). */
    private fun isLocalRepoPopulated(): Boolean {
        if (tempRepoDir.isEmpty()) return false
        return File(tempRepoDir, "config").isFile
    }

    // ── Sync engine ──────────────────────────────────────

    /**
     * Execute [action] with remote repo synced before/after as needed.
     * For local/rest-server backends, executes [action] directly without sync.
     * Protected by [repoSyncMutex] so concurrent operations don't corrupt tempRepoDir.
     *
     * Cleanup strategy:
     * - Write ops (needsUpload=true): cleanup only on successful sync to remote.
     *   On syncToRemote failure the local repo is preserved so the next
     *   operation can retry — destroying it would lose the just-created snapshot.
     * - Read-only ops (needsUpload=false): keep local cache for subsequent operations.
     * - Read-only ops skip download entirely if local repo is already populated.
     */
    suspend fun <T> withRemoteSync(
        backend: String,
        backendUrl: String,
        backendUser: String,
        backendPass: String,
        backendShare: String,
        repoPath: String,
        needsDownload: Boolean,
        needsUpload: Boolean,
        onProgress: suspend (RemoteTransport.TransferProgress) -> Unit = {},
        onByteProgress: suspend (RemoteTransport.ByteProgress) -> Unit = {},
        action: suspend () -> Result<T>
    ): Result<T> {
        if (backend != "smb" && backend != "webdav") return action()

        return repoSyncMutex.withLock {
            var shouldCleanup = false
            try {
                val t = ensureTransport(backend, backendUrl, backendUser, backendPass, backendShare, repoPath)
                    ?: return@withLock Result.failure(Exception("Failed to create transport for backend: $backend"))

                val localDir = File(tempRepoDir)

                val emitProgress: suspend (RemoteTransport.TransferProgress) -> Unit = { p ->
                    withContext(Dispatchers.Main) { onProgress(p) }
                }

                // Write ops always download to avoid overwriting remote changes.
                // Read-only ops skip download if local repo is already present.
                val actualDownload = needsDownload && (needsUpload || !isLocalRepoPopulated())
                if (actualDownload) {
                    Log.i(TAG, "syncFromRemote start: $repoPath -> $tempRepoDir")
                    val syncResult = RemoteTransport.syncFromRemote(t, localDir, repoPath, emitProgress, onByteProgress)
                    if (syncResult.isFailure) {
                        shouldCleanup = true
                        Log.e(TAG, "syncFromRemote FAILED: ${syncResult.exceptionOrNull()?.message}")
                        return@withLock Result.failure(
                            Exception("syncFromRemote failed: ${syncResult.exceptionOrNull()?.message}")
                        )
                    }
                    Log.i(TAG, "syncFromRemote complete")
                } else if (needsDownload) {
                    Log.i(TAG, "syncFromRemote skipped: local repo already populated")
                }

                val result = action()

                if (needsUpload && result.isSuccess) {
                    Log.i(TAG, "syncToRemote start: $tempRepoDir -> $repoPath")
                    val uploadResult = RemoteTransport.syncToRemote(t, localDir, repoPath, emitProgress, onByteProgress)
                    if (uploadResult.isFailure) {
                        shouldCleanup = false  // PRESERVE local repo — snapshot would be lost
                        Log.e(TAG, "syncToRemote FAILED: ${uploadResult.exceptionOrNull()?.message} — local repo preserved for retry")
                        return@withLock Result.failure(
                            Exception("syncToRemote failed: ${uploadResult.exceptionOrNull()?.message}")
                        )
                    }
                    Log.i(TAG, "syncToRemote complete")
                    shouldCleanup = true
                } else if (result.isFailure) {
                    shouldCleanup = true
                }

                result
            } catch (e: CancellationException) {
                shouldCleanup = true
                throw e
            } catch (e: Exception) {
                shouldCleanup = true
                Result.failure(e)
            } finally {
                if (shouldCleanup) {
                    Log.i(TAG, "withRemoteSync: cleaning up temp dirs")
                    cleanupTempDirs()
                } else {
                    Log.d(TAG, "withRemoteSync: keeping local repo for subsequent ops")
                }
            }
        }
    }

    /**
     * Public safety-net cleanup called by fragment lifecycle.
     * Waits for any in-progress operation to finish, then deletes temp dirs.
     */
    suspend fun cleanup() {
        repoSyncMutex.withLock { cleanupTempDirs() }
    }
}
