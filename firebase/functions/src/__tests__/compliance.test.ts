import { readFileSync } from "node:fs";
import { join } from "node:path";
import { DateTime } from "luxon";
import { describe, expect, it } from "vitest";
import { computeDay, DayCheckin } from "../compliance";
import { ReminderSettings } from "../schedule";

/**
 * `computeDay` agrega `statusOf()` sobre todos los slots de un día. Reusa los
 * vectores V13/V14/V18/V19 de docs/schedule-vectors.json (docs/08 F6 "Listo cuando").
 */

interface VectorsFile {
  baseSettings: ReminderSettings;
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
}

const vectorsPath = join(__dirname, "..", "..", "..", "..", "docs", "schedule-vectors.json");
const vectors: VectorsFile = JSON.parse(readFileSync(vectorsPath, "utf-8"));
const settings = vectors.baseSettings;

function loc(iso: string): number {
  return DateTime.fromISO(iso, { zone: settings.timezone }).toMillis();
}

describe("computeDay — vectores V19 (recompute) de docs/schedule-vectors.json", () => {
  it("un slot missed pasa a on_time con syncedLate al llegar la revisión atrasada", () => {
    const v19 = vectors.recompute.find((v) => v.id === "V19");
    if (!v19) throw new Error("No se encontró el vector V19");

    // Paso 1: sin revisiones, 07:00 y 07:10 deben quedar missed.
    const step1 = v19.steps[0];
    const day1 = computeDay(settings, [], loc(step1.now), v19.date);
    expect(day1.slots["07:00"].status).toBe("missed");
    expect(day1.slots["07:10"].status).toBe("missed");
    expect(day1.counts.missed).toBeGreaterThanOrEqual(2);

    // Paso 2: llega una revisión atrasada asignada a 07:00 -> on_time + syncedLate.
    const step2 = v19.steps[1];
    const checkins: DayCheckin[] = step2.checkins.map((c, i) => ({
      eventId: `e${i}`,
      realAt: loc(c.clientAt),
      createdAt: loc(c.createdAt),
    }));
    const day2 = computeDay(settings, checkins, loc(step2.now), v19.date);
    expect(day2.slots["07:00"].status).toBe("on_time");
    expect(day2.slots["07:00"].syncedLate).toBe(true);
    expect(day2.slots["07:00"].eventId).toBe("e0");
    expect(day2.counts.syncedLate).toBe(1);
  });

  it("expected coincide con la cantidad total de slots del día (36, docs/03 §2.1)", () => {
    const day = computeDay(settings, [], loc("2026-09-21T06:00:00"), "2026-09-21");
    expect(day.counts.expected).toBe(36);
    expect(Object.keys(day.slots)).toHaveLength(36);
  });

  it("un día deshabilitado no tiene slots", () => {
    const disabled: ReminderSettings = { ...settings, enabled: false };
    const day = computeDay(disabled, [], loc("2026-09-21T06:00:00"), "2026-09-21");
    expect(day.counts.expected).toBe(0);
    expect(Object.keys(day.slots)).toHaveLength(0);
  });

  it("los conteos suman el total de slots esperados", () => {
    const day = computeDay(settings, [], loc("2026-09-21T07:04:00"), "2026-09-21");
    const { expected, onTime, late, missed, pending, upcoming } = day.counts;
    expect(onTime + late + missed + pending + upcoming).toBe(expected);
  });
});
