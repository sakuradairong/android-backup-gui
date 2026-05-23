package com.example.androidbackupgui.backup

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.File

object ResticBinary {

    private const val TAG = "ResticBinary"
    private const val BINARY_NAME = "librestic.so"

    fun prepare(context: Context): String? {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        Log.d(TAG, "nativeLibraryDir = $nativeLibDir")

        val path = File(nativeLibDir, BINARY_NAME)
        Log.d(TAG, "restic: exists=${path.isFile} len=${path.length()} canExec=${path.canExecute()}")

        if (!path.isFile) {
            Log.e(TAG, "librestic.so NOT FOUND at ${path.absolutePath}")
            return null
        }

        Log.i(TAG, "librestic.so ready at ${path.absolutePath} (${path.length()} bytes)")
        return path.absolutePath
    }

    /**
     * Prepare the rclone binary. Since Android requires native libs to be
     * named lib*.so, restic can't find them via PATH lookup (it looks for
     * `rclone` not `librclone.so`). We create a symlink `rclone` in filesDir
     * pointing to the real binary in nativeLibraryDir.
     */
    fun prepareRclone(context: Context): String? {
        val libPath = File(context.applicationInfo.nativeLibraryDir, "librclone.so")
        Log.d(TAG, "rclone lib: exists=${libPath.isFile} len=${libPath.length()}")

        if (!libPath.isFile) {
            Log.e(TAG, "librclone.so NOT FOUND at ${libPath.absolutePath}")
            return null
        }

        val symlink = File(context.filesDir, "rclone")
        if (!symlink.exists()) {
            try {
                symlink.delete() // clean up any stale non-symlink
                Os.symlink(libPath.absolutePath, symlink.absolutePath)
                Log.i(TAG, "rclone symlink created: ${symlink.absolutePath} -> ${libPath.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create rclone symlink, trying copy", e)
                try {
                    libPath.copyTo(symlink, overwrite = true)
                    symlink.setExecutable(true)
                    Log.i(TAG, "rclone copied to ${symlink.absolutePath}")
                } catch (e2: Exception) {
                    Log.e(TAG, "Failed to copy rclone", e2)
                    return null
                }
            }
        }

        Log.i(TAG, "rclone ready at ${symlink.absolutePath} (${symlink.length()} bytes)")
        return symlink.absolutePath
    }

    fun isReady(): Boolean = false // call prepare() instead
}
