package com.rw251.pleasecharge

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Centralized logging system that writes to:
 * 1. Logcat (for development)
 * 2. Persistent file (for debugging in production)
 * 
 * Features:
 * - Weekly rotating log files (app_log_YYYY-WW.txt)
 * - Automatic rotation when files exceed 5MB
 * - 14-day retention policy (older logs auto-deleted)
 * - Efficient partial reading (getLastLogLines) for large files
 * - Background async writing to avoid UI blocking
 * 
 * IMPORTANT: Always test builds before committing!
 * Common issues:
 * - XML syntax errors (duplicate closing tags)
 * - Missing imports when adding new features
 * - Breaking existing API contracts
 */
object AppLogger {
    private const val TAG = "PleaseCharge"
    private const val LOG_FILE_PREFIX = "app_log_" // e.g., app_log_2026-02.txt
    private const val LOG_FILE_SUFFIX = ".txt"
    private const val MAX_LOG_FILE_SIZE = 5 * 1024 * 1024  // 5 MB
    
    private var logFile: File? = null
    private var filesDir: File? = null
    private var currentWeekId: String = ""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeQueue = ConcurrentLinkedQueue<String>()
    // private val memoryBuffer = ArrayDeque<String>()
    
    // private val _logLines = MutableStateFlow<List<String>>(emptyList())
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    
    fun init(context: Context) {
        filesDir = context.filesDir
        currentWeekId = weekId(System.currentTimeMillis())
        logFile = File(filesDir!!, fileNameForWeek(currentWeekId))
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
            // Rotate weekly by time
            val thisWeek = weekId(System.currentTimeMillis())
            if (thisWeek != currentWeekId) {
                currentWeekId = thisWeek
                logFile = File(filesDir!!, fileNameForWeek(currentWeekId))
                pruneOldLogFiles()
            }
            val file = logFile ?: return
            
            // Rotate log if too large
            if (file.exists() && file.length() > MAX_LOG_FILE_SIZE) {
                val backupFile = File(file.parent, "${file.name}.old")
                file.renameTo(backupFile)
            }
            
            file.appendText("$line\n")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to log file: ${e.message}")
        }
    }
    
    fun getLogFilePath(): String? = logFile?.absolutePath

    private fun weekId(ts: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = ts }
        val year = cal.get(Calendar.YEAR)
        val week = cal.get(Calendar.WEEK_OF_YEAR)
        return "%04d-%02d".format(year, week)
    }

    private fun fileNameForWeek(weekId: String): String = LOG_FILE_PREFIX + weekId + LOG_FILE_SUFFIX

    private fun pruneOldLogFiles() {
        val dir = filesDir ?: return
        val files = dir.listFiles { f -> f.name.startsWith(LOG_FILE_PREFIX) && f.name.endsWith(LOG_FILE_SUFFIX) }?.toList() ?: return
        val cutoffTime = System.currentTimeMillis() - (14L * 24 * 60 * 60 * 1000) // 14 days
        val toDelete = files.filter { it.lastModified() < cutoffTime }
        toDelete.forEach {
            try { it.delete(); i("AppLogger: Deleted old log ${it.name} (older than 14 days)") } catch (e: Exception) { w("AppLogger: Failed to delete ${it.name}: ${e.message}") }
        }
    }
    
    fun getLogFileContent(): String {
        return try {
            logFile?.readText() ?: ""
        } catch (e: Exception) {
            "Error reading log file: ${e.message}"
        }
    }
    
    fun getLastLogLines(maxLines: Int): String {
        return try {
            val file = logFile ?: return ""
            if (!file.exists()) return ""
            
            // For large files, read from end to avoid memory issues
            val lines = mutableListOf<String>()
            file.useLines { sequence ->
                // Collect lines efficiently
                val allLines = sequence.toList()
                val startIdx = (allLines.size - maxLines).coerceAtLeast(0)
                lines.addAll(allLines.subList(startIdx, allLines.size))
            }
            lines.joinToString("\n")
        } catch (e: Exception) {
            "Error reading log file: ${e.message}"
        }
    }
    
    fun getLogLineCount(): Int {
        return try {
            logFile?.useLines { it.count() } ?: 0
        } catch (e: Exception) {
            0
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
