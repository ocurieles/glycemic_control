import { HttpsError, onCall } from "firebase-functions/v2/https";
import { lluAppVersion, lluEncKey } from "./config";
import { LibreApiError } from "./libre/client";
import { removeCredentials, saveCredentials, testConnection } from "./libre/service";

/** Callables de LibreLinkUp (docs/04, docs/05). Las credenciales nunca viajan al cliente. */

function requireAuth(request: { auth?: { uid: string; token: Record<string, unknown> } | null }) {
  if (!request.auth) throw new HttpsError("unauthenticated", "Debes iniciar sesión.");
  const familyId = request.auth.token.familyId as string | undefined;
  const role = request.auth.token.role as string | undefined;
  if (!familyId || role !== "parent") {
    throw new HttpsError("permission-denied", "Solo un padre puede configurar LibreLinkUp.");
  }
  return familyId;
}

function toHttpsError(err: unknown): HttpsError {
  if (err instanceof LibreApiError) {
    const messages: Record<string, string> = {
      auth: "Usuario o clave incorrectos.",
      terms: "Abre LibreLinkUp y acepta los términos nuevos.",
      version: "La integración necesita actualización.",
      network: "LibreLinkUp no responde, intenta más tarde.",
    };
    return new HttpsError("failed-precondition", messages[err.code] ?? err.message);
  }
  return new HttpsError("internal", "No se pudo conectar con LibreLinkUp.");
}

export const setLibreLinkUp = onCall<{ email: string; password: string; patientId?: string }>(
  { secrets: [lluEncKey] },
  async (request) => {
    const familyId = requireAuth(request);
    const { email, password, patientId } = request.data ?? {};
    if (!email?.trim() || !password) {
      throw new HttpsError("invalid-argument", "Falta el correo o la clave de LibreLinkUp.");
    }
    try {
      return await saveCredentials(familyId, email.trim(), password, patientId, lluAppVersion.value(), lluEncKey.value());
    } catch (err) {
      throw toHttpsError(err);
    }
  },
);

export const testLibreLinkUp = onCall({ secrets: [lluEncKey] }, async (request) => {
  const familyId = requireAuth(request);
  const result = await testConnection(familyId, lluAppVersion.value(), lluEncKey.value());
  if (!result.ok) {
    const messages: Record<string, string> = {
      auth: "Usuario o clave incorrectos.",
      terms: "Abre LibreLinkUp y acepta los términos nuevos.",
      version: "La integración necesita actualización.",
      network: "LibreLinkUp no responde, intenta más tarde.",
      not_configured: "Todavía no conectaste LibreLinkUp.",
    };
    throw new HttpsError("failed-precondition", messages[result.error ?? "network"] ?? "No se pudo conectar.");
  }
  return result;
});

export const removeLibreLinkUp = onCall({ secrets: [lluEncKey] }, async (request) => {
  const familyId = requireAuth(request);
  return removeCredentials(familyId);
});
