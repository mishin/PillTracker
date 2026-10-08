package ru.namnyam.menu.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

object AppLogger {

    private const val LOG_FILE_NAME = "app_logs.txt"

    // Запись сообщения в файл лога с временной меткой
    fun log(context: Context, tag: String, message: String, throwable: Throwable? = null) {
        try {
            val file = File(context.filesDir, LOG_FILE_NAME)
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            val writer = FileWriter(file, true)
            
            writer.append("[$timestamp] [$tag] $message\n")
            if (throwable != null) {
                writer.append(throwable.stackTraceToString()).append("\n")
            }
            writer.append("--------------------------------------------------\n")
            writer.flush()
            writer.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Чтение всего лога как строки
    fun readLog(context: Context): String {
        val file = File(context.filesDir, LOG_FILE_NAME)
        return if (file.exists()) file.readText() else "Лог-файл пуст."
    }

    // Очистка лога
    fun clearLog(context: Context) {
        val file = File(context.filesDir, LOG_FILE_NAME)
        if (file.exists()) file.delete()
    }

    // Экспорт / Поделиться файлом лога через системный диалог Android
    fun shareLogFile(context: Context) {
        val file = File(context.filesDir, LOG_FILE_NAME)
        if (!file.exists()) {
            file.writeText("Лог создан. Ошибок пока нет.\n")
        }

        val uri = FileProvider.getUriForFile(
            context,
            "ru.namnyam.menu.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Лог ошибок заказа Меню")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(Intent.createChooser(intent, "Отправить файл логов"))
    }
}