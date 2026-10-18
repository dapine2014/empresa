# Bitácora de misión: Neo y Aria saben lo que pasa en desarrollo (2026-10-17)

## Por qué

En el Pong de 3 niveles el fundador preguntó «@Iris ¿qué está realizando?» y «@Neo …». Los dos respondieron sobre
`MISSION-KAEL-CRM-1792045685`, una misión que ya no existe. Neo además inventó que estaba «finalizando la planificación del
incremento 1» y que avisaría a Aria «en ~10-15 minutos».

En ese momento la realidad era otra: el Pong iba en la ronda R18 del incremento 3, Kael estaba reparando `game/`, Iris ya
había commiteado su corrección y Neo no tenía ninguna tarea abierta.

Causas:

- **Nadie guarda lo que pasa.** Los ~40 tipos de evento `EMPRESA_*` salen a Kafka, pero nadie los consume. `Mission`
  guarda solo el paso y el mensaje actuales.
- **Una parte del trabajo queda solo en el log del contenedor:** cuándo arranca el sandbox, los rechazos de los gates y
  el modelo que entró de respaldo.
- **El chat no recibe datos reales.** A un `@Agente` sin `MISSION-X` en el mensaje Java no le da ningún dato. El modelo
  recibe solo el historial del chat y rellena lo que falta.

Pedido del fundador: «hay un historial de eventos que Forjai debería leer… Neo debe saber todo lo que pasa a nivel de
desarrollo junto con la Scrum (Aria); así el proyecto esté corriendo en el sandbox, uno de los dos debe saber». Lo aprobó
el 2026-10-17, junto con que las preguntas de estado las responda Java.

## Alcance

1. **Bitácora persistente por misión.** Se arma desde el único punto que publica eventos.
2. **Eventos que faltan.** Hoy esas cosas solo quedan en el log.
3. **Chat.**
   - Las preguntas de estado («¿qué estás haciendo?», «¿qué pasó en MISSION-X?») las responde Java, sin modelo.
   - A Neo y a Aria Java les antepone la bitácora real en cualquier otra mención.
   - El resto de los agentes recibe su propio estado real.
4. **Neo al planificar.** Al planificar un incremento nuevo o una ronda, Neo recibe el resumen de lo que pasó en el
   incremento anterior.
5. **Command Center.** El detalle de la misión muestra la bitácora como línea de tiempo, y Activity la incluye.

Fuera de alcance:

- Consumir Kafka: sigue siendo solo productor.
- Que Neo actúe solo sobre la bitácora (decisiones nuevas): la lee, no decide con ella.
- Ver el sandbox paso a paso en vivo (logs en streaming): los eventos de inicio y fin de cada verificación lo cubren en
  parte.

## Parte 1 — Bitácora

### Modelo

Cada evento `EMPRESA_*` con `missionId` no nulo queda guardado así:

```
(:Mission {id})-[:HAS_EVENT]->(:MissionEvent {id, type, at, agentId, taskId, summary, round, increment})
```

- **`id`:** el `eventId` del `CompanyEvent`, con constraint de unicidad (`CompanyMemoryInitializer`).
- **`summary`:** texto en español armado por Java (`MissionEventText.summary(type, agentId, data)`), de 300 caracteres como
  máximo. No se guarda el payload completo: puede traer resultados de agentes de decenas de KB.
- **`round` e `increment`:** salen de `data.round`, `data.repairRound` o `data.increment` cuando el evento los trae; si
  no, quedan null.
- **Tope:** se guardan los últimos 500 por misión. Al escribir el 501 se borra el más viejo, en la misma transacción.
- **Borrado:** borrar la misión borra también sus `MissionEvent` (igual que `HAS_CONTRACT`).

### Dónde se escribe

`CompanyEventPublisher.publish` llama a `MissionEventLog.record(event)` antes de mandar a Kafka. Reglas:

- Nunca lanza. Si Neo4j falla, se loguea `MISSION_EVENT_LOG_FAILED` y el evento igual sale a Kafka.
- Es sincrónico. Es una escritura chica y así el orden de la bitácora es el de las llamadas.
- Los eventos sin misión (prospección, finanzas, modelos) no se guardan en la bitácora.

### `MissionEventText.summary`

Tiene un `switch` por tipo con plantillas fijas, para los eventos del Development Group y de misión. Por ejemplo:

| Tipo | Resumen |
|---|---|
| `EMPRESA_MISSION_UPDATED` | «Misión → WAITING_AGENT_RESULTS: Desarrollo por capas — Kael (2/3) está generando [game]» |
| `EMPRESA_TASK_COMMITTED` | «Kael commiteó 1 archivo (game/Main.cs) — reparación 2» |
| `EMPRESA_SANDBOX_VERIFICATION_COMPLETED` | «Sandbox FAIL en build: 7 errores de compilación» / «Sandbox PASS: 25 tests» |
| `EMPRESA_MISSION_CORRECTION_STARTED` | «Corrección automática ronda 18» |

Un tipo sin plantilla usa una genérica: «<tipo sin EMPRESA_> por <agente>». El nombre del agente sale de su id con el
roster de `CompanyMemoryService`. Si no se encuentra, se usa el id.

## Parte 2 — Eventos que faltan

Todos tienen `missionId` y algunos tienen `taskId`. Se agregan a `docs/EVENTS.md`.

| Evento | Dónde | data |
|---|---|---|
| `EMPRESA_SANDBOX_STARTED` | `DevelopmentTeamStrategy`, antes de `verifyComponents` | `round` (vuelta del ciclo), `commitSha` |
| `EMPRESA_REPAIR_REQUESTED` | ciclo de reparación, al mandar errores a un dueño | `agentId`, `errors` (cuántos), `repeated` (cuántos `[REPETIDO]`), `files` |
| `EMPRESA_TEST_TRIAGE_COMPLETED` | `triageAndRepair`, después de validar | `verdicts` (lista `test → veredicto`) o `error` |
| `EMPRESA_TASK_ATTEMPT_REJECTED` | `DevelopmentRuntime`, intento con fallas retryable | `attempt`, `reasons` (las 3 primeras, de 200 caracteres como máximo cada una) |
| `EMPRESA_MODEL_SWITCHED` | `CeoService`, donde hoy loguea `MODEL_FREE_FALLBACK`, `MODEL_FALLBACK` y el intento pagado | `operation`, `from`, `to`, `kind` (`FREE`, `PAID`, `PAID_RETRY`, `LOCAL`) |

Para `EMPRESA_MODEL_SWITCHED` la misión sale de `ModelCallContext.current()`. Sin contexto, no se publica.

El resultado del sandbox ya se publica (`EMPRESA_SANDBOX_VERIFICATION_COMPLETED`). Hay que confirmar que su `data` trae
el paso que falló y la cantidad de tests. Si no los trae, se agregan: `failedStep`, `testsPassed`, `testsFailed` y
`compileErrors`.

## Parte 3 — Chat

### 3.1 Preguntas de estado: responde Java

`ChatIntentRouter` agrega una regla después de la gobernanza y antes de la respuesta por modelo de las menciones:

- **`@Agente` + pregunta de estado.**
  - Regex: «qué (estás|está|están) (haciendo|realizando|trabajando)», «en qué (estás|está|andas|anda)», «qué haces»,
    «qué hace» y «estado de @X».
  - Responde `AgentActivity.describe(agentId)`, sin modelo, con el nombre del agente como hablante.
- **«¿qué pasó en MISSION-X?», «bitácora de MISSION-X», «historial de MISSION-X».**
  - Responde `MissionEventLog.recent(missionId, 15)` formateado: hora local del fundador (UTC-5), resumen y agente.
- **«¿qué está pasando en desarrollo?» o «bitácora» sin misión.**
  - Responde con las misiones vivas del Development Group y los últimos 5 eventos de cada una.

`AgentActivity.describe(agentId)` se arma en Java:

- **Misiones vivas:** las que no están en `COMPLETED`, `CANCELLED`, `FAILED` ni `INTERRUPTED`. En `BLOCKED` y
  `AWAITING_*` cuentan como «detenida, esperando al fundador».
- **Por cada misión viva donde el agente tiene tareas:**
  - id, estado y paso;
  - su tarea más reciente (id, acción, estado y hace cuánto);
  - su último evento en la bitácora.
- **Si el paso actual nombra al agente** («Kael (2/3) está generando…»), dice «trabajando ahora».
- **Sin misiones vivas con tareas suyas:** «Libre: sin tareas en misiones activas».
- **Neo:** si no tiene tareas abiertas pero hay una misión viva de su equipo, agrega «coordina como líder técnico: la
  corrección la reparte Java por dueño de archivo; Neo entra en el plan y en el triage de tests».

### 3.2 Neo y Aria con la bitácora

Al mencionar a `engineering` (Neo) o a `product-owner` (Aria) sin una pregunta de estado, `missionData` antepone un bloque
`CeoService.JAVA_MEMORY_DATA_MARKER`. Se arma así:

- **Si el mensaje trae `MISSION-X`:** esa misión.
- **Si no:** las misiones vivas del Development Group, hasta 3.
- **Por cada misión:**
  - estado y paso;
  - incremento y ronda;
  - los últimos 20 eventos de la bitácora;
  - qué agente trabaja ahora.
- **Regla al final del bloque:** «Responde solo con lo que está en estos datos. No prometas tiempos, avisos ni pasos que
  no figuren aquí; si algo no está, di que no lo sabes. Las misiones que no aparecen aquí no están activas».

### 3.3 Los demás agentes

Al mencionar a cualquier otro agente sin una pregunta de estado y sin `MISSION-X`, Java antepone
`AgentActivity.describe(agentId)` con la misma regla. Así nunca responde sobre una misión que no existe.

## Parte 4 — Neo al planificar

`MissionExecutor.buildIncrementAsync` (incremento N > 1) y la ronda de corrección le agregan a la instrucción de Neo un
bloque «LO QUE PASÓ EN EL INCREMENTO ANTERIOR (Java)». `MissionEventLog.incrementDigest(missionId, increment)` lo arma con:

- las rondas de corrección usadas y la que llegó a `VERIFIED`;
- los 5 errores o fallas más repetidos, por huella (`CorrectionReport.fingerprint`), con cuántas rondas aparecieron;
- los veredictos del triage (`CODE_WRONG` / `TEST_WRONG`) por test;
- los agentes con más reparaciones.

Si no hay eventos (misión anterior a la bitácora), el bloque no se agrega.

## Parte 5 — Command Center

- **API:** `GET /api/company/missions/{id}/events?limit=100` devuelve `MissionEventView(id, type, at, agentId, agentName,
  taskId, summary, round, increment)`, del más nuevo al más viejo.
- **MissionDetailPage:**
  - una sección «Bitácora» con la línea de tiempo (hora local, agente, resumen);
  - un filtro por agente y un filtro «solo sandbox», con `SANDBOX_*`, `REPAIR_*` y `TEST_TRIAGE_*`;
  - polling cada 5 s mientras la misión está viva.
- **ActivityPage:** suma los `MissionEvent` recientes de todas las misiones como tipo `EVENT`.
- **Tipos:** se agregan a mano en `api/types.ts`. No hace falta una ruta nueva en `SpaController`.

## Compatibilidad

- Las misiones anteriores no tienen bitácora: el chat y la pantalla muestran «sin bitácora (misión anterior al
  2026-10-17)».
- El orden de las reglas del chat no cambia para lo existente. La regla nueva de estado va justo antes de la respuesta
  por modelo de las menciones.

## Review Focus

1. **Bitácora caída.** Neo4j falla al escribir un evento. La misión sigue y el evento sale igual a Kafka.
2. **Mención a un agente en dos misiones vivas.** `describe` lista las dos, cada una con su tarea más reciente, ordenadas
   por actualización.
3. **Mención sin ninguna misión viva.** El agente responde «Libre» y no nombra misiones borradas, aunque el historial del
   chat las mencione.
4. **Ráfaga de eventos.** Con más de 500 en una misión (una corrección larga), la bitácora se queda en los últimos 500 y
   no crece sin tope.
5. **Pregunta de estado sin @ ni misión** («¿qué hace Iris?»). La regex resuelve el nombre del agente con
   `MentionResolver` y responde igual, sin modelo.
