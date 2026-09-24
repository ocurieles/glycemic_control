package com.ingeint.checkin.data.local

import androidx.room.TypeConverter

class OutboxConverters {
    @TypeConverter
    fun statusToString(status: OutboxStatus): String = status.name

    @TypeConverter
    fun statusFromString(value: String): OutboxStatus = OutboxStatus.valueOf(value)

    @TypeConverter
    fun typeToString(type: OutboxEventType): String = type.name

    @TypeConverter
    fun typeFromString(value: String): OutboxEventType = OutboxEventType.valueOf(value)
}
