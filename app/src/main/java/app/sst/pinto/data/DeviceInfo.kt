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
    val requireCardReceipt: Boolean = true // Default to true for backward compatibility
)




