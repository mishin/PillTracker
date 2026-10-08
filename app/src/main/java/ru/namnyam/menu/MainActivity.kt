package ru.namnyam.menu

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import ru.namnyam.menu.data.DishItem
import ru.namnyam.menu.data.GoogleSheetsManager
import ru.namnyam.menu.data.OrderItem

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GoogleSheetsManager.initCredentials(applicationContext)

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF2E7D32),
                    secondary = Color(0xFFFF9800),
                    background = Color(0xFFF8F9FA)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    OrderMenuScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderMenuScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val daysOfWeek = listOf("ПН 12_10_2026", "ВТ 13_10_2026", "СР 14_10_2026", "ЧТ 15_10_2026", "ПТ 16_10_2026")
    var selectedDay by remember { mutableStateOf(daysOfWeek[0]) }
    var employeeNumber by remember { mutableStateOf(1) } // Номер сотрудника (1..35)
    var dishes by remember { mutableStateOf<List<DishItem>>(emptyList()) }
    val orderState = remember { mutableStateMapOf<Int, Int>() } // rowIndex -> count
    var isLoading by remember { mutableStateOf(false) }

    val totalSum = dishes.sumOf { (orderState[it.rowIndex] ?: 0) * it.price }
    val budgetLimit = 600.0

    // Загрузка блюд при смене дня
    LaunchedEffect(selectedDay) {
        isLoading = true
        dishes = GoogleSheetsManager.loadMenuForDay(selectedDay)
        orderState.clear()
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Заказ обедов: Нам-Ням", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF2E7D32),
                    titleContentColor = Color.White
                ),
                actions = {
                    IconButton(onClick = {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.nam-nyam.ru/menu-na-nedelu/"))
                        context.startActivity(browserIntent)
                    }) {
                        Icon(Icons.Default.Language, contentDescription = "Сайт Нам-Ням", tint = Color.White)
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                shadowElevation = 8.dp,
                color = Color.White
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Итого: ${totalSum.toInt()} ₽ / ${budgetLimit.toInt()} ₽",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = if (totalSum > budgetLimit) Color.Red else Color(0xFF2E7D32)
                            )
                            Text(
                                text = if (totalSum > budgetLimit) "Перерасход лимита!" else "Остаток: ${(budgetLimit - totalSum).toInt()} ₽",
                                fontSize = 12.sp,
                                color = if (totalSum > budgetLimit) Color.Red else Color.Gray
                            )
                        }

                        Button(
                            onClick = {
                                scope.launch {
                                    val orderItems = dishes.map { OrderItem(it, orderState[it.rowIndex] ?: 0) }
                                    isLoading = true
                                    val success = GoogleSheetsManager.submitOrder(selectedDay, employeeNumber, orderItems)
                                    isLoading = false
                                    if (success) {
                                        Toast.makeText(context, "✅ Заказ отмечен в Google Таблице!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "❌ Ошибка отправки заказа", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            enabled = totalSum > 0 && !isLoading,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                        ) {
                            Text("Отметить в таблице")
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Выбор дня и номера сотрудника
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("День недели:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        daysOfWeek.forEach { day ->
                            val label = day.take(2)
                            FilterChip(
                                selected = selectedDay == day,
                                onClick = { selectedDay = day },
                                label = { Text(label) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Сотрудник № (колонка 1..35): ", fontSize = 13.sp)
                        Text("$employeeNumber", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color(0xFF2E7D32))
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(onClick = { if (employeeNumber > 1) employeeNumber-- }) {
                            Icon(Icons.Default.RemoveCircleOutline, null)
                        }
                        IconButton(onClick = { if (employeeNumber < 35) employeeNumber++ }) {
                            Icon(Icons.Default.AddCircleOutline, null)
                        }
                    }
                }
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF2E7D32))
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(dishes) { dish ->
                        val count = orderState[dish.rowIndex] ?: 0
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White)
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(12.dp)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Картинка блюда (Coil)
                                AsyncImage(
                                    model = "https://www.nam-nyam.ru/images/dishes_preview.jpg",
                                    contentDescription = dish.name,
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFFEEEEEE))
                                        .clickable {
                                            val i = Intent(Intent.ACTION_VIEW, Uri.parse(dish.descriptionUrl))
                                            context.startActivity(i)
                                        },
                                    contentScale = ContentScale.Crop
                                )

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(dish.category, fontSize = 10.sp, color = Color.Gray)
                                    Text(dish.name, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                    Text("${dish.price.toInt()} ₽", fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32), fontSize = 14.sp)
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (count > 0) {
                                        IconButton(onClick = { orderState[dish.rowIndex] = count - 1 }) {
                                            Icon(Icons.Default.Remove, null, tint = Color.Red)
                                        }
                                        Text("$count", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    }
                                    IconButton(onClick = { orderState[dish.rowIndex] = count + 1 }) {
                                        Icon(Icons.Default.Add, null, tint = Color(0xFF2E7D32))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}