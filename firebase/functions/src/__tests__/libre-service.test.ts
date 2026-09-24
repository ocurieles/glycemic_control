import { getApps, initializeApp } from "firebase-admin/app";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { removeCredentials, saveCredentials, testConnection } from "../libre/service";

/**
 * `libre/service.ts` contra el emulador de Firestore real, con `fetch` mockeado
 * (nunca contra la API real de LibreLinkUp — no hay credenciales de prueba, ver
 * docs/08 F7 "Listo cuando"). Corre con `test:integration` (ver CLAUDE.md).
 */

const canRun = !!process.env.FIRESTORE_EMULATOR_HOST;
if (!canRun) {
  console.warn("libre-service.test.ts se saltea: necesita el emulador de Firestore (ver CLAUDE.md).");
}

const projectId = process.env.GCLOUD_PROJECT ?? "demo-checkin-rules";
if (!getApps().length) initializeApp({ projectId });
const db = getFirestore();

const SECRET = "test-secret-key";
const APP_VERSION = "4.16.0";

function jsonResponse(status: number, body: unknown): Response {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as Response;
}

function loginResponse(token = "tok-1") {
  return jsonResponse(200, {
    status: 0,
    data: { authTicket: { token, expires: 1_800_000_000 }, user: { id: "user-1" } },
  });
}

describe.runIf(canRun)("libre/service (integración con el emulador)", () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("saveCredentials valida con un login real, cifra y guarda; testConnection reutiliza la sesión", async () => {
    const familyId = `fam-libre-${Date.now()}`;
    await db.doc(`families/${familyId}`).set({ childName: "Cesar", createdAt: Timestamp.now() });

    fetchMock.mockResolvedValueOnce(loginResponse());
    await saveCredentials(familyId, "a@b.com", "secret", undefined, APP_VERSION, SECRET);

    const familyAfterSave = await db.doc(`families/${familyId}`).get();
    expect(familyAfterSave.get("libreConfigured")).toBe(true);
    expect(familyAfterSave.get("libreStatus").ok).toBe(true);

    const privateDoc = await db.doc(`families/${familyId}/private/libre`).get();
    expect(privateDoc.get("credentialsEnc")).not.toContain("secret"); // está cifrado, no en claro

    // testConnection reutiliza la sesión ya guardada: no vuelve a loguear.
    fetchMock.mockResolvedValueOnce(
      jsonResponse(200, {
        data: [{ patientId: "p1", firstName: "Cesar", glucoseMeasurement: null }],
      }),
    );
    fetchMock.mockClear();
    const result = await testConnection(familyId, APP_VERSION, SECRET);

    expect(result.ok).toBe(true);
    expect(result.connections).toEqual([{ patientId: "p1", name: "Cesar" }]);
    expect(fetchMock).toHaveBeenCalledTimes(1); // solo /connections, no /auth/login otra vez
  });

  it("relogea una vez ante un 401 y reintenta la llamada", async () => {
    const familyId = `fam-libre-401-${Date.now()}`;
    await db.doc(`families/${familyId}`).set({ childName: "Cesar", createdAt: Timestamp.now() });

    fetchMock.mockResolvedValueOnce(loginResponse("tok-viejo"));
    await saveCredentials(familyId, "a@b.com", "secret", undefined, APP_VERSION, SECRET);

    fetchMock
      .mockResolvedValueOnce(jsonResponse(401, {})) // sesión guardada, pero el servidor la rechaza
      .mockResolvedValueOnce(loginResponse("tok-nuevo")) // relogin
      .mockResolvedValueOnce(jsonResponse(200, { data: [] })); // reintento con el token nuevo
    fetchMock.mockClear();

    const result = await testConnection(familyId, APP_VERSION, SECRET);

    expect(result.ok).toBe(true);
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it("credenciales inválidas: saveCredentials propaga el error y no guarda nada", async () => {
    const familyId = `fam-libre-bad-${Date.now()}`;
    await db.doc(`families/${familyId}`).set({ childName: "Cesar", createdAt: Timestamp.now() });

    fetchMock.mockResolvedValueOnce(jsonResponse(200, { status: 2, error: { message: "notAuthenticated" } }));

    await expect(saveCredentials(familyId, "a@b.com", "wrong", undefined, APP_VERSION, SECRET)).rejects.toMatchObject(
      { code: "auth" },
    );

    const privateDoc = await db.doc(`families/${familyId}/private/libre`).get();
    expect(privateDoc.exists).toBe(false);
  });

  it("removeCredentials borra el documento privado y desmarca libreConfigured", async () => {
    const familyId = `fam-libre-remove-${Date.now()}`;
    await db.doc(`families/${familyId}`).set({ childName: "Cesar", createdAt: Timestamp.now() });

    fetchMock.mockResolvedValueOnce(loginResponse());
    await saveCredentials(familyId, "a@b.com", "secret", undefined, APP_VERSION, SECRET);

    await removeCredentials(familyId);

    const privateDoc = await db.doc(`families/${familyId}/private/libre`).get();
    expect(privateDoc.exists).toBe(false);
    const family = await db.doc(`families/${familyId}`).get();
    expect(family.get("libreConfigured")).toBe(false);
  });
});
