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
import ru.mishin.pilltracker.data.MedicationDao
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
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

class MedicationViewModel(private val dao: MedicationDao) : ViewModel() {
    val medications = dao.getAllMedications()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val selectedEpochDay = MutableStateFlow(LocalDate.now().toEpochDay())

    val schedule = combine(
        medications,
        dao.getAllIntakes(),
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
        viewModelScope.launch { onAdded(dao.insert(med).toInt()) }
    }

    fun toggleTaken(med: Medication, taken: Boolean) {
        val day = schedule.value.selectedDay
        toggleTakenOnDay(med, day, taken) // Перенаправляем на новый метод
    }

    // НОВЫЙ МЕТОД: Позволяет ставить галочку для любой даты из сетки
    fun toggleTakenOnDay(med: Medication, day: LocalDate, taken: Boolean) {
        viewModelScope.launch {
            if (taken) {
                dao.upsertIntake(DoseIntake(med.id, day.toEpochDay(), taken = true))
            } else {
                dao.deleteIntake(med.id, day.toEpochDay())
            }
            if (day == LocalDate.now()) {
                dao.update(med.copy(isTakenToday = taken))
            }
        }
    }

    fun deleteMedication(med: Medication) {
        viewModelScope.launch { dao.delete(med) }
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
                val gid = extractGid(urlString)
                val export = buildString {
                    append("https://docs.google.com/spreadsheets/d/$id/export?format=csv")
                    if (gid != null) append("&gid=").append(gid)
                }
                val inputStream: InputStream = URL(export).openStream()

                if (replaceExisting) dao.clearAll()
                val count = processCsvStream(inputStream, onMedicationAdded)
                withContext(Dispatchers.Main) { onComplete(count) }
            } catch (e: Exception) {
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

    private suspend fun processCsvStream(
        inputStream: InputStream,
        onMedicationAdded: (Medication, Int) -> Unit
    ): Int {
        var added = 0
        var lastName = ""

        BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
            val header = parseCsvLine(
                reader.readLine()?.trim()?.removePrefix("\uFEFF") ?: return 0
            )
            val yearHint = LocalDate.now().year
            val dateColumns = header.drop(2).map { parseFlexibleDate(it, yearHint) }
            val parsedDates = dateColumns.filterNotNull()
            val courseStart = parsedDates.firstOrNull() ?: LocalDate.now()
            val durationDays = if (parsedDates.isNotEmpty()) parsedDates.size else {
                (header.size - 2).coerceAtLeast(1)
            }
            val startMillis = courseStart.atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli()

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val row = parseCsvLine(line!!)
                if (row.size < 2) continue

                val rawName = row[0].replace("\n", " ").replace("\r", " ").trim()
                if (rawName.isNotEmpty() && rawName != "-") lastName = rawName

                val timeRaw = row[1].trim()
                val timeStr = timeRaw.lowercase(Locale.ROOT)
                if (lastName.isEmpty() || timeStr.isEmpty() || timeStr == "время приёма") continue

                val info = parseTimeInfo(timeRaw)
                val med = Medication(
                    name = lastName,
                    hour = info.hour,
                    minute = info.minute,
                    startTimestamp = startMillis,
                    durationDays = durationDays,
                    notes = info.notes,
                    timeLabel = timeRaw
                )
                val newId = dao.insert(med).toInt()
                onMedicationAdded(med.copy(id = newId), newId)

                dateColumns.forEachIndexed { index, date ->
                    val cell = row.getOrNull(index + 2).orEmpty()
                    val day = date ?: courseStart.plusDays(index.toLong())
                    if (isTakenMark(cell)) {
                        dao.upsertIntake(DoseIntake(newId, day.toEpochDay(), taken = true))
                    }
                }
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

    private data class TimeInfo(val hour: Int, val minute: Int, val notes: String)

    private fun parseTimeInfo(text: String): TimeInfo {
        val lower = text.lowercase(Locale.ROOT)
        val notes = Regex("\\(([^)]+)\\)").find(text)?.groupValues?.get(1)?.trim() ?: ""
        val (h, m) = when {
            lower.contains("утро") -> 7 to 30
            lower.contains("завтрак") && lower.contains("после") -> 8 to 30
            lower.contains("завтрак") -> 8 to 15
            lower.contains("обед") || lower.contains("день") -> 13 to 30
            lower.contains("вечер") -> 18 to 30
            lower.contains("ужин") -> 19 to 30
            lower.contains("сном") || lower.contains("сон") -> 22 to 0
            else -> parseClock(lower) ?: (8 to 0)
        }
        return TimeInfo(h, m, notes)
    }

    private fun parseClock(text: String): Pair<Int, Int>? {
        val match = Regex("""(\d{1,2}):(\d{2})""").find(text) ?: return null
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
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

fun parseFlexibleDate(raw: String, yearHint: Int): LocalDate? {
    val s = raw.trim().removeSurrounding("\"")
    if (s.isEmpty() || s.equals("Лекарство", true) || s.contains("Время")) return null
    val dateTimeFormats = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    )
    for (fmt in dateTimeFormats) {
        runCatching { return java.time.LocalDateTime.parse(s, fmt).toLocalDate() }
    }
    val dateFormats = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ofPattern("dd.MM.yyyy"),
        DateTimeFormatter.ofPattern("d.M.yyyy"),
        DateTimeFormatter.ofPattern("dd.MM.yy")
    )
    for (fmt in dateFormats) {
        runCatching { return LocalDate.parse(s, fmt) }
    }
    val md = Regex("""^(\d{1,2})\.(\d{1,2})$""").matchEntire(s) ?: return null
    val day = md.groupValues[1].toInt()
    val month = md.groupValues[2].toInt()
    return runCatching { LocalDate.of(yearHint, month, day) }.getOrNull()
}

fun isTakenMark(cell: String): Boolean {
    val t = cell.trim()
    if (t.isEmpty() || t == "-" || t == "–" || t == "—" || t.equals("нет", true)) return false
    return t == "+" || t == "x" || t == "X" || t == "х" || t == "Х" ||
            t == "✓" || t == "✔" || t == "v" || t == "V" ||
            t == "1" || t.equals("да", true)
}
