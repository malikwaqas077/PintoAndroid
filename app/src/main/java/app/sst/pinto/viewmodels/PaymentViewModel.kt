package app.sst.pinto.viewmodels

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sst.pinto.data.models.MessageData
import app.sst.pinto.data.models.PaymentScreenState
import app.sst.pinto.data.models.SocketMessage
import app.sst.pinto.network.SocketManager
import app.sst.pinto.utils.TimeoutManager
import app.sst.pinto.payment.PlanetPaymentManager
import app.sst.pinto.payment.MockPaymentManager
import app.sst.pinto.data.AppDatabase
import app.sst.pinto.utils.getDeviceIpAddress
import app.sst.pinto.utils.getDeviceSerialNumber
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import app.sst.pinto.config.ConfigManager

class PaymentViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "PaymentViewModel"
    private val socketManager = SocketManager.getInstance()
    private val timeoutManager = TimeoutManager.getInstance()
    private val configManager = ConfigManager.getInstance(getApplication())

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
    private var isProcessingPayment: Boolean = false
    private var isHandlingPaymentLocally: Boolean = false // Flag to track if we're handling payment locally (YASPA disabled)
    private var allowNavigationFromLimitError: Boolean = false // Flag to allow navigation away from LIMIT_ERROR after user reset
    
    // Track last successful sale transaction for refund/reversal
    data class SuccessfulSaleTransaction(
        val transactionId: String,
        val amount: Int, // Amount in cents/pence
        val requesterTransRefNum: String? // From payment result
    )
    private var lastSuccessfulSale: SuccessfulSaleTransaction? = null

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val messageAdapter: JsonAdapter<SocketMessage> =
        moshi.adapter(SocketMessage::class.java)

    init {
        Log.d(TAG, "Initializing PaymentViewModel")

        // Setup timeout manager with callback for timeout events
        timeoutManager.setup {
            Log.d(TAG, "Timeout occurred, handling in ViewModel")
            handleTimeout()
        }

        // Monitor timeout state to show screensaver directly
        viewModelScope.launch {
            timeoutManager.timeoutOccurred.collect { occurred ->
                Log.d(TAG, "Timeout state changed: $occurred")
                if (occurred && _isOnAmountScreen.value) {
                    Log.d(TAG, "Timeout occurred while on amount screen, showing screensaver")
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
                Log.d(TAG, "Screensaver visibility changed: $visible")
                if (visible) {
                    // Make sure timers are paused when screensaver is visible
                    timeoutManager.pauseTimersForScreensaver()
                }
            }
        }

        // Monitor socket messages
        viewModelScope.launch {
            socketManager.messageReceived.collect { message ->
                Log.d(TAG, "Received socket message: $message")
                processSocketMessage(message)
            }
        }

        // Monitor socket connection state
        viewModelScope.launch {
            socketManager.connectionState.collect { state ->
                Log.d(TAG, "Socket connection state changed: $state")
                when (state) {
                    SocketManager.ConnectionState.DISCONNECTED -> {
                        Log.d(TAG, "Socket disconnected, updating screen state")
                        // Immediately set to ConnectionError, don't go through Loading
                        _screenState.value = PaymentScreenState.ConnectionError
                    }
                    SocketManager.ConnectionState.CONNECTING -> {
                        // Only set Loading if we're not already in ConnectionError state
                        if (_screenState.value !is PaymentScreenState.ConnectionError) {
                            Log.d(TAG, "Socket connecting, updating screen state")
                            _screenState.value = PaymentScreenState.Loading
                        }
                    }
                    else -> {} // No direct state change on connected
                }
            }
        }
    }

    fun connectToBackend(url: String) {
        Log.d(TAG, "Connecting to backend: $url")
        // Store the URL for later use
        this.serverUrl = url
        socketManager.connect(url)

        // Set a timeout to ensure we get an initial screen
        viewModelScope.launch {
            delay(5000) // Wait 5 seconds
            if (_screenState.value is PaymentScreenState.Loading) {
                Log.d(TAG, "Still in loading state after 5 seconds, requesting initial screen")
                requestInitialScreen()
            }
        }
    }

    /**
     * Records a user interaction to reset the timeout timer.
     */
    fun recordUserInteraction() {
        Log.d(TAG, "Recording user interaction to reset timeout timer")
        timeoutManager.recordUserInteraction()

        // If the screensaver is visible, hide it and restore the previous state
        if (_isScreensaverVisible.value) {
            Log.d(TAG, "User interacted while screensaver was visible, hiding screensaver")
            _isScreensaverVisible.value = false

            // If we have a saved state, restore it
            lastActiveState?.let {
                Log.d(TAG, "Restoring last active state: ${it::class.simpleName}")
                _screenState.value = it
            }
        }
    }

    /**
     * Handles a timeout event by canceling any active transaction.
     */
    private fun handleTimeout() {
        Log.d(TAG, "Handling timeout event")
        // Only trigger screensaver if on the amount selection screen
        if (_isOnAmountScreen.value) {
            // Save current state before changing to screensaver
            if (_screenState.value !is PaymentScreenState.DeviceError &&
                _screenState.value !is PaymentScreenState.ConnectionError) {
                lastActiveState = _screenState.value
                Log.d(TAG, "Saved last active state: ${lastActiveState?.javaClass?.simpleName}")
            }

            // Cancel any active transaction
            cancelPayment(isTimeout = true)
        } else {
            Log.d(TAG, "Not on amount screen, ignoring timeout")
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
                Log.w(TAG, "Device configuration not found, using original amount without fee")
                return originalAmount
            }
            
            val feeType = deviceInfo.transactionFeeType
            val feeValue = deviceInfo.transactionFeeValue
            val originalAmountDouble = originalAmount.toDouble()
            
            // Only add fee if feeValue is greater than 0
            if (feeValue <= 0) {
                Log.d(TAG, "Fee value is 0 or negative, using original amount: $originalAmount")
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
                    Log.w(TAG, "Unknown fee type: $feeType, using original amount")
                    originalAmountDouble
                }
            }
            
            val roundedAmount = finalAmount.toInt()
            Log.d(TAG, "Fee calculation: original=$originalAmount, feeType=$feeType, feeValue=$feeValue, final=$roundedAmount")
            roundedAmount
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating fee, using original amount", e)
            originalAmount
        }
    }
    
    fun selectAmount(amount: Int) {
        Log.d(TAG, "Amount selected: $amount")
        recordUserInteraction()

        // Special code -2 is used to return to amount selection from limit error
        if (amount == -2) {
            val transactionId = UUID.randomUUID().toString()
            currentTransactionId = transactionId
            Log.d(TAG, "Reset requested (code -2), new transaction ID: $transactionId")
            
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
            Log.d(TAG, "Other option selected - showing keypad screen")
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
            Log.d(TAG, "Generated new transaction ID: $it")
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
                Log.w(TAG, "Amount $amount is below minimum limit $minAmount")
                _screenState.value = PaymentScreenState.LimitError(
                    errorMessage = "Minimum transaction limit is $currencySymbol$minAmount"
                )
                allowNavigationFromLimitError = false // Reset flag when showing LIMIT_ERROR
                return@launch
            }
            
            if (amount > maxAmount) {
                Log.w(TAG, "Amount $amount exceeds maximum limit $maxAmount")
                _screenState.value = PaymentScreenState.LimitError(
                    errorMessage = "Maximum transaction limit is $currencySymbol$maxAmount"
                )
                allowNavigationFromLimitError = false // Reset flag when showing LIMIT_ERROR
                return@launch
            }
            
            // For Mock payment provider, check if amount is 101 (daily limit trigger)
            val paymentProvider = deviceInfo?.paymentProvider?.lowercase() ?: "integra"
            if (paymentProvider == "mock" && amount == 101) {
                Log.d(TAG, "Mock payment: Amount 101 triggers daily limit exceeded")
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
                Log.d(TAG, "Storing original amount: $amount, final amount with fee: $finalAmount")
                currentAmount = finalAmount // Store final amount for payment processing (includes fee)
            }

            // Check if YASPA is enabled to determine flow
            val yaspaEnabled = deviceInfo?.yaspaEnabled ?: true
            
            if (!yaspaEnabled) {
                // YASPA disabled: Show timeout screen locally, then proceed directly to payment
                Log.d(TAG, "YASPA disabled - showing timeout screen then proceeding directly to payment")
                
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
                Log.d(TAG, "YASPA disabled - proceeding directly to payment after timeout screen")
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
        Log.d(TAG, "Payment method selected: $method")
        recordUserInteraction()

        val transactionId = currentTransactionId
        if (transactionId == null) {
            Log.e(TAG, "Cannot select payment method: No active transaction ID")
            return
        }

        // For DEBIT_CARD and PAY_BY_BANK, handle payment locally
        if (method == "DEBIT_CARD" || method == "PAY_BY_BANK") {
            Log.d(TAG, "Processing local payment for method: $method")
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
            Log.w(TAG, "Payment already in progress, ignoring duplicate request")
            return
        }
        
        isProcessingPayment = true
        isHandlingPaymentLocally = true // Mark that we're handling payment locally
        
        // Step 1: Show PROCESSING screen automatically
        Log.d(TAG, "Showing PROCESSING screen for local payment")
        _screenState.value = PaymentScreenState.Processing
        _isOnAmountScreen.value = false
        
        viewModelScope.launch {
            try {
                // Get device configuration to determine payment provider
                val database = AppDatabase.getDatabase(getApplication())
                val deviceInfo = database.deviceInfoDao().getDeviceInfo().first()
                
                if (deviceInfo == null) {
                    Log.e(TAG, "Device configuration not found, cannot process payment")
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
                
                // For MOCK payment provider, show MockPaymentCard screen after Processing screen
                if (paymentProvider == "mock") {
                    Log.d(TAG, "Mock payment: Showing MockPaymentCard screen after Processing")
                    delay(2000) // Show Processing screen for 2 seconds
                    _screenState.value = PaymentScreenState.MockPaymentCard(
                        amount = currentAmount,
                        currency = currencySymbol
                    )
                    delay(3000) // Show MockPaymentCard screen for 3 seconds before proceeding
                }
                
                // Step 2: Perform CardCheckEmv locally with amount including fee
                Log.d(TAG, "Performing card check with provider: $paymentProvider, amount (including fee): $amountFormatted")
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
                    Log.e(TAG, "Card check failed: ${cardCheckResult.message}")
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
                    Log.d(TAG, "Sending card check result to server for daily limit validation: token=${cardCheckResult.token}")
                    
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
                    Log.d(TAG, "Mock payment: Skipping server limit check, proceeding directly to sale")
                    continuePaymentAfterLimitCheck(true, transactionId, "")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error processing local payment", e)
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
     * Continue payment processing after limit check result is received.
     * This is called when LIMIT_CHECK_RESULT message is received from server.
     */
    private fun continuePaymentAfterLimitCheck(approved: Boolean, transactionId: String, errorMessage: String = "Daily spending limit exceeded") {
        // If payment is not in progress but we received a LIMIT_ERROR, still show the error screen
        // This can happen if ViewModel was recreated (e.g., after activity restart)
        if (!approved && !isProcessingPayment) {
            Log.w(TAG, "Received LIMIT_ERROR but payment not in progress - showing error screen anyway")
            _screenState.value = PaymentScreenState.LimitError(
                errorMessage = errorMessage
            )
            allowNavigationFromLimitError = false // Reset flag when showing LIMIT_ERROR
            return
        }
        
        if (!isProcessingPayment) {
            Log.w(TAG, "Received limit check result but payment not in progress")
            return
        }
        
        val cardCheckResult = pendingCardCheckResult
        if (cardCheckResult == null) {
            Log.e(TAG, "No pending card check result found")
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
            Log.d(TAG, "Limit check rejected, cancelling payment")
            // Cancel the card check on terminal - MUST complete before showing error screen
            viewModelScope.launch {
                try {
                    val database = AppDatabase.getDatabase(getApplication())
                    val deviceInfo = database.deviceInfoDao().getDeviceInfo().first()
                    if (deviceInfo != null && deviceInfo.paymentProvider.lowercase() == "integra") {
                        // Only cancel if using real terminal
                        Log.d(TAG, "Cancelling transaction on terminal with sequenceNumber: ${cardCheckResult.sequenceNumber}")
                        val cancelSuccess = PlanetPaymentManager.performCancel(
                            requesterRef = transactionId,
                            sequenceNumberToCancel = cardCheckResult.sequenceNumber
                        )
                        if (cancelSuccess) {
                            Log.d(TAG, "Transaction cancelled successfully on terminal")
                        } else {
                            Log.w(TAG, "Transaction cancel may have failed or timed out, but continuing")
                        }
                    } else {
                        Log.d(TAG, "Not using integra provider, skipping terminal cancel")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error cancelling payment", e)
                } finally {
                    // Always show error screen after cancel attempt completes
                    // Show error screen BEFORE resetting flags to ensure it's displayed
                    _screenState.value = PaymentScreenState.LimitError(
                        errorMessage = errorMessage
                    )
                    isProcessingPayment = false
                    isHandlingPaymentLocally = false // Reset flag on limit error
                    allowNavigationFromLimitError = false // Reset flag when showing LIMIT_ERROR
                    Log.d(TAG, "Limit error screen displayed: $errorMessage")
                }
            }
            return
        }
        
        // Step 5: Perform Sale transaction locally
        Log.d(TAG, "Limit check approved, performing sale transaction")
        viewModelScope.launch {
            try {
                val database = AppDatabase.getDatabase(getApplication())
                val deviceInfo = database.deviceInfoDao().getDeviceInfo().first()
                
                if (deviceInfo == null) {
                    Log.e(TAG, "Device configuration not found")
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
                Log.d(TAG, "Performing sale transaction with provider: $paymentProvider, amount (including fee): $amountFormatted")
                val saleResult = if (paymentProvider == "mock") {
                    MockPaymentManager.performSale(amountFormatted, transactionId)
                } else {
                    PlanetPaymentManager.performSale(
                        amountFormatted = amountFormatted, // Amount includes transaction fee
                        requesterRef = transactionId
                    )
                }
                
                // Step 6: Show SUCCESS or FAILED screen immediately
                if (saleResult.success) {
                    Log.d(TAG, "Payment successful")
                    // Store successful sale transaction for potential refund/reversal
                    lastSuccessfulSale = SuccessfulSaleTransaction(
                        transactionId = transactionId,
                        amount = currentAmount,
                        requesterTransRefNum = saleResult.requesterTransRefNum ?: transactionId
                    )
                    Log.d(TAG, "Stored successful sale transaction: $lastSuccessfulSale")
                    _screenState.value = PaymentScreenState.TransactionSuccess(showReceipt = true)
                } else {
                    Log.d(TAG, "Payment failed: ${saleResult.message}")
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
                val paymentResultJson = """
                    {
                        "messageType": "PAYMENT_RESULT",
                        "screen": "${if (saleResult.success) "SUCCESS" else "FAILED"}",
                        "data": {
                            "errorCode": ${if (saleResult.resultCode != null) "\"${saleResult.resultCode}\"" else "null"},
                            "errorMessage": ${if (saleResult.message != null) "\"${saleResult.message}\"" else "null"},
                            "paymentDetails": {
                                "Result": "${saleResult.resultCode ?: ""}",
                                "BankResultCode": "${saleResult.bankResultCode ?: ""}",
                                "Message": "${saleResult.message ?: ""}",
                                "RequesterTransRefNum": "$transactionId"
                            }
                        },
                        "transactionId": "$transactionId",
                        "timestamp": ${System.currentTimeMillis()}
                    }
                """.trimIndent()
                
                socketManager.sendMessage(paymentResultJson)
                
                isProcessingPayment = false
                isHandlingPaymentLocally = false // Reset flag when payment completes
            } catch (e: Exception) {
                Log.e(TAG, "Error performing sale transaction", e)
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
            Log.d(TAG, "Cannot cancel payment: No active transaction ID")
            // Reset flags if no active transaction
            isProcessingPayment = false
            isHandlingPaymentLocally = false
            // If there's no active transaction but this is a timeout,
            // we should still show the screensaver if on amount screen
            if (isTimeout && _isOnAmountScreen.value) {
                Log.d(TAG, "No active transaction, but showing screensaver due to timeout")
                forceShowScreensaver()
            }
            return
        }
        
        // Reset flags when cancelling
        isProcessingPayment = false
        isHandlingPaymentLocally = false

        if (isTimeout) {
            Log.d(TAG, "Canceling payment due to timeout")
        } else {
            Log.d(TAG, "User manually canceled payment")
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
        Log.d(TAG, "Forcing screensaver to show immediately")
        // Save current state
        if (_screenState.value !is PaymentScreenState.DeviceError &&
            _screenState.value !is PaymentScreenState.ConnectionError) {
            lastActiveState = _screenState.value
            Log.d(TAG, "Saved last active state: ${lastActiveState?.javaClass?.simpleName}")
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
        Log.d(TAG, "Dismissing screensaver")
        _isScreensaverVisible.value = false

        // Resume timers after screensaver is dismissed
        timeoutManager.resumeTimersAfterScreensaver()

        // Record user interaction to reset all timers
        recordUserInteraction()

        // Ensure socket connection is active
        ensureSocketConnection()

        // Request fresh amount selection screen to ensure UI is shown
        Log.d(TAG, "Requesting fresh amount selection screen after screensaver dismissal")
        requestInitialScreen()
    }

    /**
     * Requests the initial amount selection screen from the server.
     * Used after timeouts to ensure UI is properly displayed.
     */
    private fun requestInitialScreen() {
        Log.d(TAG, "Requesting initial screen from server")

        // Reset flags when starting a new screen flow
        isProcessingPayment = false
        isHandlingPaymentLocally = false

        // Check if we have a valid server URL
        if (serverUrl.isEmpty()) {
            Log.e(TAG, "Cannot request initial screen: Server URL is empty")
            _screenState.value = PaymentScreenState.ConnectionError
            return
        }

        // Make sure socket is connected before attempting to send messages
        ensureSocketConnection()

        // Generate a new transaction ID for the new session
        val transactionId = UUID.randomUUID().toString()
        currentTransactionId = transactionId
        Log.d(TAG, "Generated new transaction ID for reset: $transactionId")

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
        Log.d(TAG, "Set temporary loading state while waiting for screen response")

        // Set up a fallback in case we don't get a response
        viewModelScope.launch {
            delay(3000) // Wait 3 seconds for response
            if (_screenState.value is PaymentScreenState.Loading) {
                Log.d(TAG, "No response received after 3 seconds, retrying connection")
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
            Log.e(TAG, "Cannot ensure socket connection: Server URL is empty")
            _screenState.value = PaymentScreenState.ConnectionError
            return
        }

        // Use ensureConnected which handles all connection states properly
        socketManager.ensureConnected()
    }

    fun retryConnection() {
        Log.d(TAG, "Retrying connection to $serverUrl")
        _screenState.value = PaymentScreenState.Loading
        socketManager.disconnect()
        connectToBackend(serverUrl)
    }

    /**
     * Send a message to the server with better error handling
     */
    private fun sendMessage(message: SocketMessage) {
        val jsonMessage = messageAdapter.toJson(message)
        Log.d(TAG, "Sending message: $jsonMessage")

        // Check if we have a valid server URL
        if (serverUrl.isEmpty()) {
            Log.e(TAG, "Cannot send message: Server URL is empty")
            _screenState.value = PaymentScreenState.ConnectionError
            return
        }

        // Ensure we're connected before sending
        if (!socketManager.isConnected()) {
            Log.w(TAG, "Socket not connected, attempting to reconnect to $serverUrl")
            socketManager.connect(serverUrl)

            // Queue up message send after a brief delay
            viewModelScope.launch {
                delay(1000) // Wait 1 second for connection
                if (socketManager.isConnected()) {
                    Log.d(TAG, "Connection established, sending delayed message")
                    val success = socketManager.sendMessage(jsonMessage)
                    Log.d(TAG, "Delayed message send result: $success")
                } else {
                    Log.e(TAG, "Still not connected, unable to send message")
                    _screenState.value = PaymentScreenState.ConnectionError
                }
            }
            return
        }

        // Normal send if already connected
        val success = socketManager.sendMessage(jsonMessage)
        Log.d(TAG, "Message send result: $success")

        if (!success) {
            Log.e(TAG, "Failed to send message, checking connection")
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
                Log.d(TAG, "=== SOCKET MESSAGE RECEIVED ===")
                Log.d(TAG, "Raw JSON: $jsonMessage")
                Log.d(TAG, "Message Type: ${it.messageType}")
                Log.d(TAG, "Screen: ${it.screen}")
                Log.d(TAG, "Transaction ID: ${it.transactionId}")
                Log.d(TAG, "Current Screen State: ${_screenState.value::class.simpleName}")
                Log.d(TAG, "Timestamp: ${it.timestamp}")
                Log.d(TAG, "================================")

                when (it.messageType) {
                    "SCREEN_CHANGE" -> {
                        Log.d(TAG, "Processing SCREEN_CHANGE to: ${it.screen}")
                        handleScreenChange(it)
                        Log.d(TAG, "New Screen State: ${_screenState.value::class.simpleName}")
                    }
                    "ERROR" -> {
                        Log.d(TAG, "Processing ERROR message")
                        handleError(it)
                    }
                    "STATUS_UPDATE" -> {
                        Log.d(TAG, "Processing STATUS_UPDATE message")
                        handleStatusUpdate(it)
                    }
                    "LIMIT_CHECK_RESULT" -> {
                        Log.d(TAG, "Processing LIMIT_CHECK_RESULT message")
                        handleLimitCheckResult(it)
                    }
                    "DEVICE_INFO" -> {
                        Log.d(TAG, "Processing DEVICE_INFO message")
                        handleDeviceInfo(it)
                    }
                    "RESTART_APP" -> {
                        Log.d(TAG, "Processing RESTART_APP message")
                        handleRestartApp(it)
                    }
                    "REFUND_REQUEST", "REVERSAL_REQUEST" -> {
                        Log.d(TAG, "Processing ${it.messageType} message")
                        handleRefundRequest(it)
                    }
                    else -> {
                        Log.d(TAG, "Unhandled message type: ${it.messageType}")
                    }
                }
            }
        } catch (e: Exception) {
            // Handle parsing error
            Log.e(TAG, "Error parsing socket message: $jsonMessage", e)
            _screenState.value = PaymentScreenState.DeviceError("Invalid message format: ${e.message}")
            // Make sure to update screen state flag
            _isOnAmountScreen.value = false
        }
    }

    /**
     * Process screen state changes to track when we're on the amount selection screen
     */
    fun respondToReceiptQuestion(wantsReceipt: Boolean) {
        Log.d(TAG, "Receipt response: $wantsReceipt")
        recordUserInteraction()

        val transactionId = currentTransactionId
        if (transactionId == null) {
            Log.e(TAG, "Cannot respond to receipt question: No active transaction ID")
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
        Log.d(TAG, "Handling screen change to: ${message.screen}")
        
        // If we're currently showing LIMIT_ERROR, don't allow other screens to override it
        // unless the user has explicitly requested a reset (allowNavigationFromLimitError flag)
        if (_screenState.value is PaymentScreenState.LimitError && 
            message.screen != "LIMIT_ERROR" && 
            !allowNavigationFromLimitError) {
            Log.d(TAG, "Ignoring screen change to ${message.screen} - LIMIT_ERROR screen is active. User must interact to dismiss.")
            return
        }
        
        // Reset the flag after allowing navigation
        if (allowNavigationFromLimitError && message.screen == "AMOUNT_SELECT") {
            allowNavigationFromLimitError = false
            Log.d(TAG, "Navigation from LIMIT_ERROR allowed - resetting flag")
        }
        
        when (message.screen) {
            "INFO_SCREEN" -> {
                // Handle INFO_SCREEN request for device information
                Log.d(TAG, "Received INFO_SCREEN request")
                val requestType = message.data?.requestType
                if (requestType == "DEVICE_INFO") {
                    Log.d(TAG, "INFO_SCREEN request for device info, sending device IP and serial number")
                    sendDeviceIpAddress(message.transactionId)
                    sendDeviceSerialNumber(message.transactionId)
                } else {
                    Log.d(TAG, "INFO_SCREEN with unknown requestType: $requestType")
                }
                // Note: INFO_SCREEN does not change the current screen state
                return
            }
            "AMOUNT_SELECT" -> {
                val data = message.data
                if (data != null && data.amounts != null && data.currency != null) {
                    Log.d(TAG, "Changing to AMOUNT_SELECT screen with ${data.amounts.size} amounts")
                    _screenState.value = PaymentScreenState.AmountSelect(
                        amounts = data.amounts,
                        currency = data.currency,
                        showOtherOption = data.showOtherOption ?: true
                    )
                    // Set flag that we're on the amount selection screen
                    _isOnAmountScreen.value = true
                    Log.d(TAG, "Set isOnAmountScreen = true")
                } else {
                    Log.e(TAG, "Invalid data for AMOUNT_SELECT: $data")
                    _isOnAmountScreen.value = false
                }
            }
            "RECEIPT_QUESTION" -> {
                Log.d(TAG, "Received RECEIPT_QUESTION screen change")
                viewModelScope.launch {
                    val database = AppDatabase.getDatabase(getApplication())
                    val deviceInfo = database.deviceInfoDao().getDeviceInfo().first()
                    val requireCardReceipt = deviceInfo?.requireCardReceipt ?: true
                    
                    if (requireCardReceipt) {
                        Log.d(TAG, "requireCardReceipt is enabled - showing receipt question screen")
                        _screenState.value = PaymentScreenState.ReceiptQuestion(showGif = true)
                    } else {
                        Log.d(TAG, "requireCardReceipt is disabled - skipping receipt question screen")
                        // Automatically respond NO to receipt question
                        respondToReceiptQuestion(wantsReceipt = false)
                    }
                    _isOnAmountScreen.value = false
                }
            }
            "TIMEOUT" -> {
                Log.d(TAG, "Server sent TIMEOUT message - showing timeout screen")
                _screenState.value = PaymentScreenState.Timeout
                _isOnAmountScreen.value = false

                // REMOVE THIS AUTO-NAVIGATION - let the server control the flow
                // The server will send the next screen when ready
                // Don't auto-navigate after timeout
            }
            "KEYPAD" -> {
                Log.d(TAG, "Changing to KEYPAD screen")
                _screenState.value = PaymentScreenState.KeypadEntry(
                    currency = message.data?.currency ?: "£",
                    minAmount = 10, // Default
                    maxAmount = 300 // Default
                )
                // Not on amount selection screen anymore
                _isOnAmountScreen.value = false
                Log.d(TAG, "Set isOnAmountScreen = false")
            }
            "PAYMENT_METHOD" -> {
                // Check if we're handling payment locally (YASPA disabled) - if so, ignore this screen change
                if (isHandlingPaymentLocally) {
                    Log.d(TAG, "Ignoring PAYMENT_METHOD screen - handling payment locally (YASPA disabled)")
                    return
                }
                
                Log.d(TAG, "Changing to PAYMENT_METHOD screen with amount: $currentAmount")
                _screenState.value = PaymentScreenState.PaymentMethodSelect(
                    methods = message.data?.methods ?: listOf("DEBIT_CARD", "PAY_BY_BANK"),
                    amount = currentAmount, // Use stored amount (includes fee)
                    currency = message.data?.currency ?: "£",
                    allowCancel = message.data?.allowCancel ?: true
                )
                // Not on amount selection screen anymore
                _isOnAmountScreen.value = false
                Log.d(TAG, "Set isOnAmountScreen = false")
            }
            "QR_CODE" -> {
                val paymentUrl = message.data?.paymentUrl ?: ""
                Log.d(TAG, "Changing to QR_CODE screen with URL: $paymentUrl")
                _screenState.value = PaymentScreenState.QrCodeDisplay(
                    paymentUrl = paymentUrl
                )
                _isOnAmountScreen.value = false
            }
            "PROCESSING" -> {
                Log.d(TAG, "Changing to PROCESSING screen")
                _screenState.value = PaymentScreenState.Processing
                _isOnAmountScreen.value = false
            }
            "SUCCESS" -> {
                Log.d(TAG, "Changing to SUCCESS screen")
                _screenState.value = PaymentScreenState.TransactionSuccess(
                    showReceipt = true
                )
                _isOnAmountScreen.value = false
            }
            "FAILED" -> {
                val errorMessage = message.data?.errorMessage
                Log.d(TAG, "Changing to FAILED screen with error: $errorMessage")
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
                Log.d(TAG, "Changing to LIMIT_ERROR screen with message: $errorMessage")
                _screenState.value = PaymentScreenState.LimitError(
                    errorMessage = errorMessage
                )
                _isOnAmountScreen.value = false
                // Reset navigation flag when showing LIMIT_ERROR
                allowNavigationFromLimitError = false
            }
            "PRINT_TICKET" -> {
                Log.d(TAG, "Changing to PRINT_TICKET screen")
                _screenState.value = PaymentScreenState.PrintingTicket
                _isOnAmountScreen.value = false
            }
            "COLLECT_TICKET" -> {
                Log.d(TAG, "Changing to COLLECT_TICKET screen")
                _screenState.value = PaymentScreenState.CollectTicket
                _isOnAmountScreen.value = false
            }
            "THANK_YOU" -> {
                Log.d(TAG, "Changing to THANK_YOU screen")
                // #region agent log
                try {
                    val logData = """{"sessionId":"debug-session","runId":"run1","hypothesisId":"C","location":"PaymentViewModel.kt:1211","message":"THANK_YOU screen change initiated","data":{"previousScreen":"${_screenState.value::class.simpleName}"},"timestamp":${System.currentTimeMillis()}}"""
                    java.io.File("d:\\Development\\PintoAndroidApp\\.cursor\\debug.log").appendText(logData + "\n")
                } catch (e: Exception) {}
                // #endregion
                _screenState.value = PaymentScreenState.ThankYou
                _isOnAmountScreen.value = false
            }
            // Add this case to the when statement in handleScreenChange method
            "REFUND_PROCESSING" -> {
                Log.d(TAG, "Changing to REFUND_PROCESSING screen")
                _screenState.value = PaymentScreenState.RefundProcessing(
                    errorMessage = message.data?.errorMessage
                )
                _isOnAmountScreen.value = false
            }
            "PRINTER_ERROR" -> {
                val errorMessage = message.data?.errorMessage ?: "Printer error occurred"
                Log.d(TAG, "Changing to PRINTER_ERROR screen with message: $errorMessage")
                _screenState.value = PaymentScreenState.DeviceError(
                    errorMessage = errorMessage
                )
                _isOnAmountScreen.value = false
            }
            "DEVICE_ERROR" -> {
                val errorMessage = message.data?.errorMessage ?: "Unknown device error"
                Log.d(TAG, "Changing to DEVICE_ERROR screen with message: $errorMessage")
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
        Log.d(TAG, "Handling error: $errorMessage")
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
        Log.d(TAG, "Received status update: ${message.data}")
    }
    
    /**
     * Handle LIMIT_CHECK_RESULT messages from the server.
     * This is received after sending CARD_CHECK_RESULT for daily limit validation.
     */
    private fun handleLimitCheckResult(message: SocketMessage) {
        Log.d(TAG, "Received LIMIT_CHECK_RESULT: screen=${message.screen}")
        
        val transactionId = message.transactionId
        if (transactionId != currentTransactionId) {
            Log.w(TAG, "LIMIT_CHECK_RESULT transaction ID mismatch: expected=$currentTransactionId, received=$transactionId")
        }
        
        // Check if limit check was approved or rejected
        val approved = message.screen == "APPROVED" || message.screen.uppercase() == "APPROVED"
        
        if (approved) {
            Log.d(TAG, "Limit check approved, continuing payment")
            continuePaymentAfterLimitCheck(true, transactionId, "")
        } else {
            val errorMessage = message.data?.errorMessage ?: "Daily spending limit exceeded"
            Log.d(TAG, "Limit check rejected: $errorMessage")
            continuePaymentAfterLimitCheck(false, transactionId, errorMessage)
        }
    }
    
    /**
     * Handle DEVICE_INFO messages from the server.
     * This can be either device configuration from server or device info request.
     */
    private fun handleDeviceInfo(message: SocketMessage) {
        Log.d(TAG, "Received DEVICE_INFO message")
        
        val data = message.data
        if (data == null) {
            Log.d(TAG, "DEVICE_INFO message has no data, ignoring")
            return
        }
        
        // Check if this is device configuration from server
        // Device configuration includes: minTransactionLimit, maxTransactionLimit, etc.
        val hasConfig = data.minTransactionLimit != null || 
                       data.maxTransactionLimit != null ||
                       data.transactionFeeType != null ||
                       data.yaspaEnabled != null ||
                       data.paymentProvider != null ||
                       data.requireCardReceipt != null
        
        if (hasConfig) {
            Log.d(TAG, "Received device configuration from server")
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
                        paymentProvider = data.paymentProvider ?: existingInfo?.paymentProvider ?: "integra",
                        requireCardReceipt = data.requireCardReceipt ?: existingInfo?.requireCardReceipt ?: true
                    )
                    
                    // Use insertDeviceInfo which handles both insert and update (REPLACE strategy)
                    deviceInfoDao.insertDeviceInfo(deviceInfo)
                    
                    Log.d(TAG, "Device configuration saved: provider=${deviceInfo.paymentProvider}, yaspaEnabled=${deviceInfo.yaspaEnabled}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving device configuration", e)
                }
            }
        } else {
            Log.d(TAG, "DEVICE_INFO message is not configuration, may be device info request")
            // Could be a request for device info - handle if needed
        }
    }
    
    /**
     * Handle RESTART_APP messages from the server.
     * This instructs the client to restart the application.
     */
    private fun handleRestartApp(message: SocketMessage) {
        Log.d(TAG, "Received RESTART_APP message, closing application")
        
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
                
                Log.d(TAG, "Sending device IP address: $ipAddress")
                
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
                Log.e(TAG, "Error sending device IP address", e)
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
                
                Log.d(TAG, "Sending device serial number: $serialNumber")
                
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
                Log.e(TAG, "Error sending device serial number", e)
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
                Log.d(TAG, "Handling refund/reversal request: ${message.messageType}")
                
                // Check if we have a last successful sale transaction
                val lastSale = lastSuccessfulSale
                if (lastSale == null) {
                    Log.e(TAG, "Cannot process refund/reversal: No successful sale transaction found")
                    // Send error response
                    val errorResponse = """
                        {
                            "messageType": "REVERSAL_RESULT",
                            "screen": "FAILED",
                            "data": {
                                "errorCode": "NO_SALE_FOUND",
                                "errorMessage": "No successful sale transaction found to reverse",
                                "paymentDetails": {}
                            },
                            "transactionId": "${message.transactionId}",
                            "timestamp": ${System.currentTimeMillis()}
                        }
                    """.trimIndent()
                    socketManager.sendMessage(errorResponse)
                    return@launch
                }
                
                // Get transaction details from message or use last successful sale
                val originalTransactionId = message.data?.originalTransactionId ?: lastSale.transactionId
                // Ensure we have a non-null originalRequesterRef (use transactionId as fallback)
                // Since lastSale.transactionId is always non-null, the result is guaranteed to be non-null
                val originalRequesterRef = message.data?.originalRequesterTransRefNum 
                    ?: lastSale.requesterTransRefNum 
                    ?: lastSale.transactionId // lastSale.transactionId is String (non-null), so result is String
                val reversalAmount = message.data?.reversalAmount ?: lastSale.amount
                
                Log.d(TAG, "Processing reversal: originalTxId=$originalTransactionId, originalRef=$originalRequesterRef, amount=$reversalAmount")
                
                // Hide screensaver if visible (user initiated reversal, so they're active)
                _isScreensaverVisible.value = false
                
                // Show refund processing screen
                _screenState.value = PaymentScreenState.RefundProcessing(
                    errorMessage = "Processing refund..."
                )
                
                // Get payment provider
                val database = AppDatabase.getDatabase(getApplication())
                val deviceInfo = database.deviceInfoDao().getDeviceInfo().first()
                val paymentProvider = deviceInfo?.paymentProvider?.lowercase() ?: "integra"
                
                val amountFormatted = String.format("%.2f", reversalAmount.toDouble())
                // Generate a unique reversal reference (don't double-prefix if transactionId already has REVERSAL_)
                val reversalRef = if (message.transactionId.startsWith("REVERSAL_")) {
                    message.transactionId // Already has REVERSAL_ prefix
                } else {
                    "REVERSAL_${message.transactionId}"
                }
                
                // Perform reversal
                Log.d(TAG, "Performing sale reversal with provider: $paymentProvider, amount: $amountFormatted")
                val reversalResult = if (paymentProvider == "mock") {
                    MockPaymentManager.performSaleReversal(
                        amountFormatted = amountFormatted,
                        requesterRef = reversalRef,
                        originalRequesterRef = originalRequesterRef
                    )
                } else {
                    PlanetPaymentManager.performSaleReversal(
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
                    Log.d(TAG, "Reversal successful - showing success screen")
                    // Clear the last successful sale since it's been reversed
                    lastSuccessfulSale = null
                    
                    // Ensure screensaver is hidden to show success screen
                    _isScreensaverVisible.value = false
                    timeoutManager.recordUserInteraction() // Reset timeout timer
                    
                    // Show success screen briefly, then return to amount selection
                    _screenState.value = PaymentScreenState.TransactionSuccess(showReceipt = false)
                    _isOnAmountScreen.value = false
                    Log.d(TAG, "Screen state changed to TransactionSuccess after reversal")
                    
                    // After showing success, automatically request initial screen from server
                    viewModelScope.launch {
                        delay(3000) // Show success for 3 seconds
                        Log.d(TAG, "Requesting initial screen after successful reversal")
                        requestInitialScreen()
                    }
                } else {
                    Log.e(TAG, "Reversal failed: ${reversalResult.message}")
                    // Ensure screensaver is hidden to show failed screen
                    _isScreensaverVisible.value = false
                    timeoutManager.recordUserInteraction() // Reset timeout timer
                    
                    // Show failed screen, then return to amount selection
                    _screenState.value = PaymentScreenState.TransactionFailed(
                        errorMessage = reversalResult.message ?: "Reversal failed"
                    )
                    _isOnAmountScreen.value = false
                    Log.d(TAG, "Screen state changed to TransactionFailed after reversal")
                    
                    // Auto-return to amount selection after 4 seconds
                    viewModelScope.launch {
                        delay(4000)
                        requestInitialScreen()
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error processing refund/reversal request", e)
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

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "ViewModel being cleared, canceling timers")
        timeoutManager.cancelTimers()
    }
}