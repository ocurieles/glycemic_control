package com.ingeint.checkin.ui.parent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class StatsCalculatorTest {
    private val zone = ZoneId.of("America/Caracas")
    private val now = ZonedDateTime.of(2026, 10, 15, 20, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun at(day: Int, hour: Int, minute: Int = 0) =
        ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun checkin(day: Int, hour: Int, glucose: Int?, minute: Int = 0) =
        StatEvent("checkin", at(day, hour, minute), glucose, 3, null)

    private fun dose(day: Int, hour: Int, units: Double, glucose: Int?, minute: Int = 0) =
        StatEvent("insulin_dose", at(day, hour, minute), glucose, 3, units)

    @Test
    fun `tiempo en rango y estadisticas basicas`() {
        val events = listOf(60, 100, 150, 200, 300).mapIndexed { i, g -> checkin(14, 8 + i, g) }
        val s = StatsCalculator.compute(events, 7, zone, 70, 180, now).glucose!!
        assertEquals(5, s.count)
        assertEquals(162, s.mean)
        assertEquals(150, s.median)
        assertEquals(60, s.min)
        assertEquals(300, s.max)
        assertEquals(20.0, s.lowPct, 0.01)
        assertEquals(40.0, s.inRangePct, 0.01)
        assertEquals(20.0, s.highPct, 0.01)
        assertEquals(20.0, s.veryHighPct, 0.01)
    }

    @Test
    fun `sin lecturas no hay estadisticas de glicemia`() {
        val r = StatsCalculator.compute(listOf(checkin(14, 8, null)), 7, zone, 70, 180, now)
        assertNull(r.glucose)
        assertNull(r.response)
    }

    @Test
    fun `el periodo excluye eventos anteriores`() {
        val events = listOf(checkin(1, 8, 500), checkin(14, 8, 100))
        val s = StatsCalculator.compute(events, 7, zone, 70, 180, now).glucose!!
        assertEquals(1, s.count)
        assertEquals(100, s.mean)
    }

    @Test
    fun `insulina por dia y promedio diario`() {
        val events = listOf(dose(13, 8, 2.0, 120), dose(13, 20, 1.0, 140), dose(14, 8, 3.0, 160))
        val r = StatsCalculator.compute(events, 7, zone, 70, 180, now)
        assertEquals(6.0, r.totalUnits, 0.001)
        assertEquals(3, r.totalDoses)
        assertEquals(3.0, r.days.first { it.date.dayOfMonth == 13 }.units, 0.001)
        assertEquals(2, r.days.first { it.date.dayOfMonth == 13 }.doses)
        assertEquals(0.0, r.days.first { it.date.dayOfMonth == 12 }.units, 0.001)
        // Primera dosis el 13: el promedio se calcula sobre 3 días (13, 14, 15), no sobre 7.
        assertEquals(2.0, r.avgDailyUnits, 0.001)
    }

    @Test
    fun `respuesta a la dosis usa la revision siguiente`() {
        val events = listOf(dose(14, 8, 2.0, 250), checkin(14, 10, 150))
        val r = StatsCalculator.compute(events, 7, zone, 70, 180, now).response
        assertNotNull(r)
        assertEquals(1, r!!.pairs)
        assertEquals(250, r.avgBefore)
        assertEquals(150, r.avgAfter)
        assertEquals(-100, r.avgDelta)
        assertEquals(120, r.avgMinutes)
    }

    @Test
    fun `no empareja si hay otra dosis en medio o pasan mas de 5 horas`() {
        val otraDosis = listOf(dose(14, 8, 2.0, 250), dose(14, 9, 1.0, 200), checkin(14, 10, 150))
        assertEquals(1, StatsCalculator.compute(otraDosis, 7, zone, 70, 180, now).response?.pairs)
        val muyTarde = listOf(dose(14, 8, 2.0, 250), checkin(14, 15, 150))
        assertNull(StatsCalculator.compute(muyTarde, 7, zone, 70, 180, now).response)
    }

    @Test
    fun `promedio por hora`() {
        val events = listOf(checkin(13, 8, 100), checkin(14, 8, 200), checkin(14, 9, 120))
        val h = StatsCalculator.compute(events, 7, zone, 70, 180, now).hourly
        assertEquals(150, h.first { it.hour == 8 }.mean)
        assertEquals(2, h.first { it.hour == 8 }.count)
        assertEquals(120, h.first { it.hour == 9 }.mean)
    }
}
