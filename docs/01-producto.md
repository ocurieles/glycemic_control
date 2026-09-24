# 01 — Producto

## Contexto

Cesar es un niño con diabetes que usa un sensor FreeStyle Libre en el brazo y tiene la app **LibreLink** en su teléfono Android. Sus padres lo siguen con **LibreLinkUp**, y toda la familia usa Android.

Durante las clases Cesar debe revisarse periódicamente, y sus padres necesitan saber que lo hizo. La app se encarga de tres cosas: recordarle a Cesar de forma **discreta**, que solo vibre, sin sonido y sin palabras que lo delaten ante sus compañeros, confirmar a los padres cada revisión y dar un canal de ayuda (SOS) en ambos sentidos.

> **Aviso de seguridad (debe aparecer en la app, en Ajustes → Acerca de):** esta app es una ayuda de comunicación y de hábitos. **No sustituye** las alarmas de LibreLink/LibreLinkUp ni las indicaciones médicas. Las alarmas de hipo e hiperglucemia deben seguir activas en LibreLinkUp.

## Usuarios

| Rol | Quién | Dispositivo |
|---|---|---|
| `child` | Cesar | Su teléfono Android (con LibreLink) |
| `parent` | Mamá, Papá (1..N) | Sus teléfonos Android |

Es **una sola app (APK)** con dos roles, elegidos al vincular el teléfono. Una "familia" agrupa un niño y sus padres.

## Objetivos

1. Cesar recibe recordatorios cada N minutos (configurable) **solo dentro del horario escolar** y **solo con vibración**.
2. Cesar confirma con **un toque** ("Ya me revisé"), desde la app o desde la notificación.
3. Los padres reciben un push por cada revisión e, idealmente, con el **valor de glucosa** obtenido de LibreLinkUp.
4. **Sin internet no se pierde nada:** cada revisión se guarda localmente con su hora real y se sincroniza al recuperar conexión. Los padres pueden ver después que Cesar cumplió sus revisiones.
5. Cesar puede enviar un **SOS**, y los padres lo reciben como alerta prioritaria, que suena aunque estén en No Molestar si lo permiten.
6. Los padres pueden enviar mensajes a Cesar ("Recuerda la lectura"), que le llegan como vibración.
7. Si Cesar no confirma un recordatorio en X minutos, los padres reciben un aviso de **recordatorio sin respuesta**.

## No objetivos (MVP)

- Pedir o escribir valores de glucosa a mano.
- Reemplazar alarmas de glucosa.
- iOS.
- Publicación en Google Play: se instala por APK (sideload). Esto permite permisos como `USE_EXACT_ALARM` y `SEND_SMS` sin revisión de Play.

## Historias de usuario y criterios de aceptación

### H1 — Recordatorio discreto (Cesar)
*Como Cesar quiero que mi teléfono vibre, sin sonar, cuando me toca revisarme, para que mis compañeros no lo noten.*
- [ ] Vibra con el patrón `REMINDER` (2 cortas) en cada slot del horario.
- [ ] **Nunca** reproduce sonido, ni siquiera con el volumen al máximo.
- [ ] Vibra aunque el teléfono esté en modo silencio (a validar por modelo, ver 06).
- [ ] La notificación dice solo "Recordatorio", sin "glucosa", "diabetes" ni "azúcar", y en pantalla de bloqueo no muestra contenido.
- [ ] Si no confirma, repite la vibración a los `nudgeMinutes` (por defecto 3) **una sola vez**.
- [ ] Fuera del horario o en días no activos no hay recordatorios.
- [ ] Si ya se revisó poco antes del slot (ver regla en 03), el recordatorio de ese slot se omite.
- [ ] Funciona sin internet y tras reiniciar el teléfono.

### H2 — Confirmar revisión (Cesar)
- [ ] Un botón grande "Ya me revisé" en la app y una acción "Listo" en la notificación.
- [ ] Al tocar: vibración corta inmediata (`TAP`). Cuando el servidor confirma la recepción: vibración larga (`CONFIRM`).
- [ ] Sin internet: la revisión queda en la cola local, la UI muestra "Guardado — se enviará al tener internet" y **no** vibra `CONFIRM` hasta que se sincronice.
- [ ] Doble toque accidental en menos de 60 s: se registra una sola revisión.

### H3 — Aviso de revisión (padres)
- [ ] Push: "Cesar se revisó ✓ — 10:40 a. m. · 128 mg/dL ↗".
- [ ] Si LibreLinkUp falla o no está configurado: "Cesar se revisó ✓ — 10:40 a. m." (la revisión nunca se bloquea por el valor).
- [ ] Si el valor está fuera de rango (umbrales configurables): canal de alta prioridad y texto "BAJA" o "ALTA".
- [ ] Si la revisión llegó tarde por falta de conexión: "Cesar se revisó (sin conexión) — A las 10:40 a. m. · sincronizado 11:05 a. m." (textos exactos en 04, que es la fuente de verdad), con el valor más cercano a las 10:40 si está disponible en el historial de LibreLinkUp.

### H4 — Cumplimiento del día (padres)
- [ ] En la pantalla principal: resumen del día con slots esperados, confirmados a tiempo, confirmados tarde, sin respuesta y sincronizados después.
- [ ] Una línea de tiempo del día con cada evento.
- [ ] El resumen se recalcula cuando llegan revisiones atrasadas: un slot marcado "sin respuesta" pasa a "confirmado (sin conexión)" si la revisión se hizo a tiempo según la hora del dispositivo.

### H5 — SOS (Cesar → padres)
- [ ] Botón "Ayuda" que se activa **manteniéndolo presionado 2 s**, con indicador de progreso.
- [ ] En el teléfono de Cesar: sin sonido, solo vibración `CONFIRM` al enviarse.
- [ ] En los padres: canal SOS con sonido de alarma, pantalla completa si se permite y omisión de No Molestar si se concede el acceso.
- [ ] Incluye ubicación si hay permiso (timeout de 5 s, no bloquea el envío) y la última glucosa si se obtiene.
- [ ] **Sin internet:** si la opción está activa, envía un SMS a los teléfonos de los padres (texto exacto en 07; usa `childName`) y además encola el evento.
- [ ] Un padre responde "Voy en camino". Cesar recibe la vibración `SEEN` (2 largas) y el otro padre ve quién respondió.

### H6 — Mensajes padres → Cesar
- [ ] Botones rápidos: "Recuerda la lectura", "¿Todo bien?", "Voy en camino", además de texto libre (máximo 120 caracteres).
- [ ] En Cesar: vibración `MESSAGE` (3 cortas), notificación con título neutro "Mensaje" y texto visible solo con el teléfono desbloqueado.

### H7 — Recordatorio sin respuesta (padres)
- [ ] Si un slot no tiene revisión tras `escalationMinutes` (por defecto 5), los padres reciben "Cesar no ha confirmado el recordatorio de las 10:40 (puede estar sin conexión)".
- [ ] A la vez se envía a Cesar un push de refuerzo, que solo vibra si el refuerzo local no ocurrió (por ejemplo, si el fabricante mató la alarma). Máximo un refuerzo por slot.
- [ ] Máximo un aviso por slot.

### H8 — Configuración (padres)
- [ ] Intervalo (5–240 min), días de la semana, hora de inicio y fin, minutos de escalamiento, minutos de refuerzo, umbrales bajo/alto, activar o pausar.
- [ ] Los cambios llegan al teléfono de Cesar sin que él abra la app (push `sync`).
- [ ] Credenciales de LibreLinkUp, con un botón "Probar lectura".
- [ ] Teléfonos para el SMS de SOS sin conexión.
- [ ] Generar un código de vinculación de 6 dígitos (válido 30 min).

## Patrones de vibración (contrato)

| Nombre | Uso | Patrón (ms: espera, vibra, espera, vibra…) |
|---|---|---|
| `REMINDER` | Recordatorio / refuerzo | `0, 250, 150, 250` |
| `MESSAGE` | Mensaje de padres | `0, 200, 120, 200, 120, 200` |
| `CONFIRM` | Revisión o SOS recibido por el servidor | `0, 700` |
| `SEEN` | Un padre respondió al SOS | `0, 700, 250, 700` |
| `TAP` | Feedback inmediato al tocar | `0, 40` |

Cesar debe practicar con sus padres a distinguir los patrones (hay una pantalla de práctica en Ajustes de la app del niño).
