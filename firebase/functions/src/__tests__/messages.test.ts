import { DateTime } from "luxon";
import { describe, expect, it } from "vitest";
import { formatCheckinMessage, formatInsulinDoseMessage, formatSosMessage, formatTime } from "../messages";

const tz = "America/Caracas";
function at(hhmm: string): number {
  const [h, m] = hhmm.split(":").map(Number);
  return DateTime.fromObject({ year: 2026, month: 9, day: 21, hour: h, minute: m }, { zone: tz }).toMillis();
}

describe("messages.ts — textos exactos de docs/04", () => {
  it("formatea la hora en es-VE", () => {
    expect(formatTime(at("10:40"))).toBe("10:40 a. m.");
    expect(formatTime(at("22:05"))).toBe("10:05 p. m.");
  });

  it("checkin normal sin glucosa", () => {
    const { title, body } = formatCheckinMessage({ childName: "Cesar", realAtMs: at("10:40"), syncedLate: false });
    expect(title).toBe("Cesar se revisó ✓");
    expect(body).toBe("10:40 a. m.");
  });

  it("checkin con glucosa normal", () => {
    const { title, body } = formatCheckinMessage({
      childName: "Cesar",
      realAtMs: at("10:40"),
      syncedLate: false,
      glucose: { valueMgDl: 128, trend: 4, level: "normal" },
    });
    expect(title).toBe("Cesar se revisó ✓");
    expect(body).toBe("10:40 a. m. · 128 mg/dL ↗");
  });

  it("checkin con glucosa baja", () => {
    const { title, body } = formatCheckinMessage({
      childName: "Cesar",
      realAtMs: at("10:40"),
      syncedLate: false,
      glucose: { valueMgDl: 62, trend: 2, level: "low" },
    });
    expect(title).toBe("⚠️ Cesar se revisó — BAJA");
    expect(body).toBe("10:40 a. m. · 62 mg/dL ↘");
  });

  it("checkin sin glucosa por lectura vieja (glucoseError: stale) lo dice explícitamente", () => {
    const { title, body } = formatCheckinMessage({
      childName: "Cesar",
      realAtMs: at("10:40"),
      syncedLate: false,
      glucoseError: "stale",
    });
    expect(title).toBe("Cesar se revisó ✓");
    expect(body).toBe("10:40 a. m. · Sin lectura reciente del sensor");
  });

  it("checkin sin glucosa por otro motivo (p. ej. not_configured) no lo anuncia", () => {
    const { body } = formatCheckinMessage({
      childName: "Cesar",
      realAtMs: at("10:40"),
      syncedLate: false,
      glucoseError: "not_configured",
    });
    expect(body).toBe("10:40 a. m.");
  });

  it("registro de dosis de insulina", () => {
    const { title, body } = formatInsulinDoseMessage("Cesar", 1.5, at("10:40"));
    expect(title).toBe("Cesar registró una dosis");
    expect(body).toBe("1.5 U · 10:40 a. m.");
  });

  it("checkin_late (sincronizado tarde)", () => {
    const { title, body } = formatCheckinMessage({
      childName: "Cesar",
      realAtMs: at("10:40"),
      createdAtMs: at("11:05"),
      syncedLate: true,
    });
    expect(title).toBe("Cesar se revisó (sin conexión)");
    expect(body).toBe("A las 10:40 a. m. · sincronizado 11:05 a. m.");
  });

  it("sos", () => {
    const { title, body } = formatSosMessage({
      childName: "Cesar",
      realAtMs: at("10:42"),
      syncedLate: false,
      smsSent: false,
    });
    expect(title).toBe("🆘 Cesar necesita ayuda");
    expect(body).toBe("10:42 a. m. · Toca para ver ubicación");
  });
});
