package app.sst.pinto.payment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import app.sst.pinto.utils.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Result of an NNSmart SALE / REFUND.
 *
 * For daily-limit validation we rely on [par] (Payment Account Reference) and
 * [cardRefId]. To later reverse the sale we use [originalTrxUniqueId] which
 * corresponds to the `id` field of the NNSmart transaction data and is sent
 * back to the terminal as `payment_ref` of a CANCELLATION request.
 */
data class NNSmartPaymentResult(
    val success: Boolean,
    val resultCode: String? = null,
    val message: String? = null,
    val state: String? = null,
    val originalTrxUniqueId: String? = null,
    val par: String? = null,
    val cardRefId: String? = null,
    val authCode: String? = null,
    val rrn: String? = null,
    val amount: Long? = null,
    val currencyAlphaCode: String? = null,
    val pan: String? = null,
    val rawResponse: String? = null
)

/**
 * Thin wrapper around the Newland NNSmart payment application.
 *
 * NNSmart is a separate Android application on the terminal that accepts
 * payment requests via implicit broadcast Intents and replies via a broadcast
 * to an action name we pass in as `intent_action_reply`.
 *
 * Unlike Planet Integra there is no "card check" that returns a token before
 * the payment is taken — the Payment Account Reference (`par`) and
 * `cardRefId` are only available AFTER a SALE completes. So the daily-limit
 * flow for this provider is:
 *   1. Run SALE — money is captured on the terminal.
 *   2. Send `par` / `cardRefId` + amount to backend for limit validation.
 *   3. If approved: continue the normal post-payment flow.
 *   4. If rejected: run CANCELLATION with `payment_ref` = original trx id
 *      to reverse the sale.
 */
object NNSmartPaymentManager {

    private const val TAG = "NNSmartPaymentManager"
    private var fileLogger: FileLogger? = null

    private fun logDebug(message: String) {
        if (fileLogger != null) {
            fileLogger?.d(TAG, message)
        } else {
            Log.d(TAG, message)
        }
    }

    private fun logWarn(message: String) {
        if (fileLogger != null) {
            fileLogger?.w(TAG, message)
        } else {
            Log.w(TAG, message)
        }
    }

    private fun logError(message: String, t: Throwable? = null) {
        if (fileLogger != null) {
            fileLogger?.e(TAG, message, t)
        } else {
            Log.e(TAG, message, t)
        }
    }

    fun configureLogging(logger: FileLogger) {
        fileLogger = logger
        logDebug("NNSmartPaymentManager file logging configured")
    }

    // Broadcast actions defined by the NNSmart integration spec.
    private const val ACTION_REQUEST = "com.newnote.nsmart.ecr.request"
    private const val ACTION_REQUEST_BACKGROUND = "com.newnote.nsmart.ecr.background.request"

    // Ensure only one terminal interaction runs at a time (sale or cancel).
    private val transactionMutex = Mutex()

    private const val SALE_TIMEOUT_MS = 120_000L
    private const val CANCEL_TIMEOUT_MS = 30_000L

    /** Returns true if the provider string identifies the NNSmart / Newland terminal. */
    fun isNNSmartProvider(provider: String?): Boolean {
        if (provider.isNullOrBlank()) return false
        val p = provider.lowercase().trim()
        return p == "nnsmart" || p == "newland"
    }

    /**
     * Perform a SALE on the NNSmart terminal.
     *
     * @param context any context; the application context is used internally.
     * @param amountFormatted amount as a decimal string, e.g. "10.00".
     * @param requesterRef unique reference for this request.
     * @param currencyAlphaCode optional ISO-4217 alpha code, e.g. "GBP".
     * @param showReceipts whether the terminal should print its own receipt.
     */
    suspend fun performSale(
        context: Context,
        amountFormatted: String,
        requesterRef: String,
        currencyAlphaCode: String? = null,
        showReceipts: Boolean = false
    ): NNSmartPaymentResult = transactionMutex.withLock {
        withContext(Dispatchers.IO) {
            // NNSmart treats `request_id` as an idempotency key and rejects
            // any repeat with `{"error":{"id":-7,"reason":"Reference already exists"}}`.
            // Our caller sometimes passes a placeholder id like "initial" which
            // is guaranteed to collide, so ALWAYS generate a fresh UUID here
            // and only keep the caller-supplied ref for audit logging.
            val nnsmartRequestId = UUID.randomUUID().toString()
            logDebug(
                "Starting NNSmart sale: amount=$amountFormatted callerRef=$requesterRef " +
                    "nnsmartRequestId=$nnsmartRequestId currency=$currencyAlphaCode"
            )

            val amountMinor = parseAmountToMinorUnits(amountFormatted)
            if (amountMinor == null) {
                logError("Invalid amount format: $amountFormatted")
                return@withContext NNSmartPaymentResult(
                    success = false,
                    resultCode = "INVALID_AMOUNT",
                    message = "Invalid amount format: $amountFormatted"
                )
            }

            val extras = HashMap<String, Any>().apply {
                put("operation", "SALE")
                put("amount", amountMinor)
                put("request_id", nnsmartRequestId)
                put("show_receipts", showReceipts)
                if (!currencyAlphaCode.isNullOrBlank()) {
                    put("currency_alpha_code", currencyAlphaCode)
                }
            }

            val response = sendRequestAndAwaitResponse(
                context = context,
                action = ACTION_REQUEST,
                extras = extras,
                timeoutMs = SALE_TIMEOUT_MS
            ) ?: return@withContext NNSmartPaymentResult(
                success = false,
                resultCode = "TX_TIMEOUT",
                message = "NNSmart sale timed out"
            )

            parseTransactionResponse(response)
        }
    }

    /**
     * Reverse / cancel a previously-approved SALE.
     *
     * NNSmart Cancellation requires the original transaction's unique id to be
     * passed as `payment_ref`. We send it via the background broadcast action
     * so the operation runs without requiring the customer to tap again.
     *
     * @return true if the terminal reports the cancellation as completed.
     */
    suspend fun performCancel(
        context: Context,
        requesterRef: String,
        originalTrxUniqueId: String
    ): Boolean = transactionMutex.withLock {
        withContext(Dispatchers.IO) {
            val nnsmartRequestId = UUID.randomUUID().toString()
            logDebug(
                "Starting NNSmart cancel: callerRef=$requesterRef " +
                    "nnsmartRequestId=$nnsmartRequestId originalTrxId=$originalTrxUniqueId"
            )

            if (originalTrxUniqueId.isBlank()) {
                logError("Cannot cancel NNSmart transaction: originalTrxUniqueId is empty")
                return@withContext false
            }

            val extras = HashMap<String, Any>().apply {
                put("operation", "CANCELLATION")
                put("request_id", nnsmartRequestId)
                put("payment_ref", originalTrxUniqueId)
                put("show_receipts", false)
            }

            val response = sendRequestAndAwaitResponse(
                context = context,
                action = ACTION_REQUEST_BACKGROUND,
                extras = extras,
                timeoutMs = CANCEL_TIMEOUT_MS
            )

            if (response == null) {
                logWarn("NNSmart cancel timed out for originalTrxId=$originalTrxUniqueId")
                return@withContext false
            }

            val parsed = parseTransactionResponse(response)
            logDebug("NNSmart cancel completed: success=${parsed.success} state=${parsed.state} msg=${parsed.message}")
            parsed.success
        }
    }

    /**
     * Send an implicit broadcast to NNSmart and suspend until it broadcasts
     * back to our reply action (or the timeout elapses).
     *
     * The receiver is always unregistered on both success and timeout paths.
     */
    private suspend fun sendRequestAndAwaitResponse(
        context: Context,
        action: String,
        extras: Map<String, Any>,
        timeoutMs: Long
    ): Intent? {
        val appContext = context.applicationContext
        val replyAction = "app.sst.pinto.nnsmart.REPLY." + UUID.randomUUID().toString().replace("-", "")

        // Keep a reference outside the coroutine so the finally block can
        // unregister it regardless of how the coroutine completes.
        var registeredReceiver: BroadcastReceiver? = null

        return try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Intent?> { cont ->
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(ctx: Context, intent: Intent) {
                            logDebug("NNSmart reply received on $replyAction")
                            if (cont.isActive) cont.resume(intent)
                        }
                    }
                    registeredReceiver = receiver

                    val filter = IntentFilter(replyAction)
                    try {
                        // NNSmart is an external app, so on Android 13+ the
                        // receiver must be explicitly exported.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                        } else {
                            @Suppress("UnspecifiedRegisterReceiverFlag")
                            appContext.registerReceiver(receiver, filter)
                        }
                    } catch (e: Throwable) {
                        logError("Failed to register NNSmart reply receiver", e)
                        registeredReceiver = null
                        if (cont.isActive) cont.resume(null)
                        return@suspendCancellableCoroutine
                    }

                    try {
                        val requestIntent = Intent(action).apply {
                            putExtra("intent_action_reply", replyAction)
                            for ((k, v) in extras) {
                                when (v) {
                                    is String -> putExtra(k, v)
                                    is Boolean -> putExtra(k, v)
                                    is Long -> putExtra(k, v)
                                    is Int -> putExtra(k, v.toLong())
                                    else -> putExtra(k, v.toString())
                                }
                            }
                        }
                        logDebug("Sending NNSmart broadcast: action=$action extras=$extras reply=$replyAction")
                        appContext.sendBroadcast(requestIntent)
                    } catch (e: Throwable) {
                        logError("Failed to send NNSmart broadcast", e)
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        } finally {
            registeredReceiver?.let { r ->
                try {
                    appContext.unregisterReceiver(r)
                } catch (_: Throwable) { /* already unregistered */ }
            }
        }
    }

    /**
     * NNSmart normally returns the entire response as a single JSON string
     * packed into one extra (see spec section 2.4). Different terminal
     * firmwares use different extra keys ("ecrResponse", "data",
     * "intentResponseBundleData", or even the action name itself), so we:
     *   1. Dump every extra for visibility during integration.
     *   2. Try each extra as a candidate JSON string and pick the first one
     *      that parses and contains `trxData` / `error` / `status`.
     *   3. Fall back to reading the legacy per-field extras.
     */
    private fun parseTransactionResponse(intent: Intent): NNSmartPaymentResult {
        val extras = intent.extras

        // 1. Dump every extra key/value so the actual wire format is visible
        //    in logcat. This is invaluable for first-time integration because
        //    the NNSmart spec does not name the extra that carries the JSON.
        val extrasDump = buildString {
            append("action=").append(intent.action)
            append(" keys=[")
            if (extras != null) {
                val keys = extras.keySet().toList()
                keys.forEachIndexed { i, k ->
                    if (i > 0) append(", ")
                    val v = extras.get(k)
                    append(k).append('=')
                    when (v) {
                        null -> append("null")
                        is String -> append('"').append(v).append('"')
                        else -> append(v.toString())
                    }
                }
            }
            append(']')
        }
        logDebug("NNSmart reply extras: $extrasDump")

        // 2. Walk every extra and try to parse it as the NNSmart envelope JSON.
        //    The envelope has the shape: { id, status?, error?, trxData? }.
        var envelope: JSONObject? = null
        var envelopeKey: String? = null
        if (extras != null) {
            for (key in extras.keySet()) {
                val raw = extras.get(key)?.toString() ?: continue
                val candidate = safeJson(raw) ?: continue
                val looksLikeEnvelope = candidate.has("trxData") ||
                    candidate.has("error") ||
                    candidate.has("status") ||
                    candidate.has("id")
                if (looksLikeEnvelope) {
                    envelope = candidate
                    envelopeKey = key
                    break
                }
            }
        }
        if (envelope != null) {
            logDebug("NNSmart response envelope found under extra '$envelopeKey'")
        }

        // 3. Legacy/per-field fallbacks (older NNSmart builds sometimes split
        //    the response across multiple extras).
        val trxDataRaw = envelope?.optString("trxData")?.takeIf { it.isNotBlank() }
            ?: extras?.getString("trxData")
        val errorRaw = envelope?.optString("error")?.takeIf { it.isNotBlank() }
            ?: extras?.getString("error")
        val statusRaw = envelope?.optString("status")?.takeIf { it.isNotBlank() }
            ?: extras?.getString("status")
        val idRaw = envelope?.optString("id")?.takeIf { it.isNotBlank() }
            ?: extras?.getString("id")

        val rawForLog = buildString {
            append("id=").append(idRaw)
            append(" status=").append(statusRaw)
            append(" error=").append(errorRaw)
            append(" trxData=").append(trxDataRaw)
            if (envelopeKey != null) append(" envelopeKey=").append(envelopeKey)
        }
        logDebug("NNSmart response payload: $rawForLog")

        // If an error block is present, treat the request as failed.
        if (!errorRaw.isNullOrBlank()) {
            val (errId, errDesc) = parseErrorJson(errorRaw)
            return NNSmartPaymentResult(
                success = false,
                resultCode = errId?.toString(),
                message = errDesc ?: "NNSmart error",
                state = "ERROR",
                rawResponse = rawForLog
            )
        }

        // Prefer an explicit trxData block. In the envelope it may be a
        // JSONObject or a JSON-encoded String, so handle both.
        val trxJson: JSONObject? = when {
            envelope != null -> {
                envelope.optJSONObject("trxData")
                    ?: trxDataRaw?.let { safeJson(it) }
            }
            !trxDataRaw.isNullOrBlank() -> safeJson(trxDataRaw)
            else -> null
        }

        if (trxJson == null) {
            return NNSmartPaymentResult(
                success = false,
                resultCode = "NO_TRX_DATA",
                message = "No trxData in NNSmart response",
                state = statusRaw,
                rawResponse = rawForLog
            )
        }

        val state = trxJson.optString("state").ifEmpty { trxJson.optString("trxStatus") }
        val responseCode = trxJson.optString("responseCode").ifEmpty { trxJson.optString("trxResponseCode") }
        val responseDesc = trxJson.optString("responseCodeDescription").ifEmpty { trxJson.optString("trxResponseCodeDescription") }
        val success = state.equals("APPROVED", ignoreCase = true) ||
            state.equals("COMPLETED", ignoreCase = true) ||
            responseCode == "00"

        return NNSmartPaymentResult(
            success = success,
            resultCode = responseCode.ifEmpty { state },
            message = responseDesc.ifEmpty { state },
            state = state.ifEmpty { statusRaw },
            originalTrxUniqueId = trxJson.optString("id").takeIf { it.isNotBlank() },
            par = trxJson.optString("par").takeIf { it.isNotBlank() },
            cardRefId = trxJson.optString("cardRefId").takeIf { it.isNotBlank() },
            authCode = trxJson.optString("authCode").ifEmpty { trxJson.optString("trxAuthCode") }.takeIf { it.isNotBlank() },
            rrn = trxJson.optString("rrn").takeIf { it.isNotBlank() },
            amount = trxJson.opt("amount")?.let { (it as? Number)?.toLong() ?: it.toString().toLongOrNull() },
            currencyAlphaCode = trxJson.optString("currAlphaCode").ifEmpty { trxJson.optString("currencyCodeAlpha") }.takeIf { it.isNotBlank() },
            pan = trxJson.optString("pan").takeIf { it.isNotBlank() },
            rawResponse = rawForLog
        )
    }

    private fun parseErrorJson(raw: String): Pair<Int?, String?> {
        val json = safeJson(raw) ?: return null to raw
        val id = if (json.has("id")) json.optInt("id") else null
        // Spec says `description` but NNSmart builds in the field actually
        // return `reason`. Accept either.
        val desc = json.optString("description").takeIf { it.isNotBlank() }
            ?: json.optString("reason").takeIf { it.isNotBlank() }
            ?: json.optString("message").takeIf { it.isNotBlank() }
        return id to desc
    }

    private fun safeJson(raw: String): JSONObject? = try {
        JSONObject(raw)
    } catch (e: Throwable) {
        logWarn("Failed to parse NNSmart JSON: ${e.message}")
        null
    }

    /**
     * NNSmart expects amount as a Long in minor units (no decimals).
     * Accepts "10.00" or "1000" style inputs.
     */
    private fun parseAmountToMinorUnits(amountFormatted: String): Long? {
        val trimmed = amountFormatted.trim()
        if (trimmed.isEmpty()) return null
        val asDouble = trimmed.toDoubleOrNull()
        if (asDouble != null) {
            // Round to nearest penny to avoid floating-point drift.
            return Math.round(asDouble * 100.0)
        }
        return trimmed.toLongOrNull()
    }
}
