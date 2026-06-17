package com.example.androidbackupgui.backup.security

import java.io.File

object LegacyCredentialMigrator {

    data class MigrationResult(
        val migratedResticPassword: Boolean,
        val migratedBackendPass: Boolean,
        val rewroteFile: Boolean,
        val error: String? = null,
    )

    fun migrate(configFile: File): MigrationResult {
        if (!configFile.exists()) {
            return MigrationResult(false, false, false)
        }

        return try {
            val lines = configFile.readLines()
            var resticPassword: String? = null
            var backendPass: String? = null

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

                val eq = trimmed.indexOf('=')
                if (eq < 0) continue
                val key = trimmed.substring(0, eq).trim()
                val rawValue = trimmed.substring(eq + 1).trim()

                if (key == "restic_password") {
                    resticPassword = unquote(rawValue)
                } else if (key == "restic_backend_pass") {
                    backendPass = unquote(rawValue)
                }
            }

            var migratedRestic = false
            var migratedBackend = false

            if (!resticPassword.isNullOrEmpty() &&
                resticPassword != "stored-in-keystore" &&
                !PasswordManager.hasResticPassword()
            ) {
                PasswordManager.setResticPassword(resticPassword)
                migratedRestic = true
            }

            if (!backendPass.isNullOrEmpty() &&
                backendPass != "stored-in-keystore" &&
                PasswordManager.getBackendPass() == null
            ) {
                PasswordManager.setBackendPass(backendPass)
                migratedBackend = true
            }

            var rewrote = false
            if (migratedRestic || migratedBackend) {
                val content = configFile.readText()
                val updated = content
                    .replace(Regex("""restic_password\s*=\s*"[^"]*""""), """restic_password="stored-in-keystore"""")
                    .replace(Regex("""restic_password\s*=\s*[^"\s]+"""), """restic_password="stored-in-keystore"""")
                    .replace(Regex("""restic_backend_pass\s*=\s*"[^"]*""""), """restic_backend_pass="stored-in-keystore"""")
                    .replace(Regex("""restic_backend_pass\s*=\s*[^"\s]+"""), """restic_backend_pass="stored-in-keystore"""")
                if (updated != content) {
                    configFile.writeText(updated)
                    rewrote = true
                }
            }

            MigrationResult(migratedRestic, migratedBackend, rewrote)
        } catch (e: Exception) {
            MigrationResult(false, false, false, e.message)
        }
    }

    private fun unquote(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.length >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length - 1)
                .replace("\\\\", "\\")
                .replace("\\\"", "\"")
        }
        return trimmed.removeSurrounding("\"")
    }
}
