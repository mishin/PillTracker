package ru.mishin.pilltracker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.mishin.pilltracker.data.DatabaseProvider

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = DatabaseProvider.get(context)
                    val medications = db.medicationDao().getAllMedicationsList()
                    for (med in medications) {
                        scheduleMedicationAlarm(context, med, med.id)
                    }
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}