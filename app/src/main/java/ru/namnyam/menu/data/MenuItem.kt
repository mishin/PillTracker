package ru.namnyam.menu.data

// Модель блюда из таблицы и сайта Нам-Ням
data class DishItem(
    val rowIndex: Int,          // Номер строки в Google Таблице (1-based)
    val name: String,           // Название блюда (например: "Борщ с пампушками 300 г")
    val price: Double,          // Стоимость блюда
    val category: String,       // Раздел (Первые блюда, Салаты и т.д.)
    val imageUrl: String = "",  // URL превью с сайта нам-ням
    val descriptionUrl: String = "https://www.nam-nyam.ru/menu-na-nedelu/"
)

// Состояние заказа конкретного блюда
data class OrderItem(
    val dish: DishItem,
    var quantity: Int = 0
)