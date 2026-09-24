# 08 — Plan de implementación con Claude Code

## Cómo trabajar
- **Una fase por sesión** de Claude Code. Al empezar cada sesión, Claude Code lee `CLAUDE.md` automáticamente.
- En cada fase: pegar el prompt → pedir **plan mode** (Shift+Tab) → revisar el plan → aprobarlo → implementar → correr las pruebas → hacer commit.
- Al cerrar cada fase, pedir: *"Actualiza la sección Estado de CLAUDE.md con lo hecho, las decisiones y lo pendiente"*.
- Nunca pasar a la siguiente fase con pruebas en rojo.
- Requisitos en tu máquina: Android Studio (SDK + `ANDROID_HOME`), JDK 17+ para Gradle, **JDK 21+ para los emuladores de Firebase** (`firebase emulators:*` lo exige; si tienes ambos, exporta `JAVA_HOME` al 21+ solo para esos comandos), Node LTS, `firebase-tools` y un teléfono o emulador con Google Play Services.

---

## F0 — Preparación (manual, 20 min)
Pasos que haces **tú**, no Claude Code (detalle en 10):
1. Crear el proyecto Firebase `checkin-familia`, activar el **plan Blaze** y una **alerta de presupuesto de USD 1**.
2. Activar Authentication → **Anónimo**, Firestore (modo producción, `us-east1`) y Cloud Messaging.
3. Registrar la app Android `com.ingeint.checkin` y descargar `google-services.json`.
4. `git init checkin`, copiar esta carpeta de docs y `CLAUDE.md`, y hacer el primer commit.

---

## F1 — Backend base
**Prompt:**
```
Lee docs/02, docs/03 y docs/04. Implementa la fase F1 del backend en firebase/:
- Scaffold de Functions v2 en TypeScript (última versión estable de firebase-functions/firebase-admin),
  firebase.json con emuladores (auth, firestore, functions), region us-east1 global.
- src/time.ts y src/schedule.ts implementando EXACTAMENTE las reglas de slots de docs/03 §2,
  con tests que carguen docs/schedule-vectors.json y cubran TODOS los vectores (referencia: docs/reference/schedule_reference.py).
- Callables: createFamily, createPairingCode, joinFamily (con rate limit), leaveFamily.
- messaging.ts (sendToParents/sendToChild, limpieza de tokens inválidos).
- onEventCreated para checkin/sos/parent_message/sos_ack SIN LibreLinkUp todavía
  (glucoseError "not_configured"), con idempotencia por processedAt y realAt.
- onFamilyUpdated → push "sync".
- firestore.rules completas según docs/03 §4 + tests con @firebase/rules-unit-testing
  (casos permitidos y denegados por rol, clientAt fuera de rango, claves extra, update prohibido).
Usa plan mode primero. Al final corre build, tests y emuladores.
```
**Listo cuando:** `npm test` pasa en verde (vectores y reglas), `firebase emulators:exec` corre los tests de triggers y los textos de push coinciden con 04.

---

## F2 — App Android base y vinculación
**Prompt:**
```
Lee docs/06 y docs/03. Crea el proyecto Android en android/ (Kotlin, Compose M3, minSdk 26,
versiones estables actuales en libs.versions.toml, paquete com.ingeint.checkin, nombre "Check-in").
Implementa:
- CheckinApp con AppContainer (DI manual), Auth anónima persistente, cliente de Functions us-east1.
- SetupScreen completo (crear familia / unirse con código / rol niño), getIdToken(true) tras vincular,
  guardado de rol/familia/settings en DataStore, registro del token FCM.
- Canales de notificación por rol exactamente como la tabla de docs/06 (child_* sin sonido).
- Haptics con los patrones de docs/01 y USAGE_ALARM.
- PushService con el switch de tipos de docs/04 (handlers vacíos o mínimos donde aplique).
- Pantalla de diagnóstico (permisos, modo de timbre, probar cada vibración) y
  asistente de permisos (notificaciones, batería, alarmas exactas).
- Tests: test de palabras prohibidas en strings del rol niño; test instrumentado de que los canales child_* no tienen sonido.
Apunta a los emuladores de Firebase en debug (BuildConfig flag). Plan mode primero.
```
**Listo cuando:** dos emuladores se vinculan (padre crea la familia, niño se une con el código), los claims funcionan contra las reglas y un push de prueba desde el emulador llega al rol correcto.

---

## F3 — Recordatorios del niño
**Prompt:**
```
Lee docs/03 §2 y docs/06 (sección Recordatorios). Implementa en android/:
- ReminderSchedule (java.time, puro) con tests JVM que carguen docs/schedule-vectors.json (el mismo archivo que usa el backend).
- ReminderScheduler (setExactAndAllowWhileIdle, fallback inexacto), ReminderReceiver (REMINDER/NUDGE),
  BootReceiver, manejo de push "nudge" y "sync", listener de settings con la app abierta.
- ChildNotifier: notificación neutra "Recordatorio", VISIBILITY_PRIVATE + publicVersion,
  acción "Listo" (por ahora solo registra un log y cancela; el outbox llega en F4).
- ChildScreen: botón "Ya me revisé", próximo recordatorio, último mensaje, botón Ayuda (hold 2 s con progreso) sin lógica de envío aún.
Plan mode primero.
```
**Listo cuando:** en el emulador, con intervalo de 5 min, las alarmas suenan (vibran) a la hora exacta, sobreviven a un reinicio (`adb reboot`), respetan los días y el horario, y el refuerzo ocurre una sola vez.

---

## F4 — Outbox, sincronización y SOS
**Prompt:**
```
Lee docs/07 completo y docs/03 §5. Implementa:
- Room (outbox_events), OutboxRepository.record() con debounce de checkin de 60 s.
- SyncWorker exactamente como el algoritmo de docs/07 (único, expedited, backoff, getForegroundInfo,
  manejo de PERMISSION_DENIED consultando el doc en servidor, lotes, CONFIRM solo para eventos recientes),
  periódico de respaldo, trigger al volver la red y en boot.
- Flujo de revisión desde la app y desde la acción "Listo"; la UI muestra pendientes/enviado.
- La omisión del slot cubierto en ReminderReceiver usa el outbox (assign() de ReminderSchedule).
- SOS: ubicación con timeout de 5 s, SmsFallback cuando no hay red validada y smsFallbackEnabled.
- Manejo de clockOffsetMs.
- Tests: unitarios del repositorio y worker (con fakes) + instrumentado "modo avión" descrito en docs/07.
Plan mode primero.
```
**Listo cuando:** pasan todas las pruebas de 07 §"Pruebas obligatorias" en un emulador y en un teléfono real.

---

## F5 — App de padres
**Prompt:**
```
Lee docs/01 (H3–H8) y docs/06 (pantallas de padres, ParentNotifier). Implementa:
- ParentHomeScreen: banner SOS con "Voy en camino"/ubicación, estado, cumplimiento de hoy desde days/{fecha}
  (barra de slots por estado + contadores + marca "sincronizado después"), mensajes rápidos (outbox),
  línea de tiempo, historial de 30 días.
- ParentSettingsScreen completo con validación igual a docs/03 §1 (sección LibreLinkUp: UI + llamadas a callables,
  que pueden devolver not-implemented hasta F7).
- ParentNotifier con los canales y acciones de docs/06 (full screen SOS, acción sos_ack).
- En el niño: manejo real de parent_message y sos_ack (vibraciones MESSAGE y SEEN).
Plan mode primero.
```
**Listo cuando:** se completa el ciclo de extremo a extremo en el emulador: el niño se revisa, el padre recibe el push, el padre manda un mensaje y el niño vibra, el niño envía SOS, el padre responde "Voy en camino" y el niño vibra `SEEN` mientras el otro padre recibe el aviso.

---

## F6 — Cumplimiento y escalamiento (backend)
**Prompt:**
```
Lee docs/03 §2 y §3 (days) y docs/04 (recomputeDay, checkMissedSlots) y docs/07 (Qué ven los padres). Implementa:
- recomputeDay puro + escritura preservando missedAlerted/summarySent.
- checkMissedSlots cada 5 min: missed → push a padres + nudge al niño, una vez por slot, ventana de 60 min.
- day_summary al terminar el horario.
- checkin_late y agrupación de ráfagas (>3 en 1 min).
- Tests con el reloj simulado: V13, V14, V18, V19 end-to-end en el emulador.
Plan mode primero.
```
**Listo cuando:** en el emulador, al ignorar un recordatorio llega "no ha confirmado"; al sincronizar después una revisión hecha a tiempo, el slot pasa a `on_time` con la marca "sincronizado después" y el resumen del día cuadra.

---

## F7 — LibreLinkUp
**Prompt:**
```
Lee docs/05 completo. ANTES de escribir código, revisa el código actual de los proyectos de la comunidad
(nightscout-librelink-up de timoschlueter y el paquete npm libre-link-up-api-client) para confirmar
endpoints, headers, versión mínima y el header Account-Id; resume diferencias con docs/05 y actualiza docs/05.
Luego implementa libre/client.ts, libre/crypto.ts, libre/service.ts (sesión cifrada, relogin en 401,
búsqueda en graph para revisiones atrasadas, timeouts), los callables setLibreLinkUp/testLibreLinkUp/removeLibreLinkUp,
integración en onEventCreated (checkin y sos), libreStatus. Secret LLU_ENC_KEY y param LLU_APP_VERSION.
Tests con fetch mockeado (login, redirect, terms, version, 401, graph). Plan mode primero.
```
**Listo cuando:** con la cuenta real de seguidor, "Probar lectura" muestra el valor que se ve en LibreLinkUp, una revisión real trae el valor en el push y una revisión atrasada toma el valor del historial.

---

## F8 — Endurecimiento y prueba en campo
**Prompt:**
```
Lee docs/09 y docs/10. Implementa: guía de optimización de batería por fabricante en el asistente de permisos,
pantalla "Acerca de" con el aviso de seguridad, firma de release (keystore fuera del repo, signingConfig vía
gradle.properties locales), R8 con reglas para Firebase/Room, versionCode automático, script para generar el APK release.
Revisa todo el código contra las reglas de discreción de docs/06 y contra CLAUDE.md, y lista cualquier desviación.
```
**Listo cuando:** se completa el checklist de campo de 09 en los teléfonos reales, incluida **una semana escolar de prueba** con los padres revisando el cumplimiento diario.

---

## Fase 3 del producto (opcional, después)
- Lectura del valor desde la notificación de LibreLink (`NotificationListenerService`) para tener glucosa sin internet.
- Widget de pantalla de inicio para el niño con el botón "Ya me revisé".
- Soporte para reloj Wear OS (la vibración en la muñeca es aún más discreta).
- Exportar el historial a PDF para el endocrinólogo.
