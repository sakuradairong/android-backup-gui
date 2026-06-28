package com.example.androidbackupgui.backup.restic

import android.util.Base64
import com.example.androidbackupgui.backup.core.AppError
import com.example.androidbackupgui.backup.core.AppResult
import com.example.androidbackupgui.backup.core.LogSanitizer
import com.example.androidbackupgui.backup.core.LogUtil
import com.example.androidbackupgui.backup.core.err
import com.example.androidbackupgui.backup.core.retryWithBackoff
import com.thegrizzlylabs.sardineandroid.Sardine
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import com.thegrizzlylabs.sardineandroid.impl.SardineException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

class WebdavTransport(
    private val baseUrl: String,
    private val username: String,
    private val password: String,
    private val bufferSize: Int = 8192,
    private val connectTimeoutSeconds: Int = 15,
    private val readTimeoutSeconds: Int = 30,
    private val allowInsecure: Boolean = false,
) : RemoteTransport {
    companion object {
        private const val TAG = "WebdavTransport"
    }

    init {
        val scheme = baseUrl.substringBefore("://", "").lowercase()
        val hasCredentials = username.isNotEmpty()
        if (scheme == "http") {
            if (hasCredentials) {
                throw IllegalArgumentException("WebDAV Basic auth over HTTP is not allowed. Use HTTPS.")
            }
            if (!allowInsecure) {
                throw IllegalArgumentException(
                    "WebDAV HTTP is not allowed by default. Enable 'allow insecure WebDAV' in settings or use HTTPS.",
                )
            }
        }
        if (baseUrl.contains("@") && (baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))) {
            val afterScheme = baseUrl.substringAfter("://")
            if (afterScheme.contains("@")) {
                throw IllegalArgumentException("URL userinfo is not allowed. Put credentials in the username/password fields.")
            }
        }
    }

    private val sardine: Sardine by lazy {
        val client =
            okhttp3.OkHttpClient
                .Builder()
                .connectTimeout(connectTimeoutSeconds.toLong(), java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(readTimeoutSeconds.toLong(), java.util.concurrent.TimeUnit.SECONDS)
                .build()
        OkHttpSardine(client).apply {
            if (username.isNotEmpty()) {
                setCredentials(username, password)
            }
        }
    }

    private fun buildUrl(path: String): String {
        val cleanPath = path.trimStart('/')
        return "$baseUrl/$cleanPath"
    }

    override suspend fun upload(
        localPath: String,
        remotePath: String,
        onProgress: suspend (RemoteTransport.TransferProgress) -> Unit,
        onByteProgress: suspend (RemoteTransport.ByteProgress) -> Unit,
    ): AppResult<Unit> =
        retryWithBackoff(TAG, "WebDAV 上传") {
            withContext(Dispatchers.IO) {
                try {
                    val url = buildUrl(remotePath)
                    val file = File(localPath)
                    val fileSize = file.length()
                    if (fileSize > 50 * 1024 * 1024L) {
                        return@withContext err(AppError.Remote("WebDAV 上传: 文件过大 (${fileSize / 1024 / 1024}MB), 上限 50MB", "upload"))
                    }
                    LogUtil.d(TAG, "upload ${LogSanitizer.redact(localPath)} -> ${LogSanitizer.redact(url)} ($fileSize bytes)")
                    onProgress(RemoteTransport.TransferProgress("connecting", 0, 1, remotePath))
                    val data =
                        file.inputStream().buffered(bufferSize).use { input ->
                            onProgress(RemoteTransport.TransferProgress("transferring", 0, 1, remotePath))
                            val out = ByteArrayOutputStream()
                            val buffer = ByteArray(bufferSize)
                            var totalRead = 0L
                            var n = input.read(buffer)
                            while (n != -1) {
                                coroutineContext.ensureActive()
                                out.write(buffer, 0, n)
                                totalRead += n
                                onByteProgress(RemoteTransport.ByteProgress(totalRead, fileSize, remotePath))
                                n = input.read(buffer)
                            }
                            out.toByteArray()
                        }
                    sardine.put(url, data, "application/octet-stream")
                    onProgress(RemoteTransport.TransferProgress("completed", 1, 1, remotePath))
                    AppResult.Success(Unit)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LogUtil.e(TAG, "upload failed: ${LogSanitizer.redact(remotePath)}", e)
                    err(AppError.Remote("WebDAV 上传失败", "upload", cause = e))
                }
            }
        }

    override suspend fun download(
        remotePath: String,
        localPath: String,
        onProgress: suspend (RemoteTransport.TransferProgress) -> Unit,
        onByteProgress: suspend (RemoteTransport.ByteProgress) -> Unit,
    ): AppResult<Unit> =
        retryWithBackoff(TAG, "WebDAV 下载") {
            withContext(Dispatchers.IO) {
                try {
                    val url = buildUrl(remotePath)
                    val localFile = File(localPath)
                    localFile.parentFile?.mkdirs()
                    val partFile = File(localPath + ".part")
                    val existingBytes = if (partFile.exists()) partFile.length() else 0L

                    onProgress(RemoteTransport.TransferProgress("connecting", 0, 1, remotePath))

                    if (existingBytes > 0L) {
                        LogUtil.d(TAG, "download 发现 .part 文件, 从 offset=$existingBytes 续传: ${LogSanitizer.redact(remotePath)}")
                        downloadRangeResume(url, partFile, existingBytes, onByteProgress, remotePath)
                    } else {
                        sardine.get(url).use { input ->
                            partFile.outputStream().use { output ->
                                onProgress(RemoteTransport.TransferProgress("transferring", 0, 1, remotePath))
                                val buffer = ByteArray(bufferSize)
                                var totalRead = 0L
                                var n = input.read(buffer)
                                while (n != -1) {
                                    coroutineContext.ensureActive()
                                    output.write(buffer, 0, n)
                                    totalRead += n
                                    onByteProgress(RemoteTransport.ByteProgress(totalRead, 0, remotePath))
                                    n = input.read(buffer)
                                }
                            }
                        }
                    }

                    if (partFile.exists()) {
                        partFile.renameTo(localFile)
                    }
                    onProgress(RemoteTransport.TransferProgress("completed", 1, 1, remotePath))
                    LogUtil.d(
                        TAG,
                        "download ${LogSanitizer.redact(url)} -> ${LogSanitizer.redact(localPath)} (${localFile.length()} bytes)",
                    )
                    AppResult.Success(Unit)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LogUtil.e(TAG, "download failed: ${LogSanitizer.redact(remotePath)}", e)
                    err(AppError.Remote("WebDAV 下载失败", "download", cause = e))
                }
            }
        }

    private suspend fun downloadRangeResume(
        url: String,
        partFile: File,
        offset: Long,
        onByteProgress: suspend (RemoteTransport.ByteProgress) -> Unit,
        remotePath: String,
    ) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = connectTimeoutSeconds * 1000
        conn.readTimeout = readTimeoutSeconds * 1000
        try {
            conn.requestMethod = "GET"
            if (username.isNotEmpty()) {
                val basicAuth =
                    "Basic " +
                        Base64.encodeToString(
                            "$username:$password".toByteArray(Charsets.UTF_8),
                            Base64.NO_WRAP,
                        )
                conn.setRequestProperty("Authorization", basicAuth)
            }
            conn.setRequestProperty("Range", "bytes=$offset-")
            conn.connect()

            val statusCode = conn.responseCode
            if (statusCode != 206 && statusCode != 200) {
                throw IOException("WebDAV Range resume 失败: HTTP $statusCode (需要 206)")
            }

            // 审查报告 H2：服务器返回 200 说明忽略了 Range 请求，返回的是完整内容。
            // 此时绝不能继续 append，否则会得到「旧残留 + 完整内容」的损坏文件。
            // 改为截断重写，从头开始接收完整内容。
            val resumeSupported = statusCode == 206
            val totalSize: Long
            val startOffset: Long
            if (resumeSupported) {
                totalSize = offset + conn.contentLength
                startOffset = offset
            } else {
                totalSize = conn.contentLength.toLong().let { if (it <= 0) 0L else it }
                startOffset = 0L
            }
            java.io.FileOutputStream(partFile, resumeSupported).use { output ->
                conn.inputStream.use { input ->
                    val buffer = ByteArray(bufferSize)
                    var totalRead = startOffset
                    var n = input.read(buffer)
                    while (n != -1) {
                        coroutineContext.ensureActive()
                        output.write(buffer, 0, n)
                        totalRead += n
                        onByteProgress(RemoteTransport.ByteProgress(totalRead, totalSize, remotePath))
                        n = input.read(buffer)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    override suspend fun listFiles(remoteDir: String): AppResult<List<RemoteTransport.RemoteFileInfo>> =
        withContext(Dispatchers.IO) {
            try {
                val url = buildUrl(remoteDir)
                val resources = sardine.list(url)
                val urlPath = url.replace(Regex("/+$"), "")
                val entries =
                    resources
                        .filter { r ->
                            val name = r.name
                            val href = r.href?.toString()?.replace(Regex("/+$"), "") ?: ""
                            name != "." && name != ".." && href != urlPath
                        }.map {
                            RemoteTransport.RemoteFileInfo(
                                name = it.name,
                                size = it.contentLength,
                                isDirectory = it.isDirectory,
                            )
                        }
                LogUtil.d(TAG, "listFiles ${LogSanitizer.redact(remoteDir)} -> ${entries.size} entries")
                AppResult.Success(entries)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val is404 = e is SardineException && e.statusCode == 404
                if (is404) {
                    LogUtil.d(TAG, "listFiles ${LogSanitizer.redact(remoteDir)} -> 404 (not found)")
                    return@withContext err(AppError.Remote("远端路径不存在", "list", isNotFound = true))
                }
                LogUtil.e(TAG, "listFiles failed: ${LogSanitizer.redact(remoteDir)}", e)
                err(AppError.Remote("WebDAV 列表失败", "list", cause = e))
            }
        }

    override suspend fun mkdirs(remotePath: String): AppResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val parts = remotePath.trimStart('/').split("/")
                var current = ""
                var createdCount = 0
                var lastError: Exception? = null
                for (part in parts) {
                    current = if (current.isEmpty()) part else "$current/$part"
                    try {
                        sardine.createDirectory(buildUrl(current))
                        createdCount++
                    } catch (e: SardineException) {
                        // 409 Conflict / 405 Method Not Allowed usually means the directory already exists
                        if (e.statusCode == 409 || e.statusCode == 405) {
                            createdCount++
                        } else {
                            lastError = e
                            LogUtil.w(TAG, "mkdirs: failed to create ${LogSanitizer.redact(current)} — HTTP ${e.statusCode}")
                        }
                    } catch (e: Exception) {
                        lastError = e
                        LogUtil.w(TAG, "mkdirs: failed to create ${LogSanitizer.redact(current)} — ${e.message}")
                    }
                }
                if (createdCount == 0 && parts.isNotEmpty()) {
                    return@withContext err(
                        AppError.Remote(
                            "WebDAV 创建目录失败: 无法创建任何层级",
                            "mkdirs",
                            cause = lastError,
                        ),
                    )
                }
                AppResult.Success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUtil.e(TAG, "mkdirs failed: ${LogSanitizer.redact(remotePath)} — ${e.message}")
                err(AppError.Remote("WebDAV mkdirs 失败", "mkdirs", cause = e))
            }
        }

    override suspend fun delete(remotePath: String): AppResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val url = buildUrl(remotePath)
                sardine.delete(url)
                AppResult.Success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 审查报告 L4：此分支实际返回 err，并非“忽略”。原日志措辞 "ignoring" 误导调用方。
                LogUtil.w(TAG, "delete failed: ${LogSanitizer.redact(remotePath)} — ${e.message}")
                err(AppError.Remote("WebDAV 删除失败", "delete", cause = e))
            }
        }

    override suspend fun exists(remotePath: String): AppResult<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val result = sardine.exists(buildUrl(remotePath))
                AppResult.Success(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                err(AppError.Remote("WebDAV 检查失败", "exists", cause = e))
            }
        }

    override suspend fun fileSize(remotePath: String): AppResult<Long> =
        withContext(Dispatchers.IO) {
            try {
                val url = buildUrl(remotePath)
                val resources = sardine.list(url)
                val resource = resources.firstOrNull { it.name == remotePath.substringAfterLast("/") }
                if (resource != null) {
                    AppResult.Success(resource.contentLength)
                } else {
                    err(AppError.Remote("文件不存在", "fileSize"))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                err(AppError.Remote("WebDAV 获取文件大小失败", "fileSize", cause = e))
            }
        }
}
