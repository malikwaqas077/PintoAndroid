package app.sst.pinto.utils

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileLoggerTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("filelogger").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun logger(maxFileSizeBytes: Long = 4 * 1024 * 1024L) =
        FileLogger(dir, maxFileSizeBytes = maxFileSizeBytes, mirrorToLogcat = false)

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun allText() = dir.listFiles()!!
        .filter { it.name.startsWith("log_") }
        .sortedBy { it.name.removePrefix("log_${today()}_").removeSuffix(".txt").toInt() }
        .joinToString("") { it.readText() }

    @Test
    fun writesEntriesInOrderAfterFlush() {
        val logger = logger()
        repeat(50) { logger.i("Order", "line $it") }
        assertTrue(logger.flush())

        val lines = allText().lines().filter { it.isNotBlank() }
        assertEquals(50, lines.size)
        lines.forEachIndexed { index, line ->
            assertTrue(line, line.endsWith("[INFO] [${Thread.currentThread().name}] [Order] line $index"))
        }
    }

    @Test
    fun rollsToNextFileWhenSizeLimitReached() {
        val logger = logger(maxFileSizeBytes = 300)
        repeat(20) { logger.d("Roll", "entry number $it with some padding text") }
        assertTrue(logger.flush())

        val files = dir.listFiles()!!.filter { it.name.startsWith("log_${today()}_") }
        assertTrue("expected several files, got ${files.map { it.name }}", files.size > 3)
        assertTrue(File(dir, "log_${today()}_0.txt").exists())
        assertTrue(File(dir, "log_${today()}_1.txt").exists())
        // Nothing lost across the rollovers.
        val lines = allText().lines().filter { it.isNotBlank() }
        assertEquals(20, lines.size)
    }

    @Test
    fun resumesTodaysNewestFileWithRoom() {
        File(dir, "log_${today()}_0.txt").writeText("old full file\n".repeat(50))
        File(dir, "log_${today()}_10.txt").writeText("existing\n")
        val logger = logger(maxFileSizeBytes = 400)
        logger.i("Resume", "appended")
        assertTrue(logger.flush())

        assertTrue(File(dir, "log_${today()}_10.txt").readText().contains("[Resume] appended"))
    }

    @Test
    fun suppressesRapidDuplicates() {
        val logger = logger()
        repeat(5) { logger.w("Dup", "same message") }
        logger.w("Dup", "different message")
        assertTrue(logger.flush())

        val text = allText()
        assertEquals(1, Regex("same message").findAll(text).count())
        assertTrue(text.contains("different message"))
    }

    @Test
    fun writesStackTraceForErrors() {
        val logger = logger()
        logger.e("Err", "boom", IllegalStateException("bad state"))
        assertTrue(logger.flush())

        val text = allText()
        assertTrue(text.contains("[ERROR]"))
        assertTrue(text.contains("java.lang.IllegalStateException: bad state"))
    }

    @Test
    fun capturedLinesAreWrittenButNotRegisteredAsOwnTags() {
        val logger = logger()
        logger.i("AppTag", "from app")
        logger.appendCaptured("INFO", "System.out", "sdk says hi", "logcat:42", System.currentTimeMillis())
        assertTrue(logger.flush())

        assertTrue(logger.isOwnTag("AppTag"))
        assertFalse(logger.isOwnTag("System.out"))
        assertTrue(allText().contains("[INFO] [logcat:42] [System.out] sdk says hi"))
    }

    @Test
    fun parsesThreadtimeLogcatLines() {
        val parsed = LogcatCollector.parse("09-15 12:03:01.123  1234  1250 I System.out: [Integra] connected: yes")
        assertNotNull(parsed)
        assertEquals("09-15 12:03:01.123", parsed!!.time)
        assertEquals(1234, parsed.pid)
        assertEquals(1250, parsed.tid)
        assertEquals("INFO", parsed.level)
        assertEquals("System.out", parsed.tag)
        assertEquals("[Integra] connected: yes", parsed.message)

        val padded = LogcatCollector.parse("09-15 12:03:01.123  1234  1250 F libc    : Fatal signal 11 (SIGSEGV)")
        assertEquals("libc", padded!!.tag)
        assertEquals("FATAL", padded.level)

        assertNull(LogcatCollector.parse("--------- beginning of main"))
    }
}
