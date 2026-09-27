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
- Fase actual: F8 cerrada (endurecimiento). Falta la prueba de campo real (ver "Pendiente"): eso no lo puede hacer Claude Code, requiere a Cesar y a sus padres con teléfonos reales durante una semana escolar.
  > Nota: F6, F7 y F8 se hicieron seguidas, sin pausar entre fases, a pedido explícito del usuario ("avanza hasta F8, sin preguntar"). Cada fase igual quedó verificada (tests en verde, y donde fue posible contra el emulador) y documentada por separado abajo.
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
  - **F5**: backend — 3 callables stub de LibreLinkUp (`setLibreLinkUp`/`testLibreLinkUp`/`removeLibreLinkUp`, `HttpsError('unimplemented', ...)`, docs/08 F5: "pueden devolver not-implemented hasta F7"), desplegados. App:
    - `ParentNotifier`: `notifyCheckin`/`notifyAlert` (canales `parent_checkin`/`parent_alert`) y `notifySos` (pantalla completa, bypassa DND, acciones "Voy en camino" y "Ver ubicación" con intent `geo:`).
    - `ParentActionReceiver`: acción "Voy en camino" de la notificación de SOS → registra `sos_ack` en el outbox.
    - `PushService`: ya construye las notificaciones reales de padres (`checkin`/`checkin_late`/`missed`/`sos`/`sos_glucose`/`sos_ack_info`/`day_summary`) y del niño (`parent_message` vibra `MESSAGE` + `ChildNotifier.showMessage`; `sos_ack` vibra `SEEN`).
    - `ParentHomeScreen`/`ParentViewModel`: banner de SOS activo (últimos 60 min sin `sos_ack`, con "Voy en camino" y "Llamar a Cesar"), cumplimiento de hoy desde `days/{fecha}` (con mensaje neutro si el doc no existe todavía — F6 lo escribe), mensajes rápidos (outbox), línea de tiempo en tiempo real (listener de Firestore).
    - `ParentSettingsScreen`/`ParentSettingsViewModel`: horario completo (intervalo, días, horas, escalamiento, refuerzo), umbrales, SOS por SMS, teléfono del niño, sección LibreLinkUp (conecta con los stubs), generar código de vinculación, desvincular. Validación de rangos espejo de `validSettings()` (docs/03 §1), escritura directa a Firestore (sin callable, como marcan las reglas).
  - **Verificado en el emulador (F5), de punta a punta y en tiempo real**: crear familia → enviar un mensaje rápido → aparece **al instante** en la línea de tiempo (outbox → `SyncWorker` → Firestore → `onEventCreated` → listener); Ajustes → "Probar lectura" muestra "La integración con LibreLinkUp llega en F7."; "Generar código" trae un código nuevo real; "Guardar" persiste los settings validados contra las reglas de Firestore.
  - **F6**: `compliance.ts` (`computeDay`, pura, no importa Firebase) + `recompute.ts` (`recomputeDay`, I/O: lee los `checkin` del día por `realAt`, preserva `missedAlerted`/`summarySent`) + `pushBuffer.ts` (agrupa ráfagas de `checkin_late`: los primeros 3 en 60 s se avisan de inmediato, el resto se junta en un solo push al vencer la ventana) + `missed.ts` (`checkMissedSlots`, `onSchedule` cada 5 min zona `America/Caracas`: recalcula el día, avisa "no ha confirmado" una sola vez por slot dentro de la última hora, manda el refuerzo al niño, y al terminar el horario manda `day_summary`).
    - `onEventCreated` (`checkin`) ahora sí llama a `recomputeDay` y enruta `checkin_late` por el `pushBuffer` en vez de mandarlo siempre de inmediato.
    - **Bug real encontrado y arreglado**: `family.settings ?? DEFAULT_SETTINGS` no cubre un `settings` parcial o `{}` (solo cubre `null`/`undefined`), lo que rompía `recomputeDay` con "Cannot use undefined as a Firestore value" en cuanto una familia tenía settings incompletos (pasaba con las familias de los tests de F1). Se agregó `resolveSettings()` en `families.ts`, que mergea con los defaults campo por campo, y se usa en `events.ts` y `missed.ts`.
    - Tests: `compliance.test.ts` (JVM-style TS, vector V19 de `docs/schedule-vectors.json` end-to-end: un slot `missed` pasa a `on_time` + `syncedLate` al llegar la revisión atrasada) y `missed.test.ts` (integración: llama a `processFamily` directo, sin pasar por el scheduler real —el emulador de Firestore no soporta Pub/Sub— confirma que los `missed` viejos no se re-alertan en una segunda corrida). 60 tests en total (32 unitarios + 21 de reglas + 7 de integración), todos en verde. Desplegado, incluida `checkMissedSlots` en Cloud Scheduler (activó la API automáticamente).
  - **F7**: antes de programar se revisó el código fuente actual (no solo el README) de `timoschlueter/nightscout-librelink-up` y `DiaKEM/libre-link-up-api-client` para confirmar headers/endpoints/versión mínima (docs/05 actualizado con lo encontrado, incluido un riesgo nuevo: posible fingerprinting TLS de Cloudflare del lado de Abbott). Implementado tal como quedó documentado en docs/05 (que ya casi no cambió):
    - `libre/crypto.ts`: AES-256-GCM, clave `sha256(LLU_ENC_KEY)`, IV aleatorio de 12 bytes, formato `base64(iv‖authTag‖ciphertext)`; cifra credenciales y sesión por separado.
    - `libre/client.ts`: cliente HTTP puro (sin Firebase) — `login` (con redirección regional, `status:2` → `auth`, `status:4` → `terms`, HTTP 403 → `version`), `fetchConnections`/`fetchGraph` (parsean `FactoryTimestamp`, que es UTC pero con formato `M/D/YYYY h:mm:ss AM|PM`, no ISO), `LibreUnauthorizedError` en HTTP 401.
    - `libre/service.ts`: orquesta sesión (cachea en `families/{fid}/private/libre`, cifrada; reusa si falta más de 5 min para vencer; **un** relogin y reintento ante 401), `lookupGlucoseForEvent` (usa `clientAt` como referencia, no "ahora"; si la lectura de `connections` está a más de 15 min de `clientAt` busca en `graph` un punto a ±10 min; si no hay, `stale`), `saveCredentials`/`testConnection`/`removeCredentials` (validan con un login real antes de guardar), y siempre actualiza `families.libreStatus`.
    - `libre.ts`: las 3 callables ahora son reales (antes stubs `unimplemented` de F5); mapean `LibreApiError` a mensajes en español de la tabla de docs/05.
    - `events.ts`: `checkin`/`sos` ahora llaman a `lookupGlucoseForEvent` y guardan `glucose` (o `glucoseError` con el motivo) en el evento; `formatCheckinMessage` ya incluía el parámetro `glucose` desde F6, así que el push ahora sí trae el valor cuando está disponible (docs/01 punto 3, "idealmente con el valor de glucosa").
    - Un fallo de LibreLinkUp (red, credenciales, términos, versión) nunca lanza fuera de `lookupGlucoseForEvent`: siempre vuelve `{ error }`, así que la revisión y el resto del evento se procesan igual (CLAUDE.md regla 4).
    - App: `ParentSettingsViewModel.friendlyLibreError` ya no usa el texto fijo "llega en F7" — muestra el mensaje real que manda el backend (`HttpsError.message`, ya en español).
    - **No se pudo verificar contra una cuenta real de LibreLinkUp** (no hay credenciales de prueba disponibles): la verificación fue con `fetch` mockeado (login éxito/redirección/`status 2`/`status 4`/HTTP 403/HTTP 401→relogin, `connections`/`graph`) más una prueba de integración contra el emulador de Firestore real (`libre-service.test.ts`: `saveCredentials`→`testConnection` reutiliza sesión, relogin ante 401, credenciales inválidas no se guardan, `removeCredentials` limpia todo). El "Listo cuando" de docs/08 F7 que pide una cuenta real queda pendiente para el F8/campo.
    - Tests: 72 en total (40 unitarios + 21 de reglas + 11 de integración), todos en verde. Desplegado (incluyendo el secreto `LLU_ENC_KEY`, cuyo acceso se otorgó automáticamente a la cuenta de servicio de Functions).
  - **F8**:
    - `PermissionsWizardScreen`/`DevicePermissions`: guía de "Inicio automático" por fabricante — resuelve en tiempo de ejecución la pantalla conocida de MIUI/Huawei/Color OS (Oppo)/Vivo/iQOO/OnePlus/Asus (ver dontkillmyapp.com) y solo la ofrece si existe en ese teléfono; como Android no expone si ya se activó, el ítem se marca "listo" al abrirla (no hay forma de verificarlo de verdad).
    - `ui/about/AboutScreen.kt`: pantalla "Acerca de" con el aviso de seguridad exacto de docs/01 ("no sustituye las alarmas de LibreLink/LibreLinkUp..."). Solo se llega desde Ajustes del padre (nuevo botón al final de `ParentSettingsScreen`) — nunca desde el rol niño, porque el texto menciona "hipoglucemia"/"hiperglucemia" (viola la regla de palabras prohibidas si apareciera ahí).
    - Firma de release: `app/build.gradle.kts` lee `CHECKIN_RELEASE_STORE_FILE`/`_STORE_PASSWORD`/`_KEY_ALIAS`/`_KEY_PASSWORD` de propiedades de Gradle (pensado para `~/.gradle/gradle.properties`, que nunca está en git — **no** de `android/gradle.properties`, que sí lo está). Si faltan, `release` queda sin firmar pero el resto de las tareas (`assembleDebug`, `lint`, `testDebugUnitTest`) siguen funcionando; se avisa con un `logger.warn` en la configuración.
    - `versionCode` automático: `git rev-list --count HEAD` (cae a 1 si no hay git). Ya no hace falta subirlo a mano en cada release.
    - `proguard-rules.pro`: reglas de R8 para Firebase y para las clases propias de Room/modelos de datos (`data.local`, `data.model`) — capa de seguridad extra sobre las reglas de consumidor que ya traen esas librerías. `assembleRelease` (con R8 real) se corrió y compiló sin errores.
    - `android/scripts/build_release.sh`: corre tests + lint + `assembleRelease` y verifica primero que el keystore esté configurado, con un mensaje claro si falta.
    - **Revisión completa contra docs/06 "Reglas de discreción" y CLAUDE.md regla 2/3**: se confirmó (no se asumió) que `RingtoneManager`/`MediaPlayer`/`ToneGenerator`/`setSound` con URI **no aparecen en ningún archivo** del proyecto — el único `setSound(...)` con un URI real es el canal `parent_sos` (`Channels.kt`), que es exclusivamente del rol padre. Los tres canales `child_*`/`sync_status` usan `setSound(null, null)` + `enableVibration(false)`. `PermissionsWizardScreen`/`DiagnosticsScreen` (compartidas por ambos roles) solo referencian strings de `strings_child.xml`, que sigue pasando el test de palabras prohibidas con las cadenas nuevas de F8. **No se encontraron desviaciones** de las reglas de discreción.
    - **No verificado (requiere hardware/cuentas reales, fuera del alcance de Claude Code)**: nada de esto se probó en un teléfono real con ROM de un fabricante específico (para confirmar que la actividad de "inicio automático" resuelta realmente exista y funcione en ese modelo), ni se generó un keystore real ni se instaló un APK release firmado en un teléfono.
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
  - ~~Confirmar los headers actuales de LibreLinkUp antes de F7.~~ Hecho en F7 (ver docs/05 "Verificación F7").
  - **F7**: falta probar contra una cuenta real de LibreLinkUp (no hay una disponible) — solo se verificó con `fetch` mockeado + el emulador de Firestore. Falta ver en producción qué tan seguido pasa cada error (`auth`/`terms`/`version`/`network`) y si el riesgo de fingerprinting de Cloudflare (ver docs/05) se materializa. La UI de Ajustes no pide `patientId`: si la cuenta de LibreLinkUp sigue a más de un paciente, toma el primero de `connections` — hay que confirmar con una cuenta real si eso alcanza o hace falta agregar el campo.
  - **F5**: no se probó con dos dispositivos a la vez el ciclo completo que pide docs/08 ("el niño se revisa, el padre recibe el push, el padre manda un mensaje y el niño vibra, el niño envía SOS, el padre responde y el niño vibra SEEN") — se probó el lado del padre (mensajes, ajustes, código) de punta a punta, pero no la recepción real en un segundo teléfono/emulador como niño, ni el push de FCM en sí (solo el trigger de Firestore).
  - `ParentHomeScreen`: el chip "Voy en camino" de mensajes rápidos se ve angosto en pantallas pequeñas (problema de layout, no funcional); pendiente de pulir.
  - **F6**: `checkMissedSlots` no se probó como *scheduled function* real (el emulador de Firestore no trae Pub/Sub; se probó su lógica llamando `processFamily` directo). Falta verificar en producción, tras que pase el primer intervalo de 5 min, que corre sola. También falta un caso real con más de 3 `checkin_late` en ráfaga para confirmar el mensaje agrupado de `pushBuffer`.
  - El botón "Llamar a Cesar" y el banner de ubicación del SOS no se probaron (falta un SOS real con `lat`/`lng`, que a su vez depende de que el niño lo envíe).
  - `PushService.onNewToken` genera un warning del compilador ("overrides a deprecated member"); revisar si el SDK de Firebase Messaging 34.19.0 ya tiene un reemplazo antes de F5.
  - ~~Actualizar `docs/10-despliegue.md` para mencionar JDK ≥ 21 (emuladores) y `compileSdk 37`/`platforms;android-37.2` como requisito del SDK de Android.~~ Ya estaba hecho (ver docs/10).
  - **F8 / única fase que falta de verdad**: la "semana piloto" real de docs/09 con Cesar y sus padres, y el checklist completo de campo de docs/09 en los teléfonos reales — nada de esto lo puede hacer Claude Code por sí solo.
  - Todos los ítems marcados como "no probado" en F2–F6 arriba siguen abiertos salvo los que la sesión de pruebas de campo de abajo (2026-09-24) ya cubrió; son parte del mismo checklist de docs/09, no fallas encontradas, solo verificación pendiente con hardware real.

### Sesión de pruebas de campo (2026-09-24) — 2 bugs reales encontrados y arreglados
Primera prueba real con el Samsung del padre (Orlando) y el WP53 Pro de Cesar, primero contra los emuladores (en la misma Wi-Fi de la Mac, sin `adb reverse` — ver "app apunta a producción" más abajo) y después contra el proyecto real `checkin-familia`. Se encontraron y arreglaron dos bugs reales que **no** aparecían en ningún test automatizado porque ninguno prueba un push real de extremo a extremo contra la API real de FCM:

1. **El token FCM nunca se guardaba.** `PushService.onNewToken` era la única vía para guardarlo, pero Firebase genera el token al arrancar el proceso — casi siempre antes de que exista `users/{uid}` (lo crea `createFamily`/`joinFamily` del lado del servidor). El `.update()` fallaba con NOT_FOUND, quedaba silenciado por un `catch` que solo logueaba, y como `onNewToken` no vuelve a dispararse hasta que el token rote, nunca se guardaba. **Arreglo:** `push/FcmTokenSync.kt`, llamado explícitamente justo después de vincularse (`SetupViewModel`, cuando `users/{uid}` ya existe seguro) y en cada arranque de la app (`CheckinApp`, cubre teléfonos ya vinculados antes de este fix).
2. **Los pushes al niño (`parent_message`, `sos_ack`) nunca llegaban, aunque los del niño a los padres sí.** FCM rechaza con `messaging/invalid-argument` cualquier clave de payload que sea una palabra reservada del protocolo (`from`, `to`, `message_type`, `collapse_key`, o que empiece con `google.`/`gcm.`) — y el nombre del remitente se mandaba como `from`. Encima, el código de limpieza de tokens trataba `invalid-argument` igual que `registration-token-not-registered` y **borraba el token** al primer fallo, dejando al usuario sin push hasta reabrir la app (que es lo único que lo vuelve a guardar) — aunque la causa real (la clave prohibida) seguía ahí. **Arreglo:** la clave ahora es `senderName` (`events.ts`, `messages.ts`, `PushService.kt`, docs/04), y `messaging.ts` solo borra el token en `registration-token-not-registered` (la única señal de "no existe más" según la propia doc de FCM).

**Verificado de punta a punta con hardware real, en ambas direcciones:** niño → padre (revisión, SOS) y padre → niño (mensajes rápidos, "Voy en camino") funcionan con pushes reales de FCM (no simulados). También se conectó una **cuenta real de LibreLinkUp** por primera vez y el push de revisión mostró el valor de glucosa real — la limitación que quedaba abierta de F7 ("nunca se probó contra una cuenta real") queda resuelta.

**Hallazgos de infraestructura de prueba (no bugs de la app, pero vale dejarlos anotados):**
- Los emuladores de Firebase necesitan JDK ≥ 21; se instaló `openjdk@21` por Homebrew y se corre con `JAVA_HOME` apuntando ahí (ver "Comandos" más arriba, y docs/10).
- Con dos teléfonos y un solo cable USB, `adb reverse` (127.0.0.1) solo sirve al que está conectado en ese momento — el otro se queda con el outbox local sin poder subir hasta reconectarlo. Para probar dos teléfonos reales *a la vez*, se agregó `BuildConfig.EMULATOR_HOST` configurable por Gradle (`-PCHECKIN_EMULATOR_HOST=<IP de la Mac>`, `AppContainer.kt`) y `firebase.json` ahora liga los emuladores a `0.0.0.0` en vez de solo `127.0.0.1`, para que cualquier teléfono en la misma Wi-Fi los alcance sin `adb reverse`. `network_security_config.xml` (solo debug) se simplificó a permitir cleartext para cualquier host en debug, en vez de listar IPs fijas, porque la IP de la Mac cambia de red en red.
- Ese mismo mecanismo permite además que un build **debug** (firmado con el keystore de debug, se instala con `adb install`/`installDebug` sin necesitar el keystore de release) apunte a producción en vez de a los emuladores: `-PCHECKIN_USE_EMULATORS=false`. Así se pudo probar fuera de la red de casa (Cesar salía al colegio) sin esperar a tener el keystore de release listo.
- Los teléfonos reales cambian de red Wi-Fi solos si conocen una con mejor señal — hay que fijarse que ambos estén en la misma red que la Mac antes de dar por sentado que algo "dejó de funcionar".

### Feature + sesión de pruebas (2026-09-27) — calendario de insulina, y 3 bugs reales más
Pedido del usuario: mostrar la glucosa dentro de la app del niño (solo tras tocar "Ya me revisé", nunca antes ni en notificación — se acordó explícitamente para no romper la discreción), un registro rápido de dosis de insulina (wording neutro, ícono discreto, nunca "insulina" en `strings_child.xml`), y un calendario de insulina en la app del padre (`InsulinScreen`/`InsulinViewModel`, `days/{fecha}.insulin` acumulado por `insulin.ts`). Bugs reales encontrados **en producción real, con el niño usando el teléfono**, no en tests:

1. **Family Link (supervisión de Google) y la protección "ajustes restringidos" de Android 13+ bloquean `SEND_SMS`** en cualquier app fuera de Play Store — dos capas independientes, no es un bug del proyecto. Se documenta la solución (Family Link → app → permisos, y "Permitir ajustes restringidos" en la info de la app) en vez de intentar arreglarlo en código.
2. **Un evento fallido bloqueaba toda la cola del outbox.** `SyncWorker.doWork()` hacía `return Result.retry()` al primer evento que fallaba (por PERMISSION_DENIED ambiguo o cualquier error de red), sin intentar los que venían después en el mismo lote. Un solo evento atascado (de cualquier tipo) dejaba a todos los siguientes sin subir indefinidamente. Arreglo: se sigue con el resto del lote, se reintenta solo lo que de verdad falló.
3. **El diálogo de "Registro rápido" no se cerraba solo tras elegir una dosis** — solo cambiaba un texto chico ("Registrado ✓"), lo que parecía "no pasó nada". Se agregó autocierre a los 600 ms.
4. **La más seria: la regla de Firestore para `doseUnits` rechazaba silenciosamente las dosis enteras (1, 2, 3 U).** La regla comparaba `doseUnits in [0.5, 1, 1.5, 2, 2.5, 3]` — una lista con literales mixtos enteros/flotantes — contra un valor que Android **siempre** manda como `Double` (nunca como entero). El test de reglas escrito con el SDK de JS no lo detectó porque ese SDK normaliza números enteros de JS como tipo entero al serializar, algo que el SDK de Android no hace. Diagnosticado leyendo la consola de Firebase directamente (el documento nunca se creaba, `onEventCreated` nunca se disparaba) tras descartar reloj, crash del backend (probado contra el emulador) y el bug #2. Arreglo: la regla ahora compara con aritmética (rango 0.5–3 y múltiplo de 0.5 vía `math.round`), sin depender de que el tipo numérico coincida exacto. **Lección para el futuro:** cualquier regla de Firestore que compare números contra una lista de literales debe probarse también pensando en cómo los serializa el cliente real (Android/iOS), no solo el SDK de pruebas en JS — y hay que agregar logs de diagnóstico (`logger.info` en cada `case` del switch, no solo en los que ya tenían I/O notorio) desde el primer día de cualquier feature nueva, porque sin eso ni los logs de producción sirven para descartar causas.

**Verificado con hardware real, tras el arreglo:** el registro de insulina llega de punta a punta (outbox → Firestore → `onEventCreated` → push al padre), confirmado por el usuario.
