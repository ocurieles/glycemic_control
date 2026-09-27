import { getApps, initializeApp } from "firebase-admin/app";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { DateTime } from "luxon";
import { describe, expect, it } from "vitest";
import { processFamily } from "../missed";
import { ReminderSettings } from "../schedule";

/**
 * `processFamily` (el cuerpo de `checkMissedSlots`, docs/04) contra el emulador de
 * Firestore real. Se llama directamente como función de TS, sin pasar por el
 * scheduler real (eso requeriría el emulador de Pub/Sub) — ver docs/08 F6
 * "Listo cuando": el aviso de "no ha confirmado" y el resumen se comprueban aquí.
 * Correr con: firebase emulators:exec --only firestore "npm --prefix functions run test:integration"
 */

const canRun = !!process.env.FIRESTORE_EMULATOR_HOST;
if (!canRun) {
  console.warn("missed.test.ts se saltea: necesita el emulador de Firestore (ver CLAUDE.md).");
}

const projectId = process.env.GCLOUD_PROJECT ?? "demo-checkin-rules";
if (!getApps().length) initializeApp({ projectId });
const db = getFirestore();

// Horario amplio y de intervalo corto para que, sin importar la hora real en que
// corra el test, siempre haya slots ya "missed" bastante antes de "ahora".
const settings: ReminderSettings = {
  enabled: true,
  intervalMinutes: 1,
  days: [1, 2, 3, 4, 5, 6, 7],
  startTime: "00:00",
  endTime: "23:59",
  escalationMinutes: 1,
  nudgeMinutes: 1,
  timezone: "America/Caracas",
  lowThreshold: 70,
  highThreshold: 180,
  smsFallbackEnabled: false,
  smsNumbers: [],
  childPhone: null,
};

describe.runIf(canRun)("processFamily (checkMissedSlots, integración con el emulador)", () => {
  it("marca slots viejos como missed, no los repite, y no se cae si no hay fcmToken", async () => {
    const familyId = `fam-missed-${Date.now()}`;
    await db.doc(`families/${familyId}`).set({
      childName: "Cesar",
      childUid: "child1",
      parents: { parent1: { name: "Mamá" } },
      settings,
      createdAt: Timestamp.now(),
    });

    const family = (await db.doc(`families/${familyId}`).get()).data()!;

    // Primera corrida: deben aparecer varios "missed" y quedar registrados en missedAlerted.
    await processFamily(familyId, family, settings);

    const todayKey = DateTime.now().setZone(settings.timezone).toFormat("yyyy-MM-dd");
    const daySnap1 = await db.doc(`families/${familyId}/days/${todayKey}`).get();
    expect(daySnap1.exists).toBe(true);
    const day1 = daySnap1.data()!;
    expect(day1.counts.missed).toBeGreaterThan(0);
    const missedAlertedAfterFirst = (day1.missedAlerted as string[]) ?? [];
    // Solo se alertan los "missed" de la última hora (docs/04: "t ≥ ahora − 60 min",
    // para no alertar lo muy viejo tras una caída del servicio); con un horario de
    // 00:00–23:59 e intervalo de 1 min, la mayoría de los "missed" del día son más
    // viejos que eso, así que missedAlerted es un subconjunto, nunca todos.
    expect(missedAlertedAfterFirst.length).toBeGreaterThan(0);
    expect(missedAlertedAfterFirst.length).toBeLessThanOrEqual(day1.counts.missed);

    // Segunda corrida inmediata: los mismos slots no deben volver a alertarse (docs/04:
    // "una sola vez por slot").
    await processFamily(familyId, family, settings);
    const daySnap2 = await db.doc(daySnap1.ref.path).get();
    const missedAlertedAfterSecond = (daySnap2.data()!.missedAlerted as string[]) ?? [];
    expect(missedAlertedAfterSecond.length).toBe(missedAlertedAfterFirst.length);
  });

  it("una revisión a tiempo no genera un slot missed para ese horario", async () => {
    const familyId = `fam-missed-ontime-${Date.now()}`;
    await db.doc(`families/${familyId}`).set({
      childName: "Cesar",
      childUid: "child1",
      parents: {},
      settings,
      createdAt: Timestamp.now(),
    });

    // Slot de "ahora mismo" (redondeado al minuto, como marca el intervalo de 1 min).
    const nowLocal = DateTime.now().setZone(settings.timezone).set({ second: 0, millisecond: 0 });
    const nowHhmm = nowLocal.toFormat("HH:mm");
    const nowDate = nowLocal.toJSDate();

    await db.doc(`families/${familyId}/events/e1`).set({
      type: "checkin",
      createdBy: "child1",
      realAt: Timestamp.fromDate(nowDate),
      createdAt: Timestamp.fromDate(nowDate),
      processedAt: Timestamp.now(),
    });

    const family = (await db.doc(`families/${familyId}`).get()).data()!;
    await processFamily(familyId, family, settings);

    const todayKey = nowLocal.toFormat("yyyy-MM-dd");
    const day = (await db.doc(`families/${familyId}/days/${todayKey}`).get()).data()!;
    expect(["on_time", "late"]).toContain(day.slots[nowHhmm]?.status);
  });

  it("recomputeDay (vía processFamily) preserva el campo insulin del día (bug real 2026-09-27)", async () => {
    const familyId = `fam-insulin-preserve-${Date.now()}`;
    await db.doc(`families/${familyId}`).set({
      childName: "Cesar",
      childUid: "child1",
      parents: {},
      settings,
      createdAt: Timestamp.now(),
    });

    const todayKey = DateTime.now().setZone(settings.timezone).toFormat("yyyy-MM-dd");
    // Simula lo que escribe insulin.ts antes de que corra cualquier recompute.
    await db.doc(`families/${familyId}/days/${todayKey}`).set(
      { insulin: { total: 1.5, doses: [{ eventId: "e-dose1", atMillis: Date.now(), units: 1.5 }] } },
      { merge: true },
    );

    const family = (await db.doc(`families/${familyId}`).get()).data()!;
    await processFamily(familyId, family, settings);

    const day = (await db.doc(`families/${familyId}/days/${todayKey}`).get()).data()!;
    expect(day.insulin?.total).toBe(1.5);
    expect(day.insulin?.doses).toHaveLength(1);
  });
});
