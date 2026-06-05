package com.example.androidbackupgui.backup

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
/**
 * NanoHTTPD-based REST bridge implementing the restic REST backend API.
 *
 * Translates restic HTTP requests into [RemoteTransport] calls so that restic
 * can read/write blobs directly to SMB/WebDAV without a local staging repo.
 *
 * Port is auto-assigned (0); use [listeningPort] after start().
 *
 * @param repoPath repository path from the bridge URL (e.g. "backup").
 *                 Stripped from incoming URIs so that the remoteBase SMB path
 *                 does not get double-nested with the repo prefix.
 */
class ResticRestBridge(
    private val transport: RemoteTransport,
    private val remoteBase: String,
    private val repoPath: String,
    private val cacheDir: File
) : NanoHTTPD(0) {

    private val TAG = "ResticRestBridge"

    init {
        cacheDir.mkdirs()
    }

    @Suppress("DEPRECATION")
    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        val headers = session.headers
        val params = session.parms

        Log.d(TAG, "$method $uri")

        return try {
            handleRequest(method, uri, headers, params, session)
        } catch (e: Exception) {
            Log.e(TAG, "request failed: $method $uri", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                e.message ?: "Internal error"
            )
        }
    }

    private fun handleRequest(
        method: NanoHTTPD.Method,
        uri: String,
        headers: Map<String, String>,
        params: Map<String, String>,
        session: IHTTPSession
    ): Response {
        val path = uri.trimEnd('/')
        // Strip the repoPath prefix (/backup/...) from the URI so that type/name
        // parsing sees only the restic REST API segment.
        val stripPrefix = if (repoPath.isNotEmpty()) "/${repoPath.trim('/')}" else ""
        val strippedPath = if (stripPrefix.isNotEmpty() && path.startsWith(stripPrefix)) {
            path.removePrefix(stripPrefix).ifEmpty { "/" }
        } else {
            path
        }

        // POST {path}?create=true -> mkdirs
        if (method == NanoHTTPD.Method.POST && params["create"] == "true") {
            return runBlocking {
                when (transport.mkdirs(remoteBase)) {
                    is AppResult.Success -> newFixedLengthResponse(
                        Response.Status.OK, "text/plain", ""
                    )
                    is AppResult.Failure -> newFixedLengthResponse(
                        Response.Status.INTERNAL_ERROR, "text/plain", "mkdirs failed"
                    )
                }
            }
        }

        val segments = strippedPath.split("/").filter { it.isNotEmpty() }

        if (segments.isEmpty()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Invalid path")
        }

        val firstSegment = segments.first()

        // /config endpoints
        if (firstSegment == "config" && segments.size == 1) {
            return handleConfig(method, headers, session)
        }

        // /{type}/ or /{type}/{name}
        val type = firstSegment
        val name = if (segments.size >= 2) segments.drop(1).joinToString("/") else null

        if (name == null) {
            if (method == NanoHTTPD.Method.GET) {
                return handleListBlobs(type)
            }
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "")
        }

        return when (method) {
            NanoHTTPD.Method.HEAD -> handleHeadBlob(type, name)
            NanoHTTPD.Method.GET -> handleGetBlob(type, name, headers)
            NanoHTTPD.Method.POST -> handlePostBlob(type, name, session)
            NanoHTTPD.Method.DELETE -> handleDeleteBlob(type, name)
            else -> newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "")
        }
    }

    // -- Config endpoints -------------------------------------------
    /**
     * Stream body from session input to a temp file to avoid OOM on large blobs.
     * Returns the temp file (caller must delete).
     */
    private fun streamBodyToFile(session: IHTTPSession, tmpDir: File): Result<File> {
        val started = System.currentTimeMillis()
        return try {
            val tmpFile = File(tmpDir, "restic_blob_${UUID.randomUUID()}")
            val contentLength = session.headers["content-length"]?.toLongOrNull() ?: -1L
            val input = (session as NanoHTTPD.HTTPSession).inputStream
            Log.d(TAG, "streamBodyToFile: reading body (content-length=$contentLength)...")
            tmpFile.outputStream().use { output ->
                if (contentLength > 0) {
                    // Read exactly Content-Length bytes to avoid blocking on keep-alive
                    val buf = ByteArray(8192)
                    var remaining = contentLength
                    while (remaining > 0) {
                        val toRead = minOf(buf.size.toLong(), remaining).toInt()
                        val n = input.read(buf, 0, toRead)
                        if (n == -1) break
                        output.write(buf, 0, n)
                        remaining -= n
                    }
                    if (remaining > 0) {
                        Log.w(TAG, "streamBodyToFile: body truncated, expected $contentLength bytes but got EOF after ${contentLength - remaining}")
                    }
                    Unit
                } else {
                    input.copyTo(output)
                }
            }
            val elapsed = System.currentTimeMillis() - started
            val bytes = tmpFile.length()
            Log.i(TAG, "streamBodyToFile: read $bytes bytes in ${elapsed}ms")
            Result.success(tmpFile)
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - started
            Log.w(TAG, "streamBodyToFile failed after ${elapsed}ms", e)
            Result.failure(e)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun handleConfig(
        method: NanoHTTPD.Method,
        headers: Map<String, String>,
        session: IHTTPSession
    ): Response = runBlocking {
        val remotePath = "$remoteBase/config"
        when (method) {
            NanoHTTPD.Method.HEAD -> {
                when (val exists = transport.exists(remotePath)) {
                    is AppResult.Success -> {
                        if (exists.data) {
                            val sizeResult = transport.fileSize(remotePath)
                            val fileSize = if (sizeResult is AppResult.Success) sizeResult.data else 0L
                            newFixedLengthResponse(
                                Response.Status.OK, "application/octet-stream",
                                ByteArrayInputStream(ByteArray(0)), fileSize
                            )
                        } else {
                            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
                        }
                    }
                    is AppResult.Failure -> newFixedLengthResponse(
                        Response.Status.NOT_FOUND, "text/plain", ""
                    )
                }
            }
            NanoHTTPD.Method.GET -> {
                val tempFile = File(cacheDir, "restic_blob_${UUID.randomUUID()}")
                try {
                    when (transport.download(remotePath, tempFile.absolutePath)) {
                        is AppResult.Success -> {
                            val data = tempFile.readBytes()
                            newFixedLengthResponse(Response.Status.OK, "application/octet-stream", data.inputStream(), data.size.toLong())
                        }
                        is AppResult.Failure -> newFixedLengthResponse(
                            Response.Status.NOT_FOUND, "text/plain", ""
                        )
                    }
                } finally {
                    tempFile.delete()
                }
            }
            NanoHTTPD.Method.POST -> {
                val tmpResult = streamBodyToFile(session, cacheDir)
                if (tmpResult.isFailure) return@runBlocking newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, "text/plain",
                    "body read failed: ${tmpResult.exceptionOrNull()?.message ?: "unknown"}"
                )
                val tmpFile = tmpResult.getOrThrow()
                try {
                    when (transport.upload(tmpFile.absolutePath, remotePath)) {
                        is AppResult.Success -> newFixedLengthResponse(
                            Response.Status.OK, "text/plain", ""
                        )
                        is AppResult.Failure -> newFixedLengthResponse(
                            Response.Status.INTERNAL_ERROR, "text/plain", "upload failed"
                        )
                    }
                } finally {
                    tmpFile.delete()
                }
            }
            else -> newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "")
        }
    }

    // -- Blob listing -----------------------------------------------

    private fun handleListBlobs(type: String): Response = runBlocking {
        val remoteDir = "$remoteBase/$type"
        when (val result = transport.listFiles(remoteDir)) {
            is AppResult.Success -> {
                val items = result.data
                val json = buildV2Json(items)
                newFixedLengthResponse(Response.Status.OK, "application/vnd.x.restic.rest.v2", json)
            }
            is AppResult.Failure -> newFixedLengthResponse(
                Response.Status.NOT_FOUND, "text/plain", ""
            )
        }
    }

    private fun buildV2Json(items: List<RemoteTransport.RemoteFileInfo>): String {
        val sb = StringBuilder("[")
        var first = true
        for (item in items) {
            if (item.isDirectory) continue
            if (!first) sb.append(",")
            first = false
            sb.append("{\"name\":\"${item.name}\",\"size\":${item.size}}")
        }
        sb.append("]")
        return sb.toString()
    }

    // -- Blob HEAD (exists + size) ----------------------------------

    private fun handleHeadBlob(type: String, name: String): Response = runBlocking {
        val remotePath = "$remoteBase/$type/$name"
        when (val result = transport.exists(remotePath)) {
            is AppResult.Success -> {
                if (result.data) {
                    newFixedLengthResponse(Response.Status.OK, "application/octet-stream", "")
                } else {
                    newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
                }
            }
            is AppResult.Failure -> newFixedLengthResponse(
                Response.Status.NOT_FOUND, "text/plain", ""
            )
        }
    }

    // -- Blob GET (download with optional Range) --------------------

    private fun handleGetBlob(
        type: String,
        name: String,
        headers: Map<String, String>
    ): Response = runBlocking {
        val remotePath = "$remoteBase/$type/$name"
        // Use RandomAccessFile to avoid loading entire blob into memory
        val tempFile = File(cacheDir, "restic_blob_${UUID.randomUUID()}")
        try {
            when (transport.download(remotePath, tempFile.absolutePath)) {
                is AppResult.Success -> {
                    val rangeHeader = headers["range"]?.lowercase()

                    if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                        // Range request — only works with known file size
                        val fileLen = tempFile.length()
                        val range = rangeHeader.removePrefix("bytes=").trim()
                        val dashIdx = range.indexOf('-')
                        val start = range.substring(0, if (dashIdx >= 0) dashIdx else range.length)
                            .toLongOrNull() ?: 0L
                        val end = if (dashIdx >= 0 && dashIdx + 1 < range.length) {
                            range.substring(dashIdx + 1).toLongOrNull() ?: (fileLen - 1)
                        } else {
                            fileLen - 1
                        }

                        val actualEnd = minOf(end, fileLen - 1).coerceAtLeast(0)
                        val actualStart = minOf(start, actualEnd).coerceAtLeast(0)
                        val chunkSize = (actualEnd - actualStart + 1).toInt()
                        val chunk = ByteArray(chunkSize)
                        try {
                            val raf = java.io.RandomAccessFile(tempFile, "r")
                            raf.use { it.seek(actualStart); it.readFully(chunk) }
                        } catch (_: Exception) {
                            return@runBlocking newFixedLengthResponse(
                                Response.Status.INTERNAL_ERROR, "text/plain", "range read failed"
                            )
                        }

                        val response = newChunkedResponse(
                            Response.Status.PARTIAL_CONTENT,
                            "application/octet-stream",
                            chunk.inputStream()
                        )
                        response.addHeader("Content-Range", "bytes $actualStart-$actualEnd/$fileLen")
                        response.addHeader("Content-Length", chunkSize.toString())
                        return@runBlocking response
                    }
                    // Full file — read into memory (blobs are typically small)
                    val data = tempFile.readBytes()
                    val response = newChunkedResponse(
                        Response.Status.OK,
                        "application/octet-stream",
                        data.inputStream()
                    )
                    response.addHeader("Content-Length", data.size.toString())
                    response
                }
                is AppResult.Failure -> newFixedLengthResponse(
                    Response.Status.NOT_FOUND, "text/plain", ""
                )
            }
        } finally {
            tempFile.delete()
        }
    }

    // -- Blob POST (upload) -----------------------------------------

    private fun handlePostBlob(
        type: String,
        name: String,
        session: IHTTPSession
    ): Response = runBlocking {
        val remotePath = "$remoteBase/$type/$name"
        val tmpResult = streamBodyToFile(session, cacheDir)
        if (tmpResult.isFailure) return@runBlocking newFixedLengthResponse(
            Response.Status.INTERNAL_ERROR, "text/plain",
            "body read failed: ${tmpResult.exceptionOrNull()?.message ?: "unknown"}"
        )
        val tmpFile = tmpResult.getOrThrow()
        try {
            when (transport.upload(tmpFile.absolutePath, remotePath)) {
                is AppResult.Success -> newFixedLengthResponse(
                    Response.Status.OK, "text/plain", ""
                )
                is AppResult.Failure -> newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, "text/plain", "upload failed"
                )
            }
        } finally {
            tmpFile.delete()
        }
    }

    // -- Blob DELETE ------------------------------------------------

    private fun handleDeleteBlob(type: String, name: String): Response = runBlocking {
        val remotePath = "$remoteBase/$type/$name"
        when (transport.delete(remotePath)) {
            is AppResult.Success -> newFixedLengthResponse(
                Response.Status.OK, "text/plain", ""
            )
            is AppResult.Failure -> newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "text/plain", "delete failed"
            )
        }
    }
}
