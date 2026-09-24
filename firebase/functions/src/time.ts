import { DateTime } from "luxon";

/**
 * Helpers de zona horaria (docs/03 §2). Todo el sistema trabaja con instantes
 * UTC (epoch ms) puros y convierte a/desde hora local con la IANA timezone
 * de la familia usando luxon, que sí maneja DST correctamente (a diferencia
 * de docs/reference/schedule_reference.py, que usa datetimes ingenuos porque
 * America/Caracas no tiene DST).
 */

/** "HH:mm" -> minutos desde medianoche. */
export function hhmmToMinutes(hhmm: string): number {
  const [h, m] = hhmm.split(":").map(Number);
  return h * 60 + m;
}

/** minutos desde medianoche -> "HH:mm". */
export function minutesToHhmm(minutes: number): string {
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}`;
}

/** Époch ms correspondiente a `dateKey` (yyyy-MM-dd) + `hhmm` en `timezone`. */
export function wallTimeToEpochMs(dateKey: string, hhmm: string, timezone: string): number {
  const [year, month, day] = dateKey.split("-").map(Number);
  const [hour, minute] = hhmm.split(":").map(Number);
  const dt = DateTime.fromObject(
    { year, month, day, hour, minute, second: 0, millisecond: 0 },
    { zone: timezone },
  );
  if (!dt.isValid) {
    throw new Error(`Fecha/hora inválida: ${dateKey} ${hhmm} ${timezone} (${dt.invalidReason})`);
  }
  return dt.toMillis();
}

/** "yyyy-MM-dd" del instante `epochMs` en `timezone`. */
export function dateKeyOf(epochMs: number, timezone: string): string {
  return DateTime.fromMillis(epochMs, { zone: timezone }).toFormat("yyyy-MM-dd");
}

/** Día ISO (1 = lunes … 7 = domingo) del instante `epochMs` en `timezone`. */
export function isoWeekdayOf(epochMs: number, timezone: string): number {
  return DateTime.fromMillis(epochMs, { zone: timezone }).weekday;
}

/** `dateKey` `days` días después de `dateKey` (calendario, no epoch). */
export function addDaysToDateKey(dateKey: string, days: number): string {
  const [year, month, day] = dateKey.split("-").map(Number);
  return DateTime.fromObject({ year, month, day }, { zone: "utc" })
    .plus({ days })
    .toFormat("yyyy-MM-dd");
}
