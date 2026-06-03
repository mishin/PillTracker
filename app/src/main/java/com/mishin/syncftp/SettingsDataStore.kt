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
        private val LOCAL_PATH = stringPreferencesKey("local_path")
        private val PROTOCOL = stringPreferencesKey("protocol")
        private val PORT = stringPreferencesKey("port")
    }
    
    val settingsFlow: Flow<Settings> = context.dataStore.data.map { preferences ->
        Settings(
            server = preferences[SERVER] ?: "",
            username = preferences[USERNAME] ?: "",
            password = preferences[PASSWORD] ?: "",
            localPath = preferences[LOCAL_PATH] ?: "",
            protocol = preferences[PROTOCOL] ?: "ftp",
            port = preferences[PORT]?.toIntOrNull() ?: if (preferences[PROTOCOL] == "sftp") 22 else 21
        )
    }
    
    suspend fun saveSettings(settings: Settings) {
        context.dataStore.edit { preferences ->
            preferences[SERVER] = settings.server
            preferences[USERNAME] = settings.username
            preferences[PASSWORD] = settings.password
            preferences[LOCAL_PATH] = settings.localPath
            preferences[PROTOCOL] = settings.protocol
            preferences[PORT] = settings.port.toString()
        }
    }
    
    suspend fun clearSettings() {
        context.dataStore.edit { it.clear() }
    }
}

data class Settings(
    val server: String,
    val username: String,
    val password: String,
    val localPath: String,
    val protocol: String,
    val port: Int = if (protocol == "sftp") 22 else 21
)