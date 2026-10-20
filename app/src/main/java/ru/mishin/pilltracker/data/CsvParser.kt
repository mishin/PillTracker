package ru.mishin.pilltracker.data

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class CsvParser {

    data class ParsedMedication(
        val name: String,
        val hour: Int,
        val minute: Int,
        val startTimestamp: Long,
        val durationDays: Int,
        val notes: String,
        val timeLabel: String,
        val takenDates: List<LocalDate> // Даты, где стоит отметка о приеме
    )

    fun parse(inputStream: InputStream): List<ParsedMedication> {
        val result = mutableListOf<ParsedMedication>()
        var lastName = ""

        BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
            val header = parseCsvLine(
                reader.readLine()?.trim()?.removePrefix("\uFEFF") ?: return emptyList()
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

            var line: String? = reader.readLine()
            while (line != null) {
                val row = parseCsvLine(line)
                if (row.size < 2) {
                    line = reader.readLine()
                    continue
                }

                val rawName = row[0].replace("\n", " ").replace("\r", " ").trim()
                if (rawName.isNotEmpty() && rawName != "-") lastName = rawName

                val timeRaw = row[1].trim()
                val timeStr = timeRaw.lowercase(Locale.ROOT)
                if (lastName.isEmpty() || timeStr.isEmpty() || timeStr == "время приёма") {
                    line = reader.readLine()
                    continue
                }

                val info = parseTimeInfo(timeRaw)
                val takenDates = mutableListOf<LocalDate>()
                dateColumns.forEachIndexed { index, date ->
                    val cell = row.getOrNull(index + 2).orEmpty()
                    val day = date ?: courseStart.plusDays(index.toLong())
                    if (isTakenMark(cell)) {
                        takenDates.add(day)
                    }
                }

                result.add(
                    ParsedMedication(
                        name = lastName,
                        hour = info.hour,
                        minute = info.minute,
                        startTimestamp = startMillis,
                        durationDays = durationDays,
                        notes = info.notes,
                        timeLabel = timeRaw,
                        takenDates = takenDates
                    )
                )
                line = reader.readLine()
            }
        }
        return result
    }

    // --- Вспомогательные методы (скопированы из MedicationViewModel) ---
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

    private fun parseFlexibleDate(raw: String, yearHint: Int): LocalDate? {
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

    private fun isTakenMark(cell: String): Boolean {
        val t = cell.trim()
        if (t.isEmpty() || t == "-" || t == "–" || t == "—" || t.equals("нет", true)) return false
        return t == "+" || t == "x" || t == "X" || t == "х" || t == "Х" ||
                t == "✓" || t == "✔" || t == "v" || t == "V" ||
                t == "1" || t.equals("да", true)
    }
}