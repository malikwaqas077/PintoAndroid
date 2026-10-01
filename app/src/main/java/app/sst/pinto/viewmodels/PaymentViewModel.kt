package app.sst.pinto.viewmodels

import android.app.Application
import android.content.Intent
import android.content.SharedPreferences
import app.sst.pinto.utils.AppLog
import app.sst.pinto.utils.WireLog
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sst.pinto.data.models.MessageData
import app.sst.pinto.data.models.PaymentScreenState
import app.sst.pinto.data.models.SocketMessage
import app.sst.pinto.network.PortalTransactionReporter
import app.sst.pinto.network.PortalWebSocketRepository
import app.sst.pinto.network.SocketManager
import app.sst.pinto.utils.NetworkConnectivity
import app.sst.pinto.payment.PlanetPaymentManager
import app.sst.pinto.payment.DccDetails
import app.sst.pinto.payment.MockPaymentManager
import app.sst.pinto.payment.NNSmartPaymentManager
import app.sst.pinto.payment.NNSmartPaymentResult
import app.sst.pinto.payment.NNSmartCardVerificationResult
import app.sst.pinto.payment.CcvPaymentManager
import app.sst.pinto.payment.CcvPaymentResult
import app.sst.pinto.payment.SwitchioPaymentManager
import app.sst.pinto.payment.SwitchioPaymentResult
import app.sst.pinto.payment.SwitchioCardVerifyResult
import app.sst.pinto.data.AppDatabase
import app.sst.pinto.utils.FileLogger
import app.sst.pinto.utils.LenientIntAdapter
import app.sst.pinto.utils.LenientLongAdapter
import app.sst.pinto.utils.getDeviceIpAddress
import app.sst.pinto.utils.getDeviceSerialNumber
import app.sst.pinto.utils.TimeoutManager
import app.sst.pinto.config.ConfigManager
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class PaymentViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "PaymentViewModel"

    private companion object {
        const val PREF_RECENT_REVERSIBLE_SALES = "recent_reversible_sales"
    }

    /**
     * Fallback PAR value used when the NNSmart / Newland terminal does not
     * populate the real PAR on the sale response (common on dev terminals).
     * Lets the CARD_CHECK_RESULT / backend limit check still be exercised.
     */
    private val CARD_ID_MISSING_MESSAGE =
        "Your card could not be verified for the daily limit. The payment has been cancelled."

    private val isDebuggableBuild: Boolean by lazy {
        (getApplication<android.app.Application>().applicationInfo.flags and
            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private val socketManager = SocketManager.getInstance()
    private val timeoutManager = TimeoutManager.getInstance()
    private val configManager = ConfigManager.getInstance(getApplication())
    private val fileLogger = FileLogger.getInstance(getApplication())
    private val portalTxnReporter = PortalTransactionReporter.getInstance(getApplication())
    private val recoveryPrefs: SharedPreferences =
        getApplication<Application>().getSharedPreferences("payment_recovery", 0)

    // Store the server URL as a class property
    private var serverUrl: String = configManager.getServerUrl()

    private val _screenState = MutableStateFlow<PaymentScreenState>(PaymentScreenState.Loading)
    val screenState: StateFlow<PaymentScreenState> = _screenState

    // State for screensaver visibility
    private val _isScreensaverVisible = MutableStateFlow(false)
    val isScreensaverVisible: StateFlow<Boolean> = _isScreensaverVisible

    // Track if we're on the amount selection screen
    private val _isOnAmountScreen = MutableStateFlow(false)
    val isOnAmountScreen: StateFlow<Boolean> = _isOnAmountScreen

    private var currentTransactionId: String? = null
    private var currentAmount: Int = 0 // Track the current amount
    private var lastActiveState: PaymentScreenState? = null // Track the state before timeout
    
    // Payment processing state
    private var pendingCardCheckResult: app.sst.pinto.payment.CardCheckResult? = null

    // For NNSmart / Newland: the SALE is executed BEFORE the backend limit
    // check (since PAR is only available after a sale). If the backend rejects
    // the limit we use this to run a Cancellation on the terminal.
    private var pendingNnsmartSale: NNSmartPaymentResult? = null

    // For NNSmart Card Verification flow: holds the CV result (PAR + transactionId)
    // while awaiting the backend limit check response.
    private var pendingNnsmartCardVerification: NNSmartCardVerificationResult? = null

    // For CCV: the SALE is captured BEFORE the backend limit check (the card
    // hash is only available after a sale). If the backend rejects the limit we
    // use this to run a REFUND on the terminal.
    private var pendingCcvSale: CcvPaymentResult? = null

    // For Switchio / Monet+: sale-first (post-processing) keeps the captured
    // sale so a limit rejection can reverse it via originalTransactionId.
    private var pendingSwitchioSale: SwitchioPaymentResult? = null

    // For Planet/Integra post-processing: the approved sale waiting on the
    // backend limit check; a rejection reverses it with Sale-Reversal.
    private var pendingPlanetSale: app.sst.pinto.payment.PlanetPaymentResult? = null

    // For Switchio Card Verify / READ CARD (pre-processing): PAR obtained before
    // capture. On approve we run read_card_payment; on reject nothing was captured.
    private var pendingSwitchioCardVerify: SwitchioCardVerifyResult? = null

    // --- Ticket redemption (redeem to bank account) state ---

    // The redemption the server initiated via REDEEM_REQUEST; carries the
    // bank/cash breakdown once REDEEM_BREAKDOWN arrives.
    private data class ActiveRedemption(
        val transactionId: String,
        val ticketId: String?,
        var bankAmount: Int = 0,
        var cashAmount: Int = 0,
        var totalAmount: Int = 0,
        var currency: String = "£"
    )
    private var activeRedemption: ActiveRedemption? = null
    private var isProcessingRedeem: Boolean = false
    // Fails the redemption if the breakdown never arrives / user never answers.
    private var redeemTimeoutJob: Job? = null
    // Returns to the main screen if the server never drives the post-redeem
    // print flow after a successful redemption.
    private var redeemSuccessFallbackJob: Job? = null

    // How long the customer may look at the bank/cash breakdown before we
    // auto-cancel (ticket stays valid).
    private val REDEEM_CONFIRMATION_TIMEOUT_MS = 60_000L
    // How long we wait for REDEEM_BREAKDOWN / server REDEEM_RESULT after REDEEM_REQUEST.
    private val REDEEM_BREAKDOWN_TIMEOUT_MS = 30_000L
    // How long we wait for server REDEEM_RESULT after the customer presses CONTINUE.
    private val REDEEM_RESULT_TIMEOUT_MS = 60_000L

    private var isProcessingPayment: Boolean = false
    private var isHandlingPaymentLocally: Boolean = false // Flag to track if we're handling payment locally (YASPA disabled)
    private var allowNavigationFromLimitError: Boolean = false // Flag to allow navigation away from LIMIT_ERROR after user reset
    
    // Track last successful sale transaction for refund/reversal
    data class SuccessfulSaleTransaction(
        val transactionId: String,
        val amount: Int, // Amount in cents/pence
        val requesterTransRefNum: String?, // From payment result
        // Which provider captured the sale (used to route the reversal/refund).
        val provider: String? = null,
        // CCV refund-addressing params (OPI-NL): the refund must be addressed by the
        // original sale's approvalCode and/or numeric receiptNumber. Persisted so a
        // later portal REFUND_REQUEST can complete a CCV refund off stored values.
        val approvalCode: String? = null,
        val receiptNumber: String? = null,
        val trxReferenceNumber: String? = null,
        val currency: String? = null
    )
    private var lastSuccessfulSale: SuccessfulSaleTransaction? = null

    // Result of resolving which stored sale a reversal/refund request targets.
    private data class ReversalTarget(
        val sale: SuccessfulSaleTransaction?,
        val identifiersProvided: Boolean
    )

    // Bounded history of recent successful sales so a server-initiated
    // reversal/refund can target the *specific* transaction it names rather
    // than always voiding the most recent sale. This prevents voiding a fresh
    // transaction when a newer customer paid between a failed void and its
    // retry. Keyed by backend transactionId, insertion-ordered (newest last).
    private val maxRecentSales = 10
    private val recentSuccessfulSales = object : LinkedHashMap<String, SuccessfulSaleTransaction>() {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, SuccessfulSaleTransaction>
        ): Boolean = size > maxRecentSales
    }

    /** Record a successful sale as both the latest and in the lookup history. */
    private fun recordSuccessfulSale(sale: SuccessfulSaleTransaction) {
        lastSuccessfulSale = sale
        // Re-insert so repeated transactionIds refresh their recency position.
        recentSuccessfulSales.remove(sale.transactionId)
        recentSuccessfulSales[sale.transactionId] = sale
        persistRecentSuccessfulSales()
        AppLog.d(
            TAG,
            "Recorded successful sale tx=${sale.transactionId} ref=${sale.requesterTransRefNum} " +
                "(history size=${recentSuccessfulSales.size})"
        )
        reportPortalTransaction(
            transactionId = sale.transactionId,
            status = "Approved",
            amountMinor = sale.amount,
            currency = sale.currency,
            provider = sale.provider,
            approvalCode = sale.approvalCode,
            receiptNumber = sale.receiptNumber,
            requesterRef = sale.requesterTransRefNum,
            trxReference = sale.trxReferenceNumber,
            message = "APPROVED"
        )
    }

    /** Forget a sale once it has been reversed so it cannot be reversed twice. */
    private fun forgetSuccessfulSale(sale: SuccessfulSaleTransaction?) {
        if (sale == null) return
        recentSuccessfulSales.remove(sale.transactionId)
        if (lastSuccessfulSale?.transactionId == sale.transactionId) {
            lastSuccessfulSale = recentSuccessfulSales.values.lastOrNull()
        }
        persistRecentSuccessfulSales()
        reportPortalTransaction(
            transactionId = sale.transactionId,
            status = "Reversed",
            amountMinor = sale.amount,
            currency = sale.currency,
            provider = sale.provider,
            approvalCode = sale.approvalCode,
            receiptNumber = sale.receiptNumber,
            requesterRef = sale.requesterTransRefNum,
            trxReference = sale.trxReferenceNumber,
            message = "REVERSED"
        )
    }

    /** Fire-and-forget report to Ask portal Transactions tab. */
    private fun reportPortalTransaction(
        transactionId: String,
        status: String,
        amountMinor: Int,
        currency: String? = null,
        provider: String? = null,
        approvalCode: String? = null,
        receiptNumber: String? = null,
        requesterRef: String? = null,
        trxReference: String? = null,
        cardToken: String? = null,
        message: String? = null
    ) {
        portalTxnReporter.reportAsync(
            PortalTransactionReporter.Report(
                transactionId = transactionId,
                status = status,
                amountMinor = amountMinor,
                currency = currency ?: "GBP",
                provider = provider,
                approvalCode = approvalCode,
                receiptNumber = receiptNumber,
                requesterRef = requesterRef,
                trxReference = trxReference,
                cardToken = cardToken,
                message = message
            )
        )
    }

    /** Persist reversible-sale history so reversals still work after app restart. */
    private fun persistRecentSuccessfulSales() {
        try {
            val arr = JSONArray()
            for (sale in recentSuccessfulSales.values) {
                arr.put(
                    JSONObject().apply {
                        put("transactionId", sale.transactionId)
                        put("amount", sale.amount)
                        put("requesterTransRefNum", sale.requesterTransRefNum ?: "")
                        put("provider", sale.provider ?: "")
                        put("approvalCode", sale.approvalCode ?: "")
                        put("receiptNumber", sale.receiptNumber ?: "")
                        put("trxReferenceNumber", sale.trxReferenceNumber ?: "")
                        put("currency", sale.currency ?: "")
                    }
                )
            }
            recoveryPrefs.edit()
                .putString(PREF_RECENT_REVERSIBLE_SALES, arr.toString())
                .apply()
            audit("Persisted ${recentSuccessfulSales.size} reversible sale(s) to disk")
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to persist reversible sales", e)
        }
    }

    /** Restore reversible-sale history written by [persistRecentSuccessfulSales]. */
    private fun loadRecentSuccessfulSales() {
        val raw = recoveryPrefs.getString(PREF_RECENT_REVERSIBLE_SALES, null) ?: return
        try {
            val arr = JSONArray(raw)
            recentSuccessfulSales.clear()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val txId = obj.optString("transactionId")
                if (txId.isBlank()) continue
                val sale = SuccessfulSaleTransaction(
                    transactionId = txId,
                    amount = obj.optInt("amount"),
                    requesterTransRefNum = obj.optString("requesterTransRefNum").takeIf { it.isNotBlank() },
                    provider = obj.optString("provider").takeIf { it.isNotBlank() },
                    approvalCode = obj.optString("approvalCode").takeIf { it.isNotBlank() },
                    receiptNumber = obj.optString("receiptNumber").takeIf { it.isNotBlank() },
                    trxReferenceNumber = obj.optString("trxReferenceNumber").takeIf { it.isNotBlank() },
                    currency = obj.optString("currency").takeIf { it.isNotBlank() }
                )
                recentSuccessfulSales[sale.transactionId] = sale
            }
            while (recentSuccessfulSales.size > maxRecentSales) {
                recentSuccessfulSales.remove(recentSuccessfulSales.keys.first())
            }
            lastSuccessfulSale = recentSuccessfulSales.values.lastOrNull()
            AppLog.d(
                TAG,
                "Loaded ${recentSuccessfulSales.size} reversible sale(s) from disk " +
                    "(latest=${lastSuccessfulSale?.transactionId})"
            )
            audit(
                "Loaded ${recentSuccessfulSales.size} reversible sale(s) from disk " +
                    "latest=${lastSuccessfulSale?.transactionId}"
            )
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to load reversible sales, clearing persisted history", e)
            recoveryPrefs.edit().remove(PREF_RECENT_REVERSIBLE_SALES).apply()
        }
    }

    /**
     * Resolve which stored sale a refund/reversal request targets.
     *
     * Server identifiers take precedence so we void the *specific* transaction
     * named by the server, not whatever happened to be the most recent sale
     * (critical when a newer customer paid between a failed void and its retry).
     *
     * When the server provides identifiers but we have no matching record, the
     * returned sale is null and [ReversalTarget.identifiersProvided] is true so
     * callers refuse to fall back to the latest sale.
     */
    private fun resolveSaleForReversal(message: SocketMessage): ReversalTarget {
        val serverTxId = message.data?.originalTransactionId?.takeIf { it.isNotBlank() }
        val serverRef = message.data?.originalRequesterTransRefNum?.takeIf { it.isNotBlank() }

        // No identifiers => legacy auto-refund (e.g. ticket print failed right
        // after the sale). The most recent sale is the correct target.
        if (serverTxId == null && serverRef == null) {
            return ReversalTarget(lastSuccessfulSale, identifiersProvided = false)
        }

        fun matches(sale: SuccessfulSaleTransaction): Boolean =
            (serverTxId != null && sale.transactionId == serverTxId) ||
                (serverRef != null &&
                    (sale.requesterTransRefNum == serverRef || sale.transactionId == serverRef))

        // Newest matching entry wins (values() is oldest-first).
        val match = recentSuccessfulSales.values.lastOrNull { matches(it) }
            ?: lastSuccessfulSale?.takeIf { matches(it) }

        return ReversalTarget(match, identifiersProvided = true)
    }

    private val moshi by lazy {
        Moshi.Builder()
            .add(LenientIntAdapter.FACTORY)
            .add(LenientLongAdapter.FACTORY)
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    private val messageAdapter: JsonAdapter<SocketMessage> by lazy {
        moshi.adapter(SocketMessage::class.java)
    }

    private data class PendingRecoveryTransaction(
        val transactionId: String,
        val amount: Int,
        val originalTrxUniqueId: String,
        val provider: String
    )

    private data class PendingTicketPrintTransaction(
        val transactionId: String,
        val amount: Int,
        val originalRequesterRef: String,
        val provider: String
    )

    private var disconnectRecoveryJob: Job? = null
    private var isRecoveryInProgress: Boolean = false

    /** Transaction ID of the last sale paid with DCC; its receipt question is auto-answered YES. */
    private var dccReceiptTransactionId: String? = null

    private fun audit(message: String) {
        fileLogger.i(TAG, message)
    }

    init {
        AppLog.d(TAG, "Initializing PaymentViewModel")
        loadRecentSuccessfulSales()

        // Setup timeout manager with callback for timeout events
        timeoutManager.setup {
            AppLog.d(TAG, "Timeout occurred, handling in ViewModel")
            handleTimeout()
        }

        // Monitor timeout state to show screensaver directly
        viewModelScope.launch {
            timeoutManager.timeoutOccurred.collect { occurred ->
                AppLog.d(TAG, "Timeout state changed: $occurred")
                if (occurred && _isOnAmountScreen.value) {
                    AppLog.d(TAG, "Timeout occurred while on amount screen, showing screensaver")
                    // Pause timers when showing screensaver
                    timeoutManager.pauseTimersForScreensaver()

                    // Show screensaver
                    _isScreensaverVisible.value = true
                }
            }
        }

        // Monitor screensaver visibility
        viewModelScope.launch {
            _isScreensaverVisible.collect { visible ->
                AppLog.d(TAG, "Screensaver visibility changed: $visible")
                if (visible) {
                    // Make sure timers are paused when screensaver is visible
                    timeoutManager.pauseTimersForScreensaver()
                }
            }
        }

        // Monitor socket messages
        viewModelScope.launch {
            socketManager.messageReceived.collect { message ->
                processSocketMessage(message)
            }
        }

        // Monitor socket connection state
        viewModelScope.launch {
            socketManager.connectionState.collect { state ->
                AppLog.d(TAG, "Socket connection state changed: $state")
                when (state) {
                    SocketManager.ConnectionState.DISCONNECTED -> {
                        AppLog.d(TAG, "Socket disconnected, updating screen state")
                        audit("Socket DISCONNECTED while state=${_screenState.value::class.simpleName}")
                        // Immediately set to ConnectionError, don't go through Loading
                        _screenState.value = buildConnectionError(
                            preferred = ConnectionIssue.PAYMENT_SERVER_DISCONNECTED
                        )
                        scheduleDisconnectRecoveryIfNeeded()
                    }
                    SocketManager.ConnectionState.CONNECTING -> {
                        // Only set Loading if we're not already in ConnectionError state
                        if (_screenState.value !is PaymentScreenState.ConnectionError) {
                            AppLog.d(TAG, "Socket connecting, updating screen state")
                            _screenState.value = PaymentScreenState.Loading
                        }
                    }
                    SocketManager.ConnectionState.CONNECTED -> {
                        audit("Socket CONNECTED; cancel disconnect-recovery timer and flush pending critical messages")
                        disconnectRecoveryJob?.cancel()
                        disconnectRecoveryJob = null
                        flushPendingCriticalMessages()
                    }
                }
            }
        }

        viewModelScope.launch {
            delay(1500)
            audit("Startup recovery check begin")
            recoverPendingTransactionIfAny()
            recoverPendingTicketNotPrintedIfAny()
            audit("Startup recovery check complete")
        }
    }

    private enum class ConnectionIssue {
        NO_INTERNET,
        PAYMENT_SERVER_NOT_CONFIGURED,
        PAYMENT_SERVER_UNREACHABLE,
        PAYMENT_SERVER_DISCONNECTED,
        UNKNOWN
    }

    /**
     * Build a specific connection-error screen: internet vs payment server vs portal status.
     */
    private fun buildConnectionError(
        preferred: ConnectionIssue = ConnectionIssue.UNKNOWN
    ): PaymentScreenState.ConnectionError {
        val app = getApplication<Application>()
        val online = NetworkConnectivity.hasInternet(app)
        val transport = NetworkConnectivity.transportLabel(app)
        val paymentUrl = serverUrl.ifBlank { configManager.getServerUrl() }
        val paymentConnected = socketManager.isConnected()
        val paymentFailure = socketManager.lastFailureReason
        val portalConfigured = configManager.getPortalUrl().isNotBlank()
        val portalConnected = try {
            PortalWebSocketRepository.getInstance(app).isConnected()
        } catch (_: Exception) {
            false
        }
        val portalUrl = configManager.getPortalWebSocketUrl().ifBlank { "(not set)" }

        val portalSecondary = when {
            !portalConfigured ->
                "Ask portal: not configured (set Portal URL in Server Config)."
            portalConnected ->
                "Ask portal hub: connected ($portalUrl)."
            !online ->
                "Ask portal hub: unreachable while device is offline."
            else ->
                "Ask portal hub: not connected to $portalUrl."
        }

        // Prefer the most specific root cause.
        val issue = when {
            !online -> ConnectionIssue.NO_INTERNET
            paymentUrl.isBlank() -> ConnectionIssue.PAYMENT_SERVER_NOT_CONFIGURED
            preferred == ConnectionIssue.PAYMENT_SERVER_NOT_CONFIGURED -> preferred
            preferred == ConnectionIssue.PAYMENT_SERVER_UNREACHABLE -> preferred
            preferred == ConnectionIssue.PAYMENT_SERVER_DISCONNECTED && !paymentConnected -> preferred
            !paymentConnected -> ConnectionIssue.PAYMENT_SERVER_DISCONNECTED
            else -> preferred
        }

        return when (issue) {
            ConnectionIssue.NO_INTERNET -> PaymentScreenState.ConnectionError(
                title = "No Internet Connection",
                detail = "This device is offline. Check Wi‑Fi or Ethernet, then wait for the app to reconnect.",
                secondaryDetail = portalSecondary
            )
            ConnectionIssue.PAYMENT_SERVER_NOT_CONFIGURED -> PaymentScreenState.ConnectionError(
                title = "Payment Server Not Configured",
                detail = "No payment server address is set. Open Server Config and enter the payment server IP and port.",
                secondaryDetail = portalSecondary
            )
            ConnectionIssue.PAYMENT_SERVER_UNREACHABLE -> PaymentScreenState.ConnectionError(
                title = "Payment Server Unreachable",
                detail = buildString {
                    append("Could not reach the payment server at $paymentUrl")
                    if (!paymentFailure.isNullOrBlank()) append(" ($paymentFailure)")
                    append(". Device is on $transport — confirm the server is running and reachable on this network.")
                },
                secondaryDetail = portalSecondary
            )
            ConnectionIssue.PAYMENT_SERVER_DISCONNECTED -> PaymentScreenState.ConnectionError(
                title = "Payment Server Disconnected",
                detail = buildString {
                    append("Connection to the payment server was lost")
                    if (!paymentFailure.isNullOrBlank()) append(" ($paymentFailure)")
                    append(". Target: $paymentUrl.")
                },
                secondaryDetail = portalSecondary
            )
            ConnectionIssue.UNKNOWN -> PaymentScreenState.ConnectionError(
                title = "Connection Error",
                detail = "Unable to communicate with the payment backend. Check network and server settings.",
                secondaryDetail = portalSecondary
            )
        }
    }

    fun connectToBackend(url: String) {
        AppLog.d(TAG, "Connecting to backend: $url")
        // Store the URL for later use
        this.serverUrl = url
        socketManager.connect(url)

        // Set a timeout to ensure we get an initial screen
        viewModelScope.launch {
            delay(5000) // Wait 5 seconds
            if (_screenState.value is PaymentScreenState.Loading) {
                AppLog.d(TAG, "Still in loading state after 5 seconds, requesting initial screen")
                requestInitialScreen()
            }
        }
    }

    /**
     * Records a user interaction to reset the timeout timer.
     */
    fun recordUserInteraction() {
        timeoutManager.recordUserInteraction()

        // If the screensaver is visible, hide it and restore the previous state
        if (_isScreensaverVisible.value) {
            AppLog.d(TAG, "User interacted while screensaver was visible, hiding screensaver")
            _isScreensaverVisible.value = false

            // If we have a saved state, restore it
            lastActiveState?.let {
                AppLog.d(TAG, "Restoring last active state: ${it::class.simpleName}")
                _screenState.value = it
            }
        }
    }

    /**
     * Handles a timeout event by canceling any active transaction.
     */
    private fun handleTimeout() {
        AppLog.d(TAG, "Handling timeout event")
        // Only trigger screensaver if on the amount selection screen
        if (_isOnAmountScreen.value) {
            // Save current state before changing to screensaver
            if (_screenState.value !is PaymentScreenState.DeviceError &&
                _screenState.value !is PaymentScreenState.ConnectionError) {
                lastActiveState = _screenState.value
                AppLog.d(TAG, "Saved last active state: ${lastActiveState?.javaClass?.simpleName}")
            }

            // Cancel any active transaction
            cancelPayment(isTimeout = true)
        } else {
            AppLog.d(TAG, "Not on amount screen, ignoring timeout")
            // Reset the timeout timer
            timeoutManager.recordUserInteraction()
        }
    }

    /**
     * Calculate the final amount including transaction fee.
     * Returns the original amount if device configuration is not available or fee is disabled (value is 0).
     */
    private suspend fun calculateFinalAmountWithFee(originalAmount: Int): Int {
        return try {
            val database = AppDatabase.getDatabase(getApplication())
            val deviceInfo = database.deviceInfoDao().getDeviceInfo().first()
            
            if (deviceInfo == null) {
                AppLog.w(TAG, "Device configuration not found, using original amount without fee")
                return originalAmount
            }
            
            val feeType = deviceInfo.transactionFeeType
            val feeValue = deviceInfo.transactionFeeValue
            val originalAmountDouble = originalAmount.toDouble()
            
            // Only add fee if feeValue is greater than 0
            if (feeValue <= 0) {
                AppLog.d(TAG, "Fee value is 0 or negative, using original amount: $originalAmount")
                return originalAmount
            }
            
            val finalAmount = when (feeType.uppercase()) {
                "FIXED" -> {
                    originalAmountDouble + feeValue
                }
                "PERCENTAGE" -> {
                    originalAmountDouble + (originalAmountDouble * feeValue / 100.0)
                }
                else -> {
                    AppLog.w(TAG, "Unknown fee type: $feeType, using original amount")
                    originalAmountDouble
                }
            }
            
            val roundedAmount = finalAmount.toInt()
            AppLog.d(TAG, "Fee calculation: original=$originalAmount, feeType=$feeType, feeValue=$feeValue, final=$roundedAmount")
            roundedAmount
        } catch (e: Exception) {
            AppLog.e(TAG, "Error calculating fee, using original amount", e)
            originalAmount
        }
    }
    
    fun selectAmount(amount: Int) {
        AppLog.d(TAG, "Amount selected: $amount")
        recordUserInteraction()

        // Special code -2 is used to return to amount selection from limit error
        if (amount == -2) {
            val transactionId = UUID.randomUUID().toString()
            currentTransactionId = transactionId
            AppLog.d(TAG, "Reset requested (code -2), new transaction ID: $transactionId")
            
            // Allow navigation away from LIMIT_ERROR screen after user-initiated reset
            allowNavigationFromLimitError = true

            val message = SocketMessage(
                messageType = "USER_ACTION",
                screen = "RESET",
                data = null,
                transactionId = transactionId,
                timestamp = System.currentTimeMillis()
            )

            sendMessage(message)
            return
        }

        // Special code -1 indicates "Other" - show keypad screen for custom amount entry
        if (amount == -1) {
            AppLog.d(TAG, "Other option selected - showing keypad screen")
            viewModelScope.launch {
                val database = AppDatabase.getDatabase(getApplication())
                val deviceInfo = database.deviceInfoDao().getDeviceInfo().first()
                val currencyCode = deviceInfo?.currency ?: "GBP"
                // Convert currency code to symbol for display
                val currencySymbol = when (currencyCode.uppercase()) {
                    "GBP" -> "£"
                    "USD" -> "$"
                    "EUR" -> "€"
                    else -> currencyCode
                }
                val minAmount = deviceInfo?.minTransactionLimit?.toInt() ?: 10
                val maxAmount = deviceInfo?.maxTransactionLimit?.toInt() ?: 300
                
                // Show keypad screen locally (client-controlled screen)
                _screenState.value = PaymentScreenState.KeypadEntry(
                    currency = currencySymbol,
                    minAmount = minAmount,
                    maxAmount = maxAmount
                )
                _isOnAmountScreen.value = false
            }
            return
        }

        val transactionId = currentTransactionId ?: UUID.randomUUID().toString().also {
            currentTransactionId = it
            AppLog.d(TAG, "Generated new transaction ID: $it")
        }

        // Validate amount against min/max transaction limits locally
        viewModelScope.launch {
            val database = AppDatabase.getDatabase(getApplication())
            val deviceInfo = database.deviceInfoDao().getDeviceInfo().first()
            
            // Check min/max transaction limits locally
            val minAmount = deviceInfo?.minTransactionLimit?.toInt() ?: 10
            val maxAmount = deviceInfo?.maxTransactionLimit?.toInt() ?: 300
            
            // Get currency symbol for error messages
            val currencyCode = deviceInfo?.currency ?: "GBP"
            val currencySymbol = when (currencyCode.uppercase()) {
                "GBP" -> "£"
                "USD" -> "$"
                "EUR" -> "€"
                else -> currencyCode
            }
            
            if (amount < minAmount) {
                AppLog.w(TAG, "Amount $amount is below minimum limit $minAmount")
                _screenState.value = PaymentScreenState.LimitError(
                    errorMessage = "Minimum transaction limit is $currencySymbol$minAmount"
                )
                allowNavigationFromLimitError = false // Reset flag when showing LIMIT_ERROR
                return@launch
            }
            
            if (amount > maxAmount) {
                AppLog.w(TAG, "Amount $amount exceeds maximum limit $maxAmount")
                _screenState.value = PaymentScreenState.LimitError(
                    errorMessage = "Maximum transaction limit is $currencySymbol$maxAmount"
                )
                allowNavigationFromLimitError = false // Reset flag when showing LIMIT_ERROR
                return@launch
            }
            
            // For Mock payment provider, check if amount is 101 (daily limit trigger)
            val paymentProvider = deviceInfo?.paymentProvider?.lowercase() ?: "nnsmart"
            if (paymentProvider == "mock" && amount == 101) {
                AppLog.d(TAG, "Mock payment: Amount 101 triggers daily limit exceeded")
                _screenState.value = PaymentScreenState.LimitError(
                    errorMessage = "Daily spending limit exceeded"
                )
                allowNavigationFromLimitError = false // Reset flag when showing LIMIT_ERROR
                return@launch
            }

            // Calculate final amount with fee and store both original and final amounts
            val finalAmount = calculateFinalAmountWithFee(amount)
            
            // Store the original amount for display, but use final amount for payment
            if (amount > 0) {
                AppLog.d(TAG, "Storing original amount: $amount, final amount with fee: $finalAmount")
                currentAmount = finalAmount // Store final amount for payment processing (includes fee)
            }

            // Check if YASPA is enabled to determine flow
            val yaspaEnabled = deviceInfo?.yaspaEnabled ?: true
            
            if (!yaspaEnabled) {
                // YASPA disabled: Show timeout screen locally, then proceed directly to payment
                AppLog.d(TAG, "YASPA disabled - showing timeout screen then proceeding directly to payment")
                
                // Set flag to indicate we're handling payment locally
                isHandlingPaymentLocally = true
                
                // Send amount selection message to server
                val message = SocketMessage(
                    messageType = "USER_ACTION",
                    screen = "AMOUNT_SELECT",
                    data = MessageData(
                        selectedAmount = finalAmount, // Send final amount including fee
                        selectionMethod = if (amount in listOf(
                                20,
                                40,
                                60,
                                80,
                                100
                            )
                        ) "PRESET_BUTTON" else "CUSTOM"
                    ),
                    transactionId = transactionId,
                    timestamp = System.currentTimeMillis()
                )
                sendMessage(message)
                
                // Show timeout screen locally
                _screenState.value = PaymentScreenState.Timeout
                _isOnAmountScreen.value = false
                
                // After showing timeout screen briefly, proceed directly to payment
                delay(3000) // Show timeout screen for 3 seconds
                
                // Proceed directly to payment (as if DEBIT_CARD was selected)
                AppLog.d(TAG, "YASPA disabled - proceeding directly to payment after timeout screen")
                processLocalPayment("DEBIT_CARD", transactionId)
            } else {
                // YASPA enabled: Normal flow - send message and wait for server response (PAYMENT_METHOD screen)
                val message = SocketMessage(
                    messageType = "USER_ACTION",
                    screen = "AMOUNT_SELECT",
                    data = MessageData(
                        selectedAmount = finalAmount, // Send final amount including fee
                        selectionMethod = if (amount in listOf(
                                20,
                                40,
                                60,
                                80,
                                100
                            )
                        ) "PRESET_BUTTON" else "CUSTOM"
                    ),
                    transactionId = transactionId,
                    timestamp = System.currentTimeMillis()
                )
                sendMessage(message)
            }
        }
    }

    fun selectPaymentMethod(method: String) {
        AppLog.d(TAG, "Payment method selected: $method")
        recordUserInteraction()

        val transactionId = currentTransactionId
        if (transactionId == null) {
            AppLog.e(TAG, "Cannot select payment method: No active transaction ID")
            return
        }

        // For DEBIT_CARD and PAY_BY_BANK, handle payment locally
        if (method == "DEBIT_CARD" || method == "PAY_BY_BANK") {
            AppLog.d(TAG, "Processing local payment for method: $method")
            processLocalPayment(method, transactionId)
        } else {
            // For other payment methods (e.g., QR_CODE), send to server
            val message = SocketMessage(
                messageType = "USER_ACTION",
                screen = "PAYMENT_METHOD",
                data = MessageData(selectionMethod = method),
                transactionId = transactionId,
                timestamp = System.currentTimeMillis()
            )
            sendMessage(message)
        }
    }
    
    /**
     * Process payment locally for DEBIT_CARD and PAY_BY_BANK methods.
     * This follows the flow described in the documentation:
     * 1. Show PROCESSING screen
     * 2. Perform CardCheckEmv locally
     * 3. Send card token to server for limit validation
     * 4. Wait for LIMIT_CHECK_RESULT
     * 5. If approved, perform Sale transaction locally
     * 6. Show SUCCESS or FAILED screen
     * 7. Send PAYMENT_RESULT to server
     */
    private fun processLocalPayment(method: String, transactionId: String) {
        if (isProcessingPayment) {
            AppLog.w(TAG, "Payment already in progress, ignoring duplicate request")
            return
        }
        if (isProcessingRedeem) {
            AppLog.w(TAG, "Ticket redemption in progress, ignoring payment request")
            return
        }
        
        isProcessingPayment = true
        isHandlingPaymentLocally = true // Mark that we're handling payment locally

        // Clear any stale per-provider state from a previous attempt.
        pendingCardCheckResult = null
        pendingNnsmartSale = null
        pendingNnsmartCardVerification = null
        pendingCcvSale = null
        pendingSwitchioSale = null
        pendingPlanetSale = null
        pendingSwitchioCardVerify = null

        // Step 1: Show PROCESSING screen automatically
        AppLog.d(TAG, "Showing PROCESSING screen for local payment")
        _screenState.value = PaymentScreenState.Processing
        _isOnAmountScreen.value = false
        
        viewModelScope.launch {
            try {
                // Get device configuration to determine payment provider
                val database = AppDatabase.getDatabase(getApplication())
                val deviceInfo = database.deviceInfoDao().getDeviceInfo().first()
                
                if (deviceInfo == null) {
                    AppLog.e(TAG, "Device configuration not found, cannot process payment")
                    _screenState.value = PaymentScreenState.TransactionFailed(
                        errorMessage = "Device configuration not found"
                    )
                    isProcessingPayment = false
                    isHandlingPaymentLocally = false // Reset flag on error
                    
                    // Auto-return to amount selection after 4 seconds
                    viewModelScope.launch {
                        delay(4000)
                        requestInitialScreen()
                    }
                    return@launch
                }
                
                val paymentProvider = deviceInfo.paymentProvider.lowercase()
                val amountFormatted = String.format("%.2f", currentAmount.toDouble())

                // Get currency symbol for display
                val currencyCode = deviceInfo.currency ?: "GBP"
                val currencySymbol = when (currencyCode.uppercase()) {
                    "GBP" -> "£"
                    "USD" -> "$"
                    "EUR" -> "€"
                    else -> currencyCode
                }

                if (CcvPaymentManager.isCcvProvider(paymentProvider)) {
                    // CCV cannot return a card identifier before capture, so the
                    // SALE is taken first; the limit is validated afterwards and
                    // the sale is REFUNDED if rejected.
                    AppLog.d(TAG, "CCV: using sale-first limit flow")
                    processCcvPayment(
                        transactionId = transactionId,
                        amountFormatted = amountFormatted,
                        currencyCode = currencyCode
                    )
                    return@launch
                }

                if (NNSmartPaymentManager.isNNSmartProvider(paymentProvider)) {
                    if (deviceInfo.nnsmartPostProcessingLimit) {
                        // Post-processing: capture the sale first, validate the
                        // limit afterwards, and reverse the sale if rejected.
                        AppLog.d(TAG, "NNSmart: using POST-processing limit flow (sale-first)")
                        processNnsmartPayment(
                            transactionId = transactionId,
                            amountFormatted = amountFormatted,
                            currencyCode = currencyCode
                        )
                    } else {
                        // Pre-processing: obtain PAR via Card Verification and
                        // validate the limit before any money is captured.
                        AppLog.d(TAG, "NNSmart: using PRE-processing limit flow (card verification)")
                        processNnsmartCardVerificationPayment(
                            transactionId = transactionId,
                            amountFormatted = amountFormatted,
                            currencyCode = currencyCode
                        )
                    }
                    return@launch
                }

                if (SwitchioPaymentManager.isSwitchioProvider(paymentProvider)) {
                    // Switchio/Monet+ is always post-processing (sale-first, reverse
                    // if the limit is rejected). Monet+ declines read_card for our
                    // terminals, so the pre-processing flow is deliberately not
                    // selectable here regardless of nnsmartPostProcessingLimit.
                    AppLog.d(TAG, "Switchio: using POST-processing limit flow (sale-first)")
                    processSwitchioPayment(
                        transactionId = transactionId,
                        amountFormatted = amountFormatted,
                        currencyCode = currencyCode
                    )
                    return@launch
                }

                // Planet/Integra post-processing: sale first, limit check on the
                // sale's card Token, Sale-Reversal if rejected.
                if (paymentProvider != "mock" && deviceInfo.planetPostProcessingLimit) {
                    AppLog.d(TAG, "Planet: using POST-processing limit flow (sale-first)")
                    processPlanetPostPayment(
                        transactionId = transactionId,
                        amountFormatted = amountFormatted,
                        paymentProvider = paymentProvider
                    )
                    return@launch
                }

                // For MOCK payment provider, show MockPaymentCard screen after Processing screen
                if (paymentProvider == "mock") {
                    AppLog.d(TAG, "Mock payment: Showing MockPaymentCard screen after Processing")
                    delay(2000) // Show Processing screen for 2 seconds
                    _screenState.value = PaymentScreenState.MockPaymentCard(
                        amount = currentAmount,
                        currency = currencySymbol
                    )
                    delay(3000) // Show MockPaymentCard screen for 3 seconds before proceeding
                }
                
                // Step 2: Perform CardCheckEmv locally with amount including fee
                AppLog.d(TAG, "Performing card check with provider: $paymentProvider, amount (including fee): $amountFormatted")
                val cardCheckResult = if (paymentProvider == "mock") {
                    MockPaymentManager.performCardCheck(transactionId, amountFormatted)
                } else {
                    PlanetPaymentManager.performCardCheck(
                        requesterRef = transactionId,
                        amountFormatted = amountFormatted // Amount includes transaction fee
                    )
                }
                
                pendingCardCheckResult = cardCheckResult
                
                if (!cardCheckResult.success) {
                    AppLog.e(TAG, "Card check failed: ${cardCheckResult.message}")
                    _screenState.value = PaymentScreenState.TransactionFailed(
                        errorMessage = cardCheckResult.message ?: "Card check failed"
                    )
                    isProcessingPayment = false
                    isHandlingPaymentLocally = false // Reset flag on failure
                    
                    // Auto-return to amount selection after 4 seconds
                    viewModelScope.launch {
                        delay(4000)
                        requestInitialScreen()
                    }
                    return@launch
                }
                
                // Step 3: Send card token to server for daily limit validation (only for Real Planet payment)
                // For Mock payment, skip server limit check as it's handled locally
                if (paymentProvider != "mock") {
                    AppLog.d(TAG, "Sending card check result to server for daily limit validation: token=${cardCheckResult.token}")
                    
                    // Create a custom message with cardToken for daily limit check
                    val cardCheckJson = """
                        {
                            "messageType": "CARD_CHECK_RESULT",
                            "screen": "PROCESSING",
                            "data": {
                                "cardToken": "${cardCheckResult.token}",
                                "selectedAmount": $currentAmount
                            },
                            "transactionId": "$transactionId",
                            "timestamp": ${System.currentTimeMillis()}
                        }
                    """.trimIndent()
                    
                    socketManager.sendMessage(cardCheckJson)
                    
                    // Step 4: Wait for LIMIT_CHECK_RESULT from server
                    // This will be handled in processSocketMessage when LIMIT_CHECK_RESULT is received
                } else {
                    // For Mock payment, skip server limit check and proceed directly to sale
                    AppLog.d(TAG, "Mock payment: Skipping server limit check, proceeding directly to sale")
                    continuePaymentAfterLimitCheck(true, transactionId, "")
                }
                
            } catch (e: Exception) {
                AppLog.e(TAG, "Error processing local payment", e)
                _screenState.value = PaymentScreenState.TransactionFailed(
                    errorMessage = "Payment processing error: ${e.message}"
                )
                isProcessingPayment = false
                isHandlingPaymentLocally = false // Reset flag on error
                
                // Auto-return to amount selection after 4 seconds
                viewModelScope.launch {
                    delay(4000)
                    requestInitialScreen()
                }
            }
        }
    }
    
    /**
     * CCV payment flow (sale-first).
     *
     * CCV exposes the card hash (panHash) only AFTER the sale completes, so we
     * capture the sale, keep the result (for a possible refund and for the
     * refund-addressing params), then send the card hash to the backend for
     * daily-limit validation. If the backend rejects we REFUND the sale; if it
     * approves we carry on. This mirrors [processNnsmartPayment] but reverses via
     * a CCV REFUND instead of an NNSmart Cancellation.
     */
    private suspend fun processCcvPayment(
        transactionId: String,
        amountFormatted: String,
        currencyCode: String
    ) {
        try {
            AppLog.d(TAG, "CCV: performing up-front sale amount=$amountFormatted ref=$transactionId")
            val saleResult = CcvPaymentManager.performSale(
                context = getApplication(),
                amountFormatted = amountFormatted,
                requesterRef = transactionId,
                currencyAlphaCode = currencyCode
            )

            if (!saleResult.success) {
                AppLog.w(TAG, "CCV sale failed: code=${saleResult.resultCode} msg=${saleResult.message}")
                _screenState.value = PaymentScreenState.TransactionFailed(
                    errorMessage = saleResult.message ?: "Payment failed"
                )
                isProcessingPayment = false
                isHandlingPaymentLocally = false

                val paymentResultJson = buildPaymentResultJson(
                    success = false,
                    transactionId = transactionId,
                    resultCode = saleResult.resultCode,
                    message = saleResult.message
                )
                AppLog.d(TAG, "CCV: sending PAYMENT_RESULT (sale-failed) to backend")
                socketManager.sendMessage(paymentResultJson)

                viewModelScope.launch {
                    delay(4000)
                    requestInitialScreen()
                }
                return
            }

            // Sale succeeded on the terminal. Keep the result so we can refund it
            // if the backend rejects the limit check, and so the refund-addressing
            // params are available for a later portal REFUND_REQUEST.
            pendingCcvSale = saleResult

            // CCV's panHash is the stable per-card identifier (≈ NNSmart PAR);
            // fall back to the card token. Without either, release builds refund the
            // sale and debug builds use a dev mock value.
            val rawHash = saleResult.panHash?.trim().orEmpty()
            val tokenForLimitCheck = cardIdForLimitCheck(rawHash.ifEmpty { saleResult.cardToken }, "CCV")
                ?: return rejectPaymentWithoutCardId(transactionId)

            AppLog.d(
                TAG,
                "CCV: sale approved. panHash='$rawHash' approvalCode=${saleResult.approvalCode} " +
                    "receiptNumber=${saleResult.receiptNumber} trxRef=${saleResult.trxReferenceNumber} tokenUsed=$tokenForLimitCheck"
            )

            val cardCheckJson = """
                {
                    "messageType": "CARD_CHECK_RESULT",
                    "screen": "PROCESSING",
                    "data": {
                        "cardToken": "$tokenForLimitCheck",
                        "selectedAmount": $currentAmount
                    },
                    "transactionId": "$transactionId",
                    "timestamp": ${System.currentTimeMillis()}
                }
            """.trimIndent()
            AppLog.d(TAG, "CCV: sending CARD_CHECK_RESULT to backend for limit validation")
            val sent = socketManager.sendMessage(cardCheckJson)
            AppLog.d(TAG, "CCV: CARD_CHECK_RESULT send result: $sent")

            // Stay on Processing until LIMIT_CHECK_RESULT arrives; the rest of the
            // flow is handled in continueCcvPaymentAfterLimitCheck.
        } catch (e: Exception) {
            AppLog.e(TAG, "CCV: error during sale", e)
            _screenState.value = PaymentScreenState.TransactionFailed(
                errorMessage = "Payment error: ${e.message}"
            )
            isProcessingPayment = false
            isHandlingPaymentLocally = false
            viewModelScope.launch {
                delay(4000)
                requestInitialScreen()
            }
        }
    }

    /**
     * NNSmart payment flow.
     *
     * NNSmart cannot return a card identifier before the sale is taken, so we
     * charge first, then send `par` / `cardRefId` to the backend for daily
     * limit validation. If the backend rejects, we reverse via Cancellation.
     */
    /**
     * Planet/Integra post-processing (sale-first) flow:
     *   1. Sale-Terminal (DCC may be offered here as usual).
     *   2. Send the sale's card Token to the backend in CARD_CHECK_RESULT.
     *   3. LIMIT_CHECK_RESULT approved → report success; rejected → Sale-Reversal
     *      (see continuePlanetPaymentAfterLimitCheck).
     */
    private suspend fun processPlanetPostPayment(
        transactionId: String,
        amountFormatted: String,
        paymentProvider: String
    ) {
        try {
            AppLog.d(TAG, "Planet: performing up-front sale amount=$amountFormatted ref=$transactionId")
            val saleResult = PlanetPaymentManager.performSale(
                amountFormatted = amountFormatted,
                requesterRef = transactionId
            )

            if (!saleResult.success) {
                AppLog.w(TAG, "Planet sale failed: code=${saleResult.resultCode} msg=${saleResult.message}")
                reportPortalTransaction(
                    transactionId = transactionId,
                    status = "Failed",
                    amountMinor = currentAmount,
                    provider = paymentProvider,
                    message = saleResult.message ?: "Payment failed"
                )
                _screenState.value = PaymentScreenState.TransactionFailed(
                    errorMessage = saleResult.message ?: "Payment failed"
                )
                isProcessingPayment = false
                isHandlingPaymentLocally = false
                socketManager.sendMessage(buildPlanetPaymentResultJson(saleResult, transactionId))
                viewModelScope.launch {
                    delay(4000)
                    requestInitialScreen()
                }
                return
            }

            // Money is captured: keep the sale so a rejection (or an app restart
            // before the limit result) can reverse it.
            pendingPlanetSale = saleResult
            savePendingRecoveryTransaction(
                transactionId = transactionId,
                amount = currentAmount,
                originalTrxUniqueId = saleResult.requesterTransRefNum ?: transactionId,
                provider = paymentProvider
            )

            // The Sale response carries the same card Token as CardCheckEmv, so
            // the backend's per-card limit keys match the pre-processing flow.
            val tokenForLimitCheck = cardIdForLimitCheck(saleResult.rawOptions["Token"], "Planet")
                ?: return rejectPaymentWithoutCardId(transactionId)

            AppLog.d(TAG, "Planet: sale approved, sending CARD_CHECK_RESULT token=$tokenForLimitCheck")
            val cardCheckJson = JSONObject().apply {
                put("messageType", "CARD_CHECK_RESULT")
                put("screen", "PROCESSING")
                put("data", JSONObject().apply {
                    put("cardToken", tokenForLimitCheck)
                    put("selectedAmount", currentAmount)
                })
                put("transactionId", transactionId)
                put("timestamp", System.currentTimeMillis())
            }.toString()
            socketManager.sendMessage(cardCheckJson)

            // Stay on Processing until LIMIT_CHECK_RESULT arrives.
        } catch (e: Exception) {
            AppLog.e(TAG, "Planet: error during sale-first payment", e)
            _screenState.value = PaymentScreenState.TransactionFailed(
                errorMessage = "Payment error: ${e.message}"
            )
            isProcessingPayment = false
            isHandlingPaymentLocally = false
            viewModelScope.launch {
                delay(4000)
                requestInitialScreen()
            }
        }
    }

    private fun continuePlanetPaymentAfterLimitCheck(
        approved: Boolean,
        transactionId: String,
        errorMessage: String,
        saleResult: app.sst.pinto.payment.PlanetPaymentResult
    ) {
        val originalRef = saleResult.requesterTransRefNum ?: transactionId

        if (approved) {
            AppLog.d(TAG, "Planet: limit approved for sale-first payment tx=$transactionId")
            rememberDccSale(saleResult, transactionId)
            recordSuccessfulSale(
                SuccessfulSaleTransaction(
                    transactionId = transactionId,
                    amount = currentAmount,
                    requesterTransRefNum = originalRef
                )
            )
            savePendingTicketPrintTransaction(
                transactionId = transactionId,
                amount = currentAmount,
                originalRequesterRef = originalRef,
                provider = "integra"
            )
            socketManager.sendMessage(buildPlanetPaymentResultJson(saleResult, transactionId))
            _screenState.value = PaymentScreenState.TransactionSuccess(showReceipt = true)
            pendingPlanetSale = null
            clearPendingRecoveryTransaction()
            isProcessingPayment = false
            isHandlingPaymentLocally = false
            return
        }

        AppLog.d(TAG, "Planet: limit rejected, reversing sale tx=$transactionId")
        _screenState.value = PaymentScreenState.LimitError(errorMessage = errorMessage)
        allowNavigationFromLimitError = false
        viewModelScope.launch {
            delay(1200)
            _screenState.value = PaymentScreenState.ReversingTransaction(
                message = "Limit exceeded. Reversing card transaction..."
            )

            val reversalRef = "REVERSAL_$transactionId"
            val reversalOk = try {
                PlanetPaymentManager.performSaleReversal(
                    amountFormatted = String.format("%.2f", currentAmount.toDouble()),
                    requesterRef = reversalRef,
                    originalRequesterRef = originalRef
                ).success
            } catch (e: Exception) {
                AppLog.e(TAG, "Planet: error reversing sale", e)
                false
            }

            val reversalResultJson = buildReversalResultJson(
                success = reversalOk,
                transactionId = transactionId,
                resultCode = if (reversalOk) "LIMIT_REVERSED" else "LIMIT_REVERSAL_FAILED",
                message = if (reversalOk) "Limit exceeded - sale reversed" else "Limit exceeded - REVERSAL FAILED",
                requesterTransRefNum = reversalRef,
                originalRequesterTransRefNum = originalRef,
                originalTransactionId = transactionId,
                reversalAmount = currentAmount
            )
            socketManager.sendMessage(reversalResultJson)

            if (reversalOk) {
                _screenState.value = PaymentScreenState.ReversalSuccess(
                    message = "Limit exceeded. Card transaction reversed successfully."
                )
                delay(2500)
            } else {
                // Needs operator attention: the customer was charged.
                _screenState.value = PaymentScreenState.TransactionFailed(
                    errorMessage = "Please contact staff - reversal failed"
                )
                delay(6000)
            }

            pendingPlanetSale = null
            clearPendingRecoveryTransaction()
            isProcessingPayment = false
            isHandlingPaymentLocally = false
            requestInitialScreen()
        }
    }

    /** Remembers a DCC sale so its receipt question is answered YES (Planet rule). */
    private fun rememberDccSale(saleResult: app.sst.pinto.payment.PlanetPaymentResult, transactionId: String) {
        val dcc = if (saleResult.success) saleResult.dcc else null
        dccReceiptTransactionId = if (dcc != null) transactionId else null
        if (dcc != null) {
            audit("DCC sale tx=$transactionId ${dcc.localAmount} ${dcc.localCurrency} -> ${dcc.dccAmount} ${dcc.dccCurrency}")
        }
    }

    /**
     * PAYMENT_RESULT for a Planet sale. Built with JSONObject because the Integra
     * receipt text (PrintData1/2) is multi-line and must be escaped properly.
     */
    private fun buildPlanetPaymentResultJson(
        saleResult: app.sst.pinto.payment.PlanetPaymentResult,
        transactionId: String
    ): String {
        val dcc = if (saleResult.success) saleResult.dcc else null
        val paymentDetails = JSONObject().apply {
            put("Result", saleResult.resultCode ?: "")
            put("BankResultCode", saleResult.bankResultCode ?: "")
            put("Message", saleResult.message ?: "")
            put("RequesterTransRefNum", transactionId)
            if (saleResult.success) {
                saleResult.merchantReceipt?.let { put("PrintData1", it) }
                saleResult.cardholderReceipt?.let { put("PrintData2", it) }
                if (dcc != null) {
                    DccDetails.RESPONSE_KEYS.forEach { key ->
                        saleResult.rawOptions[key]?.let { put(key, it) }
                    }
                }
            }
        }
        return JSONObject().apply {
            put("messageType", "PAYMENT_RESULT")
            put("screen", if (saleResult.success) "SUCCESS" else "FAILED")
            put("data", JSONObject().apply {
                put("errorCode", saleResult.resultCode ?: JSONObject.NULL)
                put("errorMessage", saleResult.message ?: JSONObject.NULL)
                put("receiptRequired", dcc != null)
                put("paymentDetails", paymentDetails)
            })
            put("transactionId", transactionId)
            put("timestamp", System.currentTimeMillis())
        }.toString()
    }

    private suspend fun processNnsmartPayment(
        transactionId: String,
        amountFormatted: String,
        currencyCode: String
    ) {
        try {
            AppLog.d(TAG, "NNSmart: performing up-front sale amount=$amountFormatted ref=$transactionId")
            val saleResult = NNSmartPaymentManager.performSale(
                context = getApplication(),
                amountFormatted = amountFormatted,
                requesterRef = transactionId,
                currencyAlphaCode = currencyCode,
                showReceipts = false
            )

            if (!saleResult.success) {
                AppLog.w(TAG, "NNSmart sale failed: code=${saleResult.resultCode} msg=${saleResult.message}")
                _screenState.value = PaymentScreenState.TransactionFailed(
                    errorMessage = saleResult.message ?: "Payment failed"
                )
                isProcessingPayment = false
                isHandlingPaymentLocally = false

                // Still notify server that the attempt failed.
                val paymentResultJson = buildPaymentResultJson(
                    success = false,
                    transactionId = transactionId,
                    resultCode = saleResult.resultCode,
                    message = saleResult.message
                )
                AppLog.d(TAG, "NNSmart: sending PAYMENT_RESULT (sale-failed) to backend")
                AppLog.d(TAG, "NNSmart: PAYMENT_RESULT payload: $paymentResultJson")
                val sent = socketManager.sendMessage(paymentResultJson)
                AppLog.d(TAG, "NNSmart: PAYMENT_RESULT send result: $sent")

                viewModelScope.launch {
                    delay(4000)
                    requestInitialScreen()
                }
                return
            }

            // Sale succeeded on the terminal. Keep the result so we can
            // reverse it later if the backend rejects the limit check.
            pendingNnsmartSale = saleResult
            saleResult.originalTrxUniqueId?.let { trxId ->
                savePendingRecoveryTransaction(
                    transactionId = transactionId,
                    amount = currentAmount,
                    originalTrxUniqueId = trxId,
                    provider = "nnsmart"
                )
            }

            // NNSmart returns a PAR (Payment Account Reference) on the sale
            // response. We ship it to the backend in the same CARD_CHECK_RESULT
            // shape used for Integra, reusing the `cardToken` field.
            // On dev terminals the PAR is sometimes not populated; debug builds fall
            // back to a fixed mock value so the backend flow can still be tested.
            val rawPar = saleResult.par?.trim().orEmpty()
            val tokenForLimitCheck = cardIdForLimitCheck(rawPar, "NNSmart")
                ?: return rejectPaymentWithoutCardId(transactionId)

            AppLog.d(TAG, "NNSmart: sale approved. par='$rawPar' cardRefId=${saleResult.cardRefId} trxId=${saleResult.originalTrxUniqueId} tokenUsed=$tokenForLimitCheck")

            val cardCheckJson = """
                {
                    "messageType": "CARD_CHECK_RESULT",
                    "screen": "PROCESSING",
                    "data": {
                        "cardToken": "$tokenForLimitCheck",
                        "selectedAmount": $currentAmount
                    },
                    "transactionId": "$transactionId",
                    "timestamp": ${System.currentTimeMillis()}
                }
            """.trimIndent()
            AppLog.d(TAG, "NNSmart: sending CARD_CHECK_RESULT to backend for limit validation")
            AppLog.d(TAG, "NNSmart: CARD_CHECK_RESULT payload: $cardCheckJson")
            val sent = socketManager.sendMessage(cardCheckJson)
            AppLog.d(TAG, "NNSmart: CARD_CHECK_RESULT send result: $sent")

            // Stay on Processing until LIMIT_CHECK_RESULT arrives; the rest
            // of the flow is handled in continuePaymentAfterLimitCheck.
        } catch (e: Exception) {
            AppLog.e(TAG, "NNSmart: error during sale", e)
            _screenState.value = PaymentScreenState.TransactionFailed(
                errorMessage = "Payment error: ${e.message}"
            )
            isProcessingPayment = false
            isHandlingPaymentLocally = false
            viewModelScope.launch {
                delay(4000)
                requestInitialScreen()
            }
        }
    }

    /**
     * NNSmart Card Verification payment flow.
     *
     * Uses the Card Verification feature to obtain the PAR before capturing
     * payment. This avoids the need to reverse a sale if the limit is exceeded.
     *
     * Flow:
     *   1. Send Card Verification request → terminal reads card, returns PAR.
     *   2. Send PAR to backend for limit validation.
     *   3. If approved → confirm card verification (terminal captures payment).
     *   4. If rejected → cancel card verification (no money captured).
     */
    private suspend fun processNnsmartCardVerificationPayment(
        transactionId: String,
        amountFormatted: String,
        currencyCode: String
    ) {
        try {
            AppLog.d(TAG, "NNSmart CV: performing card verification amount=$amountFormatted ref=$transactionId")
            val cvResult = NNSmartPaymentManager.performCardVerification(
                context = getApplication(),
                amountFormatted = amountFormatted,
                requesterRef = transactionId,
                currencyAlphaCode = currencyCode,
                showReceipts = false
            )

            if (!cvResult.success) {
                AppLog.w(TAG, "NNSmart CV: card verification failed: ${cvResult.message}")
                _screenState.value = PaymentScreenState.TransactionFailed(
                    errorMessage = cvResult.message ?: "Card verification failed"
                )
                isProcessingPayment = false
                isHandlingPaymentLocally = false

                val paymentResultJson = buildPaymentResultJson(
                    success = false,
                    transactionId = transactionId,
                    resultCode = "CV_FAILED",
                    message = cvResult.message
                )
                AppLog.d(TAG, "NNSmart CV: sending PAYMENT_RESULT (cv-failed) to backend")
                socketManager.sendMessage(paymentResultJson)

                viewModelScope.launch {
                    delay(4000)
                    requestInitialScreen()
                }
                return
            }

            // Card verification succeeded — we have the PAR without capturing payment.
            pendingNnsmartCardVerification = cvResult

            val rawPar = cvResult.par?.trim().orEmpty()
            val tokenForLimitCheck = cardIdForLimitCheck(rawPar, "NNSmart CV")
                ?: return rejectPaymentWithoutCardId(transactionId)

            AppLog.d(TAG, "NNSmart CV: card verified. par='$rawPar' cvTransactionId=${cvResult.transactionId} tokenUsed=$tokenForLimitCheck")

            val cardCheckJson = """
                {
                    "messageType": "CARD_CHECK_RESULT",
                    "screen": "PROCESSING",
                    "data": {
                        "cardToken": "$tokenForLimitCheck",
                        "selectedAmount": $currentAmount
                    },
                    "transactionId": "$transactionId",
                    "timestamp": ${System.currentTimeMillis()}
                }
            """.trimIndent()
            AppLog.d(TAG, "NNSmart CV: sending CARD_CHECK_RESULT to backend for limit validation")
            val sent = socketManager.sendMessage(cardCheckJson)
            AppLog.d(TAG, "NNSmart CV: CARD_CHECK_RESULT send result: $sent")

            // Stay on Processing until LIMIT_CHECK_RESULT arrives; the rest
            // is handled in continuePaymentAfterLimitCheck → continueNnsmartCvPaymentAfterLimitCheck.
        } catch (e: Exception) {
            AppLog.e(TAG, "NNSmart CV: error during card verification", e)
            _screenState.value = PaymentScreenState.TransactionFailed(
                errorMessage = "Payment error: ${e.message}"
            )
            isProcessingPayment = false
            isHandlingPaymentLocally = false
            viewModelScope.launch {
                delay(4000)
                requestInitialScreen()
            }
        }
    }

    /**
     * Switchio / Monet+ payment flow (sale-first / post-processing).
     *
     * Capture payment, send PAR/monetToken for daily-limit validation, and
     * reverse via originalTransactionId if the backend rejects.
     */
    private suspend fun processSwitchioPayment(
        transactionId: String,
        amountFormatted: String,
        currencyCode: String
    ) {
        try {
            // Use a dedicated Switchio txn id so reversal can address it later.
            val switchioTxnId = UUID.randomUUID().toString()
            AppLog.d(
                TAG,
                "Switchio: performing up-front sale amount=$amountFormatted " +
                    "backendRef=$transactionId switchioTxnId=$switchioTxnId"
            )
            val saleResult = SwitchioPaymentManager.performSale(
                amountFormatted = amountFormatted,
                currencyAlphaCode = currencyCode,
                transactionId = switchioTxnId,
                invoiceNumber = transactionId.take(20)
            )

            if (!saleResult.success) {
                AppLog.w(TAG, "Switchio sale failed: code=${saleResult.resultCode} msg=${saleResult.message}")
                _screenState.value = PaymentScreenState.TransactionFailed(
                    errorMessage = saleResult.message ?: "Payment failed"
                )
                isProcessingPayment = false
                isHandlingPaymentLocally = false

                val paymentResultJson = buildPaymentResultJson(
                    success = false,
                    transactionId = transactionId,
                    resultCode = saleResult.resultCode,
                    message = saleResult.message
                )
                socketManager.sendMessage(paymentResultJson)

                viewModelScope.launch {
                    delay(4000)
                    requestInitialScreen()
                }
                return
            }

            pendingSwitchioSale = saleResult
            saleResult.transactionId?.let { trxId ->
                savePendingRecoveryTransaction(
                    transactionId = transactionId,
                    amount = currentAmount,
                    originalTrxUniqueId = trxId,
                    provider = "switchio"
                )
            }

            val rawIdentity = SwitchioPaymentManager.cardIdentityForLimit(saleResult).orEmpty()
            val tokenForLimitCheck = cardIdForLimitCheck(rawIdentity, "Switchio")
                ?: return rejectPaymentWithoutCardId(transactionId)

            AppLog.d(
                TAG,
                "Switchio: sale approved. identity='$rawIdentity' switchioTxnId=${saleResult.transactionId} " +
                    "tokenUsed=$tokenForLimitCheck"
            )

            val cardCheckJson = """
                {
                    "messageType": "CARD_CHECK_RESULT",
                    "screen": "PROCESSING",
                    "data": {
                        "cardToken": "$tokenForLimitCheck",
                        "selectedAmount": $currentAmount
                    },
                    "transactionId": "$transactionId",
                    "timestamp": ${System.currentTimeMillis()}
                }
            """.trimIndent()
            AppLog.d(TAG, "Switchio: sending CARD_CHECK_RESULT to backend for limit validation")
            socketManager.sendMessage(cardCheckJson)
        } catch (e: Exception) {
            AppLog.e(TAG, "Switchio: error during sale", e)
            _screenState.value = PaymentScreenState.TransactionFailed(
                errorMessage = "Payment error: ${e.message}"
            )
            isProcessingPayment = false
            isHandlingPaymentLocally = false
            viewModelScope.launch {
                delay(4000)
                requestInitialScreen()
            }
        }
    }

    /**
     * Switchio two-step pre-processing flow (Option 2).
     *
     * 1. read_card → card identity stored on terminal, no capture
     * 2. backend limit check
     * 3. approve → read_card_payment / reject → nothing captured
     */
    private suspend fun processSwitchioCardVerifyPayment(
        transactionId: String,
        amountFormatted: String,
        currencyCode: String
    ) {
        try {
            val readCardTxnId = UUID.randomUUID().toString()
            AppLog.d(
                TAG,
                "Switchio read_card: amount=$amountFormatted " +
                    "backendRef=$transactionId readCardTxnId=$readCardTxnId currency=$currencyCode"
            )
            val cvResult = SwitchioPaymentManager.performReadCard(
                amountFormatted = amountFormatted,
                currencyAlphaCode = currencyCode,
                transactionId = readCardTxnId
            )

            if (!cvResult.success) {
                AppLog.w(TAG, "Switchio read_card failed: ${cvResult.message}")
                _screenState.value = PaymentScreenState.TransactionFailed(
                    errorMessage = cvResult.message ?: "Card read failed"
                )
                isProcessingPayment = false
                isHandlingPaymentLocally = false

                val paymentResultJson = buildPaymentResultJson(
                    success = false,
                    transactionId = transactionId,
                    resultCode = cvResult.responseCode ?: "READ_CARD_FAILED",
                    message = cvResult.message
                )
                socketManager.sendMessage(paymentResultJson)

                viewModelScope.launch {
                    delay(4000)
                    requestInitialScreen()
                }
                return
            }

            pendingSwitchioCardVerify = cvResult

            val rawIdentity = SwitchioPaymentManager.cardIdentityForLimit(cvResult).orEmpty()
            val tokenForLimitCheck = cardIdForLimitCheck(rawIdentity, "Switchio read_card")
                ?: return rejectPaymentWithoutCardId(transactionId)

            AppLog.d(
                TAG,
                "Switchio read_card OK. identity='$rawIdentity' " +
                    "readCardTxnId=${cvResult.transactionId} cardInputType=${cvResult.cardInputType} " +
                    "tokenUsed=$tokenForLimitCheck"
            )

            val cardCheckJson = """
                {
                    "messageType": "CARD_CHECK_RESULT",
                    "screen": "PROCESSING",
                    "data": {
                        "cardToken": "$tokenForLimitCheck",
                        "selectedAmount": $currentAmount
                    },
                    "transactionId": "$transactionId",
                    "timestamp": ${System.currentTimeMillis()}
                }
            """.trimIndent()
            AppLog.d(TAG, "Switchio read_card: sending CARD_CHECK_RESULT to backend for limit validation")
            socketManager.sendMessage(cardCheckJson)
        } catch (e: Exception) {
            AppLog.e(TAG, "Switchio read_card: error", e)
            _screenState.value = PaymentScreenState.TransactionFailed(
                errorMessage = "Payment error: ${e.message}"
            )
            isProcessingPayment = false
            isHandlingPaymentLocally = false
            viewModelScope.launch {
                delay(4000)
                requestInitialScreen()
            }
        }
    }

    /**
     * NNSmart Card Verification post-limit-check flow.
     *
     * Unlike the legacy NNSmart flow, no money has been captured yet:
     *   - approved  → confirm the card verification (terminal captures payment).
     *   - rejected  → cancel the card verification (no reversal needed).
     */
    private fun continueNnsmartCvPaymentAfterLimitCheck(
        approved: Boolean,
        transactionId: String,
        errorMessage: String,
        cvResult: NNSmartCardVerificationResult
    ) {
        if (approved) {
            AppLog.d(TAG, "NNSmart CV: limit approved, confirming card verification (cvTxId=${cvResult.transactionId})")

            viewModelScope.launch {
                try {
                    val saleResult = NNSmartPaymentManager.confirmCardVerification(
                        context = getApplication()
                    )

                    if (!saleResult.success) {
                        AppLog.w(TAG, "NNSmart CV: confirm failed: code=${saleResult.resultCode} msg=${saleResult.message}")
                        _screenState.value = PaymentScreenState.TransactionFailed(
                            errorMessage = saleResult.message ?: "Payment capture failed"
                        )

                        val paymentResultJson = buildPaymentResultJson(
                            success = false,
                            transactionId = transactionId,
                            resultCode = saleResult.resultCode,
                            message = saleResult.message
                        )
                        AppLog.d(TAG, "NNSmart CV: sending PAYMENT_RESULT (confirm-failed) to backend")
                        socketManager.sendMessage(paymentResultJson)

                        pendingNnsmartCardVerification = null
                        isProcessingPayment = false
                        isHandlingPaymentLocally = false
                        delay(4000)
                        requestInitialScreen()
                        return@launch
                    }

                    // Payment captured successfully.
                    recordSuccessfulSale(
                        SuccessfulSaleTransaction(
                            transactionId = transactionId,
                            amount = currentAmount,
                            requesterTransRefNum = saleResult.originalTrxUniqueId ?: transactionId
                        )
                    )
                    saleResult.originalTrxUniqueId?.let { trxId ->
                        savePendingTicketPrintTransaction(
                            transactionId = transactionId,
                            amount = currentAmount,
                            originalRequesterRef = trxId,
                            provider = "nnsmart"
                        )
                    }

                    val paymentResultJson = buildPaymentResultJson(
                        success = true,
                        transactionId = transactionId,
                        resultCode = saleResult.resultCode ?: "A",
                        message = saleResult.message ?: "APPROVED",
                        bankResultCode = "00"
                    )
                    AppLog.d(TAG, "NNSmart CV: sending PAYMENT_RESULT (success) to backend")
                    socketManager.sendMessage(paymentResultJson)

                    _screenState.value = PaymentScreenState.TransactionSuccess(showReceipt = true)
                    pendingNnsmartCardVerification = null
                    isProcessingPayment = false
                    isHandlingPaymentLocally = false
                } catch (e: Exception) {
                    AppLog.e(TAG, "NNSmart CV: error confirming card verification", e)
                    _screenState.value = PaymentScreenState.TransactionFailed(
                        errorMessage = "Payment error: ${e.message}"
                    )
                    pendingNnsmartCardVerification = null
                    isProcessingPayment = false
                    isHandlingPaymentLocally = false
                    delay(4000)
                    requestInitialScreen()
                }
            }
            return
        }

        // Rejected by backend limit check — simply cancel, no reversal needed.
        AppLog.d(TAG, "NNSmart CV: limit rejected, cancelling card verification")
        _screenState.value = PaymentScreenState.LimitError(
            errorMessage = errorMessage
        )
        allowNavigationFromLimitError = false

        viewModelScope.launch {
            try {
                NNSmartPaymentManager.cancelCardVerification(context = getApplication())
                AppLog.d(TAG, "NNSmart CV: card verification cancelled successfully")
            } catch (e: Exception) {
                AppLog.e(TAG, "NNSmart CV: error cancelling card verification", e)
            }

            pendingNnsmartCardVerification = null
            isProcessingPayment = false
            isHandlingPaymentLocally = false

            delay(4000)
            requestInitialScreen()
        }
    }

    /**
     * Switchio two-step post-limit-check flow.
     *
     * No money captured yet:
     *   - approved → read_card_payment (uses stored card from read_card)
     *   - rejected → show LimitError (nothing to reverse)
     */
    private fun continueSwitchioCvPaymentAfterLimitCheck(
        approved: Boolean,
        transactionId: String,
        errorMessage: String,
        currencyCode: String
    ) {
        if (approved) {
            val readCard = pendingSwitchioCardVerify
            AppLog.d(
                TAG,
                "Switchio: limit approved, performing read_card_payment " +
                    "(storedTxnId=${readCard?.transactionId} cardInputType=${readCard?.cardInputType})"
            )
            viewModelScope.launch {
                try {
                    val database = AppDatabase.getDatabase(getApplication())
                    val deviceInfo = database.deviceInfoDao().getDeviceInfo().first()
                    val amountFormatted = String.format("%.2f", currentAmount.toDouble())
                    // Prefer the same transactionId from read_card so Switchio
                    // can associate the stored card with this payment.
                    val switchioTxnId = readCard?.transactionId?.takeIf { it.isNotBlank() }
                        ?: UUID.randomUUID().toString()
                    val saleResult = SwitchioPaymentManager.performReadCardPayment(
                        amountFormatted = amountFormatted,
                        currencyAlphaCode = deviceInfo?.currency ?: currencyCode,
                        transactionId = switchioTxnId,
                        cardInputType = readCard?.cardInputType ?: "CLESS",
                        serviceLevel = "SELF_SERVE",
                        invoiceNumber = transactionId.take(20)
                    )

                    if (!saleResult.success) {
                        AppLog.w(
                            TAG,
                            "Switchio read_card_payment failed: code=${saleResult.resultCode} msg=${saleResult.message}"
                        )
                        _screenState.value = PaymentScreenState.TransactionFailed(
                            errorMessage = saleResult.message ?: "Payment capture failed"
                        )
                        val paymentResultJson = buildPaymentResultJson(
                            success = false,
                            transactionId = transactionId,
                            resultCode = saleResult.resultCode,
                            message = saleResult.message
                        )
                        socketManager.sendMessage(paymentResultJson)
                        pendingSwitchioCardVerify = null
                        isProcessingPayment = false
                        isHandlingPaymentLocally = false
                        delay(4000)
                        requestInitialScreen()
                        return@launch
                    }

                    recordSuccessfulSale(
                        SuccessfulSaleTransaction(
                            transactionId = transactionId,
                            amount = currentAmount,
                            requesterTransRefNum = saleResult.transactionId ?: switchioTxnId,
                            provider = "switchio",
                            currency = deviceInfo?.currency ?: currencyCode
                        )
                    )
                    savePendingTicketPrintTransaction(
                        transactionId = transactionId,
                        amount = currentAmount,
                        originalRequesterRef = saleResult.transactionId ?: switchioTxnId,
                        provider = "switchio"
                    )

                    val paymentResultJson = buildPaymentResultJson(
                        success = true,
                        transactionId = transactionId,
                        resultCode = saleResult.resultCode ?: "OK",
                        message = saleResult.message ?: "APPROVED",
                        bankResultCode = "00"
                    )
                    socketManager.sendMessage(paymentResultJson)

                    _screenState.value = PaymentScreenState.TransactionSuccess(showReceipt = true)
                    pendingSwitchioCardVerify = null
                    isProcessingPayment = false
                    isHandlingPaymentLocally = false
                } catch (e: Exception) {
                    AppLog.e(TAG, "Switchio read_card_payment: error", e)
                    _screenState.value = PaymentScreenState.TransactionFailed(
                        errorMessage = "Payment error: ${e.message}"
                    )
                    pendingSwitchioCardVerify = null
                    isProcessingPayment = false
                    isHandlingPaymentLocally = false
                    delay(4000)
                    requestInitialScreen()
                }
            }
            return
        }

        AppLog.d(TAG, "Switchio read_card: limit rejected — no capture to reverse")
        _screenState.value = PaymentScreenState.LimitError(
            errorMessage = errorMessage
        )
        allowNavigationFromLimitError = false
        pendingSwitchioCardVerify = null
        isProcessingPayment = false
        isHandlingPaymentLocally = false
        viewModelScope.launch {
            delay(4000)
            requestInitialScreen()
        }
    }

    /**
     * Switchio sale-first post-limit-check flow.
     */
    private fun continueSwitchioPaymentAfterLimitCheck(
        approved: Boolean,
        transactionId: String,
        errorMessage: String,
        saleResult: SwitchioPaymentResult
    ) {
        if (approved) {
            AppLog.d(TAG, "Switchio: limit approved, completing success flow")
            recordSuccessfulSale(
                SuccessfulSaleTransaction(
                    transactionId = transactionId,
                    amount = currentAmount,
                    requesterTransRefNum = saleResult.transactionId ?: transactionId,
                    provider = "switchio"
                )
            )
            savePendingTicketPrintTransaction(
                transactionId = transactionId,
                amount = currentAmount,
                originalRequesterRef = saleResult.transactionId ?: transactionId,
                provider = "switchio"
            )

            val paymentResultJson = buildPaymentResultJson(
                success = true,
                transactionId = transactionId,
                resultCode = saleResult.resultCode ?: "OK",
                message = saleResult.message ?: "APPROVED",
                bankResultCode = "00"
            )
            socketManager.sendMessage(paymentResultJson)

            _screenState.value = PaymentScreenState.TransactionSuccess(showReceipt = true)
            pendingSwitchioSale = null
            clearPendingRecoveryTransaction()
            isProcessingPayment = false
            isHandlingPaymentLocally = false
            return
        }

        AppLog.d(TAG, "Switchio: limit rejected, showing limit error before reversal")
        _screenState.value = PaymentScreenState.LimitError(
            errorMessage = errorMessage
        )
        allowNavigationFromLimitError = false
        viewModelScope.launch {
            delay(1200)
            _screenState.value = PaymentScreenState.ReversingTransaction(
                message = "Limit exceeded. Reversing card transaction..."
            )

            val originalId = saleResult.transactionId
            val reversal = try {
                if (originalId.isNullOrBlank()) {
                    AppLog.e(TAG, "Switchio: cannot reverse — transactionId missing")
                    SwitchioPaymentResult(
                        success = false,
                        resultCode = "MISSING_ORIGINAL_ID",
                        message = "Missing Switchio transactionId for reversal"
                    )
                } else {
                    SwitchioPaymentManager.performReversal(
                        originalTransactionId = originalId,
                        transactionId = UUID.randomUUID().toString()
                    )
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "Switchio: error reversing sale", e)
                SwitchioPaymentResult(
                    success = false,
                    resultCode = "REVERSAL_ERROR",
                    message = e.message
                )
            }

            val reversalResultJson = buildReversalResultJson(
                success = reversal.success,
                transactionId = transactionId,
                resultCode = if (reversal.success) "LIMIT_REVERSED" else "LIMIT_REVERSAL_FAILED",
                message = if (reversal.success) {
                    "Limit exceeded - sale reversed"
                } else {
                    "Limit exceeded - REVERSAL FAILED"
                },
                requesterTransRefNum = "REVERSAL_$transactionId",
                originalRequesterTransRefNum = originalId ?: transactionId,
                originalTransactionId = transactionId,
                reversalAmount = currentAmount
            )
            socketManager.sendMessage(reversalResultJson)

            if (reversal.success) {
                _screenState.value = PaymentScreenState.LimitError(
                    errorMessage = errorMessage
                )
                allowNavigationFromLimitError = false
                delay(4000)
                requestInitialScreen()
            } else {
                _screenState.value = PaymentScreenState.TransactionFailed(
                    errorMessage = "Limit exceeded and card reversal failed. Please contact staff."
                )
                delay(6000)
                requestInitialScreen()
            }

            pendingSwitchioSale = null
            clearPendingRecoveryTransaction()
            isProcessingPayment = false
            isHandlingPaymentLocally = false
        }
    }

    /**
     * Build a PAYMENT_RESULT JSON matching the server's expected contract.
     */
    private fun buildPaymentResultJson(
        success: Boolean,
        transactionId: String,
        resultCode: String?,
        message: String?,
        bankResultCode: String? = null
    ): String {
        val screen = if (success) "SUCCESS" else "FAILED"
        val errCodeJson = if (resultCode != null) "\"$resultCode\"" else "null"
        val errMsgJson = if (message != null) "\"${message.replace("\"", "\\\"")}\"" else "null"
        // Successful sales are reported from recordSuccessfulSale; failures report here.
        if (!success) {
            reportPortalTransaction(
                transactionId = transactionId,
                status = "Failed",
                amountMinor = currentAmount,
                message = message ?: resultCode ?: "FAILED"
            )
        }
        return """
            {
                "messageType": "PAYMENT_RESULT",
                "screen": "$screen",
                "data": {
                    "errorCode": $errCodeJson,
                    "errorMessage": $errMsgJson,
                    "paymentDetails": {
                        "Result": "${resultCode ?: ""}",
                        "BankResultCode": "${bankResultCode ?: ""}",
                        "Message": "${(message ?: "").replace("\"", "\\\"")}",
                        "RequesterTransRefNum": "$transactionId"
                    }
                },
                "transactionId": "$transactionId",
                "timestamp": ${System.currentTimeMillis()}
            }
        """.trimIndent()
    }

    /**
     * Build a REVERSAL_RESULT JSON for any reversal/cancellation outcome.
     */
    private fun buildReversalResultJson(
        success: Boolean,
        transactionId: String,
        resultCode: String?,
        message: String?,
        requesterTransRefNum: String,
        originalRequesterTransRefNum: String,
        originalTransactionId: String,
        reversalAmount: Int
    ): String {
        val screen = if (success) "SUCCESS" else "FAILED"
        val errCodeJson = if (resultCode != null) "\"$resultCode\"" else "null"
        val errMsgJson = if (message != null) "\"${message.replace("\"", "\\\"")}\"" else "null"
        return """
            {
                "messageType": "REVERSAL_RESULT",
                "screen": "$screen",
                "data": {
                    "errorCode": $errCodeJson,
                    "errorMessage": $errMsgJson,
                    "paymentDetails": {
                        "Result": "${resultCode ?: ""}",
                        "BankResultCode": "${if (success) "00" else ""}",
                        "Message": "${(message ?: "").replace("\"", "\\\"")}",
                        "RequesterTransRefNum": "$requesterTransRefNum",
                        "OriginalRequesterTransRefNum": "$originalRequesterTransRefNum"
                    },
                    "originalTransactionId": "$originalTransactionId",
                    "reversalAmount": $reversalAmount
                },
                "transactionId": "$transactionId",
                "timestamp": ${System.currentTimeMillis()}
            }
        """.trimIndent()
    }

    private fun savePendingRecoveryTransaction(
        transactionId: String,
        amount: Int,
        originalTrxUniqueId: String,
        provider: String
    ) {
        val json = JSONObject().apply {
            put("transactionId", transactionId)
            put("amount", amount)
            put("originalTrxUniqueId", originalTrxUniqueId)
            put("provider", provider)
        }.toString()
        recoveryPrefs.edit().putString("pending_tx", json).apply()
        AppLog.d(TAG, "Saved pending recovery transaction for tx=$transactionId provider=$provider")
        audit("Persisted pending_tx tx=$transactionId provider=$provider amount=$amount originalTrxId=$originalTrxUniqueId")
    }

    private fun clearPendingRecoveryTransaction() {
        recoveryPrefs.edit().remove("pending_tx").apply()
        audit("Cleared pending_tx")
    }

    private fun readPendingRecoveryTransaction(): PendingRecoveryTransaction? {
        val raw = recoveryPrefs.getString("pending_tx", null) ?: return null
        return try {
            val json = JSONObject(raw)
            PendingRecoveryTransaction(
                transactionId = json.optString("transactionId"),
                amount = json.optInt("amount"),
                originalTrxUniqueId = json.optString("originalTrxUniqueId"),
                provider = json.optString("provider", "nnsmart")
            )
        } catch (e: Exception) {
            AppLog.e(TAG, "Invalid pending recovery payload, clearing", e)
            clearPendingRecoveryTransaction()
            null
        }
    }

    private suspend fun recoverPendingTransactionIfAny() {
        val pending = readPendingRecoveryTransaction() ?: return
        if (pending.transactionId.isBlank() || pending.originalTrxUniqueId.isBlank()) {
            AppLog.w(TAG, "Pending recovery transaction missing required fields, clearing")
            clearPendingRecoveryTransaction()
            return
        }
        // Planet sale-first payments are stored under the Planet provider name
        // ("integra"); CCV and mock never register a pending_tx.
        if (CcvPaymentManager.isCcvProvider(pending.provider) || pending.provider == "mock") {
            clearPendingRecoveryTransaction()
            return
        }
        val isPlanet = !NNSmartPaymentManager.isNNSmartProvider(pending.provider) &&
            !SwitchioPaymentManager.isSwitchioProvider(pending.provider)

        AppLog.w(
            TAG,
            "Recovering pending ${pending.provider} transaction after restart: tx=${pending.transactionId}"
        )
        _screenState.value = PaymentScreenState.ReversingTransaction(
            message = "Recovering previous transaction..."
        )
        ensureSocketConnection()

        val requesterRef = "RECOVERY_REVERSAL_${pending.transactionId}"
        val cancelOk = try {
            if (isPlanet) {
                PlanetPaymentManager.performSaleReversal(
                    amountFormatted = String.format("%.2f", pending.amount.toDouble()),
                    requesterRef = requesterRef,
                    originalRequesterRef = pending.originalTrxUniqueId
                ).success
            } else if (SwitchioPaymentManager.isSwitchioProvider(pending.provider)) {
                SwitchioPaymentManager.performReversal(
                    originalTransactionId = pending.originalTrxUniqueId,
                    transactionId = UUID.randomUUID().toString()
                ).success
            } else {
                NNSmartPaymentManager.performCancel(
                    context = getApplication(),
                    requesterRef = requesterRef,
                    originalTrxUniqueId = pending.originalTrxUniqueId
                )
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Recovery reversal failed with exception", e)
            false
        }

        val resultCode = if (cancelOk) "RECOVERY_REVERSED" else "RECOVERY_REVERSAL_FAILED"
        val resultMessage = if (cancelOk) {
            "Recovered after app restart - sale reversed"
        } else {
            "Recovered after app restart - reversal failed"
        }
        val reversalResultJson = buildReversalResultJson(
            success = cancelOk,
            transactionId = pending.transactionId,
            resultCode = resultCode,
            message = resultMessage,
            requesterTransRefNum = requesterRef,
            originalRequesterTransRefNum = pending.originalTrxUniqueId,
            originalTransactionId = pending.transactionId,
            reversalAmount = pending.amount
        )
        sendCriticalReversalResult(
            reversalResultJson = reversalResultJson,
            reason = "Sale recovery"
        )
        clearPendingRecoveryTransaction()

        if (cancelOk) {
            _screenState.value = PaymentScreenState.ReversalSuccess(
                message = "Recovered previous transaction successfully."
            )
            delay(2200)
        } else {
            _screenState.value = PaymentScreenState.TransactionFailed(
                errorMessage = "Recovery reversal failed - please contact staff"
            )
            delay(3000)
        }
        requestInitialScreen()
    }

    private fun savePendingTicketPrintTransaction(
        transactionId: String,
        amount: Int,
        originalRequesterRef: String,
        provider: String
    ) {
        val json = JSONObject().apply {
            put("transactionId", transactionId)
            put("amount", amount)
            put("originalRequesterRef", originalRequesterRef)
            put("provider", provider)
        }.toString()
        recoveryPrefs.edit().putString("pending_ticket_tx", json).apply()
        AppLog.d(TAG, "Saved pending ticket-print transaction for tx=$transactionId")
        audit("Persisted pending_ticket_tx tx=$transactionId provider=$provider amount=$amount originalRef=$originalRequesterRef")
    }

    private fun clearPendingTicketPrintTransaction() {
        recoveryPrefs.edit().remove("pending_ticket_tx").apply()
        audit("Cleared pending_ticket_tx")
    }

    private fun savePendingCriticalMessage(rawJson: String) {
        recoveryPrefs.edit().putString("pending_critical_message", rawJson).apply()
        audit("Persisted pending_critical_message bytes=${rawJson.length}")
    }

    private fun readPendingCriticalMessage(): String? =
        recoveryPrefs.getString("pending_critical_message", null)

    private fun clearPendingCriticalMessage() {
        recoveryPrefs.edit().remove("pending_critical_message").apply()
        audit("Cleared pending_critical_message")
    }

    private fun sendCriticalReversalResult(reversalResultJson: String, reason: String) {
        val sent = socketManager.sendMessage(reversalResultJson)
        AppLog.d(TAG, "$reason REVERSAL_RESULT send result: $sent")
        audit("$reason REVERSAL_RESULT send attempted sent=$sent payload=$reversalResultJson")
        if (!sent) {
            AppLog.w(TAG, "Failed to deliver REVERSAL_RESULT, persisting for retry")
            savePendingCriticalMessage(reversalResultJson)
        } else {
            clearPendingCriticalMessage()
        }
    }

    private fun flushPendingCriticalMessages() {
        val pendingMessage = readPendingCriticalMessage() ?: return
        if (!socketManager.isConnected()) return
        val sent = socketManager.sendMessage(pendingMessage)
        AppLog.d(TAG, "Retry pending critical message send result: $sent")
        audit("Retry pending_critical_message sent=$sent bytes=${pendingMessage.length}")
        if (sent) {
            clearPendingCriticalMessage()
        }
    }

    private fun readPendingTicketPrintTransaction(): PendingTicketPrintTransaction? {
        val raw = recoveryPrefs.getString("pending_ticket_tx", null) ?: return null
        return try {
            val json = JSONObject(raw)
            PendingTicketPrintTransaction(
                transactionId = json.optString("transactionId"),
                amount = json.optInt("amount"),
                originalRequesterRef = json.optString("originalRequesterRef"),
                provider = json.optString("provider", "nnsmart")
            )
        } catch (e: Exception) {
            AppLog.e(TAG, "Invalid pending ticket-print payload, clearing", e)
            clearPendingTicketPrintTransaction()
            null
        }
    }

    private fun scheduleDisconnectRecoveryIfNeeded() {
        if (disconnectRecoveryJob?.isActive == true) return
        if (readPendingTicketPrintTransaction() == null) return
        disconnectRecoveryJob = viewModelScope.launch {
            // Grace period for brief network blips.
            delay(20000)
            if (!socketManager.isConnected() && readPendingTicketPrintTransaction() != null) {
                AppLog.w(TAG, "Prolonged disconnect with pending ticket state; starting protective reversal")
                audit("Prolonged disconnect threshold reached; protective reversal starts")
                recoverPendingTicketNotPrintedIfAny()
            }
        }
    }

    private suspend fun recoverPendingTicketNotPrintedIfAny() {
        if (isRecoveryInProgress) return
        val pending = readPendingTicketPrintTransaction() ?: return
        if (pending.transactionId.isBlank() || pending.originalRequesterRef.isBlank()) {
            clearPendingTicketPrintTransaction()
            return
        }
        isRecoveryInProgress = true

        try {
            AppLog.w(TAG, "Recovering ticket-not-printed transaction: tx=${pending.transactionId}")
            _screenState.value = PaymentScreenState.ReversingTransaction(
                message = "Ticket not confirmed. Reversing transaction..."
            )
            ensureSocketConnection()

            val recoveryRef = "RECOVERY_TICKET_REVERSAL_${pending.transactionId}"
            val reversalResult = try {
                when {
                    pending.provider == "mock" -> MockPaymentManager.performSaleReversal(
                        amountFormatted = String.format("%.2f", pending.amount.toDouble()),
                        requesterRef = recoveryRef,
                        originalRequesterRef = pending.originalRequesterRef
                    )
                    NNSmartPaymentManager.isNNSmartProvider(pending.provider) -> {
                        val ok = NNSmartPaymentManager.performCancel(
                            context = getApplication(),
                            requesterRef = recoveryRef,
                            originalTrxUniqueId = pending.originalRequesterRef
                        )
                        app.sst.pinto.payment.PlanetPaymentResult(
                            success = ok,
                            resultCode = if (ok) "TICKET_NOT_PRINTED_RECOVERY_REVERSED" else "TICKET_NOT_PRINTED_RECOVERY_FAILED",
                            bankResultCode = if (ok) "00" else null,
                            message = if (ok) "Ticket not confirmed after restart - sale reversed" else "Ticket not confirmed after restart - reversal failed",
                            requesterTransRefNum = recoveryRef,
                            rawOptions = emptyMap()
                        )
                    }
                    SwitchioPaymentManager.isSwitchioProvider(pending.provider) -> {
                        val reversal = SwitchioPaymentManager.performReversal(
                            originalTransactionId = pending.originalRequesterRef,
                            transactionId = UUID.randomUUID().toString()
                        )
                        app.sst.pinto.payment.PlanetPaymentResult(
                            success = reversal.success,
                            resultCode = if (reversal.success) {
                                "TICKET_NOT_PRINTED_RECOVERY_REVERSED"
                            } else {
                                "TICKET_NOT_PRINTED_RECOVERY_FAILED"
                            },
                            bankResultCode = if (reversal.success) "00" else null,
                            message = if (reversal.success) {
                                "Ticket not confirmed after restart - sale reversed"
                            } else {
                                "Ticket not confirmed after restart - reversal failed"
                            },
                            requesterTransRefNum = recoveryRef,
                            rawOptions = emptyMap()
                        )
                    }
                    else -> PlanetPaymentManager.performSaleReversal(
                        amountFormatted = String.format("%.2f", pending.amount.toDouble()),
                        requesterRef = recoveryRef,
                        originalRequesterRef = pending.originalRequesterRef
                    )
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "Ticket recovery reversal exception", e)
                app.sst.pinto.payment.PlanetPaymentResult(
                    success = false,
                    resultCode = "TICKET_NOT_PRINTED_RECOVERY_EXCEPTION",
                    bankResultCode = null,
                    message = "Ticket recovery reversal exception: ${e.message}",
                    requesterTransRefNum = recoveryRef,
                    rawOptions = emptyMap()
                )
            }

            val reversalResultJson = buildReversalResultJson(
                success = reversalResult.success,
                transactionId = pending.transactionId,
                resultCode = reversalResult.resultCode,
                message = reversalResult.message,
                requesterTransRefNum = recoveryRef,
                originalRequesterTransRefNum = pending.originalRequesterRef,
                originalTransactionId = pending.transactionId,
                reversalAmount = pending.amount
            )
            sendCriticalReversalResult(
                reversalResultJson = reversalResultJson,
                reason = "Ticket recovery"
            )
            clearPendingTicketPrintTransaction()
            if (reversalResult.success) {
                forgetSuccessfulSale(
                    SuccessfulSaleTransaction(
                        transactionId = pending.transactionId,
                        amount = pending.amount,
                        requesterTransRefNum = pending.originalRequesterRef
                    )
                )
                _screenState.value = PaymentScreenState.ReversalSuccess(
                    message = "Previous transaction reversed (ticket not confirmed)."
                )
                delay(2200)
            } else {
                _screenState.value = PaymentScreenState.TransactionFailed(
                    errorMessage = "Ticket recovery reversal failed - contact staff"
                )
                delay(3000)
            }
            requestInitialScreen()
        } finally {
            isRecoveryInProgress = false
        }
    }

    /**
     * CCV-specific post-limit-check flow.
     *
     * The CCV sale has already been captured by the time we get here, so:
     *   - approved  → record the sale (with its CCV refund-addressing params) and
     *                 jump to the success path.
     *   - rejected  → REFUND the sale addressed by the original approvalCode /
     *                 receiptNumber, then show LimitError. If the refund itself
     *                 fails the CCV params remain in the 10-entry history so a
     *                 portal REFUND_REQUEST can complete it later.
     */
    private fun continueCcvPaymentAfterLimitCheck(
        approved: Boolean,
        transactionId: String,
        errorMessage: String,
        ccvSale: CcvPaymentResult
    ) {
        // The sale's terminal reference, used to address the refund.
        val saleRef = ccvSale.trxReferenceNumber ?: transactionId

        if (approved) {
            AppLog.d(TAG, "CCV: limit approved, completing success flow")

            // Record the sale so a later server-initiated refund can reverse it,
            // carrying the CCV refund-addressing params (approvalCode/receiptNumber).
            recordSuccessfulSale(
                SuccessfulSaleTransaction(
                    transactionId = transactionId,
                    amount = currentAmount,
                    requesterTransRefNum = saleRef,
                    provider = "ccv",
                    approvalCode = ccvSale.approvalCode,
                    receiptNumber = ccvSale.receiptNumber,
                    trxReferenceNumber = ccvSale.trxReferenceNumber,
                    currency = ccvSale.currencyAlphaCode
                )
            )
            // NOTE: CCV deliberately does NOT register a pending_ticket_tx for
            // protective auto-reversal. CCV refunds are addressed by the sale's
            // approvalCode/receiptNumber and are portal-triggered (the stored
            // 10-entry history above is the recovery mechanism); an idle-device
            // auto-reversal would issue an unaddressed refund that prompts for the
            // card. See handleRefundRequest's CCV branch.

            val paymentResultJson = buildPaymentResultJson(
                success = true,
                transactionId = transactionId,
                resultCode = ccvSale.resultCode ?: "A",
                message = ccvSale.message ?: "APPROVED",
                bankResultCode = "00"
            )
            AppLog.d(TAG, "CCV: sending PAYMENT_RESULT (success) to backend")
            socketManager.sendMessage(paymentResultJson)

            _screenState.value = PaymentScreenState.TransactionSuccess(showReceipt = true)
            pendingCcvSale = null
            clearPendingRecoveryTransaction()
            isProcessingPayment = false
            isHandlingPaymentLocally = false
            return
        }

        // Rejected by backend limit check → refund the captured sale.
        AppLog.d(TAG, "CCV: limit rejected, showing limit error before refund")
        _screenState.value = PaymentScreenState.LimitError(
            errorMessage = errorMessage
        )
        allowNavigationFromLimitError = false
        viewModelScope.launch {
            delay(1200)
            _screenState.value = PaymentScreenState.ReversingTransaction(
                message = "Limit exceeded. Refunding card transaction..."
            )

            val refundResult = try {
                CcvPaymentManager.performRefund(
                    context = getApplication(),
                    amountFormatted = String.format("%.2f", currentAmount.toDouble()),
                    requesterRef = "REVERSAL_$transactionId",
                    currencyAlphaCode = ccvSale.currencyAlphaCode,
                    approvalCode = ccvSale.approvalCode,
                    receiptNumber = ccvSale.receiptNumber
                )
            } catch (e: Exception) {
                AppLog.e(TAG, "CCV: error refunding sale", e)
                CcvPaymentResult(success = false, resultCode = "REFUND_FAILED", message = e.message)
            }
            val refundOk = refundResult.success

            val reversalResultCode = if (refundOk) "LIMIT_REFUNDED" else "LIMIT_REFUND_FAILED"
            val reversalMessage = if (refundOk) "Limit exceeded - sale refunded" else "Limit exceeded - REFUND FAILED"
            val reversalResultJson = buildReversalResultJson(
                success = refundOk,
                transactionId = transactionId,
                resultCode = reversalResultCode,
                message = reversalMessage,
                requesterTransRefNum = "REVERSAL_$transactionId",
                originalRequesterTransRefNum = saleRef,
                originalTransactionId = transactionId,
                reversalAmount = currentAmount
            )
            AppLog.d(TAG, "CCV: sending REVERSAL_RESULT (limit-rejected) to backend")
            socketManager.sendMessage(reversalResultJson)

            if (refundOk) {
                clearPendingTicketPrintTransaction()
                _screenState.value = PaymentScreenState.ReversalSuccess(
                    message = "Limit exceeded. Card transaction refunded successfully."
                )
                viewModelScope.launch {
                    delay(2500)
                    requestInitialScreen()
                }
            } else {
                // Refund did not go through now. The CCV params are still recorded
                // in the 10-entry history (recordSuccessfulSale below) so a portal
                // REFUND_REQUEST can complete it later.
                recordSuccessfulSale(
                    SuccessfulSaleTransaction(
                        transactionId = transactionId,
                        amount = currentAmount,
                        requesterTransRefNum = saleRef,
                        provider = "ccv",
                        approvalCode = ccvSale.approvalCode,
                        receiptNumber = ccvSale.receiptNumber,
                        trxReferenceNumber = ccvSale.trxReferenceNumber,
                        currency = ccvSale.currencyAlphaCode
                    )
                )
                _screenState.value = PaymentScreenState.TransactionFailed(
                    errorMessage = "Please contact staff - refund failed"
                )
                viewModelScope.launch {
                    delay(6000)
                    requestInitialScreen()
                }
            }

            pendingCcvSale = null
            clearPendingRecoveryTransaction()
            isProcessingPayment = false
            isHandlingPaymentLocally = false
        }
    }

    /**
     * NNSmart-specific post-limit-check flow.
     *
     * By the time we get here the sale has already been captured on the
     * terminal, so:
     *   - approved  → jump straight to the success path, exactly like the
     *                 post-sale branch of the Integra flow.
     *   - rejected  → reverse the sale via NNSmart Cancellation, then show
     *                 LimitError. If the reversal itself fails we surface a
     *                 TransactionFailed with a clear "contact staff" message.
     */
    private fun continueNnsmartPaymentAfterLimitCheck(
        approved: Boolean,
        transactionId: String,
        errorMessage: String,
        saleResult: NNSmartPaymentResult
    ) {
        if (approved) {
            AppLog.d(TAG, "NNSmart: limit approved, completing success flow")

            // Record the sale so a later server-initiated refund can reverse it.
            recordSuccessfulSale(
                SuccessfulSaleTransaction(
                    transactionId = transactionId,
                    amount = currentAmount,
                    requesterTransRefNum = saleResult.originalTrxUniqueId ?: transactionId
                )
            )
            savePendingTicketPrintTransaction(
                transactionId = transactionId,
                amount = currentAmount,
                originalRequesterRef = saleResult.originalTrxUniqueId ?: transactionId,
                provider = "nnsmart"
            )

            // Tell the server the payment succeeded.
            val paymentResultJson = buildPaymentResultJson(
                success = true,
                transactionId = transactionId,
                resultCode = saleResult.resultCode ?: "A",
                message = saleResult.message ?: "APPROVED",
                bankResultCode = "00"
            )
            AppLog.d(TAG, "NNSmart: sending PAYMENT_RESULT (success) to backend")
            AppLog.d(TAG, "NNSmart: PAYMENT_RESULT payload: $paymentResultJson")
            val sent = socketManager.sendMessage(paymentResultJson)
            AppLog.d(TAG, "NNSmart: PAYMENT_RESULT send result: $sent")

            _screenState.value = PaymentScreenState.TransactionSuccess(showReceipt = true)
            pendingNnsmartSale = null
            clearPendingRecoveryTransaction()
            isProcessingPayment = false
            isHandlingPaymentLocally = false
            return
        }

        // Rejected by backend limit check:
        // 1) show limit error
        // 2) show reversal in progress
        // 3) show reversal outcome
        AppLog.d(TAG, "NNSmart: limit rejected, showing limit error before reversal")
        _screenState.value = PaymentScreenState.LimitError(
            errorMessage = errorMessage
        )
        allowNavigationFromLimitError = false
        viewModelScope.launch {
            delay(1200)
            _screenState.value = PaymentScreenState.ReversingTransaction(
                message = "Limit exceeded. Reversing card transaction..."
            )

            val cancelOk = try {
                val trxId = saleResult.originalTrxUniqueId
                if (trxId.isNullOrBlank()) {
                    AppLog.e(TAG, "NNSmart: cannot reverse sale — originalTrxUniqueId is missing")
                    false
                } else {
                    NNSmartPaymentManager.performCancel(
                        context = getApplication(),
                        requesterRef = "REVERSAL_$transactionId",
                        originalTrxUniqueId = trxId
                    )
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "NNSmart: error reversing sale", e)
                false
            }

            // Notify the server reversal outcome via REVERSAL_RESULT.
            val reversalResultCode = if (cancelOk) "LIMIT_REVERSED" else "LIMIT_REVERSAL_FAILED"
            val reversalMessage = if (cancelOk) "Limit exceeded - sale reversed" else "Limit exceeded - REVERSAL FAILED"
            val reversalResultJson = buildReversalResultJson(
                success = cancelOk,
                transactionId = transactionId,
                resultCode = reversalResultCode,
                message = reversalMessage,
                requesterTransRefNum = "REVERSAL_$transactionId",
                originalRequesterTransRefNum = saleResult.originalTrxUniqueId ?: transactionId,
                originalTransactionId = transactionId,
                reversalAmount = currentAmount
            )
            AppLog.d(TAG, "NNSmart: sending REVERSAL_RESULT (limit-rejected) to backend")
            AppLog.d(TAG, "NNSmart: REVERSAL_RESULT payload: $reversalResultJson")
            val sent = socketManager.sendMessage(reversalResultJson)
            AppLog.d(TAG, "NNSmart: REVERSAL_RESULT send result: $sent")

            if (cancelOk) {
                clearPendingTicketPrintTransaction()
                _screenState.value = PaymentScreenState.ReversalSuccess(
                    message = "Limit exceeded. Card transaction reversed successfully."
                )
                viewModelScope.launch {
                    delay(2500)
                    requestInitialScreen()
                }
            } else {
                clearPendingTicketPrintTransaction()
                // Reversal failed — this is rare but needs operator attention.
                _screenState.value = PaymentScreenState.TransactionFailed(
                    errorMessage = "Please contact staff - reversal failed"
                )
                viewModelScope.launch {
                    delay(6000)
                    requestInitialScreen()
                }
            }

            pendingNnsmartSale = null
            clearPendingRecoveryTransaction()
            isProcessingPayment = false
            isHandlingPaymentLocally = false
        }
    }

    /**
     * Card identifier sent to the controller for its per-card daily limit check (PAR, token or
     * panHash from the payment app). Returns null when there is none on a release build: the
     * payment must then be rejected, otherwise every card would share one daily limit.
     */
    private fun cardIdForLimitCheck(realId: String?, providerLabel: String): String? {
        val cardId = app.sst.pinto.payment.LimitCheckCardId.resolve(realId, isDebuggableBuild)
        if (cardId == null) {
            AppLog.e(TAG, "$providerLabel: payment app returned no card identifier; rejecting the payment")
        } else if (realId.isNullOrBlank()) {
            AppLog.w(TAG, "$providerLabel: payment app returned no card identifier, using dev mock PAR (debug build)")
        }
        return cardId
    }

    /**
     * Rejects a payment whose card couldn't be identified, using the same provider-specific path
     * as a controller limit rejection (reverse/refund a captured sale, cancel a card verification).
     */
    private fun rejectPaymentWithoutCardId(transactionId: String) {
        if (pendingNnsmartCardVerification != null || pendingSwitchioCardVerify != null) {
            // Nothing was captured; tell the controller, which is still waiting for CARD_CHECK_RESULT.
            socketManager.sendMessage(
                buildPaymentResultJson(
                    success = false,
                    transactionId = transactionId,
                    resultCode = "CARD_ID_MISSING",
                    message = CARD_ID_MISSING_MESSAGE
                )
            )
        }
        continuePaymentAfterLimitCheck(
            approved = false,
            transactionId = transactionId,
            errorMessage = CARD_ID_MISSING_MESSAGE
        )
    }

    /**
     * Continue payment processing after limit check result is received.
     * This is called when LIMIT_CHECK_RESULT message is received from server.
     */
    private fun continuePaymentAfterLimitCheck(approved: Boolean, transactionId: String, errorMessage: String = "Daily spending limit exceeded") {
        // CCV: the sale has already been captured, so "rejected" means we must
        // REFUND it (handled in continueCcvPaymentAfterLimitCheck).
        val ccvSale = pendingCcvSale
        if (ccvSale != null) {
            continueCcvPaymentAfterLimitCheck(
                approved = approved,
                transactionId = transactionId,
                errorMessage = errorMessage,
                ccvSale = ccvSale
            )
            return
        }

        // NNSmart Card Verification flow: no money captured yet, just
        // confirm or cancel the pending card verification.
        val nnsmartCv = pendingNnsmartCardVerification
        if (nnsmartCv != null) {
            continueNnsmartCvPaymentAfterLimitCheck(
                approved = approved,
                transactionId = transactionId,
                errorMessage = errorMessage,
                cvResult = nnsmartCv
            )
            return
        }

        // Switchio Card Verify (pre-processing): no money captured yet.
        if (pendingSwitchioCardVerify != null) {
            viewModelScope.launch {
                val database = AppDatabase.getDatabase(getApplication())
                val currency = database.deviceInfoDao().getDeviceInfo().first()?.currency ?: "EUR"
                continueSwitchioCvPaymentAfterLimitCheck(
                    approved = approved,
                    transactionId = transactionId,
                    errorMessage = errorMessage,
                    currencyCode = currency
                )
            }
            return
        }

        // If a pending NNSmart sale is tracked, the sale has already been
        // taken on the terminal — handle this branch separately because
        // "rejected" here means we must REVERSE an already-successful sale.
        val nnsmartSale = pendingNnsmartSale
        if (nnsmartSale != null) {
            continueNnsmartPaymentAfterLimitCheck(
                approved = approved,
                transactionId = transactionId,
                errorMessage = errorMessage,
                saleResult = nnsmartSale
            )
            return
        }

        // Planet sale-first: reverse on limit rejection.
        val planetSale = pendingPlanetSale
        if (planetSale != null) {
            continuePlanetPaymentAfterLimitCheck(
                approved = approved,
                transactionId = transactionId,
                errorMessage = errorMessage,
                saleResult = planetSale
            )
            return
        }

        // Switchio sale-first: reverse on limit rejection.
        val switchioSale = pendingSwitchioSale
        if (switchioSale != null) {
            continueSwitchioPaymentAfterLimitCheck(
                approved = approved,
                transactionId = transactionId,
                errorMessage = errorMessage,
                saleResult = switchioSale
            )
            return
        }

        // If payment is not in progress but we received a LIMIT_ERROR, still show the error screen
        // This can happen if ViewModel was recreated (e.g., after activity restart)
        if (!approved && !isProcessingPayment) {
            AppLog.w(TAG, "Received LIMIT_ERROR but payment not in progress - showing error screen anyway")
            _screenState.value = PaymentScreenState.LimitError(
                errorMessage = errorMessage
            )
            allowNavigationFromLimitError = false // Reset flag when showing LIMIT_ERROR
            return
        }
        
        if (!isProcessingPayment) {
            AppLog.w(TAG, "Received limit check result but payment not in progress")
            return
        }
        
        val cardCheckResult = pendingCardCheckResult
        if (cardCheckResult == null) {
            AppLog.e(TAG, "No pending card check result found")
            // If we have an error message, still show it
            if (!approved) {
                _screenState.value = PaymentScreenState.LimitError(
                    errorMessage = errorMessage
                )
                allowNavigationFromLimitError = false // Reset flag when showing LIMIT_ERROR
            }
            isProcessingPayment = false
            return
        }
        
        if (!approved) {
            AppLog.d(TAG, "Limit check rejected, cancelling payment")
            // Cancel the card check on terminal - MUST complete before showing error screen
            viewModelScope.launch {
                try {
                    val database = AppDatabase.getDatabase(getApplication())
                    val deviceInfo = database.deviceInfoDao().getDeviceInfo().first()
                    if (deviceInfo != null && deviceInfo.paymentProvider.lowercase() == "integra") {
                        // Only cancel if using real terminal
                        AppLog.d(TAG, "Cancelling transaction on terminal with sequenceNumber: ${cardCheckResult.sequenceNumber}")
                        val cancelSuccess = PlanetPaymentManager.performCancel(
                            requesterRef = transactionId,
                            sequenceNumberToCancel = cardCheckResult.sequenceNumber
                        )
                        if (cancelSuccess) {
                            AppLog.d(TAG, "Transaction cancelled successfully on terminal")
                        } else {
                            AppLog.w(TAG, "Transaction cancel may have failed or timed out, but continuing")
                        }
                    } else {
                        AppLog.d(TAG, "Not using integra provider, skipping terminal cancel")
                    }
                } catch (e: Exception) {
                    AppLog.e(TAG, "Error cancelling payment", e)
                } finally {
                    // Always show error screen after cancel attempt completes
                    // Show error screen BEFORE resetting flags to ensure it's displayed
                    _screenState.value = PaymentScreenState.LimitError(
                        errorMessage = errorMessage
                    )
                    isProcessingPayment = false
                    isHandlingPaymentLocally = false // Reset flag on limit error
                    allowNavigationFromLimitError = false // Reset flag when showing LIMIT_ERROR
                    AppLog.d(TAG, "Limit error screen displayed: $errorMessage")
                }
            }
            return
        }
        
        // Step 5: Perform Sale transaction locally
        AppLog.d(TAG, "Limit check approved, performing sale transaction")
        viewModelScope.launch {
            try {
                val database = AppDatabase.getDatabase(getApplication())
                val deviceInfo = database.deviceInfoDao().getDeviceInfo().first()
                
                if (deviceInfo == null) {
                    AppLog.e(TAG, "Device configuration not found")
                    _screenState.value = PaymentScreenState.TransactionFailed(
                        errorMessage = "Device configuration not found"
                    )
                    isProcessingPayment = false
                    isHandlingPaymentLocally = false // Reset flag on error
                    
                    // Auto-return to amount selection after 4 seconds
                    viewModelScope.launch {
                        delay(4000)
                        requestInitialScreen()
                    }
                    return@launch
                }
                
                val paymentProvider = deviceInfo.paymentProvider.lowercase()
                val amountFormatted = String.format("%.2f", currentAmount.toDouble())
                
                // Perform Sale with amount including fee
                AppLog.d(TAG, "Performing sale transaction with provider: $paymentProvider, amount (including fee): $amountFormatted")
                val saleResult = if (paymentProvider == "mock") {
                    MockPaymentManager.performSale(amountFormatted, transactionId)
                } else {
                    PlanetPaymentManager.performSale(
                        amountFormatted = amountFormatted, // Amount includes transaction fee
                        requesterRef = transactionId
                    )
                }
                
                rememberDccSale(saleResult, transactionId)

                // Step 6: Show SUCCESS or FAILED screen immediately
                if (saleResult.success) {
                    AppLog.d(TAG, "Payment successful")
                    // Store successful sale transaction for potential refund/reversal
                    recordSuccessfulSale(
                        SuccessfulSaleTransaction(
                            transactionId = transactionId,
                            amount = currentAmount,
                            requesterTransRefNum = saleResult.requesterTransRefNum ?: transactionId
                        )
                    )
                    savePendingTicketPrintTransaction(
                        transactionId = transactionId,
                        amount = currentAmount,
                        originalRequesterRef = saleResult.requesterTransRefNum ?: transactionId,
                        provider = paymentProvider
                    )
                    AppLog.d(TAG, "Stored successful sale transaction: $lastSuccessfulSale")
                    _screenState.value = PaymentScreenState.TransactionSuccess(showReceipt = true)
                } else {
                    AppLog.d(TAG, "Payment failed: ${saleResult.message}")
                    reportPortalTransaction(
                        transactionId = transactionId,
                        status = "Failed",
                        amountMinor = currentAmount,
                        provider = paymentProvider,
                        message = saleResult.message ?: "Payment failed"
                    )
                    _screenState.value = PaymentScreenState.TransactionFailed(
                        errorMessage = saleResult.message ?: "Payment failed"
                    )
                    
                    // Auto-return to amount selection after 4 seconds
                    viewModelScope.launch {
                        delay(4000)
                        requestInitialScreen()
                    }
                }
                
                // Step 7: Send PAYMENT_RESULT to server (informational)
                socketManager.sendMessage(buildPlanetPaymentResultJson(saleResult, transactionId))
                
                isProcessingPayment = false
                isHandlingPaymentLocally = false // Reset flag when payment completes
            } catch (e: Exception) {
                AppLog.e(TAG, "Error performing sale transaction", e)
                _screenState.value = PaymentScreenState.TransactionFailed(
                    errorMessage = "Sale transaction error: ${e.message}"
                )
                isProcessingPayment = false
                isHandlingPaymentLocally = false // Reset flag on error
                
                // Auto-return to amount selection after 4 seconds
                viewModelScope.launch {
                    delay(4000)
                    requestInitialScreen()
                }
            }
        }
    }

    fun cancelPayment(isTimeout: Boolean = false) {
        val transactionId = currentTransactionId
        if (transactionId == null) {
            AppLog.d(TAG, "Cannot cancel payment: No active transaction ID")
            // Reset flags if no active transaction
            isProcessingPayment = false
            isHandlingPaymentLocally = false
            // If there's no active transaction but this is a timeout,
            // we should still show the screensaver if on amount screen
            if (isTimeout && _isOnAmountScreen.value) {
                AppLog.d(TAG, "No active transaction, but showing screensaver due to timeout")
                forceShowScreensaver()
            }
            return
        }
        
        // Reset flags when cancelling
        isProcessingPayment = false
        isHandlingPaymentLocally = false
        pendingCardCheckResult = null
        pendingNnsmartSale = null
        pendingCcvSale = null
        pendingSwitchioSale = null
        pendingPlanetSale = null
        pendingSwitchioCardVerify = null
        clearPendingRecoveryTransaction()

        if (isTimeout) {
            AppLog.d(TAG, "Canceling payment due to timeout")
        } else {
            AppLog.d(TAG, "User manually canceled payment")
            recordUserInteraction()
        }

        // Send cancel message
        val message = SocketMessage(
            messageType = "USER_ACTION",
            screen = "CANCEL",
            data = MessageData(
                errorMessage = if (isTimeout) "Session timed out due to inactivity" else null
            ),
            transactionId = transactionId,
            timestamp = System.currentTimeMillis()
        )

        sendMessage(message)

        // No need to send RESET message here if it's a timeout
        // We'll handle that when the screensaver is dismissed
        if (!isTimeout) {
            // Request to go back to amount selection
            val resetMessage = SocketMessage(
                messageType = "USER_ACTION",
                screen = "RESET",
                data = null,
                transactionId = transactionId,
                timestamp = System.currentTimeMillis()
            )

            sendMessage(resetMessage)
        }

        // If this is a timeout and we're on the amount screen, show the screensaver
        if (isTimeout && _isOnAmountScreen.value) {
            forceShowScreensaver()
        }
    }

    /**
     * Force the screensaver to show immediately
     * Used when the user presses the timeout/end button
     */
    fun forceShowScreensaver() {
        AppLog.d(TAG, "Forcing screensaver to show immediately")
        // Save current state
        if (_screenState.value !is PaymentScreenState.DeviceError &&
            _screenState.value !is PaymentScreenState.ConnectionError) {
            lastActiveState = _screenState.value
            AppLog.d(TAG, "Saved last active state: ${lastActiveState?.javaClass?.simpleName}")
        }

        // Pause all timers when showing screensaver
        timeoutManager.pauseTimersForScreensaver()

        // Show screensaver immediately
        _isScreensaverVisible.value = true
    }

    /**
     * Call this when the user dismisses the screensaver.
     */
    fun dismissScreensaver() {
        AppLog.d(TAG, "Dismissing screensaver")
        _isScreensaverVisible.value = false

        // Resume timers after screensaver is dismissed
        timeoutManager.resumeTimersAfterScreensaver()

        // Record user interaction to reset all timers
        recordUserInteraction()

        // Ensure socket connection is active
        ensureSocketConnection()

        // Request fresh amount selection screen to ensure UI is shown
        AppLog.d(TAG, "Requesting fresh amount selection screen after screensaver dismissal")
        requestInitialScreen()
    }

    /**
     * Requests the initial amount selection screen from the server.
     * Used after timeouts to ensure UI is properly displayed.
     */
    private fun requestInitialScreen() {
        AppLog.d(TAG, "Requesting initial screen from server")

        // Reset flags when starting a new screen flow
        isProcessingPayment = false
        isHandlingPaymentLocally = false
        pendingNnsmartSale = null
        pendingCcvSale = null
        pendingSwitchioSale = null
        pendingPlanetSale = null
        pendingSwitchioCardVerify = null
        cleanupRedeemState()
        clearPendingRecoveryTransaction()

        // Check if we have a valid server URL
        if (serverUrl.isEmpty()) {
            AppLog.e(TAG, "Cannot request initial screen: Server URL is empty")
            _screenState.value = buildConnectionError(ConnectionIssue.PAYMENT_SERVER_NOT_CONFIGURED)
            return
        }

        // Make sure socket is connected before attempting to send messages
        ensureSocketConnection()

        // Generate a new transaction ID for the new session
        val transactionId = UUID.randomUUID().toString()
        currentTransactionId = transactionId
        AppLog.d(TAG, "Generated new transaction ID for reset: $transactionId")

        // Send RESET message to get back to amount selection
        val resetMessage = SocketMessage(
            messageType = "USER_ACTION",
            screen = "RESET",
            data = null,
            transactionId = transactionId,
            timestamp = System.currentTimeMillis()
        )

        sendMessage(resetMessage)

        // Set a temporary loading state until we receive the response
        _screenState.value = PaymentScreenState.Loading
        AppLog.d(TAG, "Set temporary loading state while waiting for screen response")

        // Set up a fallback in case we don't get a response
        viewModelScope.launch {
            delay(3000) // Wait 3 seconds for response
            if (_screenState.value is PaymentScreenState.Loading) {
                AppLog.d(TAG, "No response received after 3 seconds, retrying connection")
                socketManager.disconnect() // Force disconnect to get a fresh connection
                delay(500) // Short delay
                socketManager.connect(serverUrl) // Reconnect
                delay(1000) // Wait for connection

                // Try again
                val newTransactionId = UUID.randomUUID().toString()
                currentTransactionId = newTransactionId
                val retryMessage = SocketMessage(
                    messageType = "USER_ACTION",
                    screen = "RESET",
                    data = null,
                    transactionId = newTransactionId,
                    timestamp = System.currentTimeMillis()
                )
                sendMessage(retryMessage)
            }
        }
    }

    /**
     * Ensures socket is connected before sending messages
     */
    private fun ensureSocketConnection() {
        // Check if we have a valid server URL
        if (serverUrl.isEmpty()) {
            AppLog.e(TAG, "Cannot ensure socket connection: Server URL is empty")
            _screenState.value = buildConnectionError(ConnectionIssue.PAYMENT_SERVER_NOT_CONFIGURED)
            return
        }

        // Use ensureConnected which handles all connection states properly
        socketManager.ensureConnected()
    }

    fun retryConnection() {
        AppLog.d(TAG, "Retrying connection to $serverUrl")
        _screenState.value = PaymentScreenState.Loading
        socketManager.disconnect()
        connectToBackend(serverUrl)
    }

    /**
     * Send a message to the server with better error handling
     */
    private fun sendMessage(message: SocketMessage) {
        val jsonMessage = messageAdapter.toJson(message)
        WireLog.controllerOut(jsonMessage)

        // Check if we have a valid server URL
        if (serverUrl.isEmpty()) {
            AppLog.e(TAG, "Cannot send message: Server URL is empty")
            _screenState.value = buildConnectionError(ConnectionIssue.PAYMENT_SERVER_NOT_CONFIGURED)
            return
        }

        // Ensure we're connected before sending
        if (!socketManager.isConnected()) {
            AppLog.w(TAG, "Socket not connected, attempting to reconnect to $serverUrl")
            socketManager.connect(serverUrl)

            // Queue up message send after a brief delay
            viewModelScope.launch {
                delay(1000) // Wait 1 second for connection
                if (socketManager.isConnected()) {
                    AppLog.d(TAG, "Connection established, sending delayed message")
                    val success = socketManager.sendMessage(jsonMessage)
                    AppLog.d(TAG, "Delayed message send result: $success")
                } else {
                    AppLog.e(TAG, "Still not connected, unable to send message")
                    _screenState.value = buildConnectionError(ConnectionIssue.PAYMENT_SERVER_UNREACHABLE)
                }
            }
            return
        }

        // Normal send if already connected
        val success = socketManager.sendMessage(jsonMessage)

        if (!success) {
            AppLog.e(TAG, "Failed to send message, checking connection")
            socketManager.ensureConnected()
        }
    }

    /**
     * Process messages received from the socket
     */

    // Add this enhanced logging to processSocketMessage method in PaymentViewModel.kt

    private fun processSocketMessage(jsonMessage: String) {
        try {
            val message = messageAdapter.fromJson(jsonMessage)

            message?.let {
                // Store transaction ID for response
                currentTransactionId = it.transactionId
                WireLog.flow(
                    "handling ${it.messageType}/${it.screen} txn=${it.transactionId} " +
                        "while on ${_screenState.value::class.simpleName}"
                )

                when (it.messageType) {
                    "SCREEN_CHANGE" -> {
                        AppLog.d(TAG, "Processing SCREEN_CHANGE to: ${it.screen}")
                        handleScreenChange(it)
                        AppLog.d(TAG, "New Screen State: ${_screenState.value::class.simpleName}")
                    }
                    "ERROR" -> {
                        AppLog.d(TAG, "Processing ERROR message")
                        handleError(it)
                    }
                    "STATUS_UPDATE" -> {
                        AppLog.d(TAG, "Processing STATUS_UPDATE message")
                        handleStatusUpdate(it)
                    }
                    "LIMIT_CHECK_RESULT" -> {
                        AppLog.d(TAG, "Processing LIMIT_CHECK_RESULT message")
                        handleLimitCheckResult(it)
                    }
                    "DEVICE_INFO" -> {
                        AppLog.d(TAG, "Processing DEVICE_INFO message")
                        handleDeviceInfo(it)
                    }
                    "RESTART_APP" -> {
                        AppLog.d(TAG, "Processing RESTART_APP message")
                        handleRestartApp(it)
                    }
                    "REFUND_REQUEST", "REVERSAL_REQUEST" -> {
                        AppLog.d(TAG, "Processing ${it.messageType} message")
                        handleRefundRequest(it)
                    }
                    "REDEEM_REQUEST" -> {
                        AppLog.d(TAG, "Processing REDEEM_REQUEST message")
                        handleRedeemRequest(it)
                    }
                    "REDEEM_BREAKDOWN" -> {
                        AppLog.d(TAG, "Processing REDEEM_BREAKDOWN message")
                        handleRedeemBreakdown(it)
                    }
                    "REDEEM_RESULT" -> {
                        AppLog.d(TAG, "Processing server REDEEM_RESULT message")
                        handleServerRedeemResult(it)
                    }
                    else -> {
                        AppLog.d(TAG, "Unhandled message type: ${it.messageType}")
                    }
                }
            }
        } catch (e: Exception) {
            // Handle parsing error
            AppLog.e(TAG, "Error parsing socket message: $jsonMessage", e)
            _screenState.value = PaymentScreenState.DeviceError("Invalid message format: ${e.message}")
            // Make sure to update screen state flag
            _isOnAmountScreen.value = false
        }
    }

    /**
     * Process screen state changes to track when we're on the amount selection screen
     */
    fun respondToReceiptQuestion(wantsReceipt: Boolean) {
        AppLog.d(TAG, "Receipt response: $wantsReceipt")
        recordUserInteraction()

        val transactionId = currentTransactionId
        if (transactionId == null) {
            AppLog.e(TAG, "Cannot respond to receipt question: No active transaction ID")
            return
        }

        val message = SocketMessage(
            messageType = "USER_ACTION",
            screen = "RECEIPT_RESPONSE",
            data = MessageData(
                selectionMethod = if (wantsReceipt) "YES" else "NO"
            ),
            transactionId = transactionId,
            timestamp = System.currentTimeMillis()
        )

        sendMessage(message)
    }

    // In PaymentViewModel.kt, update the handleScreenChange method

    private fun handleScreenChange(message: SocketMessage) {
        AppLog.d(TAG, "Handling screen change to: ${message.screen}")

        // Any server-driven screen change after a successful redemption means
        // the server took over the post-redeem flow (cash ticket printing etc.),
        // so the local fallback timer is no longer needed.
        if (redeemSuccessFallbackJob?.isActive == true) {
            AppLog.d(TAG, "Server drove screen change after redemption - cancelling redeem fallback timer")
            redeemSuccessFallbackJob?.cancel()
            redeemSuccessFallbackJob = null
        }

        // If we're currently showing LIMIT_ERROR, don't allow other screens to override it
        // unless the user has explicitly requested a reset (allowNavigationFromLimitError flag)
        if (_screenState.value is PaymentScreenState.LimitError && 
            message.screen != "LIMIT_ERROR" && 
            !allowNavigationFromLimitError) {
            AppLog.d(TAG, "Ignoring screen change to ${message.screen} - LIMIT_ERROR screen is active. User must interact to dismiss.")
            return
        }
        
        // Reset the flag after allowing navigation
        if (allowNavigationFromLimitError && message.screen == "AMOUNT_SELECT") {
            allowNavigationFromLimitError = false
            AppLog.d(TAG, "Navigation from LIMIT_ERROR allowed - resetting flag")
        }
        
        when (message.screen) {
            "INFO_SCREEN" -> {
                // Handle INFO_SCREEN request for device information
                AppLog.d(TAG, "Received INFO_SCREEN request")
                val requestType = message.data?.requestType
                if (requestType == "DEVICE_INFO") {
                    AppLog.d(TAG, "INFO_SCREEN request for device info, sending device IP and serial number")
                    sendDeviceIpAddress(message.transactionId)
                    sendDeviceSerialNumber(message.transactionId)
                } else {
                    AppLog.d(TAG, "INFO_SCREEN with unknown requestType: $requestType")
                }
                // Note: INFO_SCREEN does not change the current screen state
                return
            }
            "AMOUNT_SELECT" -> {
                clearPendingTicketPrintTransaction()
                val data = message.data
                if (data != null && data.amounts != null && data.currency != null) {
                    AppLog.d(TAG, "Changing to AMOUNT_SELECT screen with ${data.amounts.size} amounts")
                    _screenState.value = PaymentScreenState.AmountSelect(
                        amounts = data.amounts,
                        currency = data.currency,
                        showOtherOption = data.showOtherOption ?: true
                    )
                    // Set flag that we're on the amount selection screen
                    _isOnAmountScreen.value = true
                    AppLog.d(TAG, "Set isOnAmountScreen = true")
                } else {
                    AppLog.e(TAG, "Invalid data for AMOUNT_SELECT: $data")
                    _isOnAmountScreen.value = false
                }
            }
            "RECEIPT_QUESTION" -> {
                AppLog.d(TAG, "Received RECEIPT_QUESTION screen change")
                viewModelScope.launch {
                    val database = AppDatabase.getDatabase(getApplication())
                    val deviceInfo = database.deviceInfoDao().getDeviceInfo().first()
                    val requireCardReceipt = deviceInfo?.requireCardReceipt ?: true
                    val isDccSale = dccReceiptTransactionId != null &&
                        dccReceiptTransactionId == currentTransactionId

                    if (isDccSale) {
                        // Planet: a receipt is mandatory for DCC; the cardholder may not decline it.
                        AppLog.d(TAG, "DCC sale - receipt is mandatory, answering YES without asking")
                        respondToReceiptQuestion(wantsReceipt = true)
                    } else if (requireCardReceipt) {
                        AppLog.d(TAG, "requireCardReceipt is enabled - showing receipt question screen")
                        _screenState.value = PaymentScreenState.ReceiptQuestion(showGif = true)
                    } else {
                        AppLog.d(TAG, "requireCardReceipt is disabled - skipping receipt question screen")
                        // Automatically respond NO to receipt question
                        respondToReceiptQuestion(wantsReceipt = false)
                    }
                    _isOnAmountScreen.value = false
                }
            }
            "TIMEOUT" -> {
                AppLog.d(TAG, "Server sent TIMEOUT message - showing timeout screen")
                _screenState.value = PaymentScreenState.Timeout
                _isOnAmountScreen.value = false

                // REMOVE THIS AUTO-NAVIGATION - let the server control the flow
                // The server will send the next screen when ready
                // Don't auto-navigate after timeout
            }
            "KEYPAD" -> {
                AppLog.d(TAG, "Changing to KEYPAD screen")
                _screenState.value = PaymentScreenState.KeypadEntry(
                    currency = message.data?.currency ?: "£",
                    minAmount = 10, // Default
                    maxAmount = 300 // Default
                )
                // Not on amount selection screen anymore
                _isOnAmountScreen.value = false
                AppLog.d(TAG, "Set isOnAmountScreen = false")
            }
            "PAYMENT_METHOD" -> {
                // Check if we're handling payment locally (YASPA disabled) - if so, ignore this screen change
                if (isHandlingPaymentLocally) {
                    AppLog.d(TAG, "Ignoring PAYMENT_METHOD screen - handling payment locally (YASPA disabled)")
                    return
                }
                
                AppLog.d(TAG, "Changing to PAYMENT_METHOD screen with amount: $currentAmount")
                _screenState.value = PaymentScreenState.PaymentMethodSelect(
                    methods = message.data?.methods ?: listOf("DEBIT_CARD", "PAY_BY_BANK"),
                    amount = currentAmount, // Use stored amount (includes fee)
                    currency = message.data?.currency ?: "£",
                    allowCancel = message.data?.allowCancel ?: true
                )
                // Not on amount selection screen anymore
                _isOnAmountScreen.value = false
                AppLog.d(TAG, "Set isOnAmountScreen = false")
            }
            "QR_CODE" -> {
                val paymentUrl = message.data?.paymentUrl ?: ""
                AppLog.d(TAG, "Changing to QR_CODE screen with URL: $paymentUrl")
                _screenState.value = PaymentScreenState.QrCodeDisplay(
                    paymentUrl = paymentUrl
                )
                _isOnAmountScreen.value = false
            }
            "PROCESSING" -> {
                AppLog.d(TAG, "Changing to PROCESSING screen")
                _screenState.value = PaymentScreenState.Processing
                _isOnAmountScreen.value = false
            }
            "SUCCESS" -> {
                AppLog.d(TAG, "Changing to SUCCESS screen")
                _screenState.value = PaymentScreenState.TransactionSuccess(
                    showReceipt = true
                )
                _isOnAmountScreen.value = false
            }
            "FAILED" -> {
                val errorMessage = message.data?.errorMessage
                AppLog.d(TAG, "Changing to FAILED screen with error: $errorMessage")
                _screenState.value = PaymentScreenState.TransactionFailed(
                    errorMessage = errorMessage
                )
                _isOnAmountScreen.value = false
                
                // Auto-return to amount selection after 4 seconds (don't wait for server)
                viewModelScope.launch {
                    delay(4000)
                    requestInitialScreen()
                }
            }
            "LIMIT_ERROR" -> {
                val errorMessage = message.data?.errorMessage ?: "Limit exceeded"
                AppLog.d(TAG, "Changing to LIMIT_ERROR screen with message: $errorMessage")
                _screenState.value = PaymentScreenState.LimitError(
                    errorMessage = errorMessage
                )
                _isOnAmountScreen.value = false
                // Reset navigation flag when showing LIMIT_ERROR
                allowNavigationFromLimitError = false
            }
            "PRINT_TICKET" -> {
                AppLog.d(TAG, "Changing to PRINT_TICKET screen")
                _screenState.value = PaymentScreenState.PrintingTicket
                _isOnAmountScreen.value = false
            }
            "COLLECT_TICKET" -> {
                AppLog.d(TAG, "Changing to COLLECT_TICKET screen")
                clearPendingTicketPrintTransaction()
                _screenState.value = PaymentScreenState.CollectTicket
                _isOnAmountScreen.value = false
            }
            "THANK_YOU" -> {
                AppLog.d(TAG, "Changing to THANK_YOU screen")
                clearPendingTicketPrintTransaction()
                _screenState.value = PaymentScreenState.ThankYou
                _isOnAmountScreen.value = false
            }
            // Add this case to the when statement in handleScreenChange method
            "REFUND_PROCESSING" -> {
                AppLog.d(TAG, "Changing to REFUND_PROCESSING screen")
                _screenState.value = PaymentScreenState.RefundProcessing(
                    errorMessage = message.data?.errorMessage
                )
                _isOnAmountScreen.value = false
            }
            "PRINTER_ERROR" -> {
                val errorMessage = message.data?.errorMessage ?: "Printer error occurred"
                AppLog.d(TAG, "Changing to PRINTER_ERROR screen with message: $errorMessage")
                _screenState.value = PaymentScreenState.DeviceError(
                    errorMessage = errorMessage
                )
                _isOnAmountScreen.value = false
            }
            "DEVICE_ERROR" -> {
                val errorMessage = message.data?.errorMessage ?: "Unknown device error"
                AppLog.d(TAG, "Changing to DEVICE_ERROR screen with message: $errorMessage")
                _screenState.value = PaymentScreenState.DeviceError(
                    errorMessage = errorMessage
                )
                _isOnAmountScreen.value = false
            }
        }
    }

    /**
     * Handle error messages from the server
     */
    private fun handleError(message: SocketMessage) {
        val errorMessage = message.data?.errorMessage
        AppLog.d(TAG, "Handling error: $errorMessage")
        _screenState.value = PaymentScreenState.TransactionFailed(
            errorMessage = errorMessage
        )
        _isOnAmountScreen.value = false
        
        // Auto-return to amount selection after 4 seconds (don't wait for server)
        viewModelScope.launch {
            delay(4000)
            requestInitialScreen()
        }
    }

    /**
     * Handle status update messages from the server
     */
    private fun handleStatusUpdate(message: SocketMessage) {
        // Handle status updates if needed
        AppLog.d(TAG, "Received status update: ${message.data}")
    }
    
    /**
     * Handle LIMIT_CHECK_RESULT messages from the server.
     * This is received after sending CARD_CHECK_RESULT for daily limit validation.
     */
    private fun handleLimitCheckResult(message: SocketMessage) {
        AppLog.d(TAG, "Received LIMIT_CHECK_RESULT: screen=${message.screen}")
        
        val transactionId = message.transactionId
        if (transactionId != currentTransactionId) {
            AppLog.w(TAG, "LIMIT_CHECK_RESULT transaction ID mismatch: expected=$currentTransactionId, received=$transactionId")
        }
        
        // Check if limit check was approved or rejected
        val approved = message.screen == "APPROVED" || message.screen.uppercase() == "APPROVED"
        
        if (approved) {
            AppLog.d(TAG, "Limit check approved, continuing payment")
            continuePaymentAfterLimitCheck(true, transactionId, "")
        } else {
            val errorMessage = message.data?.errorMessage ?: "Daily spending limit exceeded"
            AppLog.d(TAG, "Limit check rejected: $errorMessage")
            continuePaymentAfterLimitCheck(false, transactionId, errorMessage)
        }
    }
    
    /**
     * Handle DEVICE_INFO messages from the server.
     * This can be either device configuration from server or device info request.
     */
    private fun handleDeviceInfo(message: SocketMessage) {
        AppLog.d(TAG, "Received DEVICE_INFO message")
        
        val data = message.data
        if (data == null) {
            AppLog.d(TAG, "DEVICE_INFO message has no data, ignoring")
            return
        }
        
        // Check if this is device configuration from server
        // Device configuration includes: minTransactionLimit, maxTransactionLimit, etc.
        val hasConfig = data.minTransactionLimit != null ||
                       data.maxTransactionLimit != null ||
                       data.transactionFeeType != null ||
                       data.yaspaEnabled != null ||
                       data.paymentProvider != null ||
                       data.requireCardReceipt != null ||
                       data.nnsmartPostProcessingLimit != null ||
                       data.planetPostProcessingLimit != null

        if (hasConfig) {
            AppLog.d(TAG, "Received device configuration from server")
            viewModelScope.launch {
                try {
                    val database = AppDatabase.getDatabase(getApplication())
                    val deviceInfoDao = database.deviceInfoDao()
                    
                    // Get existing device info or create new one
                    val existingInfo = deviceInfoDao.getDeviceInfo().first()
                    
                    val deviceInfo = app.sst.pinto.data.DeviceInfo(
                        id = 1,
                        currency = data.currency ?: existingInfo?.currency ?: "GBP",
                        minTransactionLimit = data.minTransactionLimit ?: existingInfo?.minTransactionLimit ?: 10.0,
                        maxTransactionLimit = data.maxTransactionLimit ?: existingInfo?.maxTransactionLimit ?: 300.0,
                        transactionFeeType = data.transactionFeeType ?: existingInfo?.transactionFeeType ?: "FIXED",
                        transactionFeeValue = data.transactionFeeValue ?: existingInfo?.transactionFeeValue ?: 0.50,
                        yaspaEnabled = data.yaspaEnabled ?: existingInfo?.yaspaEnabled ?: true,
                        paymentProvider = data.paymentProvider ?: existingInfo?.paymentProvider ?: "nnsmart",
                        requireCardReceipt = data.requireCardReceipt ?: existingInfo?.requireCardReceipt ?: true,
                        // Prefer server value when provided; otherwise keep local Settings toggle.
                        // Defaults to post-processing (true) on first configuration.
                        nnsmartPostProcessingLimit = data.nnsmartPostProcessingLimit
                            ?: existingInfo?.nnsmartPostProcessingLimit
                            ?: true,
                        // Planet defaults to pre-processing (card check first).
                        planetPostProcessingLimit = data.planetPostProcessingLimit
                            ?: existingInfo?.planetPostProcessingLimit
                            ?: false
                    )
                    
                    // Use insertDeviceInfo which handles both insert and update (REPLACE strategy)
                    deviceInfoDao.insertDeviceInfo(deviceInfo)
                    
                    AppLog.d(
                        TAG,
                        "Device configuration saved: provider=${deviceInfo.paymentProvider}, " +
                            "yaspaEnabled=${deviceInfo.yaspaEnabled}, " +
                            "postProcessingLimit=${deviceInfo.nnsmartPostProcessingLimit}, " +
                            "planetPostProcessingLimit=${deviceInfo.planetPostProcessingLimit}"
                    )

                    // Visible confirmation that config arrived from the server
                    val toastMsg = "Config received: ${deviceInfo.paymentProvider}" +
                        " | ${deviceInfo.minTransactionLimit.toInt()}-${deviceInfo.maxTransactionLimit.toInt()}" +
                        " ${deviceInfo.currency}" +
                        " | pre=${
                            if (deviceInfo.paymentProvider.equals("integra", ignoreCase = true)) {
                                !deviceInfo.planetPostProcessingLimit
                            } else {
                                !deviceInfo.nnsmartPostProcessingLimit
                            }
                        }"
                    Toast.makeText(getApplication(), toastMsg, Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    AppLog.e(TAG, "Error saving device configuration", e)
                }
            }
        } else {
            AppLog.d(TAG, "DEVICE_INFO message is not configuration, may be device info request")
            // Could be a request for device info - handle if needed
        }
    }
    
    /**
     * Handle RESTART_APP messages from the server.
     * This instructs the client to restart the application.
     */
    private fun handleRestartApp(message: SocketMessage) {
        AppLog.d(TAG, "Received RESTART_APP message, closing application")
        
        // Close the application
        viewModelScope.launch {
            delay(500) // Small delay to ensure message is logged
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
    
    /**
     * Send device IP address to the server.
     * Called automatically when INFO_SCREEN message with requestType="DEVICE_INFO" is received.
     * 
     * @param transactionId Optional transaction ID for correlation (from INFO_SCREEN request)
     */
    private fun sendDeviceIpAddress(transactionId: String? = null) {
        viewModelScope.launch {
            try {
                val ipAddress = getDeviceIpAddress()
                val txId = transactionId ?: currentTransactionId ?: UUID.randomUUID().toString()
                
                AppLog.d(TAG, "Sending device IP address: $ipAddress")
                
                val messageJson = """
                    {
                        "messageType": "DEVICE_INFO",
                        "screen": "DEVICE_IP",
                        "data": {
                            "deviceIpAddress": "$ipAddress"
                        },
                        "transactionId": "$txId",
                        "timestamp": ${System.currentTimeMillis()}
                    }
                """.trimIndent()
                
                socketManager.sendMessage(messageJson)
            } catch (e: Exception) {
                AppLog.e(TAG, "Error sending device IP address", e)
            }
        }
    }
    
    /**
     * Send device serial number to the server.
     * Called automatically when INFO_SCREEN message with requestType="DEVICE_INFO" is received.
     * 
     * @param transactionId Optional transaction ID for correlation (from INFO_SCREEN request)
     */
    private fun sendDeviceSerialNumber(transactionId: String? = null) {
        viewModelScope.launch {
            try {
                val serialNumber = getDeviceSerialNumber()
                val txId = transactionId ?: currentTransactionId ?: UUID.randomUUID().toString()
                
                AppLog.d(TAG, "Sending device serial number: $serialNumber")
                
                val messageJson = """
                    {
                        "messageType": "DEVICE_INFO",
                        "screen": "DEVICE_SERIAL",
                        "data": {
                            "deviceSerialNumber": "$serialNumber"
                        },
                        "transactionId": "$txId",
                        "timestamp": ${System.currentTimeMillis()}
                    }
                """.trimIndent()
                
                socketManager.sendMessage(messageJson)
            } catch (e: Exception) {
                AppLog.e(TAG, "Error sending device serial number", e)
            }
        }
    }

    /**
     * Handle refund/reversal request from server.
     * This processes a REFUND_REQUEST or REVERSAL_REQUEST message and performs
     * a sale reversal on the payment terminal.
     */
    private fun handleRefundRequest(message: SocketMessage) {
        viewModelScope.launch {
            try {
                AppLog.d(TAG, "Handling refund/reversal request: ${message.messageType}")
                
                // Resolve which stored sale this request targets. When the
                // server names a transaction we void *that* sale, not merely the
                // latest one, so a newer sale taken between a failed void and
                // its retry is never reversed by mistake.
                val target = resolveSaleForReversal(message)
                val lastSale = target.sale
                if (lastSale == null) {
                    val (errorCode, errorMessage) = if (target.identifiersProvided) {
                        // Server named a transaction we have no record of. Do NOT
                        // fall back to the most recent sale - that could void a
                        // different (fresh) customer's transaction.
                        "NO_MATCHING_SALE" to
                            "No stored sale matches the requested transaction; refusing to reverse a different sale"
                    } else {
                        "NO_SALE_FOUND" to "No successful sale transaction found to reverse"
                    }
                    AppLog.e(
                        TAG,
                        "Cannot process refund/reversal: $errorCode " +
                            "(serverTxId=${message.data?.originalTransactionId} " +
                            "serverRef=${message.data?.originalRequesterTransRefNum})"
                    )
                    // Send error response
                    val errorResponse = """
                        {
                            "messageType": "REVERSAL_RESULT",
                            "screen": "FAILED",
                            "data": {
                                "errorCode": "$errorCode",
                                "errorMessage": "$errorMessage",
                                "paymentDetails": {}
                            },
                            "transactionId": "${message.transactionId}",
                            "timestamp": ${System.currentTimeMillis()}
                        }
                    """.trimIndent()
                    socketManager.sendMessage(errorResponse)
                    return@launch
                }
                
                // Get payment provider
                val database = AppDatabase.getDatabase(getApplication())
                val deviceInfo = database.deviceInfoDao().getDeviceInfo().first()
                val paymentProvider = deviceInfo?.paymentProvider?.lowercase() ?: "nnsmart"
                
                // Transaction details come from the resolved target sale (which
                // already matches the server's identifiers when supplied).
                val originalTransactionId = message.data?.originalTransactionId ?: lastSale.transactionId
                val backendOriginalRequesterRef = message.data?.originalRequesterTransRefNum
                val localOriginalRequesterRef = lastSale.requesterTransRefNum
                // For NNSmart, prefer the resolved sale's terminal trx id because
                // cancellation `payment_ref` must be the terminal-side id.
                val originalRequesterRef = if (
                    NNSmartPaymentManager.isNNSmartProvider(paymentProvider) ||
                    SwitchioPaymentManager.isSwitchioProvider(paymentProvider)
                ) {
                    localOriginalRequesterRef ?: backendOriginalRequesterRef ?: lastSale.transactionId
                } else {
                    backendOriginalRequesterRef ?: localOriginalRequesterRef ?: lastSale.transactionId
                }
                val reversalAmount = message.data?.reversalAmount ?: lastSale.amount
                
                if (
                    (
                        NNSmartPaymentManager.isNNSmartProvider(paymentProvider) ||
                        SwitchioPaymentManager.isSwitchioProvider(paymentProvider)
                    ) &&
                    !backendOriginalRequesterRef.isNullOrBlank() &&
                    !localOriginalRequesterRef.isNullOrBlank() &&
                    backendOriginalRequesterRef != localOriginalRequesterRef
                ) {
                    AppLog.w(
                        TAG,
                        "Reversal ref mismatch ($paymentProvider): backendRef=$backendOriginalRequesterRef localRef=$localOriginalRequesterRef - using localRef"
                    )
                }
                
                AppLog.d(
                    TAG,
                    "Processing reversal: provider=$paymentProvider originalTxId=$originalTransactionId originalRef=$originalRequesterRef amount=$reversalAmount"
                )
                
                // Decide whether to surface the refund UI. A server-initiated
                // reversal can target an OLDER transaction while a new (or no)
                // customer is at the device. Taking over the screen would
                // confuse them, and the NNSmart CANCELLATION needs no customer
                // interaction, so when the device is idle we cancel silently in
                // the background and leave the current screen untouched. The
                // server is still told the outcome via REVERSAL_RESULT below.
                val deviceIdle = _isOnAmountScreen.value || _isScreensaverVisible.value
                if (!deviceIdle) {
                    // A transaction flow is still on screen (the same customer
                    // who just paid) - show refund progress as before.
                    _isScreensaverVisible.value = false
                    _screenState.value = PaymentScreenState.RefundProcessing(
                        errorMessage = "Processing refund..."
                    )
                } else {
                    AppLog.d(TAG, "Device idle - processing reversal silently in background")
                }
                
                val amountFormatted = String.format("%.2f", reversalAmount.toDouble())
                // Generate a unique reversal reference (don't double-prefix if transactionId already has REVERSAL_)
                val reversalRef = if (message.transactionId.startsWith("REVERSAL_")) {
                    message.transactionId // Already has REVERSAL_ prefix
                } else {
                    "REVERSAL_${message.transactionId}"
                }
                
                // Perform reversal
                AppLog.d(TAG, "Performing sale reversal with provider: $paymentProvider, amount: $amountFormatted")
                val reversalResult = when {
                    paymentProvider == "mock" -> MockPaymentManager.performSaleReversal(
                        amountFormatted = amountFormatted,
                        requesterRef = reversalRef,
                        originalRequesterRef = originalRequesterRef
                    )
                    CcvPaymentManager.isCcvProvider(paymentProvider) -> {
                        // CCV refund is addressed by the ORIGINAL sale's stored
                        // approvalCode / receiptNumber (OPI-NL), which live in the
                        // resolved sale record. This is the portal-triggered
                        // later-stage refund path.
                        val refund = CcvPaymentManager.performRefund(
                            context = getApplication(),
                            amountFormatted = amountFormatted,
                            requesterRef = reversalRef,
                            currencyAlphaCode = lastSale.currency,
                            approvalCode = lastSale.approvalCode,
                            receiptNumber = lastSale.receiptNumber
                        )
                        app.sst.pinto.payment.PlanetPaymentResult(
                            success = refund.success,
                            resultCode = if (refund.success) "A" else (refund.resultCode ?: "REFUND_FAILED"),
                            bankResultCode = if (refund.success) "00" else null,
                            message = if (refund.success) "REFUND_APPROVED" else (refund.message ?: "Refund failed on terminal"),
                            requesterTransRefNum = reversalRef,
                            rawOptions = emptyMap()
                        )
                    }
                    NNSmartPaymentManager.isNNSmartProvider(paymentProvider) -> {
                        // NNSmart uses CANCELLATION (not a separate reversal
                        // request) and needs the original transaction id as
                        // payment_ref. Use the stored originalRequesterRef
                        // which, for NNSmart, holds trxData.id.
                        val ok = NNSmartPaymentManager.performCancel(
                            context = getApplication(),
                            requesterRef = reversalRef,
                            originalTrxUniqueId = originalRequesterRef
                        )
                        app.sst.pinto.payment.PlanetPaymentResult(
                            success = ok,
                            resultCode = if (ok) "A" else "REVERSAL_FAILED",
                            bankResultCode = if (ok) "00" else null,
                            message = if (ok) "REVERSAL_APPROVED" else "Reversal failed on terminal",
                            requesterTransRefNum = reversalRef,
                            rawOptions = emptyMap()
                        )
                    }
                    SwitchioPaymentManager.isSwitchioProvider(paymentProvider) -> {
                        val reversal = SwitchioPaymentManager.performReversal(
                            originalTransactionId = originalRequesterRef,
                            transactionId = UUID.randomUUID().toString()
                        )
                        app.sst.pinto.payment.PlanetPaymentResult(
                            success = reversal.success,
                            resultCode = if (reversal.success) "A" else (reversal.resultCode ?: "REVERSAL_FAILED"),
                            bankResultCode = if (reversal.success) "00" else null,
                            message = if (reversal.success) {
                                "REVERSAL_APPROVED"
                            } else {
                                reversal.message ?: "Reversal failed on terminal"
                            },
                            requesterTransRefNum = reversalRef,
                            rawOptions = emptyMap()
                        )
                    }
                    else -> PlanetPaymentManager.performSaleReversal(
                        amountFormatted = amountFormatted,
                        requesterRef = reversalRef,
                        originalRequesterRef = originalRequesterRef
                    )
                }
                
                // Send reversal result to server
                val reversalResultJson = """
                    {
                        "messageType": "REVERSAL_RESULT",
                        "screen": "${if (reversalResult.success) "SUCCESS" else "FAILED"}",
                        "data": {
                            "errorCode": ${if (reversalResult.resultCode != null) "\"${reversalResult.resultCode}\"" else "null"},
                            "errorMessage": ${if (reversalResult.message != null) "\"${reversalResult.message}\"" else "null"},
                            "paymentDetails": {
                                "Result": "${reversalResult.resultCode ?: ""}",
                                "BankResultCode": "${reversalResult.bankResultCode ?: ""}",
                                "Message": "${reversalResult.message ?: ""}",
                                "RequesterTransRefNum": "$reversalRef",
                                "OriginalRequesterTransRefNum": "$originalRequesterRef"
                            },
                            "originalTransactionId": "$originalTransactionId",
                            "reversalAmount": $reversalAmount
                        },
                        "transactionId": "${message.transactionId}",
                        "timestamp": ${System.currentTimeMillis()}
                    }
                """.trimIndent()
                
                socketManager.sendMessage(reversalResultJson)
                
                if (reversalResult.success) {
                    AppLog.d(TAG, "Reversal successful - showing success screen")
                    // Forget the reversed sale only (keeps any newer sale's
                    // record intact) so it cannot be reversed twice.
                    forgetSuccessfulSale(lastSale)
                    // Clear pending ticket-print recovery marker so a future
                    // disconnect/restart cannot re-target this already
                    // reversed transaction.
                    clearPendingTicketPrintTransaction()
                    clearPendingRecoveryTransaction()
                    
                    if (deviceIdle) {
                        // No active customer (idle on amount-select or
                        // screensaver). The cancel ran silently; leave the
                        // screen as-is so a new user is never shown a refund
                        // result for someone else's sale.
                        AppLog.d(TAG, "Reversal succeeded silently (device idle); leaving current screen")
                    } else {
                        // Ensure screensaver is hidden to show success screen
                        _isScreensaverVisible.value = false
                        timeoutManager.recordUserInteraction() // Reset timeout timer

                        // Show success screen briefly, then return to amount selection
                        _screenState.value = PaymentScreenState.TransactionSuccess(showReceipt = false)
                        _isOnAmountScreen.value = false
                        AppLog.d(TAG, "Screen state changed to TransactionSuccess after reversal")

                        // After showing success, automatically request initial screen from server
                        viewModelScope.launch {
                            delay(3000) // Show success for 3 seconds
                            AppLog.d(TAG, "Requesting initial screen after successful reversal")
                            requestInitialScreen()
                        }
                    }
                } else {
                    AppLog.e(TAG, "Reversal failed: ${reversalResult.message}")
                    if (deviceIdle) {
                        // Cancel failed but no customer is waiting. The server
                        // was notified via REVERSAL_RESULT above and owns the
                        // retry; don't disturb a new user's screen.
                        AppLog.w(TAG, "Reversal failed silently (device idle); server notified via REVERSAL_RESULT")
                    } else {
                        // Ensure screensaver is hidden to show failed screen
                        _isScreensaverVisible.value = false
                        timeoutManager.recordUserInteraction() // Reset timeout timer

                        // Show failed screen, then return to amount selection
                        _screenState.value = PaymentScreenState.TransactionFailed(
                            errorMessage = reversalResult.message ?: "Reversal failed"
                        )
                        _isOnAmountScreen.value = false
                        AppLog.d(TAG, "Screen state changed to TransactionFailed after reversal")

                        // Auto-return to amount selection after 4 seconds
                        viewModelScope.launch {
                            delay(4000)
                            requestInitialScreen()
                        }
                    }
                }
                
            } catch (e: Exception) {
                AppLog.e(TAG, "Error processing refund/reversal request", e)
                // Send error response
                val errorResponse = """
                    {
                        "messageType": "REVERSAL_RESULT",
                        "screen": "FAILED",
                        "data": {
                            "errorCode": "EXCEPTION",
                            "errorMessage": "Error processing reversal: ${e.message}",
                            "paymentDetails": {}
                        },
                        "transactionId": "${message.transactionId}",
                        "timestamp": ${System.currentTimeMillis()}
                    }
                """.trimIndent()
                socketManager.sendMessage(errorResponse)
            }
        }
    }

    // =====================================================================
    // Ticket redemption (redeem to bank account)
    // =====================================================================

    /**
     * Handle REDEEM_REQUEST from the server: a customer inserted a redeemable
     * ticket and the backend wants the device to run the redemption flow.
     *
     * The device acknowledges by showing the RedeemInitiated screen and waits
     * for REDEEM_BREAKDOWN with the bank/cash split. If the device is mid
     * payment (or mid another redemption) it refuses with DEVICE_BUSY so the
     * backend keeps the ticket unredeemed.
     */
    private fun handleRedeemRequest(message: SocketMessage) {
        audit("REDEEM_REQUEST received tx=${message.transactionId} ticket=${message.data?.ticketId}")

        if (isProcessingPayment || isProcessingRedeem) {
            AppLog.w(TAG, "REDEEM_REQUEST while busy (payment=$isProcessingPayment redeem=$isProcessingRedeem) - refusing")
            val busyResult = buildRedeemResultJson(
                success = false,
                transactionId = message.transactionId,
                errorCode = "DEVICE_BUSY",
                errorMessage = "Device is busy with another transaction",
                ticketId = message.data?.ticketId,
                bankRedeemAmount = 0,
                cashRedeemAmount = 0
            )
            socketManager.sendMessage(busyResult)
            return
        }

        isProcessingRedeem = true
        activeRedemption = ActiveRedemption(
            transactionId = message.transactionId,
            ticketId = message.data?.ticketId,
            currency = message.data?.currency ?: "£"
        )
        currentTransactionId = message.transactionId

        // Wake the device: redemption may start while the screensaver is up.
        _isScreensaverVisible.value = false
        timeoutManager.recordUserInteraction()

        _screenState.value = PaymentScreenState.RedeemInitiated(
            ticketId = message.data?.ticketId,
            currency = message.data?.currency ?: "£"
        )
        _isOnAmountScreen.value = false

        // If neither REDEEM_BREAKDOWN nor a server REDEEM_RESULT arrives, fail
        // locally so the device is not stuck on the verifying screen.
        redeemTimeoutJob?.cancel()
        redeemTimeoutJob = viewModelScope.launch {
            delay(REDEEM_BREAKDOWN_TIMEOUT_MS)
            if (_screenState.value is PaymentScreenState.RedeemInitiated) {
                AppLog.e(TAG, "REDEEM_BREAKDOWN/REDEEM_RESULT not received within ${REDEEM_BREAKDOWN_TIMEOUT_MS}ms - failing redemption")
                val redemption = activeRedemption
                sendCriticalRedeemResult(
                    buildRedeemResultJson(
                        success = false,
                        transactionId = redemption?.transactionId ?: message.transactionId,
                        errorCode = "BREAKDOWN_TIMEOUT",
                        errorMessage = "Redemption breakdown not received from server",
                        ticketId = redemption?.ticketId,
                        bankRedeemAmount = 0,
                        cashRedeemAmount = 0
                    ),
                    reason = "Redeem breakdown timeout"
                )
                // Clear tracking without cancelling this job (would abort recovery).
                clearRedeemTracking()
                showRedeemFailedAndReturnHome(
                    errorMessage = "Ticket could not be redeemed. Please try again or visit the cashier desk.",
                    displayDurationMs = 5000
                )
            }
        }
    }

    /**
     * Server-driven redemption outcome. Redemption runs on the backend; the
     * device only shows the result screen then returns home.
     *
     * FAILED  → RedeemFailed briefly, then REQUEST home/AMOUNT_SELECT
     * SUCCESS → RedeemSuccess; server may continue with PRINT_TICKET etc.
     */
    private fun handleServerRedeemResult(message: SocketMessage) {
        val screen = message.screen.uppercase()
        val errorCode = message.data?.errorCode
        val errorMessage = message.data?.errorMessage
        audit(
            "Server REDEEM_RESULT received tx=${message.transactionId} " +
                "screen=$screen code=$errorCode msg=$errorMessage"
        )

        redeemTimeoutJob?.cancel()
        redeemTimeoutJob = null
        redeemSuccessFallbackJob?.cancel()
        redeemSuccessFallbackJob = null

        when (screen) {
            "SUCCESS" -> {
                val bank = message.data?.bankRedeemAmount
                    ?: activeRedemption?.bankAmount
                    ?: 0
                val cash = message.data?.cashRedeemAmount
                    ?: activeRedemption?.cashAmount
                    ?: 0
                val currency = message.data?.currency
                    ?: activeRedemption?.currency
                    ?: "£"
                cleanupRedeemState()
                _screenState.value = PaymentScreenState.RedeemSuccess(
                    bankAmount = bank,
                    cashAmount = cash,
                    currency = currency
                )
                _isOnAmountScreen.value = false
                // Server should drive PRINT_TICKET / THANK_YOU / AMOUNT_SELECT;
                // fall back if it goes silent.
                redeemSuccessFallbackJob = viewModelScope.launch {
                    delay(20000)
                    AppLog.w(TAG, "No server screen change after server REDEEM_RESULT SUCCESS - returning home")
                    requestInitialScreen()
                }
            }
            else -> {
                // FAILED or any non-SUCCESS result from the server
                cleanupRedeemState()
                val displayMessage = when {
                    !errorMessage.isNullOrBlank() && !errorCode.isNullOrBlank() ->
                        "Your ticket has NOT been redeemed.\n\n$errorMessage"
                    !errorMessage.isNullOrBlank() ->
                        "Your ticket has NOT been redeemed.\n\n$errorMessage"
                    else ->
                        "Ticket could not be redeemed. Please try again or visit the cashier desk."
                }
                showRedeemFailedAndReturnHome(
                    errorMessage = displayMessage,
                    displayDurationMs = 5000
                )
            }
        }
    }

    /** Show RedeemFailed, then navigate back to the home (amount) screen. */
    private fun showRedeemFailedAndReturnHome(
        errorMessage: String,
        displayDurationMs: Long = 5000
    ) {
        _screenState.value = PaymentScreenState.RedeemFailed(errorMessage = errorMessage)
        _isOnAmountScreen.value = false
        viewModelScope.launch {
            delay(displayDurationMs)
            requestInitialScreen()
        }
    }

    /**
     * Handle REDEEM_BREAKDOWN from the server: how much of the ticket can go
     * to the customer's bank card (card spend + winnings) and how much must be
     * collected in cash (cash spend - AML rule). Shows the confirmation screen
     * where the customer chooses Continue or Cancel.
     */
    private fun handleRedeemBreakdown(message: SocketMessage) {
        val bank = message.data?.bankRedeemAmount
        val cash = message.data?.cashRedeemAmount
        audit("REDEEM_BREAKDOWN received tx=${message.transactionId} bank=$bank cash=$cash")

        var redemption = activeRedemption
        if (redemption == null || redemption.transactionId != message.transactionId) {
            // Be tolerant: a breakdown without (or not matching) a prior request
            // can happen if the app restarted mid-redemption. Adopt it.
            AppLog.w(TAG, "REDEEM_BREAKDOWN without matching REDEEM_REQUEST - adopting tx=${message.transactionId}")
            redemption = ActiveRedemption(
                transactionId = message.transactionId,
                ticketId = message.data?.ticketId
            )
            activeRedemption = redemption
            isProcessingRedeem = true
            currentTransactionId = message.transactionId
        }
        redeemTimeoutJob?.cancel()

        val bankAmount = bank ?: 0
        val cashAmount = cash ?: 0
        val totalAmount = message.data?.totalRedeemAmount ?: (bankAmount + cashAmount)

        // Validate the breakdown before showing it to the customer.
        if (bankAmount < 0 || cashAmount < 0 || totalAmount <= 0 || bankAmount + cashAmount != totalAmount) {
            AppLog.e(TAG, "Invalid redemption breakdown: bank=$bankAmount cash=$cashAmount total=$totalAmount")
            sendCriticalRedeemResult(
                buildRedeemResultJson(
                    success = false,
                    transactionId = redemption.transactionId,
                    errorCode = "INVALID_BREAKDOWN",
                    errorMessage = "Invalid redemption breakdown received (bank=$bankAmount cash=$cashAmount total=$totalAmount)",
                    ticketId = redemption.ticketId,
                    bankRedeemAmount = bankAmount,
                    cashRedeemAmount = cashAmount
                ),
                reason = "Invalid redeem breakdown"
            )
            cleanupRedeemState()
            showRedeemFailedAndReturnHome(
                errorMessage = "Ticket could not be redeemed. Please visit the cashier desk.",
                displayDurationMs = 5000
            )
            return
        }

        // Nothing bank-redeemable: the customer's EXISTING ticket is already
        // valid for cash at the cashier desk, so redeeming (and printing a
        // replacement cash ticket) is pointless. Refuse so the ticket stays
        // valid and point the customer to the cashier.
        if (bankAmount <= 0) {
            AppLog.w(TAG, "Redemption has no bank portion - refusing (existing ticket already cashable)")
            sendCriticalRedeemResult(
                buildRedeemResultJson(
                    success = false,
                    transactionId = redemption.transactionId,
                    errorCode = "NOTHING_TO_REDEEM",
                    errorMessage = "No bank-redeemable amount; existing ticket remains valid for cash",
                    ticketId = redemption.ticketId,
                    bankRedeemAmount = bankAmount,
                    cashRedeemAmount = cashAmount
                ),
                reason = "Nothing to redeem"
            )
            cleanupRedeemState()
            _screenState.value = PaymentScreenState.RedeemCancelled(
                title = "TICKET NOT REDEEMABLE",
                message = "This ticket was paid fully in cash, so nothing can be sent to your bank. " +
                    "Your ticket is still valid - please take it to the cashier desk to collect your cash."
            )
            viewModelScope.launch {
                delay(6000)
                requestInitialScreen()
            }
            return
        }

        redemption.bankAmount = bankAmount
        redemption.cashAmount = cashAmount
        redemption.totalAmount = totalAmount
        message.data?.currency?.let { redemption.currency = it }
        message.data?.ticketId?.let {
            if (redemption.ticketId == null) activeRedemption = redemption.copy(ticketId = it)
        }

        _screenState.value = PaymentScreenState.RedeemConfirmation(
            ticketId = activeRedemption?.ticketId,
            bankAmount = bankAmount,
            cashAmount = cashAmount,
            totalAmount = totalAmount,
            currency = redemption.currency
        )
        _isOnAmountScreen.value = false

        // Auto-cancel if the customer never answers - the ticket stays valid.
        redeemTimeoutJob = viewModelScope.launch {
            delay(REDEEM_CONFIRMATION_TIMEOUT_MS)
            if (_screenState.value is PaymentScreenState.RedeemConfirmation) {
                AppLog.w(TAG, "Redeem confirmation timed out after ${REDEEM_CONFIRMATION_TIMEOUT_MS}ms - auto-cancelling")
                respondToRedeemConfirmation(continueRedeem = false, timedOut = true)
            }
        }
    }

    /**
     * Customer answered the redemption confirmation screen (or it timed out).
     *
     * CONTINUE / CANCEL / TIMEOUT are reported to the server via USER_ACTION.
     * The backend performs redemption; this device only shows screens and
     * waits for server REDEEM_RESULT. We do not decide success/failure locally.
     */
    fun respondToRedeemConfirmation(continueRedeem: Boolean, timedOut: Boolean = false) {
        if (!timedOut) recordUserInteraction()
        redeemTimeoutJob?.cancel()
        redeemTimeoutJob = null

        val redemption = activeRedemption
        if (redemption == null) {
            AppLog.e(TAG, "Cannot respond to redemption: no active redemption")
            return
        }

        val selection = when {
            continueRedeem -> "CONTINUE"
            timedOut -> "TIMEOUT"
            else -> "CANCEL"
        }
        audit("Redeem confirmation answered: $selection tx=${redemption.transactionId}")

        // Tell the server what the customer chose (same USER_ACTION pattern
        // as RECEIPT_RESPONSE).
        val responseMessage = SocketMessage(
            messageType = "USER_ACTION",
            screen = "REDEEM_RESPONSE",
            data = MessageData(
                selectionMethod = selection,
                errorMessage = if (timedOut) "Redemption confirmation timed out" else null,
                ticketId = redemption.ticketId
            ),
            transactionId = redemption.transactionId,
            timestamp = System.currentTimeMillis()
        )
        sendMessage(responseMessage)

        if (!continueRedeem) {
            // Relevant message before returning to the main screen.
            cleanupRedeemState()
            _screenState.value = PaymentScreenState.RedeemCancelled(
                message = if (timedOut) {
                    "Redemption timed out. Your ticket has NOT been redeemed and remains valid."
                } else {
                    "Redemption cancelled. Your ticket has NOT been redeemed and remains valid."
                }
            )
            viewModelScope.launch {
                delay(4000)
                requestInitialScreen()
            }
            return
        }

        // Customer accepted — show processing and wait for the backend.
        // Do NOT run local payout or send REDEEM_RESULT ourselves.
        _screenState.value = PaymentScreenState.RedeemProcessing(
            message = if (redemption.bankAmount > 0) {
                "Sending ${redemption.currency}${redemption.bankAmount} to your bank card..."
            } else {
                "Processing your redemption..."
            }
        )
        _isOnAmountScreen.value = false

        redeemTimeoutJob = viewModelScope.launch {
            delay(REDEEM_RESULT_TIMEOUT_MS)
            if (_screenState.value is PaymentScreenState.RedeemProcessing) {
                AppLog.e(TAG, "Server REDEEM_RESULT not received within ${REDEEM_RESULT_TIMEOUT_MS}ms after CONTINUE")
                clearRedeemTracking()
                showRedeemFailedAndReturnHome(
                    errorMessage = "Redemption timed out. Your ticket has NOT been redeemed — please try again or visit the cashier desk.",
                    displayDurationMs = 5000
                )
            }
        }
    }

    /**
     * Send the bank-redeemable portion to the customer's card via the payment
     * provider, then report REDEEM_RESULT to the server. If the payout fails
     * for any reason the server is told the ticket could NOT be redeemed.
     */
    private fun performRedeem(redemption: ActiveRedemption) {
        _screenState.value = PaymentScreenState.RedeemProcessing(
            message = if (redemption.bankAmount > 0) {
                "Sending ${redemption.currency}${redemption.bankAmount} to your bank card..."
            } else {
                "Processing your redemption..."
            }
        )
        _isOnAmountScreen.value = false

        viewModelScope.launch {
            try {
                // Defensive: a zero bank portion is refused at breakdown time
                // (NOTHING_TO_REDEEM) and should never reach the payout step.
                if (redemption.bankAmount <= 0) {
                    AppLog.e(TAG, "performRedeem reached with no bank portion - failing")
                    completeRedeemFailure(
                        redemption,
                        errorCode = "NOTHING_TO_REDEEM",
                        errorMessage = "No bank-redeemable amount; existing ticket remains valid for cash"
                    )
                    return@launch
                }

                val database = AppDatabase.getDatabase(getApplication())
                val deviceInfo = database.deviceInfoDao().getDeviceInfo().first()
                val paymentProvider = deviceInfo?.paymentProvider?.lowercase() ?: "nnsmart"
                val currencyCode = deviceInfo?.currency ?: "GBP"
                val amountFormatted = String.format("%.2f", redemption.bankAmount.toDouble())
                val redeemRef = "REDEEM_${redemption.transactionId}"

                AppLog.d(TAG, "Performing redeem payout: provider=$paymentProvider amount=$amountFormatted ref=$redeemRef")
                val payoutResult = when {
                    paymentProvider == "mock" -> MockPaymentManager.performRedeem(
                        amountFormatted = amountFormatted,
                        requesterRef = redeemRef
                    )
                    CcvPaymentManager.isCcvProvider(paymentProvider) -> {
                        // Unaddressed CCV REFUND: the terminal prompts the
                        // customer to present the card the money should go to.
                        val refund = CcvPaymentManager.performRefund(
                            context = getApplication(),
                            amountFormatted = amountFormatted,
                            requesterRef = redeemRef,
                            currencyAlphaCode = currencyCode,
                            approvalCode = null,
                            receiptNumber = null
                        )
                        app.sst.pinto.payment.PlanetPaymentResult(
                            success = refund.success,
                            resultCode = if (refund.success) "A" else (refund.resultCode ?: "REDEEM_FAILED"),
                            bankResultCode = if (refund.success) "00" else null,
                            message = if (refund.success) "REDEEM_APPROVED" else (refund.message ?: "Redeem payout failed on terminal"),
                            requesterTransRefNum = redeemRef,
                            rawOptions = emptyMap()
                        )
                    }
                    else -> {
                        // NNSmart / Integra have no unreferenced payout API in
                        // the current SDK integration; refuse cleanly so the
                        // ticket stays valid rather than pretending success.
                        AppLog.e(TAG, "Redeem payout not supported by provider: $paymentProvider")
                        app.sst.pinto.payment.PlanetPaymentResult(
                            success = false,
                            resultCode = "REDEEM_NOT_SUPPORTED",
                            bankResultCode = null,
                            message = "Redemption to bank is not supported by payment provider '$paymentProvider'",
                            requesterTransRefNum = redeemRef,
                            rawOptions = emptyMap()
                        )
                    }
                }

                if (payoutResult.success) {
                    completeRedeemSuccess(
                        redemption,
                        resultCode = payoutResult.resultCode ?: "A",
                        resultMessage = payoutResult.message ?: "REDEEM_APPROVED"
                    )
                } else {
                    completeRedeemFailure(
                        redemption,
                        errorCode = payoutResult.resultCode ?: "REDEEM_FAILED",
                        errorMessage = payoutResult.message ?: "Redeem payout failed"
                    )
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "Error performing redeem payout", e)
                completeRedeemFailure(
                    redemption,
                    errorCode = "EXCEPTION",
                    errorMessage = "Error processing redemption: ${e.message}"
                )
            }
        }
    }

    /** Payout succeeded: notify server, show breakdown, await cash-ticket print flow. */
    private fun completeRedeemSuccess(
        redemption: ActiveRedemption,
        resultCode: String,
        resultMessage: String
    ) {
        audit("Redeem SUCCESS tx=${redemption.transactionId} bank=${redemption.bankAmount} cash=${redemption.cashAmount}")
        sendCriticalRedeemResult(
            buildRedeemResultJson(
                success = true,
                transactionId = redemption.transactionId,
                errorCode = null,
                errorMessage = null,
                ticketId = redemption.ticketId,
                bankRedeemAmount = redemption.bankAmount,
                cashRedeemAmount = redemption.cashAmount,
                resultCode = resultCode,
                resultMessage = resultMessage
            ),
            reason = "Redeem success"
        )

        _screenState.value = PaymentScreenState.RedeemSuccess(
            bankAmount = redemption.bankAmount,
            cashAmount = redemption.cashAmount,
            currency = redemption.currency
        )
        _isOnAmountScreen.value = false
        cleanupRedeemState()

        // The server drives the rest (cash ticket printing / thank you). If it
        // never does, fall back to the main screen so the kiosk is not stuck.
        redeemSuccessFallbackJob?.cancel()
        redeemSuccessFallbackJob = viewModelScope.launch {
            delay(20000)
            AppLog.w(TAG, "No server screen change after redeem success - returning to main screen")
            requestInitialScreen()
        }
    }

    /** Payout failed: tell the server the ticket could NOT be redeemed. */
    private fun completeRedeemFailure(
        redemption: ActiveRedemption,
        errorCode: String,
        errorMessage: String
    ) {
        audit("Redeem FAILED tx=${redemption.transactionId} code=$errorCode msg=$errorMessage")
        sendCriticalRedeemResult(
            buildRedeemResultJson(
                success = false,
                transactionId = redemption.transactionId,
                errorCode = errorCode,
                errorMessage = errorMessage,
                ticketId = redemption.ticketId,
                bankRedeemAmount = redemption.bankAmount,
                cashRedeemAmount = redemption.cashAmount
            ),
            reason = "Redeem failure"
        )
        cleanupRedeemState()
        showRedeemFailedAndReturnHome(
            errorMessage = "We could not send the funds to your bank. Your ticket has NOT been redeemed - " +
                "please try again or visit the cashier desk.\n\nReason: $errorMessage",
            displayDurationMs = 6000
        )
    }

    /**
     * Build a REDEEM_RESULT JSON matching the server's expected contract.
     */
    private fun buildRedeemResultJson(
        success: Boolean,
        transactionId: String,
        errorCode: String?,
        errorMessage: String?,
        ticketId: String?,
        bankRedeemAmount: Int,
        cashRedeemAmount: Int,
        resultCode: String? = null,
        resultMessage: String? = null
    ): String {
        val screen = if (success) "SUCCESS" else "FAILED"
        val errCodeJson = if (errorCode != null) "\"$errorCode\"" else "null"
        val errMsgJson = if (errorMessage != null) "\"${errorMessage.replace("\"", "\\\"")}\"" else "null"
        val ticketIdJson = if (ticketId != null) "\"$ticketId\"" else "null"
        return """
            {
                "messageType": "REDEEM_RESULT",
                "screen": "$screen",
                "data": {
                    "errorCode": $errCodeJson,
                    "errorMessage": $errMsgJson,
                    "ticketId": $ticketIdJson,
                    "bankRedeemAmount": $bankRedeemAmount,
                    "cashRedeemAmount": $cashRedeemAmount,
                    "paymentDetails": {
                        "Result": "${resultCode ?: errorCode ?: ""}",
                        "BankResultCode": "${if (success) "00" else ""}",
                        "Message": "${(resultMessage ?: errorMessage ?: "").replace("\"", "\\\"")}",
                        "RequesterTransRefNum": "REDEEM_$transactionId"
                    }
                },
                "transactionId": "$transactionId",
                "timestamp": ${System.currentTimeMillis()}
            }
        """.trimIndent()
    }

    /**
     * Send a REDEEM_RESULT, persisting it for retry on reconnect if the socket
     * is down (same critical-message mechanism as reversal results). The server
     * must always learn whether the ticket was redeemed.
     */
    private fun sendCriticalRedeemResult(redeemResultJson: String, reason: String) {
        val sent = socketManager.sendMessage(redeemResultJson)
        AppLog.d(TAG, "$reason REDEEM_RESULT send result: $sent")
        audit("$reason REDEEM_RESULT send attempted sent=$sent payload=$redeemResultJson")
        if (!sent) {
            AppLog.w(TAG, "Failed to deliver REDEEM_RESULT, persisting for retry")
            savePendingCriticalMessage(redeemResultJson)
        } else {
            clearPendingCriticalMessage()
        }
    }

    /** Reset all redemption bookkeeping (does not touch the screen state). */
    /** Clears active redemption flags without cancelling the caller coroutine. */
    private fun clearRedeemTracking() {
        redeemTimeoutJob = null
        activeRedemption = null
        isProcessingRedeem = false
    }

    private fun cleanupRedeemState() {
        redeemTimeoutJob?.cancel()
        clearRedeemTracking()
    }

    override fun onCleared() {
        super.onCleared()
        AppLog.d(TAG, "ViewModel being cleared, canceling timers")
        timeoutManager.cancelTimers()
    }
}