package ru.mishin.pilltracker.service

import android.content.Context
import androidx.room.Room
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
// Добавлен импорт базы данных
import ru.mishin.pilltracker.data.AppDatabase
class DailyResetWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            // Инициализируем БД
            val db = Room.databaseBuilder(
                applicationContext,
                AppDatabase::class.java, 
                "meds_db"
            ).build()
            
            // Сбрасываем галочки
            db.medicationDao().resetDailyIntake()
            
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}