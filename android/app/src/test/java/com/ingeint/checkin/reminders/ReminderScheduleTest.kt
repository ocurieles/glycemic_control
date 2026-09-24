package com.ingeint.checkin.reminders

import com.ingeint.checkin.data.model.ReminderSettings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Corre TODOS los vectores de docs/schedule-vectors.json (docs/03 §2.5), el mismo
 * archivo que usan los tests del backend (CLAUDE.md regla 7). Se lee como recurso de
 * classpath: `sourceSets["test"].resources.srcDir("../../docs")` en app/build.gradle.kts.
 */
class ReminderScheduleTest {
    private val vectors: JSONObject =
        JSONObject(
            checkNotNull(javaClass.classLoader?.getResourceAsStream("schedule-vectors.json")) {
                "No se encontró schedule-vectors.json en el classpath de test"
            }.bufferedReader().readText(),
        )
    private val baseSettings: JSONObject = vectors.getJSONObject("baseSettings")

    private fun settingsWith(overrides: JSONObject?): ReminderSettings {
        val merged = JSONObject(baseSettings.toString())
        overrides?.keys()?.forEach { key -> merged.put(key, overrides.get(key)) }
        return ReminderSettings(
            enabled = merged.optBoolean("enabled", true),
            intervalMinutes = merged.getInt("intervalMinutes"),
            days = merged.getJSONArray("days").let { arr -> List(arr.length()) { arr.getInt(it) } },
            startTime = merged.getString("startTime"),
            endTime = merged.getString("endTime"),
            escalationMinutes = merged.getInt("escalationMinutes"),
            nudgeMinutes = merged.getInt("nudgeMinutes"),
            timezone = merged.getString("timezone"),
        )
    }

    /** Interpreta un ISO local ingenuo ("2026-09-21T07:04:00") como hora de pared en `timezone`. */
    private fun loc(iso: String, timezone: String): Long =
        LocalDateTime.parse(iso).atZone(ZoneId.of(timezone)).toInstant().toEpochMilli()

    private fun slotAt(date: String, hhmm: String, settings: ReminderSettings): ReminderSlot =
        ReminderSlot(date, hhmm, loc("${date}T$hhmm:00", settings.timezone))

    private fun checkinsFrom(array: JSONArray, timezone: String): List<RealtimeCheckin> =
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            RealtimeCheckin(loc(o.getString("clientAt"), timezone), loc(o.getString("createdAt"), timezone))
        }

    @Test
    fun nextSlotVectors() {
        val array = vectors.getJSONArray("nextSlot")
        for (i in 0 until array.length()) {
            val v = array.getJSONObject(i)
            val settings = settingsWith(v.optJSONObject("settings"))
            val now = loc(v.getString("now"), settings.timezone)
            val result = ReminderSchedule.nextSlot(now, settings)
            val expected = if (v.isNull("expected")) null else loc(v.getString("expected"), settings.timezone)
            assertEquals(v.getString("id"), expected, result?.epochMillis)
        }
    }

    @Test
    fun assignVectors() {
        val array = vectors.getJSONArray("assign")
        for (i in 0 until array.length()) {
            val v = array.getJSONObject(i)
            val settings = settingsWith(v.optJSONObject("settings"))
            val c = loc(v.getString("clientAt"), settings.timezone)
            val slot = ReminderSchedule.assign(c, settings)
            val expectedSlot = if (v.isNull("expectedSlot")) null else v.getString("expectedSlot")
            assertEquals(v.getString("id"), expectedSlot, slot?.hhmm)

            if (v.has("expectedStatus") && slot != null) {
                val now = c + 60_000L // "now = clientAt + 1 min"
                val result = ReminderSchedule.statusOf(slot, now, listOf(RealtimeCheckin(c, c)), settings)
                assertEquals(v.getString("id") + " status", v.getString("expectedStatus"), result.status.toContractName())
            }
        }
    }

    @Test
    fun slotStatusVectors() {
        val array = vectors.getJSONArray("slotStatus")
        for (i in 0 until array.length()) {
            val v = array.getJSONObject(i)
            val settings = settingsWith(null)
            val slot = slotAt(v.getString("date"), v.getString("slot"), settings)
            val now = loc(v.getString("now"), settings.timezone)
            val checkins = checkinsFrom(v.getJSONArray("checkins"), settings.timezone)
            val result = ReminderSchedule.statusOf(slot, now, checkins, settings)
            assertEquals(v.getString("id"), v.getString("expectedStatus"), result.status.toContractName())
            if (v.has("expectedSyncedLate")) {
                assertEquals(v.getString("id") + " syncedLate", v.getBoolean("expectedSyncedLate"), result.syncedLate)
            }
        }
    }

    @Test
    fun recomputeVectors() {
        val array = vectors.getJSONArray("recompute")
        for (i in 0 until array.length()) {
            val v = array.getJSONObject(i)
            val settings = settingsWith(null)
            val date = v.getString("date")
            val steps = v.getJSONArray("steps")
            for (s in 0 until steps.length()) {
                val step = steps.getJSONObject(s)
                val now = loc(step.getString("now"), settings.timezone)
                val checkins = checkinsFrom(step.getJSONArray("checkins"), settings.timezone)
                val expect = step.getJSONObject("expect")
                expect.keys().forEach { hhmm ->
                    val slot = slotAt(date, hhmm, settings)
                    val result = ReminderSchedule.statusOf(slot, now, checkins, settings)
                    assertEquals("${v.getString("id")}.$s.$hhmm", expect.getString(hhmm), result.status.toContractName())
                }
                if (step.has("expectSyncedLate")) {
                    val slots = step.getJSONArray("expectSyncedLate")
                    for (j in 0 until slots.length()) {
                        val hhmm = slots.getString(j)
                        val slot = slotAt(date, hhmm, settings)
                        val result = ReminderSchedule.statusOf(slot, now, checkins, settings)
                        assertEquals("${v.getString("id")}.$s.$hhmm syncedLate", true, result.syncedLate)
                    }
                }
            }
        }
    }

    @Test
    fun slotsForVectors() {
        val array = vectors.getJSONArray("slotsFor")
        for (i in 0 until array.length()) {
            val v = array.getJSONObject(i)
            val settings = settingsWith(v.optJSONObject("settings"))
            val slots = ReminderSchedule.slotsFor(v.getString("date"), settings)
            if (v.has("expected")) {
                val expected = v.getJSONArray("expected").let { arr -> List(arr.length()) { arr.getString(it) } }
                assertEquals(v.getString("id"), expected, slots.map { it.hhmm })
            } else {
                assertEquals(v.getString("id") + " count", v.getInt("expectedCount"), slots.size)
                assertEquals(v.getString("id") + " first", v.getString("expectedFirst"), slots.first().hhmm)
                assertEquals(v.getString("id") + " last", v.getString("expectedLast"), slots.last().hhmm)
            }
        }
    }

    /** Los vectores usan los nombres en snake_case del contrato (docs/03 §2.4). */
    private fun SlotStatus.toContractName(): String =
        when (this) {
            SlotStatus.ON_TIME -> "on_time"
            SlotStatus.LATE -> "late"
            SlotStatus.UPCOMING -> "upcoming"
            SlotStatus.PENDING -> "pending"
            SlotStatus.MISSED -> "missed"
        }
}
