package ru.mishin.pilltracker.view

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mishin.pilltracker.data.DoseIntake
import ru.mishin.pilltracker.data.Medication
import ru.mishin.pilltracker.data.MedicationRepository
import java.io.File
import java.io.InputStream
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.regex.Pattern

data class MedicationGroup(
    val name: String,
    val doses: List<Medication>
)

data class ScheduleUiState(
    val groups: List<MedicationGroup> = emptyList(),
    val selectedDay: LocalDate = LocalDate.now(),
    val courseDays: List<LocalDate> = emptyList(),
    val takenKeys: Set<Long> = emptySet(),
    val isToday: Boolean = true
) {
    fun isTaken(medicationId: Int): Boolean =
        intakeKey(medicationId, selectedDay) in takenKeys

    fun isTakenOn(medicationId: Int, day: LocalDate): Boolean =
        intakeKey(medicationId, day) in takenKeys
}

fun intakeKey(medicationId: Int, day: LocalDate): Long =
    medicationId.toLong() shl 32 or (day.toEpochDay() and 0xFFFFFFFFL)

class MedicationViewModel(
    private val repository: MedicationRepository
) : ViewModel() {
    val medications = repository.allMedications
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val selectedEpochDay = MutableStateFlow(LocalDate.now().toEpochDay())

    val schedule = combine(
        medications,
        repository.allIntakes,
        selectedEpochDay
    ) { meds, intakes, epochDay ->
        val selected = LocalDate.ofEpochDay(epochDay)
        val courseDays = courseDaysOf(meds)
        val clamped = when {
            courseDays.isEmpty() -> selected
            selected in courseDays -> selected
            selected.isBefore(courseDays.first()) -> courseDays.first()
            else -> courseDays.last()
        }
        ScheduleUiState(
            groups = groupMedications(meds),
            selectedDay = clamped,
            courseDays = courseDays,
            takenKeys = intakes.filter { it.taken }
                .map { intakeKey(it.medicationId, LocalDate.ofEpochDay(it.epochDay)) }
                .toSet(),
            isToday = clamped == LocalDate.now()
        )
    }.stateIn(viewModelScope, SharingStarted.Lazily, ScheduleUiState())

    fun selectDay(day: LocalDate) {
        selectedEpochDay.value = day.toEpochDay()
    }

    fun shiftDay(delta: Int) {
        val days = schedule.value.courseDays
        if (days.isEmpty()) return
        val current = schedule.value.selectedDay
        val index = days.indexOf(current).let { if (it < 0) 0 else it }
        days.getOrNull((index + delta).coerceIn(0, days.lastIndex))?.let { selectDay(it) }
    }

    fun selectToday() {
        val today = LocalDate.now()
        val days = schedule.value.courseDays
        selectDay(
            when {
                days.isEmpty() || today in days -> today
                today.isBefore(days.first()) -> days.first()
                else -> days.last()
            }
        )
    }

    fun addMedication(med: Medication, onAdded: (Int) -> Unit) {
        viewModelScope.launch { onAdded(repository.insert(med)) }
    }

    fun toggleTaken(med: Medication, taken: Boolean) {
        val day = schedule.value.selectedDay
        toggleTakenOnDay(med, day, taken)
    }

    fun toggleTakenOnDay(med: Medication, day: LocalDate, taken: Boolean) {
        viewModelScope.launch {
            if (taken) {
                repository.upsertIntake(DoseIntake(med.id, day.toEpochDay(), taken = true))
            } else {
                repository.deleteIntake(med.id, day.toEpochDay())
            }
        }
    }

    fun deleteMedication(med: Medication) {
        viewModelScope.launch { repository.delete(med) }
    }

    fun calculateDaysLeft(med: Medication): Int {
        val start = Instant.ofEpochMilli(med.startTimestamp)
            .atZone(ZoneId.systemDefault()).toLocalDate()
        val remaining = med.durationDays - java.time.temporal.ChronoUnit.DAYS.between(
            start, LocalDate.now()
        ).toInt()
        return remaining.coerceAtLeast(0)
    }

    fun importFromCsv(
        context: Context,
        uri: Uri,
        replaceExisting: Boolean,
        onMedicationAdded: (Medication, Int) -> Unit,
        onComplete: (Int) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val stream = context.applicationContext.contentResolver.openInputStream(uri)
                    ?: throw IllegalArgumentException("Не удалось открыть файл")
                if (replaceExisting) repository.clearAll()

                // Вызываем парсинг и сохранение через репозиторий
                val importedMeds = repository.importFromCsv(stream)

                // Передаем добавленные лекарства для установки будильников
                for (med in importedMeds) {
                    onMedicationAdded(med, med.id)
                }
                withContext(Dispatchers.Main) { onComplete(importedMeds.size) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onError(e.message ?: "Ошибка парсинга CSV") }
            }
        }
    }

    fun importFromGoogleSheetsUrl(
        urlString: String,
        replaceExisting: Boolean,
        onMedicationAdded: (Medication, Int) -> Unit,
        onComplete: (Int) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val id = extractSpreadsheetId(urlString)
                    ?: throw IllegalArgumentException("Ссылка не похожа на Google Таблицу.")
                val gid = extractGid(urlString)
                val export = buildString {
                    append("https://docs.google.com/spreadsheets/d/$id/export?format=csv")
                    if (gid != null) append("&gid=").append(gid)
                }

                if (replaceExisting) repository.clearAll()

                // ИСПРАВЛЕНИЕ: Мы просто вызываем метод репозитория,
                // который сам скачает файл по ссылке, распарсит его и сохранит в БД.
                // Он возвращает готовый список List<Medication>.
                val importedMeds = repository.importFromUrl(export)

                // Передаем добавленные лекарства для установки будильников
                for (med in importedMeds) {
                    onMedicationAdded(med, med.id)
                }

                // Возвращаем результат в UI
                withContext(Dispatchers.Main) { onComplete(importedMeds.size) }

            } catch (e: Exception) {
                val friendly = when {
                    e.message?.contains("Unable to resolve host") == true ||
                            e.message?.contains("No address associated") == true ->
                        "Нет подключения к интернету"
                    e.message?.contains("404") == true ->
                        "Таблица не найдена. Проверьте ссылку"
                    e.message?.contains("403") == true || e.message?.contains("401") == true ->
                        "Доступ запрещён. Откройте доступ по ссылке."
                    e.message?.contains("Ссылка не похожа") == true -> e.message!!
                    else -> "Ошибка загрузки: ${e.message ?: "неизвестная"}"
                }
                withContext(Dispatchers.Main) { onError(friendly) }
            }
        }
    }

    fun exportToCsv(
        context: Context,
        onSuccess: (Uri) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val meds = repository.getAllMedicationsList()
                if (meds.isEmpty()) {
                    withContext(Dispatchers.Main) { onError("Список лекарств пуст") }
                    return@launch
                }

                val days = courseDaysOf(meds)
                val sb = StringBuilder()
                sb.append("\"Лекарство\",\"Время приёма\"")
                val headerFmt = DateTimeFormatter.ofPattern("dd.MM")
                for (day in days) {
                    sb.append(",\"").append(day.format(headerFmt)).append("\"")
                }
                sb.append("\n")

                val taken = schedule.value.takenKeys
                for (m in meds) {
                    val timeText = m.displaySlot()
                    sb.append("\"").append(m.name.replace("\"", "\"\"")).append("\",")
                        .append("\"").append(timeText.replace("\"", "\"\"")).append("\"")
                    for (day in days) {
                        sb.append(if (intakeKey(m.id, day) in taken) ",\"+\"" else ",\"-\"")
                    }
                    sb.append("\n")
                }

                val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                val file = File(dir, "PillTracker_${System.currentTimeMillis()}.csv")
                file.writeText(sb.toString(), Charsets.UTF_8)

                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                withContext(Dispatchers.Main) { onSuccess(uri) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onError(e.message ?: "Ошибка экспорта") }
            }
        }
    }

    private fun extractSpreadsheetId(url: String): String? {
        val matcher = Pattern.compile("/d/([a-zA-Z0-9-_]+)").matcher(url)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun extractGid(url: String): String? {
        val matcher = Pattern.compile("[?&#]gid=([0-9]+)").matcher(url)
        return if (matcher.find()) matcher.group(1) else null
    }
}

// --- Вспомогательные функции для UI ---
fun Medication.displaySlot(): String {
    if (timeLabel.isNotBlank()) return timeLabel
    val time = String.format(Locale.US, "%02d:%02d", hour, minute)
    return if (notes.isBlank()) time else "$time · $notes"
}

fun Medication.slotTitle(): String {
    val label = displaySlot()
    val paren = label.indexOf('(')
    return if (paren > 0) label.substring(0, paren).trim() else label
}

fun Medication.slotSubtitle(): String {
    if (timeLabel.isNotBlank()) {
        return Regex("\\(([^)]+)\\)").find(timeLabel)?.groupValues?.get(1)?.trim().orEmpty()
    }
    return notes
}

fun groupMedications(meds: List<Medication>): List<MedicationGroup> {
    if (meds.isEmpty()) return emptyList()
    val groups = mutableListOf<MedicationGroup>()
    var name = meds.first().name
    var bucket = mutableListOf<Medication>()
    for (med in meds) {
        if (med.name != name && bucket.isNotEmpty()) {
            groups += MedicationGroup(name, bucket.toList())
            name = med.name
            bucket = mutableListOf()
        }
        bucket += med
    }
    if (bucket.isNotEmpty()) groups += MedicationGroup(name, bucket.toList())
    return groups
}

fun courseDaysOf(meds: List<Medication>): List<LocalDate> {
    if (meds.isEmpty()) return emptyList()
    val zone = ZoneId.systemDefault()
    val start = meds.minOf {
        Instant.ofEpochMilli(it.startTimestamp).atZone(zone).toLocalDate()
    }
    val duration = meds.maxOf { it.durationDays.coerceAtLeast(1) }
    return List(duration) { start.plusDays(it.toLong()) }
}