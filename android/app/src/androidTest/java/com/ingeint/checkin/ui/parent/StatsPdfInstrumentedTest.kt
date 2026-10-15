package com.ingeint.checkin.ui.parent

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class StatsPdfInstrumentedTest {
    @Test
    fun generaUnPdfConEstadisticasDe30Dias() {
        val zone = ZoneId.of("America/Caracas")
        val now = ZonedDateTime.of(2026, 10, 15, 20, 0, 0, 0, zone).toInstant().toEpochMilli()
        val events = mutableListOf<StatEvent>()
        for (d in 0 until 30) {
            val base = now - d * 24L * 3600 * 1000
            events += StatEvent("checkin", base - 10 * 3600_000L, 90 + (d * 7) % 200, 3, null)
            events += StatEvent("insulin_dose", base - 6 * 3600_000L, 120 + (d * 11) % 150, 4, 1.0 + d % 3)
            events += StatEvent("checkin", base - 4 * 3600_000L, 110 + (d * 13) % 160, 3, null)
        }
        val stats = StatsCalculator.compute(events, 30, zone, 70, 180, now)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = StatsPdf.write(context, "Cesar", stats)
        assertTrue(file.exists())
        assertTrue(file.length() > 2_000)
        file.copyTo(java.io.File(context.getExternalFilesDir(null), "muestra.pdf"), overwrite = true)
    }
}
