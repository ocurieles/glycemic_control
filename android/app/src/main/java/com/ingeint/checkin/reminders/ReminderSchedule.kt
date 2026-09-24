package com.ingeint.checkin.reminders

import com.ingeint.checkin.data.model.ReminderSettings
import kotlin.math.abs

/**
 * Reglas de slots — CONTRATO compartido con el backend (docs/03 §2). Espejo exacto de
 * `firebase/functions/src/schedule.ts`; ambos se prueban contra
 * `docs/schedule-vectors.json` (CLAUDE.md regla 7). Si cambias algo aquí, cambia también
 * docs/03, el JSON, `docs/reference/schedule_reference.py` y `schedule.ts` en el mismo commit.
 */

data class ReminderSlot(val dateKey: String, val hhmm: String, val epochMillis: Long)

enum class SlotStatus { ON_TIME, LATE, UPCOMING, PENDING, MISSED }

/** Una revisión ya reducida a su hora real (docs/03: `c = realAt = min(clientAt, createdAt)`). */
data class RealtimeCheckin(val realAtMillis: Long, val createdAtMillis: Long)

data class SlotStatusResult(val status: SlotStatus, val syncedLate: Boolean)

private const val MAX_NEXT_SLOT_LOOKAHEAD_DAYS = 8L
private const val SYNCED_LATE_THRESHOLD_MS = 120_000L // docs/03: createdAt − realAt > 120 s

object ReminderSchedule {
    /** Slots de un día local `dateKey` según `settings` (docs/03 §2.1). Fin exclusivo. */
    fun slotsFor(dateKey: String, settings: ReminderSettings): List<ReminderSlot> {
        if (!settings.enabled) return emptyList()
        if (ReminderTime.isoWeekdayOfDateKey(dateKey) !in settings.days) return emptyList()

        val startMin = ReminderTime.hhmmToMinutes(settings.startTime)
        val endMin = ReminderTime.hhmmToMinutes(settings.endTime)
        val slots = mutableListOf<ReminderSlot>()
        var t = startMin
        while (t < endMin) {
            val hhmm = ReminderTime.minutesToHhmm(t)
            slots.add(ReminderSlot(dateKey, hhmm, ReminderTime.wallTimeToEpochMillis(dateKey, hhmm, settings.timezone)))
            t += settings.intervalMinutes
        }
        return slots
    }

    /** Primer slot con `t > now` (estricto), buscando desde hoy hasta 7 días adelante (docs/03 §2.2). */
    fun nextSlot(nowMillis: Long, settings: ReminderSettings): ReminderSlot? {
        var dateKey = ReminderTime.dateKeyOf(nowMillis, settings.timezone)
        for (k in 0 until MAX_NEXT_SLOT_LOOKAHEAD_DAYS) {
            slotsFor(dateKey, settings).firstOrNull { it.epochMillis > nowMillis }?.let { return it }
            dateKey = ReminderTime.addDaysToDateKey(dateKey, 1)
        }
        return null
    }

    /**
     * Asigna una revisión con hora real `realAtMillis` al slot más cercano del mismo día
     * local, si `|c − t| ≤ interval/2`. Empate: gana el slot anterior. Si ninguno cumple,
     * la revisión es "extra" (docs/03 §2.3).
     */
    fun assign(realAtMillis: Long, settings: ReminderSettings): ReminderSlot? {
        val dateKey = ReminderTime.dateKeyOf(realAtMillis, settings.timezone)
        val halfIntervalMs = settings.intervalMinutes * 60_000L / 2
        var best: ReminderSlot? = null
        var bestDiff = Long.MAX_VALUE
        for (slot in slotsFor(dateKey, settings)) {
            val diff = abs(realAtMillis - slot.epochMillis)
            if (diff <= halfIntervalMs && diff < bestDiff) {
                best = slot
                bestDiff = diff
            }
            // Empate exacto (diff == bestDiff): NO se reemplaza, gana el slot anterior
            // (slotsFor está ordenado ascendente, así que "best" ya es el anterior).
        }
        return best
    }

    /**
     * Estado de un slot evaluado en `now`, según docs/03 §2.4. Solo cuentan las
     * revisiones asignadas a este slot con `realAt ≤ now`.
     */
    fun statusOf(
        slot: ReminderSlot,
        nowMillis: Long,
        checkins: List<RealtimeCheckin>,
        settings: ReminderSettings,
    ): SlotStatusResult {
        val escalationMs = settings.escalationMinutes * 60_000L

        val assigned = checkins
            .filter { it.realAtMillis <= nowMillis }
            .filter { c ->
                val s = assign(c.realAtMillis, settings)
                s != null && s.dateKey == slot.dateKey && s.hhmm == slot.hhmm
            }
            .sortedBy { it.realAtMillis }

        if (assigned.isNotEmpty()) {
            val first = assigned.first()
            val syncedLate = first.createdAtMillis - first.realAtMillis > SYNCED_LATE_THRESHOLD_MS
            val status = if (first.realAtMillis <= slot.epochMillis + escalationMs) SlotStatus.ON_TIME else SlotStatus.LATE
            return SlotStatusResult(status, syncedLate)
        }

        return when {
            slot.epochMillis > nowMillis -> SlotStatusResult(SlotStatus.UPCOMING, false)
            nowMillis < slot.epochMillis + escalationMs -> SlotStatusResult(SlotStatus.PENDING, false)
            else -> SlotStatusResult(SlotStatus.MISSED, false)
        }
    }
}
