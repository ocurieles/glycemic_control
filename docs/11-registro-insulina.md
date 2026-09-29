# 11 — Registro de insulina (app de padres)

## Contexto
A veces los padres le ponen la insulina a Cesar mientras duerme y necesitan registrarlo desde su teléfono: rápido, a oscuras y sin despertarlo. Además, el otro padre tiene que enterarse para **evitar una dosis duplicada**.

> **Alcance y seguridad:** esto es un **registro** (bitácora). La app **no calcula, sugiere ni valida dosis** ni reemplaza las indicaciones del médico. Este texto va en "Acerca de" y como nota al pie de la pantalla de registro. El aviso de posible duplicado es **informativo** y no bloquea el registro.

## Historias y criterios de aceptación

### H9 — Registrar una dosis (padre)
*Como padre quiero registrar en segundos la insulina que le puse a Cesar, aunque esté a oscuras y sin internet.*
- [ ] Botón **"Registrar insulina"** siempre visible en Inicio (FAB grande).
- [ ] La pantalla se abre con:
  - la **última insulina usada** preseleccionada,
  - las **unidades** de la última dosis de esa insulina, con botones − y + grandes (paso de 0,5 o 1 según la insulina),
  - la **hora = ahora**, con atajos "hace 15 min", "hace 30 min", "hace 1 h" y "Otra hora…",
  - el **contexto** preseleccionado en "Durmiendo" si la hora está entre `nightStart` y `nightEnd` (por defecto 20:00–06:30),
  - el **sitio** opcional, mostrando el último sitio usado para ayudar a rotar ("Última vez: abdomen"),
  - notas opcionales (máximo 200 caracteres).
- [ ] Si hay conexión, se muestra la **glucosa actual** (última lectura de LibreLinkUp) como referencia.
- [ ] Antes de guardar, un diálogo de confirmación: **"2 U de Humalog a las 2:10 a. m."** con los botones Confirmar y Corregir.
- [ ] Se guarda en el outbox local al instante y funciona sin internet (ver 07).
- [ ] Para registrar una dosis estándar (misma insulina y mismas unidades que la última vez, hora actual) bastan **3 toques**: FAB → Guardar → Confirmar.
- [ ] Modo nocturno: la pantalla usa tema oscuro con brillo reducido entre `nightStart` y `nightEnd`, sin importar el tema del sistema.
- [ ] **Nada** llega al teléfono de Cesar: ni push, ni vibración, ni datos.

### H10 — Aviso al otro padre y posible duplicado
- [ ] El otro padre recibe el push **"Mamá registró 2 U de Humalog (rápida) — 2:10 a. m. · 245 mg/dL ↗"** en el canal `parent_dose`.
- [ ] **Antes de guardar** (en el cliente), si existe otra dosis **no anulada** de la **misma clase** dentro de la ventana `duplicateWindowMinutes` de esa clase, se muestra una advertencia destacada: "⚠️ Papá registró 2 U de Humalog hace 40 min (1:30 a. m.). ¿Registrar igual?". Si no hay conexión: "Sin conexión: no se puede confirmar si otro padre registró una dosis reciente".
- [ ] **En el servidor**, si al crear una dosis existe otra de la misma clase dentro de la ventana y registrada por **otro** padre, ambos padres reciben el push `dose_duplicate` (canal `parent_alert`): "⚠️ Posible dosis duplicada: Papá 2 U (1:30 a. m.) y Mamá 2 U (2:10 a. m.)". Esto cubre el caso en que ambos registraron sin conexión.

### H11 — Corregir o anular
- [ ] Desde el historial, en una dosis registrada por uno mismo en las últimas 24 h: **"Anular"** (con motivo opcional) o **"Corregir"**, que anula la dosis y crea una nueva con los datos editados en una sola operación.
- [ ] Las dosis anuladas se ven tachadas, con quién las anuló y cuándo. **Nunca se borran.**
- [ ] El otro padre recibe `dose_voided`: "Mamá anuló la dosis de 2 U de las 2:10 a. m.".

### H12 — Consultar
- [ ] Tarjeta en Inicio: **"Insulina — últimas 24 h"**, con la última dosis de cada clase ("Rápida: 2 U hace 3 h 20 min · Lenta: 12 U hace 9 h") y el total de hoy por clase.
- [ ] Las dosis aparecen en la **línea de tiempo** del día junto con las revisiones y los SOS (icono de jeringa, hora de aplicación, glucosa si hay).
- [ ] Pantalla **Historial de insulina** filtrable por fecha (últimos 90 días) y clase, con totales diarios.
- [ ] Fase 3: exportar a PDF o CSV para el endocrinólogo, junto con el cumplimiento de revisiones.

### H13 — Configurar insulinas
- [ ] En Ajustes → **Insulinas**: lista editable de `{ nombre, clase, paso }` (por ejemplo "Humalog, rápida, 0,5 U" y "Tresiba, lenta, 1 U").
- [ ] Ventana de duplicado por clase, con defaults rápida 180 min, lenta 720 min, mixta 360 min y otra 180 min.
- [ ] Horario nocturno (`nightStart` y `nightEnd`).
- [ ] El asistente de configuración del padre ofrece agregar las insulinas de la familia; se puede omitir.
- [ ] Al quitar una insulina de la lista, las dosis históricas conservan el nombre, porque se guarda una copia en cada dosis.

## Modelo de datos

### `families/{familyId}.insulins` (padre)
```jsonc
[
  { "id": "uuid", "name": "Humalog", "kind": "rapid", "unitStep": 0.5, "active": true }
]
```
- `kind`: `rapid` | `long` | `mixed` | `other`.
- `unitStep`: `0.5` | `1`.
- Máximo 10 insulinas. Una insulina se desactiva (`active: false`) en vez de borrarse.

### `families/{familyId}.doseSettings` (padre)
| Campo | Tipo | Default | Validación |
|---|---|---|---|
| `duplicateWindowMinutes` | map kind → int | `{ rapid: 180, long: 720, mixed: 360, other: 180 }` | 0–1440 (0 = sin aviso) |
| `nightStart` | `HH:mm` | `"20:00"` | |
| `nightEnd` | `HH:mm` | `"06:30"` | puede ser menor que `nightStart` (cruza medianoche) |

### `families/{familyId}/doses/{doseId}`
Es una colección **separada de `events`**, por dos razones: solo la leen los padres, y admite anulación. `doseId` es un UUID del cliente.

| Campo | Escribe | Notas |
|---|---|---|
| `insulinId`, `insulinName`, `insulinKind` | cliente | copia de los datos de la insulina al momento de registrar |
| `units` | cliente | número, 0,5–100, múltiplo de 0,5 |
| `administeredAt` | cliente | Timestamp de la aplicación (corregido con el offset de reloj); entre 7 días antes y 10 min después de `request.time` |
| `context` | cliente | `sleeping` \| `meal` \| `correction` \| `other` |
| `site` | cliente | opcional: `abdomen`, `arm_left`, `arm_right`, `thigh_left`, `thigh_right`, `buttock_left`, `buttock_right` |
| `notes` | cliente | opcional, ≤ 200 |
| `createdBy`, `createdAt`, `clientAt` | cliente | igual que en events (`createdAt == request.time`) |
| `voided` | cliente | `false` al crear |
| `voidedBy`, `voidedAt`, `voidReason`, `replacedBy` | cliente | solo por anulación (ver reglas) |
| `senderName` | Functions | |
| `glucose` | Functions | lectura más cercana a `administeredAt` (mismo formato que en events) |
| `glucoseError` | Functions | igual que en events |
| `duplicateOf` | Functions | ids de dosis dentro de la ventana, si hay |
| `processedAt` | Functions | idempotencia |

### Reglas (agregar dentro de `match /families/{fid}`)
```
match /doses/{did} {
  function d() { return request.resource.data; }
  allow read: if member(fid) && role() == 'parent';
  allow create: if member(fid) && role() == 'parent'
    && d().createdBy == request.auth.uid
    && d().createdAt == request.time
    && d().clientAt is timestamp
    && d().administeredAt is timestamp
    && d().administeredAt >= request.time - duration.value(7, 'd')
    && d().administeredAt <= request.time + duration.value(10, 'm')
    && d().units is number && d().units >= 0.5 && d().units <= 100
    && d().units * 2 == math.floor(d().units * 2)
    && d().insulinKind in ['rapid', 'long', 'mixed', 'other']
    && d().context in ['sleeping', 'meal', 'correction', 'other']
    && d().insulinName is string && d().insulinName.size() <= 40
    && (!('notes' in d()) || d().notes.size() <= 200)
    && d().voided == false
    && d().keys().hasOnly(['insulinId','insulinName','insulinKind','units','administeredAt','context',
                            'site','notes','createdBy','createdAt','clientAt','clockOffsetMs','voided']);
  // Anulación: solo quien la creó, una vez, dentro de 24 h
  allow update: if member(fid) && role() == 'parent'
    && resource.data.createdBy == request.auth.uid
    && resource.data.voided == false
    && request.time < resource.data.createdAt + duration.value(24, 'h')
    && d().diff(resource.data).affectedKeys().hasOnly(['voided','voidedBy','voidedAt','voidReason','replacedBy'])
    && d().voided == true && d().voidedBy == request.auth.uid && d().voidedAt == request.time;
}
```
Además, en la regla de update de `families/{fid}`, las claves permitidas pasan a ser `['settings', 'childName', 'insulins', 'doseSettings']`, con validación de ambas estructuras.

> **Un punto a validar en F5b:** si `units * 2 == math.floor(units * 2)` no funciona bien con doubles en las reglas, guardar `unitsTenths` como entero (20 = 2,0 U) y documentar el cambio.

### Outbox
Las dosis usan el mismo outbox de 07, que se generaliza en 03 §5 con las columnas `collection` (`events` \| `doses`), `op` (`create` \| `void`) y `payloadJson`.
- "Corregir" crea **dos filas en orden**: primero el `create` de la nueva dosis y luego el `void` de la anterior, con `replacedBy`. El `SyncWorker` respeta el orden por `rowid`.
- `void` es idempotente: si el doc ya tiene `voided == true`, las reglas lo rechazan. Entonces se consulta el servidor y, si ya estaba anulado, la fila se marca `SENT`.

## Backend

### `onDoseCreated` — `families/{fid}/doses/{did}`
1. Idempotencia por `processedAt`.
2. `senderName` del creador.
3. **Duplicados:** busca dosis no anuladas de la misma `insulinKind` con `administeredAt` en `± duplicateWindowMinutes[kind]`. Si hay alguna de **otro** padre: escribe `duplicateOf` y envía `dose_duplicate` a **todos** los padres.
4. Push `dose_logged` a los **otros** padres, sin esperar a la glucosa. La glucosa se agrega en el texto solo si ya estaba en caché.
5. Glucosa (F7): la lectura más cercana a `administeredAt`. Si la dosis es de hace menos de 10 min usa la última lectura; si no, el historial (±10 min, ~12 h de cobertura). Se actualiza el doc; no se envía otro push.

### `onDoseUpdated`
Si `voided` pasa a `true`: push `dose_voided` a los otros padres.

### Payloads nuevos
| `type` | Destino | Campos | TTL | Canal |
|---|---|---|---|---|
| `dose_logged` | otros padres | `title, body, doseId` | 12 h | `parent_dose` |
| `dose_duplicate` | todos los padres | `title, body, doseIds` | 12 h | `parent_alert` |
| `dose_voided` | otros padres | `title, body, doseId` | 12 h | `parent_dose` |

**Nunca** se envía nada de insulina al niño. Hay un test que lo verifica: ningún payload con prefijo `dose_` llama a `sendToChild`.

## App

### Canal nuevo
| id | Rol | Importancia | Sonido | Notas |
|---|---|---|---|---|
| `parent_dose` | padres | DEFAULT | default | el usuario puede silenciarlo de noche desde los ajustes del canal; los duplicados van por `parent_alert` |

### Pantallas
- `DoseEntryScreen`: según H9, con botones de al menos 64 dp, números grandes, tema oscuro nocturno y confirmación.
- `DoseHistoryScreen`: según H11 y H12.
- En Inicio: FAB "Registrar insulina", tarjeta "Insulina — últimas 24 h" y las dosis en la línea de tiempo.
- Ajustes → Insulinas y ventanas (H13).
- Acción rápida opcional: **shortcut del launcher** "Registrar insulina" (mantener pulsado el icono de la app) que abre `DoseEntryScreen` directamente.

### Detección de duplicado en el cliente
Consulta a Firestore (con caché) de `doses` con `insulinKind == k`, `voided == false` y `administeredAt ≥ t − ventana`, más las filas pendientes del outbox local. Requiere el índice compuesto `insulinKind + voided + administeredAt` en `firestore.indexes.json`.

## Pruebas
- **Reglas:**
  - el niño **no** puede leer `doses`;
  - un padre no puede anular una dosis de otro padre, ni anular dos veces, ni anular después de 24 h;
  - se rechazan unidades inválidas (0, 0,3, 101, 2,25) y un `administeredAt` fuera de rango.
- **Unitarias:**
  - la ventana de duplicado por clase, incluido el cruce de medianoche;
  - `nightStart` y `nightEnd` cruzando medianoche;
  - el formato de texto con unidades decimales en `es-VE` ("2,5 U").
- **Outbox:** "Corregir" sin conexión → al sincronizar, queda la nueva dosis y la anterior anulada con `replacedBy`, en ese orden.
- **Campo:**
  - registrar a las 2 a. m. en modo nocturno;
  - ambos padres registran la misma dosis sin conexión → al reconectar, los dos reciben `dose_duplicate`;
  - Cesar no recibe nada.
