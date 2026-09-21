# Proyecto B (subproyecto 1): generación real de código, sin ejecutar ni gastar — diseño

**Fecha**: 2026-09-21
**Estado**: aprobado por el usuario en brainstorming, pendiente de plan de implementación.

## Contexto y objetivo

El spec del Engineering Team (`docs/superpowers/specs/2026-09-20-engineering-team-design.md`)
dejó explícitamente fuera de alcance la "ejecución real de código/infraestructura"
("Proyecto B") — un conjunto enorme de capacidades (Git, workspaces, generación
de código, ejecución sandboxed, builds, tests, CI/CD, artifacts, deployments,
infraestructura cloud, observabilidad, control de costos, políticas de
autonomía, controles de seguridad) que nunca se diseñó como una sola pieza a
propósito: sería especular sobre 15 capacidades a la vez.

Este documento es el **primer subproyecto real** de esa lista, acotado
explícitamente en brainstorming: **generación real de código por los agentes
del Engineering Team, persistida en un repo Git real por misión — sin
ejecutar ese código, sin correr builds/tests, sin tocar infraestructura real,
sin gastar dinero**. Es la base mínima sobre la que se construyen los
subproyectos siguientes (ejecución sandboxed con build+test real, y más
adelante deploy/infra), que quedan fuera de este documento.

**Relación con lo ya implementado**: el Engineering Team (5 agentes reales,
`Team`/`MEMBER_OF`/`LEADS`, modelo LLM real por agente) y `ProductStatus`
(separado de `MissionStatus`, con `DEVELOPMENT`/`QA`/`PUBLISHED` modelados
pero deliberadamente inalcanzables — siempre `false` en
`ProductStatusService`) ya están mergeados. Este subproyecto es exactamente
el que empieza a activar esos hooks: le da a `DEVELOPMENT` su primera señal
real. `QA`/`PUBLISHED` siguen sin señal — son subproyectos futuros.

## Decisiones de diseño

### 1. Disparador: `APPROVE` deja de ser terminal

Hoy, `MissionService.recordDecision` con `APPROVE` sobre una misión en
`AWAITING_INVESTOR` la deja en `MissionStatus.COMPLETED` (terminal). Pasa a:

```
AWAITING_INVESTOR --APPROVE--> EXECUTING --(las 3 tareas de desarrollo asientan)--> COMPLETED
```

`MissionStatus.EXECUTING` es un valor del enum que existe desde el día uno
sin ningún código que lo use (documentado así en `CLAUDE.md`) — es
exactamente el punto de enganche que esta feature activa. `REJECT` y
`REQUEST_MORE_EVIDENCE` no cambian: siguen exactamente igual que hoy.

`MissionService.recordDecision` sigue siendo el punto de entrada
(`POST /missions/{id}/decision`, y el mismo camino desde el chat vía
`ChatIntentRouter`) — al detectar `APPROVE`, en vez de solo persistir
`COMPLETED`, dispara la orquestación de desarrollo (mismo patrón que
`MissionService.start` dispara `MissionExecutor.executeAsync`: persiste el
cambio de estado y lanza el trabajo async, devuelve de inmediato).

### 2. Tres tareas de desarrollo en paralelo, mismo patrón que discovery

Al entrar en `EXECUTING`, se crean 3 `AgentTask` nuevas (mismo mecanismo
exacto que las 5 de `DELEGATING`: `memory.createTask`, evento
`EMPRESA_TASK_CREATED`, ejecución vía un runtime dedicado sobre un pool de
threads, espera individual con `AgentExecutionOutcome` — nunca
`allOf(...).join()`):

| agentId | action | rol |
|---|---|---|
| `engineering` | `ARCHITECTURE_DEVELOPMENT` | Neo — arquitectura + andamiaje inicial del backend |
| `backend` | `BACKEND_DEVELOPMENT` | Iris — lógica de negocio / APIs |
| `frontend-ui` | `FRONTEND_DEVELOPMENT` | Mila — interfaz |

Cada tarea recibe como contexto la instrucción original de la misión **más**
los `AgentResult` ya generados en discovery (en particular `OFFER_DESIGN` de
`product` y `DELIVERY_FEASIBILITY` de `engineering`) — mismo criterio que ya
usa `CeoService.executeMission` para consolidar: nunca le pide al modelo que
re-imagine desde cero algo que la misión ya investigó.

Mismo criterio de "agent failure ≠ mission failure": si alguno de los 3
falla tras agotar reintentos, la misión sigue con resultado parcial (queda
registrado qué agente faltó y por qué); solo si los 3 fallan no hay nada que
escribir a disco y la misión no llega a `COMPLETED` — vuelve a evaluarse
como fallo, consistente con el resto del proyecto. **Sin replanificación
automática en esta primera ronda** (`MissionExecutor.replanFailedAgents` es
específico de discovery; extenderlo a desarrollo es un ajuste pequeño pero
deliberadamente fuera de esta ronda — YAGNI hasta que un caso real lo pida).

### 3. Contrato nuevo: `DevelopmentResult`, no `AgentResult`

`AgentResult` (hechos/hipótesis/evidencia/cálculos/`customerCandidates`) es
el contrato de **discovery** — no tiene sentido para "escribir código".
Contrato nuevo, deliberadamente separado (mismo criterio que llevó a
`ProductStatus` a ser un concepto separado de `MissionStatus`):

```java
public record DevelopmentResult(
        String summary,
        List<GeneratedFile> files
) {
    public record GeneratedFile(String path, String content) {}
}
```

`DevelopmentResultSchema` (nueva clase, mismo patrón que
`AgentResultSchema`): JSON Schema formal con `files` como array de objetos
`{path: string, content: string}`, ambos `required` y con `minLength: 1` —
mismo criterio ya usado en `AgentResultSchema` para forzar que el modelo no
devuelva un archivo vacío o sin ruta.

### 4. `CeoService` gana un método nuevo, `AgentRuntime` no se toca

`CeoService.generateDevelopmentArtifact(agentId, prompt, model) ->
DevelopmentResult` — una sola llamada a Ollama (`format:
DevelopmentResultSchema.SCHEMA`, sin `tools` — no hace falta el turno de
decisión de herramienta de `executeAgentTask` en esta primera ronda, no se
busca evidencia web para generar código). Sigue siendo la única función que
llama a Ollama para esto — `CeoService` sigue siendo "el único cliente de
Ollama".

**`AgentRuntime` no se modifica.** Clase nueva, `DevelopmentRuntime`, espejo
deliberado de `AgentRuntime` (mismo reintento de `MAX_RESULT_RETRIES + 1`
intentos, mismo `memory.setAgentStatus(agentId, "WORKING"/"IDLE")` con
`finally`, mismos eventos `EMPRESA_TASK_STARTED`/`RETRY`/`COMPLETED`/`FAILED`)
pero validando/persistiendo un `DevelopmentResult`, no un `AgentResult`. Se
decidió una clase nueva en vez de generalizar `AgentRuntime` para aceptar un
contrato pluggable — evita tocar código ya probado en producción (discovery)
para una necesidad que todavía tiene un solo caso de uso.

### 5. Gate de seguridad nuevo y obligatorio: rutas de archivo

Cada `GeneratedFile.path` se valida **antes** de escribir nada a disco —
`DevelopmentPathValidationGate` (nueva clase, mismo espíritu que
`EvidenceValidationGate`: falla la tarea de inmediato, **sin reintento**,
si:

- la ruta es absoluta (empieza con `/` o tiene un prefijo de unidad), o
- contiene un segmento `..` (path traversal), o
- está vacía o es solo espacios.

Motivo del "sin reintento" (asimetría deliberada, mismo criterio que
`EvidenceValidationGate`): una ruta insegura no es un error de forma que el
modelo pueda corregir con feedback determinista útil — es una señal de que
algo salió mal, se prefiere fallar la tarea a intentar tres veces
escribiendo por fuera del workspace.

### 6. Workspace: un repo Git real por misión, escritura sin concurrencia riesgosa

Nueva config `products.workspace-root` (default `${user.home}/forjai-products`,
override por env var `PRODUCTS_WORKSPACE_ROOT`). Cada misión que llega a
`EXECUTING` obtiene `products.workspace-root/<missionId>/`.

Cada uno de los 3 `DevelopmentRuntime` **solo escribe archivos a disco**
(dentro de su propio subdirectorio de convención — `architecture/` para
Neo, `backend/` para Iris, `frontend/` para Mila, evitando que dos agentes
en paralelo escriban el mismo archivo) — ninguno toca Git directamente. Una
vez que las 3 tareas asentaron (mismo punto donde `MissionExecutor` ya
espera secuencialmente a los 3 futures, después del bucle de espera, nunca
durante la ejecución paralela), un paso nuevo y secuencial en
`MissionExecutor`:

1. `git init` en el directorio de la misión si no existe todavía
   (`Files.exists(workspaceDir.resolve(".git"))`).
2. `git add -A`.
3. Un solo commit consolidado (`git commit`), autor
   `Forjai Engineering Team <engineering@forjai.local>`, mensaje generado
   a partir de los `summary` de los `DevelopmentResult` que sí completaron.

Ejecutado vía `ProcessBuilder` (no hay ninguna librería Git ya en el
classpath del proyecto) — falla la consolidación (no la misión completa) si
Git no está disponible en el `PATH` del proceso; se loguea `WARN`, mismo
criterio que `AlertMailService.send` (best-effort, nunca tumba el flujo
principal por un problema de infraestructura secundaria).

### 7. Persistencia: mismo mecanismo de `AgentTask`, nuevo `action`

Cada `DevelopmentRuntime` completado persiste su resultado exactamente como
hoy (`MissionMemoryService.createTask`/`updateTask`, `AgentTask.result` como
JSON del `DevelopmentResult` serializado) — **sin nodo nuevo en Neo4j** para
"qué archivos se escribieron": el contenido real vive en el filesystem/Git,
Neo4j solo necesita saber que la tarea ocurrió y con qué resultado, mismo
nivel de detalle que ya se persiste para discovery. Evita inventar un nodo
`CodeArtifact` antes de tener un caso de uso concreto que lo necesite
consultar (YAGNI).

### 8. `ProductStatus.DEVELOPMENT` se vuelve alcanzable

`ProductStatusService.isInDevelopment(missionId)` deja de devolver `false`
fijo — pasa a comprobar si existe alguna `AgentTask` de esta misión con
`action` en `{"ARCHITECTURE_DEVELOPMENT", "BACKEND_DEVELOPMENT",
"FRONTEND_DEVELOPMENT"}` y `status="COMPLETED"` — mismo patrón exacto que ya
usa `isDesigned()` para `OFFER_DESIGN`. `isQaValidated()`/`isPublished()`
**siguen devolviendo `false`** — no hay señal real todavía para "QA sobre un
artefacto real" ni "publicado", eso son los subproyectos siguientes de
Proyecto B.

### 9. Nivel de autonomía: sin gate humano nuevo

`empresa.md` ya lista "realizar operaciones de desarrollo" y "desplegar
ambientes de desarrollo" bajo el nivel 🟡 (autónomo con límites) — esta
feature no gasta dinero real ni toca infraestructura real, así que no
agrega ningún punto de aprobación humana nuevo más allá del `APPROVE` que
ya existe (el nivel 🔴 real de este flujo sigue siendo, como siempre, la
decisión del inversionista).

## Testing

- `DevelopmentResultSchema`/`DevelopmentPathValidationGate`: tests unitarios
  puros (sin Neo4j/Ollama), mismo criterio que `EvidenceValidationGateTest`
  — casos de ruta absoluta, `..`, vacía, y el caso feliz.
- `DevelopmentRuntime`: tests con mocks (`CeoService`, `MissionMemoryService`,
  `CompanyEventPublisher`), mismo patrón exacto que `AgentRuntimeTest` —
  éxito directo, reintento por rechazo del gate de rutas, agotamiento de
  reintentos, `Agent.status` WORKING→IDLE incluso en fallo.
- `MissionExecutor`: casos nuevos para la fase `EXECUTING` — las 3 tareas
  completan y la misión llega a `COMPLETED`; una falla y la misión sigue con
  resultado parcial; las 3 fallan y no hay commit. El paso de `git
  init`/`add`/`commit` se testea con un directorio temporal real (no un
  mock de `ProcessBuilder` — es la forma más simple y realista de probar
  que el commit efectivamente ocurre), limpiando el directorio temporal al
  final del test.
- `ProductStatusServiceTest`: nuevo caso — `ARCHITECTURE_DEVELOPMENT`
  completada produce `DEVELOPMENT`; `QUALITY_RISK_REVIEW`/`DELIVERY_FEASIBILITY`
  (discovery) siguen sin producirlo (ya cubierto, se verifica que sigue
  cubierto).
- **Verificación en vivo** (misma convención ya acordada): antes de mergear
  a producción, correr una misión real de punta a punta hasta `APPROVE` y
  confirmar que el directorio `products.workspace-root/<missionId>/` existe
  con un repo Git real, al menos 1 commit, y archivos reales de los 3
  agentes — documentar en `docs/HISTORY.md`, no solo en tests.

## Fuera de alcance de esta ronda (documentado, no descartado)

- **Ejecutar el código generado** (build/test/run, sandboxed o no) —
  subproyecto siguiente de Proyecto B.
- **Deploy real / infraestructura cloud / gasto real** — subproyectos
  futuros, requieren su propio diseño de políticas de autonomía/costo.
- **Replanificación automática de tareas de desarrollo fallidas** — se
  puede sumar reusando `MissionExecutor.replanFailedAgents` cuando haga
  falta; no se generaliza sin un caso real todavía.
- **Herramientas de búsqueda web durante generación de código** (ej. Iris
  buscando documentación real de una librería) — el turno de
  `generateDevelopmentArtifact` no tiene `tools` en esta ronda; agregarlo
  es una extensión natural pero no se pidió.
- **Nodo `CodeArtifact`/consulta del chat sobre qué archivos existen** — el
  filesystem/Git es la fuente de verdad de los archivos; Neo4j solo sabe
  que la tarea ocurrió. Una consulta de chat tipo "¿qué generó Iris?" queda
  para cuando haya un pedido concreto.
- **Múltiples rondas de desarrollo / iterar sobre código ya generado** — hoy
  es una sola pasada por misión, disparada una vez por `APPROVE`. Qué pasa
  si se necesita generar de nuevo (ej. tras feedback) no está diseñado acá.
