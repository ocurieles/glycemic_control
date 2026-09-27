package com.ingeint.checkin.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Estados de un evento del outbox (docs/07 "Flujo de una revisión"). */
enum class OutboxStatus { PENDING, SENT, REJECTED }

/**
 * Tipos de evento (docs/03 §3): niño → `checkin`/`sos`/`insulin_dose`/`location_response`;
 * padre → `parent_message`/`sos_ack`/`location_request`.
 */
enum class OutboxEventType { CHECKIN, SOS, PARENT_MESSAGE, SOS_ACK, INSULIN_DOSE, LOCATION_REQUEST, LOCATION_RESPONSE }

/**
 * `outbox_events` (docs/03 §5): fuente de verdad local. Una revisión existe desde que
 * se escribe aquí, antes de cualquier red (CLAUDE.md regla 4). Los `PENDING` nunca se
 * borran automáticamente; los `SENT` se purgan a los 30 días (docs/07).
 */
@Entity(
    tableName = "outbox_events",
    indices = [Index("status"), Index("clientAt")],
)
data class OutboxEvent(
    @PrimaryKey val id: String, // UUID, el mismo eventId que en Firestore
    val type: OutboxEventType,
    val text: String? = null,
    val replyTo: String? = null,
    val clockOffsetMs: Long? = null,
    val clientAt: Long, // epoch ms, ya corregido con clockOffsetMs
    val source: String, // "app" | "notification"
    val lat: Double? = null,
    val lng: Double? = null,
    val accuracyM: Float? = null,
    val smsSent: Boolean = false,
    /** Solo para `INSULIN_DOSE` (docs/01, docs/03): unidades, uno de 0.5/1/1.5/2/2.5/3. */
    val doseUnits: Double? = null,
    val status: OutboxStatus = OutboxStatus.PENDING,
    val attempts: Int = 0,
    val lastError: String? = null,
    val sentAt: Long? = null,
    /** Momento en que se creó la fila (para decidir si vibrar CONFIRM al sincronizar, docs/07). */
    val recordedAt: Long,
)
