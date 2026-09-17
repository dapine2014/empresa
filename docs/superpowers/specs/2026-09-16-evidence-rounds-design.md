# Motor de aprobaciones humanas: `REQUEST_MORE_EVIDENCE` dispara re-ejecución — diseño

**Fecha**: 2026-09-16
**Estado**: aprobado por el usuario en brainstorming, pendiente de plan de implementación.

## Contexto y objetivo

Hoy `MissionController.decide` → `MissionService.recordDecision` acepta
`REQUEST_MORE_EVIDENCE` como una de las tres decisiones válidas del
inversionista, pero solo registra el `Decision` en Neo4j (`HAS_DECISION`) y
publica `EMPRESA_MISSION_DECISION_RECORDED` — la misión **no cambia de
estado** y ningún agente vuelve a correr. Es el único de los tres tipos de
decisión sin efecto real sobre el flujo de la misión.

El objetivo de este cambio es que pedir más evidencia dispare una
**re-ejecución real**: los 5 agentes vuelven a correr sus tareas, esta vez
con el motivo del inversionista incorporado, y la misión vuelve a
`AWAITING_INVESTOR` para una nueva revisión — hasta un límite de vueltas
configurable, después del cual el inversionista debe resolver con `APPROVE`
o `REJECT`.

Este documento es el resultado de una sesión de `superpowers:brainstorming`
(path arquitectural) — cada decisión de abajo fue presentada como
alternativas A/B/C al usuario y aprobada explícitamente antes de escribir
este spec.

## Decisiones de diseño

### 1. Alcance de la re-ejecución: los 5 agentes de nuevo, siempre

Una vuelta de `REQUEST_MORE_EVIDENCE` re-ejecuta las 5 tareas fijas
(`sales`/`product`/`finance`/`engineering`/`qa`) completas, no un subconjunto
elegido por el inversionista ni inferido por heurística de texto. Es
consistente con `replanFailedAgents`, que ya re-ejecuta "desde cero", y
evita construir un mecanismo de selección de agentes que hoy no tiene un
caso de uso real.

### 2. Límite de vueltas: `company.max-evidence-rounds`

Nueva propiedad en `AppProperties` (record `@ConfigurationProperties(prefix
= "company")`, junto a `seedCapitalUsd`/`challengeDays`), expuesta como
`MAX_EVIDENCE_ROUNDS` / `company.max-evidence-rounds` con **default 2** en
`application.yml`. `Mission` gana una propiedad real `evidenceRound` (int,
default 0 al crear la misión en `ensureMission`).

El chequeo del límite usa el valor **actual** de `evidenceRound` (antes de
incrementar): si `mission.evidenceRound >= company.max-evidence-rounds`,
`MissionService.recordDecision` lanza `IllegalStateException` — mismo
patrón que el chequeo existente de `DECIDABLE_STATUSES` (cae al handler
default de Spring → 500, convención ya establecida en el proyecto). La
misión queda intacta en `AWAITING_INVESTOR`; el `Decision` **no** se
registra en ese caso (el rechazo ocurre antes de escribir nada). Si pasa el
chequeo, se incrementa a `evidenceRound + 1` y ese es el número de ronda
que se le pasa a `reexecuteAsync`.

Ejemplo con el default (`max-evidence-rounds = 2`): la misión nace con
`evidenceRound = 0` (ronda de ejecución original, tareas `-R0`). Primer
`REQUEST_MORE_EVIDENCE` → `0 < 2`, pasa, ronda pasa a 1 (`-R1`). Segundo
`REQUEST_MORE_EVIDENCE` → `1 < 2`, pasa, ronda pasa a 2 (`-R2`). Tercer
intento → `2 >= 2`, rechazado; el inversionista ya solo puede `APPROVE` o
`REJECT`. Es decir, el default permite **2 vueltas extra** además de la
ejecución original.

### 3. Historial de tareas: `taskId` con sufijo de ronda

Hallazgo del brainstorming: `MissionMemoryService.createTask` hace `MERGE
(t:AgentTask {id:$taskId})` con `taskId = missionId + "-" + agentId` — el
mismo id siempre, incluso hoy en `replanFailedAgents`, así que un replan ya
sobrescribe el `AgentTask` anterior en el mismo nodo (pese a que el
comentario del código dice "desde cero"). Para este flujo, dado que son
decisiones de un inversionista humano (no reintentos internos), se decidió
**no seguir esa convención** y preservar el historial real:

`taskId = missionId + "-" + agentId.toUpperCase() + "-R" + evidenceRound`

La primera ejecución de la misión ya usa `-R0` (no hay caso especial sin
sufijo). Cada vuelta de evidencia crea 5 `AgentTask` nuevos y separados —
`GET /missions/{id}/details` los devuelve todos (orden por `t.id`, agrupa
razonablemente bien por ronda mientras `evidenceRound` sea de un dígito;
aceptable dado el límite bajo de vueltas). `replanFailedAgents` no cambia:
sigue reintentando dentro de la ronda vigente con el mismo `taskId` de esa
ronda.

### 4. Máquina de estados: reentra en `DELEGATING`, no hay estado nuevo

No se agrega un `MissionStatus` nuevo. `MissionExecutor` gana
`reexecuteAsync(missionId, instruction, evidenceRound, investorReasoning)`,
disparado desde `MissionService.recordDecision` (no bloqueante, mismo
patrón `whenComplete` + logging que `start()`). Reentra exactamente el
camino existente: `DELEGATING → WAITING_AGENT_RESULTS → EVALUATING →
CONSOLIDATING → AWAITING_INVESTOR`. Al volver a `AWAITING_INVESTOR`,
`advanceMission` ya dispara `AlertMailService` sin ningún cambio adicional
— el inversionista recibe el mismo correo que en la primera vuelta.

`executeInternal` se refactoriza para aceptar el número de ronda y el
bloque de feedback por agente (ver punto 5) como parámetros, en vez de
asumir siempre ronda 0; la lógica de contradicciones, agentes fallidos y
replanificación no cambia — opera igual sobre los resultados de **la ronda
en curso** (los futures en memoria de esa ejecución, no una relectura de
Neo4j).

### 5. Feedback del inversionista a los agentes: lo reparte el CEO

El campo `reasoning` que ya existe en `DecisionCommand` es la única entrada
de texto libre del inversionista (no se agrega un campo nuevo). Antes de
lanzar la nueva ronda, `MissionExecutor` llama a un método nuevo,
`CeoService.routeInvestorFeedback(instruction, priorAgentResultsText,
investorReasoning)`: una llamada a Ollama con `format` (schema chico, objeto
con las 5 claves fijas `sales`/`product`/`finance`/`engineering`/`qa`, cada
valor un string que puede venir vacío) y sin `tools` — mismo patrón de
"turno final con schema, sin tools" que ya usa `executeAgentTask`, así que
no viola el guard `rejectFormatCombinedWithTools`.

`priorAgentResultsText` se arma releyendo de Neo4j los `AgentTask` de la
ronda anterior (`taskId` con sufijo `-R{round-1}`), deserializando
`t.result` (JSON crudo ya persistido por `updateTask`) a `AgentResult` con
el `jsonMapper` existente, y pasando por `serializeAgentResults` (el mismo
formateador privado que ya arma el bloque de resultados para
`CeoService.executeMission`) — no se inventa un segundo formato.

El resultado (mapa agente→feedback) se antepone al prompt de cada agente en
la nueva ronda como bloque `SOLICITUD DEL INVERSIONISTA`, mismo mecanismo
textual que ya usa `CORRECCIÓN DEL INTENTO ANTERIOR` en
`AgentRuntime.executeInternal` — determinista en cuanto a *dónde* se
inserta, aunque el *contenido* del reparto sí depende de una llamada al
modelo (desviación deliberada y explícita de "nunca el modelo decidiendo el
flujo": acá el modelo decide contenido de un prompt, no una transición de
estado). Si el CEO devuelve vacío para un agente, ese agente igual se
re-ejecuta (punto 1) pero sin bloque adicional en su prompt.

### 6. Eventos Kafka

Nuevo `EMPRESA_MISSION_EVIDENCE_ROUND_STARTED` (cumple la regla dura de
`CompanyEventPublisher.publish()`, prefijo `EMPRESA_`), publicado al
arrancar cada ronda nueva con `missionId`, `evidenceRound` y el `reasoning`
del inversionista como payload. Se documenta en `docs/EVENTS.md` junto al
resto del catálogo. `EMPRESA_MISSION_DECISION_RECORDED` y
`EMPRESA_MISSION_UPDATED` no cambian de forma.

### 7. Lectura de la instrucción original para re-ejecutar

`MissionResponse` (el DTO público) no expone `instruction` hoy y no se le
agrega — evitaría tener que sincronizar `api/types.ts` en el frontend sin
necesidad real. En su lugar, `MissionMemoryService` gana un método angosto
nuevo, `Optional<String> instructionOf(String missionId)`, usado solo
internamente por `MissionService.recordDecision` para pasarle la
instrucción original a `reexecuteAsync`.

## Testing

- `MissionServiceTest`: `REQUEST_MORE_EVIDENCE` dentro del límite incrementa
  `evidenceRound` y dispara `reexecuteAsync`; en el límite lanza
  `IllegalStateException` y no registra `Decision`.
- `MissionExecutorTest`: una nueva ronda crea `AgentTask` con el sufijo
  `-R{n}` correcto y no pisa los de la ronda anterior; `reexecuteAsync`
  reentra en `DELEGATING` y llega a `AWAITING_INVESTOR` de nuevo.
- Test nuevo (unitario, parseo/prompt) para `CeoService.routeInvestorFeedback`:
  mapea el JSON de respuesta del modelo a las 5 claves fijas, tolera claves
  vacías.
- `AgentRuntimeTest` o equivalente: el bloque `SOLICITUD DEL INVERSIONISTA`
  se antepone al prompt solo cuando viene texto no vacío para ese agente.

## Fuera de alcance de esta ronda (documentado, no descartado)

- **Selección de agentes por el inversionista**: se evaluó (pregunta 1 del
  brainstorming) y se descartó — sin caso de uso real hoy, los 5 agentes
  siempre son baratos de re-ejecutar en paralelo.
- **Reconciliación de rondas en curso tras un restart del proceso**: mismo
  límite conocido y no resuelto que ya tiene `MissionExecutor` para
  misiones normales (`CLAUDE.md`, sección "Importante antes de
  reconstruir/reiniciar el contenedor") — una vuelta de evidencia en curso
  durante un `docker compose build && up` queda igual de colgada que
  cualquier misión en `RUNNING` hoy; se corrige a mano por Cypher con el
  mismo procedimiento ya documentado.
- **Editar `company.max-evidence-rounds` por misión**: es un límite global
  de la empresa, no por misión — no hay caso de uso real hoy para variarlo
  caso a caso.
