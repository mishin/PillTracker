package com.syncftp

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsDataStore: SettingsDataStore,
    onOpenCamera: () -> Unit,
    photoPath: String?,
    onOpenHistory: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(CameraSettings("", "", "", "ftp")) }
    var status by remember { mutableStateOf("Ожидание настройки") }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    LaunchedEffect(Unit) {
        settingsDataStore.settingsFlow.collect { newSettings ->
            settings = newSettings
        }
    }

    LaunchedEffect(photoPath) {
        if (photoPath != null) {
            status = "✅ Фото успешно отправлено!"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SyncFTP Camera", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF2196F3),
                    titleContentColor = Color.White
                ),
                actions = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Default.History, contentDescription = "История", tint = Color.White)
                    }
                }
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
            // Эмблема и название
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Кастомная эмблема (используем иконку)
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2196F3)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.CameraAlt,
                            contentDescription = "App Icon",
                            modifier = Modifier.size(48.dp),
                            tint = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "SyncFTP Camera",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2196F3)
                    )
                    Text(
                        text = "Автоматическая отправка фото на FTP/SFTP",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
            }

            // Protocol selection
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5)),
                shape = RoundedCornerShape(12.dp)
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

            // Server settings
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
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
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
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
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
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
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
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
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    OutlinedTextField(
                        value = settings.serverFolder,
                        onValueChange = {
                            settings = settings.copy(serverFolder = it)
                            scope.launch { settingsDataStore.saveSettings(settings) }
                        },
                        label = { Text("Папка на сервере") },
                        placeholder = { Text("/uploads/") },
                        leadingIcon = { Icon(Icons.Default.Folder, null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }

            // Action buttons row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Test connection button
                Button(
                    onClick = {
                        scope.launch {
                            isTesting = true
                            status = "Проверка подключения..."
                            try {
                                val success = if (settings.protocol == "ftp") {
                                    com.mishin.syncftp.FtpUploader.testConnection(settings)
                                } else {
                                    com.mishin.syncftp.SftpUploader.testConnection(settings)
                                }
                                if (success) {
                                    status = "✅ Подключение успешно"
                                    Toast.makeText(context, "Подключено к серверу", Toast.LENGTH_SHORT).show()
                                } else {
                                    status = "❌ Ошибка подключения"
                                }
                            } catch (e: Exception) {
                                status = "❌ Ошибка: ${e.message}"
                            }
                            isTesting = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isTesting,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9C27B0)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (isTesting) "..." else "Проверить")
                }

                // Camera button
                Button(
                    onClick = onOpenCamera,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.CameraAlt, null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Камера")
                }
            }

            // Status card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        status.contains("✅") -> Color(0xFFC8E6C9)
                        status.contains("❌") -> Color(0xFFFFCDD2)
                        else -> Color(0xFFE3F2FD)
                    }
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        when {
                            status.contains("✅") -> Icons.Default.CheckCircle
                            status.contains("❌") -> Icons.Default.Warning
                            else -> Icons.Default.Info
                        },
                        null,
                        tint = when {
                            status.contains("✅") -> Color(0xFF4CAF50)
                            status.contains("❌") -> Color(0xFFF44336)
                            else -> Color(0xFF2196F3)
                        }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Info card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF3E5F5)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("📌 Как это работает", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "1. Заполните настройки сервера\n2. Нажмите «Камера»\n3. Сделайте фото\n4. Фото сожмётся и отправится на сервер",
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 20.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(Icons.Default.Photo, null, modifier = Modifier.size(16.dp), tint = Color.Gray)
                        Text("Размер фото: ~50-150 КБ", fontSize = 11.sp, color = Color.Gray)
                        Icon(Icons.Default.Folder, null, modifier = Modifier.size(16.dp), tint = Color.Gray)
                        Text("Папка: ${settings.serverFolder}", fontSize = 11.sp, color = Color.Gray)
                    }
                }
            }
        }
    }
}