package com.syncftp

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsDataStore(private val context: Context) {

    companion object {
        private val SERVER = stringPreferencesKey("server")
        private val USERNAME = stringPreferencesKey("username")
        private val PASSWORD = stringPreferencesKey("password")
        private val PROTOCOL = stringPreferencesKey("protocol")
        private val PORT = stringPreferencesKey("port")
        private val SERVER_FOLDER = stringPreferencesKey("server_folder")
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

    suspend fun clearSettings() {
        context.dataStore.edit { it.clear() }
    }
}

data class CameraSettings(
    val server: String,
    val username: String,
    val password: String,
    val protocol: String,  // "ftp" или "sftp"
    val port: Int = 21,
    val serverFolder: String = "/uploads/"
)