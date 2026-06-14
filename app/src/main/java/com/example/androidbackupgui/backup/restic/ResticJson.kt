package com.example.androidbackupgui.backup.restic

import kotlinx.serialization.json.Json

/** Shared Json instance configured for restic's snake_case output via @SerialName. */
internal val resticJson = Json { ignoreUnknownKeys = true }
