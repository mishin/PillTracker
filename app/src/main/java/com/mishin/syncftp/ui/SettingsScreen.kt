package com.syncftp.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.syncftp.Settings
import com.syncftp.SettingsDataStore
import com.syncftp.SyncServiceManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsDataStore: SettingsDataStore,
    syncServiceManager: SyncServiceManager
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(Settings("", "", "", "", "ftp")) }
    var status by remember { mutableStateOf("Ожидание настройки") }
    var isPasswordVisible by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    
    // Load settings
    LaunchedEffect(Unit) {
        settingsDataStore.settingsFlow.collect { newSettings ->
            settings = newSettings
        }
    }
    
    // Set status callback
    LaunchedEffect(Unit) {
        syncServiceManager.setStatusCallback { newStatus ->
            status = newStatus
        }
    }
    
    // Folder picker
    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            val path = uri.path?.split(":")?.lastOrNull() ?: ""
            val fullPath = "/storage/emulated/0/$path"
            settings = settings.copy(localPath = fullPath)
            scope.launch {
                settingsDataStore.saveSettings(settings)
                Toast.makeText(context, "Папка выбрана: $fullPath", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SyncFTP") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF2196F3),
                    titleContentColor = Color.White
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Protocol selection card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Протокол", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = settings.protocol == "ftp",
                                onClick = {
                                    settings = settings.copy(protocol = "ftp", port = 21)
                                    scope.launch { settingsDataStore.saveSettings(settings) }
                                }
                            )
                            Text("FTP (порт 21)", modifier = Modifier.padding(start = 4.dp))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = settings.protocol == "sftp",
                                onClick = {
                                    settings = settings.copy(protocol = "sftp", port = 22)
                                    scope.launch { settingsDataStore.saveSettings(settings) }
                                }
                            )
                            Text("SFTP (порт 22)", modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
            }
            
            // Server settings card
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Настройки сервера", style = MaterialTheme.typography.titleMedium)
                    
                    OutlinedTextField(
                        value = settings.server,
                        onValueChange = { 
                            settings = settings.copy(server = it)
                            scope.launch { settingsDataStore.saveSettings(settings) }
                        },
                        label = { Text("Адрес сервера") },
                        placeholder = { Text("example.com или 192.168.1.100") },
                        leadingIcon = { Icon(Icons.Default.Dns, null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    OutlinedTextField(
                        value = settings.port.toString(),
                        onValueChange = {
                            it.toIntOrNull()?.let { port ->
                                settings = settings.copy(port = port)
                                scope.launch { settingsDataStore.saveSettings(settings) }
                            }
                        },
                        label = { Text("Порт") },
                        leadingIcon = { Icon(Icons.Default.Settings, null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    OutlinedTextField(
                        value = settings.username,
                        onValueChange = {
                            settings = settings.copy(username = it)
                            scope.launch { settingsDataStore.saveSettings(settings) }
                        },
                        label = { Text("Логин") },
                        leadingIcon = { Icon(Icons.Default.Person, null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    OutlinedTextField(
                        value = settings.password,
                        onValueChange = {
                            settings = settings.copy(password = it)
                            scope.launch { settingsDataStore.saveSettings(settings) }
                        },
                        label = { Text("Пароль") },
                        leadingIcon = { Icon(Icons.Default.Lock, null) },
                        trailingIcon = {
                            IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                Icon(
                                    if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    null
                                )
                            }
                        },
                        visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }
            
            // Local folder card
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Локальная папка", style = MaterialTheme.typography.titleMedium)
                    
                    Button(
                        onClick = { folderPickerLauncher.launch(null) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) {
                        Icon(Icons.Default.Folder, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Выбрать папку для синхронизации")
                    }
                    
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Выбранная папка:", style = MaterialTheme.typography.labelMedium)
                            Text(
                                text = settings.localPath.ifEmpty { "Не выбрана" },
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                    }
                    
                    Text(
                        text = "⚠️ Новые файлы в этой папке будут автоматически загружены на сервер",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFF9800)
                    )
                }
            }
            
            // Status card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (syncServiceManager.isActive()) Color(0xFFC8E6C9) else Color(0xFFFFCDD2)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (syncServiceManager.isActive()) Icons.Default.CheckCircle else Icons.Default.Warning,
                            null,
                            tint = if (syncServiceManager.isActive()) Color(0xFF4CAF50) else Color(0xFFF44336)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Статус", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            
            // Control buttons
            Button(
                onClick = {
                    if (settings.server.isEmpty() || settings.username.isEmpty() || settings.password.isEmpty() || settings.localPath.isEmpty()) {
                        Toast.makeText(context, "Заполните все поля и выберите папку", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    
                    if (syncServiceManager.isActive()) {
                        syncServiceManager.stopSync()
                    } else {
                        scope.launch {
                            settingsDataStore.saveSettings(settings)
                            syncServiceManager.startSync(settings)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (syncServiceManager.isActive()) Color(0xFFF44336) else Color(0xFF4CAF50)
                ),
                elevation = ButtonDefaults.buttonElevation(4.dp)
            ) {
                Icon(
                    if (syncServiceManager.isActive()) Icons.Default.Stop else Icons.Default.PlayArrow,
                    null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (syncServiceManager.isActive()) "Остановить синхронизацию" else "Запустить синхронизацию")
            }
            
            // Info card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF3E5F5))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("📌 Инструкция", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = """
                            1. Заполните данные сервера
                            2. Выберите локальную папку
                            3. Нажмите "Запустить синхронизацию"
                            4. Поместите файлы в выбранную папку
                            5. Файлы автоматически загрузятся на сервер
                        """.trimIndent(),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
fun SyncFTPTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF2196F3),
            secondary = Color(0xFF4CAF50),
            tertiary = Color(0xFFFF9800)
        ),
        content = content
    )
}