package ru.namnyam.menu.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

// Структура для хранения детальной карточки блюда, собранной с сайта
data class ScrapedDishDetails(
    val imageUrl: String,
    val composition: String = "",
    val minerals: String = "",
    val vitamins: String = "",
    val caloriesInfo: String = ""
)

object NamNyamScraper {

    private const val MENU_URL = "https://www.nam-nyam.ru/menu-na-nedelu/"
    private const val BASE_URL = "https://www.nam-nyam.ru"

    private val exactDishDetails = mutableMapOf<String, ScrapedDishDetails>()
    private var isScraped = false

    suspend fun fetchMenuImages(context: Context): Map<String, ScrapedDishDetails> = withContext(Dispatchers.IO) {
        if (isScraped && exactDishDetails.isNotEmpty()) {
            return@withContext exactDishDetails
        }

        try {
            AppLogger.log(context, "SCRAPER", "Парсинг каталога и состава блюд с $MENU_URL...")
            val doc = Jsoup.connect(MENU_URL)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0 Safari/537.36")
                .timeout(20000)
                .get()

            // Парсинг блоков блюд из каталога меню
            val productBlocks = doc.select(".menu-item, .catalog-item, div[class*='product'], div[class*='item'], tr[class*='menu']")
            for (block in productBlocks) {
                val title = block.select(".name, .title, a[title], h3, h4").text().ifEmpty {
                    block.select("img").attr("alt")
                }.trim()

                var imgUrl = block.select("img").attr("src").ifEmpty {
                    block.select("img").attr("data-src")
                }

                // Извлечение состава и КБЖУ, если карточка содержит подробности
                val compositionText = block.select(".composition, .ingredients, div[class*='sostav']").text().trim()
                val caloriesText = block.select(".calories, .kbju, div[class*='energy']").text().trim()

                if (title.isNotEmpty() && imgUrl.isNotEmpty()) {
                    if (imgUrl.startsWith("/")) {
                        imgUrl = BASE_URL + imgUrl
                    }
                    if (!imgUrl.contains("logo") && !imgUrl.contains("no-photo")) {
                        exactDishDetails[normalizeKey(title)] = ScrapedDishDetails(
                            imageUrl = imgUrl,
                            composition = compositionText,
                            caloriesInfo = caloriesText
                        )
                    }
                }
            }

            // Дополнительный сбор картинок по тегам img с валидным alt
            val images = doc.select("img[src*='/upload/']")
            for (img in images) {
                val alt = (img.attr("alt").ifEmpty { img.attr("title") }).trim()
                var src = img.attr("src").ifEmpty { img.attr("data-src") }
                if (alt.length > 3 && src.isNotEmpty()) {
                    if (src.startsWith("/")) {
                        src = BASE_URL + src
                    }
                    if (!src.contains("logo") && !src.contains("banner")) {
                        val key = normalizeKey(alt)
                        if (!exactDishDetails.containsKey(key)) {
                            exactDishDetails[key] = ScrapedDishDetails(imageUrl = src)
                        }
                    }
                }
            }

            isScraped = true
            AppLogger.log(context, "SCRAPER", "Найдено позиций блюд в базе: ${exactDishDetails.size}")
        } catch (e: Exception) {
            AppLogger.log(context, "SCRAPER_ERROR", "Ошибка парсинга: ${e.message}", e)
        }

        return@withContext exactDishDetails
    }

    fun findDetailsForDish(dishName: String): ScrapedDishDetails? {
        val target = normalizeKey(dishName)

        // 1. Точное совпадение
        exactDishDetails[target]?.let { return it }

        // 2. Поиск совпадений без учета граммовок
        val simpleTarget = target.replace(Regex("""\d+"""), "").trim()
        for ((key, details) in exactDishDetails) {
            val simpleKey = key.replace(Regex("""\d+"""), "").trim()
            if (simpleTarget == simpleKey || target.contains(key) || key.contains(target)) {
                return details
            }
        }

        return null
    }

    private fun normalizeKey(text: String): String {
        return text.lowercase()
            .replace(Regex("""\b\d+\s*(г|гр|мл|шт)?\b"""), "")
            .replace("\"", "")
            .replace("«", "")
            .replace("»", "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}