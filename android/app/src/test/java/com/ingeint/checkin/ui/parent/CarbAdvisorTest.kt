package com.ingeint.checkin.ui.parent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class CarbAdvisorTest {
    private val zone = ZoneId.of("America/Caracas")

    private fun at(day: Int, hour: Int, minute: Int = 0) =
        ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun meal(day: Int, hour: Int, grams: Int) = StatEvent("meal", at(day, hour), null, null, null, grams)

    private fun reading(day: Int, hour: Int, minute: Int, g: Int) = StatEvent("checkin", at(day, hour, minute), g, 3, null)

    private fun lunches(vararg grams: Int, outcome: Int = 120): List<StatEvent> =
        grams.flatMapIndexed { i, g -> listOf(meal(1 + i, 12, g), reading(1 + i, 14, 0, outcome)) }

    private val noonNow = at(15, 12, 30)

    @Test
    fun `con 5 comidas que funcionaron da el rango intercuartil`() {
        val events = lunches(30, 40, 45, 50, 60) + reading(15, 12, 0, 120)
        val r = CarbAdvisor.compute(events, zone, 70, 180, noonNow)
        assertEquals(MealSlot.LUNCH, r.targetSlot)
        assertEquals(GlucoseStatus.IN_RANGE, r.status)
        val s = r.suggestion
        assertNotNull(s)
        assertEquals(40, s!!.minGrams)
        assertEquals(50, s.maxGrams)
        assertEquals(5, s.basedOn)
    }

    @Test
    fun `con menos de 5 comidas no da numero`() {
        val events = lunches(30, 40, 45, 50) + reading(15, 12, 0, 120)
        assertNull(CarbAdvisor.compute(events, zone, 70, 180, noonNow).suggestion)
    }

    @Test
    fun `las comidas que terminaron altas no cuentan`() {
        val events = lunches(30, 40, 45, 50, 60, outcome = 250) + reading(15, 12, 0, 120)
        val r = CarbAdvisor.compute(events, zone, 70, 180, noonNow)
        assertNull(r.suggestion)
        assertEquals(5, r.slots.first { it.slot == MealSlot.LUNCH }.evaluated)
        assertEquals(0, r.slots.first { it.slot == MealSlot.LUNCH }.inRange)
    }

    @Test
    fun `con la ultima lectura alta muestra la mitad baja`() {
        val events = lunches(30, 40, 45, 50, 60) + reading(15, 12, 0, 260)
        val s = CarbAdvisor.compute(events, zone, 70, 180, noonNow).suggestion!!
        assertEquals(40, s.minGrams)
        assertEquals(45, s.maxGrams)
    }

    @Test
    fun `con la ultima lectura baja nunca da numero`() {
        val events = lunches(30, 40, 45, 50, 60) + reading(15, 12, 0, 55)
        val r = CarbAdvisor.compute(events, zone, 70, 180, noonNow)
        assertEquals(GlucoseStatus.LOW, r.status)
        assertNull(r.suggestion)
    }

    @Test
    fun `una lectura de hace mas de 3 horas no cuenta como reciente`() {
        val events = lunches(30, 40, 45, 50, 60) + reading(15, 8, 0, 55)
        val r = CarbAdvisor.compute(events, zone, 70, 180, noonNow)
        assertEquals(GlucoseStatus.NO_RECENT, r.status)
        assertNotNull(r.suggestion)
    }

    @Test
    fun `otra comida en medio invalida el resultado`() {
        val events = listOf(meal(1, 12, 40), meal(1, 13, 20), reading(1, 14, 30, 120))
        val r = CarbAdvisor.compute(events, zone, 70, 180, noonNow)
        assertEquals(1, r.slots.first { it.slot == MealSlot.LUNCH }.evaluated)
    }
}
