package com.ingeint.checkin.ui.parent

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Un `checkin` o `insulin_dose` con su lectura de glicemia (si la hubo). */
data class StatEvent(val type: String, val atMillis: Long, val glucose: Int?, val trend: Int?, val units: Double?)

data class GlucoseStats(
    val count: Int,
    val mean: Int,
    val median: Int,
    val min: Int,
    val max: Int,
    val sd: Int,
    val lowPct: Double,
    val inRangePct: Double,
    val highPct: Double,
    val veryHighPct: Double,
)

data class HourPoint(val hour: Int, val mean: Int, val count: Int)

data class DayInsulin(val date: LocalDate, val units: Double, val doses: Int, val avgGlucose: Int?)

/** Glicemia al aplicar la dosis vs. la revisión siguiente (sin otra dosis en medio). */
data class DoseResponse(val pairs: Int, val avgBefore: Int, val avgAfter: Int, val avgDelta: Int, val avgMinutes: Int)

data class StatsResult(
    val periodDays: Int,
    val firstDay: LocalDate,
    val lastDay: LocalDate,
    val glucose: GlucoseStats?,
    val hourly: List<HourPoint>,
    val days: List<DayInsulin>,
    val totalUnits: Double,
    val avgDailyUnits: Double,
    val dosesPerDay: Double,
    val totalDoses: Int,
    val response: DoseResponse?,
    val lowThreshold: Int,
    val highThreshold: Int,
)

/**
 * Estadísticas del padre (pedido 2026-10-15). Solo hay lecturas puntuales (al revisarse y
 * al registrar una dosis), no una curva continua del sensor: sirve para promedio, tiempo
 * en rango y patrones por hora, no para picos entre lecturas.
 */
object StatsCalculator {
    private const val VERY_HIGH = 250
    private const val RESPONSE_MIN_MINUTES = 20
    private const val RESPONSE_MAX_MINUTES = 300

    fun compute(
        events: List<StatEvent>,
        periodDays: Int,
        zone: ZoneId,
        low: Int,
        high: Int,
        nowMillis: Long,
    ): StatsResult {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val firstDay = today.minusDays((periodDays - 1).toLong())
        val startMillis = firstDay.atStartOfDay(zone).toInstant().toEpochMilli()
        val inPeriod = events.filter { it.atMillis >= startMillis }.sortedBy { it.atMillis }
        fun dateOf(e: StatEvent) = Instant.ofEpochMilli(e.atMillis).atZone(zone).toLocalDate()

        val readings = inPeriod.mapNotNull { it.glucose }
        val glucose = if (readings.isEmpty()) null else glucoseStats(readings, low, high)

        val hourly =
            inPeriod
                .filter { it.glucose != null }
                .groupBy { Instant.ofEpochMilli(it.atMillis).atZone(zone).hour }
                .map { (h, list) -> HourPoint(h, list.mapNotNull { it.glucose }.average().roundToInt(), list.size) }
                .sortedBy { it.hour }

        val doses = inPeriod.filter { it.type == "insulin_dose" && it.units != null }
        val days =
            (0 until periodDays).map { i ->
                val date = firstDay.plusDays(i.toLong())
                val dayDoses = doses.filter { dateOf(it) == date }
                val dayReadings = inPeriod.filter { dateOf(it) == date }.mapNotNull { it.glucose }
                DayInsulin(
                    date,
                    dayDoses.sumOf { it.units ?: 0.0 },
                    dayDoses.size,
                    if (dayReadings.isEmpty()) null else dayReadings.average().roundToInt(),
                )
            }

        val totalUnits = doses.sumOf { it.units ?: 0.0 }
        val firstDoseDay = doses.minOfOrNull { dateOf(it) }
        val effectiveDays =
            if (firstDoseDay == null) 1 else ChronoUnit.DAYS.between(maxOf(firstDay, firstDoseDay), today).toInt() + 1

        return StatsResult(
            periodDays = periodDays,
            firstDay = firstDay,
            lastDay = today,
            glucose = glucose,
            hourly = hourly,
            days = days,
            totalUnits = totalUnits,
            avgDailyUnits = totalUnits / effectiveDays,
            dosesPerDay = doses.size.toDouble() / effectiveDays,
            totalDoses = doses.size,
            response = doseResponse(inPeriod),
            lowThreshold = low,
            highThreshold = high,
        )
    }

    private fun glucoseStats(readings: List<Int>, low: Int, high: Int): GlucoseStats {
        val sorted = readings.sorted()
        val n = sorted.size
        val mean = readings.average()
        val median = if (n % 2 == 1) sorted[n / 2].toDouble() else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
        val sd = sqrt(readings.sumOf { (it - mean) * (it - mean) } / n)
        fun pct(count: Int) = count * 100.0 / n
        return GlucoseStats(
            count = n,
            mean = mean.roundToInt(),
            median = median.roundToInt(),
            min = sorted.first(),
            max = sorted.last(),
            sd = sd.roundToInt(),
            lowPct = pct(readings.count { it < low }),
            inRangePct = pct(readings.count { it in low..high }),
            highPct = pct(readings.count { it > high && it <= VERY_HIGH }),
            veryHighPct = pct(readings.count { it > high && it > VERY_HIGH }),
        )
    }

    private fun doseResponse(sorted: List<StatEvent>): DoseResponse? {
        val before = mutableListOf<Int>()
        val after = mutableListOf<Int>()
        val minutes = mutableListOf<Long>()
        for (i in sorted.indices) {
            val dose = sorted[i]
            val g0 = dose.glucose
            if (dose.type != "insulin_dose" || g0 == null) continue
            for (j in i + 1 until sorted.size) {
                val next = sorted[j]
                val diff = (next.atMillis - dose.atMillis) / 60_000
                if (diff > RESPONSE_MAX_MINUTES || next.type == "insulin_dose") break
                val g1 = next.glucose
                if (next.type == "checkin" && g1 != null && diff >= RESPONSE_MIN_MINUTES) {
                    before += g0
                    after += g1
                    minutes += diff
                    break
                }
            }
        }
        if (before.isEmpty()) return null
        return DoseResponse(
            pairs = before.size,
            avgBefore = before.average().roundToInt(),
            avgAfter = after.average().roundToInt(),
            avgDelta = (after.average() - before.average()).roundToInt(),
            avgMinutes = minutes.average().roundToInt(),
        )
    }
}
