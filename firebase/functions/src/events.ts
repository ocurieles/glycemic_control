import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { onDocumentCreated } from "firebase-functions/v2/firestore";
import { logger } from "firebase-functions";
import { lluAppVersion, lluEncKey } from "./config";
import { resolveSettings } from "./families";
import { lookupGlucoseForEvent } from "./libre/service";
import { sendToChild, sendToParents } from "./messaging";
import { formatCheckinMessage, formatSosMessage, GlucoseInfo } from "./messages";
import { registerLateCheckin } from "./pushBuffer";
import { recomputeDay } from "./recompute";
import { dateKeyOf } from "./time";
import { ReminderSettings } from "./schedule";

/**
 * `onEventCreated` (docs/04). Idempotente por `processedAt`. Para `checkin`/`sos`
 * consulta LibreLinkUp (docs/05); un fallo ahí nunca bloquea el resto del evento
 * (CLAUDE.md regla 4), solo deja `glucoseError` con el motivo.
 */
export const onEventCreated = onDocumentCreated({ document: "families/{familyId}/events/{eventId}", secrets: [lluEncKey] }, async (event) => {
  const snap = event.data;
  if (!snap) return;

  const { familyId, eventId } = event.params;
  const data = snap.data();

  if (data.processedAt) {
    logger.info("evento ya procesado, se ignora", { familyId, eventId });
    return;
  }

  const db = getFirestore();
  const familySnap = await db.doc(`families/${familyId}`).get();
  const family = familySnap.data() ?? {};
  const childName = (family.childName as string) ?? "el niño";
  const settings = resolveSettings(family.settings as Partial<ReminderSettings> | undefined);

  const clientAt = (data.clientAt as Timestamp).toMillis();
  const createdAt = (data.createdAt as Timestamp).toMillis();
  const realAtMs = Math.min(clientAt, createdAt);
  const realAt = Timestamp.fromMillis(realAtMs);
  const syncedLate = createdAt - realAtMs > 120_000;

  const senderName = await resolveSenderName(data.createdBy as string, family);

  async function lookupGlucose(): Promise<{ glucose?: GlucoseInfo; glucoseError?: string }> {
    const result = await lookupGlucoseForEvent(familyId, clientAt, settings, lluAppVersion.value(), lluEncKey.value());
    return result.glucose ? { glucose: result.glucose } : { glucoseError: result.error };
  }

  const baseUpdate = {
    realAt,
    syncedLate,
    senderName,
    processedAt: Timestamp.now(),
  };

  switch (data.type) {
    case "checkin": {
      const { glucose, glucoseError } = await lookupGlucose();
      await snap.ref.update({ ...baseUpdate, ...(glucoseError ? { glucoseError } : { glucose }) });
      await db.doc(`families/${familyId}`).update({
        lastCheckinAt: Timestamp.fromMillis(Math.max(family.lastCheckinAt?.toMillis?.() ?? 0, realAtMs)),
      });

      if (syncedLate) {
        const sendNow = await registerLateCheckin(familyId, eventId, realAtMs);
        if (sendNow) {
          const { title, body } = formatCheckinMessage({ childName, realAtMs, syncedLate, createdAtMs: createdAt, glucose });
          await sendToParents(familyId, "checkin_late", { title, body, eventId });
        }
      } else {
        const { title, body } = formatCheckinMessage({ childName, realAtMs, syncedLate, glucose });
        await sendToParents(familyId, "checkin", { title, body, eventId });
      }

      await recomputeDay(familyId, dateKeyOf(realAtMs, settings.timezone), settings);
      break;
    }
    case "sos": {
      const { glucose, glucoseError } = await lookupGlucose();
      await snap.ref.update({ ...baseUpdate, ...(glucoseError ? { glucoseError } : { glucose }) });
      const { title, body } = formatSosMessage({ childName, realAtMs, syncedLate, smsSent: !!data.smsSent });
      const location = data.location as { lat: number; lng: number } | undefined;
      await sendToParents(familyId, "sos", {
        title,
        body,
        eventId,
        ...(location ? { lat: String(location.lat), lng: String(location.lng) } : {}),
      });
      break;
    }
    case "parent_message": {
      await snap.ref.update(baseUpdate);
      await sendToChild(familyId, "parent_message", {
        text: (data.text as string) ?? "",
        from: senderName ?? "",
        eventId,
        at: String(realAtMs),
      });
      break;
    }
    case "sos_ack": {
      await snap.ref.update(baseUpdate);
      await sendToChild(familyId, "sos_ack", {
        text: (data.text as string) ?? "",
        from: senderName ?? "",
        at: String(realAtMs),
      });
      await sendToParents(
        familyId,
        "sos_ack_info",
        { title: "Respuesta al SOS", body: `${senderName} respondió: ${data.text ?? ""}` },
        data.createdBy as string,
      );
      break;
    }
    default:
      logger.warn("tipo de evento desconocido", { familyId, eventId, type: data.type });
      await snap.ref.update(baseUpdate);
  }
});

async function resolveSenderName(uid: string, family: FirebaseFirestore.DocumentData): Promise<string> {
  if (family.childUid === uid) return (family.childName as string) ?? "el niño";
  const parents = (family.parents as Record<string, { name: string }>) ?? {};
  return parents[uid]?.name ?? "un familiar";
}
