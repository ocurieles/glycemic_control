# 03 — Modelo de datos y reglas de slots

## 1. Settings de recordatorios (`ReminderSettings`)

Se guardan en `families/{familyId}.settings` y el niño los cachea localmente.

| Campo | Tipo | Default | Validación |
|---|---|---|---|
| `enabled` | bool | `true` | — |
| `intervalMinutes` | int | `10` | 5–240 |
| `days` | int[] | `[1,2,3,4,5]` | ISO: 1 = lunes … 7 = domingo, sin repetir, no vacío |
| `startTime` | string | `"07:00"` | `HH:mm` 24 h |
| `endTime` | string | `"13:00"` | `HH:mm`, > `startTime` |
| `escalationMinutes` | int | `5` | 1–60 |
| `nudgeMinutes` | int | `3` | 0–30 (0 = sin refuerzo) |
| `timezone` | string | `"America/Caracas"` | IANA |
| `lowThreshold` | int | `70` | 40–120 mg/dL |
| `highThreshold` | int | `180` | 120–400 mg/dL |
| `smsFallbackEnabled` | bool | `false` | — |
| `smsNumbers` | string[] | `[]` | E.164, máximo 3 |
| `childPhone` | string? | `null` | E.164; para "Llamar a Cesar" |

## 2. Reglas de slots (CONTRATO: idéntico en Kotlin y TypeScript)

Esta es la lógica más delicada del sistema. Se implementa dos veces, en `ReminderSchedule.kt` y en `schedule.ts`, y **ambas implementaciones deben pasar la misma tabla de vectores de prueba** (sección 2.5).

### 2.1 Slots de un día
Para una fecha local `D` en `timezone`:
- Si `!enabled` o el día ISO de `D` no está en `days`, no hay slots.
- Si no, los slots son `t_k = D@startTime + k·interval` para `k = 0, 1, 2, …` **mientras `t_k < D@endTime`** (el fin es exclusivo).

Ejemplo: 07:00–13:00 cada 10 → 07:00, 07:10, …, 12:50 (36 slots).

### 2.2 Próximo slot (para programar la alarma)
`nextSlot(now)` es el primer slot con `t > now` (estrictamente), buscando desde hoy hasta 7 días adelante. Si no hay ninguno, devuelve `null`.

### 2.3 Asignación de una revisión a un slot
**Hora real:** `c = realAt = min(clientAt, createdAt)`. El cliente ya envía `clientAt` corregido con su `clockOffsetMs` conocido (ver 07), y el `min` evita horas futuras si el reloj va adelantado. Cuando el evento todavía no llegó al servidor (en el dispositivo), `c = clientAt`.

Una revisión con hora real `c` se asigna al **slot más cercano** `t` del mismo día local, siempre que `|c − t| ≤ interval/2`. En caso de empate gana el slot **anterior**. Si ningún slot cumple la condición, la revisión es **extra**: aparece en la línea de tiempo pero no cuenta para el cumplimiento. Si varias revisiones caen en el mismo slot, cuenta la primera (menor `c`).

### 2.4 Estado de un slot (evaluado en `now`, en este orden)
Solo cuentan las revisiones con `c ≤ now`.

| Orden | Estado | Condición |
|---|---|---|
| 1 | `on_time` | tiene revisión asignada con `c ≤ t + escalation` (incluye revisiones anticipadas, aunque `t > now`) |
| 2 | `late` | tiene revisión asignada con `c > t + escalation` |
| 3 | `upcoming` | sin revisión y `t > now` |
| 4 | `pending` | sin revisión y `now < t + escalation` |
| 5 | `missed` | sin revisión y `now ≥ t + escalation` |

Además, en `on_time` y `late` se marca **`syncedLate = true`** si `createdAt − realAt > 120 s` (llegó al servidor tarde, por falta de conexión). Un slot `missed` **puede cambiar** a `on_time` o `late` cuando se sincroniza una revisión atrasada.

Consecuencias en el dispositivo:
- Cuando suena la alarma del slot `t`, se **omite** la notificación si ya existe en el outbox local una revisión asignada a `t` (por ejemplo, se revisó 3 minutos antes por su cuenta).
- El refuerzo (`nudge`) del slot `t` se omite si ya hay revisión asignada a `t`.

Consecuencia en el servidor:
- Aviso de **sin respuesta** para el slot `t` cuando pasa a `missed` y `t` no está en `days/{fecha}.missedAlerted`. Se envía una sola vez por slot.

### 2.5 Vectores de prueba
La versión ejecutable está en **`docs/schedule-vectors.json`**, que es la fuente única para los tests del backend y de Android y fue verificada con una implementación de referencia. En `assign`, el `expectedStatus` se evalúa con `now = clientAt + 1 min`. Settings base: `interval=10, 07:00–13:00, días=[1..5], escalation=5, tz=America/Caracas`. El 2026-09-21 es lunes.

| # | Entrada | Esperado |
|---|---|---|
| V1 | `nextSlot(lun 06:55)` | lun 07:00 |
| V2 | `nextSlot(lun 07:00:00)` | lun 07:10 |
| V3 | `nextSlot(lun 12:55)` | mar 07:00 |
| V4 | `nextSlot(vie 13:30)` | lun 07:00 |
| V5 | `nextSlot(sáb 09:00)` | lun 07:00 |
| V6 | `nextSlot` con `enabled=false` | `null` |
| V7 | revisión 07:04 | slot 07:00, `on_time` |
| V8 | revisión 06:57 | slot 07:00, `on_time` |
| V9 | revisión 06:54 | extra |
| V10 | revisión 07:05 (empate 07:00/07:10) | slot 07:00, `on_time` |
| V11 | revisión 07:07 | slot 07:10, `on_time` (el slot 07:00 queda `missed`) |
| V12 | revisión 12:58 | extra (\|12:58 − 12:50\| = 8 > interval/2 = 5; 13:00 no es slot porque el fin es exclusivo) |
| V13 | sin revisiones, `now=07:04` | slot 07:00 `pending` |
| V14 | sin revisiones, `now=07:05` | slot 07:00 `missed` |
| V15 | `interval=30`, revisión 07:12 | slot 07:00, `late` |
| V16 | `interval=30`, revisión 07:15 (empate) | slot 07:00, `late` |
| V17 | `interval=30`, revisión 07:16 | slot 07:30, `on_time` |
| V18 | revisión 07:03 con `createdAt=07:40` | slot 07:00, `on_time`, `syncedLate=true` |
| V19 | slot 07:00 `missed` a las 07:20 y luego llega V18 | slot 07:00 → `on_time`, `syncedLate=true` |
| V20 | `startTime=07:00`, `endTime=07:25`, `interval=10` | slots 07:00, 07:10, 07:20 |
| V11b | como V11, estado del slot 07:00 a las 07:08 | `missed` |
| V20b | día completo base | 36 slots, 07:00 … 12:50 |

> La tabla es un resumen; los tests deben correr **todos** los casos de `schedule-vectors.json`.

## 3. Firestore

### `users/{uid}`
| Campo | Escribe | Notas |
|---|---|---|
| `familyId` | Functions | |
| `role` | Functions | `parent` \| `child` |
| `displayName` | Functions | "Mamá", "Papá", "Cesar" |
| `fcmToken` | cliente (propio) | |
| `tokenUpdatedAt` | cliente (propio) | `serverTimestamp` |
| `appVersion` | cliente (propio) | |

### `families/{familyId}`
| Campo | Escribe |
|---|---|
| `childName`, `childUid` | Functions (`childName` también lo edita un padre) |
| `parents` | Functions — mapa `uid → { name }` |
| `settings` | padre (reglas validan) |
| `libreConfigured` | Functions |
| `libreStatus` | Functions — `{ ok, lastError?, lastSuccessAt? }` |
| `lastCheckinAt` | Functions — máximo `realAt` de las revisiones |
| `createdAt` | Functions |

### `families/{familyId}/events/{eventId}`
El `eventId` es un **UUID v4 generado por el cliente**.

| Campo | Escribe | Notas |
|---|---|---|
| `type` | cliente | `checkin`, `sos`, `insulin_dose` (niño); `parent_message`, `sos_ack` (padre) |
| `createdBy` | cliente | = `request.auth.uid` |
| `createdAt` | cliente | `serverTimestamp()`; las reglas exigen `== request.time` |
| `clientAt` | cliente | Timestamp del momento real del toque, **ya corregido** con `clockOffsetMs` |
| `clockOffsetMs` | cliente | opcional, informativo: la corrección aplicada (`int`, \|valor\| ≤ 7 días) |
| `realAt` | Functions | `min(clientAt, createdAt)`: la hora que usan slots, cumplimiento y textos |
| `source` | cliente | `app` \| `notification` |
| `text` | cliente | mensajes; máximo 120 caracteres |
| `location` | cliente | `{ lat, lng, accuracyM }` (solo en SOS) |
| `smsSent` | cliente | SOS: se envió SMS de respaldo |
| `replyTo` | cliente | `sos_ack`: id del SOS |
| `doseUnits` | cliente | `insulin_dose`: uno de `0.5, 1, 1.5, 2, 2.5, 3` (validado en las reglas) |
| `senderName` | Functions | |
| `syncedLate` | Functions | `createdAt − realAt > 120 s` |
| `glucose` | Functions | `{ valueMgDl, trend (1–5), readingAt, source: "latest"\|"graph", level: "low"\|"normal"\|"high" }` |
| `glucoseError` | Functions | `not_configured`, `auth`, `terms`, `version`, `network`, `no_data`, `stale` |
| `processedAt` | Functions | marca de idempotencia |

### `families/{familyId}/days/{yyyy-MM-dd}` (solo Functions)
El resumen de cumplimiento se recalcula con cada revisión y en cada corrida del scheduler.

```jsonc
{
  "date": "2026-09-21",
  "settings": { "intervalMinutes": 10, "startTime": "07:00", "endTime": "13:00", "escalationMinutes": 5 },
  "slots": {
    "07:00": { "status": "on_time", "eventId": "…", "clientAt": "…", "syncedLate": false },
    "07:10": { "status": "missed" }
  },
  "counts": { "expected": 36, "onTime": 30, "late": 2, "missed": 1, "pending": 0, "upcoming": 3, "syncedLate": 4 },
  "missedAlerted": ["07:10"],
  "summarySent": false,
  "updatedAt": "…"
}
```
> Si los settings cambian a mitad del día, el día se recalcula con los settings nuevos y los slots ya evaluados con revisión se conservan si siguen existiendo.

### `families/{familyId}/meta/pushBuffer` (solo Functions)
Agrupa ráfagas de `checkin_late` (ver 07): `{ pending: [{ eventId, realAt }], windowStart, flushAt }`. En cada `checkin_late`, en una transacción: si `pending` tiene 3 o más elementos en menos de 60 s, se agrega a la lista en vez de enviar el push. El scheduler o la siguiente revisión envían el push agrupado cuando `now ≥ flushAt` (`windowStart` + 60 s) y vacían la lista.

### `families/{familyId}/private/libre` (solo Functions)
`{ credentialsEnc, patientId, patientName, region, sessionEnc, sessionExpiresAt }`

### `pairingCodes/{code}` (solo Functions)
`{ familyId, expiresAt, createdBy }`

### `rateLimits/{uid}` (solo Functions)
`{ joinAttempts, windowStart }`: máximo 10 intentos de `joinFamily` por hora.

## 4. Reglas de Firestore (esqueleto)

```
rules_version = '2';
service cloud.firestore {
  match /databases/{db}/documents {
    function signedIn() { return request.auth != null; }
    function member(fid) { return signedIn() && request.auth.token.familyId == fid; }
    function role() { return request.auth.token.role; }

    match /users/{uid} {
      allow read: if signedIn() && request.auth.uid == uid;
      allow update: if signedIn() && request.auth.uid == uid
        && request.resource.data.diff(resource.data).affectedKeys()
             .hasOnly(['fcmToken', 'tokenUpdatedAt', 'appVersion']);
    }

    match /families/{fid} {
      allow read: if member(fid);
      allow update: if member(fid) && role() == 'parent'
        && request.resource.data.diff(resource.data).affectedKeys().hasOnly(['settings', 'childName'])
        && validSettings(request.resource.data.settings);

      match /events/{eid} {
        allow read: if member(fid);
        allow create: if member(fid)
          && request.resource.data.createdBy == request.auth.uid
          && request.resource.data.createdAt == request.time
          && request.resource.data.clientAt is timestamp
          && request.resource.data.clientAt <= request.time + duration.value(24, 'h')   // reloj adelantado: el servidor usa min(clientAt, createdAt)
          && request.resource.data.clientAt >= request.time - duration.value(7, 'd')
          && request.resource.data.keys().hasOnly(
               ['type','createdBy','createdAt','clientAt','clockOffsetMs','source','text','location','smsSent','replyTo'])
          && ((role() == 'child'  && request.resource.data.type in ['checkin', 'sos'])
           || (role() == 'parent' && request.resource.data.type in ['parent_message', 'sos_ack']))
          && (!('text' in request.resource.data) || request.resource.data.text.size() <= 120)
          && (!('clockOffsetMs' in request.resource.data) || (request.resource.data.clockOffsetMs is int
               && request.resource.data.clockOffsetMs.abs() <= 604800000));
        // update/delete: solo Functions (Admin SDK)
      }
      match /days/{day} { allow read: if member(fid); }
      match /private/{doc} { allow read, write: if false; }
      match /meta/{doc}    { allow read, write: if false; }
    }
    match /pairingCodes/{code} { allow read, write: if false; }
    match /rateLimits/{uid}    { allow read, write: if false; }
  }
}
```
`validSettings()` replica la tabla de la sección 1. Probar con el emulador (`@firebase/rules-unit-testing`).

## 5. Room (outbox en ambos roles; el caso crítico es el niño)

### `outbox_events`
| Columna | Tipo | Notas |
|---|---|---|
| `id` | TEXT PK | UUID, el mismo que el `eventId` de Firestore |
| `type` | TEXT | niño: `checkin` \| `sos` \| `insulin_dose`; padre: `parent_message` \| `sos_ack` |
| `text`, `replyTo` | TEXT? | mensajes y respuestas de los padres |
| `doseUnits` | REAL? | solo `insulin_dose` |
| `clockOffsetMs` | INTEGER? | ver 07 |
| `clientAt` | INTEGER | epoch ms |
| `source` | TEXT | |
| `lat`, `lng`, `accuracyM` | REAL? | |
| `smsSent` | INTEGER | 0/1 |
| `status` | TEXT | `PENDING` → `SENT` \| `REJECTED` |
| `attempts` | INTEGER | |
| `lastError` | TEXT? | |
| `sentAt` | INTEGER? | epoch ms de la confirmación del servidor |

- Índice por `status` y por `clientAt`.
- Retención: los `SENT` se borran a los 30 días (con un worker diario); los `PENDING` **nunca** se borran automáticamente.
- `REJECTED` se usa solo ante `PERMISSION_DENIED` no recuperable, por ejemplo si el teléfono fue desvinculado. Se registra en el log y se muestra en la pantalla de diagnóstico.

### Preferencias locales (DataStore)
`role`, `familyId`, `childName`, `displayName`, `settingsJson` (último `ReminderSettings` conocido), `lastMessage` (`{ text, from, at }`), `lastSosAckAt`.
