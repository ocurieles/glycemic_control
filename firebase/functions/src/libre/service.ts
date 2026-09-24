import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { logger } from "firebase-functions";
import {
  Connection,
  fetchConnections,
  fetchGraph,
  GlucoseItem,
  LibreApiError,
  LibreErrorCode,
  login,
  LibreUnauthorizedError,
  Session,
} from "./client";
import { decryptCredentials, decryptSession, encryptCredentials, encryptSession, StoredCredentials } from "./crypto";
import { GlucoseInfo } from "../messages";
import { ReminderSettings } from "../schedule";

/**
 * Orquesta sesión + caché + reintento de LibreLinkUp (docs/05 "Sesión y caché").
 * Nunca deja que un fallo de LibreLinkUp se propague fuera de aquí como excepción
 * no controlada: siempre vuelve `{ error }` para que el llamador (events.ts) siga
 * su camino (CLAUDE.md regla 4).
 */

const SESSION_REUSE_MARGIN_MS = 5 * 60_000;
const STALE_VS_CLIENT_AT_MS = 15 * 60_000;
const GRAPH_MATCH_WINDOW_MS = 10 * 60_000;

export type LibreLookupErrorCode = LibreErrorCode | "no_data" | "stale" | "not_configured";

export interface LibreLookupResult {
  glucose?: GlucoseInfo;
  error?: LibreLookupErrorCode;
}

interface PrivateLibreDoc {
  credentialsEnc?: string;
  sessionEnc?: string;
  patientId?: string;
}

function privateDocRef(familyId: string) {
  return getFirestore().doc(`families/${familyId}/private/libre`);
}

async function loadSession(familyId: string, doc: PrivateLibreDoc, secret: string): Promise<Session | null> {
  if (!doc.sessionEnc) return null;
  try {
    const stored = decryptSession(doc.sessionEnc, secret);
    if (stored.expiresAt <= Date.now() + SESSION_REUSE_MARGIN_MS) return null;
    return { token: stored.token, accountId: stored.accountId, region: stored.region };
  } catch (err) {
    logger.warn("no se pudo descifrar la sesión de LibreLinkUp guardada", { familyId, err });
    return null;
  }
}

async function loginAndPersist(
  familyId: string,
  creds: StoredCredentials,
  appVersion: string,
  secret: string,
): Promise<Session> {
  const result = await login(creds.email, creds.password, appVersion);
  const session: Session = { token: result.token, accountId: result.accountId, region: result.region };
  const sessionEnc = encryptSession(
    { token: result.token, expiresAt: result.expiresAtMs, accountId: result.accountId, region: result.region },
    secret,
  );
  await privateDocRef(familyId).set({ sessionEnc }, { merge: true });
  return session;
}

/** Sesión reutilizada si sigue vigente; si no, relogin. No hace red si no hay credenciales. */
async function getSession(
  familyId: string,
  doc: PrivateLibreDoc,
  appVersion: string,
  secret: string,
): Promise<{ session: Session; credentials: StoredCredentials }> {
  const credentials = decryptCredentials(doc.credentialsEnc!, secret);
  const cached = await loadSession(familyId, doc, secret);
  if (cached) return { session: cached, credentials };
  const session = await loginAndPersist(familyId, credentials, appVersion, secret);
  return { session, credentials };
}

/** Llama `fn(session)`, con **un** relogin y reintento si el servidor responde 401. */
async function withSession<T>(
  familyId: string,
  doc: PrivateLibreDoc,
  appVersion: string,
  secret: string,
  fn: (session: Session) => Promise<T>,
): Promise<T> {
  const { session, credentials } = await getSession(familyId, doc, appVersion, secret);
  try {
    return await fn(session);
  } catch (err) {
    if (!(err instanceof LibreUnauthorizedError)) throw err;
    const fresh = await loginAndPersist(familyId, credentials, appVersion, secret);
    return fn(fresh);
  }
}

async function updateLibreStatus(familyId: string, ok: boolean, lastError: string | null): Promise<void> {
  await getFirestore()
    .doc(`families/${familyId}`)
    .update({
      libreStatus: {
        ok,
        lastError,
        ...(ok ? { lastSuccessAt: Timestamp.now() } : {}),
      },
    });
}

function levelOf(valueMgDl: number, settings: Pick<ReminderSettings, "lowThreshold" | "highThreshold">): GlucoseInfo["level"] {
  if (valueMgDl < settings.lowThreshold) return "low";
  if (valueMgDl > settings.highThreshold) return "high";
  return "normal";
}

function closestWithinWindow(items: GlucoseItem[], targetMs: number, windowMs: number): GlucoseItem | null {
  let best: GlucoseItem | null = null;
  let bestDiff = Infinity;
  for (const item of items) {
    const diff = Math.abs(item.factoryTimestampMs - targetMs);
    if (diff <= windowMs && diff < bestDiff) {
      best = item;
      bestDiff = diff;
    }
  }
  return best;
}

function findConnection(connections: Connection[], patientId: string | undefined): Connection | null {
  if (!connections.length) return null;
  if (patientId) return connections.find((c) => c.patientId === patientId) ?? null;
  return connections[0];
}

/**
 * Busca la glucosa para un evento (`checkin`/`sos`, docs/04). Se usa `clientAtMs`
 * (no "ahora") como referencia: si la última lectura de `connections` ya está
 * lejos de ese momento, se busca en el historial (`graph`) un punto cercano
 * (± 10 min) — pensado para revisiones sincronizadas tarde (docs/05).
 */
export async function lookupGlucoseForEvent(
  familyId: string,
  clientAtMs: number,
  settings: Pick<ReminderSettings, "lowThreshold" | "highThreshold">,
  appVersion: string,
  secret: string,
): Promise<LibreLookupResult> {
  const snap = await privateDocRef(familyId).get();
  const doc = (snap.data() ?? {}) as PrivateLibreDoc;
  if (!doc.credentialsEnc) return { error: "not_configured" };

  try {
    const result = await withSession(familyId, doc, appVersion, secret, async (session) => {
      const connections = await fetchConnections(session, appVersion);
      const connection = findConnection(connections, doc.patientId);
      if (!connection) return { error: "no_data" as const };

      const latest = connection.glucose;
      if (latest && Math.abs(latest.factoryTimestampMs - clientAtMs) <= STALE_VS_CLIENT_AT_MS) {
        return { item: latest };
      }

      const graph = await fetchGraph(session, appVersion, connection.patientId);
      const matched = closestWithinWindow(graph, clientAtMs, GRAPH_MATCH_WINDOW_MS);
      if (matched) return { item: matched };
      return { error: "stale" as const };
    });

    if ("error" in result) {
      await updateLibreStatus(familyId, false, result.error ?? null);
      return { error: result.error };
    }

    await updateLibreStatus(familyId, true, null);
    return {
      glucose: {
        valueMgDl: result.item.valueMgDl,
        trend: result.item.trend,
        level: levelOf(result.item.valueMgDl, settings),
      },
    };
  } catch (err) {
    const code: LibreLookupErrorCode = err instanceof LibreApiError ? err.code : "network";
    logger.warn("fallo la consulta a LibreLinkUp", { familyId, code, err });
    await updateLibreStatus(familyId, false, code);
    return { error: code };
  }
}

/** `setLibreLinkUp` (docs/04): valida credenciales con un login real antes de guardarlas. */
export async function saveCredentials(
  familyId: string,
  email: string,
  password: string,
  patientId: string | undefined,
  appVersion: string,
  secret: string,
): Promise<{ ok: true }> {
  const result = await login(email, password, appVersion);
  const credentialsEnc = encryptCredentials({ email, password }, secret);
  const sessionEnc = encryptSession(
    { token: result.token, expiresAt: result.expiresAtMs, accountId: result.accountId, region: result.region },
    secret,
  );

  await privateDocRef(familyId).set({ credentialsEnc, sessionEnc, ...(patientId ? { patientId } : {}) });
  await getFirestore()
    .doc(`families/${familyId}`)
    .update({ libreConfigured: true, libreStatus: { ok: true, lastError: null, lastSuccessAt: Timestamp.now() } });
  return { ok: true };
}

/** `testLibreLinkUp`: fuerza una consulta de conexiones para confirmar que todo sigue andando. */
export async function testConnection(
  familyId: string,
  appVersion: string,
  secret: string,
): Promise<{ ok: boolean; error?: LibreLookupErrorCode; connections?: { patientId: string; name: string }[] }> {
  const snap = await privateDocRef(familyId).get();
  const doc = (snap.data() ?? {}) as PrivateLibreDoc;
  if (!doc.credentialsEnc) return { ok: false, error: "not_configured" };

  try {
    const connections = await withSession(familyId, doc, appVersion, secret, (session) =>
      fetchConnections(session, appVersion),
    );
    await updateLibreStatus(familyId, true, null);
    return { ok: true, connections: connections.map((c) => ({ patientId: c.patientId, name: c.name })) };
  } catch (err) {
    const code: LibreLookupErrorCode = err instanceof LibreApiError ? err.code : "network";
    await updateLibreStatus(familyId, false, code);
    return { ok: false, error: code };
  }
}

export async function removeCredentials(familyId: string): Promise<{ ok: true }> {
  await privateDocRef(familyId).delete();
  await getFirestore()
    .doc(`families/${familyId}`)
    .update({ libreConfigured: false, libreStatus: { ok: false, lastError: null } });
  return { ok: true };
}
