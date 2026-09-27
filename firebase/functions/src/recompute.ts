import { FieldValue, getFirestore, Timestamp } from "firebase-admin/firestore";
import { logger } from "firebase-functions";
import { computeDay, DayCheckin } from "./compliance";
import { ReminderSettings } from "./schedule";
import { wallTimeToEpochMs } from "./time";

/**
 * `recomputeDay(fid, date)` (docs/04): lee las revisiones del día con `realAt` dentro
 * de la fecha local, llama a `computeDay` y escribe `days/{fecha}` preservando
 * `missedAlerted` y `summarySent`.
 */
export async function recomputeDay(familyId: string, dateKey: string, settings: ReminderSettings): Promise<void> {
  const db = getFirestore();
  const dayRef = db.doc(`families/${familyId}/days/${dateKey}`);

  const [dayStart, dayEnd] = dayBoundsMillis(dateKey, settings.timezone);

  const eventsSnap = await db
    .collection(`families/${familyId}/events`)
    .where("type", "==", "checkin")
    .get();

  const checkins: DayCheckin[] = eventsSnap.docs
    .map((doc) => {
      const data = doc.data();
      const realAt = (data.realAt as Timestamp | undefined)?.toMillis();
      const createdAt = (data.createdAt as Timestamp | undefined)?.toMillis();
      if (realAt === undefined || createdAt === undefined) return null;
      return { eventId: doc.id, realAt, createdAt };
    })
    .filter((c): c is DayCheckin => c !== null && c.realAt >= dayStart && c.realAt < dayEnd);

  const now = Date.now();
  const computed = computeDay(settings, checkins, now, dateKey);

  const existing = await dayRef.get();
  const missedAlerted = (existing.data()?.missedAlerted as string[] | undefined) ?? [];
  const summarySent = (existing.data()?.summarySent as boolean | undefined) ?? false;
  // `{ merge: false }` reescribe TODO el documento — sin esto, cada recompute (cada
  // revisión, y cada 5 min por checkMissedSlots) borraba silenciosamente el campo
  // `insulin` que escribe insulin.ts en el mismo documento (bug real 2026-09-27: el
  // calendario de insulina "desaparecía" solo después de un rato). Se preserva igual
  // que `missedAlerted`/`summarySent`.
  const insulin = existing.data()?.insulin;

  await dayRef.set(
    {
      ...computed,
      missedAlerted,
      summarySent,
      ...(insulin !== undefined ? { insulin } : {}),
      updatedAt: FieldValue.serverTimestamp(),
    },
    { merge: false },
  );

  logger.info("recomputeDay", { familyId, dateKey, counts: computed.counts });
}

/** Límites del día local `dateKey` en `timezone`, como epoch ms [inicio, fin). */
function dayBoundsMillis(dateKey: string, timezone: string): [number, number] {
  const start = wallTimeToEpochMs(dateKey, "00:00", timezone);
  const end = wallTimeToEpochMs(dateKey, "23:59", timezone) + 60_000; // +1 min: 23:59 -> medianoche
  return [start, end];
}
