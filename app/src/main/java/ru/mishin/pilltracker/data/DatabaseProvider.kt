package ru.mishin.pilltracker.data

import android.content.Context
import androidx.room.Room

/**
 * Единая точка создания AppDatabase.
 * Использование singleton-паттерна предотвращает утечки и гарантирует
 * применение миграций во всех точках (Activity, Worker, Receiver).
 */
object DatabaseProvider {
    @Volatile
    private var instance: AppDatabase? = null

    fun get(context: Context): AppDatabase =
        instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "meds_db"
            )
                .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
                .build()
                .also { instance = it }
        }
}