package com.example.androidbackupgui.backup

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.io.File
import kotlinx.serialization.Serializable


/**
 * Unified abstraction for remote file transport (SMB / WebDAV).
 * Replaces the rclone serve proxy with direct protocol implementations.
 */
interface RemoteTransport {

    @Serializable
    data class RemoteFileInfo(
        val name: String,
        val size: Long,
        val isDirectory: Boolean = false
    )

    @Serializable
    data class TransferProgress(
        val phase: String,
        val current: Int,
        val total: Int,
        val currentFile: String = ""
    )

    @Serializable
    data class ByteProgress(
        val bytesTransferred: Long,
        val totalBytes: Long,
        val currentFile: String
    )

    suspend fun upload(localPath: String, remotePath: String, onProgress: suspend (TransferProgress) -> Unit = {}, onByteProgress: suspend (ByteProgress) -> Unit = {}): AppResult<Unit>
    suspend fun download(remotePath: String, localPath: String, onProgress: suspend (TransferProgress) -> Unit = {}, onByteProgress: suspend (ByteProgress) -> Unit = {}): AppResult<Unit>

    /** List entries in a remote directory (files and subdirectories). */
    suspend fun listFiles(remoteDir: String): AppResult<List<RemoteFileInfo>>

    /** Create a directory and any missing parents on the remote. */
    suspend fun mkdirs(remotePath: String): AppResult<Unit>

    suspend fun delete(remotePath: String): AppResult<Unit>
    suspend fun exists(remotePath: String): AppResult<Boolean>

    companion object {
        private const val TAG = "RemoteTransport"
        private const val MAX_RETRIES = 3

        /**
         * Returns true if the exception indicates a transient error worth retrying
         * (network blip, DNS hiccup, server 5xx), false for permanent errors (4xx).
         */
        private fun isTransientError(e: Exception): Boolean {
            val msg = (e.message ?: "") + (e.cause?.message ?: "")
            // DNS / network-layer failures
            if (msg.contains("Unable to resolve host", ignoreCase = true)) return true
            if (msg.contains("No address associated", ignoreCase = true)) return true
            if (msg.contains("ConnectException", ignoreCase = true)) return true
            if (msg.contains("SocketTimeoutException", ignoreCase = true)) return true
            if (msg.contains("timeout", ignoreCase = true)) return true
            if (msg.contains("Connection refused", ignoreCase = true)) return true
            if (msg.contains("Network is unreachable", ignoreCase = true)) return true
            // 5xx server errors (502 Bad Gateway, 503 Service Unavailable, 504 Gateway Timeout)
            if (Regex("\\b5\\d{2}\\b").containsMatchIn(msg)) return true
            return false
        }

        /**
         * Execute [block] with retries on transient failures.
         * Uses exponential backoff: 1s, 2s, 4s.
         */
        private suspend fun <T> withRetry(
            tag: String,
            block: suspend () -> AppResult<T>
        ): AppResult<T> {
            var lastError: AppResult<T>? = null
            for (attempt in 0..MAX_RETRIES) {
                if (attempt > 0) {
                    val waitMs = 1000L * (1 shl (attempt - 1)) // 1s, 2s, 4s
                    Log.w(TAG, "$tag retry $attempt/$MAX_RETRIES after ${waitMs}ms")
                    delay(waitMs)
                }
                val result = block()
                if (result.isSuccess) return result
                val err = result.exceptionOrNull()
                if (err != null && err is Exception && isTransientError(err)) {
                    lastError = result
                    continue
                }
                return result // permanent error — don't retry
            }
            return lastError ?: err(AppError.Remote("$tag: max retries exceeded", "retry"))
        }

        fun create(
            backend: String,
            url: String,
            user: String,
            pass: String,
            share: String,
            domain: String = ""
        ): RemoteTransport? {
            return when (backend) {
                "webdav" -> {
                    val baseUrl = url.trimEnd('/')
                    WebdavTransport(baseUrl, user, pass)
                }
                "smb" -> {
                    val host = url.trimEnd('/')
                    SmbTransport(host, share, user, pass, domain)
                }
                else -> null
            }
        }

        /**
         * Download all files from remote [remoteDir] into [localDir] recursively,
         * skipping files that already exist locally with the same size.
         * Deletes local files no longer present on the remote.
         * Returns failure if any download fails.
         */
        suspend fun syncFromRemote(
            transport: RemoteTransport,
            localDir: File,
            remoteDir: String,
            onProgress: suspend (TransferProgress) -> Unit = {},
            onByteProgress: suspend (ByteProgress) -> Unit = {}
        ): AppResult<Unit> = withContext(Dispatchers.IO) {
            try {
                localDir.mkdirs()
                val remoteFiles = listRemoteRecursive(transport, remoteDir)
                // Root dir not found (404): treat as empty remote — nothing to download.
                // This is normal for first-time init where the repo doesn't exist yet.
                if (remoteFiles == null) {
                    Log.w(TAG, "syncFromRemote: remote dir '$remoteDir' not accessible, treating as empty")
                    return@withContext AppResult.Success(Unit)
                }
                onProgress(TransferProgress("list", 0, remoteFiles.size))
                val remoteByPath = remoteFiles.associateBy { it.path }
                val errors = mutableListOf<String>()

                // Download remote files that are new or have different size
                var transferred = 0
                var skipped = 0
                val syncTotal = remoteFiles.size
                for ((relPath, info) in remoteByPath) {
                    val localFile = File(localDir, relPath)
                    if (localFile.isFile && localFile.length() == info.size) {
                        Log.d(TAG, "syncFromRemote skip (same size): $relPath")
                        skipped++
                        continue
                    }
                    transferred++
                    onProgress(TransferProgress("download", transferred, syncTotal, relPath))
                    localFile.parentFile?.mkdirs()
                    val fullRemotePath = "$remoteDir/$relPath"
                    Log.i(TAG, "syncFromRemote downloading: $fullRemotePath (${info.size} bytes)")
                    val result = withRetry("download($fullRemotePath)") {
                        transport.download(fullRemotePath, localFile.absolutePath, onProgress, onByteProgress)
                    }
                    if (result.isFailure) {
                        errors.add("$fullRemotePath: ${result.exceptionOrNull()?.message}")
                    }
                }

                // If any download failed, abort before deleting local files —
                // deleting would destroy valid data for an incomplete sync.
                if (errors.isNotEmpty()) {
                    return@withContext err(AppError.Remote("syncFromRemote: ${errors.size} file(s) failed: ${errors.joinToString("; ")}", "sync"))
                }

                // Delete local files not on remote (e.g. after prune on another client)
                val localFiles = walkLocalFiles(localDir)
                val staleLocalPaths = localFiles.keys.filter { it !in remoteByPath }
                val staleCount = staleLocalPaths.size
                for ((staleIdx, relPath) in staleLocalPaths.withIndex()) {
                    onProgress(TransferProgress("delete_stale", staleIdx + 1, staleCount))
                    val localFile = localFiles[relPath] ?: continue
                    Log.i(TAG, "syncFromRemote deleting stale local: $relPath")
                    try { localFile.delete() } catch (_: Exception) {}
                }
                onProgress(TransferProgress("complete", transferred, syncTotal, "已传输: $transferred 跳过: $skipped"))
                AppResult.Success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                err(AppError.Remote("syncFromRemote failed: ${e.message}", "sync", cause = e))
            }
        }

        /**
         * Upload all files from [localDir] into [remoteDir] recursively,
         * skipping files that already exist remotely with the same size.
         * Deletes remote files that no longer exist locally.
         * Returns failure if any upload fails.
         */
        suspend fun syncToRemote(
            transport: RemoteTransport,
            localDir: File,
            remoteDir: String,
            onProgress: suspend (TransferProgress) -> Unit = {},
            onByteProgress: suspend (ByteProgress) -> Unit = {}
        ): AppResult<Unit> = withContext(Dispatchers.IO) {
            try {
                val localFiles = walkLocalFiles(localDir)
                onProgress(TransferProgress("list", 0, localFiles.size))
                val remoteResult = listRemoteRecursive(transport, remoteDir)
                // If the remote dir is not accessible (404 or network error), treat as empty.
                // Any real upload errors will surface during the actual file uploads below.
                if (remoteResult == null) {
                    Log.w(TAG, "syncToRemote: remote dir '$remoteDir' not accessible, treating as empty")
                }
                val remoteByPath = (remoteResult ?: emptyList()).associateBy { it.path }
                val errors = mutableListOf<String>()

                // Collect unique parent directories that need to exist on remote
                val remoteDirs = mutableSetOf<String>()
                for (relPath in localFiles.keys) {
                    val parent = relPath.substringBeforeLast("/", "")
                    if (parent.isNotEmpty()) remoteDirs.add(parent)
                }

                // Ensure all remote directories exist
                for (dir in remoteDirs) {
                    transport.mkdirs("$remoteDir/$dir")
                }

                // Upload new or changed local files
                var uploaded = 0
                var uploadSkipped = 0
                val syncTotal = localFiles.size
                for ((relPath, localFile) in localFiles) {
                    val remoteInfo = remoteByPath[relPath]
                    if (remoteInfo != null && remoteInfo.size == localFile.length()) {
                        Log.d(TAG, "syncToRemote skip (same size): $relPath")
                        uploadSkipped++
                        continue
                    }
                    uploaded++
                    onProgress(TransferProgress("upload", uploaded, syncTotal, relPath))
                    val fullRemotePath = "$remoteDir/$relPath"
                    Log.i(TAG, "syncToRemote uploading: $fullRemotePath (${localFile.length()} bytes)")
                    val result = withRetry("upload($fullRemotePath)") {
                        transport.upload(localFile.absolutePath, fullRemotePath, onProgress, onByteProgress)
                    }
                    if (result.isFailure) {
                        errors.add("$fullRemotePath: ${result.exceptionOrNull()?.message}")
                    }
                }

                // If any upload failed, abort before deleting remote files —
                // deleting during failed sync could lose the only copy on remote.
                if (errors.isNotEmpty()) {
                    return@withContext err(AppError.Remote("syncToRemote: ${errors.size} file(s) failed: ${errors.joinToString("; ")}", "sync"))
                }

                // Delete remote files no longer present locally
                val staleRemotePaths = remoteByPath.keys.filter { it !in localFiles }
                val staleCount = staleRemotePaths.size
                for ((staleIdx, relPath) in staleRemotePaths.withIndex()) {
                    onProgress(TransferProgress("delete_stale", staleIdx + 1, staleCount))
                    Log.i(TAG, "syncToRemote deleting stale: $relPath")
                    transport.delete("$remoteDir/$relPath")
                }
                onProgress(TransferProgress("complete", uploaded, syncTotal, "已传输: $uploaded 跳过: $uploadSkipped"))
                AppResult.Success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                err(AppError.Remote("syncToRemote failed: ${e.message}", "sync", cause = e))
            }
        }




        private data class FlatFileInfo(val path: String, val size: Long)

        /** Recursively list all files on the remote. Returns null on failure.
         * Depth-limited to avoid redundant requests on servers that report
         * files as directories or return self-referencing PROPFIND entries. */
        private const val MAX_RECURSE_DEPTH = 3

        private suspend fun listRemoteRecursive(
            transport: RemoteTransport,
            remoteDir: String
        ): List<FlatFileInfo>? {
            val result = mutableListOf<FlatFileInfo>()
            // Pair of (relativePath, depth)
            val dirsToVisit = mutableListOf("" to 0)

            while (dirsToVisit.isNotEmpty()) {
                val (subDir, depth) = dirsToVisit.removeLast()
                if (depth >= MAX_RECURSE_DEPTH) {
                    Log.w(TAG, "listRemoteRecursive: max depth $MAX_RECURSE_DEPTH reached at $remoteDir/$subDir")
                    continue
                }
                val fullDir = if (subDir.isEmpty()) remoteDir else "$remoteDir/$subDir"
                val listResult = withRetry("listFiles($fullDir)") {
                    transport.listFiles(fullDir)
                }
                if (listResult.isFailure) {
                    val err = listResult.errorOrNull()
                    // 404 on a subdirectory: directory doesn't exist, skip it silently.
                    // 404 on the root directory: fatal — the remote repo path may be wrong.
                    if (err?.isFileNotFound() == true) {
                        if (subDir.isEmpty()) {
                            Log.e(TAG, "listRemoteRecursive: root dir '$fullDir' returned 404 — repo may not exist or is rate-limited")
                            return null
                        }
                        Log.d(TAG, "listRemoteRecursive: $fullDir -> 404, skipping")
                        continue
                    }
                    Log.e(TAG, "listRemoteRecursive: listFiles FAILED for '$fullDir': ${err?.message}")
                    return null
                }
                val entries = listResult.getOrThrow()
                val parentName = subDir.substringAfterLast("/", subDir)

                for (entry in entries) {
                    val relPath = if (subDir.isEmpty()) entry.name else "$subDir/${entry.name}"
                    if (entry.isDirectory) {
                        // Skip self-referencing entries where the server returns
                        // the directory itself as a child (e.g. data/f9/ contains "f9")
                        if (entry.name == parentName) {
                            Log.d(TAG, "listRemoteRecursive skip self-ref: $relPath")
                            continue
                        }
                        dirsToVisit.add(relPath to depth + 1)
                    } else {
                        result.add(FlatFileInfo(relPath, entry.size))
                    }
                }
            }
            Log.i(TAG, "listRemoteRecursive: $remoteDir → ${result.size} files in ${result.map { it.path }.toSet().size} paths")
            return result
        }

        /** Walk the local directory tree, returning relative-path → File mapping for all files. */
        private fun walkLocalFiles(localDir: File): Map<String, File> {
            val result = mutableMapOf<String, File>()
            val dirsToVisit = mutableListOf(localDir)
            val basePath = localDir.absolutePath

            while (dirsToVisit.isNotEmpty()) {
                val dir = dirsToVisit.removeLast()
                for (file in dir.listFiles() ?: emptyArray()) {
                    if (file.isDirectory) {
                        dirsToVisit.add(file)
                    } else {
                        val relPath = file.absolutePath.removePrefix("$basePath/")
                        result[relPath] = file
                    }
                }
            }
            return result
        }
    }
}

/** Extension to check if an [AppError] represents a "not found" remote error. */
private fun AppError.isFileNotFound(): Boolean =
    this is AppError.Remote && this.isNotFound
