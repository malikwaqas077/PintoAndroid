package app.sst.pinto

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Build
import app.sst.pinto.utils.AppLog
import app.sst.pinto.utils.FileLogger
import app.sst.pinto.utils.LogcatCollector
import java.util.Date

/**
 * Sets up persistent logging before any activity starts, so everything the
 * process logs ends up in the on-device log files the Ask portal can pull.
 */
class PintoApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val fileLogger = FileLogger.getInstance(this)
        AppLog.init(this)
        installCrashLogging(fileLogger)
        LogcatCollector.start(fileLogger)
        recordPreviousExitReasons(fileLogger)
    }

    private fun installCrashLogging(fileLogger: FileLogger) {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                fileLogger.e(TAG, "Uncaught exception on thread=${thread.name}: ${throwable.message}", throwable)
                // The process is about to die; make sure the entry reaches disk first.
                fileLogger.flush(CRASH_FLUSH_TIMEOUT_MS)
            } catch (_: Throwable) {
                // Never let logging get in the way of the default crash handling.
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Write why earlier runs of the app ended (crash, native crash, ANR, low memory, ...),
     * which the process itself cannot log when it is killed.
     */
    private fun recordPreviousExitReasons(fileLogger: FileLogger) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        Thread({
            try {
                val prefs = getSharedPreferences(LOG_STATE_PREFS, MODE_PRIVATE)
                val lastSeen = prefs.getLong(KEY_LAST_EXIT_TIMESTAMP, 0L)
                val activityManager = getSystemService(ActivityManager::class.java) ?: return@Thread
                val exits = activityManager
                    .getHistoricalProcessExitReasons(packageName, 0, MAX_EXIT_RECORDS)
                    .filter { it.timestamp > lastSeen }
                    .sortedBy { it.timestamp }

                for (info in exits) {
                    fileLogger.w(
                        TAG,
                        "Previous app exit: reason=${exitReasonName(info.reason)} status=${info.status} " +
                            "importance=${info.importance} pid=${info.pid} at=${Date(info.timestamp)} " +
                            "description=${info.description}"
                    )
                    if (info.reason == ApplicationExitInfo.REASON_ANR) {
                        info.traceInputStream?.use { stream ->
                            val trace = readCapped(stream, MAX_ANR_TRACE_BYTES)
                            fileLogger.w(TAG, "ANR trace for pid=${info.pid}:\n$trace")
                        }
                    }
                }

                exits.lastOrNull()?.let {
                    prefs.edit().putLong(KEY_LAST_EXIT_TIMESTAMP, it.timestamp).apply()
                }
            } catch (e: Exception) {
                fileLogger.w(TAG, "Unable to read previous exit reasons: ${e.message}")
            }
        }, "ExitReasonLogger").start()
    }

    private fun readCapped(stream: java.io.InputStream, maxBytes: Int): String {
        val buffer = ByteArray(maxBytes)
        var total = 0
        while (total < maxBytes) {
            val read = stream.read(buffer, total, maxBytes - total)
            if (read < 0) break
            total += read
        }
        return String(buffer, 0, total, Charsets.UTF_8)
    }

    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "code=$reason"
    }

    companion object {
        private const val TAG = "PintoApplication"
        private const val CRASH_FLUSH_TIMEOUT_MS = 2_000L
        private const val LOG_STATE_PREFS = "pinto_log_state"
        private const val KEY_LAST_EXIT_TIMESTAMP = "last_exit_timestamp"
        private const val MAX_EXIT_RECORDS = 16
        private const val MAX_ANR_TRACE_BYTES = 256 * 1024
    }
}
