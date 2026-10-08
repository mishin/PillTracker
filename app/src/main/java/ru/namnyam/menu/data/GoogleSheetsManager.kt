package ru.namnyam.menu.data

import android.content.Context
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.api.services.sheets.v4.model.ValueRange
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.GoogleCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.regex.Pattern

object GoogleSheetsManager {

    // Таблица по умолчанию
    const val DEFAULT_SPREADSHEET_ID = "10PDOQjOTR0_y8uGY2OFBjWDaFYgh8HkLYxa3hOi0NGM"

    private var sheetsService: Sheets? = null

    // Инициализация учетных данных Service Account из assets/service_account.json
    fun initCredentials(context: Context) {
        if (sheetsService != null) return
        try {
            val assetStream: InputStream = context.assets.open("service_account.json")
            val credentials = GoogleCredentials.fromStream(assetStream)
                .createScoped(listOf(SheetsScopes.SPREADSHEETS))

            sheetsService = Sheets.Builder(
                NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                HttpCredentialsAdapter(credentials)
            ).setApplicationName("NamNyam Order App").build()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Извлечение Spreadsheet ID из произвольной ссылки на Google Таблицу
    fun extractSpreadsheetId(url: String): String {
        val cleanUrl = url.trim()
        val matcher = Pattern.compile("/spreadsheets/d/([a-zA-Z0-9-_]+)").matcher(cleanUrl)
        return if (matcher.find()) {
            matcher.group(1) ?: DEFAULT_SPREADSHEET_ID
        } else if (cleanUrl.isNotEmpty() && !cleanUrl.contains("/")) {
            cleanUrl
        } else {
            DEFAULT_SPREADSHEET_ID
        }
    }

    // Получение списка всех листов таблицы с их названиями и ID
    suspend fun getSheetTitles(spreadsheetId: String): List<SheetInfo> = withContext(Dispatchers.IO) {
        val service = sheetsService ?: return@withContext emptyList()
        try {
            val spreadsheet = service.spreadsheets().get(spreadsheetId).execute()
            spreadsheet.sheets?.map { sheet ->
                SheetInfo(
                    id = sheet.properties.sheetId,
                    title = sheet.properties.title
                )
            } ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    // Загрузка блюд и УЖЕ существующих заказов выбранного сотрудника
    suspend fun loadMenuAndCurrentOrder(
        spreadsheetId: String,
        sheetTitle: String,
        employeeNumber: Int
    ): DayMenuResult = withContext(Dispatchers.IO) {
        val service = sheetsService ?: return@withContext DayMenuResult()
        
        // Колонка A=1 (Блюдо), B=2 (Цена), Сотрудник N = N + 2
        val empColLetter = getColumnLetter(employeeNumber + 2)
        val readRange = "'$sheetTitle'!A1:$empColLetter150"

        try {
            val response = service.spreadsheets().values()
                .get(spreadsheetId, readRange)
                .execute()

            val rows = response.getValues() ?: return@withContext DayMenuResult()
            val dishes = mutableListOf<DishItem>()
            val initialOrders = mutableMapOf<Int, Int>()

            // Читаем дату дня из строк заголовка (обычно строки 1-3)
            var dateHeader = ""
            for (i in 0..minOf(3, rows.size - 1)) {
                val rowStr = rows[i].joinToString(" ")
                if (rowStr.contains("2026") || rowStr.contains("октября") || rowStr.contains("ноября")) {
                    dateHeader = rowStr.trim()
                    break
                }
            }

            var currentCategory = "Салаты и закуски"
            val empColIdx = (employeeNumber + 2) - 1

            for (rowIndex in 3 until rows.size) {
                val actualRowNum = rowIndex + 1
                val row = rows[rowIndex]
                val name = row.getOrNull(0)?.toString()?.trim() ?: ""
                val priceRaw = row.getOrNull(1)?.toString()?.replace(",", ".")?.trim() ?: ""

                if (name.isNotEmpty()) {
                    val price = priceRaw.toDoubleOrNull()
                    if (price == null || price == 0.0) {
                        // Строка секции/категории
                        if (!name.equals("Наименование", ignoreCase = true) && !name.equals("ИТОГО:", ignoreCase = true)) {
                            currentCategory = name
                        }
                    } else {
                        // Генерируем ссылку превью блюда
                        val encodedDish = name.replace(" ", "%20")
                        val photoUrl = "https://www.nam-nyam.ru/images/menu/$encodedDish.jpg"

                        dishes.add(
                            DishItem(
                                rowIndex = actualRowNum,
                                name = name,
                                price = price,
                                category = currentCategory,
                                imageUrl = photoUrl
                            )
                        )

                        // Чтение того, что уже заказано этим сотрудником в таблице
                        val currentValRaw = row.getOrNull(empColIdx)?.toString()?.trim() ?: ""
                        val currentQty = currentValRaw.toIntOrNull() ?: 0
                        if (currentQty > 0) {
                            initialOrders[actualRowNum] = currentQty
                        }
                    }
                }
            }

            DayMenuResult(
                dateHeader = dateHeader,
                dishes = dishes,
                existingOrders = initialOrders
            )
        } catch (e: Exception) {
            e.printStackTrace()
            DayMenuResult()
        }
    }

    // Сохранение заказа сотрудника в таблицу
    suspend fun submitOrder(
        spreadsheetId: String,
        sheetTitle: String,
        employeeNumber: Int,
        allDishes: List<DishItem>,
        currentQuantities: Map<Int, Int>
    ): Boolean = withContext(Dispatchers.IO) {
        val service = sheetsService ?: return@withContext false
        val colLetter = getColumnLetter(employeeNumber + 2)

        try {
            // Записываем порции (или очищаем, если стало 0)
            for (dish in allDishes) {
                val qty = currentQuantities[dish.rowIndex] ?: 0
                val cellAddress = "'$sheetTitle'!$colLetter${dish.rowIndex}"
                val cellValue = if (qty > 0) qty else ""
                val body = ValueRange().setValues(listOf(listOf(cellValue)))

                service.spreadsheets().values()
                    .update(spreadsheetId, cellAddress, body)
                    .setValueInputOption("USER_ENTERED")
                    .execute()
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun getColumnLetter(colIndex: Int): String {
        var temp = colIndex
        var letter = ""
        while (temp > 0) {
            val rem = (temp - 1) % 26
            letter = ('A' + rem) + letter
            temp = (temp - 1) / 26
        }
        return letter
    }
}

data class SheetInfo(val id: Int?, val title: String)

data class DayMenuResult(
    val dateHeader: String = "",
    val dishes: List<DishItem> = emptyList(),
    val existingOrders: Map<Int, Int> = emptyMap()
)