package com.mishin.syncftp

import java.text.SimpleDateFormat
import java.util.*

data class FtpFileInfo(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val modifiedDate: Long = 0
) {
    fun getFormattedSize(): String = when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${size / 1024} KB"
        else -> "${size / (1024 * 1024)} MB"
    }
    
    fun getFormattedDate(): String {
        if (modifiedDate == 0L) return ""
        val date = Date(modifiedDate)
        return SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(date)
    }
}