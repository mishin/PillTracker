package ru.mishin.pilltracker.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "medications")
data class Medication(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val hour: Int,
    val minute: Int,
    val startTimestamp: Long,
    val durationDays: Int,
    val isTakenToday: Boolean = false,
    val notes: String = ""
)

@Dao
interface MedicationDao {
    @Query("SELECT * FROM medications")
    suspend fun getAllMedicationsList(): List<Medication>

    @Query("SELECT * FROM medications ORDER BY hour, minute ASC")
    fun getAllMedications(): Flow<List<Medication>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(medication: Medication): Long

    @Update
    suspend fun update(medication: Medication)

    @Delete
    suspend fun delete(medication: Medication)

    @Query("UPDATE medications SET isTakenToday = 0")
    suspend fun resetDailyIntake()

    @Query("DELETE FROM medications")
    suspend fun clearAll(): Int
}

@Database(entities = [Medication::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun medicationDao(): MedicationDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE medications ADD COLUMN notes TEXT NOT NULL DEFAULT ''"
                )
            }
        }
    }
}