import { DateTime } from "luxon";

/**
 * Textos para los padres (docs/04 "Payloads" — es la fuente de verdad de los textos
 * exactos). Funciones puras, sin dependencias de Firebase.
 */

const DEFAULT_TIMEZONE = "America/Caracas";

/** "10:40 a. m." en es-VE. */
export function formatTime(epochMs: number, timezone = DEFAULT_TIMEZONE): string {
  return DateTime.fromMillis(epochMs, { zone: timezone })
    .setLocale("es-VE")
    .toFormat("h:mm a")
    .replace("AM", "a. m.")
    .replace("PM", "p. m.");
}

export const TREND_ARROWS: Record<number, string> = { 1: "↓", 2: "↘", 3: "→", 4: "↗", 5: "↑" };

export interface GlucoseInfo {
  valueMgDl: number;
  trend?: number;
  level: "low" | "normal" | "high";
}

interface CheckinMessageInput {
  childName: string;
  realAtMs: number;
  syncedLate: boolean;
  createdAtMs?: number;
  timezone?: string;
  glucose?: GlucoseInfo;
}

export function formatCheckinMessage(input: CheckinMessageInput): { title: string; body: string } {
  const { childName, realAtMs, syncedLate, createdAtMs, timezone, glucose } = input;
  const time = formatTime(realAtMs, timezone);
  const valueSuffix = glucose ? ` · ${glucose.valueMgDl} mg/dL ${TREND_ARROWS[glucose.trend ?? 3] ?? ""}` : "";

  if (syncedLate) {
    const syncedAt = createdAtMs !== undefined ? formatTime(createdAtMs, timezone) : "";
    return {
      title: `${childName} se revisó (sin conexión)`,
      body: `A las ${time}${syncedAt ? ` · sincronizado ${syncedAt}` : ""}`,
    };
  }

  if (glucose?.level === "low") {
    return { title: `⚠️ ${childName} se revisó — BAJA`, body: `${time}${valueSuffix}` };
  }
  if (glucose?.level === "high") {
    return { title: `⚠️ ${childName} se revisó — ALTA`, body: `${time}${valueSuffix}` };
  }
  return { title: `${childName} se revisó ✓`, body: `${time}${valueSuffix}` };
}

interface SosMessageInput {
  childName: string;
  realAtMs: number;
  syncedLate: boolean;
  smsSent: boolean;
  timezone?: string;
}

export function formatSosMessage(input: SosMessageInput): { title: string; body: string } {
  const { childName, realAtMs, timezone } = input;
  const time = formatTime(realAtMs, timezone);
  return { title: `🆘 ${childName} necesita ayuda`, body: `${time} · Toca para ver ubicación` };
}

export function formatMissedMessage(childName: string, slotHhmm: string): { title: string; body: string } {
  return {
    title: `${childName} no ha confirmado`,
    body: `Recordatorio de las ${slotHhmm} (puede estar sin conexión)`,
  };
}

/** Ráfaga de `checkin_late` agrupada (docs/07 "Qué ven los padres"). */
export function formatGroupedLateMessage(
  childName: string,
  count: number,
  fromMs: number,
  toMs: number,
  timezone = DEFAULT_TIMEZONE,
): { title: string; body: string } {
  const from = formatTime(fromMs, timezone);
  const to = formatTime(toMs, timezone);
  return {
    title: `${childName} sincronizó ${count} revisiones`,
    body: `Hechas sin conexión (${from}–${to})`,
  };
}

/** Resumen del día al terminar el horario (docs/04 "checkMissedSlots"). */
export function formatDaySummaryMessage(counts: {
  expected: number;
  onTime: number;
  late: number;
  missed: number;
}): { title: string; body: string } {
  return {
    title: "Resumen del día",
    body: `Hoy: ${counts.onTime}/${counts.expected} a tiempo, ${counts.late} tarde, ${counts.missed} sin respuesta`,
  };
}
