import { FieldValue, getFirestore } from "firebase-admin/firestore";

/**
 * Agrupa ráfagas de `checkin_late` (docs/03 §3 `meta/pushBuffer`, docs/07 "Qué ven los
 * padres"). Si llegan más de 3 en menos de 1 min, se agrupan en un solo push en vez de
 * mandar uno por revisión.
 */
const WINDOW_MS = 60_000;
const IMMEDIATE_THRESHOLD = 3;

interface PendingItem {
  eventId: string;
  realAt: number;
}

/**
 * Registra una revisión sincronizada tarde. Devuelve `true` si este evento debe
 * anunciarse de inmediato (los primeros `IMMEDIATE_THRESHOLD` de la ventana), o
 * `false` si debe esperar al push agrupado.
 */
export async function registerLateCheckin(familyId: string, eventId: string, realAtMs: number): Promise<boolean> {
  const db = getFirestore();
  const ref = db.doc(`families/${familyId}/meta/pushBuffer`);
  const now = Date.now();

  return db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const data = snap.exists ? (snap.data() as { pending: PendingItem[]; windowStart: number; flushAt: number }) : null;

    if (!data || now >= data.flushAt) {
      tx.set(ref, { pending: [{ eventId, realAt: realAtMs }], windowStart: now, flushAt: now + WINDOW_MS });
      return true;
    }

    const pending = [...data.pending, { eventId, realAt: realAtMs }];
    tx.update(ref, { pending: FieldValue.arrayUnion({ eventId, realAt: realAtMs }) });
    return pending.length <= IMMEDIATE_THRESHOLD;
  });
}

/**
 * Si la ventana ya venció y hay más de `IMMEDIATE_THRESHOLD` eventos acumulados,
 * devuelve el resumen para el push agrupado y vacía el buffer. `checkMissedSlots`
 * (cada 5 min) llama a esto para no depender de que llegue otra revisión.
 */
export async function flushIfDue(familyId: string): Promise<{ count: number; from: number; to: number } | null> {
  const db = getFirestore();
  const ref = db.doc(`families/${familyId}/meta/pushBuffer`);

  return db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists) return null;
    const data = snap.data() as { pending: PendingItem[]; flushAt: number };
    if (Date.now() < data.flushAt) return null;
    if (data.pending.length <= IMMEDIATE_THRESHOLD) {
      tx.delete(ref);
      return null;
    }
    tx.delete(ref);
    const times = data.pending.map((p) => p.realAt);
    return { count: data.pending.length, from: Math.min(...times), to: Math.max(...times) };
  });
}
