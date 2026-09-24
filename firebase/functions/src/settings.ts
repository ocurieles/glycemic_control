import { onDocumentUpdated } from "firebase-functions/v2/firestore";
import { logger } from "firebase-functions";
import { sendToChild } from "./messaging";

/**
 * `onFamilyUpdated` (docs/04): si cambió `settings`, avisa al niño con un push
 * `sync` (sin notificación) para que recargue los settings y reprograme sus alarmas.
 * `recomputeDay` llega en F6.
 */
export const onFamilyUpdated = onDocumentUpdated("families/{familyId}", async (event) => {
  const before = event.data?.before.data();
  const after = event.data?.after.data();
  if (!before || !after) return;

  const settingsChanged = JSON.stringify(before.settings) !== JSON.stringify(after.settings);
  if (!settingsChanged) return;

  const { familyId } = event.params;
  logger.info("settings cambiaron, se avisa al niño", { familyId });
  await sendToChild(familyId, "sync", {});
});
