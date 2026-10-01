package app.sst.pinto.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "device_info")
data class DeviceInfo(
    @PrimaryKey val id: Int = 1, // Single row table
    val currency: String,
    val minTransactionLimit: Double,
    val maxTransactionLimit: Double,
    val transactionFeeType: String,
    val transactionFeeValue: Double,
    val yaspaEnabled: Boolean,
    val paymentProvider: String,
    val requireCardReceipt: Boolean = true, // Default to true for backward compatibility
    // Newland/NNSmart and Switchio/Monet+: when true (default) the SALE is taken
    // first and the daily limit is validated AFTER payment (post-processing),
    // reversing the sale if rejected. When false the PAR is obtained via Card
    // Verification / card_verify and the limit is checked BEFORE any money is
    // captured (pre-processing).
    val nnsmartPostProcessingLimit: Boolean = true,
    // Planet/Integra: false (default) = pre-processing (CardCheckEmv → limit →
    // Sale); true = post-processing (Sale → limit on the sale's card Token →
    // Sale-Reversal if rejected). Kept separate from the flag above because that
    // one defaults to post, which would silently change existing Planet kiosks.
    val planetPostProcessingLimit: Boolean = false
)



