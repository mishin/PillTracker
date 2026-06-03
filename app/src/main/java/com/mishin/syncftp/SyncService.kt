package com.syncftp

import android.os.FileObserver
import kotlinx.coroutines.*
import java.io.File

class SyncService(
    private val context: android.content.Context,
    private val settings: Settings,
    private val onStatusUpdate: (String) -> Unit
) {
    private var fileObserver: FileObserver? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var processedFiles = mutableSetOf<String>()

    fun startWatching() {
        val dir = File(settings.localPath)
        if (!dir.exists()) {
            if (dir.mkdirs()) {
                onStatusUpdate("Создана локальная папка: ${settings.localPath}")
            } else {
                onStatusUpdate("Ошибка: не удалось создать папку ${settings.localPath}")
                return
            }
        }

        fileObserver = object : FileObserver(settings.localPath, FileObserver.CREATE or FileObserver.MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                if (path != null && !path.startsWith(".")) {
                    val file = File(settings.localPath, path)
                    if (file.isFile && !processedFiles.contains(path)) {
                        processedFiles.add(path)
                        onStatusUpdate("📁 Новый файл: $path")
                        uploadFile(file, path)

                        // Cleanup processed files set after delay
                        scope.launch {
                            delay(5000)
                            processedFiles.remove(path)
                        }
                    }
                }
            }
        }

        fileObserver?.startWatching()
        onStatusUpdate("👁️ Мониторинг папки: ${settings.localPath}")
        testConnection()
    }

    private fun testConnection() {
        scope.launch {
            try {
                when (settings.protocol) {
                    "ftp" -> FtpSync.testConnection(settings)
                    "sftp" -> SftpSync.testConnection(settings)
                }
                onStatusUpdate("✅ Подключение к серверу установлено")
            } catch (e: Exception) {
                onStatusUpdate("⚠️ Не удалось подключиться к серверу: ${e.message}")
            }
        }
    }

    private fun uploadFile(file: File, fileName: String) {
        scope.launch {
            try {
                onStatusUpdate("⬆️ Загрузка: $fileName")

                when (settings.protocol) {
                    "ftp" -> FtpSync.uploadFile(settings, file)
                    "sftp" -> SftpSync.uploadFile(settings, file)
                }

                withContext(Dispatchers.Main) {
                    onStatusUpdate("✅ Загружено: $fileName")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onStatusUpdate("❌ Ошибка загрузки $fileName: ${e.message}")
                }
            }
        }
    }

    fun stopWatching() {
        fileObserver?.stopWatching()
        scope.cancel()
        onStatusUpdate("⏹️ Мониторинг остановлен")
    }

    fun setStatusCallback(callback: (String) -> Unit) {
        // Already using onStatusUpdate, this is for compatibility
    }
}