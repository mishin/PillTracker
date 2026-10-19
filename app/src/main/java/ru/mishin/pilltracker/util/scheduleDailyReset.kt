package ru.mishin.pilltracker.util

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit
// Добавлен импорт Worker'а
import ru.mishin.pilltracker.service.DailyResetWorker

fun scheduleDailyReset(context: Context) {
    val currentDate = Calendar.getInstance()
    val dueDate = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        // Если полночь сегодня уже прошла, ставим на следующую
        if (before(currentDate)) {
            add(Calendar.HOUR_OF_DAY, 24)
        }
    }

    val timeDiff = dueDate.timeInMillis - currentDate.timeInMillis

    val dailyWorkRequest = PeriodicWorkRequestBuilder<DailyResetWorker>(24, TimeUnit.HOURS)
        .setInitialDelay(timeDiff, TimeUnit.MILLISECONDS)
        .build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "daily_medication_reset",
        ExistingPeriodicWorkPolicy.KEEP, // Оставляем старую задачу, если она уже запланирована
        dailyWorkRequest
    )
}