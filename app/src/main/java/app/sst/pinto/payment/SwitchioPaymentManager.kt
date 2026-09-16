package app.sst.pinto.payment

import android.content.Intent
import android.net.Uri
import app.sst.pinto.utils.AppLog
import app.sst.pinto.utils.WireLog
import app.sst.pinto.utils.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * Result of Switchio two-step **READ CARD** (pre-processing step 1).
 *
 * Card is read and stored on the terminal without capturing payment.
 * Use [par] / [monetToken] / [cardToken] for the backend daily-limit check,
 * then call [SwitchioPaymentManager.performReadCardPayment] to complete.
 */
data class SwitchioCardVerifyResult(
    val success: Boolean,
    val par: String? = null,
    val monetToken: String? = null,
    val cardToken: String? = null,
    val transactionId: String? = null,
    val transactionReference: String? = null,
    val cardInputType: String? = null,
    val responseCode: String? = null,
    val message: String? = null,
    val rawResponse: String? = null
)

/**
 * Result of a Switchio Pay SALE / REVERSAL / REFUND.
 *
 * [transactionId] is the Switchio-side id we sent (and get back) — persist it
 * so a later [performReversal] can address the original sale via
 * `originalTransactionId`.
 */
data class SwitchioPaymentResult(
    val success: Boolean,
    val resultCode: String? = null,
    val message: String? = null,
    val transactionId: String? = null,
    val par: String? = null,
    val monetToken: String? = null,
    val cardToken: String? = null,
    val authCode: String? = null,
    val pan: String? = null,
    val amount: Long? = null,
    val currencyCode: Int? = null,
    val callReversal: Boolean = false,
    val rawResponse: String? = null
)

/**
 * Thin wrapper around Switchio Pay / Monet+ ECR (Intent API).
 *
 * Protocol (ECR v5+ / v8 preferred):
 * - Action: `switchio.pay.ECR`
 * - URI: `app://switchiopay/api/main/v{N}/{endpoint}?data={json}`
 * - Result: Bundle key `transaction_result` (JSON string)
 * - Success: `responseCode == "OK"`
 *
 * Amounts are integer minor units. Currency is ISO-4217 numeric.
 */
object SwitchioPaymentManager {

    private const val TAG = "SwitchioPaymentManager"
    private var fileLogger: FileLogger? = null

    private const val ACTION_ECR = "switchio.pay.ECR"
    private const val RESULT_KEY_PRIMARY = "transaction_result"
    private const val RESULT_KEY_FALLBACK = "data"

    /** Default to v8 so optional fee fields are available; v5+ supplies PAR/monetToken. */
    private const val DEFAULT_ECR_VERSION = 8

    private const val SALE_TIMEOUT_MS = 180_000L
    private const val REVERSAL_TIMEOUT_MS = 120_000L
    private const val READ_CARD_TIMEOUT_MS = 120_000L

    private val transactionMutex = Mutex()

    private fun logDebug(message: String) {
        fileLogger?.d(TAG, message) ?: AppLog.d(TAG, message)
    }

    private fun logWarn(message: String) {
        fileLogger?.w(TAG, message) ?: AppLog.w(TAG, message)
    }

    private fun logError(message: String, t: Throwable? = null) {
        fileLogger?.e(TAG, message, t) ?: AppLog.e(TAG, message, t)
    }

    fun configureLogging(logger: FileLogger) {
        fileLogger = logger
        logDebug("SwitchioPaymentManager file logging configured")
    }

    /** True for `switchio`, `monet`, or `monetplus` provider ids. */
    fun isSwitchioProvider(provider: String?): Boolean {
        if (provider.isNullOrBlank()) return false
        val p = provider.lowercase(Locale.ROOT).trim()
        return p == "switchio" || p == "monet" || p == "monetplus"
    }

    /**
     * Two-step processing — step 1: READ CARD.
     *
     * Reads and temporarily stores card data on the terminal without capturing
     * payment. Returns card identity (PAR / tokens) for business processing
     * (e.g. PINTO daily limit check). Complete with [performReadCardPayment].
     *
     * Requires ECR v7+.
     */
    suspend fun performReadCard(
        amountFormatted: String,
        currencyAlphaCode: String?,
        transactionId: String = UUID.randomUUID().toString(),
        ecrVersion: Int = DEFAULT_ECR_VERSION
    ): SwitchioCardVerifyResult = transactionMutex.withLock {
        withContext(Dispatchers.IO) {
            logDebug(
                "Starting Switchio read_card: amount=$amountFormatted currency=$currencyAlphaCode " +
                    "transactionId=$transactionId ecr=v$ecrVersion"
            )

            val amountMinor = parseAmountToMinorUnits(amountFormatted)
            if (amountMinor == null) {
                return@withContext SwitchioCardVerifyResult(
                    success = false,
                    message = "Invalid amount format: $amountFormatted"
                )
            }

            val requestJson = JSONObject().apply {
                put("transactionId", transactionId)
                put("amount", amountMinor)
                put("currencyCode", toIso4217Numeric(currencyAlphaCode))
            }

            val resultIntent = launchEndpoint(
                endpoint = "read_card",
                requestJson = requestJson,
                ecrVersion = ecrVersion,
                timeoutMs = READ_CARD_TIMEOUT_MS
            ) ?: return@withContext SwitchioCardVerifyResult(
                success = false,
                message = "Switchio read_card timed out or launcher unavailable"
            )

            parseCardVerifyResult(resultIntent)
        }
    }

    /**
     * Two-step processing — step 2: READ CARD PAYMENT.
     *
     * Authorizes payment using the card previously stored by [performReadCard]
     * (no second tap for CLESS / magstripe when the terminal still holds the card).
     *
     * Requires ECR v7+.
     */
    suspend fun performReadCardPayment(
        amountFormatted: String,
        currencyAlphaCode: String?,
        transactionId: String = UUID.randomUUID().toString(),
        cardInputType: String? = "CLESS",
        serviceLevel: String = "SELF_SERVE",
        invoiceNumber: String? = null,
        ecrVersion: Int = DEFAULT_ECR_VERSION
    ): SwitchioPaymentResult = transactionMutex.withLock {
        withContext(Dispatchers.IO) {
            logDebug(
                "Starting Switchio read_card_payment: amount=$amountFormatted currency=$currencyAlphaCode " +
                    "transactionId=$transactionId cardInputType=$cardInputType ecr=v$ecrVersion"
            )

            val amountMinor = parseAmountToMinorUnits(amountFormatted)
            if (amountMinor == null) {
                return@withContext SwitchioPaymentResult(
                    success = false,
                    resultCode = "INVALID_AMOUNT",
                    message = "Invalid amount format: $amountFormatted"
                )
            }

            val unitPriceMajor = amountFormatted.trim().replace(',', '.')
            val productList = org.json.JSONArray().put(
                JSONObject().apply {
                    put("code", "PINTO")
                    put("measureUnit", "UNITS")
                    put("quantity", "1")
                    put("unitPrice", unitPriceMajor)
                    put("amount", amountMinor.toString())
                    put("taxCode", "C")
                }
            )

            val requestJson = JSONObject().apply {
                put("transactionId", transactionId)
                put("amount", amountMinor)
                put("currencyCode", toIso4217Numeric(currencyAlphaCode))
                put("cardInputType", cardInputType?.takeIf { it.isNotBlank() } ?: "CLESS")
                put("serviceLevel", serviceLevel)
                put("productList", productList)
                if (!invoiceNumber.isNullOrBlank()) {
                    put("invoiceNumber", invoiceNumber.take(20))
                }
            }

            val resultIntent = launchEndpoint(
                endpoint = "read_card_payment",
                requestJson = requestJson,
                ecrVersion = ecrVersion,
                timeoutMs = SALE_TIMEOUT_MS
            ) ?: return@withContext SwitchioPaymentResult(
                success = false,
                resultCode = "TX_TIMEOUT",
                message = "Switchio read_card_payment timed out or launcher unavailable"
            )

            parsePaymentResult(resultIntent, fallbackTransactionId = transactionId)
        }
    }

    /**
     * Legacy Card Verify — zero-amount verify returning PAR / tokens.
     * Prefer [performReadCard] + [performReadCardPayment] for PINTO pre-processing
     * (official two-step ECR model).
     */
    suspend fun performCardVerify(
        transactionId: String = UUID.randomUUID().toString(),
        ecrVersion: Int = DEFAULT_ECR_VERSION
    ): SwitchioCardVerifyResult = transactionMutex.withLock {
        withContext(Dispatchers.IO) {
            logDebug("Starting Switchio card_verify: transactionId=$transactionId ecr=v$ecrVersion")

            val requestJson = JSONObject().apply {
                put("transactionId", transactionId)
            }

            val resultIntent = launchEndpoint(
                endpoint = "card_verify",
                requestJson = requestJson,
                ecrVersion = ecrVersion,
                timeoutMs = READ_CARD_TIMEOUT_MS
            ) ?: return@withContext SwitchioCardVerifyResult(
                success = false,
                message = "Switchio card_verify timed out or launcher unavailable"
            )

            parseCardVerifyResult(resultIntent)
        }
    }

    /**
     * SALE / payment capture (one-step processing).
     *
     * @param amountFormatted major-unit decimal string, e.g. `"20.00"`.
     * @param currencyAlphaCode ISO alpha (GBP/EUR/USD/CZK); converted to numeric.
     * @param transactionId Switchio transaction id (persist for later reversal).
     */
    suspend fun performSale(
        amountFormatted: String,
        currencyAlphaCode: String?,
        transactionId: String = UUID.randomUUID().toString(),
        invoiceNumber: String? = null,
        ecrVersion: Int = DEFAULT_ECR_VERSION
    ): SwitchioPaymentResult = transactionMutex.withLock {
        withContext(Dispatchers.IO) {
            logDebug(
                "Starting Switchio payment: amount=$amountFormatted currency=$currencyAlphaCode " +
                    "transactionId=$transactionId ecr=v$ecrVersion"
            )

            val amountMinor = parseAmountToMinorUnits(amountFormatted)
            if (amountMinor == null) {
                return@withContext SwitchioPaymentResult(
                    success = false,
                    resultCode = "INVALID_AMOUNT",
                    message = "Invalid amount format: $amountFormatted"
                )
            }

            val currencyCode = toIso4217Numeric(currencyAlphaCode)
            val requestJson = JSONObject().apply {
                put("transactionId", transactionId)
                put("amount", amountMinor)
                put("currencyCode", currencyCode)
                if (!invoiceNumber.isNullOrBlank()) {
                    put("invoiceNumber", invoiceNumber.take(20))
                }
            }

            val resultIntent = launchEndpoint(
                endpoint = "payment",
                requestJson = requestJson,
                ecrVersion = ecrVersion,
                timeoutMs = SALE_TIMEOUT_MS
            ) ?: return@withContext SwitchioPaymentResult(
                success = false,
                resultCode = "TX_TIMEOUT",
                message = "Switchio payment timed out or launcher unavailable"
            )

            parsePaymentResult(resultIntent, fallbackTransactionId = transactionId)
        }
    }

    /**
     * Same-day reversal of a prior payment, addressed by
     * [originalTransactionId] (the Switchio id from the original sale).
     */
    suspend fun performReversal(
        originalTransactionId: String,
        transactionId: String = UUID.randomUUID().toString(),
        ecrVersion: Int = DEFAULT_ECR_VERSION
    ): SwitchioPaymentResult = transactionMutex.withLock {
        withContext(Dispatchers.IO) {
            logDebug(
                "Starting Switchio reversal: originalTransactionId=$originalTransactionId " +
                    "transactionId=$transactionId ecr=v$ecrVersion"
            )

            if (originalTransactionId.isBlank()) {
                return@withContext SwitchioPaymentResult(
                    success = false,
                    resultCode = "MISSING_ORIGINAL_ID",
                    message = "originalTransactionId is required for Switchio reversal"
                )
            }

            val requestJson = JSONObject().apply {
                put("transactionId", transactionId)
                put("originalTransactionId", originalTransactionId)
            }

            val resultIntent = launchEndpoint(
                endpoint = "reversal",
                requestJson = requestJson,
                ecrVersion = ecrVersion,
                timeoutMs = REVERSAL_TIMEOUT_MS
            ) ?: return@withContext SwitchioPaymentResult(
                success = false,
                resultCode = "TX_TIMEOUT",
                message = "Switchio reversal timed out or launcher unavailable"
            )

            parsePaymentResult(resultIntent, fallbackTransactionId = transactionId)
        }
    }

    /**
     * Standalone refund (later-stage / portal REFUND_REQUEST path).
     */
    suspend fun performRefund(
        amountFormatted: String,
        currencyAlphaCode: String?,
        transactionId: String = UUID.randomUUID().toString(),
        invoiceNumber: String? = null,
        ecrVersion: Int = DEFAULT_ECR_VERSION
    ): SwitchioPaymentResult = transactionMutex.withLock {
        withContext(Dispatchers.IO) {
            logDebug(
                "Starting Switchio refund: amount=$amountFormatted currency=$currencyAlphaCode " +
                    "transactionId=$transactionId ecr=v$ecrVersion"
            )

            val amountMinor = parseAmountToMinorUnits(amountFormatted)
            if (amountMinor == null) {
                return@withContext SwitchioPaymentResult(
                    success = false,
                    resultCode = "INVALID_AMOUNT",
                    message = "Invalid amount format: $amountFormatted"
                )
            }

            val requestJson = JSONObject().apply {
                put("transactionId", transactionId)
                put("amount", amountMinor)
                put("currencyCode", toIso4217Numeric(currencyAlphaCode))
                if (!invoiceNumber.isNullOrBlank()) {
                    put("invoiceNumber", invoiceNumber.take(20))
                }
            }

            val resultIntent = launchEndpoint(
                endpoint = "refund",
                requestJson = requestJson,
                ecrVersion = ecrVersion,
                timeoutMs = REVERSAL_TIMEOUT_MS
            ) ?: return@withContext SwitchioPaymentResult(
                success = false,
                resultCode = "TX_TIMEOUT",
                message = "Switchio refund timed out or launcher unavailable"
            )

            parsePaymentResult(resultIntent, fallbackTransactionId = transactionId)
        }
    }

    private suspend fun launchEndpoint(
        endpoint: String,
        requestJson: JSONObject,
        ecrVersion: Int,
        timeoutMs: Long
    ): Intent? {
        val uri = buildEcrUri(endpoint, requestJson.toString(), ecrVersion)
        // Do not use FLAG_ACTIVITY_REORDER_TO_FRONT here: if Switchio MainActivity is
        // already alive it can be brought forward without running the ECR payment
        // path (observed on UN20 — dashboard shown, no card prompt).
        val intent = Intent(ACTION_ECR, uri)
        WireLog.payRequest("SWITCHIO", "$endpoint $requestJson")

        val activityResult = SwitchioEcrBridge.startForResult(intent, timeoutMs)
        if (activityResult == null) {
            logWarn("Switchio $endpoint returned no activity result (timeout/unbound)")
            return null
        }

        logDebug(
            "Switchio $endpoint activity resultCode=${activityResult.resultCode} " +
                "hasData=${activityResult.data != null}"
        )
        return activityResult.data
    }

    private fun buildEcrUri(endpoint: String, dataJson: String, ecrVersion: Int): Uri {
        return Uri.Builder()
            .scheme("app")
            .authority("switchiopay")
            .path("/api/main/v$ecrVersion/$endpoint")
            .appendQueryParameter("data", dataJson)
            .build()
    }

    private fun extractResultJson(intent: Intent?): JSONObject? {
        if (intent == null) return null
        val extras = intent.extras
        val raw = extras?.getString(RESULT_KEY_PRIMARY)
            ?: extras?.getString(RESULT_KEY_FALLBACK)
            ?: intent.dataString

        if (raw.isNullOrBlank()) {
            val keys = extras?.keySet()?.joinToString() ?: "(no extras)"
            logWarn("Switchio result missing JSON. extras keys=[$keys]")
            return null
        }

        return try {
            JSONObject(raw).also { json ->
                // Diagnostics: shows which field identifies the card (PAR / token) for the limit check.
                WireLog.payResponse("SWITCHIO", "${sanitizeForLog(json)}")
                // Shape only (every digit shown as 9): tells whether Switchio sends the full or a masked PAN.
                logDebug(
                    "Switchio card field shapes: pan=${shapeForLog(json.optString("pan"))} " +
                        "expiration=${shapeForLog(json.optString("expiration"))} " +
                        "panSequenceNumber=${shapeForLog(json.optString("panSequenceNumber"))}"
                )
            }
        } catch (e: Exception) {
            logError("Failed to parse Switchio result JSON: $raw", e)
            null
        }
    }

    private val SENSITIVE_KEY = Regex("^(track.*|cvv|cvc|pin|pinBlock|expir.*|cardholder.*|holderName)$", RegexOption.IGNORE_CASE)
    private val PAN_KEY = Regex("^(pan|cardNumber)$", RegexOption.IGNORE_CASE)
    private val FULL_PAN = Regex("""(?<!\d)(\d{6})\d{3,9}(\d{4})(?!\d)""")

    /**
     * Copy of a Switchio result for the log file. Everything is printed as received, except the
     * card number (only its first 6 and last 4 digits are kept) and track / CVV / PIN / expiry /
     * cardholder values, which are hidden: the log is retained on the device and downloadable
     * from the portal, so it must never hold full card data.
     */
    private fun sanitizeForLog(value: Any?, key: String = ""): Any? = when {
        value is JSONObject -> JSONObject().also { copy ->
            value.keys().forEach { k -> copy.put(k, sanitizeForLog(value.opt(k), k)) }
        }
        value is org.json.JSONArray -> org.json.JSONArray().also { copy ->
            for (i in 0 until value.length()) copy.put(sanitizeForLog(value.opt(i), key))
        }
        SENSITIVE_KEY.matches(key) -> "***"
        PAN_KEY.matches(key) && value != null ->
            FULL_PAN.replace(value.toString()) { "${it.groupValues[1]}******${it.groupValues[2]}" }
        else -> value
    }

    /** "541333******0036" -> "999999******9999 (length 16)"; never exposes a digit. */
    private fun shapeForLog(value: String?): String =
        if (value.isNullOrBlank()) "(none)" else "${value.replace(Regex("\\d"), "9")} (length ${value.length})"

    private fun parseCardVerifyResult(intent: Intent?): SwitchioCardVerifyResult {
        val json = extractResultJson(intent)
            ?: return SwitchioCardVerifyResult(
                success = false,
                message = "No transaction_result from Switchio read_card/card_verify"
            )

        val responseCode = json.optString("responseCode").takeIf { it.isNotBlank() }
        val ok = responseCode.equals("OK", ignoreCase = true)
        val message = json.optString("responseMessage").takeIf { it.isNotBlank() }
            ?: json.optString("merchantMessage").takeIf { it.isNotBlank() }

        logDebug(
            "Switchio read_card/card_verify parsed: ok=$ok responseCode=$responseCode " +
                "par=${json.optString("par")} monetToken=${json.optString("monetToken")} " +
                "cardInputType=${json.optString("cardInputType")} " +
                // Field names only (never values): shows which card identifiers Switchio returns.
                "fields=${json.keys().asSequence().toList()}"
        )

        return SwitchioCardVerifyResult(
            success = ok,
            par = json.optString("par").takeIf { it.isNotBlank() },
            monetToken = json.optString("monetToken").takeIf { it.isNotBlank() },
            cardToken = json.optString("cardToken").takeIf { it.isNotBlank() },
            transactionId = json.optString("transactionId").takeIf { it.isNotBlank() },
            transactionReference = json.optString("transactionReference").takeIf { it.isNotBlank() },
            cardInputType = json.optString("cardInputType").takeIf { it.isNotBlank() },
            responseCode = responseCode,
            message = if (ok) message else (message ?: "Card read failed ($responseCode)"),
            rawResponse = json.toString()
        )
    }

    private fun parsePaymentResult(
        intent: Intent?,
        fallbackTransactionId: String
    ): SwitchioPaymentResult {
        val json = extractResultJson(intent)
            ?: return SwitchioPaymentResult(
                success = false,
                resultCode = "NO_RESULT",
                message = "No transaction_result from Switchio",
                transactionId = fallbackTransactionId
            )

        val responseCode = json.optString("responseCode").takeIf { it.isNotBlank() }
        val ok = responseCode.equals("OK", ignoreCase = true)
        val message = json.optString("responseMessage").takeIf { it.isNotBlank() }
            ?: json.optString("merchantMessage").takeIf { it.isNotBlank() }

        val txnId = json.optString("transactionId").takeIf { it.isNotBlank() }
            ?: fallbackTransactionId

        logDebug(
            "Switchio payment parsed: ok=$ok responseCode=$responseCode transactionId=$txnId " +
                "par=${json.optString("par")} monetToken=${json.optString("monetToken")} " +
                "callReversal=${json.optBoolean("callReversal")} " +
                // Field names only (never values): shows which card identifiers Switchio returns.
                "fields=${json.keys().asSequence().toList()}"
        )

        return SwitchioPaymentResult(
            success = ok,
            resultCode = responseCode,
            message = if (ok) (message ?: "APPROVED") else (message ?: "Payment failed ($responseCode)"),
            transactionId = txnId,
            par = json.optString("par").takeIf { it.isNotBlank() },
            monetToken = json.optString("monetToken").takeIf { it.isNotBlank() },
            cardToken = json.optString("cardToken").takeIf { it.isNotBlank() },
            authCode = json.optString("authCode").takeIf { it.isNotBlank() },
            pan = json.optString("pan").takeIf { it.isNotBlank() },
            amount = if (json.has("amount")) json.optLong("amount") else null,
            currencyCode = if (json.has("currency")) json.optInt("currency")
            else if (json.has("currencyCode")) json.optInt("currencyCode")
            else null,
            callReversal = json.optBoolean("callReversal", false),
            rawResponse = json.toString()
        )
    }

    fun parseAmountToMinorUnits(amountFormatted: String): Long? {
        return try {
            val normalized = amountFormatted.trim().replace(',', '.')
            val parts = normalized.split('.')
            val major = parts[0].toLong()
            val minor = when {
                parts.size == 1 -> 0L
                parts[1].length == 1 -> parts[1].toLong() * 10
                else -> parts[1].take(2).padEnd(2, '0').toLong()
            }
            major * 100 + minor
        } catch (_: Exception) {
            null
        }
    }

    fun toIso4217Numeric(currencyAlphaCode: String?): Int {
        return when (currencyAlphaCode?.uppercase(Locale.ROOT)?.trim()) {
            "GBP" -> 826
            "EUR" -> 978
            "USD" -> 840
            "CZK" -> 203
            "PLN" -> 985
            "HUF" -> 348
            "RON" -> 946
            "CHF" -> 756
            else -> 978 // EUR default for Monet+/Switchio sites
        }
    }

    /**
     * Prefer PAR, then monetToken, then cardToken for backend limit identity. Switchio currently
     * sends none of them, so fall back to the digits of the masked PAN ("541333******0036" ->
     * "5413330036") until a real PAR is enabled on the account.
     */
    fun cardIdentityForLimit(result: SwitchioPaymentResult): String? {
        return result.par?.takeIf { it.isNotBlank() }
            ?: result.monetToken?.takeIf { it.isNotBlank() }
            ?: result.cardToken?.takeIf { it.isNotBlank() }
            ?: maskedPanIdentity(result.pan)
    }

    fun cardIdentityForLimit(result: SwitchioCardVerifyResult): String? {
        return result.par?.takeIf { it.isNotBlank() }
            ?: result.monetToken?.takeIf { it.isNotBlank() }
            ?: result.cardToken?.takeIf { it.isNotBlank() }
            ?: maskedPanIdentity(panFromRawResponse(result.rawResponse))
    }

    private fun maskedPanIdentity(maskedPan: String?): String? =
        LimitCheckCardId.fromMaskedPan(maskedPan)?.also {
            logWarn(
                "No PAR/token from Switchio; using masked-PAN digits as the card reference. " +
                    "Cards sharing a bank range and last four digits share one daily limit."
            )
        }

    private fun panFromRawResponse(rawResponse: String?): String? {
        if (rawResponse.isNullOrBlank()) return null
        return try {
            JSONObject(rawResponse).optString("pan").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            logWarn("Could not read pan from Switchio result: ${e.message}")
            null
        }
    }
}
