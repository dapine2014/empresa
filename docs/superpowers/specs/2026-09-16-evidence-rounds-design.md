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

---

## Revisión 2026-09-27 (reimplementación sobre el código actual)

La rama `worktree-evidence-rounds` quedó ~190 commits atrás (misiones por equipo, sandbox, proveedores remotos,
policies versionadas) y choca con `MissionExecutor`/`MissionService`/`CeoService`. Se **reimplementa** sobre `master`;
lo de arriba sigue vigente salvo donde esta sección lo reemplaza.

### Decisiones del fundador (2026-09-27)

1. **Aplica a todas las misiones**, incluidas las de equipo y Engineering (reemplaza el punto 1: ya no son "siempre los 5").
2. **El límite es una Financial Policy versionada** `MAX_EVIDENCE_ROUNDS` (default 2), editable en Settings con historial
   y rollback (reemplaza `company.max-evidence-rounds` del punto 2). El chequeo es el mismo: `evidenceRound >= límite` →
   `IllegalStateException` sin registrar la `Decision`.
3. **Sobre una misión `FAILED` también re-ejecuta** y consume una vuelta (sirve para reintentar con el comentario).

### Qué re-ejecuta cada tipo de misión

- **Discovery** (sin `teamId`): las 5 tareas fijas, como decía el spec.
- **Equipo de análisis** (Creative, Marketing) y **Engineering**: **se reutiliza el último plan válido** del líder (mismos
  miembros, acciones y, en Engineering, mismo `stackProfile`, contextos y `ownedPaths`), sin volver a planificar. Cada
  miembro vuelve a correr su tarea con el pedido del inversionista. Así una vuelta es una revisión del mismo trabajo y no un
  proyecto distinto. Si la misión falló antes de tener plan (p. ej. en la planificación), el líder planifica de nuevo con el
  pedido del inversionista agregado a la instrucción.
- **Engineering sobre el mismo repositorio**: no se vuelve a crear el scaffold; cada agente regenera su capa **viendo el
  código actual** (como en el ciclo de corrección) y commitea encima (`Forjai-Task` con el `taskId` de la ronda). Después
  corren igual la capa 1, el sandbox con su ciclo de corrección, la revisión de Vera y `validationStatus`. El historial de
  Git conserva cada ronda.

### Ids de tarea (ajusta el punto 3)

La ronda 0 **conserva los ids actuales** (`MISSION-X-SALES`, `MISSION-X-ENGINEERING-PLAN`), para no romper misiones
existentes ni el código que deriva ids; desde la ronda 1 se agrega el sufijo `-R<n>` (`MISSION-X-SALES-R1`). El nodo
`AgentTask` gana la propiedad `evidenceRound`.

### Reparto del pedido (generaliza el punto 5)

`CeoService.routeInvestorFeedback` recibe la lista de agentes de la ronda (5 fijos en discovery, miembros del plan en
equipos) y el schema se arma con esas claves. Usa el modelo del CEO (hoy remoto: `json_object`, la estructura la valida
Java). Si falla o devuelve vacío para alguien, ese agente corre igual con el pedido completo del inversionista (nunca se
pierde el pedido).

### Chat y Command Center

- En el chat, "pide más evidencia sobre MISSION-X …" ya pasa por `recordDecision`: la respuesta dice que arrancó la ronda N
  (o por qué no: límite alcanzado).
- Command Center: sin pantalla nueva; las tareas de cada ronda aparecen en el detalle con su sufijo, y `MAX_EVIDENCE_ROUNDS`
  aparece en Settings junto a las demás policies.

### Testing adicional

- Límite leído de la policy; `FAILED` re-ejecuta; ronda 0 sin sufijo y ronda 1 con `-R1`.
- Equipo: la ronda reutiliza el plan guardado sin llamar al planificador; sin plan, replanifica con el pedido.
- Engineering: la ronda no recrea el scaffold y cada agente recibe el pedido y el código actual.
- `routeInvestorFeedback`: claves dinámicas; si el modelo falla, cada agente recibe el pedido completo.
- En vivo: una misión de discovery y una de Engineering con `REQUEST_MORE_EVIDENCE`, verificando ronda 1, historial y límite.
