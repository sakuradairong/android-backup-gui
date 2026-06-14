package com.example.androidbackupgui.root

/**
 * 批量 Shell 执行器 - 合并多个 Shell 命令为单次调用。
 *
 * 减少进程创建开销，将 N 次 RootShell.exec() 调用合并为 1 次。
 *
 * 使用唯一分隔符解析每个命令的输出，确保结果可靠性。
 * 如果批量命令失败，支持回退到独立命令执行。
 */
object BatchShellExecutor {

    data class BatchResult(
        val results: List<RootShell.ShellResult>,
        val isBatchSuccess: Boolean,
    )

    /**
     * 批量执行多个 Shell 命令。
     *
     * 每个命令的输出用唯一分隔符分隔，便于解析。
     * 命令使用 `;` 分隔（独立执行），而不是 `&&`（依赖执行）。
     *
     * @param commands 要执行的命令列表
     * @param delimiter 输出分隔符（默认自动生成唯一分隔符）
     * @return BatchResult 包含每个命令的结果
     */
    suspend fun execBatch(
        commands: List<String>,
        delimiter: String = "---BATCH_DELIMITER_${System.nanoTime()}---",
    ): BatchResult {
        if (commands.isEmpty()) {
            return BatchResult(emptyList(), true)
        }

        if (commands.size == 1) {
            val result = RootShell.exec(commands[0])
            return BatchResult(listOf(result), true)
        }

        // 构建批量命令：每个命令后打印分隔符
        val batchCommand = buildString {
            commands.forEachIndexed { index, cmd ->
                if (index > 0) append("; ")
                append(cmd)
                append("; echo '$delimiter'")
            }
        }

        val batchResult = RootShell.exec(batchCommand)

        if (!batchResult.isSuccess) {
            // 批量命令失败，回退到独立执行
            return execBatchFallback(commands)
        }

        // 解析批量输出
        val outputs = batchResult.output.split(delimiter)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        // 确保输出数量与命令数量匹配
        if (outputs.size != commands.size) {
            // 输出数量不匹配，回退到独立执行
            return execBatchFallback(commands)
        }

        // 为每个命令创建 ShellResult
        val results = outputs.map { output ->
            RootShell.ShellResult(
                output = output,
                error = "", // 批量执行无法分离 stderr
                exitCode = 0,
            )
        }

        return BatchResult(results, true)
    }

    /**
     * 批量执行目录存在性检查。
     *
     * 合并多个 test -d 检查为单次调用。
     *
     * @param dirs 要检查的目录列表
     * @return Map<String, Boolean> 目录 -> 是否存在
     */
    suspend fun checkDirsExist(dirs: List<String>): Map<String, Boolean> {
        if (dirs.isEmpty()) return emptyMap()

        val commands = dirs.map { dir ->
            "test -d '${dir.shellEscape()}' && echo 'EXISTS' || echo 'NONE'"
        }

        val batchResult = execBatch(commands)

        if (!batchResult.isBatchSuccess || batchResult.results.size != dirs.size) {
            // 回退到独立检查
            return dirs.associateWith { dir ->
                RootShell.exec("test -d '${dir.shellEscape()}'").isSuccess
            }
        }

        return dirs.zip(batchResult.results).associate { (dir, result) ->
            dir to (result.output.trim() == "EXISTS")
        }
    }

    /**
     * 批量执行文件存在性和大小检查。
     *
     * 合并 test -e 和 stat -c%s 为单次调用。
     *
     * @param files 要检查的文件路径列表
     * @return Map<String, Pair<Boolean, Long>> 文件 -> (是否存在, 大小)
     */
    suspend fun checkFilesExistAndSize(files: List<String>): Map<String, Pair<Boolean, Long>> {
        if (files.isEmpty()) return emptyMap()

        val commands = files.map { file ->
            """
            if test -e '${file.shellEscape()}'; then
                echo "EXISTS $(stat -c%s '${file.shellEscape()}' 2>/dev/null || echo 0)"
            else
                echo "NONE 0"
            fi
            """.trimIndent()
        }

        val batchResult = execBatch(commands)

        if (!batchResult.isBatchSuccess || batchResult.results.size != files.size) {
            // 回退到独立检查
            return files.associateWith { file ->
                val exists = RootShell.exec("test -e '${file.shellEscape()}'").isSuccess
                val size = if (exists) {
                    RootShell.exec("stat -c%s '${file.shellEscape()}' 2>/dev/null")
                        .output.trim().toLongOrNull() ?: 0L
                } else {
                    0L
                }
                exists to size
            }
        }

        return files.zip(batchResult.results).associate { (file, result) ->
            val output = result.output.trim()
            val exists = output.startsWith("EXISTS")
            val size = output.substringAfter("EXISTS").trim()
                .toLongOrNull() ?: 0L
            file to (exists to size)
        }
    }

    /**
     * 合并压缩验证和 tar 结构验证为单次调用。
     *
     * @param archivePath 归档文件路径
     * @param isZstd 是否使用 zstd 压缩
     * @return Pair<Boolean, Boolean> (压缩验证通过, tar 结构验证通过)
     */
    suspend fun verifyArchive(
        archivePath: String,
        isZstd: Boolean,
    ): Pair<Boolean, Boolean> {
        val escapedPath = archivePath.shellEscape()

        val command = if (isZstd) {
            """
            zstd -t '$escapedPath' 2>/dev/null && echo "COMPRESS_OK" || echo "COMPRESS_FAIL"
            zstd -d -c '$escapedPath' 2>/dev/null | tar -tf - > /dev/null 2>&1 && echo "TAR_OK" || echo "TAR_FAIL"
            """.trimIndent()
        } else {
            """
            gzip -t '$escapedPath' 2>/dev/null && echo "COMPRESS_OK" || echo "COMPRESS_FAIL"
            tar -tf '$escapedPath' > /dev/null 2>&1 && echo "TAR_OK" || echo "TAR_FAIL"
            """.trimIndent()
        }

        val result = RootShell.exec(command)
        if (!result.isSuccess) return false to false

        val compressOk = result.output.contains("COMPRESS_OK")
        val tarOk = result.output.contains("TAR_OK")

        return compressOk to tarOk
    }

    // ── 内部实现 ─────────────────────────────────────

    /**
     * 回退到独立执行每个命令。
     */
    private suspend fun execBatchFallback(commands: List<String>): BatchResult {
        val results = commands.map { cmd ->
            RootShell.exec(cmd)
        }
        return BatchResult(results, false)
    }
}
