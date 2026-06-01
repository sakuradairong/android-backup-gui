package com.example.androidbackupgui.backup

import android.util.Log
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbException
import jcifs.smb.SmbFile
import jcifs.smb.SmbFileInputStream
import jcifs.smb.SmbFileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties

class SmbTransport(
    private val host: String,
    private val share: String,
    private val username: String,
    private val password: String,
    private val domain: String = "",
    private val bufferSize: Int = 8192
): RemoteTransport {
    companion object { private const val TAG = "SmbTransport" }
    private val context: CIFSContext by lazy {
        val props = Properties().apply {
            // Force SMB 2.0.2 minimum — SMB1 is disabled on modern Windows
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            // Shorter timeouts for Android
            setProperty("jcifs.smb.client.responseTimeout", "15000")
            setProperty("jcifs.smb.client.connTimeout", "10000")
            // Enable SMB signing for security (prevents tampering)
            setProperty("jcifs.smb.client.signingEnabled", "true")
            // Prefer SMB 3.x encryption when available
            setProperty("jcifs.smb.client.encryptionEnabled", "true")
        }
        val base = BaseContext(PropertyConfiguration(props))
        if (username.isNotEmpty()) {
            base.withCredentials(NtlmPasswordAuthenticator(domain, username, password))
        } else {
            base
        }
    }

    private fun buildUrl(path: String): String {
        val cleanPath = path.trimStart('/')
        val sharePath = if (share.isNotEmpty()) "$share/$cleanPath" else cleanPath
        return "smb://$host/$sharePath"
    }

    private fun smbFile(path: String): SmbFile = SmbFile(buildUrl(path), context)

    override suspend fun upload(localPath: String, remotePath: String, onProgress: suspend (RemoteTransport.TransferProgress) -> Unit, onByteProgress: suspend (RemoteTransport.ByteProgress) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val localFile = File(localPath)
                val remote = smbFile(remotePath)
                // Ensure parent directories exist (parent can be null at share root)
                val parentPath = remote.parent
                if (parentPath != null) {
                    val parent = SmbFile(parentPath, context)
                    if (!parent.exists()) parent.mkdirs()
                }
                onProgress(RemoteTransport.TransferProgress("connecting", 0, 1, remotePath))
                val fileSize = localFile.length()
                SmbFileOutputStream(remote).use { output ->
                    localFile.inputStream().use { input ->
                        onProgress(RemoteTransport.TransferProgress("transferring", 0, 1, remotePath))
                        val buffer = ByteArray(bufferSize)
                        var totalRead = 0L
                        var n = input.read(buffer)
                        while (n != -1) {
                            output.write(buffer, 0, n)
                            totalRead += n
                            onByteProgress(RemoteTransport.ByteProgress(totalRead, fileSize, remotePath))
                            n = input.read(buffer)
                        }
                    }
                }
                onProgress(RemoteTransport.TransferProgress("completed", 1, 1, remotePath))
                Log.i(TAG, "upload $localPath -> ${buildUrl(remotePath)} ($fileSize bytes)")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "upload failed: ${buildUrl(remotePath)}", e)
                Result.failure(Exception("SMB upload failed: ${e.message}", e))
            }
        }

    override suspend fun download(remotePath: String, localPath: String, onProgress: suspend (RemoteTransport.TransferProgress) -> Unit, onByteProgress: suspend (RemoteTransport.ByteProgress) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val localFile = File(localPath)
                localFile.parentFile?.mkdirs()
                val remote = smbFile(remotePath)
                onProgress(RemoteTransport.TransferProgress("connecting", 0, 1, remotePath))
                val fileSize = remote.length()
                SmbFileInputStream(remote).use { input ->
                    localFile.outputStream().use { output ->
                        onProgress(RemoteTransport.TransferProgress("transferring", 0, 1, remotePath))
                        val buffer = ByteArray(bufferSize)
                        var totalRead = 0L
                        var n = input.read(buffer)
                        while (n != -1) {
                            output.write(buffer, 0, n)
                            totalRead += n
                            onByteProgress(RemoteTransport.ByteProgress(totalRead, fileSize, remotePath))
                            n = input.read(buffer)
                        }
                    }
                }
                onProgress(RemoteTransport.TransferProgress("completed", 1, 1, remotePath))
                Log.d(TAG, "download ${buildUrl(remotePath)} -> $localPath (${localFile.length()} bytes)")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "download failed: $remotePath", e)
                Result.failure(Exception("SMB download failed: ${e.message}", e))
            }
        }

    override suspend fun listFiles(remoteDir: String): Result<List<RemoteTransport.RemoteFileInfo>> =
        withContext(Dispatchers.IO) {
            try {
                val dir = smbFile(remoteDir)
                if (!dir.exists() || !dir.isDirectory) {
                    return@withContext Result.failure(FileNotFoundException(remoteDir))
                }
                // SmbFile.getName() in jcifs-ng 2.1.x is broken — it concatenates
                // parent-dir + filename without separator. Use the URL to extract
                // the real basename. Trim trailing '/' first (dir URLs end with '/',
                // which would give empty last-segment otherwise).
                // Fall back to f.getName() with parent-path stripping if URL fails.
                val entries = dir.listFiles()
                    ?.map { f ->
                        val urlStr = f.toString().trimEnd('/')  // smb://host/share/test/keys
                        val urlName = urlStr.substringAfterLast("/")
                        val name = if (urlName.isNotEmpty()) {
                            urlName
                        } else {
                            // URL parsing gave empty — fall back with heuristic strip
                            val raw = f.name
                            // jcifs-ng bug: getName() returns "key/appList.txt" instead of "appList.txt"
                            val idx = raw.lastIndexOf('/')
                            if (idx >= 0) raw.substring(idx + 1) else raw
                        }
                        RemoteTransport.RemoteFileInfo(
                            name = name,
                            size = if (f.isFile) f.length() else 0,
                            isDirectory = f.isDirectory
                        )
                    }
                    ?: emptyList()
                Log.d(TAG, "listFiles $remoteDir -> ${entries.size} entries: ${entries.joinToString { "${it.name}(${if (it.isDirectory) "d" else "f"},${it.size})" }}")
                Result.success(entries)
            } catch (e: SmbException) {
                if (e.ntStatus == 0xC0000034.toInt()) {
                    return@withContext Result.failure(FileNotFoundException(remoteDir))
                }
                Log.e(TAG, "listFiles failed: $remoteDir", e)
                Result.failure(Exception("SMB list failed: ${e.message}", e))
            } catch (e: Exception) {
                Log.e(TAG, "listFiles failed: $remoteDir", e)
                Result.failure(Exception("SMB list failed: ${e.message}", e))
            }
        }


    override suspend fun mkdirs(remotePath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val dir = smbFile(remotePath)
                if (!dir.exists()) dir.mkdirs()
                Result.success(Unit)
            } catch (e: SmbException) {
                // STATUS_OBJECT_NAME_COLLISION (0xC0000035): directory already exists — not an error
                if (e.ntStatus == 0xC0000035.toInt()) {
                    Result.success(Unit)
                } else {
                    Log.e(TAG, "mkdirs failed: $remotePath — ${e.message}")
                    Result.failure(Exception("SMB mkdirs failed: ${e.message}", e))
                }
            } catch (e: Exception) {
                Log.e(TAG, "mkdirs failed: $remotePath — ${e.message}")
                Result.failure(Exception("SMB mkdirs failed: ${e.message}", e))
            }
        }

    override suspend fun delete(remotePath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val file = smbFile(remotePath)
                if (file.exists()) file.delete()
                Result.success(Unit)
            } catch (e: SmbException) {
                // STATUS_OBJECT_NAME_NOT_FOUND (0xC0000034): file already gone — not an error
                if (e.ntStatus == 0xC0000034.toInt()) {
                    Result.success(Unit)
                } else {
                    Log.w(TAG, "delete failed: $remotePath — ${e.message}")
                    Result.failure(Exception("SMB delete failed: ${e.message}", e))
                }
            } catch (e: Exception) {
                Log.w(TAG, "delete failed: $remotePath — ${e.message}")
                Result.failure(Exception("SMB delete failed: ${e.message}", e))
            }
        }

    override suspend fun exists(remotePath: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                Result.success(smbFile(remotePath).exists())
            } catch (e: Exception) {
                Result.failure(Exception("SMB exists check failed: ${e.message}", e))
            }
        }
}
