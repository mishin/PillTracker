package com.mishin.syncftp

import kotlinx.serialization.Serializable
import java.util.Date

@Serializable
data class UploadHistoryItem(
    val fileName: String,
    val fileSize: Long,
    val uploadDate: Long,
    val status: String,
    val remotePath: String
) {
    fun getFormattedSize(): String = when {
        fileSize < 1024 -> "$fileSize B"
        fileSize < 1024 * 1024 -> "${fileSize / 1024} KB"
        else -> "${fileSize / (1024 * 1024)} MB"
    }
    
    fun getFormattedDate(): String {
        val date = Date(uploadDate)
        return java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss", java.util.Locale.getDefault()).format(date)
    }
}