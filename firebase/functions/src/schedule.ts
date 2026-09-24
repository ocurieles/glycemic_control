import { DateTime } from "luxon";
import { addDaysToDateKey, dateKeyOf, hhmmToMinutes, minutesToHhmm, wallTimeToEpochMs } from "./time";

/**
 * Reglas de slots — CONTRATO compartido con Android (docs/03 §2).
 * No importa Firebase: función pura, probada contra docs/schedule-vectors.json
 * (ver src/__tests__/schedule.test.ts). Si cambias algo aquí, cambia también
 * docs/03, docs/schedule-vectors.json, docs/reference/schedule_reference.py
 * y ReminderSchedule.kt en el mismo commit (CLAUDE.md regla 7).
 */

export interface ReminderSettings {
  enabled: boolean;
  intervalMinutes: number;
  /** ISO: 1 = lunes … 7 = domingo. */
  days: number[];
  startTime: string; // "HH:mm"
  endTime: string; // "HH:mm"
  escalationMinutes: number;
  nudgeMinutes: number;
  timezone: string; // IANA
  lowThreshold: number;
  highThreshold: number;
  smsFallbackEnabled: boolean;
  smsNumbers: string[];
  childPhone?: string | null;
}

export interface Slot {
  dateKey: string; // "yyyy-MM-dd"
  hhmm: string; // "HH:mm"
  epochMs: number;
}

export type SlotStatus = "on_time" | "late" | "upcoming" | "pending" | "missed";

/** Una revisión ya reducida a su hora real (docs/03: `c = realAt = min(clientAt, createdAt)`). */
export interface RealtimeCheckin {
  realAt: number; // epoch ms
  createdAt: number; // epoch ms
}

const MAX_NEXT_SLOT_LOOKAHEAD_DAYS = 8;
const SYNCED_LATE_THRESHOLD_MS = 120_000; // docs/03: createdAt − realAt > 120 s

/** Slots de un día local `dateKey` según `settings` (docs/03 §2.1). Fin exclusivo. */
export function slotsFor(dateKey: string, settings: ReminderSettings): Slot[] {
  if (!settings.enabled) return [];
  if (!settings.days.includes(isoWeekdayOfDateKey(dateKey))) return [];

  const startMin = hhmmToMinutes(settings.startTime);
  const endMin = hhmmToMinutes(settings.endTime);
  const slots: Slot[] = [];
  for (let t = startMin; t < endMin; t += settings.intervalMinutes) {
    const hhmm = minutesToHhmm(t);
    slots.push({ dateKey, hhmm, epochMs: wallTimeToEpochMs(dateKey, hhmm, settings.timezone) });
  }
  return slots;
}

/** Día ISO (1 = lunes … 7 = domingo) de una fecha calendario, sin depender de ninguna zona horaria. */
function isoWeekdayOfDateKey(dateKey: string): number {
  const [year, month, day] = dateKey.split("-").map(Number);
  return DateTime.fromObject({ year, month, day }, { zone: "utc" }).weekday;
}

/** Primer slot con `t > now` (estricto), buscando desde hoy hasta 7 días adelante (docs/03 §2.2). */
export function nextSlot(nowEpochMs: number, settings: ReminderSettings): Slot | null {
  let dateKey = dateKeyOf(nowEpochMs, settings.timezone);
  for (let k = 0; k < MAX_NEXT_SLOT_LOOKAHEAD_DAYS; k++) {
    for (const slot of slotsFor(dateKey, settings)) {
      if (slot.epochMs > nowEpochMs) return slot;
    }
    dateKey = addDaysToDateKey(dateKey, 1);
  }
  return null;
}

/**
 * Asigna una revisión con hora real `realAtEpochMs` al slot más cercano del mismo
 * día local, si `|c − t| ≤ interval/2`. Empate: gana el slot anterior. Si ninguno
 * cumple, la revisión es "extra" (docs/03 §2.3).
 */
export function assign(realAtEpochMs: number, settings: ReminderSettings): Slot | null {
  const dateKey = dateKeyOf(realAtEpochMs, settings.timezone);
  const halfIntervalMs = (settings.intervalMinutes * 60_000) / 2;
  let best: Slot | null = null;
  let bestDiff = Infinity;
  for (const slot of slotsFor(dateKey, settings)) {
    const diff = Math.abs(realAtEpochMs - slot.epochMs);
    if (diff <= halfIntervalMs && diff < bestDiff) {
      best = slot;
      bestDiff = diff;
    }
    // En empate exacto (diff === bestDiff), NO reemplazamos: el slot anterior
    // (encontrado primero, porque slotsFor está ordenado ascendente) gana.
  }
  return best;
}

/**
 * Estado de un slot evaluado en `now`, según docs/03 §2.4. Solo cuentan las
 * revisiones asignadas a este slot con `realAt ≤ now`.
 */
export function statusOf(
  slot: Slot,
  nowEpochMs: number,
  checkins: RealtimeCheckin[],
  settings: ReminderSettings,
): { status: SlotStatus; syncedLate: boolean } {
  const escalationMs = settings.escalationMinutes * 60_000;

  const assigned = checkins
    .filter((c) => c.realAt <= nowEpochMs)
    .filter((c) => {
      const s = assign(c.realAt, settings);
      return s !== null && s.dateKey === slot.dateKey && s.hhmm === slot.hhmm;
    })
    .sort((a, b) => a.realAt - b.realAt);

  if (assigned.length > 0) {
    const first = assigned[0];
    const syncedLate = first.createdAt - first.realAt > SYNCED_LATE_THRESHOLD_MS;
    const status: SlotStatus = first.realAt <= slot.epochMs + escalationMs ? "on_time" : "late";
    return { status, syncedLate };
  }

  if (slot.epochMs > nowEpochMs) return { status: "upcoming", syncedLate: false };
  if (nowEpochMs < slot.epochMs + escalationMs) return { status: "pending", syncedLate: false };
  return { status: "missed", syncedLate: false };
}
