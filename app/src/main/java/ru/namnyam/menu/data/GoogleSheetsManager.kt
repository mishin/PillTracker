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

    const val DEFAULT_SPREADSHEET_ID = "10PDOQjOTR0_y8uGY2OFBjWDaFYgh8HkLYxa3hOi0NGM"
    const val SERVICE_ACCOUNT_EMAIL = "acc-45@mypython-393312.iam.gserviceaccount.com"

    private var sheetsService: Sheets? = null

    // Инициализация учетных данных
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

            AppLogger.log(context, "INIT", "Учетные данные успешно инициализированы для $SERVICE_ACCOUNT_EMAIL")
        } catch (e: Exception) {
            AppLogger.log(context, "INIT_ERROR", "Ошибка инициализации service_account.json: ${e.message}", e)
        }
    }

    // Извлечение Spreadsheet ID из ссылки
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

    // Получение списка всех листов таблицы
    suspend fun getSheetTitles(context: Context, spreadsheetId: String): List<SheetInfo> = withContext(Dispatchers.IO) {
        val service = sheetsService ?: run {
            AppLogger.log(context, "GET_SHEETS", "Сервис не инициализирован")
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
            AppLogger.log(context, "GET_SHEETS", "Успешно получены листы (${list.size}): ${list.map { it.title }}")
            list
        } catch (e: Exception) {
            AppLogger.log(context, "GET_SHEETS_ERROR", "Не удалось прочитать таблицу $spreadsheetId: ${e.message}", e)
            emptyList()
        }
    }

    // Загрузка блюд и существующих заказов
    suspend fun loadMenuAndCurrentOrder(
        context: Context,
        spreadsheetId: String,
        sheetTitle: String,
        employeeNumber: Int
    ): DayMenuResult = withContext(Dispatchers.IO) {
        val service = sheetsService ?: run {
            AppLogger.log(context, "LOAD_MENU", "Сервис не инициализирован")
            return@withContext DayMenuResult()
        }

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
                        // Подбираем URL реалистичной аппетитной картинки по категории и названию
                        val imageUrl = resolveDishImageUrl(name, currentCategory)

                        dishes.add(
                            DishItem(
                                rowIndex = actualRowNum,
                                name = name,
                                price = price,
                                category = currentCategory,
                                imageUrl = imageUrl
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

            AppLogger.log(context, "LOAD_MENU", "Загружено ${dishes.size} блюд для листа '$sheetTitle'. Заказов найдено: ${initialOrders.size}")

            DayMenuResult(
                dateHeader = dateHeader,
                dishes = dishes,
                existingOrders = initialOrders
            )
        } catch (e: Exception) {
            AppLogger.log(context, "LOAD_MENU_ERROR", "Ошибка загрузки меню: ${e.message}", e)
            DayMenuResult()
        }
    }

    // Сохранение заказа сотрудника в таблицу
    suspend fun submitOrder(
        context: Context,
        spreadsheetId: String,
        sheetTitle: String,
        employeeNumber: Int,
        allDishes: List<DishItem>,
        currentQuantities: Map<Int, Int>
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val service = sheetsService ?: run {
            val msg = "Сервис не инициализирован. Проверьте assets/service_account.json"
            AppLogger.log(context, "SUBMIT_ERROR", msg)
            return@withContext Pair(false, msg)
        }

        val colLetter = getColumnLetter(employeeNumber + 2)

        try {
            var updatedCount = 0
            for (dish in allDishes) {
                val qty = currentQuantities[dish.rowIndex] ?: 0
                val cellAddress = "'$sheetTitle'!$colLetter${dish.rowIndex}"
                val cellValue = if (qty > 0) qty else ""
                val body = ValueRange().setValues(listOf(listOf(cellValue)))

                service.spreadsheets().values()
                    .update(spreadsheetId, cellAddress, body)
                    .setValueInputOption("USER_ENTERED")
                    .execute()
                updatedCount++
            }

            val successMsg = "Заказ успешно сохранен в колонку $colLetter (строки: $updatedCount)"
            AppLogger.log(context, "SUBMIT_SUCCESS", successMsg)
            Pair(true, successMsg)

        } catch (e: Exception) {
            val errMsg = "Ошибка сохранения: ${e.message}"
            AppLogger.log(context, "SUBMIT_ERROR", "Сбой при записи в таблицу $spreadsheetId ($sheetTitle, колонка $colLetter): ${e.message}\nВАЖНО: Убедитесь, что таблица расшарена сервисному аккаунту $SERVICE_ACCOUNT_EMAIL с правами Редактора!", e)
            Pair(false, errMsg)
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

    // Надежное сопоставление фото блюд по ключевым словам и категориям (Unsplash Food CDN)
    private fun resolveDishImageUrl(dishName: String, category: String): String {
        val lower = dishName.lowercase()
        return when {
            lower.contains("борщ") -> "https://images.unsplash.com/photo-1547592166-23ac45744acd?w=300&q=80"
            lower.contains("суп") || lower.contains("крем-суп") -> "https://images.unsplash.com/photo-1547592180-85f173990554?w=300&q=80"
            lower.contains("сырник") || lower.contains("запеканка") -> "https://images.unsplash.com/photo-1579954115545-a95591f28bfc?w=300&q=80"
            lower.contains("каша") -> "https://images.unsplash.com/photo-1586444248902-2f64eddc13df?w=300&q=80"
            lower.contains("блин") -> "https://images.unsplash.com/photo-1528207776546-365bb710ee93?w=300&q=80"
            lower.contains("салат") || lower.contains("цезарь") || lower.contains("оливье") -> "https://images.unsplash.com/photo-1512621776951-a57141f2eefd?w=300&q=80"
            lower.contains("плов") || lower.contains("рис") -> "https://images.unsplash.com/photo-1536304929831-ee1ca9d44906?w=300&q=80"
            lower.contains("котлет") || lower.contains("биточк") -> "https://images.unsplash.com/photo-1529692236671-f1f6cf9683ba?w=300&q=80"
            lower.contains("куриц") || lower.contains("окороч") || lower.contains("шашлык") -> "https://images.unsplash.com/photo-1598515214211-89d3c73ae83b?w=300&q=80"
            lower.contains("паста") || lower.contains("макарон") || lower.contains("спагетти") -> "https://images.unsplash.com/photo-1621996346565-e3d5d6281724?w=300&q=80"
            lower.contains("рыб") || lower.contains("минтай") -> "https://images.unsplash.com/photo-1519708227418-c8fd9a32b7a2?w=300&q=80"
            lower.contains("вареник") || lower.contains("пельмен") || lower.contains("манты") -> "https://images.unsplash.com/photo-1541592106381-b31e9677c0e5?w=300&q=80"
            category.contains("Завтрак") -> "https://images.unsplash.com/photo-1525351484163-7529414344d8?w=300&q=80"
            category.contains("Первые") -> "https://images.unsplash.com/photo-1547592180-85f173990554?w=300&q=80"
            category.contains("Вторые") -> "https://images.unsplash.com/photo-1544025162-d76694265947?w=300&q=80"
            category.contains("Гарнир") -> "https://images.unsplash.com/photo-1514944298352-1bc541ef8c0a?w=300&q=80"
            category.contains("Выпечка") || category.contains("Блины") -> "https://images.unsplash.com/photo-1509440159596-0249088772ff?w=300&q=80"
            else -> "https://images.unsplash.com/photo-1546069901-ba9599a7e63c?w=300&q=80"
        }
    }
}

data class SheetInfo(val id: Int?, val title: String)

data class DayMenuResult(
    val dateHeader: String = "",
    val dishes: List<DishItem> = emptyList(),
    val existingOrders: Map<Int, Int> = emptyMap()
)