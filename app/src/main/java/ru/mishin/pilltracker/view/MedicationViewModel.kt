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
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.regex.Pattern

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

    fun calculateDaysLeft(med: Medication): Int {
        val currentMillis = System.currentTimeMillis()
        val diffMillis = currentMillis - med.startTimestamp
        val daysPassed = (diffMillis / (1000 * 60 * 60 * 24)).toInt()
        val daysLeft = med.durationDays - daysPassed
        return if (daysLeft > 0) daysLeft else 0
    }

    /** Импорт из локального файла (Uri) */
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
                val appContext = context.applicationContext
                val inputStream = appContext.contentResolver.openInputStream(uri)
                    ?: throw IllegalArgumentException("Не удалось открыть файл")

                if (replaceExisting) dao.clearAll()

                val addedCount = processCsvStream(inputStream, onMedicationAdded)
                withContext(Dispatchers.Main) { onComplete(addedCount) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onError(e.message ?: "Ошибка парсинга CSV") }
            }
        }
    }

    /** Импорт напрямую из Google Таблиц по ссылке */
    fun importFromGoogleSheetsUrl(
        urlString: String,
        replaceExisting: Boolean,
        onMedicationAdded: (Medication, Int) -> Unit,
        onComplete: (Int) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val spreadsheetId = extractSpreadsheetId(urlString)
                    ?: throw IllegalArgumentException("Неверный формат ссылки Google Таблиц")

                val exportUrl = "https://docs.google.com/spreadsheets/d/$spreadsheetId/export?format=csv"
                val inputStream: InputStream = URL(exportUrl).openStream()

                if (replaceExisting) dao.clearAll()

                val addedCount = processCsvStream(inputStream, onMedicationAdded)
                withContext(Dispatchers.Main) { onComplete(addedCount) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onError(e.message ?: "Ошибка загрузки из сети") }
            }
        }
    }

    /**
     * Общий метод для разбора CSV-потока (используется и для файлов, и для ссылок).
     * Возвращает количество успешно добавленных записей.
     * ДОБАВЛЕНО: ключевое слово suspend, так как вызывается dao.insert()
     */
    private suspend fun processCsvStream(
        inputStream: InputStream,
        onMedicationAdded: (Medication, Int) -> Unit
    ): Int {
        var addedCount = 0
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        var durationDays = 14
        var courseStart: Long = System.currentTimeMillis()
        var lastName = ""

        BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
            var headerLine = reader.readLine()?.trim()?.removePrefix("\uFEFF") ?: return 0
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

                // Теперь это сработает, так как функция помечена как suspend
                val newId = dao.insert(med).toInt()

                onMedicationAdded(med, newId)
                addedCount++
            }
        }
        return addedCount
    }

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

    private fun mapTimeKeywords(text: String): Pair<Int, Int> = when {
        text.contains("утро") -> 7 to 30
        text.contains("завтрак") && text.contains("после") -> 8 to 30
        text.contains("завтрак") -> 8 to 15
        text.contains("обед") || text.contains("день") -> 13 to 30
        text.contains("вечер") -> 18 to 30
        text.contains("ужин") -> 19 to 30
        text.contains("сном") || text.contains("сон") -> 22 to 0
        else -> 8 to 0
    }

    private fun extractSpreadsheetId(url: String): String? {
        val pattern = Pattern.compile("/d/([a-zA-Z0-9-_]+)")
        val matcher = pattern.matcher(url)
        return if (matcher.find()) matcher.group(1) else null
    }
}