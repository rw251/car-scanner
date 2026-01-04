package com.rw251.pleasecharge

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Centralized logging system that writes to:
 * 1. Logcat (for development)
 * 2. Persistent file (for debugging in production)
 * 3. In-memory buffer (for UI display)
 */
object AppLogger {
    private const val TAG = "PleaseCharge"
    private const val LOG_FILE_NAME = "app_log.txt"
    private const val MAX_LOG_FILE_SIZE = 5 * 1024 * 1024  // 5 MB
    private const val MAX_MEMORY_LINES = 500
    
    private var logFile: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeQueue = ConcurrentLinkedQueue<String>()
    // private val memoryBuffer = ArrayDeque<String>()
    
    // private val _logLines = MutableStateFlow<List<String>>(emptyList())
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    
    fun init(context: Context) {
        logFile = File(context.filesDir, LOG_FILE_NAME)
        i("AppLogger initialized - log file: ${logFile?.absolutePath}")
        
        // Start background writer
        scope.launch {
            processWriteQueue()
        }
    }
    
    fun d(message: String, tag: String = TAG) {
        log("DEBUG", message, tag)
    }
    
    fun i(message: String, tag: String = TAG) {
        log("INFO", message, tag)
    }
    
    fun w(message: String, tag: String = TAG) {
        log("WARN", message, tag)
    }
    
    fun e(message: String, throwable: Throwable? = null, tag: String = TAG) {
        val fullMessage = if (throwable != null) {
            "$message: ${throwable.message}\n${throwable.stackTraceToString()}"
        } else {
            message
        }
        log("ERROR", fullMessage, tag)
    }
    
    private fun log(level: String, message: String, tag: String) {
        val timestamp = dateFormat.format(Date())
        val formattedLine = "$timestamp [$level] $tag: $message"
        
        // Log to logcat
        when (level) {
            "DEBUG" -> Log.d(tag, message)
            "INFO" -> Log.i(tag, message)
            "WARN" -> Log.w(tag, message)
            "ERROR" -> Log.e(tag, message)
        }
        
        // Add to memory buffer
        // synchronized(memoryBuffer) {
        //     memoryBuffer.add(formattedLine)
        //     if (memoryBuffer.size > MAX_MEMORY_LINES) {
        //         memoryBuffer.removeFirst()
        //     }
        //     // _logLines.value = memoryBuffer.toList()
        // }
        
        // Queue for file write
        writeQueue.offer(formattedLine)
    }
    
    private suspend fun processWriteQueue() {
        while (true) {
            val line = writeQueue.poll()
            if (line != null) {
                writeToFile(line)
            } else {
                kotlinx.coroutines.delay(100)
            }
        }
    }
    
    private fun writeToFile(line: String) {
        try {
            val file = logFile ?: return
            
            // Rotate log if too large
            if (file.exists() && file.length() > MAX_LOG_FILE_SIZE) {
                val backupFile = File(file.parent, "${LOG_FILE_NAME}.old")
                file.renameTo(backupFile)
            }
            
            file.appendText("$line\n")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to log file: ${e.message}")
        }
    }
    
    fun getLogFilePath(): String? = logFile?.absolutePath
    
    fun getLogFileContent(): String {
        return try {
            logFile?.readText() ?: ""
        } catch (e: Exception) {
            "Error reading log file: ${e.message}"
        }
    }
    
    fun clearLogs() {
        try {
            logFile?.writeText("")
            // synchronized(memoryBuffer) {
            //     memoryBuffer.clear()
            //     // _logLines.value = emptyList()
            // }
            i("Logs cleared")
        } catch (e: Exception) {
            e("Failed to clear logs", e)
        }
    }
}
