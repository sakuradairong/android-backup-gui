package com.example.androidbackupgui.backup

/**
 * Stateless helper for constructing restic environment variables and repo URLs.
 */
class ResticEnvResolver {

    /** Build environment for restic. For SMB/WebDAV backends, uses local temp dir as repo. */
    fun buildFullEnv(
        repoPath: String,
        password: String,
        backend: String = "local",
        backendUrl: String = "",
        backendUser: String = "",
        backendPass: String = "",
        backendShare: String = "",
        tempRepoDir: String = ""
    ): Map<String, String> {
        val env = HashMap(System.getenv() ?: emptyMap())
        env["RESTIC_REPOSITORY"] = if (backend == "smb" || backend == "webdav") {
            tempRepoDir
        } else {
            buildRepoUrl(backend, repoPath, backendUrl)
        }
        env["RESTIC_PASSWORD"] = password
        // Provide a cache directory on Android (no $HOME by default)
        if (tempRepoDir.isNotEmpty()) {
            val cacheDir = tempRepoDir.substringBeforeLast("/") + "/restic_cache"
            env["HOME"] = cacheDir
            env["XDG_CACHE_HOME"] = cacheDir
        }
        return env
    }

    /** Build a display-friendly repository URL for UI. */
    fun buildRepoUrl(backend: String, repoPath: String, backendUrl: String): String {
        return when (backend) {
            "local" -> repoPath
            "rest-server" -> "rest:${backendUrl.trimEnd('/')}/$repoPath"
            "webdav" -> "${backendUrl.trimEnd('/')}/$repoPath"
            "smb" -> "smb:${backendUrl.trimEnd('/')}/$repoPath"
            else -> repoPath
        }
    }
}
