# 04 — Backend (Cloud Functions v2, TypeScript)

## Configuración global
- Runtime: el Node LTS más reciente que soporte Cloud Functions (verificar en la documentación de Firebase).
- `firebase-functions` (API v2) y `firebase-admin`, en su última versión estable.
- `setGlobalOptions({ region: "us-east1", maxInstances: 3, memory: "256MiB" })`.
- Secreto: `LLU_ENC_KEY` (`defineSecret`). Parámetro: `LLU_APP_VERSION` (`defineString`, default `"4.16.0"`; ver 05).
- Todas las callables exigen `request.auth` y leen `familyId` y `role` de `request.auth.token`, salvo `createFamily` y `joinFamily`, que los asignan.
- Errores: `HttpsError` con códigos estándar y mensajes en español (se muestran en la UI).
- Logs estructurados (`logger.info({ familyId, eventId, ... })`), **sin** credenciales, tokens ni valores de glucosa en texto plano innecesario.

## Callables

### `createFamily({ childName, parentName }) → { familyId, code, expiresAt }`
1. Si el uid ya tiene `familyId`, responde `failed-precondition`.
2. Crea `families/{id}` con los settings por defecto y `parents = { uid: { name } }`.
3. Crea `users/{uid}` con rol `parent`.
4. `setCustomUserClaims(uid, { familyId, role: "parent" })`.
5. Genera el código de vinculación (ver `createPairingCode`).

El cliente debe hacer `getIdToken(true)` después de esta llamada y de `joinFamily`, para recibir los claims.

### `createPairingCode() → { code, expiresAt }` (padre)
Código de 6 dígitos con `crypto.randomInt`, único y con vigencia de 30 minutos. Borra los códigos anteriores de la misma familia.

### `joinFamily({ code, role, displayName }) → { familyId, childName, role }`
- Rate limit: máximo 10 intentos por uid por hora (`rateLimits/{uid}`); si se excede, `resource-exhausted`.
- Código inexistente o vencido: `not-found` ("Código inválido o vencido").
- `role = "child"`: actualiza `families.childUid = uid` y establece `displayName = childName`. Si había otro teléfono del niño: borra su `users` doc, quita sus claims y llama a `revokeRefreshTokens(oldUid)`.
- `role = "parent"`: agrega el uid al mapa `parents`.
- Crea o actualiza `users/{uid}` y asigna los custom claims.
- Todo se hace dentro de una transacción.

### `leaveFamily()`
Quita al uid de la familia y borra sus claims y su `users` doc. Si era el último padre, no se permite.

### `setLibreLinkUp({ email, password, patientId? }) → { patients: [{ id, name }], selected, reading? }` (padre)
1. Hace login contra LibreLinkUp (ver 05). Si falla, responde `invalid-argument` con el motivo (`auth`, `version`, `terms`, etc.).
2. Obtiene las conexiones. Si no hay ninguna, responde `failed-precondition` ("Esta cuenta no sigue a ningún paciente").
3. Selección: el `patientId` recibido, o la única conexión, o la que coincida con `childName`. Si hay ambigüedad, devuelve la lista sin guardar y la UI pide elegir.
4. Guarda `private/libre` cifrado y pone `libreConfigured = true`.
5. Devuelve la lectura actual como prueba.

### `testLibreLinkUp() → { reading }` y `removeLibreLinkUp()` (padre)

## Triggers

### `onEventCreated` — `families/{fid}/events/{eid}` (onDocumentCreated)
Es idempotente: si `processedAt` existe, sale. Al terminar escribe `processedAt` y `senderName`.
La **hora real** del evento es `realAt = min(clientAt, createdAt)` (el cliente ya corrige `clientAt` con su offset, ver 07). Se escribe en el evento y se usa en **todo** el cálculo: slots, `syncedLate`, glucosa, `lastCheckinAt`, fecha del día y textos. Si `checkin_late` llega en ráfaga (más de 3 en menos de 1 min), se agrupa en un solo push (ver 07).

| `type` | Acciones |
|---|---|
| `checkin` | 1) `realAt` y `syncedLate = createdAt − realAt > 120 s`. 2) Glucosa: si **no** es `syncedLate`, usa la última lectura; si es `syncedLate`, busca en el **historial** (graph) el punto más cercano a `realAt` (±10 min); si no hay, deja `glucoseError: "stale"`. Timeout total de LibreLinkUp: 8 s. 3) Actualiza el evento y `families.lastCheckinAt = max(actual, realAt)`. 4) `recomputeDay(fid, fecha(realAt))`. 5) Push a los padres `checkin` (o `checkin_late` si `syncedLate`). |
| `sos` | 0) `realAt` y `syncedLate` (igual que en checkin). 1) Push inmediato a los padres `sos` con la hora `realAt`, **antes** de LibreLinkUp. 2) Intenta obtener la glucosa (5 s); si la obtiene, actualiza el evento y envía un push `sos_glucose`. |
| `parent_message` | Push al niño `parent_message` con `{ text, from }`. |
| `sos_ack` | Push al niño `sos_ack` con `{ from, text }` y push a los **otros** padres `sos_ack_info` ("Mamá respondió: Voy en camino"). |

Si un SOS llega con `syncedLate` (se creó sin red), el push lo indica: "SOS enviado a las 10:12 (llegó 10:30, sin conexión)". Si `smsSent`, lo menciona.

### `onFamilyUpdated` — `families/{fid}` (onDocumentUpdated)
Si cambió `settings` (comparación profunda), envía un push `sync` al niño y `recomputeDay(fid, hoy)`.

## Scheduler

### `checkMissedSlots` — `every 5 minutes`, zona `America/Caracas`
Para cada familia con `settings.enabled == true` y `childUid`:
1. Calcula los slots de hoy en la zona de la familia.
2. `recomputeDay(fid, hoy)`.
3. Para cada slot `missed` que no esté en `missedAlerted`, y con `t ≥ ahora − 60 min` (no alertar lo muy viejo tras una caída del servicio):
   - push a los padres `missed`,
   - push al niño `nudge`,
   - lo agrega a `missedAlerted` (con `arrayUnion`, en una transacción).
4. Si es la primera corrida después de `endTime`, envía a los padres el push `day_summary` ("Hoy: 33/36 a tiempo, 2 tarde, 1 sin respuesta"). Se marca con `days/{fecha}.summarySent = true`.

## `computeDay` y `recomputeDay`
- `computeDay(settings, checkins[], now) → DayDoc` es **pura**, vive en `compliance.ts` y usa `schedule.ts`.
- `recomputeDay(fid, date)` hace el I/O (en `events.ts`/`missed.ts`): lee las revisiones del día con `realAt` dentro de la fecha local, llama a `computeDay` y escribe `days/{fecha}` preservando `missedAlerted` y `summarySent`.

## Mensajería (`messaging.ts`)
- `sendToParents(fid, payload, excludeUid?)`: usa las claves de `families.parents` para leer `users/{uid}.fcmToken`. `sendToChild(fid, payload)`: usa **solo** `families.childUid`. Ambos envían con `sendEachForMulticast`.
- Si FCM responde `registration-token-not-registered` o `invalid-argument`, se borra `fcmToken` de ese usuario.
- Todos los mensajes son **data-only** con `android: { priority: "high", ttl: <según tipo> }`.

### Payloads (todas las claves y valores son strings)
| `type` | Destino | Campos | TTL | Canal en la app |
|---|---|---|---|---|
| `checkin` | padres | `title, body, eventId, level?` | 1 h | `parent_checkin` (`parent_alert` si `level` ≠ normal) |
| `checkin_late` | padres | `title, body, eventId` | 1 h | `parent_checkin` |
| `missed` | padres | `title, body, slot` | 30 min | `parent_alert` |
| `sos` | padres | `title, body, eventId, lat?, lng?` | 24 h | `parent_sos` |
| `sos_glucose` | padres | `title, body, eventId` | 1 h | `parent_sos` |
| `sos_ack_info` | padres (otros) | `title, body` | 1 h | `parent_alert` |
| `day_summary` | padres | `title, body, date` | 12 h | `parent_checkin` |
| `parent_message` | niño | `text, from, eventId, at` (epoch ms de `realAt`) | 30 min | `child_message` |
| `sos_ack` | niño | `text, from, at` | 1 h | `child_message` |
| `nudge` | niño | `slot` | 5 min | `child_reminder` |
| `sync` | niño | — | 1 h | (sin notificación) |

**Textos para los padres** (el nombre sale de `childName`; la hora se formatea en `es-VE` y la zona de la familia):
- checkin: `"{Cesar} se revisó ✓"` / `"10:40 a. m. · 128 mg/dL ↗"`; si falta el valor: `"10:40 a. m."`
- level low: `"⚠️ {Cesar} se revisó — BAJA"` / `"10:40 a. m. · 62 mg/dL ↘"`
- checkin_late: `"{Cesar} se revisó (sin conexión)"` / `"A las 10:40 a. m. · sincronizado 11:05 a. m."`
- missed: `"{Cesar} no ha confirmado"` / `"Recordatorio de las 10:40 a. m. (puede estar sin conexión)"`
- sos: `"🆘 {Cesar} necesita ayuda"` / `"10:42 a. m. · Toca para ver ubicación"`

Flechas de tendencia de LibreLinkUp: `1 ↓`, `2 ↘`, `3 →`, `4 ↗`, `5 ↑`.

**Textos para el niño**: el servidor no manda títulos. La app usa títulos neutros (ver 06).

## Pruebas del backend
- Unitarias (`vitest` o `node:test`): `schedule.ts` con **todos** los vectores de 03 §2.5, `recomputeDay`, formato de mensajes, cliente de LibreLinkUp con `fetch` mockeado (login, redirección, 401 → relogin, versión mínima) y `crypto` (ida y vuelta, y fallo si se altera el texto cifrado).
- Emulador: reglas (`@firebase/rules-unit-testing`) y triggers con Firestore Emulator.
