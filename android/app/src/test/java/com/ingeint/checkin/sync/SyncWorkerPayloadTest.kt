package com.ingeint.checkin.sync

import com.google.firebase.Timestamp
import com.ingeint.checkin.data.local.OutboxEvent
import com.ingeint.checkin.data.local.OutboxEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Payload exacto que sube el `SyncWorker` (docs/03 §3 "events"). No usa Firestore real:
 * `buildPayload` es una función pura sobre [OutboxEvent].
 */
class SyncWorkerPayloadTest {
    private fun checkinEvent(clockOffsetMs: Long? = null) =
        OutboxEvent(
            id = "e1",
            type = OutboxEventType.CHECKIN,
            clientAt = 1_700_000_000_000L,
            clockOffsetMs = clockOffsetMs,
            source = "app",
            recordedAt = 1_700_000_000_000L,
        )

    @Test
    fun `checkin sin clockOffset no incluye la clave`() {
        val payload = buildPayload(checkinEvent(), "uid1")
        assertEquals("checkin", payload["type"])
        assertEquals("uid1", payload["createdBy"])
        assertEquals(Timestamp(java.util.Date(1_700_000_000_000L)), payload["clientAt"])
        assertEquals("app", payload["source"])
        assertFalse(payload.containsKey("clockOffsetMs"))
        assertFalse(payload.containsKey("location"))
        assertFalse(payload.containsKey("text"))
    }

    @Test
    fun `checkin con clockOffset lo incluye como entero`() {
        val payload = buildPayload(checkinEvent(clockOffsetMs = 1500L), "uid1")
        assertEquals(1500, payload["clockOffsetMs"])
    }

    @Test
    fun `parent_message incluye text y replyTo`() {
        val event =
            OutboxEvent(
                id = "e2",
                type = OutboxEventType.PARENT_MESSAGE,
                text = "Recuerda la lectura",
                clientAt = 1_700_000_000_000L,
                source = "app",
                recordedAt = 1_700_000_000_000L,
            )
        val payload = buildPayload(event, "parentUid")
        assertEquals("parent_message", payload["type"])
        assertEquals("Recuerda la lectura", payload["text"])
        assertFalse(payload.containsKey("smsSent"))
    }

    @Test
    fun `sos incluye location cuando hay lat y lng`() {
        val event =
            OutboxEvent(
                id = "e3",
                type = OutboxEventType.SOS,
                clientAt = 1_700_000_000_000L,
                source = "app",
                lat = 10.5,
                lng = -66.9,
                accuracyM = 12f,
                smsSent = true,
                recordedAt = 1_700_000_000_000L,
            )
        val payload = buildPayload(event, "childUid")
        assertEquals("sos", payload["type"])
        assertEquals(true, payload["smsSent"])
        @Suppress("UNCHECKED_CAST")
        val location = payload["location"] as Map<String, Any?>
        assertEquals(10.5, location["lat"])
        assertEquals(-66.9, location["lng"])
        assertEquals(12f, location["accuracyM"])
    }

    @Test
    fun `sos sin ubicacion no incluye location`() {
        val event =
            OutboxEvent(
                id = "e4",
                type = OutboxEventType.SOS,
                clientAt = 1_700_000_000_000L,
                source = "app",
                smsSent = false,
                recordedAt = 1_700_000_000_000L,
            )
        val payload = buildPayload(event, "childUid")
        assertFalse(payload.containsKey("location"))
        assertEquals(false, payload["smsSent"])
    }

    @Test
    fun `insulin_dose incluye doseUnits`() {
        val event =
            OutboxEvent(
                id = "e5",
                type = OutboxEventType.INSULIN_DOSE,
                doseUnits = 1.5,
                clientAt = 1_700_000_000_000L,
                source = "app",
                recordedAt = 1_700_000_000_000L,
            )
        val payload = buildPayload(event, "childUid")
        assertEquals("insulin_dose", payload["type"])
        assertEquals(1.5, payload["doseUnits"])
    }

    @Test
    fun `createdAt siempre es un FieldValue serverTimestamp`() {
        val payload = buildPayload(checkinEvent(), "uid1")
        val createdAt = payload["createdAt"]
        assertEquals(com.google.firebase.firestore.FieldValue.serverTimestamp()::class, createdAt?.let { it::class })
    }
}
