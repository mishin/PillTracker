package com.mishin.syncftp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import com.mishin.syncftp.ui.theme.SyncFTPTheme
import com.syncftp.*

class MainActivity : ComponentActivity() {

    private lateinit var settingsDataStore: SettingsDataStore
    val showCamera = mutableStateOf(false)
    val showHistory = mutableStateOf(false)
    val currentPhotoPath = mutableStateOf<String?>(null)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        if (!cameraGranted) {
            Toast.makeText(this, "Камера необходима для работы приложения", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settingsDataStore = SettingsDataStore(this)
        checkAndRequestPermissions()

        setContent {
            SyncFTPTheme {
                when {
                    showCamera.value -> {
                        CameraScreen(
                            settingsDataStore = settingsDataStore,
                            onPhotoTaken = { photoPath ->
                                currentPhotoPath.value = photoPath
                                showCamera.value = false
                            },
                            onBack = { showCamera.value = false }
                        )
                    }
                    showHistory.value -> {
                        HistoryScreen(
                            settingsDataStore = settingsDataStore,
                            onBack = { showHistory.value = false }
                        )
                    }
                    else -> {
                        SettingsScreen(
                            settingsDataStore = settingsDataStore,
                            onOpenCamera = { showCamera.value = true },
                            photoPath = currentPhotoPath.value,
                            onOpenHistory = { showHistory.value = true }
                        )
                    }
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isNotEmpty()) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }
}