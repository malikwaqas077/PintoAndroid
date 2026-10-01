package app.sst.pinto.payment

import app.sst.pinto.utils.AppLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Mock payment manager for simulating payment responses.
 * Used when paymentProvider="mock" in device configuration.
 * 
 * This simulates the Planet Integra SDK behavior for testing without requiring
 * actual payment terminal hardware.
 */
object MockPaymentManager {
    private const val TAG = "MockPaymentManager"
    
    /**
     * Perform a mock card check (CardCheckEmv) to validate card and get token.
     * This simulates the Planet SDK CardCheckEmv operation.
     *
     * @param requesterRef unique reference for this transaction
     * @param amountFormatted Optional amount as a string in the format "10.00"
     */
    suspend fun performCardCheck(
        requesterRef: String,
        amountFormatted: String? = null
    ): CardCheckResult = withContext(Dispatchers.IO) {
        AppLog.d(TAG, "Starting mock card check: ref=$requesterRef, amount=$amountFormatted")
        
        // Simulate network delay (1-2 seconds)
        delay((1000..2000).random().toLong())
        
        // Generate a mock token
        val mockToken = "MOCK_TOKEN_${UUID.randomUUID().toString().substring(0, 8).uppercase()}"
        val mockSequenceNumber = "${System.currentTimeMillis() % 100000}"
        
        AppLog.d(TAG, "Mock card check completed: token=$mockToken, sequenceNumber=$mockSequenceNumber")
        
        CardCheckResult(
            success = true,
            token = mockToken,
            resultCode = "A",
            message = "APPROVED",
            sequenceNumber = mockSequenceNumber,
            rawOptions = mapOf(
                "Result" to "A",
                "Message" to "APPROVED",
                "Token" to mockToken,
                "CardToken" to mockToken,
                "SequenceNumber" to mockSequenceNumber
            )
        )
    }
    
    /**
     * Perform a mock sale transaction.
     * This simulates the Planet SDK Sale operation.
     * 
     * Special behavior:
     * - Amount 101.00 returns daily limit exceeded error
     * - Amount 102.00 returns an approved DCC sale (GBP -> EUR) with receipt text
     * - All other amounts return successful payment response
     *
     * @param amountFormatted amount as a string in the format "10.00"
     * @param requesterRef unique reference for this transaction
     */
    suspend fun performSale(
        amountFormatted: String,
        requesterRef: String
    ): PlanetPaymentResult = withContext(Dispatchers.IO) {
        AppLog.d(TAG, "Starting mock sale: amount=$amountFormatted, ref=$requesterRef")
        
        // Simulate network delay (2-4 seconds)
        delay((2000..4000).random().toLong())
        
        // Special case: amount 101.00 triggers daily limit exceeded error
        val amountValue = amountFormatted.toDoubleOrNull() ?: 0.0
        if (amountValue == 101.00) {
            AppLog.d(TAG, "Mock sale: Daily limit exceeded for amount 101.00")
            return@withContext PlanetPaymentResult(
                success = false,
                resultCode = "D",
                bankResultCode = "61",
                message = "DAILY_LIMIT_EXCEEDED",
                requesterTransRefNum = requesterRef,
                rawOptions = mapOf(
                    "Result" to "D",
                    "BankResultCode" to "61",
                    "Message" to "DAILY_LIMIT_EXCEEDED",
                    "RequesterTransRefNum" to requesterRef
                )
            )
        }
        
        // Special case: amount 102.00 simulates a DCC sale (cardholder paid in EUR)
        if (amountValue == 102.00) {
            AppLog.d(TAG, "Mock sale: DCC approved for amount 102.00")
            val rate = "1.1650000"
            val eurAmount = String.format("%.2f", amountValue * rate.toDouble())
            val receipt = { copy: String ->
                """
                |--------------------------------
                |         APPROVED
                |--------------------------------
                |SALE
                |PAN..........: xxxxxxxxxxxx1712
                |CARD TYPE....: MASTERCARD
                |TRANSACTION NO...: $requesterRef
                |SALE CURRENCY....: GBP
                |TOTAL AMOUNT.....: GBP $amountFormatted
                |--------------------------------
                |FX RATE: 1 GBP = 1.1650EUR
                |INCL. 3% OVER ECB RATE
                |TRANS. CURRENCY..:EUR
                |TRANS. AMOUNT....:EUR $eurAmount
                |(X)I HAVE BEEN OFFERED A CHOICE OF PAYMENT CURRENCIES.
                |THIS CURRENCY CONVERSION SERVICE IS OFFERED BY THE MERCHANT.
                |--------------------------------
                |      $copy RECEIPT
                """.trimMargin()
            }
            return@withContext PlanetPaymentResult(
                success = true,
                resultCode = "A",
                bankResultCode = "00",
                message = "APPROVED",
                requesterTransRefNum = requesterRef,
                rawOptions = mapOf(
                    "Result" to "A",
                    "BankResultCode" to "00",
                    "Message" to "Approval",
                    "RequesterTransRefNum" to requesterRef,
                    "Amount" to amountFormatted,
                    "AmountUsed" to eurAmount,
                    "Currency" to "GBP",
                    "CurrencyUsed" to "EUR",
                    "DCCFlag" to "Y",
                    "DCCReasonInd" to "MI",
                    "LocalAmount" to amountFormatted,
                    "LocalCurrency" to "",
                    "BinAmount" to eurAmount,
                    "BinCurrency" to "EUR",
                    "BinRate" to rate,
                    "DCCMarkup" to "3",
                    "DCCSponsor" to "MarkUp ECB",
                    "PrintData1" to receipt("MERCHANT"),
                    "PrintData2" to receipt("CARDHOLDER")
                )
            )
        }

        // All other amounts succeed
        AppLog.d(TAG, "Mock sale: Payment successful")
        PlanetPaymentResult(
            success = true,
            resultCode = "A",
            bankResultCode = "00",
            message = "APPROVED",
            requesterTransRefNum = requesterRef,
            rawOptions = mapOf(
                "Result" to "A",
                "BankResultCode" to "00",
                "Message" to "APPROVED",
                "RequesterTransRefNum" to requesterRef
            )
        )
    }
    
    /**
     * Perform a mock cancel request.
     * This simulates canceling a transaction.
     *
     * @param requesterRef unique reference for this transaction
     * @param sequenceNumberToCancel sequence number from the CardCheckEmv response
     */
    suspend fun performCancel(
        requesterRef: String,
        sequenceNumberToCancel: String?
    ): Boolean = withContext(Dispatchers.IO) {
        AppLog.d(TAG, "Starting mock cancel: ref=$requesterRef, sequenceNumber=$sequenceNumberToCancel")
        
        // Simulate network delay (500ms - 1 second)
        delay((500..1000).random().toLong())
        
        AppLog.d(TAG, "Mock cancel: Transaction cancelled successfully")
        true
    }
    
    /**
     * Perform a mock sale reversal (refund) for a previously successful sale transaction.
     * This simulates reversing/refunding a sale transaction.
     *
     * @param amountFormatted amount as a string in the format "10.00" (must match original sale amount)
     * @param requesterRef unique reference for this reversal transaction
     * @param originalRequesterRef the RequesterTransRefNum from the original sale transaction
     */
    suspend fun performSaleReversal(
        amountFormatted: String,
        requesterRef: String,
        originalRequesterRef: String
    ): PlanetPaymentResult = withContext(Dispatchers.IO) {
        AppLog.d(TAG, "Starting mock sale reversal: amount=$amountFormatted, ref=$requesterRef, originalRef=$originalRequesterRef")
        
        // Simulate network delay (2-4 seconds)
        delay((2000..4000).random().toLong())
        
        // Mock reversals always succeed
        AppLog.d(TAG, "Mock sale reversal: Reversal successful")
        PlanetPaymentResult(
            success = true,
            resultCode = "A",
            bankResultCode = "00",
            message = "REVERSAL_APPROVED",
            requesterTransRefNum = requesterRef,
            rawOptions = mapOf(
                "Result" to "A",
                "BankResultCode" to "00",
                "Message" to "REVERSAL_APPROVED",
                "RequesterTransRefNum" to requesterRef,
                "OriginalRequesterTransRefNum" to originalRequesterRef
            )
        )
    }

    /**
     * Perform a mock ticket-redemption payout (refund of the bank-redeemable
     * portion to the customer's card).
     *
     * Special behavior (mirrors the 101.00 sale rule):
     * - Bank amount 66.00 returns a declined payout so the "ticket couldn't be
     *   redeemed" path can be tested end to end
     * - All other amounts return a successful payout response
     *
     * @param amountFormatted bank-redeemable amount as a string in the format "10.00"
     * @param requesterRef unique reference for this redemption transaction
     */
    suspend fun performRedeem(
        amountFormatted: String,
        requesterRef: String
    ): PlanetPaymentResult = withContext(Dispatchers.IO) {
        AppLog.d(TAG, "Starting mock redeem payout: amount=$amountFormatted, ref=$requesterRef")

        // Simulate terminal/network delay (2-4 seconds)
        delay((2000..4000).random().toLong())

        val amountValue = amountFormatted.toDoubleOrNull() ?: 0.0
        if (amountValue == 66.00) {
            AppLog.d(TAG, "Mock redeem: Payout declined for amount 66.00 (test trigger)")
            return@withContext PlanetPaymentResult(
                success = false,
                resultCode = "D",
                bankResultCode = "05",
                message = "REDEEM_DECLINED",
                requesterTransRefNum = requesterRef,
                rawOptions = mapOf(
                    "Result" to "D",
                    "BankResultCode" to "05",
                    "Message" to "REDEEM_DECLINED",
                    "RequesterTransRefNum" to requesterRef
                )
            )
        }

        AppLog.d(TAG, "Mock redeem: Payout successful")
        PlanetPaymentResult(
            success = true,
            resultCode = "A",
            bankResultCode = "00",
            message = "REDEEM_APPROVED",
            requesterTransRefNum = requesterRef,
            rawOptions = mapOf(
                "Result" to "A",
                "BankResultCode" to "00",
                "Message" to "REDEEM_APPROVED",
                "RequesterTransRefNum" to requesterRef
            )
        )
    }
}



