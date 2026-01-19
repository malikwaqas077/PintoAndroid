package app.sst.pinto.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * FileLogger utility that:
 * - Creates a new log file every day
 * - Limits log file size to 4 MB
 * - Creates a new file when size limit is exceeded
 * - Thread-safe logging
 * - Automatically cleans up logs older than 30 days (1 month)
 */
class FileLogger private constructor(context: Context) {
    private val TAG = "FileLogger"
    
    private val context: Context = context.applicationContext
    private val logDir: File = File(context.getExternalFilesDir(null), "logs")
    private val maxFileSizeBytes = 4 * 1024 * 1024L // 4 MB
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    
    private var currentLogFile: File? = null
    private var currentDate: String = ""
    private var fileCounter: Int = 0
    private val lock = ReentrantLock()
    
    init {
        // Create logs directory if it doesn't exist
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
        
        // Initialize current date
        currentDate = dateFormat.format(Date())
        
        // Find the latest log file for today
        initializeLogFile()
        
        // Also log to Android Log for debugging
        Log.d(TAG, "FileLogger initialized. Log directory: ${logDir.absolutePath}")
    }
    
    /**
     * Initialize or get the current log file for today
     */
    private fun initializeLogFile() {
        lock.withLock {
            val today = dateFormat.format(Date())
            
            // If date changed, reset counter and update date
            if (today != currentDate) {
                currentDate = today
                fileCounter = 0
            }
            
            // Find the latest log file for today
            val todayFiles = logDir.listFiles { file ->
                file.name.startsWith("log_$currentDate") && file.name.endsWith(".txt")
            }?.sortedByDescending { it.name }
            
            if (todayFiles != null && todayFiles.isNotEmpty()) {
                // Check the latest file size
                val latestFile = todayFiles.first()
                if (latestFile.length() < maxFileSizeBytes) {
                    // Use existing file if it's under size limit
                    currentLogFile = latestFile
                    // Extract counter from filename: log_2024-01-01_0.txt
                    val nameWithoutExt = latestFile.nameWithoutExtension
                    val parts = nameWithoutExt.split("_")
                    if (parts.size >= 3) {
                        fileCounter = parts.last().toIntOrNull() ?: 0
                    }
                } else {
                    // Latest file is full, create new one
                    fileCounter = (todayFiles.size)
                    currentLogFile = createNewLogFile()
                }
            } else {
                // No files for today, create first one
                fileCounter = 0
                currentLogFile = createNewLogFile()
            }
        }
    }
    
    /**
     * Create a new log file with current date and counter
     */
    private fun createNewLogFile(): File {
        val fileName = "log_${currentDate}_${fileCounter}.txt"
        val file = File(logDir, fileName)
        try {
            file.createNewFile()
            Log.d(TAG, "Created new log file: ${file.absolutePath}")
        } catch (e: IOException) {
            Log.e(TAG, "Error creating log file: ${file.absolutePath}", e)
        }
        return file
    }
    
    /**
     * Get the current log file, creating a new one if needed
     */
    private fun getCurrentLogFile(): File {
        lock.withLock {
            val today = dateFormat.format(Date())
            
            // Check if date changed
            if (today != currentDate) {
                currentDate = today
                fileCounter = 0
                currentLogFile = createNewLogFile()
                return currentLogFile!!
            }
            
            // Check if current file exists and is under size limit
            val file = currentLogFile
            if (file != null && file.exists() && file.length() < maxFileSizeBytes) {
                return file
            }
            
            // Need to create a new file (either doesn't exist or exceeded size)
            fileCounter++
            currentLogFile = createNewLogFile()
            return currentLogFile!!
        }
    }
    
    /**
     * Write a log entry to the file
     */
    private fun writeLog(level: String, tag: String, message: String, throwable: Throwable? = null) {
        try {
            val logFile = getCurrentLogFile()
            val timestamp = timeFormat.format(Date())
            val threadName = Thread.currentThread().name
            
            val logEntry = StringBuilder()
            logEntry.append("[$timestamp] [$level] [$threadName] [$tag] $message")
            if (throwable != null) {
                logEntry.append("\n")
                logEntry.append(throwable.stackTraceToString())
            }
            logEntry.append("\n")
            
            FileWriter(logFile, true).use { writer ->
                writer.append(logEntry.toString())
                writer.flush()
            }
            
            // Also log to Android Log for immediate debugging
            when (level) {
                "DEBUG" -> Log.d(tag, message, throwable)
                "INFO" -> Log.i(tag, message, throwable)
                "WARN" -> Log.w(tag, message, throwable)
                "ERROR" -> Log.e(tag, message, throwable)
            }
        } catch (e: Exception) {
            // Fallback to Android Log if file writing fails
            Log.e(TAG, "Error writing to log file", e)
            Log.e(tag, message, throwable)
        }
    }
    
    /**
     * Log a debug message
     */
    fun d(tag: String, message: String) {
        writeLog("DEBUG", tag, message)
    }
    
    /**
     * Log an info message
     */
    fun i(tag: String, message: String) {
        writeLog("INFO", tag, message)
    }
    
    /**
     * Log a warning message
     */
    fun w(tag: String, message: String) {
        writeLog("WARN", tag, message)
    }
    
    /**
     * Log an error message
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        writeLog("ERROR", tag, message, throwable)
    }
    
    /**
     * Get all log files sorted by date (newest first)
     */
    fun getLogFiles(): List<File> {
        return logDir.listFiles { file ->
            file.name.startsWith("log_") && file.name.endsWith(".txt")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
    
    /**
     * Get the log directory path
     */
    fun getLogDirectory(): File {
        return logDir
    }
    
    /**
     * Clear old log files (older than specified days)
     * Default is 30 days (1 month)
     */
    fun clearOldLogs(daysToKeep: Int = DEFAULT_LOG_RETENTION_DAYS) {
        try {
            val cutoffTime = System.currentTimeMillis() - (daysToKeep * 24 * 60 * 60 * 1000L)
            val files = logDir.listFiles()
            var deletedCount = 0
            files?.forEach { file ->
                if (file.lastModified() < cutoffTime && file.name.startsWith("log_")) {
                    file.delete()
                    deletedCount++
                    Log.d(TAG, "Deleted old log file: ${file.name}")
                }
            }
            if (deletedCount > 0) {
                Log.d(TAG, "Cleaned up $deletedCount old log file(s) (keeping last $daysToKeep days)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing old logs", e)
        }
    }
    
    companion object {
        /**
         * Number of days to keep log files before automatic cleanup
         * Default: 30 days (1 month)
         */
        const val DEFAULT_LOG_RETENTION_DAYS = 30
        
        @Volatile
        private var instance: FileLogger? = null
        
        fun getInstance(context: Context): FileLogger {
            return instance ?: synchronized(this) {
                instance ?: FileLogger(context.applicationContext).also { instance = it }
            }
        }
    }
}

