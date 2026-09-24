package com.ingeint.checkin.data.local

import com.ingeint.checkin.data.model.ReminderSettings
import com.ingeint.checkin.reminders.ReminderSchedule
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import kotlin.math.abs

private const val CHECKIN_DEBOUNCE_MS = 60_000L
private const val SENT_RETENTION_MS = 30L * 24 * 3600 * 1000

/**
 * Outbox del rol activo (docs/03 §5, docs/07). `record()` es lo primero que pasa al
 * tocar "Ya me revisé" o "Ayuda": se escribe en Room antes de tocar la red
 * (CLAUDE.md regla 4). Existe en ambos roles (docs/07 "Padres sin conexión").
 */
class OutboxRepository(private val dao: OutboxDao) {
    /**
     * Registra un evento. Para `CHECKIN`, un toque a menos de 60 s del anterior
     * devuelve el mismo evento en vez de crear uno nuevo (docs/01 H2).
     */
    suspend fun record(
        type: OutboxEventType,
        clientAtMillis: Long,
        clockOffsetMs: Long?,
        source: String,
        text: String? = null,
        replyTo: String? = null,
        lat: Double? = null,
        lng: Double? = null,
        accuracyM: Float? = null,
        smsSent: Boolean = false,
    ): OutboxEvent {
        if (type == OutboxEventType.CHECKIN) {
            dao.lastOfType(type)?.let { last ->
                if (abs(clientAtMillis - last.clientAt) < CHECKIN_DEBOUNCE_MS) return last
            }
        }

        val event =
            OutboxEvent(
                id = UUID.randomUUID().toString(),
                type = type,
                text = text,
                replyTo = replyTo,
                clockOffsetMs = clockOffsetMs,
                clientAt = clientAtMillis,
                source = source,
                lat = lat,
                lng = lng,
                accuracyM = accuracyM,
                smsSent = smsSent,
                recordedAt = System.currentTimeMillis(),
            )
        dao.insert(event)
        return event
    }

    suspend fun markSmsSent(id: String) {
        dao.get(id)?.let { dao.update(it.copy(smsSent = true)) }
    }

    fun observeAll(): Flow<List<OutboxEvent>> = dao.observeAll()

    fun observePending(): Flow<List<OutboxEvent>> = dao.observePending()

    suspend fun pendingBatch(limit: Int = 20): List<OutboxEvent> = dao.pending(limit = limit)

    suspend fun markSent(event: OutboxEvent) {
        dao.update(event.copy(status = OutboxStatus.SENT, sentAt = System.currentTimeMillis()))
    }

    suspend fun markRejected(event: OutboxEvent, error: String) {
        dao.update(event.copy(status = OutboxStatus.REJECTED, lastError = error))
    }

    suspend fun markRetry(event: OutboxEvent, error: String?) {
        dao.update(event.copy(attempts = event.attempts + 1, lastError = error))
    }

    /** Se llama tras confirmar: ¿esta revisión la inició el usuario hace poco? (docs/07: vibrar CONFIRM). */
    fun wasRecordedRecently(event: OutboxEvent, withinMs: Long = 120_000L): Boolean =
        System.currentTimeMillis() - event.recordedAt <= withinMs

    /** Retención (docs/03 §5): `SENT` a los 30 días; `PENDING` nunca se borra. */
    suspend fun purgeOldSent() {
        dao.deleteSentBefore(System.currentTimeMillis() - SENT_RETENTION_MS)
    }

    /**
     * ¿Ya hay una revisión asignada a `dateKey`/`hhmm`? (docs/06: "se omite el recordatorio
     * si ya existe en el outbox local una revisión asignada a t").
     */
    suspend fun hasCheckinForSlot(dateKey: String, hhmm: String, settings: ReminderSettings): Boolean =
        dao.allOfType(OutboxEventType.CHECKIN).any { event ->
            val slot = ReminderSchedule.assign(event.clientAt, settings)
            slot?.dateKey == dateKey && slot.hhmm == hhmm
        }
}
