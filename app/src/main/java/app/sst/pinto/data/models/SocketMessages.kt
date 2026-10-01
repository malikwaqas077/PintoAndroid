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
    // When false: pre-processing (read card / verify before capture).
    // When true: post-processing (capture first, reverse if limit exceeded).
    @Json(name = "nnsmartPostProcessingLimit") val nnsmartPostProcessingLimit: Boolean? = null,
    // Planet/Integra equivalent of the above (default pre-processing).
    @Json(name = "planetPostProcessingLimit") val planetPostProcessingLimit: Boolean? = null,
    
    // Card check result fields.
    // For Integra this carries the card token from CardCheckEmv.
    // For NNSmart (Newland) this carries the PAR returned by the SALE —
    // sent on the same field so the backend contract is unchanged.
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
    @Json(name = "reversalAmount") val reversalAmount: Int? = null,

    // Ticket redemption fields (REDEEM_REQUEST / REDEEM_BREAKDOWN / REDEEM_RESULT).
    // bankRedeemAmount = card spend + winnings (payable to the customer's bank card),
    // cashRedeemAmount = cash spend (must be collected at the cashier desk - AML rule).
    @Json(name = "ticketId") val ticketId: String? = null,
    @Json(name = "bankRedeemAmount") val bankRedeemAmount: Int? = null,
    @Json(name = "cashRedeemAmount") val cashRedeemAmount: Int? = null,
    @Json(name = "totalRedeemAmount") val totalRedeemAmount: Int? = null
)

// Screen states for the app
sealed class PaymentScreenState {
    object Loading : PaymentScreenState()

    /**
     * Shown when the app cannot talk to a required backend.
     * [title]/[detail] describe the primary failure; [secondaryDetail] can note portal vs payment.
     */
    data class ConnectionError(
        val title: String = "Connection Error",
        val detail: String = "Unable to connect. Please check your connection.",
        val secondaryDetail: String? = null
    ) : PaymentScreenState()
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

    data class ReversingTransaction(
        val message: String = "Reversing transaction on terminal..."
    ) : PaymentScreenState()

    data class ReversalSuccess(
        val message: String = "Transaction reversed successfully"
    ) : PaymentScreenState()

    data class LimitError(
        val errorMessage: String
    ) : PaymentScreenState()

    object PrintingTicket : PaymentScreenState()

    object CollectTicket : PaymentScreenState()

    object ThankYou : PaymentScreenState()

    data class DeviceError(val errorMessage: String) : PaymentScreenState()

    // QR code display screen - updated with paymentUrl parameter
    data class QrCodeDisplay(val paymentUrl: String = "") : PaymentScreenState()

    // --- Ticket redemption (redeem to bank account) screens ---

    // Server initiated a redemption; waiting for the amount breakdown.
    data class RedeemInitiated(
        val ticketId: String?,
        val currency: String
    ) : PaymentScreenState()

    // Breakdown received; customer must Continue or Cancel.
    data class RedeemConfirmation(
        val ticketId: String?,
        val bankAmount: Int,
        val cashAmount: Int,
        val totalAmount: Int,
        val currency: String
    ) : PaymentScreenState()

    // Sending the bank portion to the customer's card.
    data class RedeemProcessing(
        val message: String = "Sending funds to your bank card..."
    ) : PaymentScreenState()

    // Redemption completed; bank portion sent, cash portion (if any) printed as ticket.
    data class RedeemSuccess(
        val bankAmount: Int,
        val cashAmount: Int,
        val currency: String
    ) : PaymentScreenState()

    // Customer cancelled, confirmation timed out, or nothing was bank-redeemable;
    // ticket remains valid in all cases.
    data class RedeemCancelled(
        val message: String,
        val title: String = "REDEMPTION CANCELLED"
    ) : PaymentScreenState()

    // Redemption failed; ticket remains valid and unredeemed.
    data class RedeemFailed(
        val errorMessage: String
    ) : PaymentScreenState()
}