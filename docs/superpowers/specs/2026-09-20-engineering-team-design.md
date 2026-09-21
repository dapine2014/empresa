# Engineering Team + modelo real por agente — diseño

**Fecha**: 2026-09-20
**Estado**: aprobado por el usuario en brainstorming, pendiente de plan de implementación.

## Contexto y objetivo

Forjai tiene hoy 6 agentes planos (`ceo`, `sales`, `product`, `finance`,
`engineering`, `qa`) sin ninguna estructura organizativa por encima de la
identidad individual — cada uno es un nodo `Agent` suelto, sin equipos,
sin liderazgo, sin capacidades declaradas. El pedido del usuario introduce
la primera unidad organizativa persistente real: el **Engineering Team**,
compuesto por 5 agentes (2 reutilizados — `Neo`/`engineering` y
`Vera`/`qa` — y 3 nuevos), con roles específicos, capacidades declaradas,
liderazgo real (`Neo` lidera), y consultable de forma determinista desde
el Company Chat.

Junto con esto, el usuario pidió explícitamente una segunda pieza que en
brainstorming se evaluó separar y finalmente se decidió incluir en la
misma ronda: que **el modelo LLM de cada agente sea un dato real y
funcional**, no solo descriptivo — hoy `CeoService` usa un único
`ollama.agent-model` compartido por las 5 tareas delegadas (decisión
deliberada documentada en `docs/HISTORY.md`, sección "Modelo de agente"),
y esta feature revierte esa limitación: cada `Agent` puede tener su
propio modelo persistido, y `AgentRuntime`/`CeoService` lo resuelven y lo
usan de verdad en cada llamada a Ollama.

**Relación explícita con el Proyecto A** (grounding anti-alucinación,
implementado y mergeado el mismo día): la existencia del Engineering Team
**no implica que exista desarrollo real** — `ProductStatus` sigue
separado de `MissionStatus`, sigue derivándose solo de señales reales
(`OFFER_DESIGN` completada, `Transaction` real, `netProfit`), y el CEO
no puede afirmar que el producto está en desarrollo solo porque el
equipo existe. Esta feature no toca `ProductStatusService` ni
`ChatIntentRouter`'s rama de estado de misión — las extiende con una
consulta nueva, sin modificar las reglas de grounding ya implementadas.

**Relación explícita con el Proyecto B** (ejecución real de código/infra,
todavía sin diseñar): crear el Engineering Team y darle a cada agente su
propio modelo **no le da a ningún agente la capacidad de escribir código,
tocar un repo, correr un build o desplegar nada**. Esas capacidades siguen
siendo el Proyecto B, que usará este Engineering Team como su base
organizativa cuando se diseñe.

## Decisiones de diseño

### 1. Cinco agentes, dos reutilizados, tres nuevos

Reutilizar (sin duplicar, sin perder identidad/historial):

- `Agent {id:'engineering'}` (Neo) → pasa a liderar el equipo.
- `Agent {id:'qa'}` (Vera) → se suma como miembro.

Crear tres `Agent` nuevos, mismo patrón de identidad que los 6 existentes
(nombre humano corto, no un id descriptivo):

- `Agent {id:'devops', name:'Diego'}` — Cloud Database & SRE/DevOps.
- `Agent {id:'backend', name:'Iris'}` — Dev Backend & Integrations.
- `Agent {id:'frontend-ui', name:'Mila'}` — Frontend & Game UI Specialist.

(Los ids `devops`/`backend`/`frontend-ui` son nuevos y no colisionan con
ningún id existente ni con los 5 agentIds fijos que `MissionExecutor`
delega — ver decisión 6.)

### 2. `Agent.role` (texto legible) se actualiza; `Agent.roleCode` (nuevo) y `Agent.capabilities` (nuevo) se agregan — solo para los 5 del equipo

`Agent.role` es hoy el texto que se muestra tal cual en
`teamRosterDescription()` (inyectado al system prompt del CEO),
`GET /agents`, y las tarjetas de `AgentsPage.tsx`. Se **reemplaza** (no se
agrega un campo paralelo) para los dos reutilizados:

- `engineering`.role: `"Chief Engineering AI"` → `"Cloud Architect & Lead Backend"`.
- `qa`.role: `"QA & Operations AI"` → `"QA & Cloud Performance Engineer"`.

Los tres nuevos nacen con su `role` humano directo (`"Cloud Database & SRE / DevOps"`,
`"Dev Backend & Integrations"`, `"Frontend & Game UI Specialist"`).

Dos campos nuevos, **solo persistidos para estos 5** (el resto de los
agentes — `ceo`/`sales`/`product`/`finance` — no los llevan en esta
ronda, no se pidió):

- `Agent.roleCode` (string, código estable para uso programático, separado
  de la presentación): `CLOUD_ARCHITECT_LEAD_BACKEND`,
  `CLOUD_DB_SRE_DEVOPS`, `DEV_BACKEND_INTEGRATIONS`,
  `FRONTEND_GAME_UI_SPECIALIST`, `QA_CLOUD_PERFORMANCE_ENGINEER`.
- `Agent.capabilities` (`List<String>`, propiedad de lista real en
  Neo4j): tomado directo de las "Responsabilidades" del pedido original,
  una entrada por bullet, más el stack técnico concreto que el usuario
  agregó después de la primera aprobación del spec (lenguajes de
  desarrollo y expertise de infraestructura/datos, repartidos por rol
  según confirmó explícitamente):

  - `engineering` (Neo).capabilities = `["arquitectura de soluciones",
    "arquitectura cloud AWS", "arquitectura backend", "decisiones
    técnicas", "diseño de sistemas", "diseño de arquitectura de
    videojuegos y aplicaciones cuando aplique", "descomposición técnica
    del trabajo", "liderazgo técnico", "revisión técnica", "coordinación
    del Engineering Team", "AWS", "bases de datos SQL", "bases de datos
    NoSQL", "C#", "Java", "JavaScript / TypeScript"]`.
  - `devops` (Diego).capabilities = `["PostgreSQL", "Redis", "bases de
    datos SQL", "bases de datos NoSQL", "bases de datos cloud",
    "infraestructura", "SRE", "observabilidad", "rendimiento", "backups",
    "recuperación", "Terraform / Pulumi", "operación cloud AWS",
    "capacidades de infraestructura cuando el Proyecto B esté
    implementado"]`.
  - `backend` (Iris).capabilities = `["backend", "APIs", "integraciones",
    "microservicios", "lógica de negocio", "servicios backend",
    "integraciones con terceros", "componentes backend para aplicaciones
    y videojuegos", "C#", "Java", "JavaScript / TypeScript"]`.
  - `frontend-ui` (Mila).capabilities = `["frontend", "interfaces web",
    "UI", "UX técnica", "Game UI", "HUD", "menús", "interfaces de
    aplicaciones y videojuegos", "C#", "Java", "JavaScript /
    TypeScript"]`.
  - `qa` (Vera).capabilities = `["QA", "pruebas funcionales", "pruebas de
    integración", "pruebas de regresión", "pruebas de rendimiento",
    "pruebas de carga", "validación de estabilidad", "análisis de
    errores", "playtesting cuando corresponda", "validación de
    performance", "validación de calidad"]` (sin stack de lenguajes —
    QA no es un rol de desarrollo, no se le agregó nada nuevo).

  Los tres roles de desarrollo (Neo, Iris, Mila) comparten el mismo orden
  de prioridad de lenguajes (`C#` primero, `Java` segundo, `JavaScript /
  TypeScript` tercero) — la lista no expresa un ranking formal aparte del
  orden de aparición, es la misma convención de lista simple que ya usa
  el resto de `capabilities`. Diego no lleva lenguajes de aplicación (es
  infraestructura/datos, no desarrollo de producto); en cambio suma
  `"bases de datos SQL"`/`"bases de datos NoSQL"` como expertise
  operativa propia (a diferencia de Neo, que las tiene como parte de su
  expertise de *arquitectura*, no de operación día a día).

### 3. `Team` como entidad de primera clase, ámbito deliberadamente acotado a Engineering

Nodo nuevo, un solo tipo de team por ahora (no se generaliza a un
framework multi-equipo — no hay un segundo equipo pedido todavía, sería
especular):

```
(:Team {id:'TEAM-ENGINEERING', name:'Engineering Team', type:'ENGINEERING', status:'ACTIVE'})
```

Relaciones:

- `(:Agent)-[:MEMBER_OF]->(:Team)` para los 5 (Neo, Vera, Diego, Iris,
  Mila).
- `(:Agent {id:'engineering'})-[:LEADS]->(:Team)`.

Nuevo constraint de unicidad `team_id` en `CompanyMemoryService.initializeSchema()`.
Seed idempotente en un método nuevo `EngineeringTeamMemoryService.ensureEngineeringTeam()`,
llamado desde el mismo punto que ya siembra Company/Agent
(`CompanyMemoryInitializer`, `ApplicationReadyEvent`) — mismo patrón de
`MERGE ... ON CREATE ... SET` idempotente que el resto del seeding.

### 4. Crear el Team nunca cambia `Agent.status` ni crea `AgentTask`

Regla dura explícita del pedido, ya alineada con cómo funciona
`Agent.status` hoy (`AgentRuntime.executeInternal` es el único que lo
muta, en `WORKING`/`IDLE` reales): `ensureEngineeringTeam()` **nunca**
toca `Agent.status`. Los 3 agentes nuevos nacen `status='IDLE'`
(`ON CREATE SET`, mismo patrón que el seed de Company/Agent existente).
Neo y Vera conservan su `status` real actual sin tocarlo (no es un
`ON CREATE`, son nodos ya existentes — el `MERGE` de esta feature solo
toca `role`/`roleCode`/`capabilities`, nunca `status`).

Ninguna `AgentTask` se crea como efecto de esta feature — `MissionExecutor`
sigue delegando exactamente a los mismos 5 agentIds fijos de siempre
(`sales`/`product`/`finance`/`engineering`/`qa`); los 3 agentes nuevos
(`devops`/`backend`/`frontend-ui`) no reciben ninguna tarea hoy, ni de
`MissionExecutor` ni de ningún otro flujo — existen en Company Memory
pero no participan todavía de ninguna ejecución real (eso es Proyecto B).

### 5. `EngineeringTeamMemoryService` — servicio nuevo, dedicado

Nueva clase (mismo patrón que `OpportunityMemoryService`/
`CustomerMemoryService`, no se sobrecarga `CompanyMemoryService` con un
concepto organizativo distinto de "identidad de agente"):

- `ensureEngineeringTeam()`: el seed idempotente de la decisión 3.
- `snapshot() -> EngineeringTeamSnapshot`: lectura real para el chat —
  `record EngineeringTeamSnapshot(String teamId, String teamName, String status, String leaderAgentId, List<TeamMemberInfo> members)`,
  `record TeamMemberInfo(String agentId, String name, String role, String roleCode, List<String> capabilities, String model)`.
  Una sola query (`MATCH (a:Agent)-[:MEMBER_OF]->(t:Team {id:'TEAM-ENGINEERING'})`
  + `OPTIONAL MATCH (a)-[:LEADS]->(t)` para identificar al líder).

Sin test directo (integración Neo4j, mismo criterio ya establecido para
toda la familia `*MemoryService` — ver decisión 9).

### 6. Modelo real por agente: `Agent.model` pasa de inexistente a persistido y funcional

Alcance ampliado explícitamente por el usuario durante brainstorming:
no es solo un dato descriptivo — `AgentRuntime`/`CeoService` deben
resolverlo y usarlo de verdad en cada llamada a Ollama, para **los 9
agentes** (los 6 existentes + los 3 nuevos), **incluido el CEO**.

- `CompanyMemoryService` gana `agentModel(String agentId, String fallback) -> String`
  (`MATCH (a:Agent {id:$id}) RETURN coalesce(a.model, $fallback) AS model`)
  y `setAgentModel(String agentId, String model)` (`SET`, sin `ON CREATE` —
  cambiarlo es una escritura directa, no un seed).
- Backfill idempotente en el mismo bucle de `initializeCompanyAndAgents()`:
  `SET a.model = coalesce(a.model, $defaultModel)`, donde `$defaultModel`
  es `ollama.ceo-model` para `ceo` y `ollama.agent-model` para el resto —
  mismo patrón ya usado para normalizar `Agent.status` (preserva un valor
  real ya seteado, nunca lo pisa).
- `CeoService.executeAgentTask`, `CeoService.chat` y
  `CeoService.executeMission` ganan un parámetro `String model` explícito
  cada uno, y dejan de leer sus campos `ceoModel`/`agentModel` inyectados
  para decidir qué modelo llamar (esos campos quedan solo como el
  `$defaultModel` de backfill de arriba, ya no se usan dentro de
  `CeoService`). Esto preserva la separación ya documentada
  ("`CeoService` sigue sin depender de Neo4j directamente, es el único
  cliente de Ollama"): quien resuelve el modelo real es el llamador
  (`AgentRuntime`/`MissionExecutor`/`ChatIntentRouter`, que ya dependen de
  memoria), nunca `CeoService` mismo.
- `AgentRuntime.execute()` gana una dependencia nueva a `CompanyMemoryService`,
  resuelve `companyMemory.agentModel(agentId, defaultAgentModel)` una vez
  por tarea, y se lo pasa a `ceoService.executeAgentTask(...)`.
- `MissionExecutor` gana la misma dependencia, resuelve el modelo del CEO
  antes de `ceoService.executeMission(...)`.
- `ChatIntentRouter` ya depende de `CompanyMemoryService` — resuelve el
  modelo del CEO ahí mismo antes de cada llamada a `ceoService.chat(...)`.

**Nuevo endpoint** `PUT /api/company/agents/{id}/model`
(`AgentModelCommand{model}`) en `CompanyController`, delega a
`CompanyMemoryService.setAgentModel`. Sin validar contra qué modelos
existen realmente en Ollama — mismo criterio que hoy con
`ollama.ceo-model`/`ollama.agent-model`: si el string no corresponde a un
modelo instalado, Ollama falla en la llamada real (`OLLAMA_ERROR`
logueado, excepción propagada), no antes. 404/`IllegalArgumentException`
si el agente no existe (convención existente del proyecto: sin manejo
fino de errores HTTP, cae al handler default → 500).

### 7. Company Chat: `QueryIntent.ENGINEERING_TEAM`

Nuevo intent determinista en `ChatIntentRouter.detectQuery`, chequeado
**antes** que `AGENT_STATUS` (que hoy dispara con solo la palabra
`"equipo"` — ver `CLAUDE.md`, bug real ya corregido en una ronda
anterior): activa con `"equipo de ingenieria"`/`"engineering team"`, o
`"ingenieria"`/`"engineering"` combinado con `"quien lidera"`/`"lider"`.
Formatter nuevo (`formatEngineeringTeam`) cruza
`EngineeringTeamMemoryService.snapshot()` (miembros/roles/roleCode/
capabilities/modelo/líder) con `missionMemory.latestTaskPerAgent()`
(status real y tarea actual — mismo patrón que ya usa
`formatMissionStatus` para no confundir "quién es" con "qué está
haciendo ahora"). 100% Java, agregado al enum de topics de
`query_company_memory` en `CeoService` igual que el resto — nunca pasa
por Ollama para contar/enumerar.

Ejemplos de lo que debe poder responder, todo desde datos reales:
existe el equipo, quiénes son sus 5 miembros, quién lidera, rol y
`roleCode` de cada uno, `capabilities` de cada uno, `model` de cada uno,
`status`/tarea actual real de cada uno. Nunca inventa un miembro, rol,
capability, estado o tarea que no esté en Company Memory — si algo no
está registrado, responde exactamente **"No tengo ese dato registrado."**
(misma regla dura del Proyecto A).

### 8. `MISSION-<id>` lookup y regla `NEVER_NARRATE_WITHOUT_EVIDENCE`: ya implementados, no se tocan

El pedido pide explícitamente esta capacidad (resolver un `MISSION-<id>`
explícito contra Company Memory, nunca inventar, decir "no tengo ese dato
registrado" si no existe) y la regla dura de nunca narrar desarrollo sin
evidencia — **ambas ya están implementadas y mergeadas** como parte del
Proyecto A (`ChatIntentRouter.handleMissionStatusQuery`,
`ProductStatusService`, el refuerzo de prompt de `CeoService.chat`). Esta
ronda no las modifica; se listan acá solo para dejar constancia de que el
criterio de aceptación correspondiente ya está cubierto por trabajo
anterior, no por esta feature.

### 9. Testing: misma convención ya acordada con el usuario

- **No automatizado, verificado en vivo y documentado** (mismo criterio
  que todo seed/escritura de `*MemoryService` en este proyecto): que el
  Team exista con exactamente 5 miembros, que Neo/Vera se reutilicen sin
  duplicarse, que la relación `LEADS` exista, que los 5 arranquen `IDLE`,
  que la membresía no cambie `Agent.status`.
- **Sí, unitario real con mocks**:
  - `EngineeringTeamServiceFormatterTest`-equivalente dentro de
    `ChatIntentRouterTest`: nueva rama `ENGINEERING_TEAM` (mock de
    `EngineeringTeamMemoryService.snapshot()` + `missionMemory.latestTaskPerAgent()`,
    formatter no inventa nada, misión/agente inexistente → "No tengo ese
    dato registrado.").
  - **Test de wiring del modelo por agente** (`AgentRuntimeTest`, nuevo
    caso): dos tareas con `agentId` distintos, `companyMemory.agentModel(...)`
    mockeado a devolver valores distintos por agente, verificar que
    `ceoService.executeAgentTask(...)` recibe el `model` correcto y
    distinto en cada llamada — prueba real de que el mecanismo funciona,
    sin depender de Ollama real.
  - Casos existentes de `CeoServiceChatHistoryTest`/`CeoServiceToolFormatGuardTest`/
    `ChatIntentRouterTest`/`MissionExecutorTest` que llaman a los métodos
    de `CeoService` con la nueva firma (`model` agregado) se actualizan
    para pasar un valor fijo de prueba — sin cambiar lo que verifican.

## Fuera de alcance de esta ronda (documentado, no descartado)

- **Proyecto B completo** (ejecución real de código/infraestructura): el
  Engineering Team es su base organizativa futura, no se diseña ni
  implementa acá.
- **`roleCode`/`capabilities` para `ceo`/`sales`/`product`/`finance`**: no
  pedido, se puede sumar cuando haya un caso de uso concreto para esos
  agentes.
- **Framework multi-equipo genérico**: `Team.type='ENGINEERING'` es
  deliberadamente el único tipo hoy — generalizar a Sales
  Team/Product Team/etc. sin un pedido concreto sería especular.
- **UI/frontend nuevo**: sin cambios de Command Center en esta ronda
  (ni pantalla de equipo, ni selector de modelo por agente) — API REST +
  chat alcanza, mismo criterio que rondas anteriores.
- **Validación de modelos contra Ollama real**: `PUT /agents/{id}/model`
  no verifica que el modelo exista/esté instalado — falla en la próxima
  llamada real si no.
