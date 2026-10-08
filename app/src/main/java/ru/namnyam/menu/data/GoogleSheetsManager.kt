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

object GoogleSheetsManager {

    // Идентификатор целевой Google Таблицы заказов
    const val SPREADSHEET_ID = "10PDOQjOTR0_y8uGY2OFBjWDaFYgh8HkLYxa3hOi0NGM"

    private var sheetsService: Sheets? = null

    // Инициализация учетных данных Service Account из папки assets (service_account.json)
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

    // Загрузка блюд на выбранный день (например, лист "ПН 12_10_2026")
    suspend fun loadMenuForDay(sheetTitle: String): List<DishItem> = withContext(Dispatchers.IO) {
        val service = sheetsService ?: return@withContext emptyList()
        val range = "'$sheetTitle'!A4:B120" // Колонки A (Блюдо) и B (Цена)
        
        try {
            val response = service.spreadsheets().values()
                .get(SPREADSHEET_ID, range)
                .execute()

            val rows = response.getValues() ?: return@withContext emptyList()
            val result = mutableListOf<DishItem>()
            var currentCategory = "Основное"

            rows.forEachIndexed { index, row ->
                val rowIndex = index + 4
                val name = row.getOrNull(0)?.toString()?.trim() ?: ""
                val priceRaw = row.getOrNull(1)?.toString()?.replace(",", ".")?.trim() ?: ""

                if (name.isNotEmpty()) {
                    val price = priceRaw.toDoubleOrNull()
                    if (price == null || price == 0.0) {
                        // Это строка раздела (Салаты и закуски, Первые блюда и т.д.)
                        currentCategory = name
                    } else {
                        // Генерируем ссылку поиска картинки на nam-nyam.ru
                        val cleanQuery = name.split(" ")[0]
                        val dummyImg = "https://www.nam-nyam.ru/images/dishes_preview.jpg"
                        result.add(
                            DishItem(
                                rowIndex = rowIndex,
                                name = name,
                                price = price,
                                category = currentCategory,
                                imageUrl = dummyImg
                            )
                        )
                    }
                }
            }
            result
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    // Запись заказа сотрудника в соответствующую колонку (1..35 -> C..AK)
    suspend fun submitOrder(
        sheetTitle: String,
        employeeNumber: Int,
        orders: List<OrderItem>
    ): Boolean = withContext(Dispatchers.IO) {
        val service = sheetsService ?: return@withContext false
        
        // Колонка A=1, B=2, Сотрудник 1 = C (колонка 3) -> colIndex = employeeNumber + 2
        val colLetter = getColumnLetter(employeeNumber + 2)

        try {
            val updates = orders.filter { it.quantity > 0 }
            for (order in updates) {
                val cellAddress = "'$sheetTitle'!$colLetter${order.dish.rowIndex}"
                val body = ValueRange().setValues(listOf(listOf(order.quantity)))
                service.spreadsheets().values()
                    .update(SPREADSHEET_ID, cellAddress, body)
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