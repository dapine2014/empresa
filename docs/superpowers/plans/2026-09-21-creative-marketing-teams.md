# Creative/Product Intelligence + Marketing & Growth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add 5 new agents (Kael, Maya, Gael, Kira, Nora) organized into 2 new persistent teams (Creative / Product Intelligence, Marketing & Growth), generalizing the existing single-team `EngineeringTeamMemoryService` into a `TeamMemoryService` that serves all 3 teams, and adding one generic `TEAM_DETAILS` Company Chat query instead of one per team.

**Architecture:** Reuses exactly the `Team`/`MEMBER_OF`/`LEADS`/`Agent.roleCode`/`Agent.capabilities` model already implemented and live-verified for Engineering. `EngineeringTeamMemoryService`/`EngineeringTeamSnapshot` are generalized (not duplicated) into `TeamMemoryService`/`TeamSnapshot`, parameterized by a fixed list of 3 `TeamDefinition`s. `ChatIntentRouter` gains a single `QueryIntent.TEAM_DETAILS` (replacing `ENGINEERING_TEAM`) resolved via keyword→teamId rules, and encodes the resolved team as an internal wire string (`"TEAM_DETAILS:" + teamId`) so `answerMemoryTopic(String)` and the `Function<String, String>` callback signature used by `CeoService.chat` never change. `CeoService`'s `query_company_memory` tool gains a second, closed-enum `teamId` parameter alongside `topic`.

**Tech Stack:** Spring Boot 4.1.1 / Java 21, neo4j-java-driver (plain Cypher), JUnit 5 + Mockito.

**Spec:** `docs/superpowers/specs/2026-09-21-creative-marketing-teams-design.md`

## Global Constraints

- Exactamente 3 equipos: `TEAM-ENGINEERING`, `TEAM-CREATIVE-PRODUCT-INTELLIGENCE`, `TEAM-MARKETING-GROWTH` — lista fija en código, sin mecanismo para agregar un cuarto sin cambiar código.
- Los 5 agentes nuevos nacen `IDLE`; `ensureAllTeams()` nunca toca `Agent.status` ni crea `AgentTask`.
- `roleCode`/`capabilities` solo para agentes que pertenecen a un equipo — nunca para `ceo`/`sales`/`product`/`finance`.
- Neo sigue liderando `TEAM-ENGINEERING` sin cambios (mismo dato ya en producción). Kael (`interaction-design`) lidera `TEAM-CREATIVE-PRODUCT-INTELLIGENCE`. Kira (`growth-content`) lidera `TEAM-MARKETING-GROWTH`.
- La estructura Cypher/contenido de Engineering (roleCodes, capabilities, team name/type) no cambia — solo se mueve a código parametrizado.
- `answerMemoryTopic(String)` y la firma `Function<String, String> companyMemoryQuery` de `CeoService.chat` no cambian — `"TEAM_DETAILS:" + teamId` es un formato de wire interno, nunca expuesto al usuario ni al LLM como tal.
- El `teamId` del tool schema de Ollama es un enum cerrado de exactamente los 3 ids conocidos — nunca texto libre. Un `teamId` desconocido (incluido vacío) siempre devuelve `"No tengo ese dato registrado."`, nunca inventa ni adivina.
- Sin cambios de frontend. Sin que estos agentes participen de `MissionExecutor` ni de ninguna ejecución real.

---

### Task 1: CompanyMemoryService — 5 agentes nuevos

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/CompanyMemoryService.java`

**Interfaces:**
- Consumes: nada nuevo — usa `defaultAgentModel` ya existente en el constructor.
- Produces: 5 nodos `Agent` nuevos (`interaction-design`, `visual-design`, `telemetry`, `growth-content`, `community`) para que Task 2 los pueda enrolar en sus equipos vía `MEMBER_OF`.

- [ ] **Step 1: Agregar las 5 filas nuevas al array `agents` de `initializeCompanyAndAgents()`**

En `CompanyMemoryService.java`, dentro de `initializeCompanyAndAgents()`, el bloque `var agents = List.of(...)` termina hoy en la fila de `"frontend-ui"`. Agregar coma después de esa fila y las 5 filas nuevas antes del `);` de cierre:

```java
                var agents = List.of(
                        new String[]{"ceo", "Alex", "Chief Executive Officer AI", "estratégico, crítico", defaultCeoModel},
                        new String[]{"sales", "Sofia", "Director of Sales AI", "persuasiva, orientada a resultados", defaultAgentModel},
                        new String[]{"product", "Luna", "Chief Product AI", "creativa, centrada en el usuario", defaultAgentModel},
                        new String[]{"finance", "Max", "Chief Finance AI", "analítico, conservador", defaultAgentModel},
                        new String[]{"engineering", "Neo", "Cloud Architect & Lead Backend", "pragmático, meticuloso", defaultAgentModel},
                        new String[]{"qa", "Vera", "QA & Cloud Performance Engineer", "escéptica, detallista", defaultAgentModel},
                        new String[]{"devops", "Diego", "Cloud Database & SRE / DevOps", "meticuloso, orientado a la estabilidad", defaultAgentModel},
                        new String[]{"backend", "Iris", "Dev Backend & Integrations", "riguroso, pragmático", defaultAgentModel},
                        new String[]{"frontend-ui", "Mila", "Frontend & Game UI Specialist", "creativa, atenta al detalle visual", defaultAgentModel},
                        new String[]{"interaction-design", "Kael", "Interactive Logic & Product Designer AI", "analítico, obsesionado con la experiencia de usuario", defaultAgentModel},
                        new String[]{"visual-design", "Maya", "Visual & Asset Director AI", "creativa, con ojo estético", defaultAgentModel},
                        new String[]{"telemetry", "Gael", "Telemetry & Analytics AI", "analítico, basado en datos", defaultAgentModel},
                        new String[]{"growth-content", "Kira", "Growth, Content & Community AI", "curiosa, comunicativa", defaultAgentModel},
                        new String[]{"community", "Nora", "Community Manager AI", "empática, cercana a la comunidad", defaultAgentModel}
                );
```

No tocar ninguna otra línea de este método.

- [ ] **Step 2: Actualizar el conteo en el javadoc de `agentModel()`**

En el javadoc de `agentModel(String agentId, String fallback)` (arriba del método, busca el texto `"los 9"`), cambiar:

```java
     * de un agente sin backfill todavía (no debería pasar en la práctica:
     * {@link #initializeCompanyAndAgents()} lo completa para los 9
     * agentes conocidos al arrancar).
```
a:
```java
     * de un agente sin backfill todavía (no debería pasar en la práctica:
     * {@link #initializeCompanyAndAgents()} lo completa para los 14
     * agentes conocidos al arrancar).
```

- [ ] **Step 3: Compilar y correr la suite completa**

Run: `cd app && mvn test`
Expected: BUILD SUCCESS, mismo número de tests que antes (166), todos en verde — este task no agrega ni quita ningún test (no hay test directo de `CompanyMemoryService`, mismo criterio ya establecido para el seed de `*MemoryService`, ver spec sección 7).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/CompanyMemoryService.java
git commit -m "Agregar 5 agentes nuevos (Kael/Maya/Gael/Kira/Nora) a CompanyMemoryService"
```

---

### Task 2: `TeamMemoryService` genérico (generaliza `EngineeringTeamMemoryService`)

**Files:**
- Create: `app/src/main/java/com/aicompany/core/model/TeamSnapshot.java`
- Delete: `app/src/main/java/com/aicompany/core/model/EngineeringTeamSnapshot.java`
- Create: `app/src/main/java/com/aicompany/core/service/TeamMemoryService.java`
- Delete: `app/src/main/java/com/aicompany/core/service/EngineeringTeamMemoryService.java`
- Modify: `app/src/main/java/com/aicompany/core/config/CompanyMemoryInitializer.java`

**Interfaces:**
- Consumes: los 14 `Agent` ya sembrados por Task 1/`CompanyMemoryService` (nunca los crea, solo los enriquece — mismo contrato que la clase que reemplaza).
- Produces: `TeamMemoryService.ensureAllTeams()`, `TeamMemoryService.snapshot(String teamId)`, constantes públicas `TeamMemoryService.TEAM_ENGINEERING`/`TEAM_CREATIVE_PRODUCT_INTELLIGENCE`/`TEAM_MARKETING_GROWTH`/`KNOWN_TEAM_IDS` — Task 3 (`ChatIntentRouter`) consume las 4 constantes y ambos métodos.

**Nota para el implementador**: al terminar este task, `ChatIntentRouter.java` y `ChatIntentRouterTest.java` (sin tocar todavía, eso es Task 3) van a fallar la compilación porque siguen referenciando `EngineeringTeamMemoryService`/`EngineeringTeamSnapshot`/`formatEngineeringTeam()`. Es intencional (mismo patrón ya usado en el plan de Engineering Team para su Task 3: "build roto hasta el próximo task"). `mvn test` **no puede** correr en este punto — Maven compila todo `src/main/java` antes de filtrar ningún test. Verificar este task por lectura de código, no por una corrida de tests que no puede completar.

- [ ] **Step 1: Crear `model/TeamSnapshot.java`**

```java
package com.aicompany.core.model;

import java.util.List;

public record TeamSnapshot(
        String teamId,
        String teamName,
        String status,
        String leaderAgentId,
        List<TeamMemberInfo> members
) {
}
```

- [ ] **Step 2: Borrar `model/EngineeringTeamSnapshot.java`**

```bash
git rm app/src/main/java/com/aicompany/core/model/EngineeringTeamSnapshot.java
```

(`TeamMemberInfo.java` no cambia — mismos campos, se sigue usando tal cual.)

- [ ] **Step 3: Crear `service/TeamMemoryService.java`**

Contenido completo (preserva byte a byte el contenido Cypher/roleCode/capabilities de Engineering ya en producción; agrega las 2 definiciones nuevas con los datos exactos del spec):

```java
package com.aicompany.core.service;

import com.aicompany.core.model.TeamMemberInfo;
import com.aicompany.core.model.TeamSnapshot;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Unidades organizativas persistentes de Forjai — una capa aparte sobre
 * los {@code Agent} ya creados por {@link CompanyMemoryService} (no
 * duplica esa lógica de identidad, solo agrega la estructura de equipo:
 * {@code Team}, membresía, liderazgo, y los campos específicos de rol
 * de cada agente). Generaliza el servicio original de un solo equipo
 * (Engineering) a los 3 equipos reales de Forjai. Ver
 * docs/superpowers/specs/2026-09-20-engineering-team-design.md (diseño
 * original de Engineering, sin cambios de contenido) y
 * docs/superpowers/specs/2026-09-21-creative-marketing-teams-design.md
 * (generalización + los 2 equipos nuevos).
 *
 * <p>Crear/asegurar un equipo nunca cambia {@code Agent.status} ni crea
 * ninguna {@code AgentTask} — es estructura organizativa, no ejecución
 * (regla dura heredada del spec original, sin cambios).
 */
@Service
public class TeamMemoryService {

    private static final Logger log =
            LoggerFactory.getLogger(TeamMemoryService.class);

    public static final String TEAM_ENGINEERING = "TEAM-ENGINEERING";
    public static final String TEAM_CREATIVE_PRODUCT_INTELLIGENCE = "TEAM-CREATIVE-PRODUCT-INTELLIGENCE";
    public static final String TEAM_MARKETING_GROWTH = "TEAM-MARKETING-GROWTH";

    public static final Set<String> KNOWN_TEAM_IDS = Set.of(
            TEAM_ENGINEERING, TEAM_CREATIVE_PRODUCT_INTELLIGENCE, TEAM_MARKETING_GROWTH
    );

    private record RoleDefinition(
            String agentId,
            String roleCode,
            List<String> capabilities) {
    }

    private record TeamDefinition(
            String teamId,
            String teamName,
            String teamType,
            String leaderAgentId,
            List<RoleDefinition> roles) {
    }

    private static final List<TeamDefinition> TEAMS = List.of(

            new TeamDefinition(TEAM_ENGINEERING, "Engineering Team", "ENGINEERING", "engineering", List.of(
                    new RoleDefinition("engineering", "CLOUD_ARCHITECT_LEAD_BACKEND", List.of(
                            "arquitectura de soluciones", "arquitectura cloud AWS", "arquitectura backend",
                            "decisiones técnicas", "diseño de sistemas",
                            "diseño de arquitectura de videojuegos y aplicaciones cuando aplique",
                            "descomposición técnica del trabajo", "liderazgo técnico", "revisión técnica",
                            "coordinación del Engineering Team", "AWS", "bases de datos SQL",
                            "bases de datos NoSQL", "C#", "Java", "JavaScript / TypeScript", "Flutter"
                    )),
                    new RoleDefinition("qa", "QA_CLOUD_PERFORMANCE_ENGINEER", List.of(
                            "QA", "pruebas funcionales", "pruebas de integración", "pruebas de regresión",
                            "pruebas de rendimiento", "pruebas de carga", "validación de estabilidad",
                            "análisis de errores", "playtesting cuando corresponda",
                            "validación de performance", "validación de calidad"
                    )),
                    new RoleDefinition("devops", "CLOUD_DB_SRE_DEVOPS", List.of(
                            "PostgreSQL", "Redis", "bases de datos SQL", "bases de datos NoSQL",
                            "bases de datos cloud", "infraestructura", "SRE", "observabilidad",
                            "rendimiento", "backups", "recuperación", "Terraform / Pulumi",
                            "operación cloud AWS", "capacidades de infraestructura cuando el Proyecto B esté implementado"
                    )),
                    new RoleDefinition("backend", "DEV_BACKEND_INTEGRATIONS", List.of(
                            "backend", "APIs", "integraciones", "microservicios", "lógica de negocio",
                            "servicios backend", "integraciones con terceros",
                            "componentes backend para aplicaciones y videojuegos",
                            "C#", "Java", "JavaScript / TypeScript", "Flutter"
                    )),
                    new RoleDefinition("frontend-ui", "FRONTEND_GAME_UI_SPECIALIST", List.of(
                            "frontend", "interfaces web", "UI", "UX técnica", "Game UI", "HUD", "menús",
                            "interfaces de aplicaciones y videojuegos",
                            "C#", "Java", "JavaScript / TypeScript", "Flutter"
                    ))
            )),

            new TeamDefinition(TEAM_CREATIVE_PRODUCT_INTELLIGENCE, "Creative / Product Intelligence",
                    "CREATIVE_PRODUCT_INTELLIGENCE", "interaction-design", List.of(
                    new RoleDefinition("interaction-design", "INTERACTIVE_LOGIC_PRODUCT_DESIGNER", List.of(
                            "UX y arquitectura de interacción", "flujos de usuario", "sistemas de gamificación",
                            "engagement y retención", "game design", "game loop", "reglas y mecánicas",
                            "curva de aprendizaje", "balance de gameplay",
                            "economía interna de productos interactivos"
                    )),
                    new RoleDefinition("visual-design", "VISUAL_ASSET_DIRECTOR", List.of(
                            "identidad visual", "dirección artística", "branding", "ilustraciones",
                            "assets de marketing", "assets 2D/3D", "sprites", "animaciones", "iluminación",
                            "consistencia visual del producto", "dirección visual para videojuegos y aplicaciones"
                    )),
                    new RoleDefinition("telemetry", "TELEMETRY_ANALYTICS", List.of(
                            "análisis de producto", "funnels de conversión", "activación", "retención",
                            "churn", "comportamiento de usuarios", "métricas de sesión",
                            "telemetría de videojuegos", "análisis de gameplay", "análisis de monetización",
                            "experimentación", "generación de insights y recomendaciones basadas en datos"
                    ))
            )),

            new TeamDefinition(TEAM_MARKETING_GROWTH, "Marketing & Growth",
                    "MARKETING_GROWTH", "growth-content", List.of(
                    new RoleDefinition("growth-content", "GROWTH_CONTENT_COMMUNITY", List.of(
                            "growth", "marketing de contenidos", "SEO", "adquisición orgánica",
                            "Product-Led Growth", "newsletters", "redes sociales", "devlogs", "campañas",
                            "estrategia de adquisición", "construcción de audiencia",
                            "coordinación de iniciativas de comunidad"
                    )),
                    new RoleDefinition("community", "COMMUNITY_MANAGER", List.of(
                            "gestión diaria de comunidades", "interacción con usuarios", "moderación",
                            "Discord", "redes sociales", "recopilación de feedback",
                            "comunicación con la comunidad", "eventos y actividades",
                            "identificación de necesidades y problemas de usuarios",
                            "escalamiento de feedback relevante hacia Product, Growth y CEO"
                    ))
            ))
    );

    private final Driver driver;

    public TeamMemoryService(Driver driver) {
        this.driver = driver;
    }

    /**
     * Idempotente: por cada uno de los 3 {@link TeamDefinition}, {@code
     * MERGE} del {@code Team}, {@code SET} de
     * {@code roleCode}/{@code capabilities} sobre cada {@code Agent} ya
     * existente (nunca {@code MERGE} de Agent -- esos ya los crea
     * {@link CompanyMemoryService#initializeCompanyAndAgents()}), y
     * {@code MERGE} de {@code MEMBER_OF}/{@code LEADS}. Llamado desde
     * {@code CompanyMemoryInitializer} después de que los agentes ya
     * existan.
     */
    public void ensureAllTeams() {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                for (var team : TEAMS) {
                    ensureTeam(tx, team);
                }
                return null;
            });
        }
    }

    private void ensureTeam(Transaction tx, TeamDefinition team) {

        tx.run("MERGE (t:Team {id:$id}) SET t.name=$name, t.type=$type, t.status=$status",
                Map.of("id", team.teamId(), "name", team.teamName(),
                        "type", team.teamType(), "status", "ACTIVE"));

        for (var role : team.roles()) {
            var result = tx.run("MATCH (a:Agent {id:$agentId}), (t:Team {id:$teamId}) "
                            + "SET a.roleCode=$roleCode, a.capabilities=$capabilities "
                            + "MERGE (a)-[:MEMBER_OF]->(t)",
                    Map.of("agentId", role.agentId(), "teamId", team.teamId(),
                            "roleCode", role.roleCode(), "capabilities", role.capabilities()));

            // SET corre siempre, en cada arranque, sin importar si
            // MEMBER_OF ya existía -- a diferencia de un MERGE de
            // relación (idempotente, "no creado" es normal en un
            // restart), propertiesSet()==0 acá solo puede significar
            // que el MATCH de Agent no encontró nada: señal confiable
            // de un agentId hardcodeado en ROLES que dejó de existir,
            // sin falsos positivos en restarts normales.
            if (result.consume().counters().propertiesSet() == 0) {
                log.warn("TEAM_MEMBER_NOT_FOUND teamId={} agentId={} — no se pudo agregar al equipo",
                        team.teamId(), role.agentId());
            }
        }

        // Nota: no se agrega un chequeo equivalente para LEADS -- es un
        // MERGE puro (sin SET), así que relationshipsCreated()==0 es el
        // camino NORMAL en cualquier arranque posterior al primero (la
        // relación ya existe), no una señal de que leaderAgentId no
        // matcheó ningún Agent. Mismo criterio ya usado en el servicio
        // original de Engineering.
        tx.run("MATCH (a:Agent {id:$leaderId}), (t:Team {id:$teamId}) MERGE (a)-[:LEADS]->(t)",
                Map.of("leaderId", team.leaderAgentId(), "teamId", team.teamId()));
    }

    /**
     * Lectura real para el Company Chat — nunca inventa un miembro, rol,
     * capability o líder que no esté en Company Memory. Devuelve
     * {@code members} vacío (nunca {@code null}) si el {@code teamId}
     * todavía no existe o no es uno de los 3 conocidos.
     */
    public TeamSnapshot snapshot(String teamId) {
        try (var session = driver.session()) {

            var rows = session.run(
                    "MATCH (a:Agent)-[:MEMBER_OF]->(t:Team {id:$teamId}) "
                            + "OPTIONAL MATCH (a)-[leads:LEADS]->(t) "
                            + "RETURN t.id AS teamId, t.name AS teamName, t.status AS teamStatus, "
                            + "a.id AS agentId, a.name AS name, a.role AS role, a.roleCode AS roleCode, "
                            + "a.capabilities AS capabilities, a.model AS model, "
                            + "leads IS NOT NULL AS isLeader "
                            + "ORDER BY a.id",
                    Map.of("teamId", teamId)
            ).list();

            if (rows.isEmpty()) {
                return new TeamSnapshot(teamId, null, null, null, List.of());
            }

            var members = new ArrayList<TeamMemberInfo>();
            String leaderAgentId = null;

            for (var row : rows) {

                var agentId = row.get("agentId").asString();

                members.add(new TeamMemberInfo(
                        agentId,
                        row.get("name").asString(),
                        row.get("role").asString(),
                        row.get("roleCode").isNull() ? null : row.get("roleCode").asString(),
                        row.get("capabilities").isNull()
                                ? List.of()
                                : row.get("capabilities").asList(v -> v.asString()),
                        row.get("model").isNull() ? null : row.get("model").asString()
                ));

                if (row.get("isLeader").asBoolean()) {
                    leaderAgentId = agentId;
                }
            }

            var first = rows.get(0);

            return new TeamSnapshot(
                    first.get("teamId").asString(),
                    first.get("teamName").asString(),
                    first.get("teamStatus").asString(),
                    leaderAgentId,
                    members
            );
        }
    }
}
```

- [ ] **Step 4: Borrar `service/EngineeringTeamMemoryService.java`**

```bash
git rm app/src/main/java/com/aicompany/core/service/EngineeringTeamMemoryService.java
```

- [ ] **Step 5: Actualizar `config/CompanyMemoryInitializer.java`**

Reemplazar el contenido completo del archivo:

```java
package com.aicompany.core.config;

import com.aicompany.core.service.CompanyMemoryService;
import com.aicompany.core.service.MissionMemoryService;
import com.aicompany.core.service.TeamMemoryService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class CompanyMemoryInitializer {
    private final CompanyMemoryService memory;
    private final MissionMemoryService missionMemory;
    private final TeamMemoryService teamMemory;

    public CompanyMemoryInitializer(
            CompanyMemoryService memory,
            MissionMemoryService missionMemory,
            TeamMemoryService teamMemory) {

        this.memory = memory;
        this.missionMemory = missionMemory;
        this.teamMemory = teamMemory;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeAfterReady() {
        memory.initialize();
        missionMemory.backfillMissionEnvironment();
        teamMemory.ensureAllTeams();
    }
}
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aicompany/core/model/TeamSnapshot.java \
        app/src/main/java/com/aicompany/core/service/TeamMemoryService.java \
        app/src/main/java/com/aicompany/core/config/CompanyMemoryInitializer.java
git commit -m "Generalizar EngineeringTeamMemoryService a TeamMemoryService (3 equipos: Engineering, Creative/PI, Marketing & Growth)

Build queda roto en ChatIntentRouter/ChatIntentRouterTest hasta el
próximo commit -- referencian los tipos viejos, se actualizan ahí."
```

---

### Task 3: `ChatIntentRouter` — `QueryIntent.TEAM_DETAILS` genérico (restaura el build)

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java`
- Modify: `app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java`

**Interfaces:**
- Consumes: `TeamMemoryService.snapshot(String teamId)`, `TeamMemoryService.KNOWN_TEAM_IDS`, `TeamMemoryService.TEAM_ENGINEERING`/`TEAM_CREATIVE_PRODUCT_INTELLIGENCE`/`TEAM_MARKETING_GROWTH` (de Task 2).
- Produces: `ChatIntentRouter.answerMemoryTopic(String topic)` acepta ahora también topics con forma `"TEAM_DETAILS:<teamId>"` — Task 4 (`CeoService`) construye ese string compuesto antes de pasarlo al callback.

- [ ] **Step 1: Reemplazar el campo y el constructor de `EngineeringTeamMemoryService` por `TeamMemoryService`**

En `ChatIntentRouter.java`, cambiar:

```java
    private final EngineeringTeamMemoryService engineeringTeamMemory;
```
a:
```java
    private final TeamMemoryService teamMemory;
```

Y en el constructor, cambiar el último parámetro y su asignación:

```java
            @Value("${ollama.ceo-model}") String defaultCeoModel,
            EngineeringTeamMemoryService engineeringTeamMemory) {

        this.missionService = missionService;
        this.ceoService = ceoService;
        this.missionMemory = missionMemory;
        this.opportunityMemory = opportunityMemory;
        this.customerMemory = customerMemory;
        this.companyMemory = companyMemory;
        this.conversationMemory = conversationMemory;
        this.appProperties = appProperties;
        this.productStatusService = productStatusService;
        this.defaultCeoModel = defaultCeoModel;
        this.engineeringTeamMemory = engineeringTeamMemory;
    }
```
a:
```java
            @Value("${ollama.ceo-model}") String defaultCeoModel,
            TeamMemoryService teamMemory) {

        this.missionService = missionService;
        this.ceoService = ceoService;
        this.missionMemory = missionMemory;
        this.opportunityMemory = opportunityMemory;
        this.customerMemory = customerMemory;
        this.companyMemory = companyMemory;
        this.conversationMemory = conversationMemory;
        this.appProperties = appProperties;
        this.productStatusService = productStatusService;
        this.defaultCeoModel = defaultCeoModel;
        this.teamMemory = teamMemory;
    }
```

- [ ] **Step 2: Reemplazar el enum `QueryIntent` y agregar `TeamKeywordRule`/`QueryMatch`**

Cambiar:
```java
    private enum QueryIntent {
        ENGINEERING_TEAM,
        AGENT_STATUS,
        MISSIONS_NEEDING_ATTENTION,
        FAILED_MISSIONS,
        TEST_MISSIONS,
        OPPORTUNITIES,
        COMPANY_PROFIT,
        COMPANY_STATUS
    }
```
a:
```java
    private enum QueryIntent {
        TEAM_DETAILS,
        AGENT_STATUS,
        MISSIONS_NEEDING_ATTENTION,
        FAILED_MISSIONS,
        TEST_MISSIONS,
        OPPORTUNITIES,
        COMPANY_PROFIT,
        COMPANY_STATUS
    }

    private record TeamKeywordRule(String teamId, List<String> topicKeywords) {}

    private static final List<TeamKeywordRule> TEAM_KEYWORD_RULES = List.of(
            new TeamKeywordRule(TeamMemoryService.TEAM_ENGINEERING,
                    List.of("ingenieria", "engineering")),
            new TeamKeywordRule(TeamMemoryService.TEAM_CREATIVE_PRODUCT_INTELLIGENCE,
                    List.of("creativ", "product intelligence", "visual", "arte", "telemetria", "analytics")),
            new TeamKeywordRule(TeamMemoryService.TEAM_MARKETING_GROWTH,
                    List.of("marketing", "growth", "crecimiento", "comunidad", "community"))
    );

    private record QueryMatch(QueryIntent intent, String teamId) {}
```

- [ ] **Step 3: Reemplazar `detectQuery` completo**

Cambiar la firma y el cuerpo de `private QueryIntent detectQuery(String message)` a `private QueryMatch detectQuery(String message)`, reemplazando el bloque específico de `ENGINEERING_TEAM` por el loop sobre `TEAM_KEYWORD_RULES` y envolviendo cada `return QueryIntent.X;` restante en `new QueryMatch(QueryIntent.X, null)`:

```java
    private QueryMatch detectQuery(String message) {

        var normalized = normalize(message);

        var teamGate = normalized.contains("equipo") || normalized.contains("team")
                || normalized.contains("lidera") || normalized.contains("lider");

        if (teamGate) {
            // Chequeado antes que AGENT_STATUS a propósito: "equipo" solo
            // ya dispara AGENT_STATUS (bug real corregido en una ronda
            // anterior, ver CLAUDE.md) -- preguntar por un equipo puntual
            // ("el equipo creativo", "quién lidera marketing") es más
            // específico que el estado general de agentes.
            for (var rule : TEAM_KEYWORD_RULES) {
                if (rule.topicKeywords().stream().anyMatch(normalized::contains)) {
                    return new QueryMatch(QueryIntent.TEAM_DETAILS, rule.teamId());
                }
            }
        }

        if (normalized.contains("equipo")
                || normalized.contains("agente")
                || normalized.contains("trabajando")) {
            // Deliberadamente amplio (antes exigía "agente" Y
            // "trabaj"/"estado" juntos, lo que dejaba afuera preguntas
            // reales como "preséntame al equipo", "¿quién está
            // trabajando ahora?" o "¿qué está haciendo cada agente?" --
            // ninguna de esas tres contiene ambas palabras a la vez,
            // reproducido en vivo por el usuario). Caían al chat
            // general, donde el CEO (LLM) elaboraba por encima del
            // roster real inyectado en el prompt e incluso agregaba
            // disclaimers de privacidad contradictorios. Todas son la
            // misma pregunta de fondo (estado real de los agentes), así
            // que resuelven igual: 100% desde Neo4j, sin pasar por el
            // modelo.
            return new QueryMatch(QueryIntent.AGENT_STATUS, null);
        }

        if (normalized.contains("fallaron")
                || normalized.contains("fallidas")
                || normalized.contains("fallida")
                || normalized.contains("misiones fallo")) {
            // Chequeo antes que MISSIONS_NEEDING_ATTENTION a propósito:
            // antes una sola consulta mezclaba AWAITING_INVESTOR y FAILED
            // bajo "necesitan tu aprobación" -- una misión FAILED no
            // necesita aprobación, reportado por el usuario ("de las 25,
            // 15 AWAITING_INVESTOR y 10 FAILED, pero el texto decía que
            // las 25 necesitaban aprobación"). Consultas separadas, cada
            // una estricta sobre su propio status real.
            return new QueryMatch(QueryIntent.FAILED_MISSIONS, null);
        }

        if (TEST_ENVIRONMENT.matcher(normalized).find() || normalized.contains("entorno de test")) {
            // "¿qué misiones están en prueba?" -- Mission.environment=TEST,
            // cualquier status. Reportado por el usuario: sin esto, ~25
            // misiones de desarrollo (MISSION-STRUCTURED-*, MVP-*, etc.)
            // contaminaban toda pregunta de negocio real.
            return new QueryMatch(QueryIntent.TEST_MISSIONS, null);
        }

        if (normalized.contains("aprobacion")
                || normalized.contains("bloquead")
                || normalized.contains("necesita")) {
            return new QueryMatch(QueryIntent.MISSIONS_NEEDING_ATTENTION, null);
        }

        if (normalized.contains("oportunidad")) {
            return new QueryMatch(QueryIntent.OPPORTUNITIES, null);
        }

        if (normalized.contains("gastado")
                || normalized.contains("gasto")
                || normalized.contains("dinero")
                || normalized.contains("ganancia")
                || normalized.contains("beneficio")) {
            return new QueryMatch(QueryIntent.COMPANY_PROFIT, null);
        }

        if (normalized.contains("status")
                || normalized.contains("estado general")
                || normalized.contains("estado actual")
                || normalized.contains("estado de forjai")
                || normalized.contains("estado de la empresa")) {
            // Catch-all deliberado, chequeado al final: reportado por el
            // usuario -- "dame un status" no matcheaba ningún keyword
            // específico y caía al chat general, donde el CEO inventaba
            // un resumen completo con placeholders sin rellenar
            // ("[Nombre del cliente]", "[Precio]") porque no existía
            // ninguna fuente real de la que sacar esos datos. Un resumen
            // agregado de la empresa es tan determinista como contar una
            // lista -- no hay ninguna razón para dejárselo al modelo.
            return new QueryMatch(QueryIntent.COMPANY_STATUS, null);
        }

        return null;
    }
```

El call site en `route()`/`resolve()` (`var query = detectQuery(message); if (query != null) { return handleQuery(query); }`) **no cambia** — sigue funcionando igual, solo que ahora `query` es un `QueryMatch`.

- [ ] **Step 4: Reemplazar `handleQuery`**

Cambiar:
```java
    private String handleQuery(QueryIntent intent) {

        log.info("CHAT_INTENT_QUERY intent={}", intent);

        return answerMemoryTopic(intent.name());
    }
```
a:
```java
    private String handleQuery(QueryMatch match) {

        log.info("CHAT_INTENT_QUERY intent={} teamId={}", match.intent(), match.teamId());

        if (match.intent() == QueryIntent.TEAM_DETAILS) {
            return answerMemoryTopic("TEAM_DETAILS:" + match.teamId());
        }

        return answerMemoryTopic(match.intent().name());
    }
```

- [ ] **Step 5: Actualizar `answerMemoryTopic` y reemplazar `formatEngineeringTeam()` por `formatTeamDetails(String)`**

Cambiar:
```java
    String answerMemoryTopic(String topic) {

        return switch (topic) {
            case "ENGINEERING_TEAM" -> formatEngineeringTeam();
            case "AGENT_STATUS" -> formatAgentStatus(missionMemory.latestTaskPerAgent());
            case "MISSIONS_NEEDING_ATTENTION" -> formatMissionsNeedingAttention(missionMemory.findAll(50));
            case "FAILED_MISSIONS" -> formatFailedMissions(missionMemory.findAll(50));
            case "TEST_MISSIONS" -> formatTestMissions(missionMemory.findAll(50));
            case "LAST_MENTIONED" -> formatLastMentioned();
            case "OPPORTUNITIES" -> formatOpportunities(opportunityMemory.listRecent(20));
            case "COMPANY_PROFIT" -> formatCompanyProfit(customerMemory.companyWideTotalRevenueAndCost());
            case "COMPANY_STATUS" -> formatCompanyStatus();
            default -> "Dato no reconocido: " + topic + ".";
        };
    }
```
a:
```java
    String answerMemoryTopic(String topic) {

        if (topic != null && topic.startsWith("TEAM_DETAILS:")) {
            return formatTeamDetails(topic.substring("TEAM_DETAILS:".length()));
        }

        return switch (topic) {
            case "AGENT_STATUS" -> formatAgentStatus(missionMemory.latestTaskPerAgent());
            case "MISSIONS_NEEDING_ATTENTION" -> formatMissionsNeedingAttention(missionMemory.findAll(50));
            case "FAILED_MISSIONS" -> formatFailedMissions(missionMemory.findAll(50));
            case "TEST_MISSIONS" -> formatTestMissions(missionMemory.findAll(50));
            case "LAST_MENTIONED" -> formatLastMentioned();
            case "OPPORTUNITIES" -> formatOpportunities(opportunityMemory.listRecent(20));
            case "COMPANY_PROFIT" -> formatCompanyProfit(customerMemory.companyWideTotalRevenueAndCost());
            case "COMPANY_STATUS" -> formatCompanyStatus();
            default -> "Dato no reconocido: " + topic + ".";
        };
    }
```

Cambiar el javadoc + cuerpo de `formatEngineeringTeam()`:
```java
    /**
     * Snapshot real del Engineering Team — cruza
     * {@link EngineeringTeamMemoryService#snapshot()} (miembros, roles,
     * roleCode, capabilities, modelo, líder) con
     * {@code missionMemory.latestTaskPerAgent()} (status/tarea actual
     * real) — mismo criterio que {@code formatMissionStatus}: "quién es"
     * y "qué está haciendo ahora" son preguntas distintas, ninguna se
     * infiere de la otra. 100% Java, nunca pasa por Ollama. Cada línea
     * arranca con el {@code agentId} real (no solo el nombre) — es el
     * identificador que el resto del chat usa para referirse al agente,
     * y hay un test que lo exige explícitamente; no lo saques.
     */
    private String formatEngineeringTeam() {

        var snapshot = engineeringTeamMemory.snapshot();

        if (snapshot.members().isEmpty()) {
            return "No tengo ese dato registrado. El Engineering Team todavía no está registrado en Company Memory.";
        }

        var statusByAgentId = missionMemory.latestTaskPerAgent().stream()
                .collect(Collectors.toMap(AgentStatusResponse::agentId, a -> a));

        var lines = snapshot.members().stream()
                .map(m -> {
                    var status = statusByAgentId.get(m.agentId());
                    var leaderTag = m.agentId().equals(snapshot.leaderAgentId()) ? " (líder)" : "";
                    var statusText = status != null ? status.status() : "no registrado";
                    var taskText = status != null && status.action() != null
                            ? ", tarea actual: " + status.action() + " (" + status.taskStatus() + ")"
                            : "";

                    return m.agentId() + ": " + m.name() + leaderTag + " — " + m.role() + " ["
                            + java.util.Objects.toString(m.roleCode(), "no registrado") + "]: "
                            + "capabilities=" + String.join(", ", m.capabilities())
                            + ", model=" + java.util.Objects.toString(m.model(), "no registrado")
                            + ", status=" + statusText + taskText;
                })
                .collect(Collectors.joining(" | "));

        return "Engineering Team (" + snapshot.status() + "): " + lines;
    }
```
a:
```java
    /**
     * Snapshot real de uno de los 3 equipos de Forjai (ver
     * {@code TeamMemoryService.KNOWN_TEAM_IDS}) — cruza
     * {@link TeamMemoryService#snapshot(String)} (miembros, roles,
     * roleCode, capabilities, modelo, líder) con
     * {@code missionMemory.latestTaskPerAgent()} (status/tarea actual
     * real) — mismo criterio que {@code formatMissionStatus}: "quién es"
     * y "qué está haciendo ahora" son preguntas distintas, ninguna se
     * infiere de la otra. 100% Java, nunca pasa por Ollama. Cada línea
     * arranca con el {@code agentId} real (no solo el nombre) — es el
     * identificador que el resto del chat usa para referirse al agente,
     * y hay un test que lo exige explícitamente; no lo saques. Un
     * {@code teamId} desconocido (fuera de {@code KNOWN_TEAM_IDS})
     * nunca llega a Neo4j — devuelve directamente el mismo disclaimer
     * que un dato no registrado, para no poder "descubrir" un equipo
     * inexistente por prueba y error.
     */
    private String formatTeamDetails(String teamId) {

        if (!TeamMemoryService.KNOWN_TEAM_IDS.contains(teamId)) {
            return "No tengo ese dato registrado.";
        }

        var snapshot = teamMemory.snapshot(teamId);

        if (snapshot.members().isEmpty()) {
            return "No tengo ese dato registrado. Ese equipo todavía no está registrado en Company Memory.";
        }

        var statusByAgentId = missionMemory.latestTaskPerAgent().stream()
                .collect(Collectors.toMap(AgentStatusResponse::agentId, a -> a));

        var lines = snapshot.members().stream()
                .map(m -> {
                    var status = statusByAgentId.get(m.agentId());
                    var leaderTag = m.agentId().equals(snapshot.leaderAgentId()) ? " (líder)" : "";
                    var statusText = status != null ? status.status() : "no registrado";
                    var taskText = status != null && status.action() != null
                            ? ", tarea actual: " + status.action() + " (" + status.taskStatus() + ")"
                            : "";

                    return m.agentId() + ": " + m.name() + leaderTag + " — " + m.role() + " ["
                            + java.util.Objects.toString(m.roleCode(), "no registrado") + "]: "
                            + "capabilities=" + String.join(", ", m.capabilities())
                            + ", model=" + java.util.Objects.toString(m.model(), "no registrado")
                            + ", status=" + statusText + taskText;
                })
                .collect(Collectors.joining(" | "));

        return snapshot.teamName() + " (" + snapshot.status() + "): " + lines;
    }
```

- [ ] **Step 6: Actualizar `ChatIntentRouterTest.java` — imports, mock y wiring del constructor**

Cambiar el import (línea 7):
```java
import com.aicompany.core.model.EngineeringTeamSnapshot;
```
a:
```java
import com.aicompany.core.model.TeamSnapshot;
```

Cambiar el campo mock y la construcción del router:
```java
    private final EngineeringTeamMemoryService engineeringTeamMemory = mock(EngineeringTeamMemoryService.class);

    private final ChatIntentRouter router = new ChatIntentRouter(
            missionService, ceoService, missionMemory, opportunityMemory, customerMemory, companyMemory,
            conversationMemory, appProperties, productStatusService, "qwen2.5-coder:14b", engineeringTeamMemory
    );
```
a:
```java
    private final TeamMemoryService teamMemory = mock(TeamMemoryService.class);

    private final ChatIntentRouter router = new ChatIntentRouter(
            missionService, ceoService, missionMemory, opportunityMemory, customerMemory, companyMemory,
            conversationMemory, appProperties, productStatusService, "qwen2.5-coder:14b", teamMemory
    );
```

- [ ] **Step 7: Actualizar los 2 tests existentes de Engineering Team al nuevo mock**

Cambiar:
```java
    @Test
    void routesEngineeringTeamQueryToADeterministicFormatting() {

        var snapshot = new EngineeringTeamSnapshot(
                "TEAM-ENGINEERING", "Engineering Team", "ACTIVE", "engineering",
                List.of(
                        new TeamMemberInfo("engineering", "Neo", "Cloud Architect & Lead Backend",
                                "CLOUD_ARCHITECT_LEAD_BACKEND", List.of("AWS", "C#"), "qwen2.5-coder:14b"),
                        new TeamMemberInfo("qa", "Vera", "QA & Cloud Performance Engineer",
                                "QA_CLOUD_PERFORMANCE_ENGINEER", List.of("QA", "pruebas de carga"), "qwen3:8b")
                )
        );
        when(engineeringTeamMemory.snapshot()).thenReturn(snapshot);
        when(missionMemory.latestTaskPerAgent()).thenReturn(List.of(
                new AgentStatusResponse("engineering", "Neo", "Cloud Architect & Lead Backend", "x",
                        "IDLE", null, null, null, Instant.now())
        ));

        var response = router.route("¿quién lidera el equipo de ingenieria?");

        assertTrue(response.contains("Neo"));
        assertTrue(response.contains("Vera"));
        assertTrue(response.contains("CLOUD_ARCHITECT_LEAD_BACKEND"));
        assertTrue(response.contains("engineering"));
        verifyNoInteractions(ceoService);
    }

    @Test
    void engineeringTeamQueryNeverInventsDataWhenTeamNotYetRegistered() {

        when(engineeringTeamMemory.snapshot()).thenReturn(
                new EngineeringTeamSnapshot("TEAM-ENGINEERING", null, null, null, List.of())
        );

        var response = router.route("cuéntame del engineering team");

        assertEquals(
                "No tengo ese dato registrado. El Engineering Team todavía no está registrado en Company Memory.",
                response
        );
        verifyNoInteractions(ceoService);
    }
```
a:
```java
    @Test
    void routesEngineeringTeamQueryToADeterministicFormatting() {

        var snapshot = new TeamSnapshot(
                "TEAM-ENGINEERING", "Engineering Team", "ACTIVE", "engineering",
                List.of(
                        new TeamMemberInfo("engineering", "Neo", "Cloud Architect & Lead Backend",
                                "CLOUD_ARCHITECT_LEAD_BACKEND", List.of("AWS", "C#"), "qwen2.5-coder:14b"),
                        new TeamMemberInfo("qa", "Vera", "QA & Cloud Performance Engineer",
                                "QA_CLOUD_PERFORMANCE_ENGINEER", List.of("QA", "pruebas de carga"), "qwen3:8b")
                )
        );
        when(teamMemory.snapshot("TEAM-ENGINEERING")).thenReturn(snapshot);
        when(missionMemory.latestTaskPerAgent()).thenReturn(List.of(
                new AgentStatusResponse("engineering", "Neo", "Cloud Architect & Lead Backend", "x",
                        "IDLE", null, null, null, Instant.now())
        ));

        var response = router.route("¿quién lidera el equipo de ingenieria?");

        assertTrue(response.contains("Neo"));
        assertTrue(response.contains("Vera"));
        assertTrue(response.contains("CLOUD_ARCHITECT_LEAD_BACKEND"));
        assertTrue(response.contains("engineering"));
        verifyNoInteractions(ceoService);
    }

    @Test
    void engineeringTeamQueryNeverInventsDataWhenTeamNotYetRegistered() {

        when(teamMemory.snapshot("TEAM-ENGINEERING")).thenReturn(
                new TeamSnapshot("TEAM-ENGINEERING", null, null, null, List.of())
        );

        var response = router.route("cuéntame del engineering team");

        assertEquals(
                "No tengo ese dato registrado. Ese equipo todavía no está registrado en Company Memory.",
                response
        );
        verifyNoInteractions(ceoService);
    }

    @Test
    void routesCreativeProductIntelligenceTeamQueryToADeterministicFormatting() {

        var snapshot = new TeamSnapshot(
                "TEAM-CREATIVE-PRODUCT-INTELLIGENCE", "Creative / Product Intelligence", "ACTIVE", "interaction-design",
                List.of(
                        new TeamMemberInfo("interaction-design", "Kael", "Interactive Logic & Product Designer AI",
                                "INTERACTIVE_LOGIC_PRODUCT_DESIGNER", List.of("game design", "UX y arquitectura de interacción"), "qwen3:8b"),
                        new TeamMemberInfo("visual-design", "Maya", "Visual & Asset Director AI",
                                "VISUAL_ASSET_DIRECTOR", List.of("identidad visual", "branding"), "qwen3:8b")
                )
        );
        when(teamMemory.snapshot("TEAM-CREATIVE-PRODUCT-INTELLIGENCE")).thenReturn(snapshot);
        when(missionMemory.latestTaskPerAgent()).thenReturn(List.of(
                new AgentStatusResponse("interaction-design", "Kael", "Interactive Logic & Product Designer AI", "x",
                        "IDLE", null, null, null, Instant.now())
        ));

        var response = router.route("¿quién lidera el equipo creativo?");

        assertTrue(response.contains("Kael"));
        assertTrue(response.contains("Maya"));
        assertTrue(response.contains("INTERACTIVE_LOGIC_PRODUCT_DESIGNER"));
        assertTrue(response.contains("Creative / Product Intelligence"));
        verifyNoInteractions(ceoService);
    }

    @Test
    void routesMarketingGrowthTeamQueryToADeterministicFormatting() {

        var snapshot = new TeamSnapshot(
                "TEAM-MARKETING-GROWTH", "Marketing & Growth", "ACTIVE", "growth-content",
                List.of(
                        new TeamMemberInfo("growth-content", "Kira", "Growth, Content & Community AI",
                                "GROWTH_CONTENT_COMMUNITY", List.of("growth", "SEO"), "qwen3:8b"),
                        new TeamMemberInfo("community", "Nora", "Community Manager AI",
                                "COMMUNITY_MANAGER", List.of("moderación", "Discord"), "qwen3:8b")
                )
        );
        when(teamMemory.snapshot("TEAM-MARKETING-GROWTH")).thenReturn(snapshot);
        when(missionMemory.latestTaskPerAgent()).thenReturn(List.of(
                new AgentStatusResponse("growth-content", "Kira", "Growth, Content & Community AI", "x",
                        "IDLE", null, null, null, Instant.now())
        ));

        var response = router.route("cuéntame del equipo de marketing");

        assertTrue(response.contains("Kira"));
        assertTrue(response.contains("Nora"));
        assertTrue(response.contains("GROWTH_CONTENT_COMMUNITY"));
        assertTrue(response.contains("Marketing & Growth"));
        verifyNoInteractions(ceoService);
    }

    @Test
    void teamDetailsWithUnknownTeamIdNeverInventsData() {

        var response = router.answerMemoryTopic("TEAM_DETAILS:TEAM-BOGUS");

        assertEquals("No tengo ese dato registrado.", response);
        verifyNoInteractions(teamMemory);
    }
```

- [ ] **Step 8: Agregar el topic `TEAM_DETAILS` al test que resuelve todos los topics conocidos**

En `passesCompanyMemoryQueryCallbackThatResolvesAllKnownTopics`, agregar el stub y la aserción nueva (junto a los demás `when(...)`/`assertTrue(...)`):

```java
        when(teamMemory.snapshot("TEAM-ENGINEERING")).thenReturn(
                new TeamSnapshot("TEAM-ENGINEERING", null, null, null, List.of())
        );
```
(agregar junto a los otros `when(...)` del test, antes de `router.route("Hola, ¿cómo estás?");`), y:
```java
        assertTrue(companyMemoryQuery.apply("TEAM_DETAILS:TEAM-ENGINEERING").contains("No tengo ese dato registrado"));
```
(agregar junto a las otras aserciones `assertTrue(companyMemoryQuery.apply(...)...)`).

- [ ] **Step 9: Compilar y correr la suite completa**

Run: `cd app && mvn test`
Expected: BUILD SUCCESS. El build vuelve a estar verde (roto desde Task 2). Conteo esperado: 166 (base) − 0 removidos + 3 tests nuevos (Creative/PI, Marketing&Growth, unknown teamId) + 0 en el test de "all known topics" (mismo test, solo una aserción más) = **169 tests**, todos en verde.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java \
        app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java
git commit -m "ChatIntentRouter: QueryIntent.TEAM_DETAILS genérico (reemplaza ENGINEERING_TEAM), consulta los 3 equipos"
```

---

### Task 4: `CeoService` — tool `query_company_memory` con `teamId`

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/CeoService.java`
- Create: `app/src/test/java/com/aicompany/core/service/CeoServiceCompanyMemoryTopicTest.java`

**Interfaces:**
- Consumes: nada de las tasks anteriores (self-contained, sin dependencia de `TeamMemoryService`).
- Produces: `CeoService.parseCompanyMemoryTopic(Map)`/`CeoService.detectInlineCompanyMemoryTopic(String)` devuelven `"TEAM_DETAILS:" + teamId` cuando el `topic` crudo es `"TEAM_DETAILS"` — el string compuesto que `ChatIntentRouter.answerMemoryTopic` (Task 3) ya sabe interpretar.

- [ ] **Step 1: Actualizar el enum `topic` y agregar `teamId` en `COMPANY_MEMORY_TOOLS`**

Reemplazar el bloque completo de `COMPANY_MEMORY_TOOLS` (desde `private static final List<Map<String, Object>> COMPANY_MEMORY_TOOLS = List.of(` hasta el `);` de cierre) por:

```java
    private static final List<Map<String, Object>> COMPANY_MEMORY_TOOLS = List.of(
            Map.of(
                    "type", "function",
                    "function", Map.of(
                            "name", "query_company_memory",
                            "description",
                            "Consulta datos reales y actuales de la "
                                    + "empresa en Neo4j (memoria "
                                    + "operacional) — nunca inventes estos "
                                    + "datos, pedí la herramienta si no los "
                                    + "tenés en este mensaje.",
                            "parameters", Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "topic", Map.of(
                                                    "type", "string",
                                                    "enum", List.of(
                                                            "TEAM_DETAILS",
                                                            "AGENT_STATUS",
                                                            "MISSIONS_NEEDING_ATTENTION",
                                                            "FAILED_MISSIONS",
                                                            "TEST_MISSIONS",
                                                            "LAST_MENTIONED",
                                                            "OPPORTUNITIES",
                                                            "COMPANY_PROFIT",
                                                            "COMPANY_STATUS"
                                                    ),
                                                    "description",
                                                    "TEAM_DETAILS: "
                                                            + "estructura real "
                                                            + "de uno de los 3 "
                                                            + "equipos de "
                                                            + "Forjai (ver "
                                                            + "teamId) -- "
                                                            + "miembros, líder, "
                                                            + "roles, "
                                                            + "roleCode, "
                                                            + "capabilities y "
                                                            + "modelo de cada "
                                                            + "uno. "
                                                            + "AGENT_STATUS: qué "
                                                            + "está "
                                                            + "haciendo cada "
                                                            + "agente ahora. "
                                                            + "MISSIONS_NEEDING_ATTENTION: "
                                                            + "misiones reales "
                                                            + "(no de prueba) "
                                                            + "que esperan tu "
                                                            + "aprobación "
                                                            + "(AWAITING_INVESTOR "
                                                            + "exclusivamente, "
                                                            + "nunca fallidas). "
                                                            + "FAILED_MISSIONS: "
                                                            + "misiones reales "
                                                            + "que fallaron. "
                                                            + "TEST_MISSIONS: "
                                                            + "misiones de "
                                                            + "prueba/desarrollo "
                                                            + "(cualquier "
                                                            + "estado) -- nunca "
                                                            + "cuentan como "
                                                            + "actividad "
                                                            + "empresarial real. "
                                                            + "LAST_MENTIONED: "
                                                            + "detalle real de "
                                                            + "las últimas "
                                                            + "misiones "
                                                            + "mencionadas en "
                                                            + "esta "
                                                            + "conversación. "
                                                            + "OPPORTUNITIES: "
                                                            + "oportunidades "
                                                            + "identificadas. "
                                                            + "COMPANY_PROFIT: "
                                                            + "ingresos/costos/"
                                                            + "utilidad reales "
                                                            + "acumulados. "
                                                            + "COMPANY_STATUS: "
                                                            + "resumen agregado "
                                                            + "y real de la "
                                                            + "empresa (capital, "
                                                            + "agentes, misiones, "
                                                            + "oportunidades, "
                                                            + "clientes, "
                                                            + "finanzas) -- "
                                                            + "usala siempre que "
                                                            + "te pidan un "
                                                            + "'status' o "
                                                            + "resumen general, "
                                                            + "nunca inventes "
                                                            + "ese resumen vos."
                                            ),
                                            "teamId", Map.of(
                                                    "type", "string",
                                                    "enum", List.of(
                                                            "TEAM-ENGINEERING",
                                                            "TEAM-CREATIVE-PRODUCT-INTELLIGENCE",
                                                            "TEAM-MARKETING-GROWTH"
                                                    ),
                                                    "description",
                                                    "Obligatorio solo si "
                                                            + "topic=TEAM_DETAILS: "
                                                            + "qué equipo. "
                                                            + "TEAM-ENGINEERING: "
                                                            + "arquitectura, "
                                                            + "backend, devops, "
                                                            + "frontend/UI, QA. "
                                                            + "TEAM-CREATIVE-PRODUCT-INTELLIGENCE: "
                                                            + "diseño de "
                                                            + "interacción/UX/"
                                                            + "game design, "
                                                            + "dirección "
                                                            + "visual/arte, "
                                                            + "telemetría/"
                                                            + "analytics. "
                                                            + "TEAM-MARKETING-GROWTH: "
                                                            + "growth/contenido/"
                                                            + "SEO, gestión de "
                                                            + "comunidad."
                                            )
                                    ),
                                    "required", List.of("topic")
                            )
                    )
            )
    );
```

- [ ] **Step 2: Cambiar `parseCompanyMemoryTopic` a package-private y componer el topic para `TEAM_DETAILS`**

Cambiar:
```java
    @SuppressWarnings("unchecked")
    private String parseCompanyMemoryTopic(Map<String, Object> rawToolCall) {

        var function = (Map<String, Object>) rawToolCall.get("function");

        if (function == null) {
            return null;
        }

        var name = String.valueOf(function.get("name"));
        var arguments = function.get("arguments");

        String topic = null;

        if (arguments instanceof Map<?, ?> argMap) {
            var value = argMap.get("topic");
            topic = value == null ? null : String.valueOf(value);
        }

        if (!"query_company_memory".equals(name)
                || topic == null
                || topic.isBlank()) {
            return null;
        }

        return topic;
    }
```
a:
```java
    @SuppressWarnings("unchecked")
    String parseCompanyMemoryTopic(Map<String, Object> rawToolCall) {

        var function = (Map<String, Object>) rawToolCall.get("function");

        if (function == null) {
            return null;
        }

        var name = String.valueOf(function.get("name"));
        var arguments = function.get("arguments");

        String topic = null;
        String teamId = null;

        if (arguments instanceof Map<?, ?> argMap) {
            var value = argMap.get("topic");
            topic = value == null ? null : String.valueOf(value);

            var teamIdValue = argMap.get("teamId");
            teamId = teamIdValue == null ? null : String.valueOf(teamIdValue);
        }

        if (!"query_company_memory".equals(name)
                || topic == null
                || topic.isBlank()) {
            return null;
        }

        if ("TEAM_DETAILS".equals(topic)) {
            return "TEAM_DETAILS:" + (teamId == null ? "" : teamId);
        }

        return topic;
    }
```

- [ ] **Step 3: Cambiar `detectInlineCompanyMemoryTopic` a package-private y componer el topic para `TEAM_DETAILS`**

Cambiar:
```java
    private String detectInlineCompanyMemoryTopic(String content) {

        if (content == null || content.isBlank()) {
            return null;
        }

        try {

            var normalized = normalizeJsonResponse(content);
            var node = jsonMapper.readTree(normalized);

            if (!node.isObject()) {
                return null;
            }

            var name = node.path("name").asString(null);
            var argumentsNode = node.path("arguments");

            if (!"query_company_memory".equals(name)
                    || !argumentsNode.isObject()) {
                return null;
            }

            var topic = argumentsNode.path("topic").asString(null);

            return (topic == null || topic.isBlank()) ? null : topic;

        } catch (Exception ex) {
            return null;
        }
    }
```
a:
```java
    String detectInlineCompanyMemoryTopic(String content) {

        if (content == null || content.isBlank()) {
            return null;
        }

        try {

            var normalized = normalizeJsonResponse(content);
            var node = jsonMapper.readTree(normalized);

            if (!node.isObject()) {
                return null;
            }

            var name = node.path("name").asString(null);
            var argumentsNode = node.path("arguments");

            if (!"query_company_memory".equals(name)
                    || !argumentsNode.isObject()) {
                return null;
            }

            var topic = argumentsNode.path("topic").asString(null);

            if (topic == null || topic.isBlank()) {
                return null;
            }

            if ("TEAM_DETAILS".equals(topic)) {
                var teamId = argumentsNode.path("teamId").asString(null);
                return "TEAM_DETAILS:" + (teamId == null ? "" : teamId);
            }

            return topic;

        } catch (Exception ex) {
            return null;
        }
    }
```

- [ ] **Step 4: Crear el test nuevo `CeoServiceCompanyMemoryTopicTest.java`**

```java
package com.aicompany.core.service;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.evidence.EvidenceAcquisitionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * `query_company_memory` reemplazó `ENGINEERING_TEAM` por un
 * `TEAM_DETAILS` genérico + un segundo argumento `teamId` (ver
 * docs/superpowers/specs/2026-09-21-creative-marketing-teams-design.md,
 * decisión 5) — estos tests confirman que ambos parsers de topic
 * (tool_calls real y el fallback de JSON inline, ver
 * CeoService.chat) componen el string interno
 * "TEAM_DETAILS:&lt;teamId&gt;" que espera
 * ChatIntentRouter.answerMemoryTopic, y que el resto de los topics
 * (sin teamId) siguen intactos.
 */
class CeoServiceCompanyMemoryTopicTest {

    private final CeoService ceoService = new CeoService(
            mock(RestClient.class),
            JsonMapper.builder().build(),
            mock(EvidenceAcquisitionService.class),
            mock(CompanyEventPublisher.class),
            new SimpleMeterRegistry()
    );

    @Test
    void buildsCompositeTopicForTeamDetailsWithTeamId() {

        var toolCall = Map.<String, Object>of(
                "function", Map.of(
                        "name", "query_company_memory",
                        "arguments", Map.of("topic", "TEAM_DETAILS", "teamId", "TEAM-ENGINEERING")
                )
        );

        assertEquals("TEAM_DETAILS:TEAM-ENGINEERING", ceoService.parseCompanyMemoryTopic(toolCall));
    }

    @Test
    void buildsCompositeTopicForTeamDetailsWithoutTeamId() {

        var toolCall = Map.<String, Object>of(
                "function", Map.of(
                        "name", "query_company_memory",
                        "arguments", Map.of("topic", "TEAM_DETAILS")
                )
        );

        assertEquals("TEAM_DETAILS:", ceoService.parseCompanyMemoryTopic(toolCall));
    }

    @Test
    void leavesOtherTopicsUnchanged() {

        var toolCall = Map.<String, Object>of(
                "function", Map.of(
                        "name", "query_company_memory",
                        "arguments", Map.of("topic", "AGENT_STATUS")
                )
        );

        assertEquals("AGENT_STATUS", ceoService.parseCompanyMemoryTopic(toolCall));
    }

    @Test
    void returnsNullForNonQueryCompanyMemoryToolCall() {

        var toolCall = Map.<String, Object>of(
                "function", Map.of(
                        "name", "search_web_evidence",
                        "arguments", Map.of("topic", "TEAM_DETAILS", "teamId", "TEAM-ENGINEERING")
                )
        );

        assertNull(ceoService.parseCompanyMemoryTopic(toolCall));
    }

    @Test
    void detectsInlineTeamDetailsWithTeamId() {

        var content = "{\"name\":\"query_company_memory\","
                + "\"arguments\":{\"topic\":\"TEAM_DETAILS\",\"teamId\":\"TEAM-MARKETING-GROWTH\"}}";

        assertEquals("TEAM_DETAILS:TEAM-MARKETING-GROWTH", ceoService.detectInlineCompanyMemoryTopic(content));
    }

    @Test
    void detectsInlineOtherTopicsUnchanged() {

        var content = "{\"name\":\"query_company_memory\",\"arguments\":{\"topic\":\"COMPANY_STATUS\"}}";

        assertEquals("COMPANY_STATUS", ceoService.detectInlineCompanyMemoryTopic(content));
    }
}
```

- [ ] **Step 5: Compilar y correr la suite completa**

Run: `cd app && mvn test`
Expected: BUILD SUCCESS, 169 (fin de Task 3) + 6 tests nuevos de este archivo = **175 tests**, todos en verde.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/CeoService.java \
        app/src/test/java/com/aicompany/core/service/CeoServiceCompanyMemoryTopicTest.java
git commit -m "CeoService: query_company_memory acepta teamId para TEAM_DETAILS (reemplaza ENGINEERING_TEAM)"
```

---

### Task 5: Documentación — `CLAUDE.md` + `docs/HISTORY.md`

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs/HISTORY.md`

**Interfaces:**
- Consumes: el estado final de Tasks 1-4 (14 agentes, 3 equipos, `TeamMemoryService`, `TEAM_DETAILS`).
- Produces: nada consumido por código — documentación para la próxima sesión de Claude Code.

- [ ] **Step 1: Actualizar la sección "Identidad de agentes" de `CLAUDE.md`**

Localizar el párrafo que empieza con `Identidad plana (sin` dentro de la sección `### Identidad de agentes` y reemplazar la lista de agentes:

De:
```
Identidad plana (sin `RoleVersion`/`AgentVersion`) separada del rol funcional: Alex/CEO, Sofia/Sales, Max/Finance, Luna/Product, Neo/Cloud Architect & Lead Backend (lidera el Engineering Team), Vera/QA & Cloud Performance Engineer, Diego/DevOps, Iris/Backend, Mila/Frontend & Game UI.
```
a:
```
Identidad plana (sin `RoleVersion`/`AgentVersion`) separada del rol funcional: Alex/CEO, Sofia/Sales, Max/Finance, Luna/Product, Neo/Cloud Architect & Lead Backend (lidera Engineering Team), Vera/QA & Cloud Performance Engineer, Diego/DevOps, Iris/Backend, Mila/Frontend & Game UI, Kael/Interactive Logic & Product Designer (lidera Creative / Product Intelligence), Maya/Visual & Asset Director, Gael/Telemetry & Analytics, Kira/Growth, Content & Community (lidera Marketing & Growth), Nora/Community Manager.
```

Ahora reemplazar el párrafo **completo** que describe la estructura de equipo (empieza con `**Engineering Team** (`EngineeringTeamMemoryService`,` y termina en `... más abajo).`) — este párrafo queda obsoleto por la generalización (nombra la clase vieja, `ensureEngineeringTeam()`, el topic `ENGINEERING_TEAM`, y dice "solo estos 5 agentes"):

De:
```
**Engineering Team** (`EngineeringTeamMemoryService`, `Team {id:'TEAM-ENGINEERING'}`): primera unidad organizativa persistente de Forjai, capa aparte sobre los `Agent` ya creados (nunca los crea, solo los enriquece) — Neo y Vera se reutilizan tal cual (mismos `Agent.id`, ningún nodo nuevo), y se suman los 3 agentes nuevos (`devops`/`backend`/`frontend-ui` → Diego/Iris/Mila). Los 5 miembros cuelgan del `Team` vía `MEMBER_OF`, Neo lidera vía `LEADS`; cada uno lleva además `Agent.roleCode` (id programático estable, p. ej. `CLOUD_ARCHITECT_LEAD_BACKEND`) y `Agent.capabilities` (tags de skill) — solo estos 5 agentes tienen `roleCode`/`capabilities`, no `ceo`/`sales`/`product`/`finance`. Stack de desarrollo compartido entre los 3 roles "builder" (arquitecto, backend, frontend/UI): C#, Java, JavaScript/TypeScript, Flutter; expertise AWS/SQL/NoSQL repartida entre el arquitecto (`engineering`) y el rol de base de datos/DevOps (`devops`). **Invariante dura**: crear/asegurar esta estructura (`ensureEngineeringTeam()`) nunca toca `Agent.status` ni crea ninguna `AgentTask` — es 100% organizacional, sin ninguna capacidad de ejecución nueva (eso sería un "Proyecto B" separado, todavía sin diseñar). Consultable desde el chat vía la consulta determinista `ENGINEERING_TEAM` (ver "Chat Intent Router" más abajo).
```
a:
```
**3 equipos reales** (`TeamMemoryService`, generaliza el servicio original de un solo equipo — ver `docs/superpowers/specs/2026-09-21-creative-marketing-teams-design.md`), capa aparte sobre los `Agent` ya creados (nunca los crea, solo los enriquece): `TEAM-ENGINEERING` (Neo/Vera/Diego/Iris/Mila, líder Neo vía `LEADS` — mismo contenido de roleCode/capabilities que la ronda anterior, sin cambios), `TEAM-CREATIVE-PRODUCT-INTELLIGENCE` (Kael/Maya/Gael, líder Kael) y `TEAM-MARKETING-GROWTH` (Kira/Nora, líder Kira) — exactamente estos 3, lista fija en código (`TeamMemoryService.TEAMS`), sin mecanismo para agregar un cuarto sin cambiar código. Cada miembro cuelga de su `Team` vía `MEMBER_OF` y lleva además `Agent.roleCode` (id programático estable, p. ej. `CLOUD_ARCHITECT_LEAD_BACKEND`, `INTERACTIVE_LOGIC_PRODUCT_DESIGNER`) y `Agent.capabilities` (tags de skill) — solo estos 10 agentes tienen `roleCode`/`capabilities`, no `ceo`/`sales`/`product`/`finance`. **Invariante dura**: crear/asegurar esta estructura (`ensureAllTeams()`) nunca toca `Agent.status` ni crea ninguna `AgentTask` — es 100% organizacional, sin ninguna capacidad de ejecución nueva (eso sería un "Proyecto B" separado, todavía sin diseñar). Consultable desde el chat vía la consulta determinista `TEAM_DETAILS` + `teamId` (ver "Chat Intent Router" más abajo).
```

- [ ] **Step 2: Actualizar la sección "Chat Intent Router" de `CLAUDE.md`**

Reemplazar la mención de `ENGINEERING_TEAM` dentro del punto 5 (`Consulta determinista`):

De:
```
`ENGINEERING_TEAM` (quiénes son los miembros del Engineering Team, quién lidera, rol/`roleCode`/capabilities/modelo real de cada uno — chequeada antes que `AGENT_STATUS`, porque "equipo" solo ya dispara ese keyword)
```
a:
```
`TEAM_DETAILS` (quiénes son los miembros de uno de los 3 equipos reales de Forjai — Engineering, Creative/Product Intelligence, Marketing & Growth —, quién lidera, rol/`roleCode`/capabilities/modelo real de cada uno; resuelto por keyword a un `teamId` concreto internamente, chequeada antes que `AGENT_STATUS` porque "equipo" solo ya dispara ese keyword; un `teamId` fuera de los 3 conocidos nunca llega a Neo4j)
```

- [ ] **Step 3: Actualizar la mención de "9 agentes" en la sección "LLM: Ollama" de `CLAUDE.md`**

Buscar, dentro de `### LLM: Ollama`, la frase `Cada uno de los 9 agentes (`ceo` + los 5 delegados + los 3 del Engineering Team) resuelve su propio modelo real y persistido`. Reemplazar:

De:
```
Cada uno de los 9 agentes (`ceo` + los 5 delegados + los 3 del Engineering Team) resuelve su propio modelo real y persistido, `Agent.model`, vía `CompanyMemoryService.agentModel(agentId, fallback)`
```
a:
```
Cada uno de los 14 agentes (`ceo` + los 5 delegados de misión + los 8 restantes repartidos en los 3 equipos organizativos) resuelve su propio modelo real y persistido, `Agent.model`, vía `CompanyMemoryService.agentModel(agentId, fallback)`
```

- [ ] **Step 4: Agregar entrada nueva a `docs/HISTORY.md`**

Leer las últimas 2-3 entradas de `docs/HISTORY.md` para copiar el formato exacto de encabezado usado (fecha + título), y agregar una entrada nueva al final con este contenido:

```markdown
## 2026-09-21 — Creative/Product Intelligence + Marketing & Growth: 5 agentes nuevos, TeamMemoryService genérico

Pedido del usuario: agregar Kael (Interactive Logic & Product Designer),
Maya (Visual & Asset Director), Gael (Telemetry & Analytics), Kira
(Growth, Content & Community) y Nora (Community Manager), organizados
en 2 equipos nuevos (Creative / Product Intelligence: Kael/Maya/Gael;
Marketing & Growth: Kira/Nora), reutilizando el modelo `Team`/
`MEMBER_OF`/`LEADS`/`roleCode`/`capabilities` ya implementado para
Engineering.

Decisión de diseño explícita del usuario (spec
`docs/superpowers/specs/2026-09-21-creative-marketing-teams-design.md`):
al pasar de 1 equipo a 3, generalizar `EngineeringTeamMemoryService`
en vez de triplicarlo — se renombra a `TeamMemoryService`,
parametrizado por una lista fija de 3 `TeamDefinition` (Engineering sin
cambios de contenido, más los 2 equipos nuevos). El usuario también
pidió explícitamente un mecanismo de consulta genérico en el chat
(`QueryIntent.TEAM_DETAILS`) en vez de un `QueryIntent` por equipo —
resuelto con un `teamId` cerrado (enum de los 3 ids conocidos, tanto en
el tool schema de Ollama como en la detección determinista por
keyword) codificado internamente como `"TEAM_DETAILS:" + teamId` para
no tener que cambiar la firma `Function<String, String>` que ya usaba
`ChatIntentRouter`/`CeoService.chat`.

Antes de esta ronda se detectó y resolvió una discrepancia real: el
`CLAUDE.md` vigente documentaba 9 agentes, pero el código de
`master` solo tenía 6 — el Engineering Team (Diego/Iris/Mila +
Team/MEMBER_OF/roleCode/capabilities/model) tenía spec y plan
**aprobados el 2026-09-20 pero nunca ejecutados**. Se encontró que ya
existía un worktree (`.claude/worktrees/engineering-team`) con las 6
tareas de ese plan completas, revisadas y hasta verificadas en vivo
contra Neo4j real, pendiente solo de merge — se mergeó a `master` (166
tests en verde antes y después del merge) antes de empezar el diseño
de esta ronda.

Sin verificación en vivo contra Neo4j real todavía para los 2 equipos
nuevos al momento de escribir este plan — pendiente de autorización
explícita del usuario (mismo Neo4j compartido de la máquina de
desarrollo, mismo criterio ya usado para Engineering).
```

- [ ] **Step 5: Compilar y correr la suite completa una última vez**

Run: `cd app && mvn test`
Expected: BUILD SUCCESS, 175 tests, todos en verde (este task no toca código, solo confirma que nada se rompió).

- [ ] **Step 6: Commit**

```bash
git add CLAUDE.md docs/HISTORY.md
git commit -m "Documentar Creative/Product Intelligence + Marketing & Growth en CLAUDE.md y docs/HISTORY.md"
```

---

## Después del plan (fuera de las tasks, requiere autorización del usuario)

**Verificación en vivo contra Neo4j real** (mismo criterio ya usado para Engineering Team, spec sección 7): arrancar el jar de esta rama en un puerto que no choque con ningún contenedor `company-core` ya corriendo, apuntando al Neo4j real compartido de la máquina, y confirmar por Cypher: 14 `Agent` totales (ninguno duplicado), 3 `Team` reales (`ACTIVE`), exactamente 5+3+2 relaciones `MEMBER_OF` (Engineering/Creative-PI/Marketing-Growth), 3 relaciones `LEADS` (Neo, Kael, Kira), los 14 agentes en `status='IDLE'` o su estado real previo sin alterar, `roleCode` seteado solo en los 10 agentes de equipo. Apagar el proceso limpiamente después y confirmar que ningún contenedor `company-core` preexistente quedó afectado. **No ejecutar esto sin pedirle autorización explícita al usuario primero** — toca infraestructura compartida con otros proyectos de la máquina.

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-09-21-creative-marketing-teams.md`. Two execution options:

1. **Subagent-Driven (recommended)** - dispatch a fresh subagent per task, review between tasks, fast iteration.
2. **Inline Execution** - execute tasks in this session using executing-plans, batch execution with checkpoints.
