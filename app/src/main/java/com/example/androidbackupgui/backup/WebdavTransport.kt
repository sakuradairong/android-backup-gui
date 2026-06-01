package com.example.androidbackupgui.backup

import android.util.Log
import com.thegrizzlylabs.sardineandroid.Sardine
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import com.thegrizzlylabs.sardineandroid.impl.SardineException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

class WebdavTransport(
    private val baseUrl: String,
    private val username: String,
    private val password: String,
    private val bufferSize: Int = 8192
): RemoteTransport {

    companion object { private const val TAG = "WebdavTransport" }

    private val sardine: Sardine by lazy {
        OkHttpSardine().apply {
            if (username.isNotEmpty()) {
                setCredentials(username, password)
            }
        }
    }

    private fun buildUrl(path: String): String {
        val cleanPath = path.trimStart('/')
        return "$baseUrl/$cleanPath"
    }

    override suspend fun upload(localPath: String, remotePath: String, onProgress: suspend (RemoteTransport.TransferProgress) -> Unit, onByteProgress: suspend (RemoteTransport.ByteProgress) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val url = buildUrl(remotePath)
                val file = File(localPath)
                val fileSize = file.length()
                if (fileSize > 50 * 1024 * 1024L) {
                    return@withContext Result.failure(
                        Exception("WebDAV upload: file too large (${fileSize / 1024 / 1024}MB), max 50MB")
                    )
                }
                Log.d(TAG, "upload $localPath -> $url ($fileSize bytes)")
                onProgress(RemoteTransport.TransferProgress("connecting", 0, 1, remotePath))
                // Read file into ByteArray with progress (sardine.put lacks InputStream variant)
                val data = file.inputStream().buffered(bufferSize).use { input ->
                    onProgress(RemoteTransport.TransferProgress("transferring", 0, 1, remotePath))
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(bufferSize)
                    var totalRead = 0L
                    var n = input.read(buffer)
                    while (n != -1) {
                        out.write(buffer, 0, n)
                        totalRead += n
                        onByteProgress(RemoteTransport.ByteProgress(totalRead, fileSize, remotePath))
                        n = input.read(buffer)
                    }
                    out.toByteArray()
                }
                sardine.put(url, data, "application/octet-stream")
                onProgress(RemoteTransport.TransferProgress("completed", 1, 1, remotePath))
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "upload failed: $remotePath", e)
                Result.failure(Exception("WebDAV upload failed: ${e.message}", e))
            }
        }

    override suspend fun download(remotePath: String, localPath: String, onProgress: suspend (RemoteTransport.TransferProgress) -> Unit, onByteProgress: suspend (RemoteTransport.ByteProgress) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val url = buildUrl(remotePath)
                val localFile = File(localPath)
                localFile.parentFile?.mkdirs()
                onProgress(RemoteTransport.TransferProgress("connecting", 0, 1, remotePath))
                sardine.get(url).use { input ->
                    localFile.outputStream().use { output ->
                        onProgress(RemoteTransport.TransferProgress("transferring", 0, 1, remotePath))
                        val buffer = ByteArray(bufferSize)
                        var totalRead = 0L
                        var n = input.read(buffer)
                        while (n != -1) {
                            output.write(buffer, 0, n)
                            totalRead += n
                            onByteProgress(RemoteTransport.ByteProgress(totalRead, 0, remotePath))
                            n = input.read(buffer)
                        }
                    }
                }
                onProgress(RemoteTransport.TransferProgress("completed", 1, 1, remotePath))
                Log.d(TAG, "download $url -> $localPath (${localFile.length()} bytes)")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "download failed: $remotePath", e)
                Result.failure(Exception("WebDAV download failed: ${e.message}", e))
            }
        }
    override suspend fun listFiles(remoteDir: String): Result<List<RemoteTransport.RemoteFileInfo>> =
        withContext(Dispatchers.IO) {
            try {
                val url = buildUrl(remoteDir)
                val resources = sardine.list(url)
                // Also filter out the directory itself (href matches request URL)
                val urlPath = url.replace(Regex("/+$"), "")
                val entries = resources
                    .filter { r ->
                        val name = r.name
                        val href = r.href?.toString()?.replace(Regex("/+$"), "") ?: ""
                        name != "." && name != ".." && href != urlPath
                    }
                    .map { RemoteTransport.RemoteFileInfo(
                        name = it.name,
                        size = it.contentLength,
                        isDirectory = it.isDirectory
                    ) }
                Log.d(TAG, "listFiles $remoteDir -> ${entries.size} entries")
                Result.success(entries)
            } catch (e: Exception) {
                // Only treat 404 as empty for non-root paths; the caller (listRemoteRecursive)
                // handles the distinction. We propagate the error so the caller can decide.
                val is404 = e is SardineException && e.statusCode == 404
                if (is404) {
                    // Return a failure with a distinguishable marker so callers can check
                    Log.d(TAG, "listFiles $remoteDir -> 404 (not found)")
                    return@withContext Result.failure(FileNotFoundException(remoteDir))
                }
                Log.e(TAG, "listFiles failed: $remoteDir", e)
                Result.failure(Exception("WebDAV list failed: ${e.message}", e))
            }
        }

    override suspend fun mkdirs(remotePath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val parts = remotePath.trimStart('/').split("/")
                var current = ""
                for (part in parts) {
                    current = if (current.isEmpty()) part else "$current/$part"
                    try { sardine.createDirectory(buildUrl(current)) }
                    catch (_: Exception) { /* already exists or parent missing, continue */ }
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Log.w(TAG, "mkdirs failed: $remotePath — ${e.message}")
                Result.success(Unit)  // best-effort; upload will fail if dir can't be created
            }
        }

    override suspend fun delete(remotePath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val url = buildUrl(remotePath)
                sardine.delete(url)
                Result.success(Unit)
            } catch (e: Exception) {
                Log.w(TAG, "delete failed (ignoring): $remotePath — ${e.message}")
                Result.success(Unit)
            }
        }

    override suspend fun exists(remotePath: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val result = sardine.exists(buildUrl(remotePath))
                Result.success(result)
            } catch (e: Exception) {
                Result.failure(Exception("WebDAV exists check failed: ${e.message}", e))
            }
        }
}
