package com.syncftp

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
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
    DisposableEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build()
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

        onDispose {
            ProcessCameraProvider.getInstance(context).get().unbindAll()
        }
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

                    val imageCapture = imageCapture
                    if (imageCapture == null) {
                        statusMessage = "Камера не готова"
                        return@Button
                    }

                    isUploading = true
                    statusMessage = "Съёмка..."

                    val fileName = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                        .format(Date()) + ".jpg"

                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/SyncFTP")
                        }
                    }

                    val outputOptions = ImageCapture.OutputFileOptions.Builder(
                        context.contentResolver,
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        contentValues
                    ).build()

                    imageCapture.takePicture(
                        outputOptions,
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                scope.launch {
                                    val savedUri = output.savedUri
                                    if (savedUri == null) {
                                        statusMessage = "Ошибка: не удалось сохранить фото"
                                        isUploading = false
                                        return@launch
                                    }
                                    statusMessage = "Сжатие фото..."

                                    // Get file from URI
                                    val inputStream = context.contentResolver.openInputStream(savedUri)
                                    val tempFile = File(context.cacheDir, "temp_$fileName")
                                    inputStream?.use { input ->
                                        tempFile.outputStream().use { output ->
                                            input.copyTo(output)
                                        }
                                    }
                                    inputStream?.close()

                                    val compressedFile = File(context.cacheDir, fileName)
                                    val compressionSuccess = ImageCompressor.compressImage(
                                        tempFile.absolutePath,
                                        compressedFile
                                    )

                                    tempFile.delete()

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