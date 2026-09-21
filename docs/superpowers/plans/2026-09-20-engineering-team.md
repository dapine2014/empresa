# Engineering Team + modelo real por agente — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Forjai gana su primera unidad organizativa persistente (Engineering Team: 5 agentes, roles/capabilities/modelo reales, liderazgo real, consultable desde el chat) y cada agente pasa a tener un modelo LLM real y funcionalmente resuelto, en vez de un valor compartido fijo.

**Architecture:** `CompanyMemoryService` extiende su seed de agentes (3 nuevos + actualización de rol de 2 existentes + backfill de `model`). Nuevo `EngineeringTeamMemoryService` (Team + MEMBER_OF + LEADS + roleCode/capabilities, capa aparte sobre los `Agent` ya creados). `CeoService` deja de decidir qué modelo usar — recibe `model` explícito de cada llamador (`AgentRuntime`, `MissionExecutor`, `ChatIntentRouter`), que lo resuelven contra Neo4j vía `CompanyMemoryService.agentModel(...)`. Nueva consulta determinista `ENGINEERING_TEAM` en `ChatIntentRouter`, 100% Java, nunca Ollama.

**Tech Stack:** Java 21, Spring Boot 4.1.1, JUnit 5 + Mockito (sin Neo4j/Kafka/Ollama reales en la suite unitaria).

**Spec:** `docs/superpowers/specs/2026-09-20-engineering-team-design.md`

## Global Constraints

- Reutilizar `Agent {id:'engineering'}` (Neo) y `Agent {id:'qa'}` (Vera) — **nunca** crear nodos nuevos para ellos. Los 3 agentes nuevos usan los ids `devops`/`backend`/`frontend-ui`.
- Crear el Engineering Team **nunca** cambia `Agent.status` de un agente ya existente (Neo/Vera conservan su status real) ni crea ninguna `AgentTask`. Los 3 agentes nuevos nacen `status='IDLE'` (`ON CREATE`).
- `Agent.roleCode`/`Agent.capabilities` se persisten **solo** para los 5 miembros del Engineering Team — no para `ceo`/`sales`/`product`/`finance`.
- `Agent.model` se persiste y se resuelve de verdad para **los 9 agentes**, incluido `ceo` — `CeoService` deja de leer su propio `ceoModel`/`agentModel` inyectado; el llamador siempre pasa el `model` explícito.
- Backfill de `Agent.model` es idempotente (`coalesce(a.model, $default)`) — nunca pisa un valor ya seteado a mano, mismo patrón que la normalización existente de `Agent.status`.
- `PUT /api/company/agents/{id}/model` no valida contra qué modelos existen en Ollama — si el string no es válido, falla en la próxima llamada real a Ollama, no antes.
- La consulta `ENGINEERING_TEAM` del chat se resuelve **100% en Java, nunca pasa por Ollama** — mismo criterio que el resto de `ChatIntentRouter`.
- Tests estructurales de Neo4j (Team existe, 5 miembros, relación `LEADS`, status inicial `IDLE`) se verifican en vivo y se documentan — **no** se automatizan (mismo criterio ya acordado con el usuario y ya aplicado a toda la familia `*MemoryService` de este proyecto). Sí llevan test unitario real: los formatters del chat y el wiring de resolución de modelo.
- Sin cambios de frontend/Command Center en esta ronda.

---

## File Structure

- **Modify** `app/src/main/java/com/aicompany/core/service/CompanyMemoryService.java` — 3 agentes nuevos en el seed, rol actualizado de Neo/Vera, backfill de `model`, métodos `agentModel`/`setAgentModel`, constraint `team_id`.
- **Create** `app/src/main/java/com/aicompany/core/model/TeamMemberInfo.java`, `app/src/main/java/com/aicompany/core/model/EngineeringTeamSnapshot.java`.
- **Create** `app/src/main/java/com/aicompany/core/service/EngineeringTeamMemoryService.java`.
- **Modify** `app/src/main/java/com/aicompany/core/config/CompanyMemoryInitializer.java` — llama a `ensureEngineeringTeam()`.
- **Modify** `app/src/main/java/com/aicompany/core/service/CeoService.java` — `model` explícito en `chat`/`executeAgentTask`/`executeMission`, elimina `ceoModel`/`agentModel` del constructor.
- **Modify** `app/src/main/java/com/aicompany/core/agent/AgentRuntime.java` — resuelve y pasa el modelo real por agente.
- **Modify** `app/src/main/java/com/aicompany/core/service/MissionExecutor.java` — resuelve y pasa el modelo real del CEO.
- **Modify** `app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java` — resuelve modelo del CEO en sus 2 llamadas a `ceoService.chat`; nueva consulta `ENGINEERING_TEAM` + formatter.
- **Modify** `app/src/main/java/com/aicompany/core/controller/CompanyController.java` — nuevo endpoint `PUT /agents/{id}/model`.
- **Create** `app/src/main/java/com/aicompany/core/model/AgentModelCommand.java`.
- **Modify** tests: `CeoServiceChatHistoryTest.java`, `CeoServiceToolFormatGuardTest.java`, `AgentRuntimeTest.java`, `MissionExecutorTest.java`, `ChatIntentRouterTest.java`, `CompanyControllerTest.java`.

---

### Task 1: `CompanyMemoryService` — 3 agentes nuevos, rol actualizado, `model` real

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/CompanyMemoryService.java`

**Interfaces:**
- Produces: `CompanyMemoryService.agentModel(String agentId, String fallback) -> String`; `CompanyMemoryService.setAgentModel(String agentId, String model) -> void` — consumidos por Tasks 4 y 5.

- [ ] **Step 1: Agregar el constraint `team_id` al esquema**

En `initializeSchema()`, agregar junto a los demás constraints del "núcleo ya en uso" (después de `evidence_id`, antes del bloque de "ampliación de memoria"):

```java
            session.run("CREATE CONSTRAINT team_id IF NOT EXISTS FOR (t:Team) REQUIRE t.id IS UNIQUE").consume();
```

- [ ] **Step 2: Agregar `@Value` para los modelos default y actualizar el constructor**

Agregar el import:

```java
import org.springframework.beans.factory.annotation.Value;
```

Reemplazar el constructor y los campos:

```java
public class CompanyMemoryService {
    private final Driver driver;
    private final String defaultCeoModel;
    private final String defaultAgentModel;

    public CompanyMemoryService(
            Driver driver,
            @Value("${ollama.ceo-model}") String defaultCeoModel,
            @Value("${ollama.agent-model}") String defaultAgentModel) {

        this.driver = driver;
        this.defaultCeoModel = defaultCeoModel;
        this.defaultAgentModel = defaultAgentModel;
    }
```

- [ ] **Step 3: Extender el seed de agentes — 3 nuevos, rol actualizado de Neo/Vera, `model` real**

Reemplazar el bloque `initializeCompanyAndAgents()` completo:

```java
    private void initializeCompanyAndAgents() {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MERGE (c:Company {id:'AI-COMPANY'}) "
                        + "ON CREATE SET c.alertEmail='dapine@gmail.com', c.systemEmail='', c.mailPassword='' "
                        + "SET c.name='Forjai', c.status='ACTIVE', c.seedCapitalUsd=50.0, c.challengeDays=60");
                var agents = List.of(
                        new String[]{"ceo", "Alex", "Chief Executive Officer AI", "estratégico, crítico", defaultCeoModel},
                        new String[]{"sales", "Sofia", "Director of Sales AI", "persuasiva, orientada a resultados", defaultAgentModel},
                        new String[]{"product", "Luna", "Chief Product AI", "creativa, centrada en el usuario", defaultAgentModel},
                        new String[]{"finance", "Max", "Chief Finance AI", "analítico, conservador", defaultAgentModel},
                        new String[]{"engineering", "Neo", "Cloud Architect & Lead Backend", "pragmático, meticuloso", defaultAgentModel},
                        new String[]{"qa", "Vera", "QA & Cloud Performance Engineer", "escéptica, detallista", defaultAgentModel},
                        new String[]{"devops", "Diego", "Cloud Database & SRE / DevOps", "meticuloso, orientado a la estabilidad", defaultAgentModel},
                        new String[]{"backend", "Iris", "Dev Backend & Integrations", "riguroso, pragmático", defaultAgentModel},
                        new String[]{"frontend-ui", "Mila", "Frontend & Game UI Specialist", "creativa, atenta al detalle visual", defaultAgentModel}
                );
                for (var agent : agents) {
                    // ON CREATE, no SET incondicional de un valor fijo:
                    // a.status lo va actualizando AgentRuntime en cada
                    // transición real (WORKING/IDLE) -- un restart del
                    // proceso no debe pisarlo. La normalización final SÍ
                    // es incondicional pero idempotente sobre el valor ya
                    // real: preserva WORKING/IDLE tal cual si ya es uno
                    // de esos dos, y solo corrige cualquier otra cosa
                    // (p. ej. 'ACTIVE', el valor fijo que este seed
                    // escribía antes de este cambio, todavía presente en
                    // los 6 nodos reales de sesiones anteriores) a IDLE.
                    // a.model sigue el mismo criterio de "backfill sin
                    // pisar": coalesce preserva un valor ya seteado a
                    // mano (p. ej. vía PUT /agents/{id}/model), y solo
                    // lo completa la primera vez.
                    tx.run("MERGE (a:Agent {id:$id}) "
                                    + "ON CREATE SET a.status='IDLE' "
                                    + "SET a.name=$name, a.role=$role, a.personality=$personality, "
                                    + "a.status = CASE WHEN a.status IN ['WORKING','IDLE'] THEN a.status ELSE 'IDLE' END, "
                                    + "a.model = coalesce(a.model, $defaultModel) "
                                    + "REMOVE a.title",
                            Map.of("id", agent[0], "name", agent[1], "role", agent[2],
                                    "personality", agent[3], "defaultModel", agent[4]));
                }
                tx.run("MATCH (c:Company {id:'AI-COMPANY'}), (a:Agent) MERGE (a)-[:WORKS_FOR]->(c)");
                tx.run("MATCH (c:Company {id:'AI-COMPANY'}), (ceo:Agent {id:'ceo'}) MERGE (c)-[:HAS_CEO]->(ceo)");
                return null;
            });
        }
    }
```

- [ ] **Step 4: Agregar `agentModel`/`setAgentModel`**

Agregar estos métodos nuevos (por ejemplo, después de `teamRosterDescription()`):

```java
    /**
     * Modelo LLM real que este agente debe usar en su próxima llamada a
     * Ollama — {@code AgentRuntime}/{@code MissionExecutor}/
     * {@code ChatIntentRouter} lo resuelven antes de cada llamada, nunca
     * {@code CeoService} (que solo ejecuta la llamada que se le pide, sin
     * decidir qué modelo usar). {@code fallback} cubre el caso defensivo
     * de un agente sin backfill todavía (no debería pasar en la práctica:
     * {@link #initializeCompanyAndAgents()} lo completa para los 9
     * agentes conocidos al arrancar).
     */
    public String agentModel(String agentId, String fallback) {
        try (var session = driver.session()) {
            return session.run(
                            "MATCH (a:Agent {id:$id}) RETURN coalesce(a.model, $fallback) AS model",
                            Map.of("id", agentId, "fallback", fallback))
                    .list(r -> r.get("model").asString())
                    .stream().findFirst()
                    .orElse(fallback);
        }
    }

    /**
     * Cambia el modelo real de un agente puntual (vía
     * {@code PUT /api/company/agents/{id}/model}) — toma efecto en la
     * próxima tarea/llamada de ese agente, sin caché ni reinicio.
     */
    public void setAgentModel(String agentId, String model) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH (a:Agent {id:$id}) SET a.model=$model",
                        Map.of("id", agentId, "model", model));
                return null;
            });
        }
    }
```

- [ ] **Step 5: Compilar y correr la suite completa**

Run: `cd app && mvn test`
Expected: BUILD SUCCESS. (`CompanyMemoryService` no tiene test directo — mismo criterio que toda la familia `*MemoryService` — este cambio no debería romper nada existente porque ningún test instancia `CompanyMemoryService` directamente hoy.)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/CompanyMemoryService.java
git commit -m "Agregar 3 agentes nuevos del Engineering Team, actualizar rol de Neo/Vera, y modelo real por agente (backfill + resolución)"
```

---

### Task 2: `EngineeringTeamMemoryService` + registros nuevos

**Files:**
- Create: `app/src/main/java/com/aicompany/core/model/TeamMemberInfo.java`
- Create: `app/src/main/java/com/aicompany/core/model/EngineeringTeamSnapshot.java`
- Create: `app/src/main/java/com/aicompany/core/service/EngineeringTeamMemoryService.java`
- Modify: `app/src/main/java/com/aicompany/core/config/CompanyMemoryInitializer.java`

**Interfaces:**
- Consumes: nada de tasks anteriores directamente (depende de que los 5 `Agent` ya existan en Neo4j al momento de correr, garantizado por el orden de llamada en `CompanyMemoryInitializer`, no por una dependencia de compilación).
- Produces: `EngineeringTeamMemoryService.snapshot() -> EngineeringTeamSnapshot` — consumido por Task 6.

- [ ] **Step 1: Crear los registros**

```java
package com.aicompany.core.model;

import java.util.List;

public record TeamMemberInfo(
        String agentId,
        String name,
        String role,
        String roleCode,
        List<String> capabilities,
        String model
) {
}
```

```java
package com.aicompany.core.model;

import java.util.List;

public record EngineeringTeamSnapshot(
        String teamId,
        String teamName,
        String status,
        String leaderAgentId,
        List<TeamMemberInfo> members
) {
}
```

- [ ] **Step 2: Crear `EngineeringTeamMemoryService`**

```java
package com.aicompany.core.service;

import com.aicompany.core.model.EngineeringTeamSnapshot;
import com.aicompany.core.model.TeamMemberInfo;
import org.neo4j.driver.Driver;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Primera unidad organizativa persistente de Forjai — una capa aparte
 * sobre los {@code Agent} ya creados por {@link CompanyMemoryService}
 * (no duplica esa lógica de identidad, solo agrega la estructura de
 * equipo: {@code Team}, membresía, liderazgo, y los campos específicos
 * de rol de ingeniería). Ver
 * docs/superpowers/specs/2026-09-20-engineering-team-design.md.
 *
 * <p>Crear el equipo nunca cambia {@code Agent.status} ni crea ninguna
 * {@code AgentTask} — es estructura organizativa, no ejecución (esa
 * distinción es una regla dura del spec).
 */
@Service
public class EngineeringTeamMemoryService {

    private static final String TEAM_ID = "TEAM-ENGINEERING";
    private static final String LEADER_AGENT_ID = "engineering";

    private record RoleDefinition(
            String agentId,
            String roleCode,
            List<String> capabilities) {
    }

    private static final List<RoleDefinition> ROLES = List.of(
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
    );

    private final Driver driver;

    public EngineeringTeamMemoryService(Driver driver) {
        this.driver = driver;
    }

    /**
     * Idempotente: {@code MERGE} del {@code Team}, {@code SET} de
     * {@code roleCode}/{@code capabilities} sobre cada {@code Agent} ya
     * existente (nunca {@code MERGE} de Agent -- esos ya los crea
     * {@link CompanyMemoryService#initializeCompanyAndAgents()}), y
     * {@code MERGE} de {@code MEMBER_OF}/{@code LEADS}. Llamado desde
     * {@code CompanyMemoryInitializer} después de que los agentes ya
     * existan.
     */
    public void ensureEngineeringTeam() {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {

                tx.run("MERGE (t:Team {id:$id}) SET t.name=$name, t.type=$type, t.status=$status",
                        Map.of("id", TEAM_ID, "name", "Engineering Team",
                                "type", "ENGINEERING", "status", "ACTIVE"));

                for (var role : ROLES) {
                    tx.run("MATCH (a:Agent {id:$agentId}), (t:Team {id:$teamId}) "
                                    + "SET a.roleCode=$roleCode, a.capabilities=$capabilities "
                                    + "MERGE (a)-[:MEMBER_OF]->(t)",
                            Map.of("agentId", role.agentId(), "teamId", TEAM_ID,
                                    "roleCode", role.roleCode(), "capabilities", role.capabilities()));
                }

                tx.run("MATCH (a:Agent {id:$leaderId}), (t:Team {id:$teamId}) MERGE (a)-[:LEADS]->(t)",
                        Map.of("leaderId", LEADER_AGENT_ID, "teamId", TEAM_ID));

                return null;
            });
        }
    }

    /**
     * Lectura real para el Company Chat — nunca inventa un miembro, rol,
     * capability o líder que no esté en Company Memory. Devuelve
     * {@code members} vacío (nunca {@code null}) si el Team todavía no
     * existe (defensivo; en la práctica {@link #ensureEngineeringTeam()}
     * ya corrió al arrancar).
     */
    public EngineeringTeamSnapshot snapshot() {
        try (var session = driver.session()) {

            var rows = session.run(
                    "MATCH (a:Agent)-[:MEMBER_OF]->(t:Team {id:$teamId}) "
                            + "OPTIONAL MATCH (a)-[leads:LEADS]->(t) "
                            + "RETURN t.id AS teamId, t.name AS teamName, t.status AS teamStatus, "
                            + "a.id AS agentId, a.name AS name, a.role AS role, a.roleCode AS roleCode, "
                            + "a.capabilities AS capabilities, a.model AS model, "
                            + "leads IS NOT NULL AS isLeader "
                            + "ORDER BY a.id",
                    Map.of("teamId", TEAM_ID)
            ).list();

            if (rows.isEmpty()) {
                return new EngineeringTeamSnapshot(TEAM_ID, null, null, null, List.of());
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

            return new EngineeringTeamSnapshot(
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

- [ ] **Step 3: Llamar a `ensureEngineeringTeam()` desde `CompanyMemoryInitializer`**

Reemplazar el archivo completo:

```java
package com.aicompany.core.config;

import com.aicompany.core.service.CompanyMemoryService;
import com.aicompany.core.service.EngineeringTeamMemoryService;
import com.aicompany.core.service.MissionMemoryService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class CompanyMemoryInitializer {
    private final CompanyMemoryService memory;
    private final MissionMemoryService missionMemory;
    private final EngineeringTeamMemoryService engineeringTeamMemory;

    public CompanyMemoryInitializer(
            CompanyMemoryService memory,
            MissionMemoryService missionMemory,
            EngineeringTeamMemoryService engineeringTeamMemory) {

        this.memory = memory;
        this.missionMemory = missionMemory;
        this.engineeringTeamMemory = engineeringTeamMemory;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeAfterReady() {
        memory.initialize();
        missionMemory.backfillMissionEnvironment();
        engineeringTeamMemory.ensureEngineeringTeam();
    }
}
```

(`ensureEngineeringTeam()` corre **después** de `memory.initialize()` a propósito — depende de que los 9 `Agent` ya existan.)

- [ ] **Step 4: Compilar y correr la suite completa**

Run: `cd app && mvn test`
Expected: BUILD SUCCESS. (Ni `EngineeringTeamMemoryService` ni `CompanyMemoryInitializer` tienen test directo — integración Neo4j / bootstrap de Spring, mismo criterio ya acordado.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/model/TeamMemberInfo.java \
        app/src/main/java/com/aicompany/core/model/EngineeringTeamSnapshot.java \
        app/src/main/java/com/aicompany/core/service/EngineeringTeamMemoryService.java \
        app/src/main/java/com/aicompany/core/config/CompanyMemoryInitializer.java
git commit -m "Agregar EngineeringTeamMemoryService: Team, roleCode/capabilities, MEMBER_OF/LEADS"
```

---

### Task 3: `CeoService` — `model` explícito, elimina el modelo fijo por rol

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/CeoService.java`
- Modify: `app/src/test/java/com/aicompany/core/service/CeoServiceChatHistoryTest.java`
- Modify: `app/src/test/java/com/aicompany/core/service/CeoServiceToolFormatGuardTest.java`

**Interfaces:**
- Produces: `CeoService.chat(String ceoName, String teamRoster, List<ConversationTurn> history, String message, Function<String,String> companyMemoryQuery, String model) -> String`; `CeoService.executeAgentTask(String agentId, String prompt, String taskSummary, String missionId, String taskId, String model) -> AgentTaskOutcome`; `CeoService.executeMission(String instruction, String agentResults, String model) -> String` — consumidos por Task 4.

- [ ] **Step 1: Actualizar las dos pruebas que instancian `CeoService` directamente (fallan por compilación hasta el Step 2)**

En `CeoServiceChatHistoryTest.java`, reemplazar la instanciación:

```java
    private final CeoService ceoService = new CeoService(
            mock(RestClient.class),
            JsonMapper.builder().build(),
            mock(EvidenceAcquisitionService.class),
            mock(CompanyEventPublisher.class),
            new SimpleMeterRegistry()
    );
```

En `CeoServiceToolFormatGuardTest.java`, el mismo cambio (quitar los dos strings de modelo):

```java
    private final CeoService ceoService = new CeoService(
            mock(RestClient.class),
            JsonMapper.builder().build(),
            mock(EvidenceAcquisitionService.class),
            mock(CompanyEventPublisher.class),
            new SimpleMeterRegistry()
    );
```

- [ ] **Step 2: Ejecutar los tests para confirmar que fallan (compilación)**

Run: `cd app && mvn test -Dtest=CeoServiceChatHistoryTest,CeoServiceToolFormatGuardTest`
Expected: FAIL (compilación — el constructor de `CeoService` todavía tiene 7 parámetros)

- [ ] **Step 3: Quitar `ceoModel`/`agentModel` del constructor de `CeoService`**

Reemplazar el bloque de campos y constructor:

```java
    private final RestClient ollama;
    private final JsonMapper jsonMapper;
    private final EvidenceAcquisitionService evidenceAcquisitionService;
    private final CompanyEventPublisher events;
    private final MeterRegistry meterRegistry;

    public CeoService(
            RestClient ollama,
            JsonMapper jsonMapper,
            EvidenceAcquisitionService evidenceAcquisitionService,
            CompanyEventPublisher events,
            MeterRegistry meterRegistry) {

        this.ollama = ollama;
        this.jsonMapper = jsonMapper;
        this.evidenceAcquisitionService = evidenceAcquisitionService;
        this.events = events;
        this.meterRegistry = meterRegistry;
    }
```

- [ ] **Step 4: `chat()` recibe `model` explícito**

Reemplazar la firma y las dos llamadas a `callModel` dentro de `chat(...)`:

```java
    public String chat(
            String ceoName,
            String teamRoster,
            List<ConversationTurn> history,
            String message,
            Function<String, String> companyMemoryQuery,
            String model) {
```

(el cuerpo del método no cambia salvo reemplazar `ceoModel` por `model` en las dos llamadas a `callModel("CEO_CHAT", "ceo", ceoModel, ...)` → `callModel("CEO_CHAT", "ceo", model, ...)`, una antes y otra después del bloque de tool-call).

- [ ] **Step 5: `executeAgentTask()` recibe `model` explícito**

Reemplazar la firma:

```java
    public AgentTaskOutcome executeAgentTask(
            String agentId,
            String prompt,
            String taskSummary,
            String missionId,
            String taskId,
            String model) {
```

Reemplazar las dos llamadas a `callModel` que usaban `agentModel`:

```java
        var toolTurn =
                callModel(
                        "AGENT_TOOL_CALL",
                        agentId,
                        model,
                        toolDecisionMessages,
                        null,
                        AGENT_TOOLS,
                        true
                );
```

```java
        var finalTurn =
                callModel(
                        "AGENT_TASK",
                        agentId,
                        model,
                        messages,
                        AgentResultSchema.SCHEMA,
                        null,
                        false
                );
```

- [ ] **Step 6: `executeMission()` recibe `model` explícito**

Reemplazar la firma y la llamada a `callModel`:

```java
    public String executeMission(
            String instruction,
            String agentResults,
            String model) {

        var prompt = """
                Actúa como CEO de Forjai.
                Consolida los resultados de los agentes y determina el siguiente paso.
                No conviertas hipótesis en hechos.
                Si no existe evidencia real de mercado,
                declara que la misión todavía no está validada.

                INSTRUCCIÓN:
                %s

                RESULTADOS DE AGENTES:
                %s
                """.formatted(
                instruction,
                agentResults
        );

        var messages = List.<Map<String, Object>>of(
                Map.of("role", "system", "content", systemPrompt()),
                Map.of("role", "user", "content", prompt)
        );

        return callModel(
                "MISSION_CONSOLIDATION", "ceo", model, messages, null, null
        ).content();
    }
```

- [ ] **Step 7: Ejecutar los tests y confirmar que pasan**

Run: `cd app && mvn test -Dtest=CeoServiceChatHistoryTest,CeoServiceToolFormatGuardTest`
Expected: PASS (estos dos archivos no llaman a `chat`/`executeAgentTask`/`executeMission` directamente, solo instancian `CeoService` y usan `buildHistoryMessages`/`rejectFormatCombinedWithTools` — deben compilar y pasar sin más cambios)

- [ ] **Step 8: Confirmar que el resto del proyecto NO compila todavía (esperado — Task 4 arregla los call-sites)**

Run: `cd app && mvn test-compile`
Expected: FAIL — `AgentRuntime`, `MissionExecutor` y `ChatIntentRouter` todavía llaman a `chat`/`executeAgentTask`/`executeMission` con la firma vieja (5/4 args en vez de 6/5/3). Esto es esperado en este punto del plan; Task 4 lo corrige. **No** hacer commit de este estado roto — combinar el commit de este task con el de Task 4, o commitear ahora y dejar Task 4 como el siguiente commit inmediato sin correr la suite completa hasta que ambos estén aplicados. Este plan usa la segunda opción: commitear este task ahora (el build roto es visible y esperado hasta Task 4) y confiar en que Task 4 se ejecuta inmediatamente después, en la misma sesión de subagent-driven-development.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/CeoService.java \
        app/src/test/java/com/aicompany/core/service/CeoServiceChatHistoryTest.java \
        app/src/test/java/com/aicompany/core/service/CeoServiceToolFormatGuardTest.java
git commit -m "CeoService: recibir el modelo LLM explícito del llamador, dejar de decidirlo internamente (build roto hasta Task 4, esperado)"
```

---

### Task 4: Resolver y pasar el modelo real — `AgentRuntime`, `MissionExecutor`, `ChatIntentRouter`

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/agent/AgentRuntime.java`
- Modify: `app/src/main/java/com/aicompany/core/service/MissionExecutor.java`
- Modify: `app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java`
- Modify: `app/src/test/java/com/aicompany/core/agent/AgentRuntimeTest.java`
- Modify: `app/src/test/java/com/aicompany/core/service/MissionExecutorTest.java`
- Modify: `app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java`

**Interfaces:**
- Consumes: `CompanyMemoryService.agentModel(String, String) -> String` (Task 1); `CeoService.chat(..., String model)`/`executeAgentTask(..., String model)`/`executeMission(..., String model)` (Task 3).

- [ ] **Step 1: `AgentRuntime` — inyectar `CompanyMemoryService`, resolver el modelo antes del bucle de reintento**

Agregar el import y el campo/parámetro de constructor:

```java
import com.aicompany.core.service.CompanyMemoryService;
import org.springframework.beans.factory.annotation.Value;
```

Reemplazar el bloque de campos y constructor:

```java
    private final CeoService ceoService;
    private final MissionMemoryService memory;
    private final CompanyMemoryService companyMemory;
    private final String defaultAgentModel;
    private final Executor agentTaskExecutor;
    private final CompanyEventPublisher events;
    private final AgentResultValidator validator;
    private final EvidenceValidationGate evidenceGate;
    private final EvidenceBindingGate evidenceBindingGate;
    private final JsonMapper jsonMapper;

    public AgentRuntime(
            CeoService ceoService,
            MissionMemoryService memory,
            CompanyMemoryService companyMemory,
            @Value("${ollama.agent-model}") String defaultAgentModel,
            @Qualifier("agentTaskExecutor") Executor agentTaskExecutor,
            CompanyEventPublisher events,
            AgentResultValidator validator,
            EvidenceValidationGate evidenceGate,
            EvidenceBindingGate evidenceBindingGate,
            JsonMapper jsonMapper) {

        this.ceoService = ceoService;
        this.memory = memory;
        this.companyMemory = companyMemory;
        this.defaultAgentModel = defaultAgentModel;
        this.agentTaskExecutor = agentTaskExecutor;
        this.events = events;
        this.validator = validator;
        this.evidenceGate = evidenceGate;
        this.evidenceBindingGate = evidenceBindingGate;
        this.jsonMapper = jsonMapper;
    }
```

En `executeInternal(...)`, agregar la resolución del modelo justo después de `memory.setAgentStatus(agentId, "WORKING");` y antes del `try {` que envuelve el bucle de reintento:

```java
        memory.setAgentStatus(agentId, "WORKING");

        var model = companyMemory.agentModel(agentId, defaultAgentModel);

        events.publishTask(
```

Y dentro del bucle, reemplazar la llamada a `ceoService.executeAgentTask(...)` para incluir `model`:

```java
                    var outcome =
                            ceoService.executeAgentTask(
                                    agentId,
                                    prompt,
                                    taskSummary,
                                    missionId,
                                    taskId,
                                    model
                            );
```

- [ ] **Step 2: `MissionExecutor` — inyectar `CompanyMemoryService`, resolver el modelo del CEO**

Agregar el import:

```java
import org.springframework.beans.factory.annotation.Value;
```

Reemplazar el bloque de campos y constructor:

```java
    private final MissionMemoryService memory;
    private final AgentRuntime runtime;
    private final CeoService ceoService;
    private final CompanyMemoryService companyMemory;
    private final String defaultCeoModel;
    private final Executor orchestratorExecutor;
    private final CompanyEventPublisher events;
    private final JsonMapper jsonMapper;
    private final ContradictionDetector contradictionDetector;
    private final AppProperties appProperties;
    private final OpportunityMemoryService opportunityMemory;
    private final AlertMailService alertMailService;

    public MissionExecutor(
            MissionMemoryService memory,
            AgentRuntime runtime,
            CeoService ceoService,
            CompanyMemoryService companyMemory,
            @Value("${ollama.ceo-model}") String defaultCeoModel,
            @Qualifier("missionOrchestratorExecutor")
            Executor orchestratorExecutor,
            CompanyEventPublisher events,
            JsonMapper jsonMapper,
            ContradictionDetector contradictionDetector,
            AppProperties appProperties,
            OpportunityMemoryService opportunityMemory,
            AlertMailService alertMailService) {

        this.memory = memory;
        this.runtime = runtime;
        this.ceoService = ceoService;
        this.companyMemory = companyMemory;
        this.defaultCeoModel = defaultCeoModel;
        this.orchestratorExecutor = orchestratorExecutor;
        this.events = events;
        this.jsonMapper = jsonMapper;
        this.contradictionDetector = contradictionDetector;
        this.appProperties = appProperties;
        this.opportunityMemory = opportunityMemory;
        this.alertMailService = alertMailService;
    }
```

Reemplazar la llamada a `ceoService.executeMission(...)`:

```java
            var finalResult =
                    ceoService.executeMission(
                            instruction,
                            resultsForCeo,
                            companyMemory.agentModel("ceo", defaultCeoModel)
                    );
```

- [ ] **Step 3: `ChatIntentRouter` — resolver el modelo del CEO en sus dos llamadas a `ceoService.chat`**

Agregar el import y el campo/parámetro de constructor (`ChatIntentRouter` ya depende de `CompanyMemoryService` vía el campo `companyMemory` existente — solo hace falta el default):

```java
import org.springframework.beans.factory.annotation.Value;
```

Agregar el campo y el parámetro de constructor (al final, después de `productStatusService`):

```java
    private final ProductStatusService productStatusService;
    private final String defaultCeoModel;

    public ChatIntentRouter(
            MissionService missionService,
            CeoService ceoService,
            MissionMemoryService missionMemory,
            OpportunityMemoryService opportunityMemory,
            CustomerMemoryService customerMemory,
            CompanyMemoryService companyMemory,
            ConversationMemoryService conversationMemory,
            AppProperties appProperties,
            ProductStatusService productStatusService,
            @Value("${ollama.ceo-model}") String defaultCeoModel) {

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
    }
```

Reemplazar las dos llamadas a `ceoService.chat(...)` para incluir el modelo resuelto:

```java
        return ceoService.chat(
                companyMemory.agentName("ceo").orElse("CEO"),
                companyMemory.teamRosterDescription(),
                conversationMemory.recentMessages(HISTORY_LIMIT),
                message,
                this::answerMemoryTopic,
                companyMemory.agentModel("ceo", defaultCeoModel)
        );
```

(esta es la del fallback general de `resolve()`; la segunda, dentro de `handleReference` cuando el predicado no matchea, se edita igual):

```java
            return ceoService.chat(
                    companyMemory.agentName("ceo").orElse("CEO"),
                    companyMemory.teamRosterDescription(),
                    conversationMemory.recentMessages(HISTORY_LIMIT),
                    hint + message,
                    this::answerMemoryTopic,
                    companyMemory.agentModel("ceo", defaultCeoModel)
            );
```

- [ ] **Step 4: Actualizar `AgentRuntimeTest` — constructor + 18 call-sites de `executeAgentTask` + nuevo test de wiring**

Reemplazar el bloque de setup:

```java
class AgentRuntimeTest {

    private final MissionMemoryService memory = mock(MissionMemoryService.class);
    private final CompanyMemoryService companyMemory = mock(CompanyMemoryService.class);
    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    private final CeoService ceoService = mock(CeoService.class);
    private final AgentResultValidator validator = mock(AgentResultValidator.class);
    private final EvidenceValidationGate evidenceGate = mock(EvidenceValidationGate.class);
    private final EvidenceBindingGate evidenceBindingGate = new EvidenceBindingGate();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private final AgentRuntime runtime = new AgentRuntime(
            ceoService, memory, companyMemory, "qwen3:8b", Runnable::run, events,
            validator, evidenceGate, evidenceBindingGate, jsonMapper
    );
```

(agregar el import `com.aicompany.core.service.CompanyMemoryService;` junto a los demás).

En **todos** los 18 call-sites existentes de `ceoService.executeAgentTask(eq("..."), anyString(), anyString(), eq("MISSION-1"), eq("TASK-1"))` (tanto en `when(...)` como en `verify(...)`), agregar `, anyString()` como sexto argumento — por ejemplo:

```java
        when(ceoService.executeAgentTask(eq("finance"), anyString(), anyString(), eq("MISSION-1"), eq("TASK-1"), anyString()))
```

```java
        verify(ceoService, times(1)).executeAgentTask(eq("finance"), anyString(), anyString(), eq("MISSION-1"), eq("TASK-1"), anyString());
```

(mismo patrón para las 18 ocurrencias — agregar `anyString()` al final de cada lista de argumentos de `executeAgentTask`, sin tocar nada más de esas líneas).

Agregar este test nuevo (por ejemplo, al final de la clase), que prueba de verdad el wiring del modelo por agente:

```java
    @Test
    void resolvesADifferentRealModelPerAgent() throws Exception {

        when(companyMemory.agentModel("finance", "qwen3:8b")).thenReturn("llama3:8b");
        when(companyMemory.agentModel("sales", "qwen3:8b")).thenReturn("qwen3:8b");

        when(ceoService.executeAgentTask(eq("finance"), anyString(), anyString(), eq("MISSION-1"), eq("TASK-1"), eq("llama3:8b")))
                .thenReturn(new AgentTaskOutcome(agentResult("finance", "ok finance"), List.of()));
        when(ceoService.executeAgentTask(eq("sales"), anyString(), anyString(), eq("MISSION-1"), eq("TASK-1"), eq("qwen3:8b")))
                .thenReturn(new AgentTaskOutcome(agentResult("sales", "ok sales"), List.of()));

        runtime.execute("TASK-1", "MISSION-1", "finance", "UNIT_ECONOMICS", "instrucción").get();
        runtime.execute("TASK-1", "MISSION-1", "sales", "MARKET_DISCOVERY", "instrucción").get();

        verify(ceoService).executeAgentTask(eq("finance"), anyString(), anyString(), eq("MISSION-1"), eq("TASK-1"), eq("llama3:8b"));
        verify(ceoService).executeAgentTask(eq("sales"), anyString(), anyString(), eq("MISSION-1"), eq("TASK-1"), eq("qwen3:8b"));
    }
```

(usa el helper `private AgentResult agentResult(String agent, String recommendation)` ya existente en este archivo de test, línea 274 al momento de escribir este plan).

- [ ] **Step 5: Ejecutar `AgentRuntimeTest` y confirmar que pasa**

Run: `cd app && mvn test -Dtest=AgentRuntimeTest`
Expected: PASS (10 tests existentes + 1 nuevo)

- [ ] **Step 6: Actualizar `MissionExecutorTest` — constructor + 10 call-sites de `executeMission`**

Reemplazar el bloque de setup:

```java
class MissionExecutorTest {

    private final MissionMemoryService memory = mock(MissionMemoryService.class);
    private final AgentRuntime runtime = mock(AgentRuntime.class);
    private final CeoService ceoService = mock(CeoService.class);
    private final CompanyMemoryService companyMemory = mock(CompanyMemoryService.class);
    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final ContradictionDetector contradictionDetector = mock(ContradictionDetector.class);
    private final AppProperties appProperties = new AppProperties("Forjai", 50.0, 60);
    private final OpportunityMemoryService opportunityMemory = mock(OpportunityMemoryService.class);
    private final AlertMailService alertMailService = mock(AlertMailService.class);

    private final MissionExecutor executor = new MissionExecutor(
            memory, runtime, ceoService, companyMemory, "qwen2.5-coder:14b", Runnable::run, events, jsonMapper,
            contradictionDetector, appProperties, opportunityMemory, alertMailService
    );
```

(`MissionExecutorTest` está en el paquete `com.aicompany.core.service`, igual que `CompanyMemoryService` — no hace falta ningún import nuevo para el mock).

En los 10 call-sites existentes de `ceoService.executeMission(anyString(), ...)` (`when`/`verify`, con `anyString()` o `resultsCaptor.capture()` como segundo argumento), agregar `, anyString()` como tercer argumento — por ejemplo:

```java
        when(ceoService.executeMission(anyString(), resultsCaptor.capture(), anyString()))
```

```java
        verify(ceoService, times(1)).executeMission(anyString(), anyString(), anyString());
```

```java
        verify(ceoService, never()).executeMission(anyString(), anyString(), anyString());
```

(mismo patrón para las 10 ocurrencias).

- [ ] **Step 7: Ejecutar `MissionExecutorTest` y confirmar que pasa**

Run: `cd app && mvn test -Dtest=MissionExecutorTest`
Expected: PASS (8 tests existentes, sin nuevos en este step)

- [ ] **Step 8: Actualizar `ChatIntentRouterTest` — constructor + 8 call-sites de `ceoService.chat`**

Reemplazar el bloque de mocks y constructor:

```java
    private final ProductStatusService productStatusService = mock(ProductStatusService.class);

    private final ChatIntentRouter router = new ChatIntentRouter(
            missionService, ceoService, missionMemory, opportunityMemory, customerMemory, companyMemory,
            conversationMemory, appProperties, productStatusService, "qwen2.5-coder:14b"
    );
```

En los 8 call-sites existentes (`when(ceoService.chat(...))`/`verify(ceoService).chat(...)`), agregar `, any()` como sexto argumento — por ejemplo:

```java
        when(ceoService.chat(anyString(), anyString(), any(), anyString(), any(), any())).thenReturn("¿A qué misión te referís?");
```

```java
        verify(ceoService).chat(anyString(), anyString(), any(), anyString(), captor.capture(), any());
```

(mismo patrón para las 8 ocurrencias — el orden de argumentos de `chat` es `ceoName, teamRoster, history, message, companyMemoryQuery, model`, así que el nuevo `any()` siempre va al final).

- [ ] **Step 9: Ejecutar la suite completa y confirmar que pasa**

Run: `cd app && mvn test`
Expected: PASS — 161 tests previos + 1 nuevo de `AgentRuntimeTest` = 162, sin regresiones.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/aicompany/core/agent/AgentRuntime.java \
        app/src/main/java/com/aicompany/core/service/MissionExecutor.java \
        app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java \
        app/src/test/java/com/aicompany/core/agent/AgentRuntimeTest.java \
        app/src/test/java/com/aicompany/core/service/MissionExecutorTest.java \
        app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java
git commit -m "Resolver y pasar el modelo LLM real por agente en AgentRuntime/MissionExecutor/ChatIntentRouter"
```

---

### Task 5: Endpoint `PUT /api/company/agents/{id}/model`

**Files:**
- Create: `app/src/main/java/com/aicompany/core/model/AgentModelCommand.java`
- Modify: `app/src/main/java/com/aicompany/core/controller/CompanyController.java`
- Modify: `app/src/test/java/com/aicompany/core/controller/CompanyControllerTest.java`

**Interfaces:**
- Consumes: `CompanyMemoryService.setAgentModel(String, String)` (Task 1).

- [ ] **Step 1: Escribir el test nuevo (falla: el endpoint no existe todavía)**

Agregar este test a `CompanyControllerTest.java` (por ejemplo, al final de la clase):

```java
    @Test
    void updateAgentModelDelegatesToCompanyMemoryService() {

        var response = controller.updateAgentModel("engineering", new AgentModelCommand("llama3:8b"));

        verify(memory).setAgentModel("engineering", "llama3:8b");
        assertEquals("llama3:8b", response.model());
    }
```

Agregar el import correspondiente:

```java
import com.aicompany.core.model.AgentModelCommand;
```

- [ ] **Step 2: Ejecutar el test para confirmar que falla (compilación)**

Run: `cd app && mvn test -Dtest=CompanyControllerTest`
Expected: FAIL (compilación — `AgentModelCommand`/`controller.updateAgentModel` no existen todavía)

- [ ] **Step 3: Crear `AgentModelCommand`**

```java
package com.aicompany.core.model;

import jakarta.validation.constraints.NotBlank;

public record AgentModelCommand(
        @NotBlank String model
) {
}
```

- [ ] **Step 4: Crear la respuesta y el endpoint en `CompanyController`**

Dado que no existe todavía un record de respuesta para esto, agregar uno simple junto a `AgentModelCommand` (mismo archivo o uno nuevo — usar uno nuevo, mismo criterio que `SettingsResponse` separado de `SettingsCommand`):

```java
package com.aicompany.core.model;

public record AgentModelResponse(
        String agentId,
        String model
) {
}
```

En `CompanyController.java`, agregar los imports:

```java
import com.aicompany.core.model.AgentModelCommand;
import com.aicompany.core.model.AgentModelResponse;
```

Agregar el endpoint (por ejemplo, después de `agentStatus()`):

```java
    /**
     * Cambia el modelo LLM real de un agente puntual — toma efecto en su
     * próxima tarea/llamada, sin caché ni reinicio (ver
     * {@code AgentRuntime}/{@code MissionExecutor}/{@code ChatIntentRouter},
     * que resuelven el modelo real desde Company Memory en cada llamada).
     * Sin validar contra qué modelos existen en Ollama — mismo criterio
     * que el resto del proyecto con `ollama.ceo-model`/`ollama.agent-model`.
     */
    @PutMapping("/agents/{id}/model")
    public AgentModelResponse updateAgentModel(
            @PathVariable("id") String id,
            @Valid @RequestBody AgentModelCommand command) {

        memoryService.setAgentModel(id, command.model());

        return new AgentModelResponse(id, command.model());
    }
```

(`@PathVariable`/`@PutMapping` ya están cubiertos por el import `org.springframework.web.bind.annotation.*` existente en este archivo — no agregar ningún import nuevo para esas anotaciones).

- [ ] **Step 5: Ejecutar el test y confirmar que pasa**

Run: `cd app && mvn test -Dtest=CompanyControllerTest`
Expected: PASS (todos los tests existentes + el nuevo)

- [ ] **Step 6: Ejecutar la suite completa**

Run: `cd app && mvn test`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/aicompany/core/model/AgentModelCommand.java \
        app/src/main/java/com/aicompany/core/model/AgentModelResponse.java \
        app/src/main/java/com/aicompany/core/controller/CompanyController.java \
        app/src/test/java/com/aicompany/core/controller/CompanyControllerTest.java
git commit -m "Agregar endpoint PUT /api/company/agents/{id}/model para cambiar el modelo real de un agente"
```

---

### Task 6: Company Chat — consulta determinista `ENGINEERING_TEAM`

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java`
- Modify: `app/src/main/java/com/aicompany/core/service/CeoService.java`
- Modify: `app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java`

**Interfaces:**
- Consumes: `EngineeringTeamMemoryService.snapshot() -> EngineeringTeamSnapshot` (Task 2); `MissionMemoryService.latestTaskPerAgent() -> List<AgentStatusResponse>` (ya existe).

- [ ] **Step 1: Escribir los tests nuevos (fallan: `EngineeringTeamMemoryService` sin inyectar todavía en el router, `QueryIntent.ENGINEERING_TEAM` no existe)**

Agregar el import a `ChatIntentRouterTest.java`:

```java
import com.aicompany.core.model.EngineeringTeamSnapshot;
import com.aicompany.core.model.TeamMemberInfo;
```

Agregar el mock y actualizar la construcción del router (10º parámetro nuevo, al final):

```java
    private final EngineeringTeamMemoryService engineeringTeamMemory = mock(EngineeringTeamMemoryService.class);

    private final ChatIntentRouter router = new ChatIntentRouter(
            missionService, ceoService, missionMemory, opportunityMemory, customerMemory, companyMemory,
            conversationMemory, appProperties, productStatusService, "qwen2.5-coder:14b", engineeringTeamMemory
    );
```

Agregar estos tests nuevos (por ejemplo, después de `routesCompanyStatusQueryToADeterministicAggregateSnapshot`):

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

- [ ] **Step 2: Ejecutar los tests para confirmar que fallan (compilación)**

Run: `cd app && mvn test -Dtest=ChatIntentRouterTest`
Expected: FAIL (compilación — constructor de 11 parámetros no existe, `EngineeringTeamMemoryService` no importado en el archivo principal)

- [ ] **Step 3: Inyectar `EngineeringTeamMemoryService` en `ChatIntentRouter`**

Agregar el campo y parámetro de constructor (al final, después de `defaultCeoModel`):

```java
    private final String defaultCeoModel;
    private final EngineeringTeamMemoryService engineeringTeamMemory;

    public ChatIntentRouter(
            MissionService missionService,
            CeoService ceoService,
            MissionMemoryService missionMemory,
            OpportunityMemoryService opportunityMemory,
            CustomerMemoryService customerMemory,
            CompanyMemoryService companyMemory,
            ConversationMemoryService conversationMemory,
            AppProperties appProperties,
            ProductStatusService productStatusService,
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

- [ ] **Step 4: Agregar `QueryIntent.ENGINEERING_TEAM` y su detección, antes del chequeo de `AGENT_STATUS`**

Reemplazar el enum:

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

En `detectQuery`, insertar el chequeo nuevo **antes** del bloque de `AGENT_STATUS` (que hoy dispara con solo `"equipo"` y por eso debe perder frente a esta consulta más específica):

```java
    private QueryIntent detectQuery(String message) {

        var normalized = normalize(message);

        if ((normalized.contains("ingenieria") || normalized.contains("engineering"))
                && (normalized.contains("equipo") || normalized.contains("team")
                        || normalized.contains("lidera") || normalized.contains("lider"))) {
            // Chequeo antes que AGENT_STATUS a propósito: "equipo" solo
            // ya dispara AGENT_STATUS (bug real corregido en una ronda
            // anterior, ver CLAUDE.md) -- "el equipo de ingeniería" es
            // una pregunta más específica sobre una estructura real
            // (Team/MEMBER_OF/LEADS), no sobre el estado de cada agente.
            return QueryIntent.ENGINEERING_TEAM;
        }

        if (normalized.contains("equipo")
                || normalized.contains("agente")
                || normalized.contains("trabajando")) {
```

(el resto del método no cambia).

- [ ] **Step 5: Agregar el caso al switch de `answerMemoryTopic` y el formatter**

Reemplazar el switch:

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

Agregar el formatter nuevo (por ejemplo, justo después de `answerMemoryTopic`):

```java
    /**
     * Snapshot real del Engineering Team — cruza
     * {@link EngineeringTeamMemoryService#snapshot()} (miembros, roles,
     * roleCode, capabilities, modelo, líder) con
     * {@code missionMemory.latestTaskPerAgent()} (status/tarea actual
     * real) — mismo criterio que {@code formatMissionStatus}: "quién es"
     * y "qué está haciendo ahora" son preguntas distintas, ninguna se
     * infiere de la otra. 100% Java, nunca pasa por Ollama.
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
                    var statusText = status != null ? status.status() : "IDLE";
                    var taskText = status != null && status.action() != null
                            ? ", tarea actual: " + status.action() + " (" + status.taskStatus() + ")"
                            : "";

                    return m.name() + leaderTag + " — " + m.role() + " [" + m.roleCode() + "]: "
                            + "capabilities=" + String.join(", ", m.capabilities())
                            + ", model=" + m.model() + ", status=" + statusText + taskText;
                })
                .collect(Collectors.joining(" | "));

        return "Engineering Team (" + snapshot.status() + "): " + lines;
    }
```

- [ ] **Step 6: Agregar `ENGINEERING_TEAM` al enum de topics de `query_company_memory` en `CeoService`**

En `CeoService.java`, dentro de `COMPANY_MEMORY_TOOLS`, agregar `"ENGINEERING_TEAM"` a la lista de `enum` de `topic` (junto a `"AGENT_STATUS"`, etc.) y una frase a la descripción:

```java
                                                    "enum", List.of(
                                                            "ENGINEERING_TEAM",
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
                                                    "ENGINEERING_TEAM: "
                                                            + "estructura real "
                                                            + "del Engineering "
                                                            + "Team -- "
                                                            + "miembros, líder, "
                                                            + "roles, "
                                                            + "capabilities y "
                                                            + "modelo de cada "
                                                            + "uno. "
                                                            + "AGENT_STATUS: qué "
                                                            + "está "
```

(el resto de la descripción existente sigue igual, solo se antepone la frase de `ENGINEERING_TEAM` antes de la de `AGENT_STATUS`).

- [ ] **Step 7: Ejecutar los tests y confirmar que pasan**

Run: `cd app && mvn test -Dtest=ChatIntentRouterTest`
Expected: PASS (todos los existentes + los 2 nuevos)

- [ ] **Step 8: Ejecutar la suite completa**

Run: `cd app && mvn test`
Expected: PASS — 162 previos + 2 nuevos = 164, sin regresiones.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java \
        app/src/main/java/com/aicompany/core/service/CeoService.java \
        app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java
git commit -m "Chat: consulta determinista ENGINEERING_TEAM (miembros, líder, roles, capabilities, modelo real)"
```

---

## Self-Review (completado antes de entregar el plan)

- **Cobertura del spec**: decisión 1 (5 agentes, 2 reutilizados) → Task 1; decisión 2 (role/roleCode/capabilities, incluido el stack de lenguajes/AWS/SQL-NoSQL/Flutter agregado después de la aprobación inicial) → Task 2; decisión 3/4 (Team, MEMBER_OF/LEADS, nunca cambia status ni crea AgentTask) → Task 2; decisión 5 (`EngineeringTeamMemoryService`) → Task 2; decisión 6 (modelo real por agente + endpoint) → Tasks 1, 3, 4, 5; decisión 7 (consulta `ENGINEERING_TEAM`) → Task 6; decisión 8 (MISSION lookup / NEVER_NARRATE_WITHOUT_EVIDENCE) → ya implementado en el Proyecto A, sin tareas nuevas acá; decisión 9 (testing) → aplicada en cada task (sin test directo para `*MemoryService`, test real para wiring/formatters).
- **Placeholders**: ninguno — todo paso trae el código completo.
- **Consistencia de tipos**: `EngineeringTeamSnapshot`/`TeamMemberInfo` usados con los mismos nombres de campo en Task 2 (producción) y Task 6 (consumo en `ChatIntentRouter` y en el test); `CompanyMemoryService.agentModel(String,String)`/`setAgentModel(String,String)` con la misma firma en Task 1 (producción) y Tasks 4/5 (consumo); `CeoService.chat/executeAgentTask/executeMission` con `model` como último parámetro en las tres, consistente entre Task 3 (producción) y Task 4 (consumo + tests).
- **Orden de dependencias verificado**: Task 1 antes de Task 2 (Team se apoya en los `Agent` ya creados); Task 3 antes de Task 4 (necesita la firma nueva de `CeoService`); Task 1 antes de Tasks 4/5 (necesitan `agentModel`/`setAgentModel`); Task 2 antes de Task 6 (necesita `EngineeringTeamMemoryService`); Task 4 antes de Task 6 (ambas tocan el constructor de `ChatIntentRouter` — Task 6 agrega su parámetro *después* del que ya dejó Task 4).
- **Riesgo señalado explícitamente**: Task 3 deja el build roto a propósito (los call-sites de `AgentRuntime`/`MissionExecutor`/`ChatIntentRouter` no compilan hasta Task 4) — aceptable en un plan de subagent-driven-development porque Task 4 se ejecuta inmediatamente después en la misma sesión, pero el ejecutor debe saberlo de antemano (ya está anotado en el Step 8 de Task 3) para no interpretarlo como que algo salió mal.
