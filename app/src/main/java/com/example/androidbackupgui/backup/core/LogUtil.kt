package com.example.androidbackupgui.backup.core

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * File-based logger with rotation support.
 * Writes logs to [baseDir]/logs/YYYY-MM-dd.log, keeping up to [maxDays] days.
 * Also dispatches to Android Logcat for real-time visibility.
 */
object LogUtil {

    private const val TAG = "LogUtil"
    private const val MAX_DAYS = 7

    private var baseDir: File? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(baseDir: File) {
        this.baseDir = baseDir
        executor.execute { rotateLogs() }
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        writeLog("D", tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        writeLog("I", tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        writeLog("W", tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable) {
        Log.w(tag, message, throwable)
        writeLog("W", tag, "$message — ${throwable.javaClass.simpleName}: ${throwable.message}")
    }

    fun e(tag: String, message: String) {
        Log.e(tag, message)
        writeLog("E", tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable) {
        Log.e(tag, message, throwable)
        writeLog("E", tag, "$message — ${throwable.javaClass.simpleName}: ${throwable.message}")
    }

    private fun writeLog(level: String, tag: String, message: String) {
        val dir = baseDir ?: return
        executor.execute {
            try {
                val today = dateFormat.format(Date())
                val logFile = File(File(dir, "logs"), "$today.log")
                logFile.parentFile?.mkdirs()
                val timestamp = timestampFormat.format(Date())
                val line = "$timestamp $level/$tag: $message\n"
                logFile.appendText(line)
            } catch (_: Exception) {
                // Silently fail — logging should never crash the app
            }
        }
    }

    private fun rotateLogs() {
        val dir = baseDir ?: return
        val logDir = File(dir, "logs")
        if (!logDir.exists()) return

        val cutoff = System.currentTimeMillis() - MAX_DAYS * 24L * 60 * 60 * 1000
        logDir.listFiles()
            ?.filter { it.name.endsWith(".log") }
            ?.forEach { file ->
                if (file.lastModified() < cutoff) {
                    file.delete()
                }
            }
    }

    /** Get all log files sorted by name (date ascending). */
    fun getLogFiles(): List<File> {
        val dir = baseDir ?: return emptyList()
        val logDir = File(dir, "logs")
        return logDir.listFiles()
            ?.filter { it.name.endsWith(".log") }
            ?.sortedBy { it.name }
            ?: emptyList()
    }
}
