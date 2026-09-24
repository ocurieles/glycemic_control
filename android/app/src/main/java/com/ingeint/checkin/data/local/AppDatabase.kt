package com.ingeint.checkin.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [OutboxEvent::class], version = 1, exportSchema = false)
@TypeConverters(OutboxConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun outboxDao(): OutboxDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "checkin.db")
                    .build()
                    .also { instance = it }
            }
    }
}
