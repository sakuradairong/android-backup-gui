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

    private var tarPath: String? = null
    private var zstdPath: String? = null

    fun tarPath(context: Context): String? = cacheOrResolve(context, "libtar_bin.so", "tar_bin", ::tarPath) { tarPath = it }
    fun zstdPath(context: Context): String? = cacheOrResolve(context, "libzstd_bin.so", "zstd_bin", ::zstdPath) { zstdPath = it }

    private fun cacheOrResolve(
        context: Context, libName: String, destName: String,
        cache: () -> String?, setCache: (String?) -> Unit
    ): String? {
        val cached = cache()
        if (cached != null) return cached
        val resolved = resolve(context, libName, destName)
        setCache(resolved)
        return resolved
    }

    private fun resolve(context: Context, libName: String, destName: String): String? {
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
