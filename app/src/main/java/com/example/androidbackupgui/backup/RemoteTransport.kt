package com.example.androidbackupgui.backup

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
    /** Get the size of a remote file in bytes. Returns [AppResult.Failure] if not found. */
    suspend fun fileSize(remotePath: String): AppResult<Long>

    companion object {

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
    }
}

/** Extension to check if an [AppError] represents a "not found" remote error. */
internal fun AppError.isFileNotFound(): Boolean =
    this is AppError.Remote && this.isNotFound
