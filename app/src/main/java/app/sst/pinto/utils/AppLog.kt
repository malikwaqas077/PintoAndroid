package app.sst.pinto.utils

import android.content.Context
import android.util.Log

/**
 * Drop-in replacement for android.util.Log: every entry is mirrored to logcat
 * and persisted to the on-device log files (see [FileLogger]) so it can be
 * pulled from the Ask portal. Until [init] runs, entries go to logcat only
 * (LogcatCollector still picks those up once it starts).
 */
object AppLog {
    @Volatile
    private var logger: FileLogger? = null

    fun init(context: Context) {
        logger = FileLogger.getInstance(context)
    }

    fun d(tag: String, msg: String?, tr: Throwable? = null) {
        val fileLogger = logger
        if (fileLogger != null) fileLogger.d(tag, msg ?: "null", tr) else Log.d(tag, msg ?: "null", tr)
    }

    fun i(tag: String, msg: String?, tr: Throwable? = null) {
        val fileLogger = logger
        if (fileLogger != null) fileLogger.i(tag, msg ?: "null", tr) else Log.i(tag, msg ?: "null", tr)
    }

    fun w(tag: String, msg: String?, tr: Throwable? = null) {
        val fileLogger = logger
        if (fileLogger != null) fileLogger.w(tag, msg ?: "null", tr) else Log.w(tag, msg ?: "null", tr)
    }

    fun e(tag: String, msg: String?, tr: Throwable? = null) {
        val fileLogger = logger
        if (fileLogger != null) fileLogger.e(tag, msg ?: "null", tr) else Log.e(tag, msg ?: "null", tr)
    }
}
