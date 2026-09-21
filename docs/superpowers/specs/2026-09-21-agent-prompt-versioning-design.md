# Prompt versionado por agente (los 14) — diseño

**Fecha**: 2026-09-21
**Estado**: aprobado por el usuario en brainstorming, pendiente de plan de implementación.

## Contexto y objetivo

Hoy "el prompt de un agente" no es un dato — es texto Java hardcodeado
repartido en tres archivos (`AgentRuntime.buildPrompt`/`buildTaskSummary`,
`CeoService.systemPrompt`/`toolDecisionSystemPrompt`, el mapeo
`action→objective` de `MissionExecutor`). El usuario pidió una
capacidad transversal nueva: que **cada uno de los 14 agentes** tenga
un prompt propio, **persistido, versionado y editable** desde el
Command Center web, separado explícitamente de las piezas que hoy
garantizan seguridad/consistencia (reglas anti-alucinación, el bloque
FORMATO OBLIGATORIO, el `AgentResultSchema` formal, y cualquier policy
futura) — esas siguen 100% fijas en código, nunca en el prompt
editable.

Separación de conceptos pedida explícitamente por el usuario:

```
Agent identity   → quién es el agente (name, personality)
Role / roleCode  → qué responsabilidad tiene
Capabilities     → qué sabe hacer
Model            → qué LLM utiliza
Prompt           → cómo debe razonar/comportarse dentro de su rol   <- esta ronda
Policies         → qué tiene permitido hacer                        <- sigue en código
AgentTask        → qué trabajo concreto está ejecutando
```

De los 14 agentes, solo 6 tienen ejecución real hoy y por lo tanto el
prompt tendrá efecto observable inmediato: `ceo`, `sales`, `product`,
`finance`, `engineering`, `qa`. Los otros 8 (`devops`, `backend`,
`frontend-ui`, `interaction-design`, `visual-design`, `telemetry`,
`growth-content`, `community`) reciben y versionan su prompt igual que
los demás, pero no tiene ningún efecto hasta que exista ejecución real
para ellos ("Proyecto B", todavía sin diseñar) — guardarlo ahora evita
un cambio arquitectónico nuevo cuando eso ocurra.

## Decisiones de diseño

### 1. `PromptVersion` — nodo nuevo, versionado, inmutable

```
(:PromptVersion {
    id: '<agentId>-v<version>',
    agentId: '<agentId>',
    version: <int, empieza en 1>,
    content: '<texto libre, puede ser vacío>',
    createdBy: 'human',
    changeReason: '<texto libre, obligatorio no vacío al crear>',
    createdAt: <datetime>
})
```

- `(:Agent)-[:HAS_PROMPT_VERSION]->(:PromptVersion)`: una por cada
  versión creada, nunca se borra, nunca se edita (`content`/
  `changeReason`/`createdBy`/`createdAt` son inmutables una vez creado
  el nodo — "editar" siempre crea una versión nueva, jamás pisa una
  vieja).
- `(:Agent)-[:HAS_ACTIVE_PROMPT]->(:PromptVersion)`: **invariante
  dura, garantizada transaccionalmente**: en todo momento existe
  exactamente una de estas relaciones por `Agent` (nunca cero después
  del seed inicial, nunca dos). Activar una versión (sea por edición
  nueva o por rollback a una vieja) es una sola transacción Cypher que
  borra la relación `HAS_ACTIVE_PROMPT` anterior y crea la nueva antes
  de terminar — nunca queda un estado intermedio con cero o dos
  activas.
- `createdBy` fijo al literal `"human"` — mismo criterio ya usado en
  `CustomerMemoryService`/`CustomerController` para acciones que solo
  puede disparar el fundador humano (no hay concepto de usuario/sesión
  en el resto del proyecto, no se inventa uno acá).
- Nuevo constraint de unicidad `prompt_version_id` en
  `CompanyMemoryService.initializeSchema()`.

**Rollback = reactivar una versión existente, nunca duplicar
contenido**: activar la versión 2 después de haber estado en la 5 no
crea una versión 6 con el contenido de la 2 — repunta
`HAS_ACTIVE_PROMPT` directo al nodo `PromptVersion` de la versión 2 ya
existente. El historial de versiones (`HAS_PROMPT_VERSION`) no cambia
al activar/rollback, solo al crear contenido nuevo.

### 2. `PromptMemoryService` — servicio nuevo, dedicado

Mismo patrón que `TeamMemoryService` (no se sobrecarga
`CompanyMemoryService` con un concepto organizativo distinto de
"identidad de agente").

```java
public record PromptVersionSummary(
        int version, String createdBy, String changeReason, Instant createdAt) {}

public record PromptSnapshot(
        String agentId, int activeVersion, String activeContent,
        String activeChangeReason, Instant activeCreatedAt,
        List<PromptVersionSummary> versions) {}
```

- `ensureDefaultPrompts()`: idempotente, llamado desde
  `CompanyMemoryInitializer` justo después de
  `memory.initialize()` (necesita que los `Agent` ya existan). Cypher:
  para cualquier `Agent` sin `HAS_ACTIVE_PROMPT`, crea su versión 1 con
  `content=''`, `changeReason='Versión inicial (seed)'`,
  `createdBy='human'`, y la activa. **Cero cambio de comportamiento**
  en los 6 agentes que ya ejecutan hoy hasta que alguien edite algo a
  propósito — mismo criterio ya usado para el backfill de
  `Agent.model`.
- `activePrompt(String agentId) -> String`: devuelve el `content` de
  la versión activa, o `""` si el agente no tiene ninguna todavía
  (defensivo — no debería pasar en la práctica tras el seed, mismo
  criterio que `agentModel`'s `fallback`).
- `snapshot(String agentId) -> PromptSnapshot`: para el endpoint de
  lectura — versión activa completa + resumen (sin `content`) de cada
  versión del historial, para la lista del front.
- `versionContent(String agentId, int version) -> String`: contenido
  completo de una versión puntual (para previsualizar antes de
  activar). Lanza `IllegalArgumentException` si no existe (misma
  convención del proyecto: sin manejo fino de errores HTTP, cae al
  handler default → 500).
- `createVersion(String agentId, String content, String changeReason) -> PromptSnapshot`:
  una sola transacción (`session.executeWrite`): calcula
  `nextVersion = coalesce(max(version), 0) + 1` sobre las versiones
  existentes de ese agente, crea el nodo `PromptVersion` nuevo,
  `MERGE (a)-[:HAS_PROMPT_VERSION]->(nueva)`, borra la relación
  `HAS_ACTIVE_PROMPT` anterior (`OPTIONAL MATCH` + `DELETE`), crea la
  nueva `HAS_ACTIVE_PROMPT` — todo en la misma transacción, nunca dos
  operaciones separadas. Lanza `IllegalArgumentException` si
  `changeReason` es nulo/vacío o si el `Agent` no existe.
- `activateVersion(String agentId, int version) -> PromptSnapshot`:
  una sola transacción: `MATCH` de la versión objetivo (falla con
  `IllegalArgumentException` si no existe esa versión para ese
  agente), borra la relación `HAS_ACTIVE_PROMPT` anterior, crea la
  nueva apuntando a la versión objetivo — nunca crea un nodo
  `PromptVersion` nuevo.

### 3. API REST nueva (`CompanyController`)

Mismo patrón ya usado para `PUT /agents/{id}/model`:

- `GET /agents/{id}/prompt` → `PromptSnapshot` (versión activa +
  lista de versiones sin contenido).
- `GET /agents/{id}/prompt/versions/{version}` → `String` (contenido
  completo de esa versión puntual, para previsualizar antes de
  activar).
- `PUT /agents/{id}/prompt` (`PromptCommand{content, changeReason}`) →
  crea versión nueva y la activa, devuelve el `PromptSnapshot`
  actualizado. 500 si `changeReason` viene vacío (misma convención de
  errores del resto del proyecto).
- `PUT /agents/{id}/prompt/versions/{version}/activate` → activa esa
  versión existente (rollback), devuelve el `PromptSnapshot`
  actualizado. 500 si la versión no existe.

### 4. Dónde entra en el pipeline real — CEO (`ceo`)

`CeoService` sigue sin depender de Neo4j directamente (invariante ya
documentada: "el único cliente de Ollama"). `systemPrompt()` pasa a
`systemPrompt(String agentPrompt)`: si `agentPrompt` no está en blanco,
se inserta un bloque nuevo después del párrafo de identidad y antes de
las reglas duras ("No inventes clientes..."):

```
CÓMO DEBES RAZONAR (definido por el fundador para vos, no reemplaza las reglas de abajo):
%s
```

`chat(...)` y `executeMission(...)` ganan un parámetro `String ceoPrompt`
cada uno (mismo lugar donde ya reciben `model`), y lo pasan a
`systemPrompt(ceoPrompt)` en vez de `systemPrompt()`. Los 3 call sites
que hoy resuelven `companyMemory.agentModel("ceo", defaultCeoModel)`
(2 en `ChatIntentRouter.chat`-callers, 1 en `MissionExecutor` para
`executeMission`) ganan al lado
`promptMemory.activePrompt("ceo")` y lo pasan como `ceoPrompt`.

### 5. Dónde entra en el pipeline real — los 5 agentes delegados

`AgentRuntime.executeInternal` ya resuelve
`companyMemory.agentModel(agentId, defaultAgentModel)` una vez por
tarea (antes del loop de reintentos). Al lado, resuelve
`promptMemory.activePrompt(agentId)` una vez también, y lo pasa a
`buildPrompt(agentId, action, instruction, agentPrompt)` (nuevo
parámetro). Dentro de `buildPrompt`, si `agentPrompt` no está en
blanco, se inserta el mismo bloque "CÓMO DEBES RAZONAR..." después de
la línea de identidad ("Estás trabajando dentro de Forjai como el
agente %s...") y antes de "REGLAS:".

**`CeoService.executeAgentTask` no cambia de firma** — el prompt del
agente delegado ya viaja embebido dentro del parámetro `prompt`
existente (el `String` que `AgentRuntime` construye en `buildPrompt` y
pasa tal cual como contenido del mensaje `user`). `AgentRuntime` gana
una dependencia nueva a `PromptMemoryService` (mismo patrón que ya
tiene con `CompanyMemoryService`).

### 6. Explícitamente fuera del prompt editable

- **`toolDecisionSystemPrompt`** (el turno corto de decisión de
  `search_web_evidence`) **no cambia** — su contrato de salida es
  binario (un JSON de tool-call exacto o la palabra `NINGUNA`) y es el
  punto de mayor riesgo de romperse con texto libre inyectado. El
  prompt editable del agente **nunca** llega a este turno.
- Las REGLAS anti-alucinación y el bloque FORMATO OBLIGATORIO dentro
  de `buildPrompt`, y el `AgentResultSchema.SCHEMA` (parámetro
  `format` de la llamada a Ollama) **no se tocan** — siguen 100% fijos
  en código. El prompt del agente se agrega como una sección aparte,
  nunca los reemplaza ni se mezcla con ellos.
- El mapeo `action→objective` de `MissionExecutor` (specifico de qué
  tarea concreta se está pidiendo, no de cómo razonar en general) no
  cambia — sigue siendo código fijo, ortogonal al prompt de rol.

### 7. Frontend (`AgentsPage.tsx`)

Click en cualquier tarjeta del organigrama abre un panel/modal con:
- Textarea con el contenido de la versión activa (`GET /agents/{id}/prompt`).
- Campo de texto obligatorio "Motivo del cambio" (`changeReason`).
- Botón "Guardar" → `PUT /agents/{id}/prompt` (crea versión nueva).
- Lista del historial de versiones (`version`, `changeReason`,
  `createdAt`) con botón "Activar" por cada una que no sea la
  actual — confirma, llama a
  `PUT /agents/{id}/prompt/versions/{version}/activate`, refresca el
  panel.
- Sin diff visual entre versiones en esta ronda (fuera de alcance,
  ver abajo).

### 8. Testing

Misma convención ya acordada: sin test directo del Cypher de
`PromptMemoryService` (mismo criterio que `TeamMemoryService`/
`CompanyMemoryService` — verificado en vivo, no unitario). Sí,
unitario real con mocks:
- `AgentRuntimeTest`: nuevo caso verificando que `activePrompt(agentId)`
  se resuelve y su contenido termina en el `prompt` pasado a
  `executeAgentTask`.
- `CeoServiceChatHistoryTest`/`CeoServiceToolFormatGuardTest`/
  `ChatIntentRouterTest`/`MissionExecutorTest`: casos existentes que
  llaman a `chat`/`executeMission` con la firma nueva (`ceoPrompt`
  agregado) se actualizan para pasar un valor fijo de prueba, sin
  cambiar lo que verifican.
- `CompanyControllerTest`: casos nuevos para los 4 endpoints,
  delegación pura a `PromptMemoryService` (mismo patrón que el test ya
  existente de `GET /teams`).

## Fuera de alcance de esta ronda (documentado, no descartado)

- **Ejecución real para los 8 agentes sin `AgentTask` hoy**: guardan y
  versionan su prompt, pero no hay ningún código que lo lea todavía —
  eso es "Proyecto B", sin diseñar.
- **Inyección en `toolDecisionSystemPrompt`**: deliberadamente nunca,
  ver decisión 6.
- **Diff visual entre versiones, o comparar dos versiones lado a
  lado**: la lista de versiones alcanza para esta ronda.
- **Multi-usuario/autenticación**: `createdBy` queda fijo en
  `"human"` — no existe concepto de usuario/sesión en ningún otro
  lugar del proyecto, no se inventa uno acá.
- **Límite de versiones retenidas / purga de historial viejo**: se
  guardan todas, es texto, barato de conservar.
- **Validar el contenido del prompt contra las reglas fijas** (por
  ejemplo, detectar si alguien intentó pegar código que imite el
  bloque FORMATO OBLIGATORIO): no se pidió, y de todos modos no podría
  "ganarle" al `format=AgentResultSchema.SCHEMA` real de la llamada a
  Ollama — el peor caso es un prompt confuso, no un contrato roto.
