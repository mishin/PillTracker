package ru.namnyam.menu.data

import android.content.Context
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.api.services.sheets.v4.model.BatchUpdateValuesRequest
import com.google.api.services.sheets.v4.model.ValueRange
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.GoogleCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.regex.Pattern

object GoogleSheetsManager {

    const val DEFAULT_SPREADSHEET_ID = "10PDOQjOTR0_y8uGY2OFBjWDaFYgh8HkLYxa3hOi0NGM"
    const val SERVICE_ACCOUNT_EMAIL = "acc-45@mypython-393312.iam.gserviceaccount.com"

    private var sheetsService: Sheets? = null

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

            AppLogger.log(context, "INIT", "Учетные данные авторизованы: $SERVICE_ACCOUNT_EMAIL")
        } catch (e: Exception) {
            AppLogger.log(context, "INIT_ERROR", "Сбой чтения ключа: ${e.message}", e)
        }
    }

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

    suspend fun getSheetTitles(context: Context, spreadsheetId: String): List<SheetInfo> = withContext(Dispatchers.IO) {
        val service = sheetsService ?: run {
            AppLogger.log(context, "GET_SHEETS", "Google API сервис не инициализирован")
            return@withContext emptyList()
        }
        try {
            val spreadsheet = service.spreadsheets().get(spreadsheetId).execute()
            val list = spreadsheet.sheets?.map { sheet ->
                SheetInfo(
                    id = sheet.properties.sheetId,
                    title = sheet.properties.title
                )
            } ?: emptyList()
            AppLogger.log(context, "GET_SHEETS", "Листы получены (${list.size}): ${list.map { it.title }}")
            list
        } catch (e: Exception) {
            AppLogger.log(context, "GET_SHEETS_ERROR", "Ошибка доступа к таблице '$spreadsheetId': ${e.message}\nУбедитесь, что таблица расшарена для $SERVICE_ACCOUNT_EMAIL", e)
            emptyList()
        }
    }

    suspend fun loadMenuAndCurrentOrder(
        context: Context,
        spreadsheetId: String,
        sheetTitle: String,
        employeeNumber: Int
    ): DayMenuResult = withContext(Dispatchers.IO) {
        val service = sheetsService ?: return@withContext DayMenuResult()

        // Запуск парсера картинок с сайта
        NamNyamScraper.fetchMenuImages(context)

        val empColLetter = getColumnLetter(employeeNumber + 2)
        val readRange = "'$sheetTitle'!A1:${empColLetter}150"

        try {
            val response = service.spreadsheets().values()
                .get(spreadsheetId, readRange)
                .execute()

            val rows = response.getValues() ?: return@withContext DayMenuResult()
            val dishes = mutableListOf<DishItem>()
            val initialOrders = mutableMapOf<Int, Int>()

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
                        if (!name.equals("Наименование", ignoreCase = true) && !name.equals("ИТОГО:", ignoreCase = true)) {
                            currentCategory = name
                        }
                    } else {
                        val realImageUrl = NamNyamScraper.findImageForDish(name) ?: resolveFallbackImage(name, currentCategory)

                        dishes.add(
                            DishItem(
                                rowIndex = actualRowNum,
                                name = name,
                                price = price,
                                category = currentCategory,
                                imageUrl = realImageUrl
                            )
                        )

                        val currentValRaw = row.getOrNull(empColIdx)?.toString()?.trim() ?: ""
                        val currentQty = currentValRaw.toIntOrNull() ?: 0
                        if (currentQty > 0) {
                            initialOrders[actualRowNum] = currentQty
                        }
                    }
                }
            }

            AppLogger.log(context, "LOAD_MENU", "Загружено блюд: ${dishes.size}, текущих заказов сотрудника №$employeeNumber: ${initialOrders.size}")

            DayMenuResult(
                dateHeader = dateHeader,
                dishes = dishes,
                existingOrders = initialOrders
            )
        } catch (e: Exception) {
            AppLogger.log(context, "LOAD_MENU_ERROR", "Ошибка чтения таблицы: ${e.message}", e)
            DayMenuResult()
        }
    }

    // Сохранение заказа сотрудника ОДНИМ ПАКЕТНЫМ ЗАПРОСОМ (Batch Update)
    suspend fun submitOrder(
        context: Context,
        spreadsheetId: String,
        sheetTitle: String,
        employeeNumber: Int,
        allDishes: List<DishItem>,
        currentQuantities: Map<Int, Int>
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val service = sheetsService ?: return@withContext Pair(false, "API не инициализирован")

        if (allDishes.isEmpty()) {
            return@withContext Pair(false, "Список блюд пуст")
        }

        val colLetter = getColumnLetter(employeeNumber + 2)
        val minRow = allDishes.minOf { it.rowIndex }
        val maxRow = allDishes.maxOf { it.rowIndex }

        try {
            // Формируем вертикальный массив ячеек с minRow до maxRow
            val columnValues = mutableListOf<List<Any>>()
            for (row in minRow..maxRow) {
                val qty = currentQuantities[row] ?: 0
                val cellVal = if (qty > 0) qty else ""
                columnValues.add(listOf(cellVal))
            }

            val rangeToUpdate = "'$sheetTitle'!$colLetter$minRow:$colLetter$maxRow"
            val valueRange = ValueRange()
                .setRange(rangeToUpdate)
                .setValues(columnValues)

            val batchRequest = BatchUpdateValuesRequest()
                .setValueInputOption("USER_ENTERED")
                .setData(listOf(valueRange))

            // Выполняем РОВНО 1 сетевой запрос вместо 133!
            service.spreadsheets().values()
                .batchUpdate(spreadsheetId, batchRequest)
                .execute()

            val msg = "Успешно записано в колонку $colLetter ($rangeToUpdate)"
            AppLogger.log(context, "SUBMIT_OK", msg)
            Pair(true, msg)
        } catch (e: Exception) {
            val msg = "Ошибка: ${e.message}"
            AppLogger.log(context, "SUBMIT_FAIL", "Сбой batchUpdate в '$spreadsheetId' (лист '$sheetTitle'): ${e.message}", e)
            Pair(false, msg)
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

    private fun resolveFallbackImage(dishName: String, category: String): String {
        val lower = dishName.lowercase()
        return when {
            lower.contains("морковь") -> "https://images.unsplash.com/photo-1598170845058-32b9d6a5da37?w=300&q=80"
            lower.contains("винегрет") -> "https://images.unsplash.com/photo-1540420773420-3366772f4999?w=300&q=80"
            lower.contains("борщ") -> "https://images.unsplash.com/photo-1547592166-23ac45744acd?w=300&q=80"
            lower.contains("суп") -> "https://images.unsplash.com/photo-1547592180-85f173990554?w=300&q=80"
            lower.contains("сырник") -> "https://images.unsplash.com/photo-1579954115545-a95591f28bfc?w=300&q=80"
            lower.contains("плов") -> "https://images.unsplash.com/photo-1536304929831-ee1ca9d44906?w=300&q=80"
            lower.contains("котлет") -> "https://images.unsplash.com/photo-1529692236671-f1f6cf9683ba?w=300&q=80"
            lower.contains("блин") -> "https://images.unsplash.com/photo-1528207776546-365bb710ee93?w=300&q=80"
            category.contains("Первые") -> "https://images.unsplash.com/photo-1547592180-85f173990554?w=300&q=80"
            category.contains("Вторые") -> "https://images.unsplash.com/photo-1544025162-d76694265947?w=300&q=80"
            else -> "https://images.unsplash.com/photo-1546069901-ba9599a7e63c?w=300&q=80"
        }
    }
}