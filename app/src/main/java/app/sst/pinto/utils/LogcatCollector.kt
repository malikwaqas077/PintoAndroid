package app.sst.pinto.utils

import android.os.Process
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Tees this process's logcat into the on-device log files so output that never
 * goes through [FileLogger] is kept too: third-party payment SDKs (the Planet /
 * Integra console appender writes to System.out), NNSmart, Switchio, CCV, and
 * platform messages such as fatal-signal reports.
 *
 * Lines from tags the app already writes through FileLogger are skipped, so
 * nothing is stored twice.
 */
object LogcatCollector {
    private const val TAG = "LogcatCollector"
    private const val INITIAL_BACKOFF_MS = 1_000L
    private const val MAX_BACKOFF_MS = 60_000L

    // `logcat -v threadtime`: "09-15 12:03:01.123  1234  1250 D Tag     : message"
    private val LINE = Regex("""^(\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEFA])\s+(.*?)\s*: ?(.*)$""")

    internal data class ParsedLine(
        val time: String,
        val pid: Int,
        val tid: Int,
        val level: String,
        val tag: String,
        val message: String
    )

    @Volatile
    private var worker: Thread? = null

    fun start(logger: FileLogger) {
        synchronized(this) {
            if (worker?.isAlive == true) return
            worker = Thread({ collect(logger) }, TAG).apply {
                isDaemon = true
                start()
            }
        }
    }

    internal fun parse(line: String): ParsedLine? {
        val match = LINE.matchEntire(line) ?: return null
        val (time, pid, tid, level, tag, message) = match.destructured
        return ParsedLine(time, pid.toInt(), tid.toInt(), levelName(level[0]), tag, message)
    }

    private fun levelName(level: Char): String = when (level) {
        'V' -> "VERBOSE"
        'D' -> "DEBUG"
        'I' -> "INFO"
        'W' -> "WARN"
        'E' -> "ERROR"
        else -> "FATAL"
    }

    private fun collect(logger: FileLogger) {
        val pid = Process.myPid()
        val timeParser = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        var since: String? = null
        var backoffMs = INITIAL_BACKOFF_MS

        while (true) {
            var process: java.lang.Process? = null
            try {
                val command = mutableListOf("logcat", "-v", "threadtime", "--pid", pid.toString())
                // On restart only fetch what we have not seen yet.
                since?.let { command += listOf("-T", it) }
                process = ProcessBuilder(command).redirectErrorStream(true).start()

                BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        val parsed = parse(line) ?: continue
                        since = parsed.time
                        backoffMs = INITIAL_BACKOFF_MS
                        if (parsed.pid != pid || logger.isOwnTag(parsed.tag)) continue
                        logger.appendCaptured(
                            parsed.level,
                            parsed.tag,
                            parsed.message,
                            "logcat:${parsed.tid}",
                            toEpochMillis(timeParser, parsed.time)
                        )
                    }
                }
                logger.w(TAG, "logcat capture ended; restarting")
            } catch (e: Exception) {
                logger.w(TAG, "logcat capture failed: ${e.message}")
            } finally {
                process?.destroy()
            }

            try {
                Thread.sleep(backoffMs)
            } catch (e: InterruptedException) {
                return
            }
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    /** logcat omits the year; assume the current one unless that lands in the future. */
    private fun toEpochMillis(parser: SimpleDateFormat, logcatTime: String): Long {
        val now = System.currentTimeMillis()
        val year = Calendar.getInstance().get(Calendar.YEAR)
        return try {
            val millis = parser.parse("$year-$logcatTime")?.time ?: now
            if (millis > now + 24 * 60 * 60 * 1000L) parser.parse("${year - 1}-$logcatTime")?.time ?: now else millis
        } catch (e: Exception) {
            now
        }
    }
}
