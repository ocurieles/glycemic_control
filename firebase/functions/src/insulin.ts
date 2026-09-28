import { FieldValue, getFirestore, Timestamp } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { resolveSettings } from "./families";
import { ReminderSettings } from "./schedule";
import { dateKeyOf } from "./time";

/**
 * Acumula las dosis de insulina por día en `families/{fid}/days/{fecha}.insulin`
 * (pedido del usuario 2026-09-24: vista de calendario en la app del padre). Separado
 * de `recompute.ts`/`compliance.ts` a propósito: eso es sobre el cumplimiento de
 * horario de revisiones, esto es solo una suma aditiva, mucho más simple.
 */
export async function addInsulinDose(
  familyId: string,
  dateKey: string,
  eventId: string,
  atMillis: number,
  units: number,
  glucose?: { valueMgDl: number; trend?: number },
): Promise<void> {
  const dayRef = getFirestore().doc(`families/${familyId}/days/${dateKey}`);
  await dayRef.set(
    {
      date: dateKey,
      insulin: {
        total: FieldValue.increment(units),
        doses: FieldValue.arrayUnion({
          eventId,
          atMillis,
          units,
          ...(glucose ? { glucoseMgDl: glucose.valueMgDl, ...(glucose.trend !== undefined ? { glucoseTrend: glucose.trend } : {}) } : {}),
        }),
      },
    },
    { merge: true },
  );
}

/**
 * TEMPORAL (2026-09-24 → borrar tras usarla una vez): reconstruye `insulin` en todos
 * los `days/{fecha}` de la familia a partir de los `insulin_dose` guardados en
 * `events/` — arregla el daño del bug real de docs/recompute.ts (`{merge:false}`
 * borraba `insulin` en cada recompute, 2026-09-27) sin perder datos, porque los
 * eventos originales nunca se tocaron.
 */
export async function backfillInsulinDays(familyId: string, timezone: string): Promise<{ daysFixed: number }> {
  const db = getFirestore();
  const eventsSnap = await db.collection(`families/${familyId}/events`).where("type", "==", "insulin_dose").get();

  type DoseEntry = { eventId: string; atMillis: number; units: number; glucoseMgDl?: number; glucoseTrend?: number };
  const byDate = new Map<string, { total: number; doses: DoseEntry[] }>();
  for (const doc of eventsSnap.docs) {
    const data = doc.data();
    const realAt = (data.realAt as Timestamp | undefined)?.toMillis();
    const doseUnits = data.doseUnits as number | undefined;
    if (realAt === undefined || doseUnits === undefined) continue;
    const dateKey = dateKeyOf(realAt, timezone);
    const entry = byDate.get(dateKey) ?? { total: 0, doses: [] };
    entry.total += doseUnits;
    const glucose = data.glucose as { valueMgDl?: number; trend?: number } | undefined;
    entry.doses.push({
      eventId: doc.id,
      atMillis: realAt,
      units: doseUnits,
      ...(glucose?.valueMgDl !== undefined ? { glucoseMgDl: glucose.valueMgDl } : {}),
      ...(glucose?.trend !== undefined ? { glucoseTrend: glucose.trend } : {}),
    });
    byDate.set(dateKey, entry);
  }

  const batch = db.batch();
  for (const [dateKey, insulin] of byDate) {
    batch.set(db.doc(`families/${familyId}/days/${dateKey}`), { date: dateKey, insulin }, { merge: true });
  }
  await batch.commit();
  return { daysFixed: byDate.size };
}

export const backfillInsulinDaysCallable = onCall(async (request) => {
  if (!request.auth) throw new HttpsError("unauthenticated", "Debes iniciar sesión.");
  const familyId = request.auth.token.familyId as string | undefined;
  const role = request.auth.token.role as string | undefined;
  if (!familyId || role !== "parent") {
    throw new HttpsError("permission-denied", "Solo un padre puede hacer esto.");
  }
  const familySnap = await getFirestore().doc(`families/${familyId}`).get();
  const settings = resolveSettings(familySnap.data()?.settings as Partial<ReminderSettings> | undefined);
  return backfillInsulinDays(familyId, settings.timezone);
});
