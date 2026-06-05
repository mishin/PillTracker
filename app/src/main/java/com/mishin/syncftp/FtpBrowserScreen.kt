package com.syncftp

import android.graphics.BitmapFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.mishin.syncftp.FtpFileInfo
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mishin.syncftp.FtpBrowser
import com.mishin.syncftp.SftpBrowser
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FtpBrowserScreen(
    settingsDataStore: SettingsDataStore,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var settings by remember { mutableStateOf<CameraSettings?>(null) }
    var files by remember { mutableStateOf<List<FtpFileInfo>>(emptyList()) }
    var currentPath by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showImagePreview by remember { mutableStateOf(false) }
    var previewImagePath by remember { mutableStateOf("") }
    var previewImageName by remember { mutableStateOf("") }

    suspend fun loadFiles(settings: CameraSettings, path: String) {
        isLoading = true
        errorMessage = null
        try {
            val fileList = if (settings.protocol == "ftp") {
                FtpBrowser.listFiles(settings, path)
            } else {
                SftpBrowser.listFiles(settings, path)
            }
            files = fileList
            currentPath = path
        } catch (e: Exception) {
            errorMessage = e.message
            files = emptyList()
        } finally {
            isLoading = false
        }
    }

    fun downloadAndPreview(fileInfo: FtpFileInfo) {
        scope.launch {
            isLoading = true
            try {
                val tempFile = File(context.cacheDir, fileInfo.name)
                val success = if (settings!!.protocol == "ftp") {
                    FtpBrowser.downloadFile(settings!!, fileInfo.path, tempFile)
                } else {
                    SftpBrowser.downloadFile(settings!!, fileInfo.path, tempFile)
                }
                if (success && tempFile.exists()) {
                    previewImagePath = tempFile.absolutePath
                    previewImageName = fileInfo.name
                    showImagePreview = true
                } else {
                    errorMessage = "Не удалось загрузить файл"
                }
            } catch (e: Exception) {
                errorMessage = "Ошибка: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        settingsDataStore.settingsFlow.collect { newSettings ->
            settings = newSettings
            if (settings != null) loadFiles(settings!!, "")
        }
    }

    if (showImagePreview && previewImagePath.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showImagePreview = false },
            title = { Text(previewImageName) },
            text = {
                Column {
                    val bitmap = BitmapFactory.decodeFile(previewImagePath)
                    if (bitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Preview",
                            modifier = Modifier.fillMaxWidth().height(400.dp)
                        )
                    } else {
                        Box(Modifier.fillMaxWidth().height(200.dp), Alignment.Center) {
                            Text("Не удалось загрузить изображение")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    val fileInfo = files.find { it.name == previewImageName }
                    if (fileInfo != null) {
                        Text("Размер: ${fileInfo.getFormattedSize()}", fontSize = 12.sp, color = Color.Gray)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showImagePreview = false }) {
                    Text("Закрыть")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FTP Браузер") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, null)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (settings != null) scope.launch { loadFiles(settings!!, currentPath) }
                    }) {
                        Icon(Icons.Default.Refresh, null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF2196F3),
                    titleContentColor = Color.White
                )
            )
        }
    ) { paddingValues ->
        Column(Modifier.fillMaxSize().padding(paddingValues)) {
            Card(
                Modifier.fillMaxWidth().padding(8.dp),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Folder, null, Modifier.size(20.dp), Color(0xFF2196F3))
                    Spacer(Modifier.width(8.dp))
                    Text(currentPath.ifEmpty { "/" }, fontSize = 12.sp, color = Color(0xFF2196F3))
                }
            }

            when {
                isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text("Загрузка...", color = Color.Gray)
                    }
                }
                errorMessage != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Error, null, Modifier.size(64.dp), Color.Red)
                        Spacer(Modifier.height(16.dp))
                        Text(errorMessage!!, color = Color.Red)
                        Button(onClick = { if (settings != null) scope.launch { loadFiles(settings!!, currentPath) } }) {
                            Text("Повторить")
                        }
                    }
                }
                files.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.FolderOpen, null, Modifier.size(64.dp), Color.Gray)
                        Spacer(Modifier.height(16.dp))
                        Text("Папка пуста", color = Color.Gray)
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
                    items(files) { file ->
                        Card(
                            Modifier.fillMaxWidth().clickable {
                                if (file.isDirectory && settings != null) {
                                    scope.launch { loadFiles(settings!!, file.path) }
                                } else if (!file.isDirectory && (file.name.endsWith(".jpg") || file.name.endsWith(".jpeg"))) {
                                    downloadAndPreview(file)
                                }
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (file.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                                    null, Modifier.size(32.dp),
                                    if (file.isDirectory) Color(0xFFFFC107) else Color(0xFF2196F3)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(file.name, fontWeight = if (file.isDirectory) FontWeight.Bold else FontWeight.Normal)
                                    if (!file.isDirectory) {
                                        Text("${file.getFormattedSize()} • ${file.getFormattedDate()}", fontSize = 11.sp, color = Color.Gray)
                                    }
                                }
                                if (file.isDirectory) Icon(Icons.Default.ChevronRight, null, tint = Color.Gray)
                            }
                        }
                    }
                }
            }
        }
    }
}