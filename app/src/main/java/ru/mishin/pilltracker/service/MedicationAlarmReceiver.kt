package ru.mishin.pilltracker.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
// Добавлен импорт главного экрана
import ru.mishin.pilltracker.MainActivity

import java.util.Calendar
import ru.mishin.pilltracker.data.DatabaseProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
class MedicationAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val medName = intent.getStringExtra("MED_NAME") ?: "Лекарство"
        val medId = intent.getIntExtra("MED_ID", 0)
        val medHour = intent.getIntExtra("MED_HOUR", 8)
        val medMinute = intent.getIntExtra("MED_MINUTE", 0)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "medication_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Напоминания о лекарствах", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        // Интент для открытия приложения при клике
        val contentIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, medId, contentIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Время приема!")
            .setContentText("Пора принять: $medName")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(medId, notification)

        // КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: Планируем будильник на завтра
        val nextIntent = Intent(context, MedicationAlarmReceiver::class.java).apply {
            putExtra("MED_NAME", medName)
            putExtra("MED_ID", medId)
            putExtra("MED_HOUR", medHour)
            putExtra("MED_MINUTE", medMinute)
        }
        val nextPendingIntent = PendingIntent.getBroadcast(
            context, medId, nextIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextCalendar = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, medHour)
            set(Calendar.MINUTE, medMinute)
            set(Calendar.SECOND, 0)
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            nextCalendar.timeInMillis,
            nextPendingIntent
        )
    }
}