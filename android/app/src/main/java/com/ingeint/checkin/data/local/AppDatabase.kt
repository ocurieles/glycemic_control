package com.ingeint.checkin.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration

/** v1 → v2: agrega `doseUnits` para `OutboxEventType.INSULIN_DOSE` (docs/01, pedido 2026-09-24). */
private val MIGRATION_1_2 =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE outbox_events ADD COLUMN doseUnits REAL DEFAULT NULL")
        }
    }

/** v2 → v3: agrega `carbsGrams` para `OutboxEventType.MEAL` (pedido 2026-10-15). */
private val MIGRATION_2_3 =
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE outbox_events ADD COLUMN carbsGrams INTEGER DEFAULT NULL")
        }
    }

@Database(entities = [OutboxEvent::class], version = 3, exportSchema = false)
@TypeConverters(OutboxConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun outboxDao(): OutboxDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "checkin.db")
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
    }
}
