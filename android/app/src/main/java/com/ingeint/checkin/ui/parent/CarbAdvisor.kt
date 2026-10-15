package com.ingeint.checkin.ui.parent

import java.time.Instant
import java.time.ZoneId
import kotlin.math.ceil

enum class MealSlot { BREAKFAST, LUNCH, SNACK, DINNER, NIGHT }

enum class GlucoseStatus { LOW, IN_RANGE, HIGH, NO_RECENT }

data class CarbRange(val minGrams: Int, val maxGrams: Int, val medianGrams: Int, val basedOn: Int)

data class SlotStats(val slot: MealSlot, val evaluated: Int, val inRange: Int, val range: CarbRange?)

data class AdvisorResult(
    val targetSlot: MealSlot,
    val status: GlucoseStatus,
    val lastGlucose: Int?,
    val todayMean: Int?,
    val todayInRangePct: Double?,
    val suggestion: CarbRange?,
    val slots: List<SlotStats>,
    val mealsLogged: Int,
    val minMealsNeeded: Int,
)

/**
 * Sugerencia de carbohidratos (pedido 2026-10-15). NO es una fórmula médica: solo
 * reutiliza lo que ya funcionó. Una comida "funcionó" si la primera lectura entre 1 y 4 h
 * después (sin otra comida en medio) quedó dentro del rango objetivo. Por franja horaria
 * se toma el rango intercuartil (P25–P75) de los gramos de esas comidas, y solo si hay
 * [MIN_MEALS] o más. Con la última lectura alta se muestra la mitad baja (P25–mediana);
 * con la última lectura baja no se da ningún número (tratar la baja es indicación médica).
 */
object CarbAdvisor {
    const val MIN_MEALS = 5
    private const val AFTER_MIN_MINUTES = 60
    private const val AFTER_MAX_MINUTES = 240
    private const val RECENT_READING_MS = 3 * 3600_000L

    fun slotOf(hour: Int): MealSlot =
        when (hour) {
            in 5..10 -> MealSlot.BREAKFAST
            in 11..14 -> MealSlot.LUNCH
            in 15..18 -> MealSlot.SNACK
            in 19..22 -> MealSlot.DINNER
            else -> MealSlot.NIGHT
        }

    fun compute(events: List<StatEvent>, zone: ZoneId, low: Int, high: Int, nowMillis: Long): AdvisorResult {
        val sorted = events.sortedBy { it.atMillis }
        val outcomes = mutableListOf<Triple<MealSlot, Int, Boolean>>()
        for (i in sorted.indices) {
            val meal = sorted[i]
            val grams = meal.carbs
            if (meal.type != "meal" || grams == null) continue
            val slot = slotOf(Instant.ofEpochMilli(meal.atMillis).atZone(zone).hour)
            for (j in i + 1 until sorted.size) {
                val next = sorted[j]
                val minutes = (next.atMillis - meal.atMillis) / 60_000
                if (minutes > AFTER_MAX_MINUTES || next.type == "meal") break
                val g = next.glucose
                if (g != null && minutes >= AFTER_MIN_MINUTES) {
                    outcomes += Triple(slot, grams, g in low..high)
                    break
                }
            }
        }

        val slots =
            MealSlot.values().map { slot ->
                val evaluated = outcomes.filter { it.first == slot }
                val good = evaluated.filter { it.third }.map { it.second }.sorted()
                SlotStats(slot, evaluated.size, good.size, if (good.size >= MIN_MEALS) range(good) else null)
            }

        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val todayReadings =
            sorted.filter { it.glucose != null && Instant.ofEpochMilli(it.atMillis).atZone(zone).toLocalDate() == today }
        val last = sorted.lastOrNull { it.glucose != null && it.atMillis <= nowMillis }
        val lastGlucose = last?.takeIf { nowMillis - it.atMillis <= RECENT_READING_MS }?.glucose
        val status =
            when {
                lastGlucose == null -> GlucoseStatus.NO_RECENT
                lastGlucose < low -> GlucoseStatus.LOW
                lastGlucose > high -> GlucoseStatus.HIGH
                else -> GlucoseStatus.IN_RANGE
            }
        val target = slotOf(Instant.ofEpochMilli(nowMillis).atZone(zone).hour)
        val base = slots.first { it.slot == target }.range
        val suggestion =
            when {
                status == GlucoseStatus.LOW || base == null -> null
                status == GlucoseStatus.HIGH -> base.copy(maxGrams = maxOf(base.minGrams, base.medianGrams))
                else -> base
            }

        return AdvisorResult(
            targetSlot = target,
            status = status,
            lastGlucose = lastGlucose,
            todayMean = todayReadings.mapNotNull { it.glucose }.takeIf { it.isNotEmpty() }?.average()?.let { Math.round(it).toInt() },
            todayInRangePct =
                todayReadings.mapNotNull { it.glucose }.takeIf { it.isNotEmpty() }?.let { r ->
                    r.count { it in low..high } * 100.0 / r.size
                },
            suggestion = suggestion,
            slots = slots,
            mealsLogged = sorted.count { it.type == "meal" },
            minMealsNeeded = MIN_MEALS,
        )
    }

    private fun range(sortedGrams: List<Int>): CarbRange {
        fun pct(p: Double) = sortedGrams[ceil(p * sortedGrams.size).toInt().coerceIn(1, sortedGrams.size) - 1]
        return CarbRange(pct(0.25), pct(0.75), pct(0.5), sortedGrams.size)
    }
}
