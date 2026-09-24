package com.ingeint.checkin.reminders

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Helpers de zona horaria (docs/03 §2), espejo de `time.ts` del backend. Usa `java.time`
 * de verdad (con DST) en vez de aritmética ingenua, igual que el backend con `luxon`
 * (CLAUDE.md regla 7: la lógica debe coincidir, aunque la implementación de cada
 * lenguaje use su propia librería de fechas).
 */
internal object ReminderTime {
    /** "HH:mm" -> minutos desde medianoche. */
    fun hhmmToMinutes(hhmm: String): Int {
        val (h, m) = hhmm.split(":").map(String::toInt)
        return h * 60 + m
    }

    /** minutos desde medianoche -> "HH:mm". */
    fun minutesToHhmm(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return "%02d:%02d".format(h, m)
    }

    /** Época (ms) de `dateKey` (yyyy-MM-dd) + `hhmm` en `timezone`. */
    fun wallTimeToEpochMillis(dateKey: String, hhmm: String, timezone: String): Long {
        val date = LocalDate.parse(dateKey)
        val time = LocalTime.parse(hhmm)
        return ZonedDateTime.of(date, time, ZoneId.of(timezone)).toInstant().toEpochMilli()
    }

    /** "yyyy-MM-dd" del instante `epochMillis` en `timezone`. */
    fun dateKeyOf(epochMillis: Long, timezone: String): String =
        java.time.Instant.ofEpochMilli(epochMillis).atZone(ZoneId.of(timezone)).toLocalDate().toString()

    /** `dateKey` `days` días después de `dateKey` (calendario, no depende de zona). */
    fun addDaysToDateKey(dateKey: String, days: Long): String = LocalDate.parse(dateKey).plusDays(days).toString()

    /** Día ISO (1 = lunes … 7 = domingo) de una fecha calendario. `DayOfWeek.value` ya es ISO. */
    fun isoWeekdayOfDateKey(dateKey: String): Int = LocalDate.parse(dateKey).dayOfWeek.value
}
