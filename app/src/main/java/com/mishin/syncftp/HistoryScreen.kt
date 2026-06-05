package com.syncftp

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mishin.syncftp.UploadHistoryItem
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    settingsDataStore: SettingsDataStore,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var history by remember { mutableStateOf<List<UploadHistoryItem>>(emptyList()) }

    LaunchedEffect(Unit) {
        settingsDataStore.uploadHistoryFlow.collect { newHistory: List<UploadHistoryItem> ->
            history = newHistory
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("История загрузок") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    if (history.isNotEmpty()) {
                        IconButton(onClick = {
                            scope.launch {
                                settingsDataStore.clearHistory()
                            }
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Очистить")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF2196F3),
                    titleContentColor = Color.White
                )
            )
        }
    ) { paddingValues ->
        if (history.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.History, null, modifier = Modifier.size(64.dp), tint = Color.Gray)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("История пуста", color = Color.Gray)
                    Text("Сделайте фото, чтобы оно появилось здесь", fontSize = 12.sp, color = Color.Gray)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(history) { item: UploadHistoryItem ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (item.status.contains("✅")) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
                        )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        if (item.status.contains("✅")) Icons.Default.CheckCircle else Icons.Default.Error,
                                        null,
                                        tint = if (item.status.contains("✅")) Color(0xFF4CAF50) else Color(0xFFF44336),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(text = item.fileName, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text(text = item.getFormattedSize(), fontSize = 12.sp, color = Color.Gray)
                                    Text(text = item.getFormattedDate(), fontSize = 12.sp, color = Color.Gray)
                                }
                                Text(text = item.remotePath, fontSize = 11.sp, color = Color(0xFF2196F3), maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}