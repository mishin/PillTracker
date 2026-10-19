package ru.mishin.pilltracker.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar
// Добавлен импорт модели данных
import ru.mishin.pilltracker.data.Medication

fun scheduleMedicationAlarm(context: Context, medication: Medication, medId: Int) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val intent = Intent(context, MedicationAlarmReceiver::class.java).apply {
        putExtra("MED_NAME", medication.name)
        putExtra("MED_ID", medId)
    }

    val pendingIntent = PendingIntent.getBroadcast(
        context, medId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, medication.hour)
        set(Calendar.MINUTE, medication.minute)
        set(Calendar.SECOND, 0)
        // Если время уже прошло, ставим на завтра
        if (timeInMillis <= System.currentTimeMillis()) {
            add(Calendar.DAY_OF_YEAR, 1)
        }
    }

    alarmManager.setExactAndAllowWhileIdle(
        AlarmManager.RTC_WAKEUP,
        calendar.timeInMillis,
        pendingIntent
    )
}