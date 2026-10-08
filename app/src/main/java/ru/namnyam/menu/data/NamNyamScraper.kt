package ru.namnyam.menu.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

object NamNyamScraper {

    private const val MENU_URL = "https://www.nam-nyam.ru/menu-na-nedelu/"
    private const val BASE_URL = "https://www.nam-nyam.ru"

    // Кэш соответствия: нормализованное_название -> URL картинки
    private val imageCache = mutableMapOf<String, String>()
    private var isScraped = false

    // Загрузка и парсинг изображений с официальной страницы меню
    suspend fun fetchMenuImages(context: Context): Map<String, String> = withContext(Dispatchers.IO) {
        if (isScraped && imageCache.isNotEmpty()) {
            return@withContext imageCache
        }

        try {
            AppLogger.log(context, "SCRAPER", "Начало граббинга картинок с $MENU_URL...")
            val doc = Jsoup.connect(MENU_URL)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0 Safari/537.36")
                .timeout(15000)
                .get()

            // Ищем все элементы с картинками блюд на странице Битрикс
            val imgElements = doc.select("img")
            var count = 0

            for (img in imgElements) {
                val title = (img.attr("title").ifEmpty { img.attr("alt") }).trim()
                var src = img.attr("src").ifEmpty { img.attr("data-src") }

                if (title.isNotEmpty() && src.isNotEmpty()) {
                    if (src.startsWith("/")) {
                        src = BASE_URL + src
                    }
                    val normalizedKey = normalizeName(title)
                    imageCache[normalizedKey] = src
                    count++
                }
            }

            // Также парсим ссылки в карточках каталога (.catalog-item, .product-card и т.д.)
            val cards = doc.select("div[class*='item'], div[class*='product'], div[class*='card']")
            for (card in cards) {
                val titleElem = card.select("a[title], .name, .title, h3, h4").firstOrNull()
                val imgElem = card.select("img").firstOrNull()
                if (titleElem != null && imgElem != null) {
                    val title = (titleElem.attr("title").ifEmpty { titleElem.text() }).trim()
                    var src = imgElem.attr("src").ifEmpty { imgElem.attr("data-src") }
                    if (title.isNotEmpty() && src.isNotEmpty()) {
                        if (src.startsWith("/")) {
                            src = BASE_URL + src
                        }
                        imageCache[normalizeName(title)] = src
                    }
                }
            }

            isScraped = true
            AppLogger.log(context, "SCRAPER", "Граббинг завершен. Найдено картинок: ${imageCache.size}")
        } catch (e: Exception) {
            AppLogger.log(context, "SCRAPER_ERROR", "Ошибка парсинга сайта нам-ням: ${e.message}", e)
        }

        return@withContext imageCache
    }

    // Поиск картинки для конкретного блюда из таблицы
    fun findImageForDish(dishName: String): String? {
        val target = normalizeName(dishName)
        
        // 1. Прямое совпадение нормализованного ключа
        imageCache[target]?.let { return it }

        // 2. Поиск по началу названия (например, "морковь по-корейски" без граммовки)
        for ((key, url) in imageCache) {
            if (target.contains(key) || key.contains(target)) {
                return url
            }
        }

        // 3. Поиск по первым двум словам названия блюда
        val words = target.split(" ").filter { it.length > 2 }
        if (words.size >= 2) {
            val prefix = "${words[0]} ${words[1]}"
            for ((key, url) in imageCache) {
                if (key.startsWith(prefix)) {
                    return url
                }
            }
        }

        return null
    }

    // Удаление веса, кавычек и спецсимволов для точного сопоставления
    private fun normalizeName(rawName: String): String {
        return rawName.lowercase()
            .replace(Regex("""\b\d+\s*(г|гр|мл|шт)?\b"""), "") // удаляем "150 г", "200г"
            .replace("\"", "")
            .replace("«", "")
            .replace("»", "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}