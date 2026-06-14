package com.example.androidbackupgui.backup.restic

import java.io.File

/**
 * 后端执行器——消除 [ResticBackup]、[ResticRestore]、[ResticSnapshotOps]、
 * [ResticMaintenance] 和 [ResticRepoInit] 中重复的 local-vs-remote 分支。
 *
 * 使用方式（替换所有子模块中的 if backend == "local" 模式）：
 *
 * ```
 * executor.withBackend(
 *     repoPath = repoPath, password = password, cacheDir = cacheDir,
 *     backend = backend, backendUrl = backendUrl,
 *     backendUser = backendUser, backendPass = backendPass,
 *     backendShare = backendShare, backendDomain = backendDomain,
 *     runner = runner, envResolver = envResolver, bridgeRunner = bridgeRunner,
 * ) { env ->
 *     val result = runner.runRestic(env, args)
 *     // parse result
 * }
 * ```
 */
class BackendExecutor {
    /**
     * 使用 [block] 执行 restic 操作。
     *
     * - "local" 后端：直接通过 [ResticEnvResolver.buildLocalEnv] 构建环境
     * - 远程后端：通过 [RestBridgeRunner.withBridge] 启动 REST 桥后再构建环境
     *
     * @param T 返回值的类型（例如 [AppResult]）
     * @param block 接收环境变量 Map，返回 [T]
     */
    suspend fun <T> withBackend(
        repoPath: String,
        password: String,
        cacheDir: String,
        backend: String,
        backendUrl: String,
        backendUser: String,
        backendPass: String,
        backendShare: String,
        backendDomain: String,
        runner: ResticCommandRunner,
        envResolver: ResticEnvResolver,
        bridgeRunner: RestBridgeRunner,
        block: suspend (Map<String, String>) -> T,
    ): T {
        if (backend == "local") {
            val env = envResolver.buildLocalEnv(repoPath, password, cacheDir)
            return block(env)
        }
        return bridgeRunner.withBridge(
            backend,
            backendUrl,
            backendUser,
            backendPass,
            backendShare,
            backendDomain,
            repoPath,
            File(cacheDir),
        ) { bridgeUrl, authToken ->
            val env = envResolver.buildBridgeEnv(password, bridgeUrl, cacheDir, authToken)
            block(env)
        }
    }

    /**
     * 与 [withBackend] 相同，但自动将 [args] 传给 [runner.runRestic]。
     *
     * 适用于 "run-and-parse-exit-code" 模式的简化调用。
     */
    suspend fun runResticWithBackend(
        args: List<String>,
        repoPath: String,
        password: String,
        cacheDir: String,
        backend: String,
        backendUrl: String,
        backendUser: String,
        backendPass: String,
        backendShare: String,
        backendDomain: String,
        runner: ResticCommandRunner,
        envResolver: ResticEnvResolver,
        bridgeRunner: RestBridgeRunner,
    ): ResticCommandRunner.CommandResult =
        withBackend(
            repoPath = repoPath,
            password = password,
            cacheDir = cacheDir,
            backend = backend,
            backendUrl = backendUrl,
            backendUser = backendUser,
            backendPass = backendPass,
            backendShare = backendShare,
            backendDomain = backendDomain,
            runner = runner,
            envResolver = envResolver,
            bridgeRunner = bridgeRunner,
        ) { env -> runner.runRestic(env, args) }

    /**
     * 与 [runResticWithBackend] 相同，但使用流式模式。
     */
    suspend fun runResticStreamingWithBackend(
        args: List<String>,
        repoPath: String,
        password: String,
        cacheDir: String,
        backend: String,
        backendUrl: String,
        backendUser: String,
        backendPass: String,
        backendShare: String,
        backendDomain: String,
        runner: ResticCommandRunner,
        envResolver: ResticEnvResolver,
        bridgeRunner: RestBridgeRunner,
        onLine: suspend (String) -> Unit = {},
    ): ResticCommandRunner.CommandResult =
        withBackend(
            repoPath = repoPath,
            password = password,
            cacheDir = cacheDir,
            backend = backend,
            backendUrl = backendUrl,
            backendUser = backendUser,
            backendPass = backendPass,
            backendShare = backendShare,
            backendDomain = backendDomain,
            runner = runner,
            envResolver = envResolver,
            bridgeRunner = bridgeRunner,
        ) { env -> runner.runResticStreaming(env, args, onLine) }
}
