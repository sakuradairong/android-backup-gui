package com.example.androidbackupgui.backup

import kotlinx.serialization.json.Json

/** Shared Json instance configured for restic's snake_case output via @SerialName. */
internal val resticJson = Json { ignoreUnknownKeys = true }
