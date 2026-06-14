package com.example.androidbackupgui.backup.security

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
            val path = File(nativeLibDir, BINARY_NAME)
            Log.d(TAG, "nativeLibraryDir=$nativeLibDir exists=${path.isFile} len=${path.length()} canExec=${path.canExecute()}")

            cachedBinaryPath = if (path.isFile) {
                Log.i(TAG, "librestic.so ready at ${path.absolutePath} (${path.length()} bytes)")
                path.absolutePath
            } else {
                Log.e(TAG, "librestic.so NOT FOUND at ${path.absolutePath}")
                null
            }
            cacheInit = true
            return cachedBinaryPath
        }
    }


    fun isReady(): Boolean = cachedBinaryPath != null
}
