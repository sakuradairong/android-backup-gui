package com.example.androidbackupgui.backup.security

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

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
        if (!PasswordManager.isInitialized()) {
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
                migratedRestic = PasswordManager.getResticPassword() == resticPassword
            }

            if (!backendPass.isNullOrEmpty() &&
                backendPass != "stored-in-keystore" &&
                PasswordManager.getBackendPass() == null
            ) {
                PasswordManager.setBackendPass(backendPass)
                migratedBackend = PasswordManager.getBackendPass() == backendPass
            }

            // 仅当至少一个字段真正迁移成功时才重写配置文件，并按字段独立替换：
            // 修复 v1.17 阶段 1-3 引入的 HIGH 问题：migratedRestic=true 且
            // migratedBackend=false 时，旧的实现仍会替换 restic_backend_pass，
            // 导致未迁移的 backend 明文被占位符覆盖丢失。
            var rewrote = false
            if (migratedRestic || migratedBackend) {
                val content = configFile.readText()
                val updated = redactField(
                    content = content,
                    fieldName = "restic_password",
                    shouldRedact = migratedRestic,
                )
                val finalContent = redactField(
                    content = updated,
                    fieldName = "restic_backend_pass",
                    shouldRedact = migratedBackend,
                )
                if (finalContent != content) {
                    rewrote = atomicWrite(configFile, finalContent)
                }
            }

            MigrationResult(migratedRestic, migratedBackend, rewrote)
        } catch (e: Exception) {
            MigrationResult(false, false, false, e.message)
        }
    }

    /**
     * 原子写入：先写临时文件 + fd.sync 强刷磁盘，再 renameTo() 覆盖原文件。
     *
     * 修复 v1.17 阶段 1-3 引入的 HIGH 问题：旧实现直接 configFile.writeText(updated)，
     * 若在 setPassword 成功但 writeText 失败/被 kill 之间崩溃，PasswordManager
     * 已有密码但配置文件仍为明文，下次启动会跳过迁移块，明文永久残留。
     *
     * 临时文件与原文件必须在同一文件系统（同 parentFile），否则 renameTo 可能失败；
     * 失败时回退到 copy+delete 路径。
     */
    private fun atomicWrite(target: File, content: String): Boolean {
        val parent = target.parentFile
            ?: throw IOException("atomicWrite: target has no parent dir: ${target.absolutePath}")
        val tmpFile = File(parent, "${target.name}.tmp.${System.nanoTime()}")
        return try {
            FileOutputStream(tmpFile).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.fd.sync() // 强刷到磁盘，避免断电后临时文件内容丢失
            }
            if (tmpFile.renameTo(target)) {
                true
            } else {
                // renameTo 跨设备 / 权限异常时可能失败：回退到 delete+rename。
                if (target.exists() && !target.delete()) {
                    throw IOException("atomicWrite: cannot delete target ${target.absolutePath}")
                }
                if (!tmpFile.renameTo(target)) {
                    throw IOException("atomicWrite: renameTo failed for ${target.absolutePath}")
                }
                true
            }
        } finally {
            if (tmpFile.exists()) tmpFile.delete()
        }
    }

    /**
     * 按字段名 + 开关独立替换配置文件中的明文密码为占位符。
     * [shouldRedact] 为 false 时直接返回原 content，不做任何修改。
     */
    private fun redactField(content: String, fieldName: String, shouldRedact: Boolean): String {
        if (!shouldRedact) return content
        val placeholder = "$fieldName=\"stored-in-keystore\""
        return content
            .replace(Regex("""${Regex.escape(fieldName)}[ \t]*=[ \t]*"[^"\r\n]*""""), placeholder)
            .replace(Regex("""${Regex.escape(fieldName)}[ \t]*=[ \t]*[^\r\n" \t]+"""), placeholder)
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
