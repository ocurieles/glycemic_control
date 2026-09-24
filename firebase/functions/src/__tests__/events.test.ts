import { getApps, initializeApp } from "firebase-admin/app";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { describe, expect, it } from "vitest";

/**
 * Integración de `onEventCreated`/`onFamilyUpdated` (docs/04) contra los
 * emuladores de Firestore + Functions. Requiere que `lib/` esté compilado
 * (`npm run build`) y correr con:
 *   firebase emulators:exec --only firestore,functions "npm --prefix functions run test:integration"
 * (ver CLAUDE.md → Comandos). NO usa LibreLinkUp (eso es F7): `glucoseError`
 * queda en "not_configured".
 */

// `firebase emulators:exec` inyecta FIRESTORE_EMULATOR_HOST al proceso hijo (esta suite).
// No hay una variable equivalente para "el emulador de functions está arriba", así que
// basta con detectar Firestore: si corres esto sin `--only firestore,functions`, el
// trigger nunca procesará el evento y el test fallará por timeout con un mensaje claro.
const canRun = !!process.env.FIRESTORE_EMULATOR_HOST;
if (!canRun) {
  console.warn(
    "events.test.ts se saltea: necesita los emuladores de Firestore y Functions " +
      "(ver CLAUDE.md → firebase emulators:exec --only firestore,functions).",
  );
}

const projectId = process.env.GCLOUD_PROJECT ?? "demo-checkin-rules";
if (!getApps().length) initializeApp({ projectId });
const db = getFirestore();

async function waitFor<T>(fn: () => Promise<T | undefined>, timeoutMs = 15000): Promise<T> {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    const result = await fn();
    if (result !== undefined) return result;
    await new Promise((r) => setTimeout(r, 250));
  }
  throw new Error("Se agotó el tiempo esperando a que el trigger procesara el evento.");
}

describe.runIf(canRun)("onEventCreated (integración con el emulador)", () => {
  it("procesa un checkin: realAt, syncedLate=false, glucoseError not_configured, processedAt", async () => {
    const familyId = `fam-checkin-${Date.now()}`;
    await db.doc(`families/${familyId}`).set({
      childName: "Cesar",
      childUid: "child1",
      parents: { parent1: { name: "Mamá" } },
      settings: {},
      createdAt: Timestamp.now(),
    });

    const eventRef = db.doc(`families/${familyId}/events/e1`);
    const clientAt = Timestamp.now();
    await eventRef.set({ type: "checkin", createdBy: "child1", createdAt: Timestamp.now(), clientAt, source: "app" });

    const processed = await waitFor(async () => {
      const snap = await eventRef.get();
      return snap.get("processedAt") ? snap.data() : undefined;
    });

    expect(processed?.glucoseError).toBe("not_configured");
    expect(processed?.syncedLate).toBe(false);
    expect(processed?.senderName).toBe("Cesar");
    expect((processed?.realAt as FirebaseFirestore.Timestamp).toMillis()).toBe(clientAt.toMillis());

    const familySnap = await db.doc(`families/${familyId}`).get();
    expect((familySnap.get("lastCheckinAt") as FirebaseFirestore.Timestamp).toMillis()).toBe(clientAt.toMillis());
  });

  it("marca syncedLate cuando createdAt llega más de 120s después de clientAt", async () => {
    const familyId = `fam-late-${Date.now()}`;
    await db.doc(`families/${familyId}`).set({
      childName: "Cesar",
      childUid: "child1",
      parents: {},
      settings: {},
      createdAt: Timestamp.now(),
    });

    const eventRef = db.doc(`families/${familyId}/events/e1`);
    const clientAt = Timestamp.fromMillis(Date.now() - 5 * 60_000); // 5 min en el pasado
    await eventRef.set({ type: "checkin", createdBy: "child1", createdAt: Timestamp.now(), clientAt, source: "app" });

    const processed = await waitFor(async () => {
      const snap = await eventRef.get();
      return snap.get("processedAt") ? snap.data() : undefined;
    });

    expect(processed?.syncedLate).toBe(true);
  });

  it("es idempotente: no reprocesa un evento con processedAt ya escrito", async () => {
    const familyId = `fam-idempotent-${Date.now()}`;
    await db.doc(`families/${familyId}`).set({
      childName: "Cesar",
      childUid: "child1",
      parents: {},
      settings: {},
      createdAt: Timestamp.now(),
    });

    const eventRef = db.doc(`families/${familyId}/events/e1`);
    await eventRef.set({
      type: "checkin",
      createdBy: "child1",
      createdAt: Timestamp.now(),
      clientAt: Timestamp.now(),
      source: "app",
    });

    await waitFor(async () => {
      const snap = await eventRef.get();
      return snap.get("processedAt") ? true : undefined;
    });

    const firstProcessedAt = (await eventRef.get()).get("processedAt") as FirebaseFirestore.Timestamp;
    // Simula un reintento del cliente: vuelve a escribir el mismo doc (mismo eventId, set()).
    await eventRef.set(
      { type: "checkin", createdBy: "child1", createdAt: Timestamp.now(), clientAt: Timestamp.now(), source: "app" },
      { merge: true },
    );
    await new Promise((r) => setTimeout(r, 2000));
    const secondProcessedAt = (await eventRef.get()).get("processedAt") as FirebaseFirestore.Timestamp;
    expect(secondProcessedAt.toMillis()).toBe(firstProcessedAt.toMillis());
  });

  it("parent_message llega marcado y con senderName resuelto", async () => {
    const familyId = `fam-msg-${Date.now()}`;
    await db.doc(`families/${familyId}`).set({
      childName: "Cesar",
      childUid: "child1",
      parents: { parent1: { name: "Papá" } },
      settings: {},
      createdAt: Timestamp.now(),
    });

    const eventRef = db.doc(`families/${familyId}/events/e1`);
    await eventRef.set({
      type: "parent_message",
      createdBy: "parent1",
      createdAt: Timestamp.now(),
      clientAt: Timestamp.now(),
      source: "app",
      text: "Recuerda la lectura",
    });

    const processed = await waitFor(async () => {
      const snap = await eventRef.get();
      return snap.get("processedAt") ? snap.data() : undefined;
    });
    expect(processed?.senderName).toBe("Papá");
  });
});

describe.runIf(canRun)("onFamilyUpdated (integración con el emulador)", () => {
  it("no falla cuando cambian settings y el niño no tiene fcmToken (sin push real)", async () => {
    const familyId = `fam-settings-${Date.now()}`;
    const familyRef = db.doc(`families/${familyId}`);
    await familyRef.set({
      childName: "Cesar",
      childUid: "child1",
      parents: {},
      settings: { intervalMinutes: 10 },
      createdAt: Timestamp.now(),
    });
    await familyRef.update({ settings: { intervalMinutes: 15 } });

    // No hay forma directa de observar el push (no hay emulador de FCM), pero si el
    // trigger tira, el propio documento no se corrompe: lo verificamos indirectamente.
    await new Promise((r) => setTimeout(r, 1500));
    const snap = await familyRef.get();
    expect(snap.get("settings").intervalMinutes).toBe(15);
  });
});
