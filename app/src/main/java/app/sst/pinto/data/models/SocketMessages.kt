package app.sst.pinto.data.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class SocketMessage(
    @Json(name = "messageType") val messageType: String,
    @Json(name = "screen") val screen: String,
    @Json(name = "data") val data: MessageData?,
    @Json(name = "transactionId") val transactionId: String,
    @Json(name = "timestamp") val timestamp: Long
)

@JsonClass(generateAdapter = true)
data class MessageData(
    // Common fields
    @Json(name = "amounts") val amounts: List<Int>? = null,
    @Json(name = "currency") val currency: String? = null,
    @Json(name = "showOtherOption") val showOtherOption: Boolean? = null,

    // Payment method fields
    @Json(name = "methods") val methods: List<String>? = null,
    @Json(name = "allowCancel") val allowCancel: Boolean? = null,

    // Amount selection response
    @Json(name = "selectedAmount") val selectedAmount: Int? = null,
    @Json(name = "selectionMethod") val selectionMethod: String? = null,

    // Error information
    @Json(name = "errorCode") val errorCode: String? = null,
    @Json(name = "errorMessage") val errorMessage: String? = null,

    // Limit information
    @Json(name = "limit") val limit: Int? = null,
    @Json(name = "remaining") val remaining: Int? = null,

    // QR code information
    @Json(name = "paymentUrl") val paymentUrl: String? = null,
    
    // Device configuration fields (from server DEVICE_INFO message)
    @Json(name = "minTransactionLimit") val minTransactionLimit: Double? = null,
    @Json(name = "maxTransactionLimit") val maxTransactionLimit: Double? = null,
    @Json(name = "transactionFeeType") val transactionFeeType: String? = null,
    @Json(name = "transactionFeeValue") val transactionFeeValue: Double? = null,
    @Json(name = "yaspaEnabled") val yaspaEnabled: Boolean? = null,
    @Json(name = "paymentProvider") val paymentProvider: String? = null,
    @Json(name = "requireCardReceipt") val requireCardReceipt: Boolean? = null,
    
    // Card check result fields
    @Json(name = "cardToken") val cardToken: String? = null,
    
    // Device information request fields
    @Json(name = "requestType") val requestType: String? = null,
    @Json(name = "deviceIpAddress") val deviceIpAddress: String? = null,
    @Json(name = "deviceSerialNumber") val deviceSerialNumber: String? = null,
    
    // Payment result fields (for PAYMENT_RESULT and REVERSAL_RESULT)
    @Json(name = "paymentDetails") val paymentDetails: Map<String, String>? = null,
    
    // Refund/Reversal request fields
    @Json(name = "originalTransactionId") val originalTransactionId: String? = null,
    @Json(name = "originalRequesterTransRefNum") val originalRequesterTransRefNum: String? = null,
    @Json(name = "reversalAmount") val reversalAmount: Int? = null
)

// Screen states for the app
sealed class PaymentScreenState {
    object Loading : PaymentScreenState()
    object ConnectionError : PaymentScreenState()
    // Add to the PaymentScreenState sealed class
    data class RefundProcessing(
        val errorMessage: String? = null
    ) : PaymentScreenState()
    data class ReceiptQuestion(
        val showGif: Boolean = true
    ) : PaymentScreenState()

    data class AmountSelect(
        val amounts: List<Int>,
        val currency: String,
        val showOtherOption: Boolean
    ) : PaymentScreenState()

    data class KeypadEntry(
        val currency: String,
        val minAmount: Int,
        val maxAmount: Int
    ) : PaymentScreenState()

    data class PaymentMethodSelect(
        val methods: List<String>,
        val amount: Int,
        val currency: String,
        val allowCancel: Boolean
    ) : PaymentScreenState()

    object Processing : PaymentScreenState()
    object Timeout : PaymentScreenState()  // ← ADD THIS LINE

    data class MockPaymentCard(
        val amount: Int,
        val currency: String
    ) : PaymentScreenState()

    data class TransactionSuccess(val showReceipt: Boolean) : PaymentScreenState()

    data class TransactionFailed(val errorMessage: String?) : PaymentScreenState()

    data class LimitError(
        val errorMessage: String
    ) : PaymentScreenState()

    object PrintingTicket : PaymentScreenState()

    object CollectTicket : PaymentScreenState()

    object ThankYou : PaymentScreenState()

    data class DeviceError(val errorMessage: String) : PaymentScreenState()

    // QR code display screen - updated with paymentUrl parameter
    data class QrCodeDisplay(val paymentUrl: String = "") : PaymentScreenState()
}