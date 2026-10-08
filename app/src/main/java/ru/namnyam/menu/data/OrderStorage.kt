package ru.namnyam.menu.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object OrderStorage {

    private const val PREFS_NAME = "order_cache_prefs"
    private const val KEY_CACHE_PREFIX = "menu_cache_"
    private const val KEY_TEMPLATE_PREFIX = "order_template_"
    private val gson = Gson()

    // 1. Кэширование меню для офлайн-доступа и мгновенного открытия
    fun saveMenuCache(context: Context, sheetTitle: String, result: DayMenuResult) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = gson.toJson(result)
        prefs.edit().putString(KEY_CACHE_PREFIX + sheetTitle, json).apply()
    }

    fun loadMenuCache(context: Context, sheetTitle: String): DayMenuResult? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_CACHE_PREFIX + sheetTitle, null) ?: return null
        return try {
            gson.fromJson(json, DayMenuResult::class.java)
        } catch (e: Exception) {
            null
        }
    }

    // 2. Сохранение шаблона / истории заказов для повтора в 1 клик
    fun saveOrderTemplate(context: Context, employeeNumber: Int, sheetTitle: String, orders: Map<Int, Int>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = "${KEY_TEMPLATE_PREFIX}${employeeNumber}_${sheetTitle.take(2)}"
        val json = gson.toJson(orders)
        prefs.edit().putString(key, json).apply()
    }

    fun loadOrderTemplate(context: Context, employeeNumber: Int, sheetTitle: String): Map<Int, Int>? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = "${KEY_TEMPLATE_PREFIX}${employeeNumber}_${sheetTitle.take(2)}"
        val json = prefs.getString(key, null) ?: return null
        val type = object : TypeToken<Map<Int, Int>>() {}.type
        return try {
            gson.fromJson(json, type)
        } catch (e: Exception) {
            null
        }
    }

    // 3. Автоподбор идеального обеда под бюджет 600 ₽ (Салат + Суп + Горячее)
    fun generateBalancedCombo(allDishes: List<DishItem>, budgetLimit: Double = 600.0): Map<Int, Int> {
        val salads = allDishes.filter { it.category.contains("салат", ignoreCase = true) || it.category.contains("закуск", ignoreCase = true) }
        val soups = allDishes.filter { it.category.contains("перв", ignoreCase = true) || it.category.contains("суп", ignoreCase = true) }
        val mains = allDishes.filter { it.category.contains("втор", ignoreCase = true) || it.category.contains("горяч", ignoreCase = true) }

        var bestCombo = emptyMap<Int, Int>()
        var bestSum = 0.0

        // Перебираем тройки блюд для поиска наиболее сытного комбо, укладывающегося в лимит
        for (salad in (salads.shuffled().take(6))) {
            for (soup in (soups.shuffled().take(6))) {
                for (main in (mains.shuffled().take(6))) {
                    val sum = salad.price + soup.price + main.price
                    if (sum <= budgetLimit && sum > bestSum) {
                        bestSum = sum
                        bestCombo = mapOf(
                            salad.rowIndex to 1,
                            soup.rowIndex to 1,
                            main.rowIndex to 1
                        )
                    }
                }
            }
        }

        // Если трех блюд не нашлось, подбираем пару (горячее + салат или суп)
        if (bestCombo.isEmpty()) {
            for (dish1 in allDishes.shuffled().take(8)) {
                for (dish2 in allDishes.shuffled().take(8)) {
                    if (dish1.rowIndex != dish2.rowIndex) {
                        val sum = dish1.price + dish2.price
                        if (sum <= budgetLimit && sum > bestSum) {
                            bestSum = sum
                            bestCombo = mapOf(
                                dish1.rowIndex to 1,
                                dish2.rowIndex to 1
                            )
                        }
                    }
                }
            }
        }

        return bestCombo
    }
}