package app.sst.pinto.payment

import android.util.Log
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
        Log.d(TAG, "Starting mock card check: ref=$requesterRef, amount=$amountFormatted")
        
        // Simulate network delay (1-2 seconds)
        delay((1000..2000).random().toLong())
        
        // Generate a mock token
        val mockToken = "MOCK_TOKEN_${UUID.randomUUID().toString().substring(0, 8).uppercase()}"
        val mockSequenceNumber = "${System.currentTimeMillis() % 100000}"
        
        Log.d(TAG, "Mock card check completed: token=$mockToken, sequenceNumber=$mockSequenceNumber")
        
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
     * - All other amounts return successful payment response
     *
     * @param amountFormatted amount as a string in the format "10.00"
     * @param requesterRef unique reference for this transaction
     */
    suspend fun performSale(
        amountFormatted: String,
        requesterRef: String
    ): PlanetPaymentResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting mock sale: amount=$amountFormatted, ref=$requesterRef")
        
        // Simulate network delay (2-4 seconds)
        delay((2000..4000).random().toLong())
        
        // Special case: amount 101.00 triggers daily limit exceeded error
        val amountValue = amountFormatted.toDoubleOrNull() ?: 0.0
        if (amountValue == 101.00) {
            Log.d(TAG, "Mock sale: Daily limit exceeded for amount 101.00")
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
        
        // All other amounts succeed
        Log.d(TAG, "Mock sale: Payment successful")
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
        Log.d(TAG, "Starting mock cancel: ref=$requesterRef, sequenceNumber=$sequenceNumberToCancel")
        
        // Simulate network delay (500ms - 1 second)
        delay((500..1000).random().toLong())
        
        Log.d(TAG, "Mock cancel: Transaction cancelled successfully")
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
        Log.d(TAG, "Starting mock sale reversal: amount=$amountFormatted, ref=$requesterRef, originalRef=$originalRequesterRef")
        
        // Simulate network delay (2-4 seconds)
        delay((2000..4000).random().toLong())
        
        // Mock reversals always succeed
        Log.d(TAG, "Mock sale reversal: Reversal successful")
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
}



