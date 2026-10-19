package ru.mishin.pilltracker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
// Добавлен импорт базы данных
import ru.mishin.pilltracker.data.AppDatabase

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            
            // Получаем асинхронный контекст BroadcastReceiver'а
            val pendingResult = goAsync()
            
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // Открываем БД
                    val db = Room.databaseBuilder(
                        context, 
                        AppDatabase::class.java, 
                        "meds_db"
                    ).build()
                    
                    // Получаем все лекарства
                    val medications = db.medicationDao().getAllMedicationsList()
                    
                    // Заново планируем будильник для каждого
                    for (med in medications) {
                        scheduleMedicationAlarm(context, med, med.id)
                    }
                } finally {
                    // Сообщаем системе, что асинхронная работа завершена
                    pendingResult.finish()
                }
            }
        }
    }
}