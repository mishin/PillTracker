// ОБЯЗАТЕЛЬНО: объявление пакета
package ru.mishin.pilltracker

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.room.Room

// Импортируем классы из ваших новых пакетов
import ru.mishin.pilltracker.data.AppDatabase
import ru.mishin.pilltracker.data.Medication
import ru.mishin.pilltracker.view.MedicationViewModel
import ru.mishin.pilltracker.service.scheduleMedicationAlarm
import ru.mishin.pilltracker.util.scheduleDailyReset
import android.net.Uri
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "meds_db").build()
        val viewModel = MedicationViewModel(db.medicationDao())

        setContent {
            MaterialTheme {
                MedicationAppScreen(viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicationAppScreen(viewModel: MedicationViewModel) {
    val context = LocalContext.current
    val medications by viewModel.medications.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    // Состояния для импорта CSV
    var pendingCsvUri by remember { mutableStateOf<Uri?>(null) }
    var showReplaceDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Внутри функции MedicationAppScreen добавьте состояние для диалога
    var showUrlDialog by remember { mutableStateOf(false) }
    var inputUrl by remember { mutableStateOf("") }

    // Лаунчер разрешений (существующий)
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    // НОВЫЙ лаунчер: выбор CSV-файла через системный file picker (без разрешений!)
    val csvFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (medications.isEmpty()) {
            // Список пуст — импортируем сразу
            launchCsvImport(
                context = context,
                viewModel = viewModel,
                uri = uri,
                replaceExisting = false,
                scope = scope,
                snackbarHostState = snackbarHostState
            )
        } else {
            // Уже есть данные — спросим пользователя
            pendingCsvUri = uri
            showReplaceDialog = true
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        scheduleDailyReset(context)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Мои лекарства (График)") },
                    // В TopAppBar добавьте кнопку рядом с иконкой загрузки
                actions = {
                    // Новая кнопка для импорта по ссылке
                    IconButton(onClick = { showUrlDialog = true }) {
                        Icon(Icons.Filled.Link, contentDescription = "Импорт по ссылке") // Импортируйте иконку Link
                    }
                    // Существующая кнопка для локального файла
                    IconButton(onClick = { csvFileLauncher.launch("*/*") }) {
                        Icon(Icons.Filled.Download, contentDescription = "Импорт CSV")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, "Добавить")
            }
        }
    ) { padding ->
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize().padding(16.dp)) {
            items(medications) { med ->
                MedicationCard(
                    medication = med,
                    daysLeft = viewModel.calculateDaysLeft(med),
                    onCheckedChange = { isChecked -> viewModel.toggleTakenStatus(med, isChecked) },
                    onDelete = { viewModel.deleteMedication(med) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        if (showAddDialog) {
            AddMedicationDialog(
                onDismiss = { showAddDialog = false },
                onSave = { name, hour, minute, duration ->
                    val newMed = Medication(
                        name = name,
                        hour = hour,
                        minute = minute,
                        startTimestamp = System.currentTimeMillis(), // Начинаем отсчет "сегодня"
                        durationDays = duration
                    )
                    viewModel.addMedication(newMed) { newId ->
                        scheduleMedicationAlarm(context, newMed, newId)
                    }
                    showAddDialog = false
                }
            )
        }
        // В конце функции (перед закрывающей скобкой) добавьте диалог ввода ссылки
        if (showUrlDialog) {
            AlertDialog(
                onDismissRequest = { showUrlDialog = false },
                title = { Text("Импорт из Google Таблиц") },
                text = {
                    Column {
                        Text("Вставьте ссылку на таблицу (доступную для просмотра по ссылке):")
                        OutlinedTextField(
                            value = inputUrl,
                            onValueChange = { inputUrl = it },
                            placeholder = { Text("https://docs.google.com/...") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        showUrlDialog = false
                        // Вызов метода ViewModel
                        viewModel.importFromGoogleSheetsUrl(
                            urlString = inputUrl,
                            replaceExisting = medications.isNotEmpty(), // Можно добавить логику с диалогом замены
                            onMedicationAdded = { med, id -> scheduleMedicationAlarm(context, med, id) },
                            onComplete = { count ->
                                scope.launch { snackbarHostState.showSnackbar("Загружено из сети: $count") }
                            },
                            onError = { msg ->
                                scope.launch { snackbarHostState.showSnackbar("Ошибка: $msg") }
                            }
                        )
                    }) { Text("Загрузить") }
                },
                dismissButton = {
                    TextButton(onClick = { showUrlDialog = false }) { Text("Отмена") }
                }
            )
        }
    }

    // НОВЫЙ диалог выбора режима импорта (только когда список не пуст)
    if (showReplaceDialog && pendingCsvUri != null) {
        AlertDialog(
            onDismissRequest = { showReplaceDialog = false; pendingCsvUri = null },
            title = { Text("Импорт из CSV") },
            text = {
                Text("В списке уже ${medications.size} записей. Заменить их или добавить к существующим?")
            },
            confirmButton = {
                Button(onClick = {
                    val uri = pendingCsvUri!!
                    showReplaceDialog = false
                    pendingCsvUri = null
                    launchCsvImport(context, viewModel, uri, replaceExisting = true, scope, snackbarHostState)
                }) { Text("Заменить") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val uri = pendingCsvUri!!
                    showReplaceDialog = false
                    pendingCsvUri = null
                    launchCsvImport(context, viewModel, uri, replaceExisting = false, scope, snackbarHostState)
                }) { Text("Добавить") }
            }
        )
    }
}

/** Общий хелпер запуска импорта с единообразной обработкой результата. */
private fun launchCsvImport(
    context: android.content.Context,
    viewModel: MedicationViewModel,
    uri: Uri,
    replaceExisting: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbarHostState: SnackbarHostState
) {
    viewModel.importFromCsv(
        context = context,
        uri = uri,
        replaceExisting = replaceExisting,
        onMedicationAdded = { med, id -> scheduleMedicationAlarm(context, med, id) },
        onComplete = { count ->
            scope.launch {
                snackbarHostState.showSnackbar("Импортировано лекарств: $count")
            }
        },
        onError = { msg ->
            scope.launch {
                snackbarHostState.showSnackbar("Ошибка импорта: $msg")
            }
        }
    )
}

@Composable
fun MedicationCard(
    medication: Medication,
    daysLeft: Int,
    onCheckedChange: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = medication.name, style = MaterialTheme.typography.titleLarge)
                Text(text = "Время: ${String.format("%02d:%02d", medication.hour, medication.minute)}")
                Text(
                    text = "Осталось дней: $daysLeft из ${medication.durationDays}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            Checkbox(
                checked = medication.isTakenToday,
                onCheckedChange = onCheckedChange
            )
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Удалить")
            }
        }
    }
}

@Composable
fun AddMedicationDialog(onDismiss: () -> Unit, onSave: (String, Int, Int, Int) -> Unit) {
    var name by remember { mutableStateOf("") }
    var hour by remember { mutableStateOf("08") }
    var minute by remember { mutableStateOf("00") }
    var duration by remember { mutableStateOf("14") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новое лекарство") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Название") })
                Row {
                    OutlinedTextField(value = hour, onValueChange = { hour = it }, label = { Text("Час (0-23)") }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(value = minute, onValueChange = { minute = it }, label = { Text("Мин") }, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(value = duration, onValueChange = { duration = it }, label = { Text("Дней курса") })
            }
        },
        confirmButton = {
            Button(onClick = { 
                onSave(name, hour.toIntOrNull() ?: 8, minute.toIntOrNull() ?: 0, duration.toIntOrNull() ?: 1) 
            }) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}