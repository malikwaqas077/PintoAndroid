package app.sst.pinto.network

import android.content.Context
import android.util.Base64
import app.sst.pinto.utils.AppLog
import app.sst.pinto.utils.WireLog
import app.sst.pinto.utils.FileLogger
import app.sst.pinto.utils.getDeviceSerialNumber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Handles Ask portal deviceHub commands (logs_list / logs_get).
 * Same protocol as EvCloudPay PortalWebSocketRepository.
 */
class PortalWebSocketRepository private constructor(private val context: Context) {
    private val TAG = "PortalWebSocketRepo"
    private val fileLogger = FileLogger.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var client: PortalWebSocketClient? = null

    fun start() {
        val serial = getDeviceSerialNumber()
        if (serial.isBlank() || serial == "unknown") {
            fileLogger.w(TAG, "Cannot start portal hub — device serial unknown")
            return
        }

        if (client != null) {
            client?.connect()
            return
        }

        val wsClient = PortalWebSocketClient(context, serial)
        wsClient.setMessageCallback { message -> handlePortalMessage(message) }
        client = wsClient
        wsClient.connect()
        fileLogger.i(TAG, "Portal deviceHub repository started serial=$serial")
    }

    fun stop() {
        client?.disconnect()
        client = null
    }

    fun reconnect() {
        client?.disconnect()
        client = null
        start()
    }

    fun isConnected(): Boolean = client?.connectionState?.value == true

    private fun sendMessage(json: String) {
        client?.sendMessage(json)
    }

    private fun handlePortalMessage(message: String) {
        try {
            val trimmed = message.trim()
            if (!trimmed.startsWith("{")) return

            WireLog.portalIn(trimmed)
            val json = JSONObject(trimmed)
            val action = json.optString("action", "")
            val requestId = json.optString("requestId", "")

            when (action) {
                "ping" -> { /* ignore — keepalive from server if any */ }
                "logs_list" -> {
                    if (requestId.isNotEmpty()) handleLogsListRequest(requestId)
                    else AppLog.e(TAG, "Missing requestId for logs_list")
                }
                "logs_get" -> {
                    if (requestId.isNotEmpty()) {
                        val fileName = json.optString("file", "")
                        val offset = json.optInt("offset", 0)
                        val limit = json.optInt("limit", 65536)
                        handleLogsChunkRequest(requestId, fileName, offset, limit)
                    } else {
                        AppLog.e(TAG, "Missing requestId for logs_get")
                    }
                }
                else -> {
                    if (action.isNotEmpty()) {
                        AppLog.w(TAG, "Unknown portal action: $action")
                    }
                }
            }
        } catch (e: Exception) {
            fileLogger.e(TAG, "Error handling portal message", e)
        }
    }

    private fun handleLogsListRequest(requestId: String) {
        scope.launch {
            try {
                // Entries are written asynchronously; get the latest ones on disk first.
                fileLogger.flush(1_000L)
                val logsDir = fileLogger.getLogDirectory()
                val files = logsDir.listFiles()
                    ?.filter { it.isFile && it.name.startsWith("log_") }
                    ?.sortedByDescending { it.lastModified() }
                    ?: emptyList()

                val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }

                val filesArray = JSONArray()
                files.forEach { file ->
                    filesArray.put(
                        JSONObject()
                            .put("name", file.name)
                            .put("size", file.length())
                            .put("modified", formatter.format(Date(file.lastModified())))
                    )
                }

                val response = JSONObject()
                    .put("type", "logs_list")
                    .put("requestId", requestId)
                    .put("success", true)
                    .put("files", filesArray)
                    .put("timestamp", System.currentTimeMillis())

                sendMessage(response.toString())
                AppLog.d(TAG, "Sent logs_list (${files.size} files)")
            } catch (e: Exception) {
                fileLogger.e(TAG, "Error preparing logs list", e)
                val response = JSONObject()
                    .put("type", "logs_list")
                    .put("requestId", requestId)
                    .put("success", false)
                    .put("error", e.message ?: "Failed to list log files")
                    .put("timestamp", System.currentTimeMillis())
                sendMessage(response.toString())
            }
        }
    }

    private fun handleLogsChunkRequest(requestId: String, fileName: String, offset: Int, limit: Int) {
        scope.launch {
            try {
                if (fileName.isBlank()) {
                    throw IllegalArgumentException("file name is required")
                }

                fileLogger.flush(1_000L)
                val logsDir = fileLogger.getLogDirectory()
                val targetFile = File(logsDir, fileName)
                val canonicalLogsDir = logsDir.canonicalPath
                val canonicalTarget = targetFile.canonicalPath
                if (!canonicalTarget.startsWith(canonicalLogsDir)) {
                    throw SecurityException("Invalid file path")
                }

                if (!targetFile.exists() || !targetFile.isFile) {
                    throw IllegalArgumentException("Log file not found")
                }

                val safeLimit = limit.coerceIn(1024, 131072)
                val safeOffset = offset.coerceAtLeast(0)
                val fileLength = targetFile.length()

                if (safeOffset >= fileLength) {
                    val response = JSONObject()
                        .put("type", "logs_chunk")
                        .put("requestId", requestId)
                        .put("file", fileName)
                        .put("offset", safeOffset)
                        .put("data", "")
                        .put("eof", true)
                        .put("nextOffset", safeOffset)
                        .put("timestamp", System.currentTimeMillis())
                    sendMessage(response.toString())
                    return@launch
                }

                val bytesToRead = minOf(safeLimit.toLong(), fileLength - safeOffset).toInt()
                val buffer = ByteArray(bytesToRead)
                RandomAccessFile(targetFile, "r").use { raf ->
                    raf.seek(safeOffset.toLong())
                    raf.readFully(buffer)
                }

                val base64Data = Base64.encodeToString(buffer, Base64.NO_WRAP)
                val nextOffset = safeOffset + bytesToRead
                val eof = nextOffset >= fileLength

                val response = JSONObject()
                    .put("type", "logs_chunk")
                    .put("requestId", requestId)
                    .put("file", fileName)
                    .put("offset", safeOffset)
                    .put("data", base64Data)
                    .put("eof", eof)
                    .put("nextOffset", nextOffset)
                    .put("size", fileLength)
                    .put("timestamp", System.currentTimeMillis())

                sendMessage(response.toString())
                AppLog.d(TAG, "Sent logs_chunk $fileName offset=$safeOffset size=$bytesToRead")
            } catch (e: Exception) {
                fileLogger.e(TAG, "Error reading log chunk", e)
                val response = JSONObject()
                    .put("type", "logs_chunk")
                    .put("requestId", requestId)
                    .put("file", fileName)
                    .put("success", false)
                    .put("error", e.message ?: "Failed to read log file")
                    .put("timestamp", System.currentTimeMillis())
                sendMessage(response.toString())
            }
        }
    }

    companion object {
        @Volatile
        private var instance: PortalWebSocketRepository? = null

        fun getInstance(context: Context): PortalWebSocketRepository {
            return instance ?: synchronized(this) {
                instance ?: PortalWebSocketRepository(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
