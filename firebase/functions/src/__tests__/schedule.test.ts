import { readFileSync } from "node:fs";
import { join } from "node:path";
import { DateTime } from "luxon";
import { describe, expect, it } from "vitest";
import { assign, nextSlot, ReminderSettings, RealtimeCheckin, Slot, slotsFor, statusOf } from "../schedule";

/**
 * Corre TODOS los vectores de docs/schedule-vectors.json (docs/03 §2.5).
 * Es el mismo archivo que usan los tests de Android (CLAUDE.md regla 7).
 */

interface VectorsFile {
  baseSettings: ReminderSettings;
  nextSlot: Array<{ id: string; now: string; settings?: Partial<ReminderSettings>; expected: string | null }>;
  assign: Array<{
    id: string;
    clientAt: string;
    settings?: Partial<ReminderSettings>;
    expectedSlot: string | null;
    expectedStatus?: string;
  }>;
  slotStatus: Array<{
    id: string;
    slot: string;
    date: string;
    now: string;
    checkins: Array<{ clientAt: string; createdAt: string }>;
    expectedStatus: string;
    expectedSyncedLate?: boolean;
  }>;
  recompute: Array<{
    id: string;
    date: string;
    steps: Array<{
      now: string;
      checkins: Array<{ clientAt: string; createdAt: string }>;
      expect: Record<string, string>;
      expectSyncedLate?: string[];
    }>;
  }>;
  slotsFor: Array<{
    id: string;
    date: string;
    settings?: Partial<ReminderSettings>;
    expected?: string[];
    expectedCount?: number;
    expectedFirst?: string;
    expectedLast?: string;
  }>;
}

const vectorsPath = join(__dirname, "..", "..", "..", "..", "docs", "schedule-vectors.json");
const vectors: VectorsFile = JSON.parse(readFileSync(vectorsPath, "utf-8"));

function withSettings(overrides?: Partial<ReminderSettings>): ReminderSettings {
  return { ...vectors.baseSettings, ...(overrides ?? {}) };
}

/** Interpreta un ISO local ingenuo ("2026-09-21T07:04:00") como hora de pared en `timezone`. */
function loc(iso: string, timezone = vectors.baseSettings.timezone): number {
  const dt = DateTime.fromISO(iso, { zone: timezone });
  if (!dt.isValid) throw new Error(`ISO inválido en vector: ${iso} (${dt.invalidReason})`);
  return dt.toMillis();
}

function toRealtimeCheckins(
  checkins: Array<{ clientAt: string; createdAt: string }>,
  timezone: string,
): RealtimeCheckin[] {
  return checkins.map((c) => ({ realAt: loc(c.clientAt, timezone), createdAt: loc(c.createdAt, timezone) }));
}

function slotAt(date: string, hhmm: string, settings: ReminderSettings): Slot {
  return { dateKey: date, hhmm, epochMs: loc(`${date}T${hhmm}:00`, settings.timezone) };
}

describe("schedule.ts — vectores de docs/schedule-vectors.json", () => {
  it.each(vectors.nextSlot)("nextSlot $id", (v) => {
    const settings = withSettings(v.settings);
    const now = loc(v.now, settings.timezone);
    const result = nextSlot(now, settings);
    const expected = v.expected ? loc(v.expected, settings.timezone) : null;
    expect(result?.epochMs ?? null).toBe(expected);
  });

  it.each(vectors.assign)("assign $id", (v) => {
    const settings = withSettings(v.settings);
    const c = loc(v.clientAt, settings.timezone);
    const slot = assign(c, settings);
    expect(slot?.hhmm ?? null).toBe(v.expectedSlot);

    if (v.expectedStatus && slot) {
      const now = c + 60_000; // "now = clientAt + 1 min" (ver descripción del JSON)
      const { status } = statusOf(slot, now, [{ realAt: c, createdAt: c }], settings);
      expect(status).toBe(v.expectedStatus);
    }
  });

  it.each(vectors.slotStatus)("slotStatus $id", (v) => {
    const settings = withSettings();
    const slot = slotAt(v.date, v.slot, settings);
    const now = loc(v.now, settings.timezone);
    const checkins = toRealtimeCheckins(v.checkins, settings.timezone);
    const { status, syncedLate } = statusOf(slot, now, checkins, settings);
    expect(status).toBe(v.expectedStatus);
    if (v.expectedSyncedLate !== undefined) {
      expect(syncedLate).toBe(v.expectedSyncedLate);
    }
  });

  it.each(vectors.recompute)("recompute $id", (v) => {
    const settings = withSettings();
    for (const [i, step] of v.steps.entries()) {
      const now = loc(step.now, settings.timezone);
      const checkins = toRealtimeCheckins(step.checkins, settings.timezone);
      for (const [hhmm, expectedStatus] of Object.entries(step.expect)) {
        const slot = slotAt(v.date, hhmm, settings);
        const { status } = statusOf(slot, now, checkins, settings);
        expect(status, `${v.id} paso ${i} slot ${hhmm}`).toBe(expectedStatus);
      }
      for (const hhmm of step.expectSyncedLate ?? []) {
        const slot = slotAt(v.date, hhmm, settings);
        const { syncedLate } = statusOf(slot, now, checkins, settings);
        expect(syncedLate, `${v.id} paso ${i} slot ${hhmm} syncedLate`).toBe(true);
      }
    }
  });

  it.each(vectors.slotsFor)("slotsFor $id", (v) => {
    const settings = withSettings(v.settings);
    const slots = slotsFor(v.date, settings);
    if (v.expected) {
      expect(slots.map((s) => s.hhmm)).toEqual(v.expected);
    } else {
      expect(slots.length).toBe(v.expectedCount);
      expect(slots[0]?.hhmm).toBe(v.expectedFirst);
      expect(slots[slots.length - 1]?.hhmm).toBe(v.expectedLast);
    }
  });
});
