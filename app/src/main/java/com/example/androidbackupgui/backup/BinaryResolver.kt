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

    private val cacheTar = ResolveCache()
    private val cacheZstd = ResolveCache()

    private class ResolveCache {
        var initialized = false
        var path: String? = null
    }

    fun tarPath(context: Context): String? = resolve(context, "libtar_bin.so", "tar_bin", cacheTar)
    fun zstdPath(context: Context): String? = resolve(context, "libzstd_bin.so", "zstd_bin", cacheZstd)

    private fun resolve(context: Context, libName: String, destName: String, cache: ResolveCache): String? {
        if (cache.initialized) return cache.path
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val source = File(nativeLibDir, libName)
        if (!source.isFile) {
            Log.e(TAG, "$libName NOT FOUND at ${source.absolutePath}")
            cache.initialized = true
            cache.path = null
            return null
        }
        val dest = File(context.filesDir, "bin/$destName")
        if (!dest.exists() || dest.length() != source.length() || !dest.canExecute()) {
            dest.parentFile?.mkdirs()
            if (dest.exists()) dest.delete()
            source.inputStream().use { src -> dest.outputStream().use { out -> src.copyTo(out) } }
            dest.setExecutable(true)
        }
        val result = dest.absolutePath
        Log.i(TAG, "ready: $libName -> $result (${dest.length()} bytes) canExec=${dest.canExecute()}")
        cache.path = result
        cache.initialized = true
        return result
    }
}
