package ru.namnyam.menu.data

data class DishItem(
    val rowIndex: Int,          // Номер строки в Google Таблице
    val name: String,           // Название блюда
    val price: Double,          // Цена в рублях
    val category: String,       // Категория (Первые блюда, Салаты и т.д.)
    val imageUrl: String = "",  // URL превью
    val descriptionUrl: String = "https://www.nam-nyam.ru/menu-na-nedelu/"
)

data class OrderItem(
    val dish: DishItem,
    var quantity: Int = 0
)