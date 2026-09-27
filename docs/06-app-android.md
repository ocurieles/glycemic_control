# 06 — App Android

## Stack
- Kotlin, Jetpack Compose (Material 3), `minSdk 26`, `targetSdk` y `compileSdk` en la última versión estable.
- Firebase BoM: `auth`, `firestore`, `functions`, `messaging` (con las extensiones KTX ya incluidas en los módulos principales).
- Room + KSP, WorkManager, DataStore, `play-services-location`, `kotlinx-coroutines-play-services`.
- DI manual: un `AppContainer` en `CheckinApp`. No se usa Hilt, para mantenerlo simple.
- Paquete: `com.ingeint.checkin`. Nombre visible: **"Check-in"**, neutro a propósito.
- Versiones: usar las **últimas estables** en `gradle/libs.versions.toml` y verificar compatibilidad entre AGP, Kotlin y el plugin de Compose.

## Reglas de discreción (niño) — NO NEGOCIABLES
1. **Cero sonido.** Los canales del niño se crean con `setSound(null, null)` y `enableVibration(false)`, porque la vibración la maneja `Haptics` para controlar el patrón y el modo silencio. Ninguna ruta de código del rol niño llama a `RingtoneManager`, `MediaPlayer` ni `ToneGenerator`.
2. **Textos neutros:** los títulos de notificación son "Recordatorio" o "Mensaje". Las palabras *glucosa, glicemia, diabetes, azúcar, sensor, insulina* no aparecen en ninguna cadena visible del rol niño.
3. **Pantalla de bloqueo:** `VISIBILITY_PRIVATE` con `publicVersion` que solo dice "Recordatorio" o "Mensaje".
4. **Nada llamativo:** sin pantalla completa ni luces LED, y colores sobrios en la UI del niño.
5. **El SOS no emite nada audible** en el teléfono del niño.
6. Agregar un **test automatizado** que recorra `strings.xml` del rol niño y falle si aparece alguna palabra prohibida, y otro que verifique que los canales `child_*` tienen `sound == null`.

## Vibración (`notify/Haptics.kt`)
Patrones definidos en 01 (`REMINDER`, `MESSAGE`, `CONFIRM`, `SEEN`, `TAP`).
```kotlin
fun vibrate(context: Context, pattern: LongArray) {
    val vibrator = if (Build.VERSION.SDK_INT >= 31)
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
    val effect = VibrationEffect.createWaveform(pattern, -1)
    if (Build.VERSION.SDK_INT >= 33) {
        vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(effect, AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
    }
}
```
`USAGE_ALARM` hace que vibre aunque el teléfono esté en **silencio**. Con **No Molestar** activo puede bloquearse según la configuración de alarmas del usuario, y algunos fabricantes alteran este comportamiento. Hay que **validarlo en el teléfono real de Cesar** (ver 09). La pantalla de diagnóstico muestra el modo de timbre actual y un botón "Probar vibración".

## Canales de notificación
| id | Rol | Importancia | Sonido | Notas |
|---|---|---|---|---|
| `child_reminder` | niño | HIGH | ninguno | heads-up para poder tocar "Listo"; vibración manual |
| `child_message` | niño | HIGH | ninguno | vibración manual |
| `sync_status` | ambos | LOW | ninguno | "Sincronizando…"; foreground info de WorkManager (texto neutro) |
| `parent_checkin` | padres | DEFAULT | default | |
| `parent_alert` | padres | HIGH | default | sin respuesta, fuera de rango |
| `parent_sos` | padres | HIGH | alarma (`USAGE_ALARM`) | `setBypassDnd(true)`, vibración larga, pantalla completa |

Los canales se crean **solo los del rol activo** (más `sync_status`), al vincular el teléfono. Al desvincularlo se borran.

## Recordatorios (`reminders/`)
- `ReminderSchedule` (objeto puro, `java.time`): `slotsFor(date)`, `nextSlot(now)`, `assign(realAt)` y `status(slot, now, checkins) → (status, syncedLate)` según 03 §2, con las mismas firmas que el JSON de vectores. Se prueba con los vectores de 03 §2.5.
- `ReminderScheduler`:
  - `scheduleNext()`: calcula `nextSlot(now)` con los settings cacheados y usa `setExactAndAllowWhileIdle(RTC_WAKEUP, …)`. Si `!canScheduleExactAlarms()`, usa `setAndAllowWhileIdle` y la UI muestra una advertencia.
  - `scheduleNudge(slot)` en `now + nudgeMinutes`, y `cancelNudge()`.
  - `requestCode` fijos: 1001 para el recordatorio y 1002 para el refuerzo, con `FLAG_IMMUTABLE`.
- `ReminderReceiver`:
  - `ACTION_REMINDER(slot)`: si hay revisión local asignada al slot, no notifica ni programa refuerzo. Si no, muestra la notificación, vibra `REMINDER` y programa el refuerzo. **Siempre** llama a `scheduleNext()`.
  - `ACTION_NUDGE(slot)`: si sigue sin revisión, vibra `REMINDER` y vuelve a mostrar la notificación.
- `BootReceiver` (`BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET`, `TIMEZONE_CHANGED`): llama a `scheduleNext()` y encola el `SyncWorker`.
- Push `nudge` del servidor: es redundancia por si el fabricante mató la alarma. Si el refuerzo local de ese slot **ya ocurrió** (se guarda `lastNudgedSlot` en DataStore) o si hay revisión asignada, se ignora. Si no, actúa como `ACTION_NUDGE`. Así el niño recibe como máximo recordatorio + un refuerzo por slot.
- Push `sync`: descarga `families/{fid}`, cachea los settings y llama a `scheduleNext()`.
- Mientras la app del niño está abierta, un listener de `families/{fid}` hace lo mismo que `sync`.

### Permisos del manifest
```
POST_NOTIFICATIONS, VIBRATE, RECEIVE_BOOT_COMPLETED, INTERNET, ACCESS_NETWORK_STATE,
USE_EXACT_ALARM (API 33+), SCHEDULE_EXACT_ALARM (maxSdkVersion 32),
REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, WAKE_LOCK, FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC,
ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION  (niño, opcional),
ACCESS_BACKGROUND_LOCATION                     (niño, opcional; feature "¿Dónde está Cesar?", pedido 2026-09-27),
SEND_SMS                                       (niño, solo si smsFallbackEnabled),
USE_FULL_SCREEN_INTENT, ACCESS_NOTIFICATION_POLICY (padres)
```
En el manifest, sobrescribir el `SystemForegroundService` de WorkManager con `tools:node="merge"` y `android:foregroundServiceType="dataSync"`. El `ForegroundInfo` del worker declara `FOREGROUND_SERVICE_TYPE_DATA_SYNC`.

`USE_EXACT_ALARM` y `SEND_SMS` están restringidos en Google Play. Como se distribuye por APK, se permiten. Si en el futuro se publica en Play, se cambia a `SCHEDULE_EXACT_ALARM` y se quita el SMS.

### Optimización de batería (crítico)
- Se pide `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` al vincular.
- Se muestra una guía por fabricante (detectado con `Build.MANUFACTURER`) con pasos para **Xiaomi/Redmi/POCO** (inicio automático, ahorro de batería "Sin restricciones"), **Samsung** (Aplicaciones que nunca se suspenden), **Huawei**, **Oppo/Realme**, **Vivo** y **Tecno/Infinix**, que son comunes en Venezuela. Referencia: dontkillmyapp.com.
- La pantalla de diagnóstico del niño muestra un check por permiso: notificaciones, alarmas exactas, batería sin restricción, ubicación, ubicación en segundo plano ("todo el tiempo", solo API ≥ 29) y SMS. En rojo si falta alguno.
- Ubicación en segundo plano: Android 11+ no permite pedirla junto con el permiso de primer plano en un solo diálogo; el asistente primero pide `ACCESS_FINE_LOCATION`/`ACCESS_COARSE_LOCATION` (si falta) y luego, ya con eso concedido, pide `ACCESS_BACKGROUND_LOCATION` por separado — el sistema redirige solo a Ajustes si hace falta.

## Envío de eventos y outbox (resumen; detalle en 07)
- Tocar "Ya me revisé" o "Listo": vibración `TAP` → `OutboxRepository.record(checkin)` en Room → cancelar la notificación y el refuerzo → encolar `SyncWorker` → cuando el servidor confirma, vibración `CONFIRM`.
- SOS: mantener 2 s → `TAP` → ubicación (máximo 5 s, sin bloquear) → `record(sos)` → si no hay red y `smsFallbackEnabled`, `SmsFallback.send()` → encolar `SyncWorker` con prioridad expedited.

## Pantallas

### Vinculación (`SetupScreen`)
1. "¿Quién usará este teléfono?" → **Papá/Mamá** o **Cesar**. El nombre se toma de la familia después de vincular; antes dice "el niño".
2. Padre: **Crear familia** (nombre del niño y "¿cómo te llamas?", por ejemplo "Mamá") o **Unirme con código**.
3. Niño: ingresar el código de 6 dígitos que muestra un padre.
4. Asistente de permisos según el rol (notificaciones, batería, alarmas exactas; en el niño también ubicación y SMS; en los padres, No Molestar para SOS y pantalla completa).
5. El niño ve la pantalla de **práctica de vibraciones**.

### Niño (`ChildScreen`)
- Botón circular grande **"Ya me revisé"** (≥ 220 dp).
- Estado bajo el botón: "Enviado ✓ 10:40", "Guardado — se enviará al tener internet (3 pendientes)" o "Enviando…".
- "Próximo recordatorio: 10:50" o "Sin recordatorios hoy".
- Último mensaje de los padres, con su hora.
- Botón **"Ayuda"** (rojo apagado), que requiere mantener presionado 2 s con un anillo de progreso. Al soltar antes, se cancela.
- Icono de ajustes → práctica de vibraciones, diagnóstico y "Acerca de".

### Padres — Inicio (`ParentHomeScreen`)
- **Banner SOS** activo (un SOS de los últimos 60 min sin `sos_ack`): rojo, con botones "Voy en camino", "Llamar a Cesar" (`ACTION_DIAL`, si se configuró su número) y "Ver ubicación".
- **Estado:** última revisión (hora, valor, tendencia y cuánto hace), si LibreLinkUp está activo y un switch "Recordatorios activos".
- **Cumplimiento de hoy** (desde `days/{hoy}`): barra de slots coloreada por estado (a tiempo, tarde, sin respuesta, pendiente, próximo), con marca de "sincronizado después" y contadores.
- **Mensajes rápidos:** chips "Recuerda la lectura", "¿Todo bien?", "Voy en camino", y un campo de texto con botón de enviar.
- **Línea de tiempo** de hoy (eventos por `clientAt` descendente), con icono por tipo e indicador "sin conexión" en los `syncedLate`.
- **Historial:** selector de fecha (últimos 30 días) con el mismo resumen.

### Padres — Ajustes (`ParentSettingsScreen`)
- Horario: intervalo, días (chips L M X J V S D), inicio y fin (TimePicker), escalamiento, refuerzo, umbrales bajo y alto.
- SOS sin conexión: switch y hasta 3 números.
- Teléfono de Cesar (`childPhone`), para el botón "Llamar a Cesar".
- LibreLinkUp: correo, clave, "Conectar", "Probar lectura" y estado (ver 05).
- Familia: nombre del niño, miembros, "Generar código de vinculación" (6 dígitos grandes y cuenta regresiva) y "Desvincular este teléfono".
- Permisos: No Molestar para SOS (`ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS`), pantalla completa y batería.
- Acerca de: el **aviso de seguridad** de 01.

## Notificaciones de los padres (`ParentNotifier`)
- `sos`: canal `parent_sos`, `setFullScreenIntent` (si `canUseFullScreenIntent()`), categoría `CATEGORY_ALARM`, acciones "Voy en camino" (crea el evento `sos_ack` vía outbox, igual que en el niño) y "Ver ubicación" (`geo:lat,lng?q=lat,lng`). No se cancela sola.
- `checkin`: agrupada (`setGroup("checkins")`) para no llenar la barra; muestra la última y un resumen.
- `missed` y fuera de rango: `parent_alert`.

## Token FCM
En `onNewToken` y después de vincular: `users/{uid}.update(fcmToken, tokenUpdatedAt, appVersion)`. Si falla, se reintenta con WorkManager.
