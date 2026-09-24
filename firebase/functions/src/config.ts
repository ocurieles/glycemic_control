import { setGlobalOptions } from "firebase-functions/v2";
import { defineSecret, defineString } from "firebase-functions/params";

// docs/02 D3, docs/04 "Configuración global".
setGlobalOptions({ region: "us-east1", maxInstances: 3, memory: "256MiB" });

// docs/05: clave de cifrado AES-256-GCM para las credenciales de LibreLinkUp.
export const lluEncKey = defineSecret("LLU_ENC_KEY");

// docs/05: versión mínima que exige la API de LibreLinkUp. Se sube cuando
// Abbott cambia la mínima soportada (ver docs/10 "Mantenimiento").
export const lluAppVersion = defineString("LLU_APP_VERSION", { default: "4.16.0" });
