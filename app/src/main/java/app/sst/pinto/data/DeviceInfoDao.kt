package app.sst.pinto.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceInfoDao {
    @Query("SELECT * FROM device_info WHERE id = 1")
    fun getDeviceInfo(): Flow<DeviceInfo?>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDeviceInfo(deviceInfo: DeviceInfo)

    // Toggle the Newland/NNSmart limit-processing mode without rewriting the
    // whole row. true = post-processing (sale-first), false = pre-processing.
    @Query("UPDATE device_info SET nnsmartPostProcessingLimit = :enabled WHERE id = 1")
    suspend fun updateNnsmartPostProcessingLimit(enabled: Boolean)

    @Query("DELETE FROM device_info WHERE id = 1")
    suspend fun deleteDeviceInfo()
}



