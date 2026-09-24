package com.ingeint.checkin.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: OutboxEvent)

    @Update
    suspend fun update(event: OutboxEvent)

    @Query("SELECT * FROM outbox_events WHERE id = :id")
    suspend fun get(id: String): OutboxEvent?

    @Query("SELECT * FROM outbox_events WHERE status = :status ORDER BY clientAt ASC LIMIT :limit")
    suspend fun pending(status: OutboxStatus = OutboxStatus.PENDING, limit: Int = 20): List<OutboxEvent>

    /** Para el debounce de `checkin` (docs/01 H2: doble toque en <60 s = una sola revisión). */
    @Query("SELECT * FROM outbox_events WHERE type = :type ORDER BY clientAt DESC LIMIT 1")
    suspend fun lastOfType(type: OutboxEventType): OutboxEvent?

    @Query("SELECT * FROM outbox_events ORDER BY clientAt DESC")
    fun observeAll(): Flow<List<OutboxEvent>>

    /** Para la omisión de slots ya cubiertos en ReminderReceiver (docs/06, vía `assign()`). */
    @Query("SELECT * FROM outbox_events WHERE type = :type")
    suspend fun allOfType(type: OutboxEventType): List<OutboxEvent>

    @Query("SELECT * FROM outbox_events WHERE status = 'PENDING' ORDER BY clientAt DESC")
    fun observePending(): Flow<List<OutboxEvent>>

    /** Retención (docs/03 §5): los `SENT` se purgan a los 30 días; los `PENDING`, nunca. */
    @Query("DELETE FROM outbox_events WHERE status = 'SENT' AND sentAt < :beforeMillis")
    suspend fun deleteSentBefore(beforeMillis: Long)
}
