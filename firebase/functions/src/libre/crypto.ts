import { createCipheriv, createDecipheriv, createHash, randomBytes } from "node:crypto";

/**
 * Cifrado de credenciales de LibreLinkUp (docs/05 "Cifrado"). AES-256-GCM,
 * clave = sha256(LLU_ENC_KEY), IV aleatorio de 12 bytes. Formato guardado:
 * base64(iv ‖ authTag ‖ ciphertext).
 */

function deriveKey(secret: string): Buffer {
  return createHash("sha256").update(secret).digest();
}

export function encrypt(plaintext: string, secret: string): string {
  const key = deriveKey(secret);
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", key, iv);
  const ciphertext = Buffer.concat([cipher.update(plaintext, "utf8"), cipher.final()]);
  const authTag = cipher.getAuthTag();
  return Buffer.concat([iv, authTag, ciphertext]).toString("base64");
}

/** Lanza si la clave no coincide (secreto rotado): el llamador debe pedir reconexión. */
export function decrypt(payload: string, secret: string): string {
  const key = deriveKey(secret);
  const raw = Buffer.from(payload, "base64");
  const iv = raw.subarray(0, 12);
  const authTag = raw.subarray(12, 28);
  const ciphertext = raw.subarray(28);
  const decipher = createDecipheriv("aes-256-gcm", key, iv);
  decipher.setAuthTag(authTag);
  return Buffer.concat([decipher.update(ciphertext), decipher.final()]).toString("utf8");
}

export interface StoredCredentials {
  email: string;
  password: string;
}

export function encryptCredentials(creds: StoredCredentials, secret: string): string {
  return encrypt(JSON.stringify(creds), secret);
}

export function decryptCredentials(payload: string, secret: string): StoredCredentials {
  return JSON.parse(decrypt(payload, secret)) as StoredCredentials;
}

export interface StoredSession {
  token: string;
  expiresAt: number; // epoch ms
  accountId: string;
  region: string | null;
}

export function encryptSession(session: StoredSession, secret: string): string {
  return encrypt(JSON.stringify(session), secret);
}

export function decryptSession(payload: string, secret: string): StoredSession {
  return JSON.parse(decrypt(payload, secret)) as StoredSession;
}
