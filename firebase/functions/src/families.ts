import { randomInt } from "node:crypto";
import { getAuth } from "firebase-admin/auth";
import { FieldValue, getFirestore } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { logger } from "firebase-functions";
import { ReminderSettings } from "./schedule";

/** Settings por defecto (docs/03 §1). */
export const DEFAULT_SETTINGS: ReminderSettings = {
  enabled: true,
  intervalMinutes: 10,
  days: [1, 2, 3, 4, 5],
  startTime: "07:00",
  endTime: "13:00",
  escalationMinutes: 5,
  nudgeMinutes: 3,
  timezone: "America/Caracas",
  lowThreshold: 70,
  highThreshold: 180,
  smsFallbackEnabled: false,
  smsNumbers: [],
  childPhone: null,
};

const PAIRING_CODE_TTL_MS = 30 * 60 * 1000;
const JOIN_RATE_LIMIT_MAX_ATTEMPTS = 10;
const JOIN_RATE_LIMIT_WINDOW_MS = 60 * 60 * 1000;

function requireAuth(request: { auth?: { uid: string; token: Record<string, unknown> } | null }) {
  if (!request.auth) throw new HttpsError("unauthenticated", "Debes iniciar sesión.");
  return request.auth;
}

async function generateUniquePairingCode(): Promise<string> {
  const db = getFirestore();
  for (let attempt = 0; attempt < 10; attempt++) {
    const code = String(randomInt(0, 1_000_000)).padStart(6, "0");
    const existing = await db.doc(`pairingCodes/${code}`).get();
    if (!existing.exists) return code;
  }
  throw new HttpsError("internal", "No se pudo generar un código de vinculación.");
}

async function issuePairingCode(familyId: string, createdBy: string): Promise<{ code: string; expiresAt: number }> {
  const db = getFirestore();
  const expiresAt = Date.now() + PAIRING_CODE_TTL_MS;
  const code = await generateUniquePairingCode();

  // Borra códigos anteriores de la misma familia.
  const previous = await db.collection("pairingCodes").where("familyId", "==", familyId).get();
  const batch = db.batch();
  previous.forEach((doc) => batch.delete(doc.ref));
  batch.set(db.doc(`pairingCodes/${code}`), { familyId, expiresAt, createdBy });
  await batch.commit();

  return { code, expiresAt };
}

/** `createFamily({ childName, parentName }) → { familyId, code, expiresAt }` (docs/04). */
export const createFamily = onCall<{ childName: string; parentName: string }>(async (request) => {
  const auth = requireAuth(request);
  const { childName, parentName } = request.data ?? {};
  if (!childName?.trim() || !parentName?.trim()) {
    throw new HttpsError("invalid-argument", "Falta el nombre del niño o el tuyo.");
  }

  const db = getFirestore();
  const userSnap = await db.doc(`users/${auth.uid}`).get();
  if (userSnap.exists && userSnap.get("familyId")) {
    throw new HttpsError("failed-precondition", "Ya perteneces a una familia.");
  }

  const familyRef = db.collection("families").doc();
  await db.runTransaction(async (tx) => {
    tx.set(familyRef, {
      childName: childName.trim(),
      childUid: null,
      parents: { [auth.uid]: { name: parentName.trim() } },
      settings: DEFAULT_SETTINGS,
      libreConfigured: false,
      libreStatus: { ok: false },
      lastCheckinAt: null,
      createdAt: FieldValue.serverTimestamp(),
    });
    tx.set(
      db.doc(`users/${auth.uid}`),
      { familyId: familyRef.id, role: "parent", displayName: parentName.trim() },
      { merge: true },
    );
  });

  await getAuth().setCustomUserClaims(auth.uid, { familyId: familyRef.id, role: "parent" });
  const { code, expiresAt } = await issuePairingCode(familyRef.id, auth.uid);

  logger.info("createFamily", { familyId: familyRef.id, uid: auth.uid });
  return { familyId: familyRef.id, code, expiresAt };
});

/** `createPairingCode() → { code, expiresAt }` (padre). */
export const createPairingCode = onCall(async (request) => {
  const auth = requireAuth(request);
  const familyId = auth.token.familyId as string | undefined;
  const role = auth.token.role as string | undefined;
  if (!familyId || role !== "parent") {
    throw new HttpsError("permission-denied", "Solo un padre puede generar un código.");
  }
  return issuePairingCode(familyId, auth.uid);
});

/**
 * `joinFamily({ code, role, displayName? }) → { familyId, childName, role }`.
 * `displayName` es obligatorio para `role: "parent"` (cómo se llama ese padre).
 * Para `role: "child"` se ignora: el nombre del niño es el que ya puso el padre
 * al crear la familia (`families.childName`), que además él puede editar
 * después (docs/03 §3, docs/06 SetupScreen paso "Niño": solo pide el código).
 */
export const joinFamily = onCall<{ code: string; role: "parent" | "child"; displayName?: string }>(async (request) => {
  const auth = requireAuth(request);
  const { code, role } = request.data ?? {};
  const displayName = request.data?.displayName?.trim();
  if (!code || !role) {
    throw new HttpsError("invalid-argument", "Faltan datos para vincular el teléfono.");
  }
  if (role !== "parent" && role !== "child") {
    throw new HttpsError("invalid-argument", "Rol inválido.");
  }
  if (role === "parent" && !displayName) {
    throw new HttpsError("invalid-argument", "Falta tu nombre.");
  }

  const db = getFirestore();
  await enforceJoinRateLimit(auth.uid);

  const codeRef = db.doc(`pairingCodes/${code}`);
  const familyId = await db.runTransaction(async (tx) => {
    const codeSnap = await tx.get(codeRef);
    if (!codeSnap.exists) throw new HttpsError("not-found", "Código inválido o vencido.");
    const { familyId: fid, expiresAt } = codeSnap.data() as { familyId: string; expiresAt: number };
    if (expiresAt < Date.now()) throw new HttpsError("not-found", "Código inválido o vencido.");

    const familyRef = db.doc(`families/${fid}`);
    const familySnap = await tx.get(familyRef);
    if (!familySnap.exists) throw new HttpsError("not-found", "Código inválido o vencido.");

    if (role === "child") {
      const oldChildUid = familySnap.get("childUid") as string | null | undefined;
      const childName = (familySnap.get("childName") as string) ?? "el niño";
      tx.update(familyRef, { childUid: auth.uid });
      if (oldChildUid && oldChildUid !== auth.uid) {
        tx.delete(db.doc(`users/${oldChildUid}`));
      }
      tx.set(db.doc(`users/${auth.uid}`), { familyId: fid, role: "child", displayName: childName });
    } else {
      tx.update(familyRef, { [`parents.${auth.uid}`]: { name: displayName } });
      tx.set(db.doc(`users/${auth.uid}`), { familyId: fid, role: "parent", displayName });
    }

    return fid;
  });

  const familySnap = await db.doc(`families/${familyId}`).get();
  const oldChildUid = role === "child" ? (familySnap.get("childUid") as string | null) : null;

  await getAuth().setCustomUserClaims(auth.uid, { familyId, role });
  if (role === "child" && oldChildUid && oldChildUid !== auth.uid) {
    try {
      await getAuth().setCustomUserClaims(oldChildUid, null);
      await getAuth().revokeRefreshTokens(oldChildUid);
    } catch (err) {
      logger.warn("no se pudo revocar el teléfono anterior del niño", { oldChildUid, err });
    }
  }

  logger.info("joinFamily", { familyId, uid: auth.uid, role });
  return { familyId, childName: familySnap.get("childName") as string, role };
});

async function enforceJoinRateLimit(uid: string): Promise<void> {
  const db = getFirestore();
  const ref = db.doc(`rateLimits/${uid}`);
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const now = Date.now();
    const data = snap.data() as { joinAttempts: number; windowStart: number } | undefined;

    if (!data || now - data.windowStart > JOIN_RATE_LIMIT_WINDOW_MS) {
      tx.set(ref, { joinAttempts: 1, windowStart: now });
      return;
    }
    if (data.joinAttempts >= JOIN_RATE_LIMIT_MAX_ATTEMPTS) {
      throw new HttpsError("resource-exhausted", "Demasiados intentos. Espera antes de volver a intentar.");
    }
    tx.update(ref, { joinAttempts: FieldValue.increment(1) });
  });
}

/** `leaveFamily()`: quita al uid de su familia. No se permite si es el último padre. */
export const leaveFamily = onCall(async (request) => {
  const auth = requireAuth(request);
  const familyId = auth.token.familyId as string | undefined;
  const role = auth.token.role as string | undefined;
  if (!familyId) throw new HttpsError("failed-precondition", "No perteneces a ninguna familia.");

  const db = getFirestore();
  const familyRef = db.doc(`families/${familyId}`);

  await db.runTransaction(async (tx) => {
    const familySnap = await tx.get(familyRef);
    if (!familySnap.exists) return;

    if (role === "parent") {
      const parents = (familySnap.get("parents") as Record<string, unknown>) ?? {};
      if (Object.keys(parents).length <= 1) {
        throw new HttpsError("failed-precondition", "No puedes salir: eres el único padre de la familia.");
      }
      tx.update(familyRef, { [`parents.${auth.uid}`]: FieldValue.delete() });
    } else if (role === "child" && familySnap.get("childUid") === auth.uid) {
      tx.update(familyRef, { childUid: null });
    }

    tx.delete(db.doc(`users/${auth.uid}`));
  });

  await getAuth().setCustomUserClaims(auth.uid, null);
  logger.info("leaveFamily", { familyId, uid: auth.uid, role });
  return { ok: true };
});
