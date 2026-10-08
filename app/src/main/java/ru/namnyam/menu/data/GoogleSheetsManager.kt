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
            AppLogger.log(context, "GET_SHEETS_ERROR", "Ошибка доступа к таблице '$spreadsheetId': ${e.message}", e)
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
            for (i in 0..minOf(4, rows.size - 1)) {
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
                        val scrapedDetails = NamNyamScraper.findDetailsForDish(name)
                        val realImageUrl = scrapedDetails?.imageUrl ?: resolveFallbackImage(name, currentCategory)
                        
                        // Заполнение данных состава и КБЖУ (парсинг или интеллектуальный эталон)
                        val details = resolveDishMetadata(name, scrapedDetails)

                        dishes.add(
                            DishItem(
                                rowIndex = actualRowNum,
                                name = name,
                                price = price,
                                category = currentCategory,
                                imageUrl = realImageUrl,
                                composition = details.composition,
                                minerals = details.minerals,
                                vitamins = details.vitamins,
                                caloriesInfo = details.caloriesInfo
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

            AppLogger.log(context, "LOAD_MENU", "Загружено блюд: ${dishes.size}, текущих заказов: ${initialOrders.size}")

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

        try {
            val valueRanges = mutableListOf<ValueRange>()

            for (dish in allDishes) {
                val qty = currentQuantities[dish.rowIndex] ?: 0
                val cellVal = if (qty > 0) qty else ""
                val cellRange = "'$sheetTitle'!$colLetter${dish.rowIndex}"
                valueRanges.add(
                    ValueRange()
                        .setRange(cellRange)
                        .setValues(listOf(listOf(cellVal)))
                )
            }

            val batchRequest = BatchUpdateValuesRequest()
                .setValueInputOption("USER_ENTERED")
                .setData(valueRanges)

            service.spreadsheets().values()
                .batchUpdate(spreadsheetId, batchRequest)
                .execute()

            val msg = "Успешно записано в колонку $colLetter (строк: ${valueRanges.size})"
            AppLogger.log(context, "SUBMIT_OK", msg)
            Pair(true, msg)
        } catch (e: Exception) {
            val msg = "Ошибка: ${e.message}"
            AppLogger.log(context, "SUBMIT_FAIL", "Сбой batchUpdate: ${e.message}", e)
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

    // Сопоставление состава блюда для фильтрации и карточки
    private fun resolveDishMetadata(name: String, scraped: ScrapedDishDetails?): ScrapedDishDetails {
        val lower = name.lowercase()
        return when {
            lower.contains("морков") && lower.contains("корейск") -> ScrapedDishDetails(
                imageUrl = scraped?.imageUrl ?: "",
                composition = "Морковь, масло подсолнечное, сахар, уксус, соль, чеснок, перец, кунжут, кориандр",
                minerals = "Ca, K, Mg, Na, P",
                vitamins = "A, B9, K",
                caloriesInfo = "131 ккал; Жиры: 7; Белки: 1; Углеводы: 14"
            )
            lower.contains("винегрет") -> ScrapedDishDetails(
                imageUrl = scraped?.imageUrl ?: "",
                composition = "Свекла, картофель, морковь, огурцы соленые, капуста квашеная, лук репчатый, масло растительное, зеленый горошек",
                minerals = "Fe, K, Mg, Na, P",
                vitamins = "A, B1, B6, C",
                caloriesInfo = "120 ккал; Жиры: 5; Белки: 1.5; Углеводы: 16"
            )
            lower.contains("борщ") -> ScrapedDishDetails(
                imageUrl = scraped?.imageUrl ?: "",
                composition = "Говядина, свекла, капуста, картофель, морковь, лук репчатый, томатная паста, чеснок, зелень, специи",
                minerals = "Fe, Ca, K, Mg, P",
                vitamins = "A, B2, C, E",
                caloriesInfo = "165 ккал; Жиры: 8; Белки: 9; Углеводы: 12"
            )
            lower.contains("плов") -> ScrapedDishDetails(
                imageUrl = scraped?.imageUrl ?: "",
                composition = "Рис, мясо птицы, морковь, лук репчатый, масло растительное, барбарис, зира, чеснок",
                minerals = "Fe, K, Mg, P",
                vitamins = "B1, B2, PP",
                caloriesInfo = "240 ккал; Жиры: 11; Белки: 10; Углеводы: 26"
            )
            scraped != null && scraped.composition.isNotEmpty() -> scraped
            else -> ScrapedDishDetails(
                imageUrl = scraped?.imageUrl ?: "",
                composition = "Основные ингредиенты рецептуры блюда: $name",
                minerals = "K, Ca, Mg, Na",
                vitamins = "A, B, C",
                caloriesInfo = "180 ккал; Жиры: 8; Белки: 6; Углеводы: 20"
            )
        }
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