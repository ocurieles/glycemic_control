package com.ingeint.checkin.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ingeint.checkin.data.model.ReminderSettings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Outbox real contra una base Room en memoria (docs/01 H2 "debounce"; docs/06 "omisión
 * de slots ya cubiertos"). No usa fakes: es la misma lógica que corre en el teléfono.
 */
@RunWith(AndroidJUnit4::class)
class OutboxRepositoryInstrumentedTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: OutboxRepository

    private val settings =
        ReminderSettings(
            intervalMinutes = 10,
            days = listOf(1, 2, 3, 4, 5),
            startTime = "07:00",
            endTime = "13:00",
            escalationMinutes = 5,
            timezone = "America/Caracas",
        )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = OutboxRepository(db.outboxDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun dosToquesEnMenosDe60sRegistranUnaSolaRevision() =
        runBlocking {
            val first = repository.record(OutboxEventType.CHECKIN, clientAtMillis = 1_000_000L, clockOffsetMs = null, source = "app")
            val second = repository.record(OutboxEventType.CHECKIN, clientAtMillis = 1_030_000L, clockOffsetMs = null, source = "app")

            assertEquals(first.id, second.id)
            assertEquals(1, repository.pendingBatch().size)
        }

    @Test
    fun toquesConMasDe60sDeDiferenciaRegistranRevisionesDistintas() =
        runBlocking {
            val first = repository.record(OutboxEventType.CHECKIN, clientAtMillis = 1_000_000L, clockOffsetMs = null, source = "app")
            val second = repository.record(OutboxEventType.CHECKIN, clientAtMillis = 1_070_000L, clockOffsetMs = null, source = "app")

            assertNotEquals(first.id, second.id)
            assertEquals(2, repository.pendingBatch().size)
        }

    @Test
    fun sosNuncaSeDebounceaConCheckin() =
        runBlocking {
            repository.record(OutboxEventType.CHECKIN, clientAtMillis = 1_000_000L, clockOffsetMs = null, source = "app")
            repository.record(OutboxEventType.SOS, clientAtMillis = 1_000_500L, clockOffsetMs = null, source = "app")

            assertEquals(2, repository.pendingBatch().size)
        }

    @Test
    fun hasCheckinForSlot_detectaUnaRevisionAsignadaAlSlot() =
        runBlocking {
            // 2026-09-21 (lunes) 07:04 -> asignado al slot 07:00 (docs/schedule-vectors.json V7).
            val clientAt = localMillis("2026-09-21T07:04:00", settings.timezone)
            repository.record(OutboxEventType.CHECKIN, clientAtMillis = clientAt, clockOffsetMs = null, source = "app")

            assertTrue(repository.hasCheckinForSlot("2026-09-21", "07:00", settings))
            assertFalse(repository.hasCheckinForSlot("2026-09-21", "07:10", settings))
        }

    @Test
    fun hasCheckinForSlot_sinRevisionesDevuelveFalse() =
        runBlocking {
            assertFalse(repository.hasCheckinForSlot("2026-09-21", "07:00", settings))
        }

    private fun localMillis(iso: String, timezone: String): Long =
        java.time.LocalDateTime.parse(iso).atZone(java.time.ZoneId.of(timezone)).toInstant().toEpochMilli()
}
