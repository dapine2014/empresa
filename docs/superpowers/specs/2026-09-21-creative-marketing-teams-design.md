# Creative/Product Intelligence + Marketing & Growth — 5 agentes nuevos, 2 equipos nuevos, TeamMemoryService genérico — diseño

**Fecha**: 2026-09-21
**Estado**: aprobado por el usuario en brainstorming, pendiente de plan de implementación.

## Contexto y objetivo

Forjai tiene hoy 9 agentes y un solo equipo persistente, el Engineering
Team (`docs/superpowers/specs/2026-09-20-engineering-team-design.md`,
ya implementado y mergeado a `master`). El pedido del usuario agrega 5
agentes nuevos — **Kael**, **Maya**, **Kira**, **Gael**, **Nora** — y 2
equipos nuevos (**Creative / Product Intelligence** y **Marketing &
Growth**), reutilizando exactamente la misma infraestructura
organizativa (`Team`, `MEMBER_OF`, `LEADS`, `Agent.roleCode`,
`Agent.capabilities`) que ya existe para Engineering — con la
diferencia de que, al pasar de 1 equipo a 3, corresponde generalizar el
servicio dedicado (`EngineeringTeamMemoryService`) en vez de triplicar
la misma clase con solo los nombres cambiados.

Al cierre de esta ronda existirán **14 agentes** en 3 equipos
persistentes + 4 agentes sin equipo (`ceo`/`sales`/`product`/`finance`,
igual que hoy). Ningún agente nuevo participa de `MissionExecutor`
(que sigue delegando exactamente a los mismos 5 `agentId` fijos de
siempre) ni tiene capacidad de ejecución real — es 100% estructura
organizativa, mismo criterio que Engineering.

## Decisiones de diseño

### 1. Cinco agentes nuevos, ids programáticos nuevos (no dados por el usuario)

El usuario dio nombre, role (texto legible), roleCode y
responsabilidades de cada agente, pero no un `id` corto tipo
`devops`/`backend`/`frontend-ui`. Se definen así, siguiendo la misma
convención (corto, funcional, no el nombre de pila):

| id | name | role | roleCode |
|---|---|---|---|
| `interaction-design` | Kael | Interactive Logic & Product Designer AI | `INTERACTIVE_LOGIC_PRODUCT_DESIGNER` |
| `visual-design` | Maya | Visual & Asset Director AI | `VISUAL_ASSET_DIRECTOR` |
| `telemetry` | Gael | Telemetry & Analytics AI | `TELEMETRY_ANALYTICS` |
| `growth-content` | Kira | Growth, Content & Community AI | `GROWTH_CONTENT_COMMUNITY` |
| `community` | Nora | Community Manager AI | `COMMUNITY_MANAGER` |

Ninguno de estos 5 ids colisiona con los 9 existentes
(`ceo`/`sales`/`product`/`finance`/`engineering`/`qa`/`devops`/`backend`/`frontend-ui`).

`personality` no fue dada por el usuario para estos 5 — se inventa una
línea breve de UI (no se inyecta a ningún prompt, mismo criterio ya
usado para Diego/Iris/Mila): Kael "analítico, obsesionado con la
experiencia de usuario"; Maya "creativa, con ojo estético"; Gael
"analítico, basado en datos"; Kira "curiosa, comunicativa"; Nora
"empática, cercana a la comunidad".

`model`: sin pedido especial — sigue el mecanismo genérico ya
implementado para los 9 agentes existentes (`Agent.model`, backfill
`coalesce(a.model, $defaultAgentModel)` en
`CompanyMemoryService.initializeCompanyAndAgents()`,
`PUT /api/company/agents/{id}/model` para cambiarlo después). No hace
falta código nuevo para esto — agregar las 5 filas al array `agents`
existente alcanza.

### 2. `Agent.capabilities`: las "Responsabilidades" del pedido, una entrada por bullet — tags programáticos, no prosa descriptiva

El usuario pidió explícitamente mantener `capabilities` conceptualmente
distintas de "responsibilities": las primeras deben poder usarse
programáticamente (selección/routing de `AgentTask` en un futuro
Proyecto B), las segundas son descriptivas. Esta ronda no introduce un
campo `Agent.responsibilities` separado (no fue pedido, sería
duplicar el mismo dato en dos formatos sin ningún caso de uso real
todavía — YAGNI, mismo criterio que dejó afuera
`roleCode`/`capabilities` para `ceo`/`sales`/`product`/`finance`). Las
"Responsabilidades" que dio el usuario ya vienen como frases cortas
tipo tag (no oraciones), consistente con el estilo ya usado en
Engineering (`"SRE"`, `"backups"`, `"arquitectura cloud AWS"`) — se
persisten tal cual, una por bullet, como `Agent.capabilities`:

- `interaction-design` (Kael): `["UX y arquitectura de interacción",
  "flujos de usuario", "sistemas de gamificación", "engagement y
  retención", "game design", "game loop", "reglas y mecánicas", "curva
  de aprendizaje", "balance de gameplay", "economía interna de
  productos interactivos"]`.
- `visual-design` (Maya): `["identidad visual", "dirección artística",
  "branding", "ilustraciones", "assets de marketing", "assets 2D/3D",
  "sprites", "animaciones", "iluminación", "consistencia visual del
  producto", "dirección visual para videojuegos y aplicaciones"]`.
- `telemetry` (Gael): `["análisis de producto", "funnels de
  conversión", "activación", "retención", "churn", "comportamiento de
  usuarios", "métricas de sesión", "telemetría de videojuegos",
  "análisis de gameplay", "análisis de monetización",
  "experimentación", "generación de insights y recomendaciones
  basadas en datos"]`.
- `growth-content` (Kira): `["growth", "marketing de contenidos",
  "SEO", "adquisición orgánica", "Product-Led Growth", "newsletters",
  "redes sociales", "devlogs", "campañas", "estrategia de
  adquisición", "construcción de audiencia", "coordinación de
  iniciativas de comunidad"]`.
- `community` (Nora): `["gestión diaria de comunidades", "interacción
  con usuarios", "moderación", "Discord", "redes sociales",
  "recopilación de feedback", "comunicación con la comunidad",
  "eventos y actividades", "identificación de necesidades y problemas
  de usuarios", "escalamiento de feedback relevante hacia Product,
  Growth y CEO"]`.

`role` (texto legible) para los 5: el texto de "Role" dado por el
usuario, verbatim (incluye el sufijo "AI").

### 3. Dos equipos nuevos, ids estables, `displayName` separado del id programático

```
(:Team {id:'TEAM-CREATIVE-PRODUCT-INTELLIGENCE', name:'Creative / Product Intelligence', type:'CREATIVE_PRODUCT_INTELLIGENCE', status:'ACTIVE'})
(:Team {id:'TEAM-MARKETING-GROWTH', name:'Marketing & Growth', type:'MARKETING_GROWTH', status:'ACTIVE'})
```

Miembros (`MEMBER_OF`):
- Creative / Product Intelligence: `interaction-design` (Kael),
  `visual-design` (Maya), `telemetry` (Gael).
- Marketing & Growth: `growth-content` (Kira), `community` (Nora).

Líderes (`LEADS`, decidido explícitamente por el usuario):
- Kael (`interaction-design`) → `LEADS` → `TEAM-CREATIVE-PRODUCT-INTELLIGENCE`.
- Kira (`growth-content`) → `LEADS` → `TEAM-MARKETING-GROWTH`.
- Engineering **no cambia**: Neo sigue liderando `TEAM-ENGINEERING`
  (dato ya persistido, la generalización de la decisión 4 no lo toca).

Exactamente estos 3 equipos en esta implementación — sin mecanismo
para agregar un cuarto team sin cambiar código (mismo criterio
deliberado que Engineering: no se generaliza a "cualquier equipo vía
API", es una lista fija en el servicio).

### 4. `EngineeringTeamMemoryService` → `TeamMemoryService` genérico

Con 3 equipos reales (no 1 especulativo), corresponde generalizar
ahora, no antes. Un solo servicio, parametrizado por una lista fija de
`TeamDefinition`:

```java
private record RoleDefinition(String agentId, String roleCode, List<String> capabilities) {}

private record TeamDefinition(
        String teamId, String teamName, String teamType,
        String leaderAgentId, List<RoleDefinition> roles) {}

public static final String TEAM_ENGINEERING = "TEAM-ENGINEERING";
public static final String TEAM_CREATIVE_PRODUCT_INTELLIGENCE = "TEAM-CREATIVE-PRODUCT-INTELLIGENCE";
public static final String TEAM_MARKETING_GROWTH = "TEAM-MARKETING-GROWTH";
public static final Set<String> KNOWN_TEAM_IDS =
        Set.of(TEAM_ENGINEERING, TEAM_CREATIVE_PRODUCT_INTELLIGENCE, TEAM_MARKETING_GROWTH);

private static final List<TeamDefinition> TEAMS = List.of(
        new TeamDefinition(TEAM_ENGINEERING, "Engineering Team", "ENGINEERING", "engineering", /* las 5 RoleDefinition ya existentes, contenido idéntico al actual, sin cambios */),
        new TeamDefinition(TEAM_CREATIVE_PRODUCT_INTELLIGENCE, "Creative / Product Intelligence", "CREATIVE_PRODUCT_INTELLIGENCE", "interaction-design", /* 3 RoleDefinition: Kael/Maya/Gael */),
        new TeamDefinition(TEAM_MARKETING_GROWTH, "Marketing & Growth", "MARKETING_GROWTH", "growth-content", /* 2 RoleDefinition: Kira/Nora */)
);
```

- `ensureAllTeams()` reemplaza `ensureEngineeringTeam()`: itera
  `TEAMS`, corre por cada una exactamente la misma Cypher que hoy
  corre `ensureEngineeringTeam()` (MERGE de `Team`, `SET`
  `roleCode`/`capabilities` + `MERGE MEMBER_OF` por rol, `MERGE LEADS`
  del líder) — **contenido Cypher sin cambios**, solo parametrizado.
  Mismo WARN si un `agentId` de una `RoleDefinition` no matchea ningún
  `Agent` real.
- `snapshot(String teamId)` reemplaza el `snapshot()` sin argumentos:
  misma query (`MATCH (a:Agent)-[:MEMBER_OF]->(t:Team {id:$teamId})
  ...`), parametrizada por `teamId` en vez de la constante fija.
  Devuelve `TeamSnapshot` vacío (`members=List.of()`) si el `teamId` no
  existe todavía o no es uno de los 3 conocidos — nunca inventa nada.
- `EngineeringTeamSnapshot` se renombra a `TeamSnapshot` (ya era
  genérico en su forma, solo mal nombrado — mismos campos:
  `teamId`/`teamName`/`status`/`leaderAgentId`/`members`).
  `TeamMemberInfo` no cambia.
- **La estructura de Engineering no cambia**: mismo `TEAM_ID`, mismo
  `type='ENGINEERING'`, mismos 5 `RoleDefinition` con el mismo
  `roleCode`/`capabilities` que ya están en producción y ya se
  verificaron en vivo contra Neo4j real — la generalización mueve el
  código, no reescribe el dato.
- `CompanyMemoryInitializer` cambia su llamada de
  `engineeringTeamMemory.ensureEngineeringTeam()` a
  `teamMemory.ensureAllTeams()`.
- Invariante dura sin cambios: `ensureAllTeams()` nunca toca
  `Agent.status` ni crea `AgentTask`. Los 5 agentes nuevos nacen
  `IDLE` (mismo `ON CREATE SET` en `CompanyMemoryService`, sin tocar
  esa lógica).

### 5. Company Chat: un solo `QueryIntent.TEAM_DETAILS` genérico, no uno por equipo

Pedido explícito del usuario: preferir un mecanismo genérico en vez de
`ENGINEERING_TEAM`/`CREATIVE_TEAM`/`MARKETING_TEAM` como 3 intents
separados. Diseño compatible con la arquitectura actual de
`ChatIntentRouter`/`CeoService` sin cambiar la firma
`Function<String, String> companyMemoryQuery`:

**Detección determinista por keyword** (`detectQuery`): se reemplaza
el `if` específico de `ENGINEERING_TEAM` por una tabla ordenada de
reglas equipo→keywords, todas chequeadas **antes** que `AGENT_STATUS`
(mismo motivo que hoy: "equipo" solo ya dispara `AGENT_STATUS`):

```java
private record TeamKeywordRule(String teamId, List<String> topicKeywords) {}

private static final List<TeamKeywordRule> TEAM_KEYWORD_RULES = List.of(
        new TeamKeywordRule(TeamMemoryService.TEAM_ENGINEERING,
                List.of("ingenieria", "engineering")),
        new TeamKeywordRule(TeamMemoryService.TEAM_CREATIVE_PRODUCT_INTELLIGENCE,
                List.of("creativ", "product intelligence", "visual", "arte", "telemetria", "analytics")),
        new TeamKeywordRule(TeamMemoryService.TEAM_MARKETING_GROWTH,
                List.of("marketing", "growth", "crecimiento", "comunidad", "community"))
);
```

Cada regla exige, además de uno de sus `topicKeywords`, el mismo
"gate" ya usado hoy (`"equipo"`/`"team"`/`"lidera"`/`"lider"`) — evita
falsos positivos (p. ej. "estrategia de marketing" sin mención de
equipo/liderazgo no dispara `TEAM_DETAILS`). `detectQuery` devuelve un
`QueryMatch(QueryIntent intent, String teamId)` (record nuevo,
`teamId` null salvo para `TEAM_DETAILS`) en vez de solo
`QueryIntent` — único cambio de firma, un solo call site
(`route()`).

`handleQuery(QueryMatch match)`:
```java
if (match.intent() == QueryIntent.TEAM_DETAILS) {
    return answerMemoryTopic("TEAM_DETAILS:" + match.teamId());
}
return answerMemoryTopic(match.intent().name());
```

`"TEAM_DETAILS:" + teamId` es un **formato de wire interno**, nunca
visible para el usuario ni para el LLM como tal — permite mantener
`answerMemoryTopic(String)` y `Function<String, String>` sin tocar su
firma en ningún otro punto del código (los otros 2 call sites que
pasan `this::answerMemoryTopic` a `ceoService.chat` siguen
funcionando sin cambios).

`answerMemoryTopic`:
```java
String answerMemoryTopic(String topic) {
    if (topic != null && topic.startsWith("TEAM_DETAILS:")) {
        return formatTeamDetails(topic.substring("TEAM_DETAILS:".length()));
    }
    return switch (topic) {
        case "AGENT_STATUS" -> ...
        // ENGINEERING_TEAM y su case se eliminan de acá
        ...
        default -> "Dato no reconocido: " + topic + ".";
    };
}

private String formatTeamDetails(String teamId) {
    if (!TeamMemoryService.KNOWN_TEAM_IDS.contains(teamId)) {
        return "No tengo ese dato registrado.";
    }
    var snapshot = teamMemory.snapshot(teamId);
    if (snapshot.members().isEmpty()) {
        return "No tengo ese dato registrado. Ese equipo todavía no está registrado en Company Memory.";
    }
    // mismo cruce con missionMemory.latestTaskPerAgent() y mismo formato
    // de línea que formatEngineeringTeam() hoy, usando snapshot.teamName()
    // para el label en vez del literal "Engineering Team".
}
```

Un `teamId` desconocido (incluida una string vacía si el LLM no lo
mandó) devuelve **"No tengo ese dato registrado."** — nunca se
inventa ni se adivina un equipo. Esto también hace cumplir la
decisión 12 (exactamente 3 equipos): nada en el código permite
consultar un cuarto equipo aunque alguien lo mencione.

**Tool-calling del LLM** (`CeoService.COMPANY_MEMORY_TOOLS`): se
reemplaza `"ENGINEERING_TEAM"` por `"TEAM_DETAILS"` en el enum de
`topic`, y se agrega una propiedad nueva, también de enum cerrado (
nunca texto libre — mismo criterio anti-alucinación de todo el
proyecto):

```json
"teamId": {
  "type": "string",
  "enum": ["TEAM-ENGINEERING", "TEAM-CREATIVE-PRODUCT-INTELLIGENCE", "TEAM-MARKETING-GROWTH"],
  "description": "Solo si topic=TEAM_DETAILS: qué equipo. TEAM-ENGINEERING: arquitectura/backend/devops/frontend-UI/QA. TEAM-CREATIVE-PRODUCT-INTELLIGENCE: diseño de interacción/UX/game design, dirección visual/arte, telemetría/analytics. TEAM-MARKETING-GROWTH: growth/contenido/SEO, gestión de comunidad."
}
```

`parseCompanyMemoryTopic`/`detectInlineCompanyMemoryTopic` (ambas
cambian de `private` a *package-private*, mismo criterio ya usado
para `rejectFormatCombinedWithTools`, para poder testearlas
directamente): cuando el `topic` crudo del tool call es
`"TEAM_DETAILS"`, leen también `arguments.teamId` y devuelven el
string compuesto `"TEAM_DETAILS:" + teamId` (o `"TEAM_DETAILS:"` con
sufijo vacío si el modelo no mandó `teamId` — nunca `null`, para que
siempre se intente una consulta real en vez de caer silenciosamente a
`turn.content()`). Para cualquier otro `topic`, comportamiento
idéntico al actual.

### 6. `AGENT_STATUS` no cambia

Sigue devolviendo estado/tarea actual de los 14 agentes sin cambios de
código (la query ya es genérica sobre `MATCH (a:Agent)`). No expone
`roleCode`/`capabilities`/`model` — eso sigue siendo trabajo de
`TEAM_DETAILS`, mismo criterio que ya separaba "quién es" (Team) de
"qué está haciendo ahora" (`AGENT_STATUS`) en la ronda de Engineering.

### 7. Testing: misma convención ya acordada con el usuario

- **No automatizado / verificado en vivo y documentado** (mismo
  criterio que todo seed/escritura de `*MemoryService`): que existan
  los 3 `Team` con exactamente sus miembros reales, que ninguno de los
  9 agentes existentes se haya duplicado, que las 2 relaciones `LEADS`
  nuevas existan (más la de Engineering sin cambios), que los 5
  agentes nuevos arranquen `IDLE`, que la membresía no toque
  `Agent.status`. Toca infraestructura compartida (mismo Neo4j real
  que ya se usó para verificar Engineering) — se pide autorización
  explícita al usuario antes de correrla, igual que la vez anterior.
- **Sí, unitario real con mocks**:
  - `ChatIntentRouterTest`: la rama `TEAM_DETAILS` (antes
    `ENGINEERING_TEAM`) se actualiza al nuevo mock
    (`TeamMemoryService`/`TeamSnapshot`, `snapshot(String teamId)` en
    vez de `snapshot()`). Se agregan casos nuevos para
    `TEAM-CREATIVE-PRODUCT-INTELLIGENCE` y `TEAM-MARKETING-GROWTH`
    (equipo con miembros → formatea roleCode/capabilities/modelo/líder
    reales; equipo vacío → "No tengo ese dato registrado."), y un caso
    de `teamId` desconocido/hallucinado (p. ej.
    `answerMemoryTopic("TEAM_DETAILS:TEAM-BOGUS")` →
    "No tengo ese dato registrado.", sin tocar Neo4j).
  - Un test nuevo (en `CeoServiceToolFormatGuardTest` o un archivo
    dedicado, package-private, sin mock de red) para
    `parseCompanyMemoryTopic`/`detectInlineCompanyMemoryTopic`:
    `topic=TEAM_DETAILS` + `teamId=TEAM-ENGINEERING` →
    `"TEAM_DETAILS:TEAM-ENGINEERING"`; `topic=TEAM_DETAILS` sin
    `teamId` → `"TEAM_DETAILS:"`; `topic=AGENT_STATUS` (sin `teamId`)
    → `"AGENT_STATUS"` (comportamiento actual intacto).
  - Casos existentes que instancian `ChatIntentRouter` (constructor de
    11 params) se actualizan al tipo renombrado sin cambiar la
    cantidad de parámetros.

## Fuera de alcance de esta ronda (documentado, no descartado)

- **Un cuarto equipo o un mecanismo genérico de alta de equipos**: los
  3 `TeamDefinition` son una lista fija en código — agregar un equipo
  nuevo sigue siendo un cambio de código, no de datos.
- **Una consulta "todos los equipos" / listado agregado**: no fue
  pedida; cada equipo se consulta individualmente vía `TEAM_DETAILS` +
  `teamId`.
- **`Agent.responsibilities` como campo separado de
  `Agent.capabilities`**: no hay caso de uso concreto todavía (ver
  decisión 2) — cuando el Proyecto B necesite diferenciarlos de
  verdad, se agrega ahí.
- **Participación real de estos 5 agentes en `MissionExecutor` o
  cualquier ejecución**: siguen sin `AgentTask`, sin capacidad de
  volverse `WORKING` — eso es Proyecto B, todavía sin diseñar, mismo
  límite que Engineering.
- **UI/frontend nuevo**: sin cambios de Command Center — API REST +
  chat alcanza, mismo criterio que Engineering.
- **Validación de `Agent.model` contra Ollama real**: sin cambios,
  mismo comportamiento ya existente para los 9 agentes actuales.
