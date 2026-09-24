import { createHash } from "node:crypto";
import { DateTime } from "luxon";

/**
 * Cliente HTTP crudo de la API no oficial de LibreLinkUp (docs/05). Sin
 * dependencias de Firebase: solo `fetch` + parseo. El manejo de sesión,
 * caché y reintento va en `service.ts`.
 */

const DEFAULT_BASE_URL = "https://api.libreview.io";
const REQUEST_TIMEOUT_MS = 8_000;

export type LibreErrorCode = "auth" | "terms" | "version" | "network";

export class LibreApiError extends Error {
  constructor(
    public readonly code: LibreErrorCode,
    message: string,
  ) {
    super(message);
    this.name = "LibreApiError";
  }
}

function baseHeaders(appVersion: string): Record<string, string> {
  return {
    "accept-encoding": "gzip",
    "cache-control": "no-cache",
    connection: "Keep-Alive",
    "content-type": "application/json",
    product: "llu.android",
    version: appVersion,
  };
}

interface LoginTicket {
  token: string;
  expires: number; // epoch seconds
}

interface LoginSuccessData {
  authTicket: LoginTicket;
  user: { id: string };
}

interface LoginRedirectData {
  redirect: true;
  region: string;
}

interface LoginResponseBody {
  status: number;
  data?: LoginSuccessData | LoginRedirectData | Record<string, unknown>;
  error?: { message?: string };
}

export interface LoginResult {
  token: string;
  expiresAtMs: number;
  accountId: string;
  region: string | null;
}

function accountIdOf(userId: string): string {
  return createHash("sha256").update(userId).digest("hex");
}

async function postLogin(
  baseUrl: string,
  email: string,
  password: string,
  appVersion: string,
): Promise<LoginResponseBody> {
  let response: Response;
  try {
    response = await fetch(`${baseUrl}/llu/auth/login`, {
      method: "POST",
      headers: baseHeaders(appVersion),
      body: JSON.stringify({ email, password }),
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
  } catch (err) {
    throw new LibreApiError("network", `No se pudo contactar a LibreLinkUp: ${(err as Error).message}`);
  }

  if (response.status === 403) {
    throw new LibreApiError("version", "La versión de la app de LibreLinkUp usada por la integración es muy vieja.");
  }
  if (!response.ok) {
    throw new LibreApiError("network", `LibreLinkUp respondió HTTP ${response.status}.`);
  }

  return (await response.json()) as LoginResponseBody;
}

/** Login con seguimiento de redirección regional (docs/05 "Login"). */
export async function login(email: string, password: string, appVersion: string): Promise<LoginResult> {
  let body = await postLogin(DEFAULT_BASE_URL, email, password, appVersion);
  let region: string | null = null;

  if (body.status === 0 && body.data && "redirect" in body.data && body.data.redirect) {
    region = (body.data as LoginRedirectData).region;
    body = await postLogin(`https://api-${region}.libreview.io`, email, password, appVersion);
  }

  if (body.status === 2) {
    throw new LibreApiError("auth", "Usuario o clave incorrectos.");
  }
  if (body.status === 4) {
    throw new LibreApiError("terms", "Hay que aceptar términos nuevos en la app oficial de LibreLinkUp.");
  }
  if (body.status !== 0 || !body.data || !("authTicket" in body.data)) {
    throw new LibreApiError("network", `Respuesta inesperada de LibreLinkUp (status ${body.status}).`);
  }

  const data = body.data as LoginSuccessData;
  return {
    token: data.authTicket.token,
    expiresAtMs: data.authTicket.expires * 1000,
    accountId: accountIdOf(data.user.id),
    region,
  };
}

export interface Session {
  token: string;
  accountId: string;
  region: string | null;
}

function baseUrlFor(session: Session): string {
  return session.region ? `https://api-${session.region}.libreview.io` : DEFAULT_BASE_URL;
}

function authHeaders(session: Session, appVersion: string): Record<string, string> {
  return {
    ...baseHeaders(appVersion),
    Authorization: `Bearer ${session.token}`,
    "Account-Id": session.accountId,
  };
}

/** Lanzado cuando el servidor responde 401: el llamador debe reloguear y reintentar una vez. */
export class LibreUnauthorizedError extends Error {
  constructor() {
    super("Sesión de LibreLinkUp vencida.");
    this.name = "LibreUnauthorizedError";
  }
}

async function authedGet(session: Session, appVersion: string, path: string): Promise<unknown> {
  let response: Response;
  try {
    response = await fetch(`${baseUrlFor(session)}${path}`, {
      headers: authHeaders(session, appVersion),
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
  } catch (err) {
    throw new LibreApiError("network", `No se pudo contactar a LibreLinkUp: ${(err as Error).message}`);
  }

  if (response.status === 401) throw new LibreUnauthorizedError();
  if (!response.ok) throw new LibreApiError("network", `LibreLinkUp respondió HTTP ${response.status}.`);
  return response.json();
}

export interface GlucoseItem {
  valueMgDl: number;
  trend?: number;
  factoryTimestampMs: number;
}

interface RawGlucoseMeasurement {
  ValueInMgPerDl: number;
  TrendArrow?: number | null;
  FactoryTimestamp: string;
}

interface RawConnection {
  patientId: string;
  firstName?: string;
  lastName?: string;
  glucoseMeasurement?: RawGlucoseMeasurement | null;
}

export interface Connection {
  patientId: string;
  name: string;
  glucose: GlucoseItem | null;
}

/** `FactoryTimestamp` viene en UTC pero con formato `M/D/YYYY h:mm:ss AM|PM` (docs/05). */
function parseFactoryTimestamp(raw: string): number {
  return DateTime.fromFormat(raw, "M/d/yyyy h:mm:ss a", { zone: "utc" }).toMillis();
}

function toGlucoseItem(raw: RawGlucoseMeasurement | null | undefined): GlucoseItem | null {
  if (!raw) return null;
  return {
    valueMgDl: raw.ValueInMgPerDl,
    trend: raw.TrendArrow ?? undefined,
    factoryTimestampMs: parseFactoryTimestamp(raw.FactoryTimestamp),
  };
}

export async function fetchConnections(session: Session, appVersion: string): Promise<Connection[]> {
  const body = (await authedGet(session, appVersion, "/llu/connections")) as { data: RawConnection[] };
  return (body.data ?? []).map((c) => ({
    patientId: c.patientId,
    name: [c.firstName, c.lastName].filter(Boolean).join(" ") || "—",
    glucose: toGlucoseItem(c.glucoseMeasurement),
  }));
}

export async function fetchGraph(session: Session, appVersion: string, patientId: string): Promise<GlucoseItem[]> {
  const body = (await authedGet(session, appVersion, `/llu/connections/${patientId}/graph`)) as {
    data: { graphData: RawGlucoseMeasurement[] };
  };
  return (body.data?.graphData ?? []).map((g) => toGlucoseItem(g)!).filter(Boolean);
}
