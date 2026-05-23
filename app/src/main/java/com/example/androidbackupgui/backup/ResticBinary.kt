package com.example.androidbackupgui.backup

import android.content.Context
import android.util.Log
import java.io.File

object ResticBinary {

    private const val TAG = "ResticBinary"
    private const val BINARY_NAME = "librestic.so"

    fun prepare(context: Context): String? {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        Log.d(TAG, "nativeLibraryDir = $nativeLibDir")

        val path = File(nativeLibDir, BINARY_NAME)
        Log.d(TAG, "checking path = ${path.absolutePath}")
        Log.d(TAG, "exists = ${path.exists()}, isFile = ${path.isFile}, length = ${path.length()}, canExecute = ${path.canExecute()}, canRead = ${path.canRead()}")

        // List what's actually in the native lib dir
        File(nativeLibDir).listFiles()?.let { files ->
            Log.d(TAG, "nativeLibDir contents: ${files.joinToString { it.name }}")
        } ?: Log.d(TAG, "nativeLibDir listFiles returned null")

        if (!path.isFile) {
            Log.e(TAG, "librestic.so NOT FOUND at ${path.absolutePath}")
            return null
        }

        Log.i(TAG, "librestic.so ready at ${path.absolutePath} (${path.length()} bytes)")
        return path.absolutePath
    }

    fun prepareRclone(context: Context): String? {
        val path = File(context.applicationInfo.nativeLibraryDir, "librclone.so")
        Log.d(TAG, "rclone path = ${path.absolutePath}, exists=${path.isFile}, len=${path.length()}")
        if (!path.isFile) {
            Log.e(TAG, "librclone.so NOT FOUND at ${path.absolutePath}")
            return null
        }
        Log.i(TAG, "librclone.so ready at ${path.absolutePath} (${path.length()} bytes)")
        return path.absolutePath
    }

    fun isReady(): Boolean = false // call prepare() instead
}
