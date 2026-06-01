package com.example.androidbackupgui.backup

import android.content.Context
import android.util.Log
import java.io.File

object ResticBinary {

    private const val TAG = "ResticBinary"
    private const val BINARY_NAME = "librestic.so"

    /** Cached result of prepare() — avoids repeated disk I/O on the main thread. */
    @Volatile private var cachedBinaryPath: String? = null
    @Volatile private var cacheInit: Boolean = false

    fun prepare(context: Context): String? {
        if (cacheInit) return cachedBinaryPath
        synchronized(this) {
            if (cacheInit) return cachedBinaryPath
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val source = File(nativeLibDir, BINARY_NAME)
            Log.d(TAG, "nativeLibraryDir=$nativeLibDir srcExists=${source.isFile} srcLen=${source.length()} srcCanExec=${source.canExecute()}")

            if (!source.isFile) {
                Log.e(TAG, "librestic.so NOT FOUND at ${source.absolutePath}")
                cacheInit = true
                cachedBinaryPath = null
                return null
            }

            // Copy to app-private dir: native lib dir may be mounted noexec on
            // Android 10+, preventing direct ProcessBuilder execution.
            val dest = File(context.filesDir, "restic_bin/librestic")
            if (!dest.exists() || dest.length() != source.length() || !dest.canExecute()) {
                dest.parentFile?.mkdirs()
                if (dest.exists()) dest.delete()
                source.inputStream().use { src ->
                    dest.outputStream().use { out ->
                        src.copyTo(out)
                    }
                }
            }
            dest.setExecutable(true)
            Log.i(TAG, "restic ready: src=${source.absolutePath} dest=${dest.absolutePath} (${dest.length()} bytes) canExec=${dest.canExecute()}")
            cachedBinaryPath = dest.absolutePath
            cacheInit = true
            return cachedBinaryPath
        }
    }

    /** Get the temp directory used as local restic repo for remote backends. */
    fun getTempRepoDir(context: Context): String {
        val dir = File(context.cacheDir, "restic_remote_repo")
        dir.mkdirs()
        Log.d(TAG, "tempRepoDir = ${dir.absolutePath}")
        return dir.absolutePath
    }

    fun isReady(): Boolean = false // call prepare() instead
}
