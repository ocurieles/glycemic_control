# CLAUDE.md — Check-in (recordatorios de revisión para Cesar)

App Android (Kotlin/Compose, un APK con roles `parent` y `child`) + backend Firebase (Functions v2 TypeScript, Firestore, FCM, Auth anónima). Cesar (niño con diabetes, sensor Libre + LibreLink) recibe recordatorios **solo por vibración** en horario escolar, confirma con un toque (también sin internet, con sincronización posterior) y puede enviar SOS. Los padres reciben pushes, el cumplimiento del día y el valor de glucosa vía LibreLinkUp.

La especificación completa está en `docs/`. **Léela antes de cada fase:**
| Doc | Contenido |
|---|---|
| `docs/01-producto.md` | historias, criterios de aceptación, patrones de vibración |
| `docs/02-arquitectura.md` | decisiones D1–D11, estructura del repo |
| `docs/03-modelo-de-datos.md` | settings, **reglas de slots (contrato)**, Firestore, reglas, Room |
| `docs/04-backend-functions.md` | callables, triggers, scheduler, payloads FCM y textos |
| `docs/05-librelinkup.md` | API no oficial, sesión, cifrado, errores |
| `docs/06-app-android.md` | discreción, canales, alarmas, permisos, pantallas |
| `docs/07-offline-sincronizacion.md` | outbox Room, SyncWorker, SOS por SMS, reloj |
| `docs/08-plan-de-implementacion.md` | fases F0–F8 con prompts |
| `docs/09-pruebas.md` | estrategia y checklist de campo |
| `docs/10-despliegue.md` | Firebase, APK, checklists de teléfonos |
| `docs/schedule-vectors.json` | vectores de contrato (los usan ambos conjuntos de tests) |

## Reglas no negociables
1. **Código en inglés** (clases, funciones, variables, campos de Firestore, columnas de Room, ids de canales). **La UI y los textos para el usuario, en español** (`strings.xml`; en backend, `messages.ts`).
2. **Rol niño: cero sonido.** Nunca `setSound` con un URI, `RingtoneManager`, `MediaPlayer` ni `ToneGenerator` en rutas del niño. La vibración va siempre por `Haptics` con `USAGE_ALARM`.
3. **Rol niño: textos neutros.** Prohibidas en strings visibles del niño: glucosa, glicemia, glucemia, diabetes, azúcar, sensor, insulina. Hay un test que lo verifica y no se desactiva.
4. **Una revisión nunca se pierde ni se bloquea:** primero se escribe en Room y después se hace todo lo demás. LibreLinkUp, la ubicación o la red nunca impiden registrar una revisión ni un SOS.
5. **Idempotencia:** `eventId` = UUID del cliente y `set()` a ese id. Los triggers verifican `processedAt`.
6. **Hora real = `realAt = min(clientAt, createdAt)`**, donde el cliente ya envía `clientAt` corregido con su offset de reloj. Se usa para slots, cumplimiento, glucosa y textos. `syncedLate = createdAt − realAt > 120 s`.
7. **Las reglas de slots se implementan igual en Kotlin y TypeScript** y ambos conjuntos de tests cargan `docs/schedule-vectors.json`. Si cambias la regla, cambias el doc 03, el JSON y `docs/reference/schedule_reference.py` en el mismo commit.
8. **Pushes data-only, prioridad alta.** La app construye las notificaciones.
9. **Credenciales de LibreLinkUp solo en Functions**, cifradas (AES-256-GCM, secret `LLU_ENC_KEY`). Nunca se loguean ni viajan al cliente.
10. No publicar en Play: se distribuye por APK. Los permisos `USE_EXACT_ALARM` y `SEND_SMS` están justificados por eso.
11. Versiones de dependencias: las **últimas estables** al momento de implementar. Verificarlas en vez de asumirlas.

## Comandos
```bash
# Backend
cd firebase/functions && npm ci
npm run build && npm test                    # unitarias puras (schedule, messages); no requiere emulador
npm run typecheck                            # incluye src/__tests__ (excluidos del build de deploy)
cd .. && firebase emulators:start            # auth, firestore, functions
firebase emulators:exec --only firestore,functions "npm --prefix functions run test:all"   # + reglas y triggers (necesita build previo)
firebase deploy --only firestore:rules,firestore:indexes,functions

# Android
cd android
./gradlew testDebugUnitTest                  # incluye vectores de schedule
./gradlew connectedDebugAndroidTest          # requiere emulador/dispositivo
./gradlew lint assembleDebug
adb shell dumpsys alarm | grep com.ingeint.checkin     # ver alarmas programadas
adb shell cmd deviceidle force-idle                    # probar Doze
```
En debug, la app apunta a los emuladores de Firebase (`BuildConfig.USE_EMULATORS`; host `10.0.2.2`).

## Convenciones
- Kotlin: paquetes `data.local`, `data.remote`, `reminders`, `sync`, `notify`, `push`, `ui.*`. Corrutinas + Flow. DI manual (`AppContainer`). Nada de lógica en Composables: ViewModels por pantalla.
- TypeScript: `strict`, sin `any` implícito, funciones puras separadas de I/O (`schedule.ts` y `compliance.ts` no importan Firebase).
- Commits pequeños por fase: `feat(f3): reminder scheduler`, etc.
- Si algo de la especificación resulta inviable o ambiguo: **para, explica y propone**, sin improvisar en silencio. Después actualiza el doc correspondiente.

## Estado
<!-- Claude Code: actualiza esta sección al cerrar cada fase -->
- Fase actual: F1 cerrada (backend base). Siguiente: F2 (app Android base y vinculación).
- Hecho:
  - Repo Android inicializado (git init + commit de la especificación).
  - `firebase/` completo: `firebase.json` (emuladores auth/firestore/functions, `us-east1`), `firestore.rules` (docs/03 §4 con `validSettings()`), `firestore.indexes.json`, `.firebaserc` (placeholder de projectId).
  - `functions/src/time.ts` y `schedule.ts`: reglas de slots (docs/03 §2), timezone-aware con `luxon` (a diferencia de `schedule_reference.py`, que es naive). Los 22 vectores de `docs/schedule-vectors.json` pasan (`npm test`).
  - `functions/src/families.ts`: `createFamily`, `createPairingCode`, `joinFamily` (rate limit 10/h), `leaveFamily`.
  - `functions/src/messaging.ts`: `sendToParents`/`sendToChild`, limpieza de `fcmToken` inválido.
  - `functions/src/messages.ts` (textos exactos de docs/04, es-VE) + `events.ts` (`onEventCreated`: checkin/sos/parent_message/sos_ack, idempotente por `processedAt`, `realAt = min(clientAt, createdAt)`, `syncedLate`, **sin LibreLinkUp todavía** → `glucoseError: "not_configured"`) + `settings.ts` (`onFamilyUpdated` → push `sync`).
  - Pruebas: 28 unitarias (`schedule.ts`, `messages.ts`, sin emulador), 21 de `firestore.rules` (`@firebase/rules-unit-testing`) y 5 de integración de triggers (`onEventCreated`/`onFamilyUpdated`) contra los emuladores de Firestore + Functions. Las 54 pasan.
- Decisiones tomadas durante la implementación:
  - **TypeScript 5.9.3** en vez de la 7.0.2 recién publicada (reescritura completa del compilador; el ecosistema de Cloud Functions/build tools aún no la soporta). Se revisará en cada fase si ya es viable subir.
  - Runtime de Cloud Functions: **`nodejs22`** (el más nuevo con soporte estable al implementar). La máquina de desarrollo tiene Node 26; el emulador avisa el desfase pero corre igual.
  - Se agregó `luxon` como dependencia del backend para el manejo de zonas horarias con DST real (el contrato en `docs/03`/`schedule_reference.py` asume naive porque `America/Caracas` no tiene DST, pero el código de producción sí debe manejarlo).
  - Se quitó `firebase-functions-test` de las devDependencies: su última versión (3.5.0) todavía no declara compatibilidad de peer-deps con `firebase-admin@14`. Las pruebas de triggers se hicieron en su lugar con `firebase-admin` directo contra los emuladores (`src/__tests__/events.test.ts`).
  - Scripts de test separados en `functions/package.json`: `test` (unitarias puras, sin emulador), `test:rules` y `test:integration` (necesitan emuladores), y `test:all` que corre los tres. `CLAUDE.md` → Comandos actualizado para reflejarlo (el comando original `firebase emulators:exec "npm --prefix functions test"` no cubría reglas ni triggers).
  - Requiere JDK ≥ 21 para los emuladores de Firebase (el proyecto tenía JDK 17); se usó `openjdk` de Homebrew. Pendiente decidir si documentarlo como prerrequisito fijo en `docs/10`.
- Pendiente / riesgos abiertos:
  - Validar la vibración en modo silencio en el teléfono real de Cesar (F8/campo).
  - Confirmar los headers actuales de LibreLinkUp antes de F7.
  - `.firebaserc` tiene un projectId placeholder: falta reemplazarlo por el proyecto Firebase real (F0 manual) antes de desplegar.
  - Actualizar `docs/10-despliegue.md` para mencionar el requisito de JDK ≥ 21 en la máquina de desarrollo (emuladores).
