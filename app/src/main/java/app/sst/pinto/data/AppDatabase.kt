package app.sst.pinto.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DeviceInfo::class],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deviceInfoDao(): DeviceInfoDao
    
    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        
        // Migration from version 1 to 2: Add requireCardReceipt column
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE device_info ADD COLUMN requireCardReceipt INTEGER NOT NULL DEFAULT 1")
            }
        }

        // Migration from version 2 to 3: Add nnsmartPostProcessingLimit column
        // (1 = post-processing / sale-first, the default behavior; can be
        // switched to pre-processing / Card Verification from the Settings screen)
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE device_info ADD COLUMN nnsmartPostProcessingLimit INTEGER NOT NULL DEFAULT 1")
            }
        }

        // Migration from version 3 to 4: correct the default for devices that
        // were configured under the original release, where the column defaulted
        // to 0 (pre-processing). Post-processing is now the intended default, so
        // flip the stored value to 1. Operators can still switch back to
        // pre-processing from the Settings screen.
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("UPDATE device_info SET nnsmartPostProcessingLimit = 1")
            }
        }

        // Migration from version 4 to 5: Add planetPostProcessingLimit column
        // (0 = pre-processing, the existing Planet behavior).
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE device_info ADD COLUMN planetPostProcessingLimit INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "pinto_database"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}




