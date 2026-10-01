# Development Group, fase 1 — diseño

**Fecha**: 2026-10-01
**Estado**: diseño aprobado por el fundador en conversación (partes 1 a 6); pendiente de revisión de este spec.
**Contexto de la etapa**: Forjai está congelado desde el 2026-10-01 (contenedores detenidos, backup en
`~/forjai-backups/neo4j-20260930-235639/`). El fundador decidió construir primero el corazón, el área de desarrollo,
hasta que demuestre que entrega software real; después las demás áreas, y al final conectarlas.

## Contexto y objetivo

Con productos reales elegidos por el orquestador, Engineering nunca llegó a `VERIFIED`. Fallos observados en la base
(2026-09-30): archivos con `...` en lugar de código (72 errores en `MISSION-1790796941181`), JSON cortado
(`Unexpected end-of-input`), tipos del dominio redefinidos en Application, tres bounded contexts para un generador de
firmas, y ninguna corrección posible más allá de 2 rondas de compilación. En pruebas acotadas (`SANDBOX-VERIFY-20/21/23`,
`E2E-ENG`) sí llegó a `VERIFIED`: la verificación funciona, la generación y el alcance no.

**Regla n.º 1 del Development Group**: no es un sistema de generación de código sino de **entrega de software**.

- *Entrada*: una idea, una HU o un objetivo técnico. El fundador define **qué**; el equipo decide **cómo**.
- *Salida*: software real, versionado, probado y verificado (código, Git, arquitectura, tests, artefactos, evidencia y
  resultado del sandbox). Una misión solo está entregada cuando hay evidencia verificable.

## Decisiones del fundador (2026-10-01)

1. **Tres fases**, una por prueba del brief: fase 1 (este spec) termina con un Hello World en Flutter Web en `VERIFIED`;
   fase 2 agrega PostgreSQL en el sandbox (CRUD Flutter + ASP.NET Core + PostgreSQL) y la entrega de Andrea; fase 3
   agrega juegos (Godot con Kael, Orion con Blender, Luna con arte y audio).
2. **`TEAM-DEVELOPMENT` reemplaza a `TEAM-ENGINEERING`.**
3. **Luna pasa solo al Development Group** (deja Discovery). Sofía sigue siendo el agente comercial; Andrea es un
   agente nuevo.
4. **BDD con trazabilidad verificada por Java** (opción A): cada escenario de Aria tiene un id y debe tener al menos un
   test que corrió y pasó en el sandbox.
5. **Corrección hasta cero errores**, sin tope de rondas, con log de auditoría. Si el mismo error persiste 5 rondas, el
   equipo busca una estrategia nueva investigando en internet y avisa del retraso. Salvaguarda aceptada: con 3
   estrategias fallidas para el mismo error, la misión queda `BLOCKED` esperando al fundador.
6. **Las misiones de desarrollo no viven en memoria**: se guardan por checkpoints y se retoman tras un reinicio.
   Cola persistente: primero lo urgente, después por orden de llegada.
7. **El backlog entra según el plan de desarrollo**: Aria ordena el backlog en incrementos; cada incremento `VERIFIED`
   encola el siguiente sin esperar aprobación. Rechazar un incremento pausa el plan.
8. **El fundador aprueba las HUs antes de que se programe nada**, mientras el equipo se entrena en aplicaciones y
   juegos. Es temporal: queda detrás de la policy `SPEC_APPROVAL_REQUIRED` (1 = activa), para apagarlo sin cambiar
   código cuando el equipo esté entrenado.
9. **Instrucciones del fundador por chat**: antes de aprobar, ajustan las HUs; después de aprobar, todo cambio va al
   final del backlog como un incremento nuevo, sin interrumpir lo que se está construyendo.

## 1. El grupo y sus agentes

`TEAM-DEVELOPMENT` (`name: "Development Group"`, `type: "DEVELOPMENT"`, `status: "ACTIVE"`, líder Neo) reemplaza a
`TEAM-ENGINEERING` en `TeamMemoryService.TEAMS` y `KNOWN_TEAM_IDS`. Migración idempotente al arrancar: el nodo
`Team {id:'TEAM-ENGINEERING'}` se renombra (sus `MEMBER_OF`/`LEADS` se conservan) y `Mission.teamId` de las misiones
viejas se actualiza. Los ids de agente no cambian (identidad, historial y memoria); cambian `role`, `roleCode` y
`capabilities`, que son propiedades distintas de `model`.

| Agente (id) | roleCode | capabilities (atómicas) | Qué escribe | Fase |
|---|---|---|---|---|
| Aria (`product-owner`, nueva) | `PRODUCT_OWNER` | requirements, user-stories, bdd, acceptance-criteria, backlog | Sin código: spec y plan de incrementos | 1 |
| Neo (`engineering`), líder | `TECH_LEAD` | architecture, planning, technical-review, integration | Sin código: plan técnico y estrategias | 1 |
| Iris (`backend`) | `BACKEND` | backend, api, csharp, dotnet, business-logic, integrations, authentication | DOMAIN, APPLICATION, API | 1 |
| Diego (`devops`) | `DATA_ARCHITECT` | postgresql, data-modeling, persistence, migrations, ef-core | INFRASTRUCTURE | 1 (PostgreSQL en 2) |
| Mila (`frontend-ui`) | `UI_UX` | flutter, dart, ui, ux, design-system, web-ui, game-ui | PRESENTATION | 1 |
| Kael (`interaction-design`, sale de Creative) | `GAME_DEV` | godot, csharp, gameplay, game-loop, physics | GAME | 3 |
| Orion (`specialist-3d`, nuevo) | `SPECIALIST_3D` | blender, 3d-modeling, rigging, gltf | Assets 3D | 3 |
| Luna (`product`, sale de Discovery) | `CREATIVE` | 2d-art, textures, audio, sfx | Assets 2D y audio | 3 |
| Vera (`qa`) | `QA` | qa, bdd, tests, regression, code-review | TESTS + revisión (VALIDATION) | 1 |
| Andrea (`delivery`, nueva) | `DEVOPS` | ci-cd, docker, aws, packaging | Entrega (Dockerfile, CI) | 2 |

- `RoleLayerCatalog` pasa a mapear los roleCodes nuevos (revisión 4). Java sigue calculando las capas y rutas de cada
  rol: el modelo no reparte archivos (verificado que no convergía, ver `RoleLayerCatalog`).
- `StackProfile`/`RoleLayerCatalog` declaran en qué fase está soportado cada roleCode. En fase 1 el validador rechaza
  asignar `GAME_DEV`, `SPECIALIST_3D`, `CREATIVE` y `DEVOPS`.
- Modelos por defecto (editables en Agents): Aria `nvidia-discovery:nvidia/nemotron-3-ultra-550b-a55b`; Orion y Andrea
  `nvidia:moonshotai/kimi-k3`. Suplente local como el resto.
- El equipo Creative queda con Maya y Gael. Los prompts versionados de Kael y Luna se conservan; el fundador puede
  editarlos en Agents.
- **Pendiente obligatorio antes de descongelar Forjai**: la tarea `OFFER_DESIGN` de Discovery (`MissionExecutor`) y
  `ProductAutomation.PRODUCT_AGENT` siguen apuntando a `product` (Luna). Hay que asignarles otro dueño. No se toca en
  esta fase porque Discovery no corre.

## 2. Entrada: `DevelopmentRequest`

Campos: `requestId`, `type` (`IDEA` | `HU` | `OBJECTIVE`), `title`, `description`, `acceptanceCriteria[]` (puede venir
vacío), `environment` (`PRODUCTION` | `TEST`, default `PRODUCTION`), `requestedBy`, `priority` (`URGENT` | `NORMAL`,
default `NORMAL`). Opcionales: `productType`, `constraints`, `suggestedStack`.

- Nodo `(:DevelopmentRequest)` con `status` (`QUEUED` | `RUNNING` | `AWAITING_SPEC_APPROVAL` | `DONE` | `BLOCKED` |
  `PAUSED` | `CANCELLED`) y
  `queuedAt`. Validación Java al crear: `title` y `description` no vacíos, `type` del enum, `suggestedStack` (si viene)
  del catálogo.
- `POST /api/company/development/requests` crea y encola; `GET` lista la cola en orden;
  `PUT /api/company/development/requests/{id}/priority` cambia la prioridad.
- Cada misión de desarrollo es un `Mission` normal con `teamId = TEAM-DEVELOPMENT`, unida con
  `(:Mission)-[:FROM_REQUEST]->(:DevelopmentRequest)`. Reutiliza estados, decisión del fundador, rondas de evidencia y
  borrado.

## 3. Etapa de Aria: `RequirementsSpec` y plan de incrementos

Aria corre primero (tarea `REQUIREMENTS_SPEC`, `format` = schema, sin `tools`). Recibe el pedido completo y, si el
pedido es un incremento siguiente, el spec y el código ya existentes.

`RequirementsSpec`:
- `summary`, `assumptions[]` (ambigüedades resueltas y cómo), `outOfScope[]`.
- `userStories[]`: `id` (`HU-01`), `asA`, `iWant`, `soThat`, `acceptanceCriteria[]`, `dependsOn[]` (ids de HU),
  `scenarios[]` con `id` (`ESC-01`), `title`, `given[]`, `when[]`, `then[]`.
- `increments[]`: `number` (1 = MVP), `goal`, `userStoryIds[]`.
- `criteriaCoverage[]`: por cada criterio del pedido, los escenarios que lo cubren.

Según el tipo: `IDEA` → Aria la divide en HUs; `HU` → la conserva y la completa; `OBJECTIVE` → HUs técnicas.

**`RequirementsSpecValidator` (Java, puro)**, hasta 3 intentos con `CORRECCIÓN DEL INTENTO ANTERIOR`:
- ≥1 HU; cada HU con ≥1 escenario; `given`/`when`/`then` no vacíos.
- Ids con formato exacto (`HU-\d{2}`, `ESC-\d{2}`) y únicos en todo el spec (los ids de escenario no se reutilizan
  entre incrementos).
- Cada HU en exactamente un incremento; incrementos numerados desde 1 sin huecos.
- Cada incremento tiene ≤ 12 escenarios.
- `dependsOn` apunta a HUs existentes, sin ciclos, y nunca a una HU de un incremento posterior.
- Cada criterio del pedido aparece en `criteriaCoverage` con ≥1 escenario existente.

Si no pasa en 3 intentos, la misión termina `FAILED` con los motivos y Neo no corre. El spec queda en la tarea de Aria y
en `(:DevelopmentPlan)-[:HAS_INCREMENT]->(:Increment)` unido al pedido. Los demás agentes reciben el spec armado por Java.

### Aprobación de las HUs (mientras `SPEC_APPROVAL_REQUIRED = 1`)

- Spec válido → el pedido pasa a `AWAITING_SPEC_APPROVAL` (y la misión del incremento 1 queda esperando antes del plan
  de Neo). Correo al fundador, evento `EMPRESA_DEVELOPMENT_SPEC_READY` y aviso en el chat. El despachador toma el
  siguiente pedido de la cola mientras tanto: un pedido esperando aprobación no ocupa el cupo de
  `MAX_PARALLEL_DEVELOPMENT`.
- El fundador ve HUs, criterios, escenarios, incrementos y supuestos en el chat ("muéstrame las HU de <pedido>") y en
  Missions.
- **Ajustar**: una instrucción del fundador (§11) sobre un pedido en `AWAITING_SPEC_APPROVAL` vuelve a correr Aria con
  el spec anterior y todas las instrucciones recibidas; el nuevo spec pasa por el mismo validador y se vuelve a
  presentar. Sin límite de ajustes.
- **Aprobar**: `POST /api/company/development/requests/{id}/spec-approval` o chat ("apruebo las HU de <pedido>") →
  `QUEUED` de nuevo y la misión sigue con el plan de Neo. Queda registrado quién y cuándo aprobó qué versión del spec
  (`approvedSpecVersion`); lo que se construye es siempre esa versión.
- **Rechazar**: "rechaza el pedido <pedido>" → `CANCELLED`.
- Las HUs que se agregan después (instrucciones posteriores, §11) también esperan aprobación antes de programarse.
- Con `SPEC_APPROVAL_REQUIRED = 0`, el spec válido pasa directo al plan de Neo.

## 4. Plan de Neo y validación

Neo recibe el spec del incremento actual, los miembros con sus capacidades y el catálogo de stacks soportados en esta
fase (`StackProfile.describe()`); `format` = schema, sin `tools`, como hoy.

`TeamPlan` (se extiende el record actual): `selectedStack`, `architecture`, `boundedContexts`, `ubiquitousLanguage`, y
`tasks[]` con `agentId`, `kind`, `action`, `objective`, `requiredCapabilities`, `dependsOn[]` (ids de tarea) y
`scenarioIds[]`. Se elimina `participationConflicts`.

Java completa (`TeamPlanResolver`): `ownedPaths` y `entryPoint` por rol y perfil, y las aristas que exige el orden de
capas (p. ej. PRESENTATION depende de DOMAIN). Neo puede agregar aristas, no quitar esas.

`TeamPlanValidator` (reglas de desarrollo reescritas) rechaza si:
- un agente no existe, no es miembro o su roleCode no está soportado en esta fase;
- `selectedStack` no está soportado (fase 1: `FLUTTER_WEB_APP`, `DOTNET_APP`);
- la capa DOMAIN de algún contexto o los archivos de entrada del perfil quedan sin dueño (las demás capas pueden
  quedar vacías; Mila toma DOMAIN/APPLICATION/INFRASTRUCTURE por respaldo si no participa Iris);
- una `requiredCapability` no es atómica (contiene `,` `;` `/` o más de 3 palabras) o no figura textualmente entre las
  del agente (nunca se modifican las capacidades para que el plan pase);
- un agente tiene más de una tarea, salvo el agente `QA`, que tiene exactamente una WORK (tests) y una VALIDATION;
- falta la tarea de tests o la VALIDATION;
- un `dependsOn` apunta a una tarea inexistente o el grafo tiene ciclos;
- dos tareas comparten `ownedPaths` o un archivo tiene dos dueños;
- un escenario del incremento no figura en `scenarioIds` de ninguna tarea WORK de código.

Se eliminan las reglas "todos los miembros con tarea" y "el líder con tarea". Plan inválido → replanifica con la lista
exacta de errores, hasta 3 intentos; después `FAILED` sin commitear nada.

**Ejecución**: orden topológico del DAG, **secuencial** (decisión del fundador tras 8 misiones en paralelo sin compilar);
empate → orden de capas. Tests de Vera después del código; VALIDATION al final.

## 5. Desarrollo y Git

Igual que hoy (`DevelopmentRuntime`, `DevelopmentWorkspaceService`, `ProjectScaffold`, contrato de API de
`PublicApiExtractor`, `codeBudget`): un workspace por **plan** (los incrementos trabajan sobre el mismo repositorio,
sin scaffold nuevo), un commit por tarea con el agente como autor y trailers `Forjai-Mission`/`Forjai-Task`; lo escrito
fuera de `ownedPaths` se descarta. Cada `AgentTask` registra entrada, salida, `ownedPaths`, `commitSha`, estado y horas.

Dos arreglos nuevos a la generación:
1. **`ElidedCodeGate`** (Java, puro): rechaza con reintento un archivo con marcadores de código omitido: una línea que
   solo contiene `...` o `…`, comentarios `// ...`, `/* ... */`, `# ...`, frases "resto del código", "el resto igual",
   "rest of the code", "TODO: implement(ar)", y `throw new NotImplementedException()` / `UnimplementedError()` fuera de
   tests.
2. **Entrega por partes**: si la respuesta se corta (`finish_reason = "length"` en la API compatible con OpenAI,
   `done_reason = "length"` en Ollama, o JSON terminado a mitad de un string/objeto), Java pide la continuación con la
   lista de rutas ya recibidas. El schema de desarrollo agrega `complete: boolean`; Java sigue pidiendo lotes hasta
   `complete = true` o hasta que un lote no traiga rutas nuevas (eso cuenta como un intento fallido).

## 6. Sandbox: seis pasos y tests con nombre

`checkout → restore → build → test → startup → smoke` (hoy el arranque está dentro de `smoke`). Cambios en
`sandbox/images/<perfil>/run.sh` y en `sandbox-runner`:

| Paso | `FLUTTER_WEB_APP` | `DOTNET_APP` |
|---|---|---|
| startup | servidor HTTP local sirve `index.html` y `main.dart.js` con 200 | el proceso arranca y el puerto escucha dentro del timeout |
| smoke | Chrome headless renderiza la app (`flutter-view`/`flt-glass-pane`) sin `Uncaught` | `GET /health` responde 200 |

El runner devuelve además `testCases[]` (`name`, `outcome`, `message`) parseados del TRX o del JSON de `flutter test`,
nunca del texto. `SandboxResult` gana `testCases` y los pasos nuevos; una `Evidence` por paso como hoy.

## 7. Verificación y trazabilidad

- Convención de nombres (en el prompt de Vera y verificada por Java): .NET, el método contiene `ESC_01`; Flutter, la
  descripción del test contiene `ESC-01`.
- `ScenarioCoverage` (Java, puro): para cada escenario del incremento, los `testCases` que lo nombran y su resultado.
  Un escenario sin test o con algún test no aprobado es un error de verificación.
- `StaticValidationStatus.compute` pasa a exigir para `VERIFIED`: chequeos estáticos OK; build, test, startup y smoke
  en PASS con ≥1 test; cobertura completa de escenarios; ningún `BLOCKER` de Vera (los `MAJOR` quedan como riesgo,
  decisión del fundador). `UNVALIDATED` si el sandbox o la revisión no corrieron. Lo escribe solo Java.

## 8. Corrección hasta cero errores

Tras cada verificación, `CorrectionPlanner` (Java) convierte cada fallo en un error con **huella** estable
(`tipo|archivo|código|mensaje normalizado`, sin números de línea ni rutas temporales) y lo asigna:

| Fallo | Responsable | Recibe |
|---|---|---|
| Chequeo estático | dueño del archivo | chequeo y regla violada |
| Compilación | dueño del archivo (antes, `MissingUsingFixer` sin modelo, como hoy) | errores reales con la línea |
| Test de escenario que falla | dueños del código de ese escenario (`scenarioIds`); no pueden tocar tests | test, salida y escenario |
| Test que no compila | Vera | errores del archivo de test |
| Escenario sin test | Vera | ids faltantes |
| startup o smoke | dueño del `entryPoint` | log del paso |
| `BLOCKER` de Vera | dueño del archivo señalado | hallazgo |

**Ronda**: cada responsable corrige y commitea; se re-verifica todo (estático y luego sandbox). Se repite mientras
quede ≥1 error, **sin tope de rondas**. Una corrección sin cambios se insiste una vez; una que agrega errores se
revierte al commit anterior (queda en el log como regresión).

**Estrategia nueva**: cuando una huella persiste `SAME_ERROR_ROUNDS_BEFORE_STRATEGY` rondas (policy, default 5), Neo:
1. busca en internet con `WebSearchPort` + `WebPageFetcher` (en `company-core`; el sandbox sigue sin red), con el
   mensaje de error, el stack y la versión;
2. propone una estrategia (`format`, sin `tools`): reescribir el archivo desde cero, cambiar el enfoque, reasignar las
   rutas a otro miembro con las capacidades, o pedir una dependencia (pasa por `DependencyService`);
3. Java exige que cite ≥1 URL de las páginas leídas (misma regla que `EvidenceBindingGate`) y que sea distinta de las
   ya intentadas para esa huella; una reasignación pasa por `TeamPlanValidator`.

Cada estrategia avisa al fundador: correo, evento `EMPRESA_DEVELOPMENT_DELAYED` y línea en el status del chat (misión,
error, rondas, estrategia, fuentes).

**`BLOCKED`**: con `MAX_STRATEGIES_BEFORE_BLOCK` (policy, default 3) estrategias fallidas para la misma huella, la
misión pasa a `BLOCKED` (estado nuevo de `MissionStatus`, con correo). Los demás errores se siguen corrigiendo. El
fundador decide con el endpoint actual: `REQUEST_MORE_EVIDENCE` con su indicación → el equipo sigue con ella;
`REJECT` → `CANCELLED`. Esa indicación sobre una misión `BLOCKED` no consume `MAX_EVIDENCE_ROUNDS`: retoma las rondas
de corrección con la indicación del fundador antepuesta al pedido de cada responsable.

**Log de auditoría**: `(:Mission)-[:HAS_CORRECTION_ROUND]->(:CorrectionRound {number, startedAt, endedAt, fromSha,
toSha})-[:HAS_ERROR]->(:CorrectionError {fingerprint, kind, file, output, assignedTo, request, response, changedFiles,
resolved, occurrences, regression})` y `(:CorrectionStrategy {fingerprint, attempt, proposal, sources[], outcome})`.
La salida de cada paso se guarda completa. Evento `EMPRESA_CORRECTION_ROUND_COMPLETED` por ronda.

## 9. Misiones durables, cola y plan por incrementos

**Cola**: `DevelopmentDispatcher` toma el siguiente `DevelopmentRequest` `QUEUED`: primero `URGENT`, después por
`queuedAt`. Corren a la vez como máximo `MAX_PARALLEL_DEVELOPMENT` (policy, default 1). Se dispara al encolar, al
terminar una misión y con un chequeo `@Scheduled` cada minuto.

**Checkpoints**: la misión de desarrollo es una secuencia de etapas persistidas
(`(:Mission)-[:HAS_STAGE]->(:DevelopmentStage {key, status, output, commitSha, startedAt, endedAt})`): spec, plan, cada
tarea del DAG, cada verificación, cada ronda y cada estrategia. Una etapa se marca `RUNNING` antes de ejecutarse y
`COMPLETED` con su salida después. Ejecutar una etapa `COMPLETED` no hace nada, así que retomar es seguro. Discovery y
los otros equipos siguen con el executor actual.

**Reconciliación al arrancar** (`ApplicationReadyEvent`, después de `CompanyMemoryInitializer` y antes de aceptar
trabajo nuevo):
1. Agentes en `WORKING` → `IDLE`.
2. Por cada misión de desarrollo no terminada: valida que el workspace exista y que `HEAD` coincida con el último
   commit registrado; si no, descarta lo no commiteado (`reset --hard` al SHA registrado). Si el workspace no existe,
   la misión pasa a `BLOCKED` con el motivo.
3. Etapas `RUNNING` → `PENDING` (se re-ejecutan desde cero).
4. Retoma en orden: urgentes, en curso y luego la cola. Cada retoma queda en el log; evento
   `EMPRESA_MISSION_RESUMED` y línea en el status del chat.

**Plan por incrementos**: cada incremento del `DevelopmentPlan` es una misión sobre el mismo repositorio. Cuando un
incremento llega a `VERIFIED`, el siguiente se encola solo, en el orden del plan y con la prioridad del pedido. Cada
incremento llega al fundador en `AWAITING_INVESTOR` con su `DeliveryResult`. `REJECT` de un incremento pausa el plan
(`DevelopmentRequest.status = PAUSED`); un incremento `BLOCKED` frena los siguientes. Reanudar un plan pausado:
`PUT /api/company/development/requests/{id}/resume` y comando de chat.

## 10. Entrega: `DeliveryResult`

Calculado por Java desde la memoria en cada consulta; nunca lo escribe un modelo. `GET
/api/company/development/missions/{id}/delivery`. Campos: `missionId`, `productName`, `stack`, `architecture`,
`repository`, `branch`, `commitShas[]`, `agents[]`, `completedTasks[]`, `buildResult`, `testResult`, `startupResult`,
`smokeTestResult`, `validationStatus`, `scenarioCoverage[]`, `artifacts[]`, `evidence[]`, `risks[]` (`MAJOR` de Vera y
supuestos de Aria), `pendingWork[]` (incrementos siguientes, `outOfScope` y errores abiertos si está `BLOCKED`),
`correctionRounds`.

La entrega al CEO sigue el flujo actual: Alex consolida, Java agrega el estado verificable, `AWAITING_INVESTOR`.

## 11. Chat y Command Center

Chat (`ChatIntentRouter`, todo en Java antes de cualquier modelo):
- Arranque (con la gobernanza, antes de las menciones): "quiero construir/desarrollar/crear una app|aplicación|sistema|
  servicio…" → `IDEA`; "construye una HU…" o texto que empieza con "como <rol> quiero" → `HU`; "necesito un servicio|
  componente|endpoint que…" → `OBJECTIVE`. "urgente" en el mensaje → `URGENT`. Responde con el id del pedido y su
  posición en la cola.
- Consultas: "¿cómo va MISSION-X?" (etapa por etapa), "¿por qué falla MISSION-X?" (errores abiertos con su historial),
  "muéstrame la ronda N de MISSION-X", "¿qué hay en el backlog?", "¿cómo va el plan de <producto>?", "¿qué misiones
  están bloqueadas?", "¿qué se retomó?"; línea de desarrollo en "dame un status".
- Comandos del fundador: "marca <pedido> como urgente", "reanuda el plan de <producto>", "apruebo las HU de
  <pedido>", "rechaza el pedido <pedido>".
- **Instrucciones del fundador** (comando de gobernanza, antes de las menciones): "para MISSION-X: …", "para <pedido>:
  …" o "@Aria … <pedido>" con un pedido o misión de desarrollo identificable. Java las guarda como
  `(:FounderInstruction {text, receivedAt, appliedIn, status})` unida al pedido y decide según la etapa:
  - pedido sin spec todavía → se agrega a la entrada de Aria;
  - `AWAITING_SPEC_APPROVAL` → Aria ajusta el spec y lo vuelve a presentar (§3);
  - spec aprobado → Aria la convierte en HUs de un **incremento nuevo al final del plan** (mismo validador; los ids
    siguen la numeración del plan), que espera aprobación antes de programarse. Nunca modifica el incremento en curso
    ni los aprobados.
  El chat confirma dónde quedó ("Instrucción registrada; quedó como HU-07 en el incremento 4, al final del plan").
  Ninguna instrucción se descarta: todas aparecen en el log y en el `DeliveryResult`. Sin pedido identificable, Java
  pide aclarar a cuál se refiere y no guarda nada. Las menciones sin forma de instrucción siguen siendo solo lectura.
- Afirmaciones de builds, tests o commits sin respaldo en la memoria → nota `UNBACKED_MEMORY_CLAIM` (existente).

Command Center, pantalla Missions: formulario "Nuevo desarrollo" (tipo, título, descripción, criterios, prioridad,
opcionales), la cola en orden con cambio de prioridad, y el detalle de cada misión de desarrollo: spec de Aria, DAG de
Neo, cobertura de escenarios, los 6 pasos, línea de tiempo de rondas con estrategias y fuentes. Settings muestra las
policies nuevas. `api/types.ts` y `SpaController` se actualizan.

## Policies nuevas

`SAME_ERROR_ROUNDS_BEFORE_STRATEGY` (5), `MAX_STRATEGIES_BEFORE_BLOCK` (3), `MAX_PARALLEL_DEVELOPMENT` (1): enteras
> 0. `SPEC_APPROVAL_REQUIRED` (1): solo 0/1, como `ORCHESTRATOR_ENABLED`. Todas versionadas como las demás y editables
en Settings.

## Eventos nuevos

`EMPRESA_DEVELOPMENT_REQUESTED`, `EMPRESA_DEVELOPMENT_SPEC_READY`, `EMPRESA_DEVELOPMENT_PLAN_READY`,
`EMPRESA_CORRECTION_ROUND_COMPLETED`, `EMPRESA_DEVELOPMENT_DELAYED`, `EMPRESA_MISSION_BLOCKED`,
`EMPRESA_MISSION_RESUMED`, `EMPRESA_DEVELOPMENT_INCREMENT_QUEUED`. Se agregan a `docs/EVENTS.md`.

## Errores

- Spec de Aria inválido 3 veces → `FAILED`, sin plan ni código.
- Plan de Neo inválido 3 veces → `FAILED`, sin commits.
- Proveedor caído → suplente local (`ModelHealthService`, existente); un error de modelo en una etapa la deja `PENDING`
  para reintentar en el siguiente chequeo del despachador, no tumba la misión.
- `sandbox-runner` caído → la verificación queda `PENDING` y se reintenta; si sigue caído, `UNVALIDATED` con el motivo
  (nunca `VERIFIED`).
- Búsqueda web sin resultados al buscar estrategia → se registra y cuenta como estrategia fallida.

## Testing

Unitarios (JUnit + Mockito, como el resto):
- `RequirementsSpecValidator`: ids, incrementos, ≤12 escenarios, dependencias hacia adelante, ciclos, cobertura de
  criterios.
- `TeamPlanValidator`: capacidades atómicas, roleCode no soportado, doble tarea de QA, DAG con ciclos, escenarios sin
  dueño, capa sin dueño.
- `ElidedCodeGate`, detección de respuesta cortada y armado de continuación.
- `ScenarioCoverage` y `StaticValidationStatus.compute` con los pasos nuevos.
- `CorrectionPlanner`: huellas estables entre rondas, asignación por tipo de fallo, conteo de persistencia, umbral de
  estrategia y de `BLOCKED`.
- `DevelopmentDispatcher`: orden urgente/FIFO y límite de paralelas.
- Reconciliación: etapas `RUNNING` → `PENDING`, `HEAD` distinto → reset, workspace faltante → `BLOCKED`.
- `ChatIntentRouter`: los 3 tipos de arranque y que no se confundan con consultas; instrucciones con y sin pedido
  identificable; aprobación y rechazo de HUs.
- Aprobación de HUs: con la policy en 1 el plan de Neo no corre hasta aprobar; con 0 pasa directo; un pedido esperando
  aprobación no ocupa cupo de la cola; se construye la versión aprobada del spec.
- Instrucciones: antes del spec → entrada de Aria; esperando aprobación → spec ajustado y re-presentado; aprobado →
  incremento nuevo al final, sin tocar los aprobados.
- `sandbox-runner`: parseo de `testCases` de TRX y JSON de Flutter; pasos `startup` separados.

**Prueba de aceptación en vivo** (registrar en `docs/HISTORY.md`):
1. Por chat: "Construye una aplicación Hello World en Flutter Web" → Aria (spec con escenarios) → el fundador ajusta
   una vez con una instrucción y aprueba las HUs → Neo (plan con Mila y
   Vera) → commits de Mila y de Vera → los 6 pasos en PASS → todos los escenarios con test aprobado → `VERIFIED` → el
   chat informa el resultado con su evidencia.
2. Repetir y reiniciar `company-core` a mitad de la misión: se retoma sola y llega a `VERIFIED`.

## Fuera de alcance (fases siguientes)

- Fase 2: PostgreSQL efímero en el sandbox, perfil Flutter + ASP.NET Core + PostgreSQL, tareas reales de Diego y
  Andrea (Dockerfile, CI), prueba del CRUD.
- Fase 3: Godot con Kael, Blender en el sandbox para Orion, proveedores de arte y audio para Luna, prueba del juego 2D.
- Integraciones con Jira y GitHub Issues.
- Misiones durables para Discovery y los otros equipos.
- Nuevo dueño de `OFFER_DESIGN` en Discovery (obligatorio antes de descongelar Forjai).
- Ejecución en paralelo de tareas del DAG.
