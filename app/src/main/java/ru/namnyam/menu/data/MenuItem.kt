package ru.namnyam.menu.data

// Информация о листе таблицы
data class SheetInfo(
    val id: Int?,
    val title: String
)

// Модель позиции блюда с детальной информацией о составе и пищевой ценности
data class DishItem(
    val rowIndex: Int,
    val name: String,
    val price: Double,
    val category: String,
    val imageUrl: String = "",
    val descriptionUrl: String = "https://www.nam-nyam.ru/menu-na-nedelu/",
    val composition: String = "",
    val minerals: String = "",
    val vitamins: String = "",
    val caloriesInfo: String = ""
)

// Элемент заказа
data class OrderItem(
    val dish: DishItem,
    var quantity: Int = 0
)

// Результат загрузки меню за день
data class DayMenuResult(
    val dateHeader: String = "",
    val dishes: List<DishItem> = emptyList(),
    val existingOrders: Map<Int, Int> = emptyMap()
)