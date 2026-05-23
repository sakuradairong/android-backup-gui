package com.example.androidbackupgui.backup

import android.content.Context
import java.io.File

/**
 * Provides the path to the restic binary on the device.
 *
 * The binary is packaged as a native library in jniLibs/<abi>/librestic.so.
 * The Android package manager extracts it to nativeLibDir which is executable
 * (unlike filesDir which is mounted noexec on API 29+).
 *
 * The binary is statically linked (Go) so it runs directly without a
 * dynamic linker trampoline.
 */
object ResticBinary {

    private const val BINARY_NAME = "librestic.so"

    /**
     * Return the absolute path to the restic binary, or null if not found.
     */
    fun prepare(context: Context): String? {
        val path = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME).absolutePath
        if (!File(path).isFile) return null
        return path
    }

    fun isReady(): Boolean = false // call prepare() instead
}
