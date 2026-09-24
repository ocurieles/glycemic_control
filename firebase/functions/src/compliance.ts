import { assign, ReminderSettings, slotsFor, SlotStatus, statusOf } from "./schedule";

/**
 * Cumplimiento del día — CONTRATO puro (docs/03 §3 "days/{fecha}"), no importa Firebase
 * (CLAUDE.md convención TypeScript). `recomputeDay` (I/O) vive en `recompute.ts`.
 */

export interface DayCheckin {
  eventId: string;
  realAt: number; // epoch ms
  createdAt: number; // epoch ms
}

export interface DaySlotDoc {
  status: SlotStatus;
  eventId?: string;
  clientAt?: number; // epoch ms (documentado como "clientAt" en docs/03, es realAt en la práctica)
  syncedLate?: boolean;
}

export interface DayCounts {
  expected: number;
  onTime: number;
  late: number;
  missed: number;
  pending: number;
  upcoming: number;
  syncedLate: number;
}

export interface ComputedDay {
  date: string;
  settings: {
    intervalMinutes: number;
    startTime: string;
    endTime: string;
    escalationMinutes: number;
  };
  slots: Record<string, DaySlotDoc>;
  counts: DayCounts;
}

/** `computeDay(settings, checkins[], now) → DayDoc` (docs/04). Pura: no hace I/O. */
export function computeDay(
  settings: ReminderSettings,
  checkins: DayCheckin[],
  nowMillis: number,
  dateKey: string,
): ComputedDay {
  const slots = slotsFor(dateKey, settings);
  const slotDocs: Record<string, DaySlotDoc> = {};
  const counts: DayCounts = {
    expected: slots.length,
    onTime: 0,
    late: 0,
    missed: 0,
    pending: 0,
    upcoming: 0,
    syncedLate: 0,
  };

  for (const slot of slots) {
    const realtimeCheckins = checkins.map((c) => ({ realAt: c.realAt, createdAt: c.createdAt }));
    const { status, syncedLate } = statusOf(slot, nowMillis, realtimeCheckins, settings);

    const doc: DaySlotDoc = { status };
    if (status === "on_time" || status === "late") {
      const assigned = checkins
        .filter((c) => c.realAt <= nowMillis && assign(c.realAt, settings)?.hhmm === slot.hhmm)
        .sort((a, b) => a.realAt - b.realAt)[0];
      if (assigned) {
        doc.eventId = assigned.eventId;
        doc.clientAt = assigned.realAt;
      }
      doc.syncedLate = syncedLate;
      if (syncedLate) counts.syncedLate++;
    }

    switch (status) {
      case "on_time":
        counts.onTime++;
        break;
      case "late":
        counts.late++;
        break;
      case "missed":
        counts.missed++;
        break;
      case "pending":
        counts.pending++;
        break;
      case "upcoming":
        counts.upcoming++;
        break;
    }
    slotDocs[slot.hhmm] = doc;
  }

  return {
    date: dateKey,
    settings: {
      intervalMinutes: settings.intervalMinutes,
      startTime: settings.startTime,
      endTime: settings.endTime,
      escalationMinutes: settings.escalationMinutes,
    },
    slots: slotDocs,
    counts,
  };
}
