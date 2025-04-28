package app.sst.pinto.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sst.pinto.data.models.MessageData
import app.sst.pinto.data.models.PaymentScreenState
import app.sst.pinto.data.models.SocketMessage
import app.sst.pinto.network.SocketManager
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class PaymentViewModel : ViewModel() {
    private val socketManager = SocketManager.getInstance()

    private val _screenState = MutableStateFlow<PaymentScreenState>(PaymentScreenState.Loading)
    val screenState: StateFlow<PaymentScreenState> = _screenState

    private var currentTransactionId: String? = null
    private var currentAmount: Int = 0 // Track the current amount

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val messageAdapter: JsonAdapter<SocketMessage> =
        moshi.adapter(SocketMessage::class.java)

    init {
        viewModelScope.launch {
            socketManager.connectionState.collect { state ->
                when (state) {
                    SocketManager.ConnectionState.DISCONNECTED -> {
                        _screenState.value = PaymentScreenState.ConnectionError
                    }
                    SocketManager.ConnectionState.CONNECTING -> {
                        _screenState.value = PaymentScreenState.Loading
                    }
                    else -> {} // No direct state change on connected
                }
            }
        }

        viewModelScope.launch {
            socketManager.messageReceived.collect { message ->
                message?.let {
                    processSocketMessage(it)
                }
            }
        }
    }

    fun connectToBackend(serverUrl: String) {
        socketManager.connect(serverUrl)
    }

    fun selectAmount(amount: Int) {
        // Special code -2 is used to return to amount selection from limit error
        if (amount == -2) {
            val transactionId = UUID.randomUUID().toString()
            currentTransactionId = transactionId

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
        }

        // Store the amount for later use
        if (amount > 0) {
            currentAmount = amount
        }

        val message = SocketMessage(
            messageType = "USER_ACTION",
            screen = "AMOUNT_SELECT",
            data = MessageData(
                selectedAmount = amount,
                selectionMethod = if (amount in listOf(20, 40, 60, 80, 100)) "PRESET_BUTTON" else "CUSTOM"
            ),
            transactionId = transactionId,
            timestamp = System.currentTimeMillis()
        )

        sendMessage(message)
    }

    fun selectPaymentMethod(method: String) {
        val transactionId = currentTransactionId ?: return

        val message = SocketMessage(
            messageType = "USER_ACTION",
            screen = "PAYMENT_METHOD",
            data = MessageData(selectionMethod = method),
            transactionId = transactionId,
            timestamp = System.currentTimeMillis()
        )

        sendMessage(message)

        // Handle Pay by Bank scenario - show QR code first
        if (method == "PAY_BY_BANK") {
            _screenState.value = PaymentScreenState.QrCodeDisplay()
        }
    }

    fun cancelPayment() {
        val transactionId = currentTransactionId ?: return

        val message = SocketMessage(
            messageType = "USER_ACTION",
            screen = "CANCEL",
            data = null,
            transactionId = transactionId,
            timestamp = System.currentTimeMillis()
        )

        sendMessage(message)

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

    private fun sendMessage(message: SocketMessage) {
        val jsonMessage = messageAdapter.toJson(message)
        socketManager.sendMessage(jsonMessage)
    }

    private fun processSocketMessage(jsonMessage: String) {
        try {
            val message = messageAdapter.fromJson(jsonMessage)

            message?.let {
                // Store transaction ID for response
                currentTransactionId = it.transactionId

                when (it.messageType) {
                    "SCREEN_CHANGE" -> handleScreenChange(it)
                    "ERROR" -> handleError(it)
                    "STATUS_UPDATE" -> handleStatusUpdate(it)
                }
            }
        } catch (e: Exception) {
            // Handle parsing error
            _screenState.value = PaymentScreenState.DeviceError("Invalid message format: ${e.message}")
        }
    }

    private fun handleScreenChange(message: SocketMessage) {
        when (message.screen) {
            "AMOUNT_SELECT" -> {
                val data = message.data
                if (data != null && data.amounts != null && data.currency != null) {
                    _screenState.value = PaymentScreenState.AmountSelect(
                        amounts = data.amounts,
                        currency = data.currency,
                        showOtherOption = data.showOtherOption ?: true
                    )
                }
            }
            "KEYPAD" -> {
                _screenState.value = PaymentScreenState.KeypadEntry(
                    currency = message.data?.currency ?: "£",
                    minAmount = 10, // Default
                    maxAmount = 300 // Default
                )
            }
            "PAYMENT_METHOD" -> {
                _screenState.value = PaymentScreenState.PaymentMethodSelect(
                    methods = message.data?.methods ?: listOf("DEBIT_CARD", "PAY_BY_BANK"),
                    amount = currentAmount, // Use stored amount instead of 0
                    currency = message.data?.currency ?: "£",
                    allowCancel = message.data?.allowCancel ?: true
                )
            }
            "PROCESSING" -> {
                _screenState.value = PaymentScreenState.Processing
            }
            "SUCCESS" -> {
                _screenState.value = PaymentScreenState.TransactionSuccess(
                    showReceipt = true
                )
            }
            "FAILED" -> {
                _screenState.value = PaymentScreenState.TransactionFailed(
                    errorMessage = message.data?.errorMessage
                )
            }
            "LIMIT_ERROR" -> {
                _screenState.value = PaymentScreenState.LimitError(
                    limit = message.data?.limit ?: 300,
                    remaining = message.data?.remaining ?: 0,
                    currency = message.data?.currency ?: "£"
                )
            }
            "PRINT_TICKET" -> {
                _screenState.value = PaymentScreenState.PrintingTicket
            }
            "COLLECT_TICKET" -> {
                _screenState.value = PaymentScreenState.CollectTicket
            }
            "THANK_YOU" -> {
                _screenState.value = PaymentScreenState.ThankYou
            }
            "DEVICE_ERROR" -> {
                _screenState.value = PaymentScreenState.DeviceError(
                    errorMessage = message.data?.errorMessage ?: "Unknown device error"
                )
            }
        }
    }

    private fun handleError(message: SocketMessage) {
        _screenState.value = PaymentScreenState.TransactionFailed(
            errorMessage = message.data?.errorMessage
        )
    }

    private fun handleStatusUpdate(message: SocketMessage) {
        // Handle status updates if needed
    }
}