import { DateTime } from "luxon";
import { describe, expect, it } from "vitest";
import { groupReadingsByDay } from "../readings";

const tz = "America/Caracas";
const at = (iso: string) => DateTime.fromISO(iso, { zone: tz }).toMillis();

describe("groupReadingsByDay", () => {
  it("agrupa por día local y deja la tendencia solo si existe", () => {
    const m = groupReadingsByDay(
      [
        { valueMgDl: 120, trend: 3, factoryTimestampMs: at("2026-10-15T23:50:00") },
        { valueMgDl: 130, factoryTimestampMs: at("2026-10-16T00:05:00") },
      ],
      tz,
    );
    expect([...m.keys()].sort()).toEqual(["2026-10-15", "2026-10-16"]);
    const first = Object.values(m.get("2026-10-15")!)[0];
    expect(first).toEqual({ v: 120, tr: 3 });
    expect(Object.values(m.get("2026-10-16")!)[0]).toEqual({ v: 130 });
  });

  it("una lectura repetida usa la misma clave (no se duplica)", () => {
    const t = at("2026-10-16T08:00:00");
    const m = groupReadingsByDay(
      [
        { valueMgDl: 100, factoryTimestampMs: t },
        { valueMgDl: 100, factoryTimestampMs: t + 400 },
      ],
      tz,
    );
    expect(Object.keys(m.get("2026-10-16")!).length).toBe(1);
  });

  it("ignora lecturas inválidas", () => {
    expect(groupReadingsByDay([{ valueMgDl: NaN, factoryTimestampMs: 1 }], tz).size).toBe(0);
  });
});
