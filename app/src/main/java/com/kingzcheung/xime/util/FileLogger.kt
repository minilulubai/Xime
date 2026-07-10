package com.kingzcheung.xime.util

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue

object FileLogger {
    private const val TAG = "FileLogger"
    private const val MAX_LOG_SIZE = 5 * 1024 * 1024
    private const val MAX_LOG_FILES = 10
    private const val QUEUE_CAPACITY = 4096
    
    private var logFile: File? = null
    private var logsDir: File? = null
    private var isInitialized = false
    
    private val logQueue = LinkedBlockingQueue<String>(QUEUE_CAPACITY)
    private var writer: BufferedWriter? = null
    private var running = false
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val fileDateFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    
    fun init(context: Context) {
        if (isInitialized) return
        try {
            logsDir = File(context.filesDir, "logs")
            if (!logsDir!!.exists()) {
                logsDir!!.mkdirs()
            }
            
            openLogFile()
            cleanOldLogs()
            cleanRimeLogs(context)
            cleanOrphanedLogs()
            
            running = true
            Thread({ flusherLoop() }, "log-flusher").also { it.isDaemon = true }.start()
            
            isInitialized = true
            i(TAG, "FileLogger initialized, log file: ${logFile!!.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize FileLogger", e)
        }
    }
    
    private fun openLogFile() {
        val today = fileDateFormat.format(Date())
        logFile = File(logsDir!!, "kime_$today.log")
        
        if (logFile!!.exists() && logFile!!.length() > MAX_LOG_SIZE) {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val newLogFile = File(logsDir!!, "kime_$ts.log")
            logFile!!.renameTo(newLogFile)
            logFile = File(logsDir!!, "kime_$today.log")
        }
        
        writer?.close()
        writer = BufferedWriter(FileWriter(logFile, true))
    }
    
    private fun flusherLoop() {
        val batch = ArrayList<String>(100)
        while (running) {
            try {
                batch.clear()
                batch.add(logQueue.take())
                logQueue.drainTo(batch, 99)
                
                val w = writer ?: continue
                for (line in batch) {
                    w.write(line)
                }
                w.flush()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                Log.e(TAG, "Log flusher error", e)
            }
        }
    }
    
    fun isInitialized(): Boolean = isInitialized
    
    fun v(tag: String, message: String) {
        Log.v(tag, message)
        writeToFile("V", tag, message)
    }
    
    fun d(tag: String, message: String) {
        Log.d(tag, message)
        writeToFile("D", tag, message)
    }
    
    fun i(tag: String, message: String) {
        Log.i(tag, message)
        writeToFile("I", tag, message)
    }
    
    fun w(tag: String, message: String) {
        Log.w(tag, message)
        writeToFile("W", tag, message)
    }
    
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        val fullMessage = if (throwable != null) {
            "$message\n${throwable.stackTraceToString()}"
        } else {
            message
        }
        writeToFile("E", tag, fullMessage)
    }
    
    fun wtf(tag: String, message: String, throwable: Throwable? = null) {
        Log.wtf(tag, message, throwable)
        val fullMessage = if (throwable != null) {
            "$message\n${throwable.stackTraceToString()}"
        } else {
            message
        }
        writeToFile("F", tag, fullMessage)
    }
    
    private fun writeToFile(level: String, tag: String, message: String) {
        if (!isInitialized) return
        try {
            val timestamp = dateFormat.format(Date())
            val logLine = "$timestamp [$level] $tag: $message\n"
            logQueue.offer(logLine)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to queue log", e)
        }
    }
    
    private fun cleanOldLogs() {
        try {
            logsDir?.let { dir ->
                val logFiles = dir.listFiles()
                    ?.filter { it.name.startsWith("kime_") && it.name.endsWith(".log") }
                    ?.sortedByDescending { it.lastModified() }
                    ?: emptyList()
                
                if (logFiles.size > MAX_LOG_FILES) {
                    logFiles.drop(MAX_LOG_FILES).forEach { oldFile ->
                        oldFile.delete()
                        Log.d(TAG, "Deleted old log file: ${oldFile.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clean old logs", e)
        }
    }
    
    fun getCurrentLogFile(): File? = logFile
    
    fun getAllLogFiles(): List<File> {
        return logsDir?.listFiles()
            ?.filter { it.name.startsWith("kime_") && it.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }
    
    fun clearAllLogs() {
        try {
            logsDir?.listFiles()
                ?.filter { it.name.startsWith("kime_") }
                ?.forEach { it.delete() }
            
            val today = fileDateFormat.format(Date())
            logFile = File(logsDir!!, "kime_$today.log")
            openLogFile()
            
            i(TAG, "All logs cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear logs", e)
        }
    }
    
    private fun cleanOrphanedLogs() {
        try {
            logsDir?.let { dir ->
                dir.listFiles()
                    ?.filter { it.name.endsWith(".log") && !it.name.startsWith("kime_") }
                    ?.forEach { file ->
                        if (file.length() > MAX_LOG_SIZE) {
                            file.delete()
                            Log.d(TAG, "Deleted orphaned log: ${file.name}")
                        }
                    }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clean orphaned logs", e)
        }
    }

    private fun cleanRimeLogs(context: Context) {
        try {
            val rimeLogDir = File(File(context.filesDir, "rime"), "logs")
            if (!rimeLogDir.exists()) return
            rimeLogDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".log") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_LOG_FILES)
                ?.forEach { file ->
                    file.delete()
                    Log.d(TAG, "Deleted rime log: ${file.name}")
                }
            rimeLogDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".log") && it.length() > MAX_LOG_SIZE }
                ?.forEach { file ->
                    file.delete()
                    Log.d(TAG, "Deleted oversized rime log: ${file.name}")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clean rime logs", e)
        }
    }

    fun reopenLogFile() {
        openLogFile()
    }

    fun flush() {
        writer?.flush()
    }
}