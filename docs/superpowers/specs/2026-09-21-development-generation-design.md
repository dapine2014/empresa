# Proyecto B (subproyecto 1): misiones por equipo + generación real de código, sin ejecutar ni gastar — diseño

**Fecha**: 2026-09-21 (versión original) · **Revisión**: 2026-09-24
**Estado**: revisión 2026-09-24 aprobada por el usuario en brainstorming
(4 secciones), pendiente de revisión del spec escrito y de actualizar el
plan (`docs/superpowers/plans/2026-09-21-development-generation.md`).

## Contexto y objetivo

El spec del Engineering Team (`2026-09-20-engineering-team-design.md`) dejó
fuera de alcance la "ejecución real de código/infraestructura" ("Proyecto
B"). La versión original de este documento (2026-09-21) acotó su **primer
subproyecto**: generación real de código por el Engineering Team, persistida
en un repo Git real por misión — sin ejecutar ese código, sin builds/tests,
sin infraestructura real, sin gastar dinero.

**Por qué se revisó (2026-09-24)**: la misión real `MISSION-1790304372795`
("inicia una misión PRODUCTION exclusivamente para el Engineering Team…
crear y desarrollar un videojuego propio") corrió igual el flujo fijo de 5
tareas de discovery de `MissionExecutor` (`sales`/`product`/`finance`/
`engineering`/`qa`, líneas 180-211 — una `List.of(AgentDefinition…)`
hardcodeada que ignora la instrucción). Terminó en `AWAITING_INVESTOR` sin
ningún workspace ni código; el grounding funcionó (Luna: "No hay evidencia de
que el Engineering Team haya desarrollado un videojuego funcional"), pero la
misión no hizo lo pedido. Queda como caso de diagnóstico del comportamiento
anterior — **no se modifica**.

Objetivo revisado: que `Team` sea una **abstracción operacional genérica**.
Una misión puede declarar el equipo responsable (`teamId`); el líder de ese
equipo descompone el trabajo entre sus miembros según
`roleCode`/`capabilities`; y cada equipo ejecuta con su estrategia propia —
Engineering genera código real (workspace + Git + commits por agente +
validación estática de Vera), Creative y Marketing producen `AgentResult` con
el runtime actual. **Criterio de éxito**: ejecutar una misión
`TEAM-ENGINEERING` y observar evidencia real de que el equipo creó un
videojuego (repo, archivos, commits por agente, validación), no solo
`AgentResult` descriptivos.

## Cambios respecto de la versión original (2026-09-21)

| Tema | Original | Revisión 2026-09-24 |
|---|---|---|
| Disparador | `APPROVE` de una misión de discovery → `EXECUTING` | `Mission.teamId` declarado al crear la misión; Engineering desarrolla directamente, sin discovery previo. `APPROVE` vuelve a ser terminal como hoy (`COMPLETED`) |
| Quién trabaja | 3 tareas fijas (Neo/Iris/Mila) | El líder del equipo planifica; en Engineering los 5 miembros reciben tarea |
| Organización de archivos | Subdirectorios fijos por agente (`architecture/`, `backend/`, `frontend/`) | `ownedPaths` declarados en el plan del líder, sin solapamiento |
| Commits | Un solo commit consolidado, autor genérico "Engineering Team", best-effort | Un commit por agente, autor = agente, trailers de misión/tarea; si el commit falla, la tarea falla |
| Artefacto en Neo4j | Nada nuevo | Propiedades en `AgentTask` (`kind`, `workspacePath`, `commitSha`, `files`, …) — sigue sin nodo nuevo |
| QA | Fuera de alcance | Validación **estática** en dos capas (Java + Vera); `ProductStatus.QA` sigue inalcanzable |
| Fin de la misión | `EXECUTING → COMPLETED` automático | `AWAITING_INVESTOR` como toda misión; decide el inversionista |
| `ProductStatus.DEVELOPMENT` | `action` ∈ 3 nombres fijos | `AgentTask` con `kind=WORK`, `COMPLETED` y `commitSha` |
| Equipos | Solo Engineering | Los 3 equipos reciben misiones por `teamId`; solo Engineering genera código |

Se mantienen de la versión original: `DevelopmentResult` + su schema,
`DevelopmentRuntime` como espejo de `AgentRuntime` (este último sin tocar),
`DevelopmentPathValidationGate` sin reintento, `products.workspace-root`,
Git vía `ProcessBuilder`, y la exclusión de ejecución/infra/gasto.

## Decisiones de diseño

### 1. `Mission.teamId`: explícito, determinista, validado

- Propiedad opcional e inmutable del nodo `Mission` (mismo criterio que
  `environment`/`financialCriteria`). `null` = misión sin equipo → flujo de
  discovery actual, **sin cambios funcionales**.
- `MissionCommand`/`MissionResponse` ganan `teamId` (nullable).
- `MissionService.start` valida antes de persistir: el id está en
  `TeamMemoryService.TEAMS` (catálogo fijo: `TEAM-ENGINEERING`,
  `TEAM-CREATIVE-PRODUCT-INTELLIGENCE`, `TEAM-MARKETING-GROWTH`), existe en
  Neo4j y tiene `status='ACTIVE'`. Si no → `IllegalArgumentException` (500,
  convención del proyecto). El LLM nunca lee ni escribe `teamId`.
- **Chat** (`ChatIntentRouter`, ambos caminos de arranque de misión): solo
  reconoce el token exacto `TEAM-[A-Z-]+` en el mensaje, y solo si es uno de
  los 3 ids. Un `TEAM-XYZ` desconocido → mensaje determinista de error y la
  misión **no** arranca (evita que un typo lance discovery). Nunca se infiere
  el equipo de frases ("Engineering Team", "equipo de ingeniería", "que lo
  haga ingeniería"…). Ejemplo válido: *"CEO, inicia una misión para
  TEAM-ENGINEERING para crear un videojuego."* → `teamId=TEAM-ENGINEERING`.
- **Frontend**: selector "Equipo responsable" en el formulario "Iniciar
  misión" de `MissionsPage` (Sin equipo / Engineering Team / Creative /
  Product Intelligence / Marketing & Growth) que envía el `teamId` real
  (`null` para "Sin equipo").

### 2. Arquitectura: planificador genérico + estrategia por tipo de equipo

`MissionExecutor` sigue siendo el dueño de la máquina de estados,
`advanceMission`, `safeFail`, eventos, alertas y consolidación. No existe un
`TeamMissionExecutor` aparte. Única bifurcación, en la resolución de trabajo:

```
MissionExecutor
  ├─ teamId == null → discoveryDefinitions() (las 5 de hoy, extraídas tal cual)
  │                   → AgentTaskBatchRunner
  └─ teamId != null → TeamWorkPlanner → TeamPlanValidator → AgentTasks
                      → TeamExecutionStrategy (por Team.type)
                           ├─ AnalysisTeamStrategy    (CREATIVE_PRODUCT_INTELLIGENCE, MARKETING_GROWTH)
                           └─ DevelopmentTeamStrategy (ENGINEERING)
```

- **`AgentTaskBatchRunner`**: el bloque actual de `MissionExecutor` que crea
  tareas, ejecuta en paralelo vía `AgentRuntime`, espera a cada agente por
  separado y replanifica (`replanFailedAgents`) se extrae **tal cual** a este
  componente. Lo usan discovery y `AnalysisTeamStrategy`. Los tests actuales
  de `MissionExecutorTest` deben pasar sin modificaciones funcionales.
- **Selección de estrategia**: mapeo fijo en código desde `Team.type` (ya
  persistido: `ENGINEERING`, `CREATIVE_PRODUCT_INTELLIGENCE`,
  `MARKETING_GROWTH`) — mismo criterio que `TeamMemoryService.TEAMS`.
- **Contrato común**: toda estrategia devuelve el texto de resultados para
  la consolidación del CEO + la lista de agentes fallidos
  (`AGENTES_FALLIDOS`), para que la consolidación no sepa qué estrategia
  corrió.
- **Estados** (sin valores nuevos en `MissionStatus`): `PLANNING` = plan del
  líder · `DELEGATING` = creación de tareas · `WAITING_AGENT_RESULTS` =
  ejecución · `EVALUATING` = (desarrollo) commits + validación estática ·
  `CONSOLIDATING` · `AWAITING_INVESTOR`.

### 3. `TeamWorkPlanner`: el líder descompone el trabajo

Genérico para los 3 equipos:

1. Resuelve el `Team` desde Neo4j, su líder (`(:Agent)-[:LEADS]->(:Team)`) y
   el roster real: `agentId`, `name`, `role`, `roleCode`, `capabilities` de
   cada miembro (`MEMBER_OF`).
2. Llama a `CeoService.planTeamWork(...)` con el modelo real del líder
   (`Agent.model`), su prompt versionado activo, `format: TeamPlanSchema`,
   **sin `tools`** (regla dura existente: nunca `format`+`tools`).
3. Plan esperado:
   ```
   { summary,
     techStack, entryPoint,                  // exigidos solo por DevelopmentTeamStrategy
     tasks: [{ agentId, kind: WORK|VALIDATION, action, objective,
               requiredCapabilities: [...],
               ownedPaths: [...] }] }       // exigidos solo por DevelopmentTeamStrategy
   ```
4. El plan válido se persiste como `AgentTask` del líder
   (`action=TEAM_PLANNING`, `result` = JSON del plan) — auditable y visible en
   Activity. Es una tarea distinta de la tarea de trabajo del líder (ids
   `<missionId>-<AGENTID>-PLAN` y `<missionId>-<AGENTID>`).

### 4. `TeamPlanValidator`: determinista, reglas comunes + reglas por estrategia

Reglas comunes (los 3 equipos):
- Todo `agentId` es miembro del `Team` (un plan de Engineering con `finance`
  se rechaza).
- A lo sumo una tarea por agente.
- **Compatibilidad de capacidades**: cada elemento de `requiredCapabilities`
  debe coincidir textualmente con una `capability` persistida del agente
  asignado — la asignación queda anclada a datos reales, el modelo no puede
  inventar capacidades.
- `action` con formato `[A-Z_]+`; `objective` no vacío; al menos una tarea
  `WORK`.

Reglas de `DevelopmentTeamStrategy`:
- **Todos** los miembros del equipo reciben exactamente una tarea.
- Exactamente una tarea `VALIDATION`, asignada a un miembro con la
  capability `QA` (en Engineering, solo Vera la tiene).
- Toda tarea `WORK` declara `ownedPaths` no vacíos, relativos y seguros
  (mismas reglas que `DevelopmentPathValidationGate`), sin solapamiento entre
  agentes (ningún path es prefijo de otro de un agente distinto).
- `techStack` y `entryPoint` no vacíos; `entryPoint` cae dentro de los
  `ownedPaths` de alguna tarea `WORK`.

Reglas de `AnalysisTeamStrategy`: no admite tareas `VALIDATION`; no exige
que todos los miembros trabajen.

Rechazo → reintento hasta 3 intentos con bloque `CORRECCIÓN DEL INTENTO
ANTERIOR` (mismo patrón que `AgentRuntime`), `EMPRESA_TEAM_PLAN_REJECTED` por
intento rechazado. Agotados → la misión va a `FAILED` con el motivo exacto.
**No hay plan por defecto**: un fallback ocultaría que el líder no pudo
planificar.

### 5. `AnalysisTeamStrategy` (Creative / Product Intelligence, Marketing & Growth)

Las tareas `WORK` del plan corren vía `AgentTaskBatchRunner` + `AgentRuntime`
actual, sin tocarlo: turno de decisión con `search_web_evidence`, los 3
gates, reintento, replanificación, `ContradictionDetector`, consolidación del
CEO. El `objective` de cada tarea viene del plan del líder. Sin capacidades
de ejecución nuevas para estos equipos en esta ronda.

### 6. `DevelopmentTeamStrategy` (Engineering): código real

**Tareas `WORK` en paralelo** (en Engineering: Neo, Diego, Iris, Mila) vía
`DevelopmentRuntime` (espejo de `AgentRuntime`: `MAX_RESULT_RETRIES + 1`
intentos, `setAgentStatus` `WORKING`/`IDLE` con `finally`, eventos
`EMPRESA_TASK_STARTED`/`RETRY`/`COMPLETED`/`FAILED`), llamando a
`CeoService.generateDevelopmentArtifact(agentId, prompt, model)` (`format:
DevelopmentResultSchema.SCHEMA`, sin `tools`). Contexto del prompt: la
instrucción de la misión, el plan del líder (`summary`, `techStack`,
`entryPoint`), su tarea, y los `ownedPaths`/`objective` de los demás
miembros (para saber qué rutas e interfaces esperar). No ve el código de los
demás — corren en paralelo; los problemas de integración son exactamente lo
que valida Vera.

Contrato sin cambios:

```java
public record DevelopmentResult(String summary, List<GeneratedFile> files) {
    public record GeneratedFile(String path, String content) {}
}
```

**Gates de ruta**:
- `DevelopmentPathValidationGate` (ruta absoluta, con `..`, vacía o solo
  espacios) → falla la tarea **sin reintento** (asimetría deliberada, igual
  que `EvidenceValidationGate`).
- Ruta segura pero **fuera de los `ownedPaths`** del agente → **con
  reintento** y corrección (error de forma corregible).

**Workspace**: `products.workspace-root/<missionId>/`
(`PRODUCTS_WORKSPACE_ROOT`, default `${user.home}/forjai-products`). En
Docker: volumen `~/forjai-products:/data/forjai-products` en
`docker-compose.yml` y `PRODUCTS_WORKSPACE_ROOT=/data/forjai-products` — sin
eso el código se perdería en cada rebuild. La imagen runtime necesita `git`
(agregarlo en el `Dockerfile` si la base `jre` no lo trae).

**Commits por agente** (`DevelopmentWorkspaceService`): una vez asentadas
todas las tareas `WORK`, paso secuencial, por cada agente completado en el
orden del plan:
1. `git init` si `<workspace>/.git` no existe.
2. Escribe sus archivos.
3. `git add` solo de esas rutas.
4. `git commit` con autor `<Agent.name> <<agentId>@agents.forjai.local>`,
   committer `Forjai company-core`, mensaje = `summary` + trailers
   `Forjai-Mission: <missionId>` y `Forjai-Task: <taskId>`.

Resultado: un commit por agente, con solo sus archivos, bidireccionalmente
enlazado a su `AgentTask`.

**Si el commit falla, la tarea falla** (cambio frente al "best-effort" de la
versión original): el commit *es* la evidencia del trabajo. La tarea pasa a
`FAILED` con motivo ("archivos generados pero sin commit") y ningún texto
puede afirmar que ese agente entregó código. "Agent failure ≠ mission
failure" se mantiene: si todas las `WORK` fallan → misión `FAILED`; si parte
falla → continúa parcial. Sin replanificación de tareas de desarrollo en
esta ronda.

### 7. Validación estática (Vera), después de los commits

**Capa 1 — `StaticWorkspaceValidator` (Java puro, sin LLM)**. Lista de
chequeos `{check, status: PASS|FAIL, detail, sha?, paths?}`:
- Cada archivo de `AgentTask.files` existe **en el commit** de esa tarea
  (contra Git, no contra el disco).
- Cada `commitSha` existe en el repo.
- El autor de cada commit corresponde al `agentId` de su tarea.
- El trailer `Forjai-Task` de cada commit corresponde a su `taskId`.
- Ningún archivo del repo cae fuera del workspace ni fuera de algún
  `ownedPaths`; no hay symlinks.
- Estructura mínima: cada tarea `WORK` completada aportó ≥1 archivo.
- El `entryPoint` declarado en el plan existe y no está vacío (se usa la
  ruta declarada, no una heurística por tecnología).

**Capa 2 — revisión de código de Vera**, contrato propio
`StaticReviewResult` (schema formal, `format` sin `tools`, vía
`DevelopmentRuntime`/`CeoService` — no toca `AgentRuntime`):

```
{ verdict: NO_EVIDENT_ISSUES | ISSUES_FOUND,
  findings: [{ path, severity: BLOCKER|MAJOR|MINOR, description }],
  missingFiles: [...],
  architectureConsistency: "...",
  notValidatableWithoutExecution: [...],   // obligatorio, no vacío
  evidence: [ AgentResult.Evidence ... ] }
```

Vera recibe el plan del líder, el resultado de la capa 1, y el árbol + el
contenido real de los archivos leído del repo, con tope (60 KB total, 8 KB
por archivo; los truncados se marcan y se declaran como revisión parcial).
Reintento hasta 3 intentos con corrección, tras estos gates:
- `EvidenceValidationGate` existente, sin cambios.
- **`RepositoryEvidenceGate`** (nuevo): cada evidencia `INTERNAL` debe citar
  `workspace:<missionId>@<sha>/<path>` con un sha real de la misión y un
  archivo existente en ese commit.
- **Guard de afirmaciones prohibidas** (heurística léxica, mismo espíritu que
  `HEDGE_MARKERS`): rechaza `findings`/`architectureConsistency` que afirmen
  que el código compila, se ejecuta, funciona o pasa tests.

**`validationStatus` lo calcula Java, no Vera**:
- `FAILED` — algún chequeo de la capa 1 en `FAIL`, o algún finding `BLOCKER` o
  `MAJOR`, sin importar el verdict (decisión del fundador del 2026-09-25, tras
  la verificación en vivo).
- `UNVALIDATED` — capa 1 pasa pero la revisión de Vera no se completó
  (reintentos agotados).
- `STATICALLY_VALIDATED` — capa 1 pasa y Vera completó sin findings `BLOCKER`
  ni `MAJOR` (los `MINOR` se reportan igual).

Persistido en la `AgentTask` de Vera (`kind=VALIDATION`, `validationStatus`,
`staticChecks` como JSON, `result` = `StaticReviewResult`). Su evidencia pasa
por `MissionMemoryService.recordEvidence` como siempre. `ProductStatus.QA`
**sigue inalcanzable** (QA = validación ejecutada; esto es estático).

### 8. Consolidación y "Estado verificable"

El CEO consolida con: `summary` del plan, commits por agente, chequeos de la
capa 1, veredicto de Vera y `AGENTES_FALLIDOS`. Además, **Java agrega al
mensaje final de la misión un bloque fijo "Estado verificable"**, no
redactado por el LLM: ruta del workspace, sha + archivos por agente,
`validationStatus`, y la frase fija *"Esta fase no ejecuta código: no se
puede afirmar que el juego compile, se ejecute o pase tests."* Así los hechos
verificables nunca dependen de la redacción del modelo. La misión termina en
`AWAITING_INVESTOR`.

### 9. Persistencia

Sin nodos nuevos. Propiedades nuevas:
- `Mission.teamId`.
- `AgentTask`: `kind` (`PLANNING` para la tarea `TEAM_PLANNING` del líder;
  `WORK`/`VALIDATION` para las del plan; ausente en discovery),
  `workspacePath`, `commitSha`, `files` (lista de rutas), y en la tarea de
  validación `validationStatus` + `staticChecks`.

`GET /missions/{id}/details` expone estos campos. `ProductStatusService.isInDevelopment`
pasa a: existe `AgentTask` de la misión con `kind='WORK'`,
`status='COMPLETED'` y `commitSha` no nulo.

### 10. Borrado de misiones

`MissionMemoryService.deleteMission` sigue igual en Neo4j. Además,
`MissionService.delete` borra `products.workspace-root/<missionId>/` del
disco, validando que la ruta resuelta (normalizada, sin seguir symlinks) esté
dentro de `workspace-root`. Si el directorio no existe, no pasa nada.

### 11. Eventos nuevos (prefijo `EMPRESA_`, documentar en `docs/EVENTS.md`)

`EMPRESA_TEAM_PLAN_CREATED`, `EMPRESA_TEAM_PLAN_REJECTED` (por intento),
`EMPRESA_TASK_COMMITTED`, `EMPRESA_STATIC_VALIDATION_COMPLETED`.

### 12. Frontend

- `MissionsPage`: selector "Equipo responsable" (ver §1).
- `MissionDetailPage`: equipo, plan del líder, por tarea `commitSha` +
  `files`, `validationStatus` + chequeos de la capa 1, bloque "Estado
  verificable".
- `api/types.ts` sincronizado a mano con los records Java.

### 13. Nivel de autonomía

Sin gate humano nuevo: generar código sin ejecutarlo ni gastar cae en 🟡 de
`empresa.md` ("realizar operaciones de desarrollo"). La decisión 🔴 sigue
siendo la del inversionista sobre la misión.

## Testing

Unitarios (sin Neo4j/Ollama reales):
- `TeamPlanValidator`: cada regla común y por estrategia, incluido un plan
  de Engineering con `finance` (rechazado), capacidad inventada (rechazado),
  `ownedPaths` solapados, sin `VALIDATION`, miembro omitido.
- `TeamWorkPlanner`: reintento con corrección; agotado → excepción.
- `DevelopmentPathValidationGate`: absoluta, `..`, vacía, caso feliz.
- `DevelopmentRuntime`: mismo patrón que `AgentRuntimeTest` (éxito,
  reintento por `ownedPaths`, sin reintento por ruta insegura, agotamiento,
  `WORKING→IDLE` en fallo).
- `DevelopmentWorkspaceService` y `StaticWorkspaceValidator`: repo Git real
  en directorio temporal (autor por agente, trailers, un commit por agente,
  cada chequeo en `PASS` y en `FAIL`).
- `RepositoryEvidenceGate`, guard de afirmaciones prohibidas, cálculo de
  `validationStatus`.
- `MissionExecutor`: sin `teamId` → las mismas 5 tareas de siempre (tests
  actuales intactos); con `TEAM-ENGINEERING` → nunca se crean tareas para
  `sales`/`product`/`finance`; plan inválido agotado → `FAILED`; con equipo
  de análisis → `AgentRuntime` con los objetivos del plan.
- `MissionService`: `teamId` inexistente/inactivo rechazado.
- `ChatIntentRouter`: token exacto reconocido; `TEAM-XYZ` desconocido no
  arranca misión; frases sin token → `teamId=null`.
- `ProductStatusServiceTest`: `kind=WORK` + `commitSha` → `DEVELOPMENT`.

**Verificación en vivo** (obligatoria antes de dar por terminado; rebuild
solo sin misiones en curso): una misión real `teamId=TEAM-ENGINEERING` de
punta a punta; confirmar en el host el repo, commits por agente con el autor
correcto, `commitSha` de cada tarea igual en Neo4j y en Git, tarea de Vera
con `validationStatus` y evidencia citando shas reales, y bloque "Estado
verificable". Documentar en `docs/HISTORY.md`.

## Fuera de alcance de esta ronda

- Compilar/ejecutar/testear el código generado, sandbox — subproyecto
  siguiente de Proyecto B.
- Deploy, infraestructura cloud, gasto real.
- Replanificación de tareas de desarrollo fallidas.
- Iterar sobre código ya generado (varias rondas).
- Detección del equipo por lenguaje natural en el chat.
- Capacidades de ejecución nuevas para Creative y Marketing.
- Búsqueda web durante la generación de código.
- Nodo `CodeArtifact` / consultas de chat sobre archivos generados.
