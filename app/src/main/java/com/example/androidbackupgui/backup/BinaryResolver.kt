package com.example.androidbackupgui.backup

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Resolves paths to binaries bundled in jniLibs.
 * Android's PackageManager extracts lib*.so from jniLibs to nativeLibraryDir.
 * We copy them to app-private dir (writable, executable) for ProcessBuilder use.
 */
object BinaryResolver {
    private const val TAG = "BinaryResolver"

    @Volatile
    private var _tarPath: String? = null

    @Volatile
    private var _zstdPath: String? = null

    /** Resolve and cache the path to the bundled tar binary. */
    fun tarPath(context: Context): String? {
        _tarPath?.let { return it }
        return resolve(context, "libtar_bin.so", "tar_bin").also { _tarPath = it }
    }

    /** Resolve and cache the path to the bundled zstd binary. */
    fun zstdPath(context: Context): String? {
        _zstdPath?.let { return it }
        return resolve(context, "libzstd_bin.so", "zstd_bin").also { _zstdPath = it }
    }

    private fun resolve(
        context: Context,
        libName: String,
        destName: String,
    ): String? {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val source = File(nativeLibDir, libName)
        if (!source.isFile) {
            Log.e(TAG, "$libName NOT FOUND at ${source.absolutePath}")
            return null
        }
        val dest = File(context.filesDir, "bin/$destName")
        if (!dest.exists() || dest.length() != source.length() || !dest.canExecute()) {
            dest.parentFile?.mkdirs()
            if (dest.exists()) dest.delete()
            source.inputStream().use { src -> dest.outputStream().use { out -> src.copyTo(out) } }
            dest.setExecutable(true)
        }
        Log.i(TAG, "ready: $libName -> ${dest.absolutePath} (${dest.length()} bytes) canExec=${dest.canExecute()}")
        return dest.absolutePath
    }
}
