package com.syncftp

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mishin.syncftp.UploadHistoryItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.flow.first

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsDataStore(private val context: Context) {

    companion object {
        private val SERVER = stringPreferencesKey("server")
        private val USERNAME = stringPreferencesKey("username")
        private val PASSWORD = stringPreferencesKey("password")
        private val PROTOCOL = stringPreferencesKey("protocol")
        private val PORT = stringPreferencesKey("port")
        private val SERVER_FOLDER = stringPreferencesKey("server_folder")
        private val UPLOAD_HISTORY = stringPreferencesKey("upload_history")
    }

    val settingsFlow: Flow<CameraSettings> = context.dataStore.data.map { preferences ->
        CameraSettings(
            server = preferences[SERVER] ?: "",
            username = preferences[USERNAME] ?: "",
            password = preferences[PASSWORD] ?: "",
            protocol = preferences[PROTOCOL] ?: "ftp",
            port = preferences[PORT]?.toIntOrNull() ?: 21,
            serverFolder = preferences[SERVER_FOLDER] ?: "/uploads/"
        )
    }

    val uploadHistoryFlow: Flow<List<UploadHistoryItem>> = context.dataStore.data.map { preferences ->
        val json = preferences[UPLOAD_HISTORY] ?: return@map emptyList()
        try {
            Json.decodeFromString<List<UploadHistoryItem>>(json)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun saveSettings(settings: CameraSettings) {
        context.dataStore.edit { preferences ->
            preferences[SERVER] = settings.server
            preferences[USERNAME] = settings.username
            preferences[PASSWORD] = settings.password
            preferences[PROTOCOL] = settings.protocol
            preferences[PORT] = settings.port.toString()
            preferences[SERVER_FOLDER] = settings.serverFolder
        }
    }

    suspend fun addToHistory(item: UploadHistoryItem) {
        val currentHistory = uploadHistoryFlow.first().toMutableList()
        currentHistory.add(0, item) // Новые сверху
        // Оставляем только последние 50 записей
        if (currentHistory.size > 50) {
            currentHistory.removeAt(currentHistory.lastIndex)
        }
        val json = Json.encodeToString(currentHistory)
        context.dataStore.edit { preferences ->
            preferences[UPLOAD_HISTORY] = json
        }
    }

    suspend fun clearHistory() {
        context.dataStore.edit { preferences ->
            preferences[UPLOAD_HISTORY] = ""
        }
    }

    suspend fun clearSettings() {
        context.dataStore.edit { it.clear() }
    }
}

data class CameraSettings(
    val server: String,
    val username: String,
    val password: String,
    val protocol: String,
    val port: Int = 21,
    val serverFolder: String = "/uploads/"
)