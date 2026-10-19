package ru.mishin.pilltracker.view

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
// Добавлены импорты для работы с БД
import ru.mishin.pilltracker.data.Medication
import ru.mishin.pilltracker.data.MedicationDao
class MedicationViewModel(private val dao: MedicationDao) : ViewModel() {
    val medications = dao.getAllMedications()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun addMedication(med: Medication, onAdded: (Int) -> Unit) {
        viewModelScope.launch {
            val id = dao.insert(med)
            onAdded(id.toInt())
        }
    }

    fun toggleTakenStatus(med: Medication, isTaken: Boolean) {
        viewModelScope.launch {
            dao.update(med.copy(isTakenToday = isTaken))
        }
    }

    fun deleteMedication(med: Medication) {
        viewModelScope.launch {
            dao.delete(med)
        }
    }

    // Подсчет оставшихся дней курса
    fun calculateDaysLeft(med: Medication): Int {
        val currentMillis = System.currentTimeMillis()
        val diffMillis = currentMillis - med.startTimestamp
        val daysPassed = (diffMillis / (1000 * 60 * 60 * 24)).toInt()
        val daysLeft = med.durationDays - daysPassed
        return if (daysLeft > 0) daysLeft else 0
    }
}