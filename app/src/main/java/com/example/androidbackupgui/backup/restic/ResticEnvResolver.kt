package com.example.androidbackupgui.backup.restic

/**
 * Stateless helper for constructing restic environment variables and repo URLs.
 */
class ResticEnvResolver {
    /** Build environment for non-local backends using the REST bridge URL. */
    fun buildBridgeEnv(
        password: String,
        bridgeUrl: String,
        cacheDir: String,
        authToken: String = "",
    ): Map<String, String> {
        // 从空白环境开始，不继承系统环境变量（防止敏感信息泄露到子进程）
        val env = HashMap<String, String>()
        env["RESTIC_REPOSITORY"] = bridgeUrl
        env["RESTIC_PASSWORD"] = password
        if (authToken.isNotEmpty()) {
            env["RESTIC_REST_USERNAME"] = authToken
            env["RESTIC_REST_PASSWORD"] = authToken
        }
        if (cacheDir.isNotEmpty()) {
            env["HOME"] = cacheDir
            env["XDG_CACHE_HOME"] = cacheDir
            val tmpDir = "$cacheDir/restic_tmp"
            env["TMPDIR"] = tmpDir
        }
        return env
    }

    /** Build environment for local repository. */
    fun buildLocalEnv(
        repoPath: String,
        password: String,
        cacheDir: String,
    ): Map<String, String> {
        // 从空白环境开始，不继承系统环境变量
        val env = HashMap<String, String>()
        env["RESTIC_REPOSITORY"] = repoPath
        env["RESTIC_PASSWORD"] = password
        if (cacheDir.isNotEmpty()) {
            env["HOME"] = cacheDir
            env["XDG_CACHE_HOME"] = cacheDir
            val tmpDir = "$cacheDir/restic_tmp"
            env["TMPDIR"] = tmpDir
        }
        return env
    }

    /** Build a display-friendly repository URL for UI. */
    fun buildRepoUrl(
        backend: String,
        repoPath: String,
        backendUrl: String,
    ): String =
        when (backend) {
            "local" -> repoPath
            "rest-server" -> "rest:${backendUrl.trimEnd('/')}/$repoPath"
            "webdav" -> "${backendUrl.trimEnd('/')}/$repoPath"
            "smb" -> "smb:${backendUrl.trimEnd('/')}/$repoPath"
            else -> repoPath
        }
}
