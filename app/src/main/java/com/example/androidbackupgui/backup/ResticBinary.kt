package com.example.androidbackupgui.backup

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * Manages the restic native binary on the device.
 *
 * On first run, extracts the correct ABI binary from APK assets to the app's
 * filesDir and sets executable permission. On subsequent runs, reuses the
 * already-extracted binary.
 *
 * Expected asset layout:
 *   assets/restic/arm64-v8a/restic
 *   assets/restic/armeabi-v7a/restic
 *   assets/restic/x86_64/restic
 */
object ResticBinary {

    private const val ASSET_PREFIX = "restic"
    private const val BINARY_NAME = "restic"

    @Volatile
    private var extractedPath: String? = null

    /**
     * Prepare the restic binary and return its absolute path.
     * Extracts from assets if not already present.
     *
     * @return the absolute path to the restic binary, or null if extraction failed.
     */
    fun prepare(context: Context): String? {
        extractedPath?.let { path ->
            val f = File(path)
            if (f.exists() && f.length() > 0) return path
        }

        val abi = detectAbi()
        val destFile = File(context.filesDir, BINARY_NAME)

        // Already extracted — executable not required (linker trampoline)
        if (destFile.exists() && destFile.length() > 0) {
            extractedPath = destFile.absolutePath
            return extractedPath
        }

        val assetPath = "$ASSET_PREFIX/$abi/$BINARY_NAME"
        val success = try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            destFile.setReadable(true, false)
            // setExecutable may fail on modern Android (W^X) — that's fine,
            // the linker trampoline only needs read permission
            destFile.setExecutable(true, false)

            destFile.length() > 0
        } catch (e: Exception) {
            tryFallbackExtraction(context, destFile)
        }

        if (!success) return null

        extractedPath = destFile.absolutePath
        return extractedPath
    }

    /** Try extracting from alternate ABI asset paths. */
    private fun tryFallbackExtraction(context: Context, destFile: File): Boolean {
        val fallbackAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        for (abi in fallbackAbis) {
            try {
                context.assets.open("$ASSET_PREFIX/$abi/$BINARY_NAME").use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
                destFile.setReadable(true, false)
                destFile.setExecutable(true, false)
                if (destFile.length() > 0) return true
            } catch (_: Exception) {
                // try next
            }
        }
        return false
    }

    /**
     * Check if the binary is ready (extracted and readable). Does NOT extract.
     */
    fun isReady(): Boolean {
        val path = extractedPath ?: return false
        val f = File(path)
        return f.exists() && f.length() > 0
    }

    /** Detect the device ABI from Build.SUPPORTED_ABIS. */
    private fun detectAbi(): String {
        val abis = android.os.Build.SUPPORTED_ABIS ?: arrayOf("arm64-v8a")
        return when {
            abis.any { it.contains("arm64") || it.contains("aarch64") } -> "arm64-v8a"
            abis.any { it.contains("armeabi") } -> "armeabi-v7a"
            abis.any { it.contains("x86_64") } -> "x86_64"
            abis.any { it.contains("x86") } -> "x86"
            else -> abis.firstOrNull() ?: "arm64-v8a"
        }
    }
}
