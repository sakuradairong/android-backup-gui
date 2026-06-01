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

    private var cacheInit = false
    private var cachedTar: String? = null
    private var cachedZstd: String? = null

    fun tarPath(context: Context): String? = resolve(context, "libtar_bin.so", "tar_bin")
    fun zstdPath(context: Context): String? = resolve(context, "libzstd_bin.so", "zstd_bin")

    private fun resolve(context: Context, libName: String, destName: String): String? {
        if (cacheInit) {
            val cached = if (libName.contains("tar")) cachedTar else cachedZstd
            if (cached != null) return cached
        }

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
            source.inputStream().use { src ->
                dest.outputStream().use { out ->
                    src.copyTo(out)
                }
            }
            dest.setExecutable(true)
        }

        val result = dest.absolutePath
        Log.i(TAG, "ready: $libName -> $result (${dest.length()} bytes) canExec=${dest.canExecute()}")
        if (libName.contains("tar")) cachedTar = result else cachedZstd = result
        cacheInit = true
        return result
    }
}
