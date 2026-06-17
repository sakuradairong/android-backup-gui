package com.example.androidbackupgui.backup.core

object LogSanitizer {

    private val PASSWORD_KEYS = listOf(
        "RESTIC_PASSWORD",
        "restic_password",
        "restic_backend_pass",
        "backend_pass",
        "password",
        "psk",
    )

    private val SENSITIVE_HEADERS = listOf(
        "Authorization",
        "authorization",
        "AUTHORIZATION",
    )

    private val URL_USERINFO = Regex("""(https?://)([^@/]+)@""")

    private val PASSWORD_ASSIGN = Regex(
        PASSWORD_KEYS.joinToString("|") { key ->
            """\b${Regex.escape(key)}\s*=\s*\S+"""
        },
        RegexOption.IGNORE_CASE
    )

    private val HEADER_ASSIGN = Regex(
        SENSITIVE_HEADERS.joinToString("|") { key ->
            """\b${Regex.escape(key)}\s*:\s*\S+"""
        }
    )

    fun redact(text: String): String {
        var result = text
        result = PASSWORD_ASSIGN.replace(result) { match ->
            val eqIdx = match.value.indexOf('=')
            if (eqIdx >= 0) "${match.value.substring(0, eqIdx + 1)}<redacted>" else "<redacted>"
        }
        result = HEADER_ASSIGN.replace(result) { match ->
            val colonIdx = match.value.indexOf(':')
            if (colonIdx >= 0) "${match.value.substring(0, colonIdx + 1)} <redacted>" else "<redacted>"
        }
        result = URL_USERINFO.replace(result) { match ->
            "${match.groupValues[1]}<redacted>@"
        }
        return result
    }

    fun redactCommand(command: String): String {
        return redact(command)
    }
}
