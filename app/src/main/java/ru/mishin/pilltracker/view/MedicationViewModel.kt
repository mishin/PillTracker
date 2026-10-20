package ru.mishin.pilltracker.view

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
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
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.regex.Pattern

class MedicationViewModel(private val dao: MedicationDao) : ViewModel() {
    val medications = dao.getAllMedications()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun addMedication(med: Medication, onAdded: (Int) -> Unit) {
        viewModelScope.launch { onAdded(dao.insert(med).toInt()) }
    }

    fun toggleTakenStatus(med: Medication, isTaken: Boolean) {
        viewModelScope.launch { dao.update(med.copy(isTakenToday = isTaken)) }
    }

    fun deleteMedication(med: Medication) {
        viewModelScope.launch { dao.delete(med) }
    }

    fun calculateDaysLeft(med: Medication): Int {
        val daysPassed = ((System.currentTimeMillis() - med.startTimestamp) /
                (1000L * 60 * 60 * 24)).toInt()
        return (med.durationDays - daysPassed).coerceAtLeast(0)
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
                if (replaceExisting) dao.clearAll()
                val count = processCsvStream(stream, onMedicationAdded)
                withContext(Dispatchers.Main) { onComplete(count) }
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
                    ?: throw IllegalArgumentException(
                        "Ссылка не похожа на Google Таблицу. Ожидается формат: " +
                                "https://docs.google.com/spreadsheets/d/…/edit"
                    )
                val inputStream: InputStream =
                    URL("https://docs.google.com/spreadsheets/d/$id/export?format=csv").openStream()

                if (replaceExisting) dao.clearAll()
                val count = processCsvStream(inputStream, onMedicationAdded)
                withContext(Dispatchers.Main) { onComplete(count) }
            } catch (e: Exception) {
                // Улучшенные тексты ошибок для UX
                val friendly = when {
                    e.message?.contains("Unable to resolve host") == true ||
                            e.message?.contains("No address associated") == true ->
                        "Нет подключения к интернету"
                    e.message?.contains("404") == true ->
                        "Таблица не найдена. Проверьте ссылку"
                    e.message?.contains("403") == true || e.message?.contains("401") == true ->
                        "Доступ запрещён. Откройте доступ: Настройки → Доступ → «Все, у кого есть ссылка» → Читатель"
                    e.message?.contains("Неверный формат") == true ||
                            e.message?.contains("Ссылка не похожа") == true -> e.message!!
                    else -> "Ошибка загрузки: ${e.message ?: "неизвестная"}"
                }
                withContext(Dispatchers.Main) { onError(friendly) }
            }
        }
    }

    /** Экспорт всех лекарств в CSV и возврат Uri через FileProvider для шаринга. */
    fun exportToCsv(
        context: Context,
        onSuccess: (Uri) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val meds = dao.getAllMedicationsList()
                if (meds.isEmpty()) {
                    withContext(Dispatchers.Main) { onError("Список лекарств пуст") }
                    return@launch
                }

                val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                val start = meds.minOf { it.startTimestamp }
                val duration = meds.maxOf { it.durationDays }

                val sb = StringBuilder()
                // Заголовок: Лекарство, Время приёма, Заметка, <даты…>
                sb.append("\"Лекарство\",\"Время приёма\",\"Заметка\"")
                val cal = Calendar.getInstance().apply { timeInMillis = start }
                repeat(duration) {
                    sb.append(",\"").append(df.format(cal.time)).append("\"")
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                }
                sb.append("\n")

                for (m in meds) {
                    val timeText = String.format(Locale.US, "%02d:%02d", m.hour, m.minute)
                    sb.append("\"").append(m.name.replace("\"", "\"\"")).append("\",")
                        .append("\"").append(timeText).append("\",")
                        .append("\"").append(m.notes.replace("\"", "\"\"")).append("\"")
                    repeat(duration) { sb.append(",") }
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

    private suspend fun processCsvStream(
        inputStream: InputStream,
        onMedicationAdded: (Medication, Int) -> Unit
    ): Int {
        var added = 0
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        var durationDays = 14
        var courseStart = System.currentTimeMillis()
        var lastName = ""

        BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
            val header = parseCsvLine(
                reader.readLine()?.trim()?.removePrefix("\uFEFF") ?: return 0
            )
            if (header.size > 2) {
                durationDays = header.size - 2
                courseStart = try {
                    df.parse(header[2])?.time ?: System.currentTimeMillis()
                } catch (_: Exception) { System.currentTimeMillis() }
            }

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val row = parseCsvLine(line!!)
                if (row.size < 2) continue

                val rawName = row[0].replace("\n", " ").replace("\r", " ").trim()
                if (rawName.isNotEmpty() && rawName != "-") lastName = rawName

                val timeStr = row[1].trim().lowercase(Locale.ROOT)
                if (lastName.isEmpty() || timeStr.isEmpty() || timeStr == "время приёма") continue

                val info = parseTimeInfo(timeStr)
                val med = Medication(
                    name = lastName,
                    hour = info.hour,
                    minute = info.minute,
                    startTimestamp = courseStart,
                    durationDays = durationDays,
                    notes = info.notes
                )
                val newId = dao.insert(med).toInt()
                onMedicationAdded(med, newId)
                added++
            }
        }
        return added
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        for (c in line) {
            when {
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { result.add(current.toString().trim()); current.clear() }
                else -> current.append(c)
            }
        }
        result.add(current.toString().trim())
        return result
    }

    private data class TimeInfo(val hour: Int, val minute: Int, val notes: String)

    /** Парсит текстовое время и извлекает заметку из скобок (если есть). */
    private fun parseTimeInfo(text: String): TimeInfo {
        // Заметка = содержимое первых скобок, например "Утро (за 30 мин до завтрака)"
        val notes = Regex("\\(([^)]+)\\)").find(text)?.groupValues?.get(1)?.trim() ?: ""
        val (h, m) = when {
            text.contains("утро") -> 7 to 30
            text.contains("завтрак") && text.contains("после") -> 8 to 30
            text.contains("завтрак") -> 8 to 15
            text.contains("обед") || text.contains("день") -> 13 to 30
            text.contains("вечер") -> 18 to 30
            text.contains("ужин") -> 19 to 30
            text.contains("сном") || text.contains("сон") -> 22 to 0
            else -> 8 to 0
        }
        return TimeInfo(h, m, notes)
    }

    private fun extractSpreadsheetId(url: String): String? {
        val matcher = Pattern.compile("/d/([a-zA-Z0-9-_]+)").matcher(url)
        return if (matcher.find()) matcher.group(1) else null
    }
}