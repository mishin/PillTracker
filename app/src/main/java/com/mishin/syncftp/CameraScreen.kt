package com.syncftp

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.mishin.syncftp.FtpUploader
import com.mishin.syncftp.SftpUploader
import com.mishin.syncftp.UploadHistoryItem
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun CameraScreen(
    settingsDataStore: SettingsDataStore,
    onPhotoTaken: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var statusMessage by remember { mutableStateOf("Готов к съёмке") }
    var isUploading by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf<CameraSettings?>(null) }

    // Для PreviewView
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    // Load settings
    LaunchedEffect(Unit) {
        settingsDataStore.settingsFlow.collect { newSettings ->
            settings = newSettings
        }
    }

    // Camera permission launcher
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            statusMessage = "Нет разрешения на камеру"
        }
    }

    // Check permission on composition
    LaunchedEffect(Unit) {
        when {
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED -> {
                // Permission granted
            }
            else -> {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    // Camera setup
    LaunchedEffect(Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Create preview use case
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView?.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture
                )
            } catch (e: Exception) {
                statusMessage = "Ошибка камеры: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(context))
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Camera preview
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                    previewView = this
                }
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )

        // Status card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isUploading) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Text(
                text = statusMessage,
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        // Buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = onBack,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Text("Назад")
            }

            Button(
                onClick = {
                    if (isUploading) return@Button
                    if (settings == null) {
                        statusMessage = "Сначала настройте сервер"
                        return@Button
                    }
                    if (settings!!.server.isEmpty() || settings!!.username.isEmpty()) {
                        statusMessage = "Заполните настройки сервера"
                        return@Button
                    }

                    val capture = imageCapture
                    if (capture == null) {
                        statusMessage = "Камера не готова"
                        return@Button
                    }

                    isUploading = true
                    statusMessage = "Съёмка..."

                    val fileName = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                        .format(Date()) + ".jpg"

                    // Создаём временный файл для фото
                    val photoFile = File(context.cacheDir, fileName)
                    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

                    capture.takePicture(
                        outputOptions,
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                scope.launch {
                                    statusMessage = "Сжатие фото..."

                                    val compressedFile = File(context.cacheDir, "compressed_$fileName")
                                    val compressionSuccess = ImageCompressor.compressImage(
                                        photoFile.absolutePath,
                                        compressedFile
                                    )

                                    photoFile.delete()

                                    if (!compressionSuccess) {
                                        statusMessage = "Ошибка сжатия"
                                        isUploading = false
                                        return@launch
                                    }

                                    statusMessage = "Отправка на сервер..."

                                    val uploadSuccess = try {
                                        when (settings!!.protocol) {
                                            "ftp" -> FtpUploader.uploadFile(settings!!, compressedFile, fileName)
                                            else -> SftpUploader.uploadFile(settings!!, compressedFile, fileName)
                                        }
                                    } catch (e: Exception) {
                                        statusMessage = "Ошибка: ${e.message}"
                                        isUploading = false
                                        compressedFile.delete()
                                        return@launch
                                    }

                                    if (uploadSuccess) {
                                        statusMessage = "✅ Фото отправлено: $fileName"
                                        onPhotoTaken(compressedFile.absolutePath)
                                    } else {
                                        statusMessage = "❌ Ошибка отправки"
                                    }

                                    compressedFile.delete()
                                    isUploading = false
                                }

                                // После успешной отправки добавить в историю
                                val historyItem = UploadHistoryItem(
                                    fileName = fileName,
                                    fileSize = compressedFile.length(),
                                    uploadDate = System.currentTimeMillis(),
                                    status = "✅ Успешно",
                                    remotePath = "${settings!!.serverFolder}$fileName"
                                )
                                settingsDataStore.addToHistory(historyItem)
                            }

                            override fun onError(exception: ImageCaptureException) {
                                statusMessage = "Ошибка съёмки: ${exception.message}"
                                isUploading = false
                            }
                        }
                    )
                },
                modifier = Modifier.weight(1f),
                enabled = !isUploading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(if (isUploading) "Отправка..." else "📸 Снять фото")
            }
        }
    }
}