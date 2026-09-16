package app.sst.pinto.payment

import android.content.Context
import app.sst.pinto.utils.AppLog
import app.sst.pinto.utils.WireLog
import app.sst.pinto.utils.FileLogger
import eu.ccvlab.api_android.MAPI
import eu.ccvlab.mapi.api.PaymentService
import eu.ccvlab.mapi.core.DeliveryBoxCallback
import eu.ccvlab.mapi.core.MAPIError
import eu.ccvlab.mapi.core.RequestType
import eu.ccvlab.mapi.core.api.response.delegate.PaymentDelegate
import eu.ccvlab.mapi.core.api.response.result.Error as MapiError
import eu.ccvlab.mapi.core.payment.Money
import eu.ccvlab.mapi.core.payment.Payment
import eu.ccvlab.mapi.core.payment.PaymentResult
import eu.ccvlab.mapi.core.terminal.ExternalTerminal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency
import java.util.Locale

/**
 * Result of a CCV SALE / REFUND.
 *
 * For the daily-limit validation we ship [panHash] (a stable hash of the full PAN,
 * the CCV equivalent of NNSmart's PAR) to the backend as the card identifier; if it
 * is absent we fall back to [cardToken].
 *
 * To later REFUND the sale, CCV's OPI-NL flow needs either the numeric
 * [receiptNumber] (used as `referenceNumber`) or the [approvalCode]
 * (with `originalTransactionRequestType = CARD_PAYMENT`). Both are captured here and
 * persisted with the reversible-sale history so a refund can run immediately on a
 * limit rejection or later from a portal REFUND_REQUEST.
 */
data class CcvPaymentResult(
    val success: Boolean,
    val resultCode: String? = null,
    val message: String? = null,
    // Card identifier for the limit check.
    val panHash: String? = null,
    val cardToken: String? = null,
    val pan: String? = null,
    // Refund-addressing parameters.
    val approvalCode: String? = null,
    val trxReferenceNumber: String? = null,
    val receiptNumber: String? = null,
    val paymentSTAN: String? = null,
    val amountMinor: Long? = null,
    val currencyAlphaCode: String? = null,
    val rawResponse: String? = null
)

/**
 * Thin wrapper around the CCV MAPI Android SDK, mirroring PayBridge's `CcvAdapter`.
 *
 * Talks to the CCV payment engine on the same device (IM25 / IM30 / IM30 V2 / A920 /
 * A77) via the OPI-NL protocol on localhost:4100 (compatibility port 4102).
 *
 * Unlike Planet Integra there is no card-check that returns a token before payment is
 * taken — the card identifier (`panHash`) is only available AFTER the SALE completes.
 * So the daily-limit flow for this provider is sale-first:
 *   1. Run SALE — money is captured on the terminal.
 *   2. Send `panHash` + amount to the backend for limit validation.
 *   3. If approved: continue the normal post-payment flow.
 *   4. If rejected: run REFUND addressed by the original approvalCode / receiptNumber.
 *
 * Amount note: callers pass amounts in MAJOR units (e.g. "20.00"), which is what CCV's
 * [Money] expects, so we parse them straight into a [BigDecimal] (unlike `CcvAdapter`,
 * which divides by 100 because PayBridge hands it minor units).
 */
object CcvPaymentManager {

    private const val TAG = "CcvPaymentManager"

    // On-device CCV terminal: OPI-NL on localhost (matches PayBridge Constants).
    private const val CCV_DEFAULT_IP = "127.0.0.1"
    private const val CCV_DEFAULT_PORT = 4100        // OPI-NL primary
    private const val CCV_COMPATIBILITY_PORT = 4102  // OPI-NL secondary

    private const val DEFAULT_CURRENCY = "GBP"
    private const val PAYMENT_TIMEOUT_MS = 120_000L

    // Serialise terminal interactions (sale or refund) like NNSmartPaymentManager.
    private val transactionMutex = Mutex()

    @Volatile private var sdkInitialized: Boolean = false
    private var fileLogger: FileLogger? = null

    fun configureLogging(logger: FileLogger) {
        fileLogger = logger
        logDebug("CcvPaymentManager file logging configured")
    }

    private fun logDebug(message: String) {
        if (fileLogger != null) fileLogger?.d(TAG, message) else AppLog.d(TAG, message)
    }

    private fun logWarn(message: String) {
        if (fileLogger != null) fileLogger?.w(TAG, message) else AppLog.w(TAG, message)
    }

    private fun logError(message: String, t: Throwable? = null) {
        if (fileLogger != null) fileLogger?.e(TAG, message, t) else AppLog.e(TAG, message, t)
    }

    /** Returns true if the provider string identifies the CCV terminal. */
    fun isCcvProvider(provider: String?): Boolean {
        if (provider.isNullOrBlank()) return false
        return provider.lowercase(Locale.ROOT).trim() == "ccv"
    }

    /** True once the CCV MAPI classes are on the classpath (AAR present). */
    private fun isSdkOnClasspath(): Boolean = try {
        Class.forName("eu.ccvlab.api_android.MAPI")
        Class.forName("eu.ccvlab.mapi.api.PaymentService")
        Class.forName("eu.ccvlab.mapi.core.payment.Payment")
        true
    } catch (_: Throwable) {
        false
    }

    /**
     * Initialise the CCV MAPI SDK. Safe to call repeatedly. Returns false if the SDK
     * is unavailable (e.g. running on non-CCV hardware) so callers can surface a
     * clean error instead of crashing.
     */
    fun initialize(context: Context): Boolean {
        if (sdkInitialized) return true
        if (!isSdkOnClasspath()) {
            logWarn("CCV MAPI SDK not on classpath (api-hardware AAR missing)")
            return false
        }
        return try {
            MAPI.initialize(context.applicationContext)
            sdkInitialized = true
            logDebug("CCV MAPI SDK initialized")
            true
        } catch (e: Throwable) {
            sdkInitialized = false
            logError("CCV MAPI init failed: ${e.message}", e)
            false
        }
    }

    /**
     * Perform a SALE on the CCV terminal.
     *
     * @param amountFormatted amount as a decimal string in MAJOR units, e.g. "20.00".
     * @param requesterRef caller reference (round-tripped in merchantReference).
     * @param currencyAlphaCode ISO-4217 alpha code, e.g. "GBP".
     */
    suspend fun performSale(
        context: Context,
        amountFormatted: String,
        requesterRef: String,
        currencyAlphaCode: String? = null
    ): CcvPaymentResult = transactionMutex.withLock {
        runPayment(
            context = context,
            type = Payment.Type.SALE,
            amountFormatted = amountFormatted,
            requesterRef = requesterRef,
            currencyAlphaCode = currencyAlphaCode,
            approvalCode = null,
            receiptNumber = null
        )
    }

    /**
     * REFUND a previous CCV sale.
     *
     * Addressing follows CcvAdapter: prefer the numeric [receiptNumber]
     * (`referenceNumber`); otherwise use [approvalCode] +
     * `originalTransactionRequestType = CARD_PAYMENT`.
     */
    suspend fun performRefund(
        context: Context,
        amountFormatted: String,
        requesterRef: String,
        currencyAlphaCode: String? = null,
        approvalCode: String? = null,
        receiptNumber: String? = null
    ): CcvPaymentResult = transactionMutex.withLock {
        runPayment(
            context = context,
            type = Payment.Type.REFUND,
            amountFormatted = amountFormatted,
            requesterRef = requesterRef,
            currencyAlphaCode = currencyAlphaCode,
            approvalCode = approvalCode,
            receiptNumber = receiptNumber
        )
    }

    private suspend fun runPayment(
        context: Context,
        type: Payment.Type,
        amountFormatted: String,
        requesterRef: String,
        currencyAlphaCode: String?,
        approvalCode: String?,
        receiptNumber: String?
    ): CcvPaymentResult {
        if (!sdkInitialized && !initialize(context)) {
            return CcvPaymentResult(
                success = false,
                resultCode = "CCV_UNAVAILABLE",
                message = "CCV terminal not available"
            )
        }

        val major = parseAmountMajor(amountFormatted)
            ?: return CcvPaymentResult(
                success = false,
                resultCode = "INVALID_AMOUNT",
                message = "Invalid amount format: $amountFormatted"
            )

        return withContext(Dispatchers.IO) {
            val deferred = CompletableDeferred<CcvPaymentResult>()
            try {
                // mode + socketMode must be set explicitly or ConnectionManager NPEs
                // (surfaces as a useless TERMINAL_CONNECTION_LOST). COMPATIBLE +
                // DUAL_SOCKET matches the on-device OPI-NL setup (primary 4100,
                // compatibility 4102) — see CcvAdapter.
                val terminal = ExternalTerminal.builder()
                    .ipAddress(CCV_DEFAULT_IP)
                    .port(CCV_DEFAULT_PORT)
                    .compatibilityPort(CCV_COMPATIBILITY_PORT)
                    .mode(ExternalTerminal.Mode.COMPATIBLE)
                    .socketMode(ExternalTerminal.SocketMode.DUAL_SOCKET)
                    .terminalType(ExternalTerminal.TerminalType.OPI_NL)
                    .build()

                // OPI-NL requires the CCV requestId to be a numeric string ≤ 9 digits.
                // Synthesize a compliant one and round-trip the caller ref via
                // merchantReference (≤ 100 chars accepted by CCV).
                val ccvRequestId = toCcvRequestId(requesterRef)
                val money = toMoney(major, currencyAlphaCode)

                val paymentBuilder = Payment.builder()
                    .requestId(ccvRequestId)
                    .type(type)
                    .amount(money)

                if (requesterRef.isNotBlank() && requesterRef.length <= 100) {
                    paymentBuilder.merchantReference(requesterRef)
                }

                if (type == Payment.Type.REFUND) {
                    val numericRef = receiptNumber?.trim()?.toIntOrNull()
                    if (numericRef != null) {
                        paymentBuilder.referenceNumber(numericRef)
                    } else if (!approvalCode.isNullOrBlank()) {
                        paymentBuilder.approvalCode(approvalCode)
                        paymentBuilder.originalTransactionRequestType(RequestType.CARD_PAYMENT)
                    }
                    // else: unaddressed refund — terminal will prompt for the card.
                }

                val payment = paymentBuilder.build()
                val delegate = buildDelegate(deferred)

                logDebug(
                    "Starting CCV ${type.name} - ref=$requesterRef amount=$amountFormatted " +
                        "currency=$currencyAlphaCode receiptNumber=$receiptNumber approvalCode=$approvalCode"
                )
                PaymentService().payment(terminal, payment, delegate)

                withTimeout(PAYMENT_TIMEOUT_MS) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                logWarn("CCV ${type.name} timed out for ref=$requesterRef")
                CcvPaymentResult(success = false, resultCode = "TX_TIMEOUT", message = "CCV payment timeout")
            } catch (e: Throwable) {
                logError("CCV ${type.name} failed", e)
                CcvPaymentResult(success = false, resultCode = "DEVICE_ERROR", message = e.message ?: "CCV failure")
            }
        }
    }

    private fun buildDelegate(
        deferred: CompletableDeferred<CcvPaymentResult>
    ): PaymentDelegate = object : PaymentDelegate {
        // CCV's OPI-NL flow sends a DeliveryBox DeviceRequest mid-payment expecting
        // the POS to confirm "goods delivered". We are a payment frontend with no
        // physical delivery box, so always proceed=true so the flow can continue to
        // acquirer authorisation (otherwise the terminal sits then aborts ~30s).
        override fun onDeliverGoodsOrServices(callback: DeliveryBoxCallback) {
            logDebug("CCV DeliveryBox prompt — auto-confirming delivery=true")
            try {
                callback.proceed(true)
            } catch (e: Throwable) {
                logError("Failed to confirm DeliveryBox", e)
            }
        }

        override fun onPaymentSuccess(result: PaymentResult) {
            if (deferred.isCompleted) return
            WireLog.payResponse("CCV", "approved approval=${result.approvalCode} trxRef=${result.trxReferenceNumber}")
            deferred.complete(mapResult(result, success = true))
        }

        override fun onPaymentError(result: PaymentResult) {
            if (deferred.isCompleted) return
            val state = runCatching { result.state() }.getOrNull()
            WireLog.payResponse("CCV", "declined state=$state returnCode=${result.returnCode} answerCode=${result.answerCode}")
            deferred.complete(mapResult(result, success = false))
        }

        override fun onError(error: MAPIError) {
            if (deferred.isCompleted) return
            logWarn("CCV onError(MAPIError): $error")
            deferred.complete(
                CcvPaymentResult(success = false, resultCode = "DEVICE_ERROR", message = "CCV MAPIError: $error")
            )
        }

        override fun onError(error: MapiError) {
            if (deferred.isCompleted) return
            logWarn("CCV onError(Error): $error")
            deferred.complete(
                CcvPaymentResult(success = false, resultCode = "DEVICE_ERROR", message = "CCV Error: $error")
            )
        }
    }

    private fun mapResult(result: PaymentResult, success: Boolean): CcvPaymentResult {
        val returnCode = result.returnCode?.takeIf { it.isNotBlank() }
        val answerCode = result.answerCode?.takeIf { it.isNotBlank() }
        val message = when {
            success -> "APPROVED"
            answerCode != null -> answerCode
            returnCode != null -> returnCode
            else -> "Declined"
        }
        val amountMinor = result.amount?.value
            ?.let { it.multiply(BigDecimal(100)).toLong() }
        val currency = result.amount?.currency?.currencyCode
        val card = result.card
        return CcvPaymentResult(
            success = success,
            resultCode = if (success) "A" else (returnCode ?: "DECLINED"),
            message = message,
            panHash = card?.panHash?.takeIf { it.isNotBlank() },
            cardToken = (result.token ?: result.cardToken())?.takeIf { it.isNotBlank() },
            pan = card?.pan?.takeIf { it.isNotBlank() },
            approvalCode = result.approvalCode?.takeIf { it.isNotBlank() },
            trxReferenceNumber = result.trxReferenceNumber?.takeIf { it.isNotBlank() },
            receiptNumber = result.receiptNumber?.takeIf { it.isNotBlank() },
            paymentSTAN = result.paymentSTAN?.takeIf { it.isNotBlank() },
            amountMinor = amountMinor,
            currencyAlphaCode = currency,
            rawResponse = "state=${runCatching { result.state() }.getOrNull()} returnCode=$returnCode answerCode=$answerCode"
        )
    }

    /**
     * Derive a CCV-compliant requestId from a free-form caller ref. CCV's OPI-NL
     * validator requires `Integer.parseUnsignedInt`-parseable, length ≤ 9, so we
     * take the trailing digits and clamp. Falls back to a 9-digit modulo of the
     * current time when no digits are present.
     */
    private fun toCcvRequestId(callerRef: String): String {
        val digits = callerRef.filter { it.isDigit() }
        val trimmed = digits.takeLast(9).trimStart('0').ifEmpty { "0" }
        return trimmed.toLongOrNull()?.let { (it % 1_000_000_000L).toString() }
            ?: (System.currentTimeMillis() % 1_000_000_000L).toString()
    }

    private fun toMoney(major: BigDecimal, currencyAlpha: String?): Money {
        val alpha = currencyAlpha?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.length == 3 }
        val currency = try {
            Currency.getInstance(alpha ?: DEFAULT_CURRENCY)
        } catch (e: Exception) {
            logWarn("Unrecognised currency '$currencyAlpha', defaulting to $DEFAULT_CURRENCY")
            Currency.getInstance(DEFAULT_CURRENCY)
        }
        return Money(major, currency)
    }

    /** Parse a major-unit decimal string ("20.00") into a 2-dp BigDecimal. */
    private fun parseAmountMajor(amountFormatted: String): BigDecimal? {
        val trimmed = amountFormatted.trim()
        if (trimmed.isEmpty()) return null
        return try {
            BigDecimal(trimmed).setScale(2, RoundingMode.HALF_UP)
        } catch (e: Exception) {
            null
        }
    }
}
