# 09 — Estrategia de pruebas

## Automatizadas
| Nivel | Qué | Dónde |
|---|---|---|
| Unitarias backend | `schedule.ts` (todos los de schedule-vectors.json), `recomputeDay`, formato de textos, crypto, cliente LibreLinkUp mockeado | `firebase/functions`, `npm test` |
| Reglas | permisos por rol, validación de settings y eventos | Emulador + `@firebase/rules-unit-testing` |
| Integración backend | triggers y scheduler con el emulador (FCM mockeado) | `firebase emulators:exec` |
| Unitarias Android (JVM) | `ReminderSchedule` (todos los de schedule-vectors.json), `OutboxRepository`, lógica de `SyncWorker` con fakes, validación de settings | `./gradlew testDebugUnitTest` |
| Instrumentadas | canales `child_*` sin sonido, Room, WorkManager (`work-testing`), flujo en modo avión | `./gradlew connectedDebugAndroidTest` |
| Estáticas | palabras prohibidas en strings del niño; lint sin errores | `./gradlew lint` |

> **Paridad de contrato:** los vectores viven en un solo archivo, `docs/schedule-vectors.json` (ya incluido y verificado con `docs/reference/schedule_reference.py`), que leen ambos conjuntos de tests. Así el backend y la app no pueden divergir. Android lo lee como recurso de test: `sourceSets["test"].resources.srcDir("../../docs")`.

## Checklist de campo (teléfonos reales)
Se prueba en el **teléfono de Cesar** (anotar marca, modelo y versión de Android) y en los de los padres.

**Discreción (con el teléfono de Cesar)**
- [ ] Volumen al máximo: el recordatorio **no suena**.
- [ ] Modo vibración: vibra con el patrón correcto.
- [ ] Modo silencio: vibra. Si **no** vibra, anotarlo y evaluar el cambio a `setAlarmClock` o la guía de ajustes.
- [ ] No Molestar activo: anotar el comportamiento. Documentar para los padres si hay que agregar la app como excepción.
- [ ] En la pantalla de bloqueo la notificación dice solo "Recordatorio".
- [ ] Cesar distingue los patrones REMINDER, MESSAGE, CONFIRM y SEEN sin mirar (pantalla de práctica).

**Confiabilidad**
- [ ] Intervalo de 10 min durante 6 h con la pantalla apagada y el teléfono en el bolsillo: ningún recordatorio perdido (revisar el historial).
- [ ] Tras reiniciar el teléfono, los recordatorios siguen.
- [ ] Con el ahorro de batería del fabricante activo, siguiendo la guía: siguen.
- [ ] Cambio de settings desde el padre con la app del niño cerrada: se aplica en menos de 1 min.

**Sin conexión**
- [ ] Modo avión → 5 revisiones → reiniciar → sin modo avión: 5 revisiones con su hora real, sin duplicados.
- [ ] Los padres ven "sin respuesta" durante el corte y luego el slot corregido con la marca "sincronizado después".
- [ ] SOS en modo avión con SMS activado y señal celular: los padres reciben el SMS.
- [ ] SOS sin señal ni datos: queda pendiente y se envía al volver.

**Padres**
- [ ] SOS con el teléfono del padre en No Molestar (con acceso concedido): suena.
- [ ] SOS con la pantalla bloqueada: aparece a pantalla completa.
- [ ] "Voy en camino": Cesar vibra SEEN y el otro padre recibe el aviso.
- [ ] El resumen del día al terminar el horario cuadra con la línea de tiempo.

**LibreLinkUp**
- [ ] El valor del push coincide con el que muestra LibreLinkUp (±1 min).
- [ ] Con la clave cambiada: la revisión igual llega sin valor y Ajustes muestra el error.

## Semana piloto
Durante una semana escolar, cada tarde los padres comparan el resumen del día con lo que Cesar cuenta, anotan los falsos "sin respuesta" y los recordatorios que no sintió, y al final se ajustan el intervalo, el escalamiento y el refuerzo.
