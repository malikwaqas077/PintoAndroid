package app.sst.pinto.network

import android.content.Context
import app.sst.pinto.utils.AppLog
import app.sst.pinto.config.ConfigManager
import app.sst.pinto.utils.FileLogger
import app.sst.pinto.utils.getDeviceSerialNumber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Posts payment outcomes to Ask portal /api/device-transactions for the Transactions tab.
 */
class PortalTransactionReporter private constructor(private val context: Context) {
    private val TAG = "PortalTxnReporter"
    private val fileLogger = FileLogger.getInstance(context)
    private val configManager = ConfigManager.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class Report(
        val transactionId: String,
        val status: String,
        val amountMinor: Int,
        val currency: String? = null,
        val provider: String? = null,
        val approvalCode: String? = null,
        val receiptNumber: String? = null,
        val requesterRef: String? = null,
        val trxReference: String? = null,
        val cardToken: String? = null,
        val message: String? = null,
        val deviceName: String? = null,
        val customerCode: String? = null,
        val txnTimeUtcMs: Long = System.currentTimeMillis()
    )

    fun reportAsync(report: Report) {
        scope.launch {
            report(report)
        }
    }

    fun report(report: Report): Boolean {
        val base = configManager.getPortalHttpBaseUrl()
        if (base.isBlank()) {
            fileLogger.w(TAG, "Skip txn report — portal URL not configured")
            return false
        }

        val serial = getDeviceSerialNumber()
        if (serial.isBlank() || serial == "unknown") {
            fileLogger.w(TAG, "Skip txn report — device serial unknown")
            return false
        }

        return try {
            val bodyJson = JSONObject().apply {
                put("transactionId", report.transactionId)
                put("deviceSerial", serial)
                put("status", report.status)
                put("amountMinor", report.amountMinor)
                put("currency", report.currency ?: JSONObject.NULL)
                put("provider", report.provider ?: JSONObject.NULL)
                put("approvalCode", report.approvalCode ?: JSONObject.NULL)
                put("receiptNumber", report.receiptNumber ?: JSONObject.NULL)
                put("requesterRef", report.requesterRef ?: JSONObject.NULL)
                put("trxReference", report.trxReference ?: JSONObject.NULL)
                put("cardToken", report.cardToken ?: JSONObject.NULL)
                put("message", report.message ?: JSONObject.NULL)
                put("deviceName", report.deviceName ?: JSONObject.NULL)
                put("customerCode", report.customerCode ?: JSONObject.NULL)
                val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                put("txnTimeUtc", iso.format(Date(report.txnTimeUtcMs)))
            }

            val request = Request.Builder()
                .url("$base/api/device-transactions")
                .addHeader("Content-Type", "application/json")
                .addHeader("X-Device-Serial", serial)
                .post(bodyJson.toString().toRequestBody(JSON_MEDIA))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    fileLogger.i(
                        TAG,
                        "Reported txn ${report.transactionId} status=${report.status} http=${response.code}"
                    )
                    true
                } else {
                    fileLogger.w(
                        TAG,
                        "Txn report failed http=${response.code} body=${response.body?.string()?.take(200)}"
                    )
                    false
                }
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Txn report error", e)
            fileLogger.e(TAG, "Txn report error: ${e.message}", e)
            false
        }
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        @Volatile
        private var instance: PortalTransactionReporter? = null

        fun getInstance(context: Context): PortalTransactionReporter {
            return instance ?: synchronized(this) {
                instance ?: PortalTransactionReporter(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
