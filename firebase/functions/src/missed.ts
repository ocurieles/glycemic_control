import { FieldValue, getFirestore } from "firebase-admin/firestore";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { logger } from "firebase-functions";
import { resolveSettings } from "./families";
import { sendToChild, sendToParents } from "./messaging";
import { formatDaySummaryMessage, formatGroupedLateMessage, formatMissedMessage } from "./messages";
import { flushIfDue } from "./pushBuffer";
import { recomputeDay } from "./recompute";
import { ReminderSettings } from "./schedule";
import { dateKeyOf, wallTimeToEpochMs } from "./time";

// No alertar lo muy viejo tras una caída del servicio (docs/04 "checkMissedSlots").
const MISSED_LOOKBACK_MS = 60 * 60_000;

interface DaySlotSnapshot {
  status: string;
}

/** `checkMissedSlots` — cada 5 minutos, zona `America/Caracas` (docs/04). */
export const checkMissedSlots = onSchedule({ schedule: "every 5 minutes", timeZone: "America/Caracas" }, async () => {
  const db = getFirestore();
  const familiesSnap = await db.collection("families").get();

  for (const familyDoc of familiesSnap.docs) {
    const family = familyDoc.data();
    const settings = resolveSettings(family.settings as Partial<ReminderSettings> | undefined);
    if (!settings.enabled || !family.childUid) continue;

    const familyId = familyDoc.id;
    await processFamily(familyId, family, settings);
  }
});

/** Exportado para pruebas de integración (`missed.test.ts`): corre la lógica sin pasar por el scheduler real. */
export async function processFamily(
  familyId: string,
  family: FirebaseFirestore.DocumentData,
  settings: ReminderSettings,
): Promise<void> {
  const db = getFirestore();
  const now = Date.now();
  const todayKey = dateKeyOf(now, settings.timezone);
  const childName = (family.childName as string) ?? "el niño";

  await recomputeDay(familyId, todayKey, settings);

  const dayRef = db.doc(`families/${familyId}/days/${todayKey}`);
  const daySnap = await dayRef.get();
  const dayData = daySnap.data();
  if (!dayData) return;

  const missedAlerted: string[] = (dayData.missedAlerted as string[]) ?? [];
  const slots = (dayData.slots as Record<string, DaySlotSnapshot>) ?? {};
  const newlyMissed: string[] = [];

  for (const [hhmm, slot] of Object.entries(slots)) {
    if (slot.status !== "missed" || missedAlerted.includes(hhmm)) continue;
    const slotMs = wallTimeToEpochMs(todayKey, hhmm, settings.timezone);
    if (slotMs < now - MISSED_LOOKBACK_MS) continue;

    const { title, body } = formatMissedMessage(childName, hhmm);
    await sendToParents(familyId, "missed", { title, body, slot: hhmm });
    await sendToChild(familyId, "nudge", { slot: hhmm });
    newlyMissed.push(hhmm);
  }

  if (newlyMissed.length > 0) {
    await dayRef.update({ missedAlerted: FieldValue.arrayUnion(...newlyMissed) });
  }

  // day_summary: primera corrida después de endTime.
  const endMs = wallTimeToEpochMs(todayKey, settings.endTime, settings.timezone);
  if (now >= endMs && !dayData.summarySent) {
    const { title, body } = formatDaySummaryMessage(
      dayData.counts as { expected: number; onTime: number; late: number; missed: number },
    );
    await sendToParents(familyId, "day_summary", { title, body, date: todayKey });
    await dayRef.update({ summarySent: true });
  }

  // Ráfagas de checkin_late vencidas (docs/07).
  const grouped = await flushIfDue(familyId);
  if (grouped) {
    const { title, body } = formatGroupedLateMessage(
      childName,
      grouped.count,
      grouped.from,
      grouped.to,
      settings.timezone,
    );
    await sendToParents(familyId, "checkin_late", { title, body, eventId: `${todayKey}-grouped` });
  }

  logger.info("checkMissedSlots", { familyId, todayKey, newlyMissed: newlyMissed.length });
}
