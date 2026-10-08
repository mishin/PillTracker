package ru.namnyam.menu.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

object NamNyamScraper {

    private const val MENU_URL = "https://www.nam-nyam.ru/menu-na-nedelu/"
    private const val BASE_URL = "https://www.nam-nyam.ru"

    private val exactDishImages = mutableMapOf<String, String>()
    private var isScraped = false

    suspend fun fetchMenuImages(context: Context): Map<String, String> = withContext(Dispatchers.IO) {
        if (isScraped && exactDishImages.isNotEmpty()) {
            return@withContext exactDishImages
        }

        try {
            AppLogger.log(context, "SCRAPER", "Парсинг точных изображений блюд с $MENU_URL...")
            val doc = Jsoup.connect(MENU_URL)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0 Safari/537.36")
                .timeout(20000)
                .get()

            // 1. Парсинг конкретных блоков блюд по атрибутам карточек каталога
            val productBlocks = doc.select(".menu-item, .catalog-item, div[class*='product'], div[class*='item'], tr[class*='menu']")
            for (block in productBlocks) {
                val title = block.select(".name, .title, a[title], h3, h4").text().ifEmpty {
                    block.select("img").attr("alt")
                }.trim()

                var imgUrl = block.select("img").attr("src").ifEmpty {
                    block.select("img").attr("data-src")
                }

                if (title.isNotEmpty() && imgUrl.isNotEmpty()) {
                    if (imgUrl.startsWith("/")) {
                        imgUrl = BASE_URL + imgUrl
                    }
                    if (!imgUrl.contains("logo") && !imgUrl.contains("no-photo")) {
                        exactDishImages[normalizeKey(title)] = imgUrl
                    }
                }
            }

            // 2. Парсинг всех изображений с валидным alt
            val images = doc.select("img[src*='/upload/']")
            for (img in images) {
                val alt = (img.attr("alt").ifEmpty { img.attr("title") }).trim()
                var src = img.attr("src").ifEmpty { img.attr("data-src") }
                if (alt.length > 3 && src.isNotEmpty()) {
                    if (src.startsWith("/")) {
                        src = BASE_URL + src
                    }
                    if (!src.contains("logo") && !src.contains("banner")) {
                        exactDishImages[normalizeKey(alt)] = src
                    }
                }
            }

            isScraped = true
            AppLogger.log(context, "SCRAPER", "Найдено уникальных картинок блюд: ${exactDishImages.size}")
        } catch (e: Exception) {
            AppLogger.log(context, "SCRAPER_ERROR", "Ошибка парсинга: ${e.message}", e)
        }

        return@withContext exactDishImages
    }

    fun findImageForDish(dishName: String): String? {
        val target = normalizeKey(dishName)

        // Точное совпадение
        exactDishImages[target]?.let { return it }

        // Совпадение без граммовки и кавычек
        val simpleTarget = target.replace(Regex("""\d+"""), "").trim()
        for ((key, url) in exactDishImages) {
            val simpleKey = key.replace(Regex("""\d+"""), "").trim()
            if (simpleTarget == simpleKey || target.contains(key) || key.contains(target)) {
                return url
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