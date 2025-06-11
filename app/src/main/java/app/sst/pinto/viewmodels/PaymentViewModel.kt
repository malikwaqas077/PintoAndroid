package app.sst.pinto.viewmodels

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sst.pinto.data.models.MessageData
import app.sst.pinto.data.models.PaymentScreenState
import app.sst.pinto.data.models.SocketMessage
import app.sst.pinto.network.SocketManager
import app.sst.pinto.utils.TimeoutManager
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import app.sst.pinto.config.ConfigManager

class PaymentViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "PaymentViewModel"
    private val socketManager = SocketManager.getInstance()
    private val timeoutManager = TimeoutManager.getInstance()
    private val configManager = ConfigManager.getInstance(getApplication())

    // Store the server URL as a class property
//    private var serverUrl: String = "ws://192.168.2.112:5001"
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

        // Monitor timeout state to show screensaver
        viewModelScope.launch {
            timeoutManager.timeoutOccurred.collect { occurred ->
                Log.d(TAG, "Timeout state changed: $occurred")
                if (occurred) {
                    Log.d(TAG, "Timeout occurred, showing screensaver")
                    // Pause timers when showing screensaver
                    timeoutManager.pauseTimersForScreensaver()

                    // First set to false to guarantee state change
                    _isScreensaverVisible.value = false
                    // Then set to true
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
                message?.let {
                    Log.d(TAG, "Received socket message: $it")
                    processSocketMessage(it)
                }
            }
        }

        // Monitor socket connection state
        // In the PaymentViewModel.kt file
// Update the code to directly switch to ConnectionError

// For example, in the socketManager connection state collector:
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
        // Save current state before changing to screensaver
        if (_screenState.value !is PaymentScreenState.DeviceError &&
            _screenState.value !is PaymentScreenState.ConnectionError) {
            lastActiveState = _screenState.value
            Log.d(TAG, "Saved last active state: ${lastActiveState?.javaClass?.simpleName}")
        }

        // Cancel any active transaction
        cancelPayment(isTimeout = true)

        // Note: We don't need to explicitly show screensaver here as it's handled by
        // the timeout flow collector
    }

    fun selectAmount(amount: Int) {
        Log.d(TAG, "Amount selected: $amount")
        recordUserInteraction()

        // Special code -2 is used to return to amount selection from limit error
        if (amount == -2) {
            val transactionId = UUID.randomUUID().toString()
            currentTransactionId = transactionId
            Log.d(TAG, "Reset requested (code -2), new transaction ID: $transactionId")

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

        val transactionId = currentTransactionId ?: UUID.randomUUID().toString().also {
            currentTransactionId = it
            Log.d(TAG, "Generated new transaction ID: $it")
        }

        // Store the amount for later use
        if (amount > 0) {
            Log.d(TAG, "Storing current amount: $amount")
            currentAmount = amount
        }

        val message = SocketMessage(
            messageType = "USER_ACTION",
            screen = "AMOUNT_SELECT",
            data = MessageData(
                selectedAmount = amount,
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

    fun selectPaymentMethod(method: String) {
        Log.d(TAG, "Payment method selected: $method")
        recordUserInteraction()

        val transactionId = currentTransactionId
        if (transactionId == null) {
            Log.e(TAG, "Cannot select payment method: No active transaction ID")
            return
        }

        val message = SocketMessage(
            messageType = "USER_ACTION",
            screen = "PAYMENT_METHOD",
            data = MessageData(selectionMethod = method),
            transactionId = transactionId,
            timestamp = System.currentTimeMillis()
        )

        sendMessage(message)
    }

    fun cancelPayment(isTimeout: Boolean = false) {
        val transactionId = currentTransactionId
        if (transactionId == null) {
            Log.d(TAG, "Cannot cancel payment: No active transaction ID")
            // If there's no active transaction but this is a timeout,
            // we should still show the screensaver
            if (isTimeout) {
                Log.d(TAG, "No active transaction, but showing screensaver due to timeout")
                forceShowScreensaver()
            }
            return
        }

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

        // If this is a timeout, show the screensaver
        if (isTimeout) {
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

        // Show screensaver immediately - explicitly toggle to false first to ensure state change
        _isScreensaverVisible.value = false
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

        if (!socketManager.isConnected()) {
            Log.d(TAG, "Socket not connected, reconnecting to $serverUrl")
            socketManager.connect(serverUrl)

            // Give it a moment to connect
            viewModelScope.launch {
                delay(1000) // Wait 1 second for connection
                socketManager.ensureConnected()
            }
        } else {
            Log.d(TAG, "Socket is connected, ensuring connection is healthy")
            socketManager.ensureConnected()
        }
    }
    // Add this function to PaymentViewModel
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
    private fun processSocketMessage(jsonMessage: String) {
        try {
            val message = messageAdapter.fromJson(jsonMessage)

            message?.let {
                // Store transaction ID for response
                currentTransactionId = it.transactionId
                Log.d(TAG, "Processing message type: ${it.messageType}, screen: ${it.screen}")

                when (it.messageType) {
                    "SCREEN_CHANGE" -> handleScreenChange(it)
                    "ERROR" -> handleError(it)
                    "STATUS_UPDATE" -> handleStatusUpdate(it)
                }
            }
        } catch (e: Exception) {
            // Handle parsing error
            Log.e(TAG, "Error parsing socket message", e)
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
    private fun handleScreenChange(message: SocketMessage) {
        Log.d(TAG, "Handling screen change to: ${message.screen}")
        when (message.screen) {
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
                Log.d(TAG, "Changing to RECEIPT_QUESTION screen")
                _screenState.value = PaymentScreenState.ReceiptQuestion(showGif = true)
                _isOnAmountScreen.value = false
            }
            "TIMEOUT" -> {
                Log.d(TAG, "Changing to TIMEOUT screen")
                _screenState.value = PaymentScreenState.Timeout
                _isOnAmountScreen.value = false

                // Auto-navigate back to amount selection after a few seconds
                viewModelScope.launch {
                    delay(5000) // Wait 5 seconds
                    requestInitialScreen() // Return to amount selection
                }
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
                Log.d(TAG, "Changing to PAYMENT_METHOD screen with amount: $currentAmount")
                _screenState.value = PaymentScreenState.PaymentMethodSelect(
                    methods = message.data?.methods ?: listOf("DEBIT_CARD", "PAY_BY_BANK"),
                    amount = currentAmount, // Use stored amount
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
            }
            "LIMIT_ERROR" -> {
                val errorMessage = message.data?.errorMessage ?: "Limit exceeded"
                Log.d(TAG, "Changing to LIMIT_ERROR screen with message: $errorMessage")
                _screenState.value = PaymentScreenState.LimitError(
                    errorMessage = errorMessage
                )
                _isOnAmountScreen.value = false
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
                _screenState.value = PaymentScreenState.ThankYou
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
    }

    /**
     * Handle status update messages from the server
     */
    private fun handleStatusUpdate(message: SocketMessage) {
        // Handle status updates if needed
        Log.d(TAG, "Received status update: ${message.data}")
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "ViewModel being cleared, canceling timers")
        timeoutManager.cancelTimers()
    }
}