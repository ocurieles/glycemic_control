# Check-in — especificación para construir con Claude Code

App Android familiar para que **Cesar** confirme sus revisiones de glucosa en clase con un toque, con recordatorios **solo por vibración**, y que sus padres lo sepan al momento, con el valor de LibreLinkUp, o después si no había internet. Incluye SOS en ambos sentidos.

## Cómo usar esta carpeta
1. Crea el repositorio y copia el contenido de esta carpeta en su raíz:
   ```bash
   mkdir checkin && cd checkin && git init
   # copia CLAUDE.md, README.md y docs/ aquí
   git add . && git commit -m "docs: especificación inicial"
   ```
2. Haz la **F0** a mano (proyecto Firebase en plan Blaze, alerta de presupuesto, `google-services.json`): ver `docs/10-despliegue.md`.
3. Abre Claude Code en la raíz del repo (`claude`). `CLAUDE.md` se carga solo.
4. Sigue `docs/08-plan-de-implementacion.md`: una fase por sesión, pegando el prompt de la fase, en plan mode, con pruebas en verde antes de avanzar.
5. Termina con el checklist de campo (`docs/09-pruebas.md`) y una semana piloto.

## Lo esencial en una tabla
| Tema | Decisión |
|---|---|
| Plataforma | Kotlin + Compose, un APK con roles padre/niño, distribución por APK |
| Backend | Firebase Blaze (costo esperado USD 0), `us-east1` |
| Recordatorios | Locales (AlarmManager), funcionan sin internet, solo vibración |
| Sin conexión | Outbox en Room + WorkManager; la hora real se conserva; los padres ven "sincronizado después" |
| Glucosa | LibreLinkUp (API no oficial) desde Functions; si falla, la revisión llega igual |
| SOS | Hold de 2 s, ubicación, SMS de respaldo sin datos, "Voy en camino" de vuelta |
| Escalamiento | Aviso a los padres si un recordatorio queda sin respuesta, más un refuerzo al niño |

> Esta app **no sustituye** las alarmas de LibreLink/LibreLinkUp ni la indicación médica.
