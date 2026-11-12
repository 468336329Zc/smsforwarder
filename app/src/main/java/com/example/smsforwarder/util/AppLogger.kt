package com.example.smsforwarder.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    private const val TAG = "AppLogger"
    private const val LOG_DIR_NAME = "logs"
    private const val LOG_FILE_NAME = "runtime.log"
    private const val MAX_LINES = 500
    private const val TRIM_CHECK_INTERVAL = 20

    private var logFile: File? = null
    private val writeLock = Any()
    private var writesSinceTrim = 0
    private val timeFormat = ThreadLocal.withInitial {
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
    }

    fun init(context: Context) {
        if (logFile != null) return
        synchronized(writeLock) {
            if (logFile == null) {
                val dir = File(context.applicationContext.filesDir, LOG_DIR_NAME)
                dir.mkdirs()
                logFile = File(dir, LOG_FILE_NAME)
            }
        }
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        writeToFile(tag, message)
    }

    fun e(tag: String, message: String) {
        Log.e(tag, message)
        writeToFile("ERROR/$tag", message)
    }

    private fun writeToFile(tag: String, message: String) {
        val file = logFile ?: return
        val ts = timeFormat.get()?.format(Date()) ?: ""
        val line = "$ts [$tag] $message\n"
        synchronized(writeLock) {
            try {
                file.appendText(line)
                writesSinceTrim++
                if (writesSinceTrim >= TRIM_CHECK_INTERVAL) {
                    writesSinceTrim = 0
                    trimIfNeeded(file)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "写入日志文件失败", t)
            }
        }
    }

    private fun trimIfNeeded(file: File) {
        try {
            val lines = file.readLines()
            if (lines.size > MAX_LINES) {
                val kept = lines.takeLast(MAX_LINES).joinToString("\n") + "\n"
                file.writeText(kept)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "裁剪日志文件失败", t)
        }
    }

    fun readAll(): List<String> {
        val file = logFile ?: return emptyList()
        if (!file.exists()) return emptyList()
        return try {
            file.readLines().filter { it.isNotBlank() }
        } catch (t: Throwable) {
            Log.w(TAG, "读取日志文件失败", t)
            emptyList()
        }
    }

    fun clear() {
        val file = logFile ?: return
        synchronized(writeLock) {
            try {
                file.writeText("")
            } catch (t: Throwable) {
                Log.w(TAG, "清空日志文件失败", t)
            }
        }
    }
}
