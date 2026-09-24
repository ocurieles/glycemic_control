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
npm run build && npm test
cd .. && firebase emulators:start            # auth, firestore, functions
firebase emulators:exec "npm --prefix functions test"
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
- Fase actual: F0 (preparación manual)
- Hecho: especificación
- Decisiones tomadas durante la implementación: —
- Pendiente / riesgos abiertos: validar la vibración en modo silencio en el teléfono real de Cesar; confirmar los headers actuales de LibreLinkUp (F7).
