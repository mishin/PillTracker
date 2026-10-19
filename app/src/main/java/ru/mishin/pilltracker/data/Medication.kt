package ru.mishin.pilltracker.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "medications")
data class Medication(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val hour: Int,
    val minute: Int,
    val startTimestamp: Long, // Когда начали (например, в четверг)
    val durationDays: Int,    // Длительность курса
    val isTakenToday: Boolean = false // Галочка приема
)

@Dao
interface MedicationDao {
    // ==========================================
    // ДОБАВИТЬ ЭТОТ МЕТОД (Одноразовое чтение):
    @Query("SELECT * FROM medications")
    suspend fun getAllMedicationsList(): List<Medication>
    // ==========================================
    @Query("SELECT * FROM medications ORDER BY hour, minute ASC")
    fun getAllMedications(): Flow<List<Medication>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(medication: Medication): Long

    @Update
    suspend fun update(medication: Medication)

    @Delete
    suspend fun delete(medication: Medication)
    
    // Сброс галочек в полночь
    @Query("UPDATE medications SET isTakenToday = 0")
    suspend fun resetDailyIntake()
}

// Было: @Database(entities = [Medication::class], version = 1)
// Стало: добавляем параметр exportSchema = false
@Database(entities = [Medication::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun medicationDao(): MedicationDao
}