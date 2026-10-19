package ru.mishin.pilltracker.view

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mishin.pilltracker.data.Medication
import ru.mishin.pilltracker.data.MedicationDao
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Locale

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

    /**
     * Импорт списка лекарств из CSV-файла (экспорт из Excel / Google Таблиц).
     *
     * Ожидаемый формат (соответствует файлу "График приёма лекарств.xlsx"):
     *   Строка 1 (заголовок): "Лекарство","Время приёма","2026-10-17 00:00:00", ...
     *   Строки 2..N:          "<название или пусто>","<текстовое время>","...","..."
     *
     * @param replaceExisting если true — таблица полностью очищается перед вставкой.
     * @param onMedicationAdded вызывается из IO-потока для каждого лекарства (для планирования будильника).
     * @param onComplete вызывается на Main-потоке с числом успешно добавленных записей.
     * @param onError вызывается на Main-потоке с текстом ошибки.
     */
    fun importFromCsv(
        context: Context,
        uri: Uri,
        replaceExisting: Boolean,
        onMedicationAdded: (Medication, Int) -> Unit,
        onComplete: (Int) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            var addedCount = 0
            try {
                // applicationContext — безопасно хранить в корутине IO-диспетчера
                val appContext = context.applicationContext
                val inputStream = appContext.contentResolver.openInputStream(uri)
                    ?: run {
                        withContext(Dispatchers.Main) { onError("Не удалось открыть файл") }
                        return@launch
                    }

                if (replaceExisting) {
                    dao.clearAll()
                }

                // SimpleDateFormat не потокобезопасен, но мы внутри одной корутины — ок
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                var durationDays = 14
                var courseStart: Long = System.currentTimeMillis()
                var lastName = ""

                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                    // Удаляем BOM, который Excel любит добавлять в начало файла
                    var headerLine = reader.readLine()?.trim()?.removePrefix("\uFEFF") ?: return@use
                    val header = parseCsvLine(headerLine)
                    if (header.size > 2) {
                        durationDays = header.size - 2
                        courseStart = try {
                            dateFormat.parse(header[2])?.time ?: System.currentTimeMillis()
                        } catch (e: Exception) {
                            System.currentTimeMillis()
                        }
                    }

                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val row = parseCsvLine(line!!)
                        if (row.size < 2) continue

                        val rawName = row[0].replace("\n", " ").replace("\r", " ").trim()
                        // Пустая ячейка = объединённая с предыдущей строкой (Excel merged cells)
                        if (rawName.isNotEmpty() && rawName != "-") {
                            lastName = rawName
                        }

                        val timeStr = row[1].trim().lowercase(Locale.ROOT)
                        if (lastName.isEmpty() || timeStr.isEmpty() || timeStr == "время приёма") continue

                        val (hour, minute) = mapTimeKeywords(timeStr)

                        val med = Medication(
                            name = lastName,
                            hour = hour,
                            minute = minute,
                            startTimestamp = courseStart,
                            durationDays = durationDays
                        )
                        val newId = dao.insert(med).toInt()
                        // Будильник планируем сразу — callback уже в IO-потоке, AlarmManager это переживёт
                        onMedicationAdded(med, newId)
                        addedCount++
                    }
                }

                withContext(Dispatchers.Main) { onComplete(addedCount) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onError(e.message ?: "Ошибка парсинга CSV") }
            }
        }
    }

    /** Разбор одной строки CSV с учётом кавычек (RFC 4180) и запятых внутри значений. */
    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        for (c in line) {
            when {
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    result.add(current.toString().trim())
                    current.clear()
                }
                else -> current.append(c)
            }
        }
        result.add(current.toString().trim())
        return result
    }

    /** Маппинг текстового времени из таблицы в (hour, minute). */
    private fun mapTimeKeywords(text: String): Pair<Int, Int> = when {
        text.contains("утро") -> 7 to 30
        text.contains("завтрак") && text.contains("после") -> 8 to 30
        text.contains("завтрак") -> 8 to 15
        text.contains("обед") || text.contains("день") -> 13 to 30
        text.contains("вечер") -> 18 to 30
        text.contains("ужин") -> 19 to 30
        text.contains("сном") || text.contains("сон") -> 22 to 0
        else -> 8 to 0 // fallback — утро
    }
}