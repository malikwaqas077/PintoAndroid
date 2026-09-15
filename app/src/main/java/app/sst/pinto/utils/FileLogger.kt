package app.sst.pinto.utils

import android.content.Context
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * FileLogger utility that:
 * - Creates a new log file every day (log_yyyy-MM-dd_N.txt)
 * - Limits log file size to 4 MB, rolling to the next counter when exceeded
 * - Writes on a single background thread so callers (including the UI thread)
 *   never block on disk I/O; [flush] waits for pending entries when it matters
 * - Automatically cleans up logs older than 60 days (2 months)
 * - Enforces max total size and max file count caps
 * - Accepts lines captured from logcat (see [LogcatCollector]) so third-party
 *   SDK output lands in the same files as the app's own entries
 *
 * These files are what the Ask portal pulls through /deviceHub (logs_list / logs_get).
 */
class FileLogger internal constructor(
    private val logDir: File,
    private val maxFileSizeBytes: Long = 4 * 1024 * 1024L, // 4 MB
    private val maxTotalSizeBytes: Long = 200L * 1024L * 1024L, // 200 MB cap across all log files
    private val maxFileCount: Int = 120, // hard cap to avoid file explosion
    private val mirrorToLogcat: Boolean = true
) {
    private val TAG = "FileLogger"

    private class Entry(
        val timeMs: Long,
        val level: String,
        val thread: String,
        val tag: String,
        val message: String,
        val throwable: Throwable?,
        val suppressedBefore: Int
    )

    private class FlushMarker(val latch: CountDownLatch)

    private val queue = LinkedBlockingQueue<Any>(MAX_QUEUED_ENTRIES)
    private val droppedEntries = AtomicInteger(0)

    // --- Writer-thread state (only touched on the writer thread) ---------------
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var currentLogFile: File? = null
    private var currentDate: String = ""
    private var fileCounter: Int = 0
    private var currentFileBytes: Long = 0L
    private var capsCheckDue = true
    private var lastCapsCheckMs = 0L

    // --- Duplicate suppression -------------------------------------------------
    // Repeating, identical log lines (e.g. the WebSocket reconnect loop emitting
    // "No route to host" every few seconds) would otherwise flood the file. We
    // write the first occurrence in full, then suppress identical entries and
    // re-emit at most once per window with a count of how many were collapsed.
    private data class DupState(var lastWriteMs: Long, var suppressedCount: Int)
    private val dupStates = HashMap<String, DupState>()
    private val dupSuppressWindowMs = 60_000L // re-emit a repeating entry at most once per minute
    private val dupStatesMaxEntries = 256
    private val lock = ReentrantLock()

    // Tags written through this logger. LogcatCollector skips them, since those
    // lines are already in the file (they are mirrored to logcat as well).
    private val ownTags: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val writerThread = Thread({ writerLoop() }, "FileLogger-writer").apply { isDaemon = true }

    init {
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
        ownTags.add(TAG)
        writerThread.start()
        if (mirrorToLogcat) {
            Log.d(TAG, "FileLogger initialized. Log directory: ${logDir.absolutePath}")
        }
    }

    /**
     * Queue a log entry for the file (and mirror it to logcat).
     */
    private fun writeLog(level: String, tag: String, message: String, throwable: Throwable? = null) {
        // Register before mirroring so LogcatCollector never sees an unregistered own line.
        ownTags.add(tag)

        // Always mirror to logcat for live debugging; only the persisted file is
        // de-duplicated, so `adb logcat` still shows every single event.
        if (mirrorToLogcat) {
            when (level) {
                "DEBUG" -> Log.d(tag, message, throwable)
                "INFO" -> Log.i(tag, message, throwable)
                "WARN" -> Log.w(tag, message, throwable)
                "ERROR" -> Log.e(tag, message, throwable)
            }
        }

        enqueue(level, tag, message, throwable, Thread.currentThread().name, System.currentTimeMillis())
    }

    /**
     * Append a line captured from logcat. Never mirrored back to logcat, which would loop.
     */
    internal fun appendCaptured(level: String, tag: String, message: String, thread: String, timeMs: Long) {
        enqueue(level, tag, message, null, thread, timeMs)
    }

    internal fun isOwnTag(tag: String): Boolean = ownTags.contains(tag)

    private fun enqueue(level: String, tag: String, message: String, throwable: Throwable?, thread: String, timeMs: Long) {
        // Collapse identical, rapidly-repeating entries so retry/reconnect loops
        // don't fill the log file. The signature ignores the timestamp so
        // otherwise-identical lines match.
        val throwableSig = throwable?.let { "${it::class.java.name}:${it.message}" }
        val signature = "$level|$tag|$message" + (throwableSig?.let { "|$it" } ?: "")

        var suppressedBefore = 0
        val shouldWrite = lock.withLock {
            val now = System.currentTimeMillis()
            val state = dupStates[signature]
            if (state != null && now - state.lastWriteMs < dupSuppressWindowMs) {
                // Seen recently: count it but keep it out of the file for now.
                state.suppressedCount++
                false
            } else {
                suppressedBefore = state?.suppressedCount ?: 0
                dupStates[signature] = DupState(now, 0)
                // Prune stale signatures so the map can't grow without bound.
                if (dupStates.size > dupStatesMaxEntries) {
                    val cutoff = now - dupSuppressWindowMs
                    dupStates.entries.removeAll { it.value.lastWriteMs < cutoff }
                }
                true
            }
        }

        if (!shouldWrite) return

        val entry = Entry(timeMs, level, thread, tag, message, throwable, suppressedBefore)
        if (!queue.offer(entry)) {
            droppedEntries.incrementAndGet()
        }
    }

    private fun writerLoop() {
        clearOldLogs(DEFAULT_LOG_RETENTION_DAYS)
        enforceStorageCaps()

        val batch = ArrayList<Any>(MAX_BATCH)
        while (true) {
            try {
                batch.add(queue.take())
                queue.drainTo(batch, MAX_BATCH - 1)
                writeBatch(batch)
            } catch (e: InterruptedException) {
                return
            } catch (t: Throwable) {
                // Fallback to Android Log if file writing fails
                Log.e(TAG, "Error writing to log file", t)
            } finally {
                batch.forEach { if (it is FlushMarker) it.latch.countDown() }
                batch.clear()
            }
        }
    }

    private fun writeBatch(batch: List<Any>) {
        var out: OutputStream? = null
        var outFile: File? = null
        try {
            val dropped = droppedEntries.getAndSet(0)
            val entries = ArrayList<Entry>(batch.size + 1)
            if (dropped > 0) {
                entries.add(
                    Entry(
                        System.currentTimeMillis(), "WARN", writerThread.name, TAG,
                        "$dropped log entries dropped because the log writer queue was full", null, 0
                    )
                )
            }
            batch.forEach { if (it is Entry) entries.add(it) }

            for (entry in entries) {
                val bytes = format(entry).toByteArray(Charsets.UTF_8)
                val target = fileForWrite()
                if (target != outFile) {
                    out?.close()
                    out = BufferedOutputStream(FileOutputStream(target, true))
                    outFile = target
                }
                out!!.write(bytes)
                currentFileBytes += bytes.size
            }
        } finally {
            out?.close()
        }

        // Keep storage bounded even if logs are very noisy, without listing the
        // directory on every write.
        val now = System.currentTimeMillis()
        if (capsCheckDue || now - lastCapsCheckMs > CAPS_CHECK_INTERVAL_MS) {
            enforceStorageCaps()
        }
    }

    private fun format(entry: Entry): String {
        val logEntry = StringBuilder(entry.message.length + 96)
        logEntry.append('[').append(timeFormat.format(Date(entry.timeMs))).append("] ")
            .append('[').append(entry.level).append("] ")
            .append('[').append(entry.thread).append("] ")
            .append('[').append(entry.tag).append("] ")
            .append(entry.message)
        if (entry.suppressedBefore > 0) {
            logEntry.append(
                " (previous identical entry suppressed ${entry.suppressedBefore}x in the last ${dupSuppressWindowMs / 1000}s)"
            )
        }
        // Only attach the (potentially large) stack trace on the first
        // occurrence; the per-window repeats stay as one-liners.
        if (entry.throwable != null && entry.suppressedBefore == 0) {
            logEntry.append("\n").append(entry.throwable.stackTraceToString().trimEnd())
        }
        logEntry.append("\n")
        return logEntry.toString()
    }

    /**
     * Get the current log file, rolling to a new one for a new day or when the size limit is reached.
     */
    private fun fileForWrite(): File {
        val today = dateFormat.format(Date())
        val current = currentLogFile
        if (current != null && today == currentDate && currentFileBytes < maxFileSizeBytes && current.exists()) {
            return current
        }

        if (current == null || today != currentDate || !current.exists()) {
            // First write, new day, or the file vanished: resume today's newest file if it still has room.
            currentDate = today
            val newest = newestFileFor(today)
            if (newest != null && newest.second.length() < maxFileSizeBytes) {
                fileCounter = newest.first
                currentLogFile = newest.second
                currentFileBytes = newest.second.length()
                return newest.second
            }
            fileCounter = if (newest != null) newest.first + 1 else 0
        } else {
            fileCounter++
        }

        if (!logDir.exists()) {
            logDir.mkdirs()
        }
        val file = File(logDir, "log_${currentDate}_${fileCounter}.txt")
        currentLogFile = file
        currentFileBytes = if (file.exists()) file.length() else 0L
        capsCheckDue = true
        return file
    }

    private fun newestFileFor(date: String): Pair<Int, File>? {
        val prefix = "log_${date}_"
        return logDir.listFiles()
            ?.mapNotNull { file ->
                if (!file.isFile || !file.name.startsWith(prefix) || !file.name.endsWith(".txt")) return@mapNotNull null
                file.name.removePrefix(prefix).removeSuffix(".txt").toIntOrNull()?.let { it to file }
            }
            ?.maxByOrNull { it.first }
    }

    /**
     * Log a debug message
     */
    fun d(tag: String, message: String, throwable: Throwable? = null) {
        writeLog("DEBUG", tag, message, throwable)
    }

    /**
     * Log an info message
     */
    fun i(tag: String, message: String, throwable: Throwable? = null) {
        writeLog("INFO", tag, message, throwable)
    }

    /**
     * Log a warning message
     */
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        writeLog("WARN", tag, message, throwable)
    }

    /**
     * Log an error message
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        writeLog("ERROR", tag, message, throwable)
    }

    /**
     * Block until every entry queued before this call is on disk, or [timeoutMs] elapses.
     * Use before reading the files (portal pull) or when the process is about to die.
     */
    fun flush(timeoutMs: Long = 2_000L): Boolean {
        if (Thread.currentThread() === writerThread) return false
        val marker = FlushMarker(CountDownLatch(1))
        return try {
            queue.offer(marker, timeoutMs, TimeUnit.MILLISECONDS) &&
                marker.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
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
     * Default is 60 days (2 months)
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

    /**
     * Enforce maximum number of log files and total bytes by deleting oldest files first.
     * The file currently being written is never deleted.
     */
    private fun enforceStorageCaps() {
        capsCheckDue = false
        lastCapsCheckMs = System.currentTimeMillis()
        try {
            val files = logDir.listFiles { file ->
                file.name.startsWith("log_") && file.name.endsWith(".txt") && file != currentLogFile
            }?.sortedBy { it.lastModified() }?.toMutableList() ?: return

            var fileCount = files.size + if (currentLogFile?.exists() == true) 1 else 0
            var totalSize = files.sumOf { it.length() } + (currentLogFile?.takeIf { it.exists() }?.length() ?: 0L)

            // First enforce file-count cap.
            while (fileCount > maxFileCount && files.isNotEmpty()) {
                val oldest = files.removeAt(0)
                totalSize -= oldest.length()
                fileCount--
                oldest.delete()
                Log.w(TAG, "Deleted log due to file-count cap: ${oldest.name}")
            }

            // Then enforce total-size cap.
            while (totalSize > maxTotalSizeBytes && files.isNotEmpty()) {
                val oldest = files.removeAt(0)
                totalSize -= oldest.length()
                oldest.delete()
                Log.w(TAG, "Deleted log due to size cap: ${oldest.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enforcing log storage caps", e)
        }
    }

    companion object {
        /**
         * Number of days to keep log files before automatic cleanup
         * Default: 60 days (2 months)
         */
        const val DEFAULT_LOG_RETENTION_DAYS = 60

        private const val MAX_QUEUED_ENTRIES = 20_000
        private const val MAX_BATCH = 512
        private const val CAPS_CHECK_INTERVAL_MS = 60_000L

        @Volatile
        private var instance: FileLogger? = null

        fun getInstance(context: Context): FileLogger {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val appContext = context.applicationContext
                    val baseDir = appContext.getExternalFilesDir(null) ?: appContext.filesDir
                    FileLogger(File(baseDir, "logs"))
                }.also { instance = it }
            }
        }
    }
}
