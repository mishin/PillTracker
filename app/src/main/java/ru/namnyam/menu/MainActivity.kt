package ru.namnyam.menu

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import ru.namnyam.menu.data.AppLogger
import ru.namnyam.menu.data.DishItem
import ru.namnyam.menu.data.GoogleSheetsManager
import ru.namnyam.menu.data.OrderStorage
import ru.namnyam.menu.data.ReminderManager
import ru.namnyam.menu.data.SheetInfo

class MainActivity : ComponentActivity() {

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GoogleSheetsManager.initCredentials(applicationContext)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        ReminderManager.scheduleDailyReminder(applicationContext, 10, 30)

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
            prefs.getString("table_url", "https://docs.google.com/spreadsheets/d/10hP-ob73NNvkxTOSgq_Ri6xgMaXA0lO1g-f6CzyYPVA/edit") ?: ""
        )
    }
    var employeeNumber by remember { mutableStateOf(prefs.getInt("employee_num", 33)) }

    var spreadsheetId by remember { mutableStateOf(GoogleSheetsManager.extractSpreadsheetId(tableUrl)) }
    var availableSheets by remember { mutableStateOf<List<SheetInfo>>(emptyList()) }
    var selectedSheetTitle by remember { mutableStateOf("") }
    var dateHeader by remember { mutableStateOf("") }

    var dishes by remember { mutableStateOf<List<DishItem>>(emptyList()) }
    val orderState = remember { mutableStateMapOf<Int, Int>() }
    val collapsedCategories = remember { mutableStateMapOf<String, Boolean>() }

    // Состояния фильтрации и видимости блоков
    var isFiltersVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var excludeFilterText by remember { mutableStateOf("") }
    var showOnlyOrdered by remember { mutableStateOf(false) }
    var selectedDishForDetails by remember { mutableStateOf<DishItem?>(null) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var showOrderSummarySheet by remember { mutableStateOf(false) }

    var isLoading by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showEmployeePicker by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }
    var logContent by remember { mutableStateOf("") }

    val totalSum = dishes.sumOf { (orderState[it.rowIndex] ?: 0) * it.price }
    val totalCount = dishes.sumOf { orderState[it.rowIndex] ?: 0 }
    val budgetLimit = 600.0

    val budgetProgress = (totalSum / budgetLimit).toFloat().coerceIn(0f, 1f)
    val budgetIndicatorColor = when {
        totalSum > budgetLimit -> Color(0xFFD32F2F)
        totalSum > 450.0 -> Color(0xFFFF9800)
        else -> Color(0xFF2E7D32)
    }

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

    // Загрузка меню (сначала локальный кэш, затем сеть)
    LaunchedEffect(selectedSheetTitle, employeeNumber) {
        if (selectedSheetTitle.isNotEmpty()) {
            val cached = OrderStorage.loadMenuCache(context, selectedSheetTitle)
            if (cached != null && cached.dishes.isNotEmpty()) {
                dishes = cached.dishes
                dateHeader = cached.dateHeader
                orderState.clear()
                orderState.putAll(cached.existingOrders)
            }

            isLoading = dishes.isEmpty()
            val result = GoogleSheetsManager.loadMenuAndCurrentOrder(context, spreadsheetId, selectedSheetTitle, employeeNumber)
            if (result.dishes.isNotEmpty()) {
                dishes = result.dishes
                dateHeader = result.dateHeader
                orderState.clear()
                orderState.putAll(result.existingOrders)
                OrderStorage.saveMenuCache(context, selectedSheetTitle, result)
            }
            isLoading = false
        }
    }

    val formattedDayDate = remember(selectedSheetTitle, dateHeader) {
        if (dateHeader.isNotEmpty()) {
            dateHeader
        } else {
            val match = Regex("""(\d{2})_(\d{2})_(\d{4})""").find(selectedSheetTitle)
            if (match != null) {
                "${match.groupValues[1]}.${match.groupValues[2]}.${match.groupValues[3]} г."
            } else {
                selectedSheetTitle
            }
        }
    }

    // Фильтрация
    val filteredDishes = remember(dishes, searchQuery, excludeFilterText, showOnlyOrdered, orderState.toMap()) {
        val query = searchQuery.trim().lowercase()
        val exclude = excludeFilterText.trim().lowercase()

        dishes.filter { dish ->
            val count = orderState[dish.rowIndex] ?: 0
            if (showOnlyOrdered && count == 0) {
                return@filter false
            }
            val matchesQuery = query.isEmpty() || dish.name.lowercase().contains(query)
            val notExcluded = exclude.isEmpty() || (
                !dish.name.lowercase().contains(exclude) &&
                !dish.composition.lowercase().contains(exclude)
            )
            matchesQuery && notExcluded
        }
    }

    // Список выбранных блюд
    val selectedDishesSummary = remember(dishes, orderState.toMap()) {
        dishes.mapNotNull { dish ->
            val count = orderState[dish.rowIndex] ?: 0
            if (count > 0) Pair(dish, count) else null
        }
    }

    // Сводка заказа перед сохранением (ModalBottomSheet)
    if (showOrderSummarySheet) {
        ModalBottomSheet(
            onDismissRequest = { showOrderSummarySheet = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (totalCount > 0) "Сводка заказа" else "Обнуление заказа",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Сотрудник №$employeeNumber",
                        fontSize = 13.sp,
                        color = Color.Gray,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (selectedDishesSummary.isEmpty()) {
                    Surface(
                        color = Color(0xFFFFEBEE),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "⚠️ В заказе не выбрано ни одного блюда.",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFC62828),
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "При сохранении колонка сотрудника №$employeeNumber в таблице будет очищена.",
                                color = Color(0xFF555555),
                                fontSize = 12.sp
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        selectedDishesSummary.forEach { (dish, qty) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(dish.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        "${dish.price.toInt()} ₽ × $qty шт.",
                                        fontSize = 12.sp,
                                        color = Color.Gray
                                    )
                                }
                                Text(
                                    "${(dish.price * qty).toInt()} ₽",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF2E7D32)
                                )
                            }
                            Divider(color = Color(0xFFEEEEEE))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Итого к сохранению:", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        "${totalSum.toInt()} ₽",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = if (totalSum > budgetLimit) Color.Red else Color(0xFF2E7D32)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        showOrderSummarySheet = false
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
                                OrderStorage.saveOrderTemplate(context, employeeNumber, selectedSheetTitle, orderState)
                                val okMsg = if (totalCount > 0) "✅ Заказ сохранен в таблице!" else "✅ Заказ успешно обнулен в таблице!"
                                Toast.makeText(context, okMsg, Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "❌ $message (см. Лог 📝)", Toast.LENGTH_LONG).show()
                                logContent = AppLogger.readLog(context)
                                showLogDialog = true
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (totalCount > 0) Color(0xFF2E7D32) else Color(0xFFD32F2F)
                    )
                ) {
                    Icon(
                        if (totalCount > 0) Icons.Default.Check else Icons.Default.DeleteSweep,
                        null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (totalCount > 0) "Подтвердить и отправить" else "Обнулить заказ в таблице")
                }

                Spacer(modifier = Modifier.height(10.dp))
            }
        }
    }

    // Диалог подтверждения очистки заказа
    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text("Очистить текущий выбор?") },
            text = { Text("Все выбранные блюда ($totalCount шт.) будут сброшены. Чтобы удалить заказ и из Google Таблицы, нажмите «Сохранить» после сброса.") },
            confirmButton = {
                Button(
                    onClick = {
                        orderState.clear()
                        showClearConfirmDialog = false
                        Toast.makeText(context, "Выбор сброшен. Нажмите 'Сохранить', чтобы обнулить в таблице", Toast.LENGTH_LONG).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text("Сбросить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }

    // Диалог подробностей блюда
    selectedDishForDetails?.let { dish ->
        Dialog(onDismissRequest = { selectedDishForDetails = null }) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = dish.name.uppercase(),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF212121),
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { selectedDishForDetails = null }) {
                            Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color(0xFF7CB342))
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(dish.imageUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = dish.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(210.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFEEEEEE)),
                        contentScale = ContentScale.Crop
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    if (dish.composition.isNotEmpty()) {
                        Text(text = "Состав: ${dish.composition}", fontSize = 13.sp, color = Color(0xFF558B2F), lineHeight = 18.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    if (dish.minerals.isNotEmpty()) {
                        Text(text = "Макро и микроэлементы: ${dish.minerals}", fontSize = 13.sp, color = Color(0xFF689F38))
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    if (dish.vitamins.isNotEmpty()) {
                        Text(text = "Витамины: ${dish.vitamins}", fontSize = 13.sp, color = Color(0xFF689F38))
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    if (dish.caloriesInfo.isNotEmpty()) {
                        Text(text = "Энергетическая ценность: ${dish.caloriesInfo}", fontSize = 13.sp, color = Color(0xFF689F38))
                        Spacer(modifier = Modifier.height(14.dp))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "${dish.price.toInt()} руб.", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF689F38))
                        val currentCount = orderState[dish.rowIndex] ?: 0
                        OutlinedButton(
                            onClick = {
                                orderState[dish.rowIndex] = currentCount + 1
                                Toast.makeText(context, "Добавлено: ${dish.name}", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF2E7D32))
                        ) {
                            Text("В корзину", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
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
                    Text("Аккаунт: ${GoogleSheetsManager.SERVICE_ACCOUNT_EMAIL}", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                            .background(Color(0xFF1E1E1E), RoundedCornerShape(6.dp))
                            .padding(8.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(text = logContent.ifEmpty { "Записей в логе нет." }, color = Color(0xFF4AF626), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
            },
            confirmButton = {
                Button(onClick = { AppLogger.shareLogFile(context) }) {
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

    // Диалог настроек таблицы
    if (showSettingsDialog) {
        var tempUrl by remember { mutableStateOf(tableUrl) }
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("Настройка Google Таблицы") },
            text = {
                Column {
                    Text("Вставьте ссылку на Google Таблицу:", fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = tempUrl, onValueChange = { tempUrl = it }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
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
            dismissButton = { TextButton(onClick = { showSettingsDialog = false }) { Text("Отмена") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Обеды Нам-Ням", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text(formattedDayDate, fontSize = 11.sp, color = Color.White.copy(alpha = 0.9f))
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
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    LinearProgressIndicator(
                        progress = budgetProgress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = budgetIndicatorColor,
                        trackColor = Color(0xFFE0E0E0)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Итого: ${totalSum.toInt()} ₽ / ${budgetLimit.toInt()} ₽",
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp,
                                color = if (totalSum > budgetLimit) Color.Red else Color(0xFF2E7D32)
                            )
                            Text(
                                text = if (totalSum > budgetLimit) "Превышение бюджета!" else "Остаток: ${(budgetLimit - totalSum).toInt()} ₽",
                                fontSize = 12.sp,
                                color = if (totalSum > budgetLimit) Color.Red else Color.Gray
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (totalCount > 0) {
                                IconButton(
                                    onClick = { showClearConfirmDialog = true },
                                    modifier = Modifier.padding(end = 4.dp)
                                ) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "Очистить выбор", tint = Color.Red)
                                }
                            }

                            // Кнопка активна ВСЕГДА, пока нет процесса загрузки (позволяет отправить и пустой заказ)
                            Button(
                                onClick = { showOrderSummarySheet = true },
                                enabled = !isLoading,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (totalCount > 0) Color(0xFF2E7D32) else Color(0xFFD32F2F)
                                )
                            ) {
                                Text(if (totalCount > 0) "Сохранить" else "Обнулить")
                            }
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

                    Spacer(modifier = Modifier.height(4.dp))

                    val daysScrollState = rememberScrollState()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(daysScrollState)
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        availableSheets.forEach { sheet: SheetInfo ->
                            val match = Regex("""(\d{2})_(\d{2})""").find(sheet.title)
                            val shortLabel = if (match != null) {
                                val dayName = sheet.title.split(" ").firstOrNull() ?: ""
                                "$dayName ${match.groupValues[1]}.${match.groupValues[2]}"
                            } else {
                                sheet.title.split(" ").firstOrNull() ?: sheet.title
                            }

                            FilterChip(
                                selected = selectedSheetTitle == sheet.title,
                                onClick = { selectedSheetTitle = sheet.title },
                                label = { Text(shortLabel, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = isFiltersVisible || searchQuery.isNotEmpty() || excludeFilterText.isNotEmpty(),
                            onClick = { isFiltersVisible = !isFiltersVisible },
                            leadingIcon = {
                                Icon(
                                    if (isFiltersVisible) Icons.Default.FilterAlt else Icons.Default.FilterList,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            label = {
                                Text(
                                    if (isFiltersVisible) "Скрыть фильтры" else "Фильтры и поиск",
                                    fontSize = 12.sp
                                )
                            }
                        )

                        FilterChip(
                            selected = showOnlyOrdered,
                            onClick = { showOnlyOrdered = !showOnlyOrdered },
                            leadingIcon = { Icon(Icons.Default.ShoppingCart, null, modifier = Modifier.size(16.dp)) },
                            label = { Text("Мой заказ ($totalCount)") }
                        )

                        FilledTonalButton(
                            onClick = {
                                val combo = OrderStorage.generateBalancedCombo(dishes, budgetLimit)
                                if (combo.isNotEmpty()) {
                                    orderState.clear()
                                    orderState.putAll(combo)
                                    Toast.makeText(context, "✨ Собрано комбо на 600 ₽!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Не удалось собрать комбо", Toast.LENGTH_SHORT).show()
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.AutoFixHigh, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Комбо на 600 ₽", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                val template = OrderStorage.loadOrderTemplate(context, employeeNumber, selectedSheetTitle)
                                if (template != null && template.isNotEmpty()) {
                                    orderState.clear()
                                    orderState.putAll(template)
                                    Toast.makeText(context, "🔄 Заказ восстановлен!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "История для этого дня пуста", Toast.LENGTH_SHORT).show()
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.History, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Повторить заказ", fontSize = 12.sp)
                        }
                    }

                    AnimatedVisibility(
                        visible = isFiltersVisible,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                placeholder = { Text("Поиск блюда (например: карбонара)", fontSize = 13.sp) },
                                leadingIcon = {
                                    Icon(Icons.Default.Search, contentDescription = "Поиск", tint = Color(0xFF2E7D32), modifier = Modifier.size(20.dp))
                                },
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(Icons.Default.Clear, contentDescription = "Очистить", modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFFF1F8E9),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Исключить аллергены:",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            color = Color(0xFF2E7D32)
                                        )

                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (filteredDishes.size < dishes.size) Color(0xFF2E7D32) else Color(0xFFE0E0E0)
                                        ) {
                                            Text(
                                                text = "${filteredDishes.size} из ${dishes.size}",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (filteredDishes.size < dishes.size) Color.White else Color(0xFF424242),
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))

                                    OutlinedTextField(
                                        value = excludeFilterText,
                                        onValueChange = { excludeFilterText = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        placeholder = { Text("Исключить продукт (например: лук)", fontSize = 12.sp) },
                                        trailingIcon = {
                                            if (excludeFilterText.isNotEmpty()) {
                                                IconButton(onClick = { excludeFilterText = "" }) {
                                                    Icon(Icons.Default.Clear, contentDescription = "Сброс", modifier = Modifier.size(16.dp))
                                                }
                                            }
                                        }
                                    )

                                    Spacer(modifier = Modifier.height(4.dp))

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.horizontalScroll(rememberScrollState())
                                    ) {
                                        listOf("лук", "чеснок", "перец", "мясо").forEach { allergen ->
                                            val isSelected = excludeFilterText.equals(allergen, ignoreCase = true)
                                            FilterChip(
                                                selected = isSelected,
                                                onClick = { excludeFilterText = if (isSelected) "" else allergen },
                                                label = { Text("Без $allergen", fontSize = 11.sp) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF2E7D32))
                }
            } else {
                val groupedDishes = remember(filteredDishes) { filteredDishes.groupBy { it.category } }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Категории меню (${groupedDishes.size})",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        fontWeight = FontWeight.SemiBold
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Свернуть все",
                            fontSize = 12.sp,
                            color = Color(0xFF2E7D32),
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable {
                                groupedDishes.keys.forEach { cat -> collapsedCategories[cat] = true }
                            }
                        )
                        Text(text = "•", fontSize = 12.sp, color = Color.Gray)
                        Text(
                            text = "Развернуть все",
                            fontSize = 12.sp,
                            color = Color(0xFF2E7D32),
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable {
                                collapsedCategories.clear()
                            }
                        )
                    }
                }

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
                                    .clickable { collapsedCategories[category] = !isCollapsed },
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
                                    Text(category, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                    Text("${categoryDishes.size}", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(end = 6.dp))
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
                                                .clickable { selectedDishForDetails = dish },
                                            contentScale = ContentScale.Crop
                                        )

                                        Spacer(modifier = Modifier.width(10.dp))

                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable { selectedDishForDetails = dish }
                                        ) {
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