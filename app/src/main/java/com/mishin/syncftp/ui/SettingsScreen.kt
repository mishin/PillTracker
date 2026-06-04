package com.mishin.syncftp

import android.widget.Toast
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
import com.syncftp.CameraSettings
import com.syncftp.SettingsDataStore
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsDataStore: SettingsDataStore,
    onOpenCamera: () -> Unit,
    photoPath: String?
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(CameraSettings("", "", "", "ftp")) }
    var status by remember { mutableStateOf("Ожидание настройки") }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    // Load settings
    LaunchedEffect(Unit) {
        settingsDataStore.settingsFlow.collect { newSettings ->
            settings = newSettings
        }
    }

    // Show last photo status
    LaunchedEffect(photoPath) {
        if (photoPath != null) {
            status = "✅ Последнее фото отправлено"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SyncFTP Camera") },
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
            // Protocol selection
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

            // Server settings
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
                        singleLine = true
                    )
                }
            }

            // Test connection button
            Button(
                onClick = {
                    scope.launch {
                        isTesting = true
                        status = "Проверка подключения..."
                        try {
                            val success = if (settings.protocol == "ftp") {
                                FtpUploader.testConnection(settings)
                            } else {
                                SftpUploader.testConnection(settings)
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
                modifier = Modifier.fillMaxWidth(),
                enabled = !isTesting,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9C27B0))
            ) {
                Icon(Icons.Default.CheckCircle, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isTesting) "Проверка..." else "Проверить подключение")
            }

            // Camera button
            Button(
                onClick = onOpenCamera,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                elevation = ButtonDefaults.buttonElevation(8.dp)
            ) {
                Icon(Icons.Default.CameraAlt, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("📷 Открыть камеру")
            }

            // Status card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (status.contains("✅")) Color(0xFFC8E6C9)
                    else if (status.contains("❌")) Color(0xFFFFCDD2)
                    else Color(0xFFE3F2FD)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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

            // Info card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF3E5F5))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("📌 Как это работает", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = """
                            1. Заполните настройки сервера
                            2. Нажмите "Открыть камеру"
                            3. Сделайте фото
                            4. Фото автоматически сожмётся (640x480)
                            5. И отправится на FTP/SFTP сервер
                        """.trimIndent(),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "💡 Размер фото: ~50-150 КБ\n📁 Папка на сервере: ${settings.serverFolder}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
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
}