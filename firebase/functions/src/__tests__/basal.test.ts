import { DateTime } from "luxon";
import { describe, expect, it } from "vitest";
import { basalReminderDue } from "../basal";

const tz = "America/Caracas";
const plan = { enabled: true, units: 20, time: "21:00" };
const at = (iso: string) => DateTime.fromISO(iso, { zone: tz }).toMillis();

describe("basalReminderDue", () => {
  it("no avisa antes de la hora prevista más el margen", () => {
    expect(basalReminderDue(plan, at("2026-10-15T21:20:00"), tz, [], undefined)).toBe(false);
  });
  it("avisa pasado el margen si no se registró", () => {
    expect(basalReminderDue(plan, at("2026-10-15T21:31:00"), tz, [], undefined)).toBe(true);
  });
  it("no avisa si ya se registró hoy ni si ya se avisó hoy", () => {
    expect(basalReminderDue(plan, at("2026-10-15T22:00:00"), tz, ["2026-10-15"], undefined)).toBe(false);
    expect(basalReminderDue(plan, at("2026-10-15T22:00:00"), tz, [], "2026-10-15")).toBe(false);
  });
  it("un registro de ayer no cuenta para hoy", () => {
    expect(basalReminderDue(plan, at("2026-10-15T22:00:00"), tz, ["2026-10-14"], "2026-10-14")).toBe(true);
  });
  it("no avisa si el plan está apagado o no existe", () => {
    expect(basalReminderDue({ ...plan, enabled: false }, at("2026-10-15T23:00:00"), tz, [], undefined)).toBe(false);
    expect(basalReminderDue(undefined, at("2026-10-15T23:00:00"), tz, [], undefined)).toBe(false);
  });
});
