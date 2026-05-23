package com.example.androidbackupgui.backup

import android.util.Log
import jcifs.CIFSContext
import jcifs.context.SingletonContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import jcifs.smb.SmbFileInputStream
import jcifs.smb.SmbFileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class SmbTransport(
    private val host: String,
    private val share: String,
    private val username: String,
    private val password: String
) : RemoteTransport {

    companion object { private const val TAG = "SmbTransport" }

    private val context: CIFSContext by lazy {
        if (username.isNotEmpty()) {
            SingletonContext.getInstance()
                .withCredentials(NtlmPasswordAuthenticator(/* domain */ "", username, password))
        } else {
            SingletonContext.getInstance()
        }
    }

    private fun buildUrl(path: String): String {
        val cleanPath = path.trimStart('/')
        val sharePath = if (share.isNotEmpty()) "$share/$cleanPath" else cleanPath
        return "smb://$host/$sharePath"
    }

    private fun smbFile(path: String): SmbFile = SmbFile(buildUrl(path), context)

    override suspend fun upload(localPath: String, remotePath: String): Result<Unit> =
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
                SmbFileOutputStream(remote).use { output ->
                    localFile.inputStream().buffered().use { input ->
                        input.copyTo(output)
                    }
                }
                Log.i(TAG, "upload $localPath -> ${buildUrl(remotePath)} (${localFile.length()} bytes)")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "upload failed: ${buildUrl(remotePath)}", e)
                Result.failure(Exception("SMB upload failed: ${e.message}", e))
            }
        }

    override suspend fun download(remotePath: String, localPath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val localFile = File(localPath)
                localFile.parentFile?.mkdirs()
                val remote = smbFile(remotePath)
                SmbFileInputStream(remote).use { input ->
                    localFile.outputStream().buffered().use { output ->
                        input.copyTo(output)
                    }
                }
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
                    return@withContext Result.success(emptyList())
                }
                val entries = dir.listFiles()
                    ?.map { RemoteTransport.RemoteFileInfo(
                        name = it.name,
                        size = if (it.isFile) it.length() else 0,
                        isDirectory = it.isDirectory
                    ) }
                    ?: emptyList()
                Log.d(TAG, "listFiles $remoteDir -> ${entries.size} entries")
                Result.success(entries)
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
            } catch (e: Exception) {
                Log.w(TAG, "mkdirs failed: $remotePath — ${e.message}")
                Result.success(Unit)
            }
        }

    override suspend fun delete(remotePath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val file = smbFile(remotePath)
                if (file.exists()) file.delete()
                Result.success(Unit)
            } catch (e: Exception) {
                Log.w(TAG, "delete failed (ignoring): $remotePath — ${e.message}")
                Result.success(Unit)
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
