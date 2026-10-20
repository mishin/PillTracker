package ru.mishin.pilltracker.data

import kotlinx.coroutines.flow.Flow
import java.io.InputStream
import java.net.URL

class MedicationRepository(
    private val dao: MedicationDao,
    private val csvParser: CsvParser = CsvParser() // Создаем парсер по умолчанию
) {
    val allMedications: Flow<List<Medication>> = dao.getAllMedications()
    val allIntakes: Flow<List<DoseIntake>> = dao.getAllIntakes()

    suspend fun insert(med: Medication): Int = dao.insert(med).toInt()
    suspend fun update(med: Medication) = dao.update(med)
    suspend fun delete(med: Medication) = dao.delete(med)

    // Добавляем недостающие методы:
    suspend fun getAllMedicationsList(): List<Medication> = dao.getAllMedicationsList()
    suspend fun upsertIntake(intake: DoseIntake) = dao.upsertIntake(intake)
    suspend fun deleteIntake(medicationId: Int, epochDay: Long) = dao.deleteIntake(medicationId, epochDay)

    suspend fun clearAll() = dao.clearAll()

    suspend fun importFromCsv(inputStream: InputStream): List<Medication> {
        // Парсим CSV и сразу сохраняем в БД
        val parsed = csvParser.parse(inputStream)
        val result = mutableListOf<Medication>()
        for (p in parsed) {
            val med = Medication(
                name = p.name,
                hour = p.hour,
                minute = p.minute,
                startTimestamp = p.startTimestamp,
                durationDays = p.durationDays,
                notes = p.notes,
                timeLabel = p.timeLabel
            )
            val newId = dao.insert(med).toInt()
            result.add(med.copy(id = newId))
            // Восстанавливаем отметки о приеме
            for (date in p.takenDates) {
                dao.upsertIntake(DoseIntake(newId, date.toEpochDay(), taken = true))
            }
        }
        return result
    }

    suspend fun importFromUrl(urlString: String): List<Medication> {
        // В идеале здесь должна быть библиотека (OkHttp/Ktor), но для простоты используем URL
        val url = URL(urlString)
        return url.openStream().use { stream ->
            importFromCsv(stream)
        }
    }
}