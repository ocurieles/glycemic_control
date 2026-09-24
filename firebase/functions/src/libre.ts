import { HttpsError, onCall } from "firebase-functions/v2/https";

/**
 * Stubs de LibreLinkUp (docs/05): la integración real llega en F7. Por ahora
 * devuelven `unimplemented` para que la UI de F5 (Ajustes → LibreLinkUp) tenga
 * algo real que llamar y mostrar (docs/08 F5: "pueden devolver not-implemented
 * hasta F7").
 */

function requireAuth(request: { auth?: { uid: string } | null }) {
  if (!request.auth) throw new HttpsError("unauthenticated", "Debes iniciar sesión.");
}

export const setLibreLinkUp = onCall<{ email: string; password: string; patientId?: string }>((request) => {
  requireAuth(request);
  throw new HttpsError("unimplemented", "La integración con LibreLinkUp todavía no está lista.");
});

export const testLibreLinkUp = onCall((request) => {
  requireAuth(request);
  throw new HttpsError("unimplemented", "La integración con LibreLinkUp todavía no está lista.");
});

export const removeLibreLinkUp = onCall((request) => {
  requireAuth(request);
  throw new HttpsError("unimplemented", "La integración con LibreLinkUp todavía no está lista.");
});
