import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { onDocumentCreated } from "firebase-functions/v2/firestore";
import { logger } from "firebase-functions";
import { sendToChild, sendToParents } from "./messaging";
import { formatCheckinMessage, formatSosMessage } from "./messages";

/**
 * `onEventCreated` (docs/04). Idempotente por `processedAt`. NO consulta LibreLinkUp
 * todavía (eso es F7): `checkin`/`sos` quedan con `glucoseError: "not_configured"`.
 */
export const onEventCreated = onDocumentCreated("families/{familyId}/events/{eventId}", async (event) => {
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
  // TODO(F7): usar family.settings.lowThreshold/highThreshold al leer la glucosa de LibreLinkUp.

  const clientAt = (data.clientAt as Timestamp).toMillis();
  const createdAt = (data.createdAt as Timestamp).toMillis();
  const realAtMs = Math.min(clientAt, createdAt);
  const realAt = Timestamp.fromMillis(realAtMs);
  const syncedLate = createdAt - realAtMs > 120_000;

  const senderName = await resolveSenderName(data.createdBy as string, family);

  const baseUpdate = {
    realAt,
    syncedLate,
    senderName,
    processedAt: Timestamp.now(),
  };

  switch (data.type) {
    case "checkin": {
      await snap.ref.update({ ...baseUpdate, glucoseError: "not_configured" });
      await db.doc(`families/${familyId}`).update({
        lastCheckinAt: Timestamp.fromMillis(Math.max(family.lastCheckinAt?.toMillis?.() ?? 0, realAtMs)),
      });
      const { title, body } = formatCheckinMessage({ childName, realAtMs, syncedLate });
      await sendToParents(familyId, syncedLate ? "checkin_late" : "checkin", { title, body, eventId });
      break;
    }
    case "sos": {
      await snap.ref.update({ ...baseUpdate, glucoseError: "not_configured" });
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
