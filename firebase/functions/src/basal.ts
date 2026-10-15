import { FieldValue, getFirestore } from "firebase-admin/firestore";
import { logger } from "firebase-functions";
import { sendToParents } from "./messaging";
import { formatBasalMissingMessage } from "./messages";
import { dateKeyOf, wallTimeToEpochMs } from "./time";

/** Pasada la hora prevista de la Tresiba, se espera este margen antes de avisar. */
export const BASAL_GRACE_MS = 30 * 60_000;

export interface BasalPlan {
  enabled: boolean;
  units: number;
  time: string; // HH:mm
}

/** Pura (docs/04): ¿toca avisar hoy que falta la Tresiba? */
export function basalReminderDue(
  plan: BasalPlan | undefined,
  nowMs: number,
  timezone: string,
  doneDates: string[],
  alertedDate: string | undefined,
): boolean {
  if (!plan?.enabled) return false;
  const today = dateKeyOf(nowMs, timezone);
  if (doneDates.includes(today) || alertedDate === today) return false;
  return nowMs >= wallTimeToEpochMs(today, plan.time, timezone) + BASAL_GRACE_MS;
}

/** Guarda que la Tresiba de ese día ya se registró (la lee `checkBasal`). */
export async function markBasalDone(familyId: string, dateKey: string): Promise<void> {
  await getFirestore()
    .doc(`families/${familyId}/meta/basal`)
    .set({ doneDates: FieldValue.arrayUnion(dateKey) }, { merge: true });
}

/** Aviso diario si pasó la hora de la Tresiba sin registro (pedido 2026-10-15). */
export async function checkBasal(
  familyId: string,
  family: FirebaseFirestore.DocumentData,
  timezone: string,
  nowMs = Date.now(),
): Promise<boolean> {
  const plan = family.basalPlan as BasalPlan | undefined;
  if (!plan?.enabled) return false;
  const ref = getFirestore().doc(`families/${familyId}/meta/basal`);
  const meta = (await ref.get()).data() ?? {};
  const doneDates = (meta.doneDates as string[] | undefined) ?? [];
  const alertedDate = meta.alertedDate as string | undefined;
  if (!basalReminderDue(plan, nowMs, timezone, doneDates, alertedDate)) return false;

  const { title, body } = formatBasalMissingMessage((family.childName as string) ?? "el niño", plan.time);
  await sendToParents(familyId, "basal_missing", { title, body });
  await ref.set({ alertedDate: dateKeyOf(nowMs, timezone) }, { merge: true });
  logger.info("basal_missing enviado", { familyId, plan: plan.time });
  return true;
}
