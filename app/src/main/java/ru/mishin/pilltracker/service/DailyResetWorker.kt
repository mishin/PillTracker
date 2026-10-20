package ru.mishin.pilltracker.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ru.mishin.pilltracker.data.DatabaseProvider

class DailyResetWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            DatabaseProvider.get(applicationContext).medicationDao().resetDailyIntake()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}