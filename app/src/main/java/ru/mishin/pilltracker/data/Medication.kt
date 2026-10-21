package ru.mishin.pilltracker.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
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
    val notes: String = "",
    val timeLabel: String = ""
)

@Entity(
    tableName = "dose_intakes",
    primaryKeys = ["medicationId", "epochDay"],
    foreignKeys = [
        ForeignKey(
            entity = Medication::class,
            parentColumns = ["id"],
            childColumns = ["medicationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("medicationId")]
)
data class DoseIntake(
    val medicationId: Int,
    val epochDay: Long,
    val taken: Boolean = true
)

@Dao
interface MedicationDao {
    @Query("SELECT * FROM medications")
    suspend fun getAllMedicationsList(): List<Medication>

    @Query("SELECT * FROM medications ORDER BY id ASC")
    fun getAllMedications(): Flow<List<Medication>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(medication: Medication): Long

    @Update
    suspend fun update(medication: Medication)

    @Delete
    suspend fun delete(medication: Medication)

    @Query("DELETE FROM medications")
    suspend fun clearAll(): Int

    @Query("SELECT * FROM dose_intakes")
    fun getAllIntakes(): Flow<List<DoseIntake>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertIntake(intake: DoseIntake)

    @Query("DELETE FROM dose_intakes WHERE medicationId = :medicationId AND epochDay = :epochDay")
    suspend fun deleteIntake(medicationId: Int, epochDay: Long)
}

@Database(
    entities = [Medication::class, DoseIntake::class],
    version = 4, // ИСПРАВЛЕНИЕ: Изменили версию базы данных с 3 на 4
    exportSchema = false
)
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

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE medications ADD COLUMN timeLabel TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS dose_intakes (
                        medicationId INTEGER NOT NULL,
                        epochDay INTEGER NOT NULL,
                        taken INTEGER NOT NULL DEFAULT 1,
                        PRIMARY KEY(medicationId, epochDay),
                        FOREIGN KEY(medicationId) REFERENCES medications(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_dose_intakes_medicationId ON dose_intakes(medicationId)"
                )
            }
        }

        // НОВАЯ МИГРАЦИЯ: Удаляем колонку isTakenToday
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // В SQLite нельзя просто удалить колонку, поэтому пересоздаем таблицу
                db.execSQL("""
                    CREATE TABLE medications_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        hour INTEGER NOT NULL,
                        minute INTEGER NOT NULL,
                        startTimestamp INTEGER NOT NULL,
                        durationDays INTEGER NOT NULL,
                        notes TEXT NOT NULL,
                        timeLabel TEXT NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO medications_new (id, name, hour, minute, startTimestamp, durationDays, notes, timeLabel)
                    SELECT id, name, hour, minute, startTimestamp, durationDays, notes, timeLabel FROM medications
                """.trimIndent())
                db.execSQL("DROP TABLE medications")
                db.execSQL("ALTER TABLE medications_new RENAME TO medications")
            }
        }
    }
}
