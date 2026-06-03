package com.syncftp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class SyncServiceManager(private val context: Context) {
    
    private var syncService: SyncService? = null
    private var isRunning = false
    private var statusCallback: ((String) -> Unit)? = null
    
    fun setStatusCallback(callback: (String) -> Unit) {
        statusCallback = callback
        syncService?.setStatusCallback(callback)
    }
    
    fun startSync(settings: Settings) {
        if (isRunning) return
        
        syncService = SyncService(context, settings) { status ->
            statusCallback?.invoke(status)
        }
        syncService?.startWatching()
        isRunning = true
        statusCallback?.invoke("Синхронизация запущена")
    }
    
    fun stopSync() {
        syncService?.stopWatching()
        syncService = null
        isRunning = false
        statusCallback?.invoke("Синхронизация остановлена")
    }
    
    fun isActive(): Boolean = isRunning
}