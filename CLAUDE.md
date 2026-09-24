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
En debug, la app apunta a los emuladores de Firebase (`BuildConfig.USE_EMULATORS`; host `127.0.0.1`). En un **teléfono real** por USB (no en el emulador de Android Studio) hace falta además:
```bash
adb reverse tcp:9099 tcp:9099   # Auth
adb reverse tcp:8080 tcp:8080   # Firestore
adb reverse tcp:5001 tcp:5001   # Functions
```

## Convenciones
- Kotlin: paquetes `data.local`, `data.remote`, `reminders`, `sync`, `notify`, `push`, `ui.*`. Corrutinas + Flow. DI manual (`AppContainer`). Nada de lógica en Composables: ViewModels por pantalla.
- TypeScript: `strict`, sin `any` implícito, funciones puras separadas de I/O (`schedule.ts` y `compliance.ts` no importan Firebase).
- Commits pequeños por fase: `feat(f3): reminder scheduler`, etc.
- Si algo de la especificación resulta inviable o ambiguo: **para, explica y propone**, sin improvisar en silencio. Después actualiza el doc correspondiente.

## Estado
<!-- Claude Code: actualiza esta sección al cerrar cada fase -->
- Fase actual: F4 cerrada (outbox, sincronización y SOS). Siguiente: F5 (app de padres).
- Hecho:
  - **F0/F1**: repo git, backend Functions v2 desplegado en el proyecto real `checkin-familia` (us-east1), Auth anónima + Firestore habilitados, `google-services.json` en `android/app/`.
  - **F1 fix post-deploy**: `joinFamily` pedía `displayName` también al niño y sobrescribía `families.childName`; ahora es obligatorio solo para `role: "parent"` y el niño hereda el `childName` ya existente (docs/04 corregido, función redesplegada).
  - **F2**: proyecto Android completo en `android/` (Kotlin + Compose M3, `minSdk 26`, paquete `com.ingeint.checkin`, nombre "Check-in"):
    - `CheckinApp` + `AppContainer` (DI manual): Auth anónima persistente, Firestore/Functions(`us-east1`)/Messaging, apuntando a los emuladores en debug (`BuildConfig.USE_EMULATORS`, host `127.0.0.1` + `adb reverse` — funciona igual en emulador y en teléfono real).
    - `src/debug/res/xml/network_security_config.xml`: permite HTTP sin cifrar hacia `127.0.0.1`/`10.0.2.2`/`localhost` **solo en debug** (si no, Android bloquea la conexión a los emuladores de Firebase con "Cleartext HTTP traffic ... not permitted").
    - `SetupScreen` completo: elegir rol → crear familia / unirse con código (padre) o solo código (niño) → `getIdToken(true)` tras vincular → guarda rol/familia/settings en DataStore (`Prefs`) → registra canales.
    - `Channels`: `child_reminder`/`child_message` (sin sonido, `VISIBILITY_PRIVATE`), `parent_checkin`/`parent_alert`/`parent_sos` (con alarma, bypassa DND), `sync_status`; se crean solo para el rol activo.
    - `Haptics` con los 5 patrones de docs/01 y `USAGE_ALARM`.
    - `PushService` (`FirebaseMessagingService`): switch por `type` de docs/04; `sync` ya refresca settings cacheados; el resto son handlers mínimos con `TODO(F3/F5)`.
    - `PermissionsWizardScreen` (rol-dependiente) y `DiagnosticsScreen` (checklist de permisos, modo de timbre, practicar cada vibración).
    - Tests: `ChildStringsForbiddenWordsTest` (JVM) y `ChildChannelsInstrumentedTest` (androidTest).
  - `./gradlew assembleDebug testDebugUnitTest connectedDebugAndroidTest lint` — **todo en verde**, `connectedDebugAndroidTest` corrido en un teléfono real (WP53 Pro, Android 16/API 36; ver "verificado en dispositivo real" abajo).
  - **Verificado en dispositivo real** (el teléfono de Cesar, con LibreLink instalado, contra los emuladores de Firebase — no contra producción): rol padre completo de punta a punta — crear familia → código de vinculación real (`createFamily` procesado por el emulador de Functions) → asistente de permisos → pantalla de inicio. Se limpiaron los datos de prueba de la app al terminar (`pm clear`) para no dejar el teléfono en un estado de prueba.
  - **F3**: `reminders/` (`ReminderSchedule` + `ReminderTime`, espejo exacto de `schedule.ts`; `ReminderScheduler` con `AlarmManager.setExactAndAllowWhileIdle` + fallback inexacto; `ReminderReceiver` para `ACTION_REMINDER`/`ACTION_NUDGE`/`ACTION_ACK`; `BootReceiver` para `BOOT_COMPLETED`/`MY_PACKAGE_REPLACED`/`TIME_SET`/`TIMEZONE_CHANGED`).
    - `ChildNotifier`: notificación "Recordatorio" neutra, `VISIBILITY_PRIVATE` + `publicVersion`, acción "Listo" que por ahora solo cancela y loguea (el outbox real llega en F4).
    - `PushService`: `nudge` y `sync` ya actúan de verdad (vibran/reprograman), no solo `TODO`.
    - `ChildScreen` + `ChildViewModel`: botón "Ya me revisé" (sin lógica de envío aún), próximo recordatorio calculado con `ReminderSchedule.nextSlot`, último mensaje de los padres, botón Ayuda con hold de 2 s y anillo de progreso (sin envío aún), listener de Firestore en `families/{fid}` mientras la app está abierta.
    - Test: `ReminderScheduleTest` (JVM), carga `docs/schedule-vectors.json` vía `sourceSets["test"].resources.srcDir("../../docs")` — los mismos 22 vectores que el backend, en verde.
  - **Verificado en dispositivo real (F3)**: con `intervalMinutes=1` (parcheado directo en el emulador de Firestore para una prueba rápida), la alarma vibró **dos veces exactas cada 60 s** con el patrón `REMINDER` y `usage: ALARM` (confirmado con `dumpsys vibrator_manager`, no solo por observación), y quedó programada la siguiente (`dumpsys alarm`). No se probó la sobrevivencia a un reinicio real (`adb reboot`) ni el refuerzo (nudge) disparándose de verdad, por tiempo.
  - **F4**: `data/local/OutboxEvent` + `OutboxDao` + `AppDatabase` (Room) + `OutboxRepository`: outbox real en ambos roles, con el debounce de 60 s para `checkin` (docs/01 H2) y `hasCheckinForSlot()` para la omisión de recordatorios ya cubiertos.
    - `sync/SyncWorker`: único y encadenado (`enqueueUniqueWork` + `APPEND_OR_REPLACE`), `NetworkType.CONNECTED`, backoff exponencial 30 s, expedited con fallback, `getForegroundInfo()` con el canal `sync_status`; maneja `PERMISSION_DENIED` distinguiendo "ya existía" (idempotencia) de "teléfono desvinculado" (`REJECTED`); vibra `CONFIRM` solo si algo se iba a sincronizar y lo inició el usuario hace poco.
    - Se encola al registrar cualquier evento, al volver la red (`ConnectivityManager.registerNetworkCallback` en `CheckinApp`), en `BootReceiver`, al vincularse, y con un periódico de respaldo cada 15 min.
    - `ReminderReceiver`/`PushService` ("nudge"): ya omiten el aviso si el outbox tiene una revisión asignada al slot (`assign()` de `ReminderSchedule`), o si ya hubo refuerzo local (`lastNudgedSlot`).
    - `ChildScreen`/`ChildViewModel`: "Ya me revisé" y "Ayuda" ya registran de verdad en Room y encolan `SyncWorker`; la UI muestra "Enviado ✓ HH:mm" o "Guardado — se enviará al tener internet (N pendientes)" (docs/06).
    - SOS: `LocationHelper` (ubicación con timeout de 5 s, nunca bloquea), `SmsFallback` (SMS de respaldo cuando no hay red validada y `smsFallbackEnabled`, con el texto exacto de docs/07).
    - `Prefs.correctedNowMillis()`: `clientAt` ya corregido con el offset de reloj conocido.
    - Tests: `SyncWorkerPayloadTest` (JVM, el mapeo exacto de `OutboxEvent` al payload de Firestore) y `OutboxRepositoryInstrumentedTest` (androidTest, Room real en memoria: debounce, SOS nunca se debouncea, `hasCheckinForSlot`) — los 8 tests instrumentados (3 de F2 + 5 nuevos) corridos en un emulador Android real, todos en verde.
- Decisiones tomadas durante la implementación:
  - **TypeScript 5.9.3** en vez de la 7.0.2 recién publicada (reescritura completa del compilador; el ecosistema de Cloud Functions/build tools aún no la soporta).
  - Runtime de Cloud Functions: **`nodejs22`**. Se agregó `luxon` al backend para manejo de timezone con DST real.
  - Se quitó `firebase-functions-test` de las devDependencies (incompatible con `firebase-admin@14` todavía); las pruebas de triggers usan `firebase-admin` directo contra los emuladores.
  - Scripts de test separados en `functions/package.json` (`test`/`test:rules`/`test:integration`/`test:all`); `CLAUDE.md` → Comandos actualizado.
  - Requiere JDK ≥ 21 para los emuladores de Firebase (Gradle usa JDK 17 aparte, sin conflicto).
  - **AGP 9.4.1 + `compileSdk/targetSdk 37`**, sin el plugin `org.jetbrains.kotlin.android` (AGP 9+ trae Kotlin integrado, ver `kotl.in/gradle/agp-built-in-kotlin`). Se intentó primero con AGP 8.13.2 (más conservador, como con TypeScript), pero las versiones "última estable" de Compose/Lifecycle/Core de sept. 2026 ya exigen API 37 y AGP 9.1+, así que downgradear esas librerías habría sido una persecución sin fin. Requirió instalar `platforms;android-37.2` + `build-tools;37.0.0` vía `cmdline-tools`/`sdkmanager` y regenerar el wrapper con **Gradle 9.7.1** (AGP 8.x deja de funcionar desde Gradle 9.6+).
  - `clockOffsetMs` (docs/07) se recalcula en **cada** sync exitoso, no "una vez al día" como sugiere la letra del doc: es más simple de implementar y, aunque hace más lecturas a Firestore, sigue siendo barato (una lectura por corrida del `SyncWorker`, no por evento) y más preciso.
- Pendiente / riesgos abiertos:
  - Falta probar `adb reboot` con la alarma programada (docs/08 F3 "Listo cuando": sobrevive a un reinicio) y confirmar que el refuerzo (nudge) se dispara de verdad una sola vez.
  - Falta un push de prueba real al niño (`nudge`/`sync`) desde el emulador de Functions, para probar `PushService` de punta a punta.
  - **F4**: no se probó de punta a punta en un dispositivo real el checklist de "Pruebas obligatorias" de docs/07 (modo avión → 5 revisiones → reiniciar → reconectar; matar la app con pendientes; 7 días sin red). Sí se probaron con Room real: el debounce y `hasCheckinForSlot`.
  - `SmsFallback`/`LocationHelper` no se probaron con un SOS real (necesitan permisos de ubicación/SMS otorgados y, para el SMS, señal celular real, no disponible en el emulador).
  - Validar la vibración en modo silencio en el teléfono real de Cesar (F8/campo).
  - Confirmar los headers actuales de LibreLinkUp antes de F7.
  - `PushService.onNewToken` genera un warning del compilador ("overrides a deprecated member"); revisar si el SDK de Firebase Messaging 34.19.0 ya tiene un reemplazo antes de F5.
  - Actualizar `docs/10-despliegue.md` para mencionar JDK ≥ 21 (emuladores) y `compileSdk 37`/`platforms;android-37.2` como requisito del SDK de Android.
