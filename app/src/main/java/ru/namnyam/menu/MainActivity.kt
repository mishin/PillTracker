package ru.namnyam.menu

import android.content.Context
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import ru.namnyam.menu.data.AppLogger
import ru.namnyam.menu.data.DishItem
import ru.namnyam.menu.data.GoogleSheetsManager
import ru.namnyam.menu.data.SheetInfo

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GoogleSheetsManager.initCredentials(applicationContext)

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF2E7D32),
                    secondary = Color(0xFFFF9800),
                    background = Color(0xFFF4F6F8)
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
    val prefs = remember { context.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }

    var tableUrl by remember {
        mutableStateOf(
            prefs.getString("table_url", "https://docs.google.com/spreadsheets/d/10PDOQjOTR0_y8uGY2OFBjWDaFYgh8HkLYxa3hOi0NGM/edit") ?: ""
        )
    }
    var employeeNumber by remember { mutableStateOf(prefs.getInt("employee_num", 1)) }

    var spreadsheetId by remember { mutableStateOf(GoogleSheetsManager.extractSpreadsheetId(tableUrl)) }
    var availableSheets by remember { mutableStateOf<List<SheetInfo>>(emptyList()) }
    var selectedSheetTitle by remember { mutableStateOf("") }
    var dateHeader by remember { mutableStateOf("") }

    var dishes by remember { mutableStateOf<List<DishItem>>(emptyList()) }
    val orderState = remember { mutableStateMapOf<Int, Int>() }
    val collapsedCategories = remember { mutableStateMapOf<String, Boolean>() }

    var isLoading by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showEmployeePicker by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }
    var logContent by remember { mutableStateOf("") }

    val totalSum = dishes.sumOf { (orderState[it.rowIndex] ?: 0) * it.price }
    val budgetLimit = 600.0

    // Загрузка листов таблицы
    LaunchedEffect(spreadsheetId) {
        isLoading = true
        val sheets = GoogleSheetsManager.getSheetTitles(context, spreadsheetId)
        availableSheets = sheets
        if (sheets.isNotEmpty()) {
            selectedSheetTitle = sheets.first().title
        }
        isLoading = false
    }

    // Загрузка меню и заказов
    LaunchedEffect(selectedSheetTitle, employeeNumber) {
        if (selectedSheetTitle.isNotEmpty()) {
            isLoading = true
            val result = GoogleSheetsManager.loadMenuAndCurrentOrder(context, spreadsheetId, selectedSheetTitle, employeeNumber)
            dishes = result.dishes
            dateHeader = result.dateHeader
            orderState.clear()
            orderState.putAll(result.existingOrders)
            isLoading = false
        }
    }

    // Диалог логов
    if (showLogDialog) {
        AlertDialog(
            onDismissRequest = { showLogDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.BugReport, null, tint = Color(0xFF2E7D32))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Журнал ошибок (Лог)")
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Аккаунт: ${GoogleSheetsManager.SERVICE_ACCOUNT_EMAIL}",
                        fontSize = 11.sp,
                        color = Color.Gray,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                            .background(Color(0xFF1E1E1E), RoundedCornerShape(6.dp))
                            .padding(8.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = logContent.ifEmpty { "Записей в логе нет." },
                            color = Color(0xFF4AF626),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    AppLogger.shareLogFile(context)
                }) {
                    Icon(Icons.Default.Share, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Скачать / Поделиться")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    AppLogger.clearLog(context)
                    logContent = "Лог очищен."
                }) {
                    Text("Очистить", color = Color.Red)
                }
            }
        )
    }

    // Диалог настройки URL таблицы
    if (showSettingsDialog) {
        var tempUrl by remember { mutableStateOf(tableUrl) }
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("Настройка Google Таблицы") },
            text = {
                Column {
                    Text("Вставьте ссылку на Google Таблицу:", fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tempUrl,
                        onValueChange = { tempUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "⚠️ Важно: Добавьте почту сервисного аккаунта в доступ таблицы (как Редактора):\n${GoogleSheetsManager.SERVICE_ACCOUNT_EMAIL}",
                        fontSize = 11.sp,
                        color = Color(0xFFC62828)
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    tableUrl = tempUrl
                    spreadsheetId = GoogleSheetsManager.extractSpreadsheetId(tempUrl)
                    prefs.edit().putString("table_url", tempUrl).apply()
                    showSettingsDialog = false
                }) {
                    Text("Сохранить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) { Text("Отмена") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Обеды Нам-Ням", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        if (dateHeader.isNotEmpty()) {
                            Text(dateHeader, fontSize = 11.sp, color = Color.White.copy(alpha = 0.85f))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF2E7D32),
                    titleContentColor = Color.White
                ),
                actions = {
                    IconButton(onClick = {
                        logContent = AppLogger.readLog(context)
                        showLogDialog = true
                    }) {
                        Icon(Icons.Default.BugReport, contentDescription = "Логи", tint = Color.White)
                    }
                    IconButton(onClick = { showEmployeePicker = true }) {
                        Icon(Icons.Default.Person, contentDescription = "Сотрудник", tint = Color.White)
                    }
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки", tint = Color.White)
                    }
                }
            )
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp, color = Color.White) {
                Column(modifier = Modifier.padding(14.dp)) {
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
                                text = if (totalSum > budgetLimit) "Превышение бюджета!" else "Остаток: ${(budgetLimit - totalSum).toInt()} ₽",
                                fontSize = 12.sp,
                                color = if (totalSum > budgetLimit) Color.Red else Color.Gray
                            )
                        }

                        Button(
                            onClick = {
                                scope.launch {
                                    isLoading = true
                                    val (success, message) = GoogleSheetsManager.submitOrder(
                                        context = context,
                                        spreadsheetId = spreadsheetId,
                                        sheetTitle = selectedSheetTitle,
                                        employeeNumber = employeeNumber,
                                        allDishes = dishes,
                                        currentQuantities = orderState
                                    )
                                    isLoading = false
                                    if (success) {
                                        Toast.makeText(context, "✅ Заказ сохранен в таблице!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "❌ $message (см. Лог 📝)", Toast.LENGTH_LONG).show()
                                        logContent = AppLogger.readLog(context)
                                        showLogDialog = true
                                    }
                                }
                            },
                            enabled = !isLoading,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                        ) {
                            Text("Сохранить в таблице")
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
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Сотрудник:", fontSize = 13.sp)
                        Box {
                            AssistChip(
                                onClick = { showEmployeePicker = true },
                                label = { Text("Сотрудник №$employeeNumber", fontWeight = FontWeight.Bold) },
                                leadingIcon = { Icon(Icons.Default.Badge, null, modifier = Modifier.size(16.dp)) }
                            )

                            DropdownMenu(
                                expanded = showEmployeePicker,
                                onDismissRequest = { showEmployeePicker = false }
                            ) {
                                (1..35).forEach { num ->
                                    DropdownMenuItem(
                                        text = { Text("Сотрудник №$num") },
                                        onClick = {
                                            employeeNumber = num
                                            prefs.edit().putInt("employee_num", num).apply()
                                            showEmployeePicker = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text("Выберите день:", fontSize = 12.sp, color = Color.Gray)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        availableSheets.forEach { sheet: SheetInfo ->
                            val label = sheet.title.split(" ").firstOrNull() ?: sheet.title
                            FilterChip(
                                selected = selectedSheetTitle == sheet.title,
                                onClick = { selectedSheetTitle = sheet.title },
                                label = { Text(label, fontSize = 12.sp) }
                            )
                        }
                    }
                }
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF2E7D32))
                }
            } else {
                val groupedDishes = remember(dishes) { dishes.groupBy { it.category } }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 12.dp)
                ) {
                    groupedDishes.forEach { (category, categoryDishes) ->
                        val isCollapsed = collapsedCategories[category] ?: false
                        val categoryOrderedCount = categoryDishes.sumOf { orderState[it.rowIndex] ?: 0 }

                        item(key = "header_$category") {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .clickable {
                                        collapsedCategories[category] = !isCollapsed
                                    },
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFFE8F5E9)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        if (isCollapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                                        contentDescription = null,
                                        tint = Color(0xFF2E7D32)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        category,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (categoryOrderedCount > 0) {
                                        Badge(containerColor = Color(0xFF2E7D32)) {
                                            Text("$categoryOrderedCount", color = Color.White)
                                        }
                                    }
                                }
                            }
                        }

                        if (!isCollapsed) {
                            items(categoryDishes, key = { it.rowIndex }) { dish ->
                                val count = orderState[dish.rowIndex] ?: 0

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (count > 0) Color(0xFFF1F8E9) else Color.White
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .padding(10.dp)
                                            .fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        AsyncImage(
                                            model = ImageRequest.Builder(context)
                                                .data(dish.imageUrl)
                                                .crossfade(true)
                                                .build(),
                                            contentDescription = dish.name,
                                            modifier = Modifier
                                                .size(64.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0xFFE0E0E0))
                                                .clickable {
                                                    val i = Intent(Intent.ACTION_VIEW, Uri.parse(dish.descriptionUrl))
                                                    context.startActivity(i)
                                                },
                                            contentScale = ContentScale.Crop
                                        )

                                        Spacer(modifier = Modifier.width(10.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(dish.name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                            Text(
                                                "${dish.price.toInt()} ₽",
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF2E7D32),
                                                fontSize = 14.sp
                                            )
                                        }

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (count > 0) {
                                                IconButton(
                                                    onClick = { orderState[dish.rowIndex] = count - 1 },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(Icons.Default.RemoveCircleOutline, null, tint = Color.Red)
                                                }
                                                Text(
                                                    "$count",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 15.sp,
                                                    modifier = Modifier.padding(horizontal = 6.dp)
                                                )
                                            }
                                            IconButton(
                                                onClick = { orderState[dish.rowIndex] = count + 1 },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(Icons.Default.AddCircle, null, tint = Color(0xFF2E7D32))
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
    }
}