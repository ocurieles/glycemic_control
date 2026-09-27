import { FieldValue, getFirestore } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import { logger } from "firebase-functions";

/**
 * Envío de pushes (docs/04 "Mensajería"). Data-only, prioridad alta.
 * Todas las claves y valores del payload deben ser strings.
 */
export type PushPayload = Record<string, string>;

/**
 * BUG real encontrado en pruebas de campo (docs/08 F8): solo `registration-token-not-
 * registered` significa "este token ya no existe, bórralo para siempre" según la doc
 * de FCM. `invalid-argument` puede salir de un token recién creado que Google todavía
 * no terminó de propagar (segundos después de instalar/vincular) — es transitorio, y
 * borrarlo de una vez dejaba al usuario sin push hasta reabrir la app (que es lo único
 * que lo vuelve a guardar). Se deja de tratar como "borrar para siempre".
 */
const INVALID_TOKEN_ERRORS = new Set(["messaging/registration-token-not-registered"]);

/** TTL por tipo de push (docs/04, columna "TTL"). */
export const PUSH_TTL_SECONDS = {
  checkin: 3600,
  checkin_late: 3600,
  missed: 1800,
  sos: 86400,
  sos_glucose: 3600,
  sos_ack_info: 3600,
  day_summary: 43200,
  insulin_dose: 3600,
  parent_message: 1800,
  sos_ack: 3600,
  nudge: 300,
  sync: 3600,
} as const;

export type PushType = keyof typeof PUSH_TTL_SECONDS;

async function sendToTokens(uidToToken: Map<string, string>, type: PushType, payload: PushPayload): Promise<void> {
  const uids = [...uidToToken.keys()];
  const tokens = [...uidToToken.values()];
  if (tokens.length === 0) {
    logger.info("sin token(es) FCM, no se manda push", { type, uids });
    return;
  }

  const response = await getMessaging().sendEachForMulticast({
    tokens,
    data: { type, ...payload },
    android: { priority: "high", ttl: PUSH_TTL_SECONDS[type] * 1000 },
  });

  const staleUids: string[] = [];
  response.responses.forEach((r, i) => {
    if (r.success) {
      logger.info("push enviado", { uid: uids[i], type });
      return;
    }
    const code = r.error?.code;
    logger.info("push failed", { uid: uids[i], type, code, errorMessage: r.error?.message });
    if (code && INVALID_TOKEN_ERRORS.has(code)) staleUids.push(uids[i]);
  });

  await Promise.all(
    staleUids.map((uid) =>
      getFirestore()
        .doc(`users/${uid}`)
        .update({ fcmToken: FieldValue.delete() })
        .catch((err) => {
          logger.warn("no se pudo limpiar fcmToken inválido", { uid, err });
        }),
    ),
  );
}

interface FamilyDoc {
  parents?: Record<string, { name: string }>;
  childUid?: string;
}

async function tokensFor(uids: string[]): Promise<Map<string, string>> {
  const db = getFirestore();
  const result = new Map<string, string>();
  await Promise.all(
    uids.map(async (uid) => {
      const snap = await db.doc(`users/${uid}`).get();
      const token = snap.get("fcmToken");
      if (token) result.set(uid, token);
    }),
  );
  return result;
}

/** Envía a todos los padres de la familia (opcionalmente excluyendo uno). */
export async function sendToParents(
  familyId: string,
  type: PushType,
  payload: PushPayload,
  excludeUid?: string,
): Promise<void> {
  const familySnap = await getFirestore().doc(`families/${familyId}`).get();
  const family = familySnap.data() as FamilyDoc | undefined;
  const parentUids = Object.keys(family?.parents ?? {}).filter((uid) => uid !== excludeUid);
  const tokens = await tokensFor(parentUids);
  await sendToTokens(tokens, type, payload);
}

/** Envía solo al niño de la familia (`families.childUid`). */
export async function sendToChild(familyId: string, type: PushType, payload: PushPayload): Promise<void> {
  const familySnap = await getFirestore().doc(`families/${familyId}`).get();
  const family = familySnap.data() as FamilyDoc | undefined;
  if (!family?.childUid) return;
  const tokens = await tokensFor([family.childUid]);
  await sendToTokens(tokens, type, payload);
}
