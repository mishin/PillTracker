package ru.namnyam.menu.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

object NamNyamScraper {

    private const val MENU_URL = "https://www.nam-nyam.ru/menu-na-nedelu/"
    private const val BASE_URL = "https://www.nam-nyam.ru"

    private val imageMap = mutableMapOf<String, String>()
    private var isScraped = false

    suspend fun fetchMenuImages(context: Context): Map<String, String> = withContext(Dispatchers.IO) {
        if (isScraped && imageMap.isNotEmpty()) {
            return@withContext imageMap
        }

        try {
            AppLogger.log(context, "SCRAPER", "Парсинг каталога и картинок с $MENU_URL")
            val doc = Jsoup.connect(MENU_URL)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0 Safari/537.36")
                .timeout(20000)
                .get()

            // 1. Парсинг карточек товаров Битрикс (прямая связь заголовка и картинки)
            val elements = doc.select("a[href*='/catalog/'], div[class*='item'], div[class*='product'], tr[class*='item']")
            for (elem in elements) {
                val titleElem = elem.select(".name, .title, a[title], h3, h4, span[class*='title']").firstOrNull()
                val imgElem = elem.select("img").firstOrNull()

                val title = (titleElem?.attr("title")?.ifEmpty { titleElem.text() } ?: imgElem?.attr("alt") ?: imgElem?.attr("title") ?: "").trim()
                var src = imgElem?.attr("src")?.ifEmpty { imgElem.attr("data-src") }?.ifEmpty { imgElem.attr("data-original") } ?: ""

                if (title.isNotEmpty() && src.isNotEmpty()) {
                    if (src.startsWith("/")) {
                        src = BASE_URL + src
                    }
                    if (!src.contains("logo") && !src.contains("icon")) {
                        imageMap[cleanText(title)] = src
                    }
                }
            }

            // 2. Дополнительный проход по всем тегам img
            val allImgs = doc.select("img")
            for (img in allImgs) {
                val alt = (img.attr("alt").ifEmpty { img.attr("title") }).trim()
                var src = img.attr("src").ifEmpty { img.attr("data-src") }
                if (alt.length > 3 && src.isNotEmpty() && !src.contains("logo") && !src.contains("icon")) {
                    if (src.startsWith("/")) {
                        src = BASE_URL + src
                    }
                    imageMap[cleanText(alt)] = src
                }
            }

            isScraped = true
            AppLogger.log(context, "SCRAPER", "Готово! Собрано оригинальных изображений: ${imageMap.size}")
        } catch (e: Exception) {
            AppLogger.log(context, "SCRAPER_ERROR", "Сбой граббинга: ${e.message}", e)
        }

        return@withContext imageMap
    }

    // Поиск лучшего соответствия для блюда из Google Таблицы
    fun findImageForDish(dishName: String): String? {
        val target = cleanText(dishName)

        // Точное совпадение
        imageMap[target]?.let { return it }

        // Совпадение по вхождению подстроки
        for ((name, url) in imageMap) {
            if (name.length > 4 && (target.contains(name) || name.contains(target))) {
                return url
            }
        }

        // Совпадение по первым 2 ключевым словам (например "винегрет овощной")
        val keywords = target.split(" ").filter { it.length > 2 }
        if (keywords.isNotEmpty()) {
            val firstWord = keywords[0]
            for ((name, url) in imageMap) {
                if (name.startsWith(firstWord)) {
                    return url
                }
            }
        }

        return null
    }

    private fun cleanText(text: String): String {
        return text.lowercase()
            .replace(Regex("""\b\d+\s*(г|гр|мл|шт)?\b"""), "")
            .replace(Regex("""[«»"'\(\)]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}