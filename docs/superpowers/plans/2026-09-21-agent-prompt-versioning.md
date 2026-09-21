# Prompt Versionado por Agente Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cada uno de los 14 agentes de Forjai tiene un prompt propio, persistido y versionado en Neo4j, editable desde el Command Center web. Para los 6 agentes que ejecutan tareas reales hoy (`ceo`, `sales`, `product`, `finance`, `engineering`, `qa`), el prompt activo tiene efecto inmediato en la próxima llamada a Ollama.

**Architecture:** Nodo nuevo `PromptVersion` (inmutable, uno por versión) + relaciones `HAS_PROMPT_VERSION` (historial completo) / `HAS_ACTIVE_PROMPT` (exactamente una, garantizada transaccionalmente) sobre `Agent`. Servicio dedicado `PromptMemoryService` (mismo patrón que `TeamMemoryService`). El contenido activo se inyecta como una sección aparte y opcional dentro de los templates existentes (`CeoService.systemPrompt`, `AgentRuntime.buildPrompt`) — mismo patrón ya usado para `Agent.model`: el llamador resuelve el valor real desde Neo4j y lo pasa como parámetro explícito, `CeoService` sigue sin depender de Neo4j.

**Tech Stack:** Spring Boot 4.1.1 / Java 21, neo4j-java-driver, JUnit 5 + Mockito, React 19 + TypeScript + Vite.

**Spec:** `docs/superpowers/specs/2026-09-21-agent-prompt-versioning-design.md`

## Global Constraints

- Invariante dura: en todo momento existe **exactamente una** relación `HAS_ACTIVE_PROMPT` por `Agent` (nunca cero tras el seed, nunca dos) — cada cambio de versión activa (crear o rollback) es una sola transacción Cypher que borra la anterior y crea la nueva.
- Rollback a una versión anterior **reactiva el nodo `PromptVersion` existente** — nunca crea un nodo nuevo con contenido duplicado.
- `createdBy` queda fijo al literal `"human"` (mismo criterio que `CustomerMemoryService` para acciones que solo dispara el fundador) — no existe concepto de usuario/sesión en el proyecto, no se inventa uno acá.
- El prompt editable **nunca** reemplaza ni se mezcla con: las REGLAS anti-alucinación y el bloque FORMATO OBLIGATORIO de `AgentRuntime.buildPrompt`, el `AgentResultSchema.SCHEMA` (parámetro `format` de Ollama), ni el mapeo `action→objective` de `MissionExecutor`. Se inserta como una sección aparte, condicional (solo si no está en blanco).
- `toolDecisionSystemPrompt` (turno corto de decisión de herramienta) **nunca** recibe el prompt editable del agente.
- Seed inicial: los 14 agentes arrancan con una versión 1 de contenido vacío — cero cambio de comportamiento en los 6 agentes que ya ejecutan hasta que alguien edite algo a propósito.
- Los 8 agentes sin `AgentTask` real hoy (`devops`, `backend`, `frontend-ui`, `interaction-design`, `visual-design`, `telemetry`, `growth-content`, `community`) guardan y versionan su prompt igual que los demás, sin ningún efecto observable todavía.

---

### Task 1: `PromptVersion` — modelo de datos + `PromptMemoryService`

**Files:**
- Create: `app/src/main/java/com/aicompany/core/model/PromptVersionSummary.java`
- Create: `app/src/main/java/com/aicompany/core/model/PromptSnapshot.java`
- Create: `app/src/main/java/com/aicompany/core/service/PromptMemoryService.java`
- Modify: `app/src/main/java/com/aicompany/core/service/CompanyMemoryService.java`
- Modify: `app/src/main/java/com/aicompany/core/config/CompanyMemoryInitializer.java`

**Interfaces:**
- Produces: `PromptMemoryService.ensureDefaultPrompts()`, `.activePrompt(String agentId) -> String`, `.snapshot(String agentId) -> PromptSnapshot`, `.versionContent(String agentId, int version) -> String`, `.createVersion(String agentId, String content, String changeReason) -> PromptSnapshot`, `.activateVersion(String agentId, int version) -> PromptSnapshot`. Task 2 (controller) y Task 4 (`AgentRuntime`/`MissionExecutor`/`ChatIntentRouter`) consumen estos métodos.
- Consumes: nada nuevo — usa el `Driver` de Neo4j ya inyectado en todos los `*MemoryService`.

- [ ] **Step 1: Crear `model/PromptVersionSummary.java`**

```java
package com.aicompany.core.model;

import java.time.Instant;

public record PromptVersionSummary(
        int version,
        String createdBy,
        String changeReason,
        Instant createdAt
) {
}
```

- [ ] **Step 2: Crear `model/PromptSnapshot.java`**

```java
package com.aicompany.core.model;

import java.time.Instant;
import java.util.List;

public record PromptSnapshot(
        String agentId,
        int activeVersion,
        String activeContent,
        String activeCreatedBy,
        String activeChangeReason,
        Instant activeCreatedAt,
        List<PromptVersionSummary> versions
) {
}
```

- [ ] **Step 3: Crear `service/PromptMemoryService.java`**

```java
package com.aicompany.core.service;

import com.aicompany.core.model.PromptSnapshot;
import com.aicompany.core.model.PromptVersionSummary;
import org.neo4j.driver.Driver;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Prompt propio, persistido y versionado, de cada uno de los 14
 * agentes — capa aparte sobre los {@code Agent} ya creados por
 * {@link CompanyMemoryService} (no los crea, solo los enriquece).
 * Ver docs/superpowers/specs/2026-09-21-agent-prompt-versioning-design.md.
 *
 * <p>Invariante dura: en todo momento existe exactamente una relación
 * {@code HAS_ACTIVE_PROMPT} por {@code Agent}. Editar (crear versión)
 * y hacer rollback (activar una versión existente) son cada uno una
 * sola transacción Cypher que borra la relación activa anterior y crea
 * la nueva — nunca queda un estado intermedio con cero o dos activas.
 * Rollback nunca crea un {@code PromptVersion} nuevo, solo reapunta
 * {@code HAS_ACTIVE_PROMPT} a un nodo ya existente.
 */
@Service
public class PromptMemoryService {

    private final Driver driver;

    public PromptMemoryService(Driver driver) {
        this.driver = driver;
    }

    /**
     * Idempotente: cualquier {@code Agent} sin {@code HAS_ACTIVE_PROMPT}
     * todavía recibe una versión 1 de contenido vacío, y queda activa.
     * Llamado desde {@code CompanyMemoryInitializer} después de que los
     * agentes ya existan.
     */
    public void ensureDefaultPrompts() {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH (a:Agent) WHERE NOT (a)-[:HAS_ACTIVE_PROMPT]->(:PromptVersion) "
                                + "MERGE (v:PromptVersion {id: a.id + '-v1'}) "
                                + "ON CREATE SET v.agentId = a.id, v.version = 1, v.content = '', "
                                + "v.createdBy = 'human', v.changeReason = 'Versión inicial (seed)', "
                                + "v.createdAt = $createdAt "
                                + "MERGE (a)-[:HAS_PROMPT_VERSION]->(v) "
                                + "MERGE (a)-[:HAS_ACTIVE_PROMPT]->(v)",
                        Map.of("createdAt", Instant.now().toString()));
                return null;
            });
        }
    }

    /**
     * Contenido de la versión activa de este agente — resuelto por
     * {@code AgentRuntime}/{@code MissionExecutor}/{@code ChatIntentRouter}
     * antes de cada llamada real a Ollama, nunca por {@code CeoService}
     * (que sigue sin depender de Neo4j directamente). {@code ""} si el
     * agente todavía no tiene ninguna versión (defensivo, no debería
     * pasar en la práctica tras {@link #ensureDefaultPrompts()}).
     */
    public String activePrompt(String agentId) {
        try (var session = driver.session()) {
            return session.run(
                            "MATCH (a:Agent {id:$agentId})-[:HAS_ACTIVE_PROMPT]->(v:PromptVersion) "
                                    + "RETURN v.content AS content",
                            Map.of("agentId", agentId))
                    .list(r -> r.get("content").asString())
                    .stream().findFirst()
                    .orElse("");
        }
    }

    /**
     * Lectura completa para el Command Center web — versión activa +
     * historial (sin el contenido de cada versión vieja, ver
     * {@link #versionContent}). Lanza {@code IllegalArgumentException}
     * si el agente no existe o no tiene ninguna versión todavía.
     */
    public PromptSnapshot snapshot(String agentId) {
        try (var session = driver.session()) {

            var active = session.run(
                    "MATCH (a:Agent {id:$agentId})-[:HAS_ACTIVE_PROMPT]->(v:PromptVersion) "
                            + "RETURN v.version AS version, v.content AS content, "
                            + "v.createdBy AS createdBy, v.changeReason AS changeReason, "
                            + "v.createdAt AS createdAt",
                    Map.of("agentId", agentId)
            ).list();

            if (active.isEmpty()) {
                throw new IllegalArgumentException("No existe prompt activo para el agente " + agentId);
            }

            var a = active.get(0);

            var versions = session.run(
                    "MATCH (:Agent {id:$agentId})-[:HAS_PROMPT_VERSION]->(v:PromptVersion) "
                            + "RETURN v.version AS version, v.createdBy AS createdBy, "
                            + "v.changeReason AS changeReason, v.createdAt AS createdAt "
                            + "ORDER BY v.version DESC",
                    Map.of("agentId", agentId)
            ).list(r -> new PromptVersionSummary(
                    r.get("version").asInt(),
                    r.get("createdBy").asString(),
                    r.get("changeReason").asString(),
                    Instant.parse(r.get("createdAt").asString())
            ));

            return new PromptSnapshot(
                    agentId,
                    a.get("version").asInt(),
                    a.get("content").asString(),
                    a.get("createdBy").asString(),
                    a.get("changeReason").asString(),
                    Instant.parse(a.get("createdAt").asString()),
                    versions
            );
        }
    }

    /**
     * Contenido completo de una versión puntual (para previsualizar
     * antes de activar una del historial). Lanza
     * {@code IllegalArgumentException} si no existe esa versión para
     * ese agente.
     */
    public String versionContent(String agentId, int version) {
        try (var session = driver.session()) {
            return session.run(
                            "MATCH (:Agent {id:$agentId})-[:HAS_PROMPT_VERSION]->(v:PromptVersion {version:$version}) "
                                    + "RETURN v.content AS content",
                            Map.of("agentId", agentId, "version", version))
                    .list(r -> r.get("content").asString())
                    .stream().findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No existe la versión " + version + " para el agente " + agentId));
        }
    }

    /**
     * Crea una versión nueva (siguiente número, nunca pisa una vieja) y
     * la activa — una sola transacción: si el {@code MATCH} del agente
     * no encuentra nada, la consulta completa no devuelve filas y se
     * lanza {@code IllegalArgumentException}, sin dejar nada a medio
     * escribir.
     */
    public PromptSnapshot createVersion(String agentId, String content, String changeReason) {

        if (changeReason == null || changeReason.isBlank()) {
            throw new IllegalArgumentException("changeReason no puede estar vacío");
        }

        try (var session = driver.session()) {
            session.executeWrite(tx -> {

                var result = tx.run(
                        "MATCH (a:Agent {id:$agentId}) "
                                + "OPTIONAL MATCH (a)-[:HAS_PROMPT_VERSION]->(existing:PromptVersion) "
                                + "WITH a, coalesce(max(existing.version), 0) + 1 AS nextVersion "
                                + "CREATE (v:PromptVersion {id: $agentId + '-v' + toString(nextVersion), "
                                + "agentId: $agentId, version: nextVersion, content: $content, "
                                + "createdBy: 'human', changeReason: $changeReason, createdAt: $createdAt}) "
                                + "MERGE (a)-[:HAS_PROMPT_VERSION]->(v) "
                                + "WITH a, v "
                                + "OPTIONAL MATCH (a)-[old:HAS_ACTIVE_PROMPT]->(:PromptVersion) "
                                + "DELETE old "
                                + "CREATE (a)-[:HAS_ACTIVE_PROMPT]->(v) "
                                + "RETURN v",
                        Map.of("agentId", agentId, "content", content, "changeReason", changeReason,
                                "createdAt", Instant.now().toString()));

                if (result.list().isEmpty()) {
                    throw new IllegalArgumentException("No existe el agente " + agentId);
                }

                return null;
            });
        }

        return snapshot(agentId);
    }

    /**
     * Rollback: reactiva una versión ya existente del historial — nunca
     * crea un {@code PromptVersion} nuevo. Una sola transacción: si el
     * {@code MATCH} de la versión objetivo no encuentra nada, la
     * consulta no devuelve filas y se lanza
     * {@code IllegalArgumentException}.
     */
    public PromptSnapshot activateVersion(String agentId, int version) {

        try (var session = driver.session()) {
            session.executeWrite(tx -> {

                var result = tx.run(
                        "MATCH (a:Agent {id:$agentId})-[:HAS_PROMPT_VERSION]->(v:PromptVersion {version:$version}) "
                                + "OPTIONAL MATCH (a)-[old:HAS_ACTIVE_PROMPT]->(:PromptVersion) "
                                + "DELETE old "
                                + "CREATE (a)-[:HAS_ACTIVE_PROMPT]->(v) "
                                + "RETURN v",
                        Map.of("agentId", agentId, "version", version));

                if (result.list().isEmpty()) {
                    throw new IllegalArgumentException(
                            "No existe la versión " + version + " para el agente " + agentId);
                }

                return null;
            });
        }

        return snapshot(agentId);
    }
}
```

- [ ] **Step 4: Agregar el constraint de unicidad en `CompanyMemoryService.initializeSchema()`**

En `CompanyMemoryService.java`, buscar la línea:
```java
            session.run("CREATE CONSTRAINT team_id IF NOT EXISTS FOR (t:Team) REQUIRE t.id IS UNIQUE").consume();
```
Agregar justo después:
```java
            session.run("CREATE CONSTRAINT prompt_version_id IF NOT EXISTS FOR (v:PromptVersion) REQUIRE v.id IS UNIQUE").consume();
```

- [ ] **Step 5: Wirear `PromptMemoryService.ensureDefaultPrompts()` en `CompanyMemoryInitializer`**

Reemplazar el contenido completo del archivo `app/src/main/java/com/aicompany/core/config/CompanyMemoryInitializer.java`:

```java
package com.aicompany.core.config;

import com.aicompany.core.service.CompanyMemoryService;
import com.aicompany.core.service.MissionMemoryService;
import com.aicompany.core.service.PromptMemoryService;
import com.aicompany.core.service.TeamMemoryService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class CompanyMemoryInitializer {
    private final CompanyMemoryService memory;
    private final MissionMemoryService missionMemory;
    private final TeamMemoryService teamMemory;
    private final PromptMemoryService promptMemory;

    public CompanyMemoryInitializer(
            CompanyMemoryService memory,
            MissionMemoryService missionMemory,
            TeamMemoryService teamMemory,
            PromptMemoryService promptMemory) {

        this.memory = memory;
        this.missionMemory = missionMemory;
        this.teamMemory = teamMemory;
        this.promptMemory = promptMemory;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeAfterReady() {
        memory.initialize();
        missionMemory.backfillMissionEnvironment();
        teamMemory.ensureAllTeams();
        promptMemory.ensureDefaultPrompts();
    }
}
```

- [ ] **Step 6: Compilar y correr la suite completa**

Run: `cd app && mvn test`
Expected: BUILD SUCCESS, mismo número de tests que antes (183) — este task no agrega tests directos de `PromptMemoryService` (mismo criterio ya establecido para el seed/Cypher de `*MemoryService`: verificado en vivo, no unitario).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/aicompany/core/model/PromptVersionSummary.java \
        app/src/main/java/com/aicompany/core/model/PromptSnapshot.java \
        app/src/main/java/com/aicompany/core/service/PromptMemoryService.java \
        app/src/main/java/com/aicompany/core/service/CompanyMemoryService.java \
        app/src/main/java/com/aicompany/core/config/CompanyMemoryInitializer.java
git commit -m "Agregar PromptVersion + PromptMemoryService: prompt persistido y versionado por agente"
```

---

### Task 2: API REST — `CompanyController`

**Files:**
- Create: `app/src/main/java/com/aicompany/core/model/PromptCommand.java`
- Create: `app/src/main/java/com/aicompany/core/model/PromptVersionContent.java`
- Modify: `app/src/main/java/com/aicompany/core/controller/CompanyController.java`
- Modify: `app/src/test/java/com/aicompany/core/controller/CompanyControllerTest.java`

**Interfaces:**
- Consumes: `PromptMemoryService` (Task 1) — `snapshot`, `versionContent`, `createVersion`, `activateVersion`.
- Produces: 4 endpoints nuevos bajo `/api/company/agents/{id}/prompt` — Task 5 (frontend) los consume.

- [ ] **Step 1: Crear `model/PromptCommand.java`**

```java
package com.aicompany.core.model;

import jakarta.validation.constraints.NotBlank;

public record PromptCommand(
        String content,
        @NotBlank String changeReason
) {
}
```

`content` sin `@NotBlank` a propósito — un prompt vacío es un valor válido (equivale a "sin instrucciones adicionales", el mismo estado del seed inicial).

- [ ] **Step 2: Crear `model/PromptVersionContent.java`**

```java
package com.aicompany.core.model;

public record PromptVersionContent(
        int version,
        String content
) {
}
```

- [ ] **Step 3: Agregar los 4 endpoints a `CompanyController.java`**

Agregar estos imports junto a los existentes:
```java
import com.aicompany.core.model.PromptCommand;
import com.aicompany.core.model.PromptSnapshot;
import com.aicompany.core.model.PromptVersionContent;
import com.aicompany.core.service.PromptMemoryService;
```

Agregar el campo y el parámetro de constructor (mismo patrón que `teamMemoryService` en el commit anterior):

```java
    private final TeamMemoryService teamMemoryService;
    private final PromptMemoryService promptMemoryService;

    public CompanyController(
            CompanyMemoryService memoryService,
            MissionMemoryService missionMemoryService,
            ActivityMemoryService activityMemoryService,
            ChatIntentRouter chatIntentRouter,
            TeamMemoryService teamMemoryService,
            PromptMemoryService promptMemoryService) {

        this.memoryService = memoryService;
        this.missionMemoryService = missionMemoryService;
        this.activityMemoryService = activityMemoryService;
        this.chatIntentRouter = chatIntentRouter;
        this.teamMemoryService = teamMemoryService;
        this.promptMemoryService = promptMemoryService;
    }
```

Agregar los 4 endpoints nuevos, después del método `teams()`:

```java
    /**
     * Prompt versionado de un agente puntual — panel "Agents" del
     * Command Center web (editor dentro del organigrama). Versión
     * activa + historial (sin el contenido de cada versión vieja, ver
     * {@code GET .../versions/{version}}).
     */
    @GetMapping("/agents/{id}/prompt")
    public PromptSnapshot agentPrompt(@PathVariable("id") String id) {
        return promptMemoryService.snapshot(id);
    }

    /**
     * Contenido completo de una versión puntual del historial — para
     * previsualizar antes de activarla (rollback).
     */
    @GetMapping("/agents/{id}/prompt/versions/{version}")
    public PromptVersionContent agentPromptVersion(
            @PathVariable("id") String id,
            @PathVariable("version") int version) {

        return new PromptVersionContent(version, promptMemoryService.versionContent(id, version));
    }

    /**
     * Crea una versión nueva del prompt de este agente y la activa —
     * nunca pisa una versión existente. Toma efecto en la próxima
     * llamada real a Ollama de este agente, sin caché ni reinicio
     * (mismo criterio ya usado para {@code PUT /agents/{id}/model}).
     */
    @PutMapping("/agents/{id}/prompt")
    public PromptSnapshot updateAgentPrompt(
            @PathVariable("id") String id,
            @Valid @RequestBody PromptCommand command) {

        return promptMemoryService.createVersion(id, command.content(), command.changeReason());
    }

    /**
     * Rollback: reactiva una versión existente del historial — nunca
     * crea contenido nuevo.
     */
    @PutMapping("/agents/{id}/prompt/versions/{version}/activate")
    public PromptSnapshot activateAgentPromptVersion(
            @PathVariable("id") String id,
            @PathVariable("version") int version) {

        return promptMemoryService.activateVersion(id, version);
    }
```

- [ ] **Step 4: Actualizar `CompanyControllerTest.java`**

Agregar el import y el mock, y actualizar la construcción del controller (mismo lugar donde se agregó `teamMemory` antes):

```java
import com.aicompany.core.model.PromptSnapshot;
import com.aicompany.core.model.PromptVersionSummary;
import com.aicompany.core.service.PromptMemoryService;
```

```java
    private final TeamMemoryService teamMemory = mock(TeamMemoryService.class);
    private final PromptMemoryService promptMemory = mock(PromptMemoryService.class);

    private final CompanyController controller =
            new CompanyController(memory, missionMemory, activityMemory, router, teamMemory, promptMemory);
```

Agregar estos 4 tests nuevos (uno por endpoint), después del test `teamsEndpointDelegatesEntirelyToTeamMemoryServiceSnapshotAll`:

```java
    @Test
    void agentPromptEndpointDelegatesEntirelyToPromptMemoryServiceSnapshot() {
        var snapshot = new PromptSnapshot("sales", 2, "Sé más directo.", "human", "Ajuste de tono", Instant.now(),
                List.of(new PromptVersionSummary(2, "human", "Ajuste de tono", Instant.now()),
                        new PromptVersionSummary(1, "human", "Versión inicial (seed)", Instant.now())));
        when(promptMemory.snapshot("sales")).thenReturn(snapshot);

        var response = controller.agentPrompt("sales");

        assertEquals(snapshot, response);
    }

    @Test
    void agentPromptVersionEndpointDelegatesEntirelyToPromptMemoryServiceVersionContent() {
        when(promptMemory.versionContent("sales", 1)).thenReturn("Versión vieja.");

        var response = controller.agentPromptVersion("sales", 1);

        assertEquals(1, response.version());
        assertEquals("Versión vieja.", response.content());
    }

    @Test
    void updateAgentPromptEndpointDelegatesEntirelyToPromptMemoryServiceCreateVersion() {
        var updated = new PromptSnapshot("sales", 3, "Sé más breve.", "human", "Otro ajuste", Instant.now(), List.of());
        when(promptMemory.createVersion("sales", "Sé más breve.", "Otro ajuste")).thenReturn(updated);

        var response = controller.updateAgentPrompt("sales", new PromptCommand("Sé más breve.", "Otro ajuste"));

        assertEquals(updated, response);
        verify(promptMemory).createVersion("sales", "Sé más breve.", "Otro ajuste");
    }

    @Test
    void activateAgentPromptVersionEndpointDelegatesEntirelyToPromptMemoryServiceActivateVersion() {
        var reactivated = new PromptSnapshot("sales", 1, "", "human", "Versión inicial (seed)", Instant.now(), List.of());
        when(promptMemory.activateVersion("sales", 1)).thenReturn(reactivated);

        var response = controller.activateAgentPromptVersion("sales", 1);

        assertEquals(reactivated, response);
        verify(promptMemory).activateVersion("sales", 1);
    }
```

Agregar también el import que falta: `import com.aicompany.core.model.PromptCommand;` (`java.time.Instant` ya está importado en el archivo, no hace falta agregarlo).

- [ ] **Step 5: Compilar y correr la suite completa**

Run: `cd app && mvn test`
Expected: BUILD SUCCESS, 183 + 4 tests nuevos = **187 tests**, todos en verde.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aicompany/core/model/PromptCommand.java \
        app/src/main/java/com/aicompany/core/model/PromptVersionContent.java \
        app/src/main/java/com/aicompany/core/controller/CompanyController.java \
        app/src/test/java/com/aicompany/core/controller/CompanyControllerTest.java
git commit -m "Agregar endpoints REST de prompt versionado por agente a CompanyController"
```

---

### Task 3: `CeoService` gana el parámetro `ceoPrompt` (build roto hasta Task 4)

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/CeoService.java`

**Interfaces:**
- Consumes: nada nuevo (sigue sin depender de Neo4j).
- Produces: `chat(..., String ceoPrompt, String model)` y `executeMission(String instruction, String agentResults, String ceoPrompt, String model)` — Task 4 actualiza los 3 call sites (`ChatIntentRouter` x2, `MissionExecutor` x1).

**Nota para el implementador**: este task deja el build roto a propósito. `ChatIntentRouter.java` y `MissionExecutor.java` (no tocados en este task) siguen llamando a `chat(...)`/`executeMission(...)` con la firma vieja (5/3 argumentos) — `mvn compile` va a fallar con errores de "method cannot be applied to given types" en esos dos archivos. Es intencional, mismo patrón ya usado en la ronda de Engineering Team para el mismo tipo de cambio (agregar un parámetro a `CeoService` que requiere actualizar 3 call sites en otro archivo). No lo reportes como bloqueante — verificá este task por lectura de código, `mvn compile` no puede completar hasta el Task 4.

- [ ] **Step 1: `systemPrompt()` gana el parámetro `agentPrompt`**

Cambiar:
```java
    private String systemPrompt() {

        return """
                Eres el CEO de Forjai,
                una empresa real operada principalmente por agentes de IA.

                Capital semilla inicial: US$50.
                Horizonte: 60 días.

                La empresa utiliza IA para operar y crear negocios;
                no vende la plataforma de IA como producto.

                Debes buscar valor económico real,
                exigir evidencia y conservar iniciativa estratégica.

                El inversionista puede aprobar, rechazar
                o proponer una alternativa.

                No inventes clientes, ventas, ingresos,
                búsquedas o evidencia.

                Diferencia siempre entre:
                - hecho
                - hipótesis
                - estimación
                - evidencia
                - resultado verificado

                Responde en español.
                """;
    }
```
a:
```java
    /**
     * {@code agentPrompt} es el prompt activo persistido del CEO
     * (`Agent {id:'ceo'}`, ver {@code PromptMemoryService}) — resuelto
     * por el llamador (`ChatIntentRouter`/`MissionExecutor`), nunca por
     * este servicio directamente (sigue sin depender de Neo4j). Se
     * inserta como una sección aparte, condicional: si está en blanco,
     * el prompt final es byte a byte igual al de antes de esta feature.
     */
    private String systemPrompt(String agentPrompt) {

        var agentPromptBlock = (agentPrompt == null || agentPrompt.isBlank())
                ? ""
                : "\nCÓMO DEBES RAZONAR (definido por el fundador para vos, no reemplaza las reglas de abajo):\n"
                        + agentPrompt + "\n";

        return """
                Eres el CEO de Forjai,
                una empresa real operada principalmente por agentes de IA.

                Capital semilla inicial: US$50.
                Horizonte: 60 días.

                La empresa utiliza IA para operar y crear negocios;
                no vende la plataforma de IA como producto.

                Debes buscar valor económico real,
                exigir evidencia y conservar iniciativa estratégica.

                El inversionista puede aprobar, rechazar
                o proponer una alternativa.
                %s
                No inventes clientes, ventas, ingresos,
                búsquedas o evidencia.

                Diferencia siempre entre:
                - hecho
                - hipótesis
                - estimación
                - evidencia
                - resultado verificado

                Responde en español.
                """.formatted(agentPromptBlock);
    }
```

- [ ] **Step 2: `chat(...)` gana el parámetro `ceoPrompt`**

Cambiar la firma:
```java
    public String chat(
            String ceoName,
            String teamRoster,
            List<ConversationTurn> history,
            String message,
            Function<String, String> companyMemoryQuery,
            String model) {

        var system = systemPrompt()
                + "\nTu nombre real es " + ceoName
```
a:
```java
    public String chat(
            String ceoName,
            String teamRoster,
            List<ConversationTurn> history,
            String message,
            Function<String, String> companyMemoryQuery,
            String ceoPrompt,
            String model) {

        var system = systemPrompt(ceoPrompt)
                + "\nTu nombre real es " + ceoName
```
(el resto del cuerpo del método, desde `+ " — ese es tu nombre..."` en adelante, no cambia.)

- [ ] **Step 3: `executeMission(...)` gana el parámetro `ceoPrompt`**

Cambiar:
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
a:
```java
    public String executeMission(
            String instruction,
            String agentResults,
            String ceoPrompt,
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
                Map.of("role", "system", "content", systemPrompt(ceoPrompt)),
                Map.of("role", "user", "content", prompt)
        );

        return callModel(
                "MISSION_CONSOLIDATION", "ceo", model, messages, null, null
        ).content();
    }
```

- [ ] **Step 4: Intentar compilar (se espera que falle solo en `ChatIntentRouter.java`/`MissionExecutor.java`)**

Run: `cd app && mvn compile`
Expected: FAILURE, con errores únicamente en `ChatIntentRouter.java` (2 call sites de `chat(...)`) y `MissionExecutor.java` (1 call site de `executeMission(...)`) — "method cannot be applied to given types" por faltar el argumento `ceoPrompt`. Si aparece algún otro error en un archivo distinto, es un problema real de este task — no lo ignores.

- [ ] **Step 5: Commit (con el build roto a propósito)**

```bash
git add app/src/main/java/com/aicompany/core/service/CeoService.java
git commit -m "CeoService: agregar parámetro ceoPrompt a chat/executeMission (build roto hasta el próximo commit)

ChatIntentRouter/MissionExecutor todavía no actualizados -- se arreglan
en el siguiente task, mismo patrón ya usado para el modelo por agente."
```

---

### Task 4: `AgentRuntime`/`MissionExecutor`/`ChatIntentRouter` — resolver e inyectar el prompt real (restaura el build)

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/agent/AgentRuntime.java`
- Modify: `app/src/main/java/com/aicompany/core/service/MissionExecutor.java`
- Modify: `app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java`
- Modify: `app/src/test/java/com/aicompany/core/agent/AgentRuntimeTest.java`
- Modify: `app/src/test/java/com/aicompany/core/service/MissionExecutorTest.java`
- Modify: `app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java`

**Interfaces:**
- Consumes: `PromptMemoryService.activePrompt(String agentId)` (Task 1), `CeoService.chat(..., ceoPrompt, model)` / `.executeMission(..., ceoPrompt, model)` (Task 3).

- [ ] **Step 1: `AgentRuntime` gana la dependencia `PromptMemoryService`**

En `AgentRuntime.java`, agregar el import:
```java
import com.aicompany.core.service.PromptMemoryService;
```

Cambiar el campo y el constructor:
```java
    private final CompanyMemoryService companyMemory;
    private final String defaultAgentModel;
```
a:
```java
    private final CompanyMemoryService companyMemory;
    private final PromptMemoryService promptMemory;
    private final String defaultAgentModel;
```

```java
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
a:
```java
    public AgentRuntime(
            CeoService ceoService,
            MissionMemoryService memory,
            CompanyMemoryService companyMemory,
            PromptMemoryService promptMemory,
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
        this.promptMemory = promptMemory;
        this.defaultAgentModel = defaultAgentModel;
        this.agentTaskExecutor = agentTaskExecutor;
        this.events = events;
        this.validator = validator;
        this.evidenceGate = evidenceGate;
        this.evidenceBindingGate = evidenceBindingGate;
        this.jsonMapper = jsonMapper;
    }
```

- [ ] **Step 2: Resolver `agentPrompt` en `executeInternal` y pasarlo a `buildPrompt`**

Cambiar:
```java
        var model = companyMemory.agentModel(agentId, defaultAgentModel);
```
a:
```java
        var model = companyMemory.agentModel(agentId, defaultAgentModel);
        var agentPrompt = promptMemory.activePrompt(agentId);
```

Cambiar, dentro del loop de reintentos:
```java
                var prompt = buildPrompt(
                        agentId,
                        action,
                        instruction
                );
```
a:
```java
                var prompt = buildPrompt(
                        agentId,
                        action,
                        instruction,
                        agentPrompt
                );
```

- [ ] **Step 3: `buildPrompt` inserta el bloque condicional del prompt del agente**

Cambiar:
```java
    private String buildPrompt(
            String agentId,
            String action,
            String instruction) {

        return """
                Estás trabajando dentro de Forjai como el agente %s.
                Esta es una tarea real dentro de una misión empresarial.

                REGLAS:
```
a:
```java
    private String buildPrompt(
            String agentId,
            String action,
            String instruction,
            String agentPrompt) {

        var agentPromptBlock = (agentPrompt == null || agentPrompt.isBlank())
                ? ""
                : "\nCÓMO DEBES RAZONAR (definido por el fundador para vos, no reemplaza las reglas de abajo):\n"
                        + agentPrompt + "\n";

        return """
                Estás trabajando dentro de Forjai como el agente %s.
                Esta es una tarea real dentro de una misión empresarial.
                %s
                REGLAS:
```

Y al final del mismo método, cambiar la llamada a `.formatted(...)`:
```java
                """.formatted(
                agentId,
                agentId,
                action,
                action,
                instruction
        );
    }
```
a:
```java
                """.formatted(
                agentId,
                agentPromptBlock,
                agentId,
                action,
                action,
                instruction
        );
    }
```

No cambies ninguna otra línea de `buildPrompt` — el resto del texto (REGLAS, FORMATO OBLIGATORIO, EVIDENCE, CALCULATION, CUSTOMER_CANDIDATE, ACCIÓN, MISIÓN) queda exactamente igual.

- [ ] **Step 4: `MissionExecutor` resuelve `ceoPrompt` para `executeMission`**

Agregar el campo y el parámetro de constructor:
```java
    private final CompanyMemoryService companyMemory;
    private final String defaultCeoModel;
```
a:
```java
    private final CompanyMemoryService companyMemory;
    private final PromptMemoryService promptMemory;
    private final String defaultCeoModel;
```

```java
    public MissionExecutor(
            MissionMemoryService memory,
            AgentRuntime runtime,
            CeoService ceoService,
            CompanyMemoryService companyMemory,
            @Value("${ollama.ceo-model}") String defaultCeoModel,
```
a:
```java
    public MissionExecutor(
            MissionMemoryService memory,
            AgentRuntime runtime,
            CeoService ceoService,
            CompanyMemoryService companyMemory,
            PromptMemoryService promptMemory,
            @Value("${ollama.ceo-model}") String defaultCeoModel,
```

Y en el cuerpo del constructor, agregar `this.promptMemory = promptMemory;` junto a `this.companyMemory = companyMemory;`. **No hace falta agregar ningún import**: `MissionExecutor` y `PromptMemoryService` están en el mismo paquete `com.aicompany.core.service`.

Cambiar el call site de `executeMission`:
```java
            var finalResult =
                    ceoService.executeMission(
                            instruction,
                            resultsForCeo,
                            companyMemory.agentModel("ceo", defaultCeoModel)
                    );
```
a:
```java
            var finalResult =
                    ceoService.executeMission(
                            instruction,
                            resultsForCeo,
                            promptMemory.activePrompt("ceo"),
                            companyMemory.agentModel("ceo", defaultCeoModel)
                    );
```

- [ ] **Step 5: `ChatIntentRouter` resuelve `ceoPrompt` para sus 2 llamadas a `chat`**

Agregar el campo y el parámetro de constructor (mismo lugar que `teamMemory`):
```java
    private final String defaultCeoModel;
    private final TeamMemoryService teamMemory;

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
            TeamMemoryService teamMemory) {
```
a:
```java
    private final String defaultCeoModel;
    private final TeamMemoryService teamMemory;
    private final PromptMemoryService promptMemory;

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
            TeamMemoryService teamMemory,
            PromptMemoryService promptMemory) {
```

Y en el cuerpo del constructor, agregar `this.promptMemory = promptMemory;` justo después de `this.teamMemory = teamMemory;`.

Cambiar los 2 call sites de `ceoService.chat(...)`:

Primero (dentro de `resolve()`, fallback general):
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
a:
```java
        return ceoService.chat(
                companyMemory.agentName("ceo").orElse("CEO"),
                companyMemory.teamRosterDescription(),
                conversationMemory.recentMessages(HISTORY_LIMIT),
                message,
                this::answerMemoryTopic,
                promptMemory.activePrompt("ceo"),
                companyMemory.agentModel("ceo", defaultCeoModel)
        );
```

Segundo (dentro de `handleReference()`, predicado no reconocido):
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
a:
```java
            return ceoService.chat(
                    companyMemory.agentName("ceo").orElse("CEO"),
                    companyMemory.teamRosterDescription(),
                    conversationMemory.recentMessages(HISTORY_LIMIT),
                    hint + message,
                    this::answerMemoryTopic,
                    promptMemory.activePrompt("ceo"),
                    companyMemory.agentModel("ceo", defaultCeoModel)
            );
```

- [ ] **Step 6: Intentar compilar**

Run: `cd app && mvn compile`
Expected: BUILD SUCCESS (restaura el build roto por Task 3).

- [ ] **Step 7: Actualizar `AgentRuntimeTest.java`**

Agregar el import `com.aicompany.core.service.PromptMemoryService`.

Agregar el mock y su helper (mismo patrón que `defaultCompanyMemory()`, líneas 35-41 del archivo actual):

```java
    // Sin stub explícito, un mock de PromptMemoryService devuelve null en
    // activePrompt(...) -- buildPrompt ya maneja null como "sin prompt
    // adicional" (mismo criterio defensivo que agentModel/fallback), así
    // que los tests existentes que no les importa el prompt del agente
    // no necesitan stub. Los que sí verifican inyección real lo
    // sobreescriben.
    private final PromptMemoryService promptMemory = defaultPromptMemory();

    private static PromptMemoryService defaultPromptMemory() {
        var mock = mock(PromptMemoryService.class);
        when(mock.activePrompt(anyString())).thenReturn("");
        return mock;
    }
```

Cambiar la construcción de `runtime`:
```java
    private final AgentRuntime runtime = new AgentRuntime(
            ceoService, memory, companyMemory, "qwen3:8b", Runnable::run, events,
            validator, evidenceGate, evidenceBindingGate, jsonMapper
    );
```
a:
```java
    private final AgentRuntime runtime = new AgentRuntime(
            ceoService, memory, companyMemory, promptMemory, "qwen3:8b", Runnable::run, events,
            validator, evidenceGate, evidenceBindingGate, jsonMapper
    );
```

Agregar este test nuevo, después de `succeedsOnFirstAttemptWithoutRetrying`:

```java
    @Test
    void injectsTheAgentsActivePromptIntoTheTaskPrompt() throws Exception {
        var result = agentResult("finance", "recomendación ok");

        when(promptMemory.activePrompt("finance")).thenReturn("Sé especialmente conservador con las proyecciones.");

        var promptCaptor = ArgumentCaptor.forClass(String.class);
        when(ceoService.executeAgentTask(eq("finance"), promptCaptor.capture(), anyString(), eq("MISSION-1"), eq("TASK-1"), anyString()))
                .thenReturn(outcome(result));
        when(validator.validate(result)).thenReturn(new AgentResultValidator.ValidationResult(true, List.of()));
        when(evidenceGate.validate(result)).thenReturn(new EvidenceValidationGate.ValidationResult(true, List.of()));

        var future = runtime.execute("TASK-1", "MISSION-1", "finance", "UNIT_ECONOMICS", "instrucción");

        assertEquals(result, future.get());
        assertTrue(promptCaptor.getValue().contains("CÓMO DEBES RAZONAR"));
        assertTrue(promptCaptor.getValue().contains("Sé especialmente conservador con las proyecciones."));
    }

    @Test
    void omitsThePromptBlockEntirelyWhenTheAgentHasNoActivePromptContent() throws Exception {
        var result = agentResult("finance", "recomendación ok");

        // promptMemory (default) ya devuelve "" para cualquier agente.
        var promptCaptor = ArgumentCaptor.forClass(String.class);
        when(ceoService.executeAgentTask(eq("finance"), promptCaptor.capture(), anyString(), eq("MISSION-1"), eq("TASK-1"), anyString()))
                .thenReturn(outcome(result));
        when(validator.validate(result)).thenReturn(new AgentResultValidator.ValidationResult(true, List.of()));
        when(evidenceGate.validate(result)).thenReturn(new EvidenceValidationGate.ValidationResult(true, List.of()));

        var future = runtime.execute("TASK-1", "MISSION-1", "finance", "UNIT_ECONOMICS", "instrucción");

        assertEquals(result, future.get());
        assertFalse(promptCaptor.getValue().contains("CÓMO DEBES RAZONAR"));
    }
```

- [ ] **Step 8: Actualizar `MissionExecutorTest.java`**

**No hace falta agregar ningún import**: `MissionExecutorTest` y `PromptMemoryService` están en el mismo paquete `com.aicompany.core.service`.

Buscar la construcción del `MissionExecutor` bajo prueba (campo o `@BeforeEach`) y agregar un mock `PromptMemoryService promptMemory = mock(PromptMemoryService.class);` con `when(promptMemory.activePrompt(anyString())).thenReturn("");` como stub por defecto, pasado como nuevo argumento al constructor en la posición agregada en el Step 4 (después de `companyMemory`, antes de `defaultCeoModel`).

Aplicar este reemplazo exacto en las 8 líneas siguientes (agregar `anyString(), // ceoPrompt` como nuevo argumento, justo antes del último `anyString())` de cada llamada a `executeMission`):

| Línea actual | Línea nueva |
|---|---|
| `when(ceoService.executeMission(anyString(), resultsCaptor.capture(), anyString()))` (aparece 3 veces: líneas ~63, ~125, ~177) | `when(ceoService.executeMission(anyString(), resultsCaptor.capture(), anyString(), anyString()))` |
| `verify(ceoService, times(1)).executeMission(anyString(), anyString(), anyString());` (líneas ~70, ~137) | `verify(ceoService, times(1)).executeMission(anyString(), anyString(), anyString(), anyString());` |
| `when(ceoService.executeMission(anyString(), anyString(), anyString())).thenReturn("consolidado");` (líneas ~103, ~203) | `when(ceoService.executeMission(anyString(), anyString(), anyString(), anyString())).thenReturn("consolidado");` |
| `verify(ceoService, never()).executeMission(anyString(), anyString(), anyString());` (línea ~228) | `verify(ceoService, never()).executeMission(anyString(), anyString(), anyString(), anyString());` |

Aplicar el reemplazo a las 8 ocurrencias (grep `executeMission(` en el archivo para confirmar que no queda ninguna con 3 `anyString()`/args en vez de 4).

- [ ] **Step 9: Actualizar `ChatIntentRouterTest.java`**

**No hace falta agregar ningún import**: `ChatIntentRouterTest` y `PromptMemoryService` están en el mismo paquete `com.aicompany.core.service`.

Agregar el mock y actualizar la construcción del router:
```java
    private final TeamMemoryService teamMemory = mock(TeamMemoryService.class);

    private final ChatIntentRouter router = new ChatIntentRouter(
            missionService, ceoService, missionMemory, opportunityMemory, customerMemory, companyMemory,
            conversationMemory, appProperties, productStatusService, "qwen2.5-coder:14b", teamMemory
    );
```
a:
```java
    private final TeamMemoryService teamMemory = mock(TeamMemoryService.class);
    private final PromptMemoryService promptMemory = mock(PromptMemoryService.class);

    private final ChatIntentRouter router = new ChatIntentRouter(
            missionService, ceoService, missionMemory, opportunityMemory, customerMemory, companyMemory,
            conversationMemory, appProperties, productStatusService, "qwen2.5-coder:14b", teamMemory, promptMemory
    );
```

Aplicar este reemplazo exacto en las 8 líneas siguientes (agregar un nuevo `any()` inmediatamente antes del último `any())` de cada llamada a `ceoService.chat`):

| Línea actual (línea aprox.) | Línea nueva |
|---|---|
| `when(ceoService.chat(anyString(), anyString(), any(), anyString(), any(), any())).thenReturn("¿A qué misión te referís?");` (~142) | `when(ceoService.chat(anyString(), anyString(), any(), anyString(), any(), any(), any())).thenReturn("¿A qué misión te referís?");` |
| `when(ceoService.chat(eq("Alex"), eq("- Sofia (Sales)"), any(), eq("Hola, ¿cómo estás?"), any(), any()))` (~458) | `when(ceoService.chat(eq("Alex"), eq("- Sofia (Sales)"), any(), eq("Hola, ¿cómo estás?"), any(), any(), any()))` |
| `when(ceoService.chat(eq("Alex"), eq("- Sofia (Sales)"), eq(history), eq("¿Cuál es mi color favorito?"), any(), any()))` (~482) | `when(ceoService.chat(eq("Alex"), eq("- Sofia (Sales)"), eq(history), eq("¿Cuál es mi color favorito?"), any(), any(), any()))` |
| `verify(ceoService).chat(anyString(), anyString(), any(), anyString(), captor.capture(), any());` (~707, ~917 — 2 ocurrencias idénticas) | `verify(ceoService).chat(anyString(), anyString(), any(), anyString(), captor.capture(), any(), any());` |
| `when(ceoService.chat(anyString(), anyString(), any(), anyString(), any(), any())).thenReturn("ok");` (~912) | `when(ceoService.chat(anyString(), anyString(), any(), anyString(), any(), any(), any())).thenReturn("ok");` |
| `when(ceoService.chat(eq("Alex"), eq("- Sofia (Sales)"), any(), contains("contame más sobre esas"), any(), any()))` (~948) | `when(ceoService.chat(eq("Alex"), eq("- Sofia (Sales)"), any(), contains("contame más sobre esas"), any(), any(), any()))` |
| `verify(ceoService).chat(eq("Alex"), eq("- Sofia (Sales)"), any(), contains("LAST_MENTIONED"), any(), any());` (~954) | `verify(ceoService).chat(eq("Alex"), eq("- Sofia (Sales)"), any(), contains("LAST_MENTIONED"), any(), any(), any());` |

`captor` en las líneas ~707/~917 sigue capturando el `Function<String,String>` en la misma posición relativa (5º argumento) — el `any()` nuevo va **después** de `captor.capture()`, no antes, porque `ceoPrompt` se agregó después de `companyMemoryQuery` en la firma real. Aplicar el reemplazo a las 8 ocurrencias (grep `ceoService.chat(` en el archivo para confirmar que no queda ninguna con 6 argumentos en vez de 7).

- [ ] **Step 10: Correr la suite completa**

Run: `cd app && mvn test`
Expected: BUILD SUCCESS. Conteo esperado: 187 (fin de Task 2) + 2 tests nuevos de `AgentRuntimeTest` = **189 tests**, todos en verde.

- [ ] **Step 11: Commit**

```bash
git add app/src/main/java/com/aicompany/core/agent/AgentRuntime.java \
        app/src/main/java/com/aicompany/core/service/MissionExecutor.java \
        app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java \
        app/src/test/java/com/aicompany/core/agent/AgentRuntimeTest.java \
        app/src/test/java/com/aicompany/core/service/MissionExecutorTest.java \
        app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java
git commit -m "Resolver e inyectar el prompt real por agente en AgentRuntime/MissionExecutor/ChatIntentRouter"
```

---

### Task 5: Frontend — editor de prompt dentro de Agents

**Files:**
- Modify: `app/frontend/src/api/types.ts`
- Modify: `app/frontend/src/api/client.ts`
- Modify: `app/frontend/src/pages/AgentsPage.tsx`
- Modify: `app/frontend/src/index.css`

**Interfaces:**
- Consumes: `GET /api/company/agents/{id}/prompt`, `GET /api/company/agents/{id}/prompt/versions/{version}`, `PUT /api/company/agents/{id}/prompt`, `PUT /api/company/agents/{id}/prompt/versions/{version}/activate` (Task 2).

- [ ] **Step 1: Agregar los tipos nuevos a `api/types.ts`**

```typescript
// Prompt versionado de un agente -- ver GET/PUT /api/company/agents/{id}/prompt
export interface PromptVersionSummary {
  version: number
  createdBy: string
  changeReason: string
  createdAt: string
}

export interface PromptSnapshot {
  agentId: string
  activeVersion: number
  activeContent: string
  activeCreatedBy: string
  activeChangeReason: string
  activeCreatedAt: string
  versions: PromptVersionSummary[]
}

export interface PromptVersionContent {
  version: number
  content: string
}

export interface PromptCommand {
  content: string
  changeReason: string
}
```

Agregarlos junto a los demás tipos existentes (por ejemplo, después de `TeamSnapshot`).

- [ ] **Step 2: Agregar las 4 funciones nuevas a `api/client.ts`**

Agregar al import de tipos:
```typescript
  PromptCommand,
  PromptSnapshot,
  PromptVersionContent,
```

Agregar al objeto `api`, junto a `teams`:
```typescript
  agentPrompt: (agentId: string) => request<PromptSnapshot>(`/api/company/agents/${agentId}/prompt`),

  agentPromptVersion: (agentId: string, version: number) =>
    request<PromptVersionContent>(`/api/company/agents/${agentId}/prompt/versions/${version}`),

  updateAgentPrompt: (agentId: string, command: PromptCommand) =>
    request<PromptSnapshot>(`/api/company/agents/${agentId}/prompt`, {
      method: 'PUT',
      body: JSON.stringify(command),
    }),

  activateAgentPromptVersion: (agentId: string, version: number) =>
    request<PromptSnapshot>(`/api/company/agents/${agentId}/prompt/versions/${version}/activate`, {
      method: 'PUT',
    }),
```

- [ ] **Step 3: Agregar el editor de prompt a `AgentsPage.tsx`**

Reemplazar el contenido completo del archivo:

```tsx
import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { AgentStatusResponse } from '../api/types'
import { statusDot } from '../statusColor'
import { humanizeAction } from '../humanize'

function AgentCard({
  agent,
  teamName,
  isLeader,
  onClick,
}: {
  agent: AgentStatusResponse
  teamName?: string
  isLeader?: boolean
  onClick: () => void
}) {
  return (
    <div className="card orgcard" onClick={onClick} role="button" tabIndex={0}>
      {teamName && <div className="team-label">{teamName}</div>}
      <div className="card-value">
        {statusDot(agent.status)} {agent.name}
        {isLeader && <span className="leader-tag">líder</span>}
      </div>
      <div className="card-title">{agent.role}</div>
      <p className="hint">{agent.personality}</p>
      <p className="hint">
        {agent.status === 'WORKING' && agent.missionId
          ? `Trabajando en: ${humanizeAction(agent.action)} (${agent.missionId})`
          : agent.missionId
            ? `Inactivo — última tarea: ${humanizeAction(agent.action)} (${agent.missionId}), resultado: ${agent.taskStatus}`
            : 'Inactivo'}
      </p>
    </div>
  )
}

function PromptEditor({ agent, onClose }: { agent: AgentStatusResponse; onClose: () => void }) {
  const queryClient = useQueryClient()
  const [content, setContent] = useState('')
  const [changeReason, setChangeReason] = useState('')

  const promptQuery = useQuery({
    queryKey: ['agentPrompt', agent.agentId],
    queryFn: () => api.agentPrompt(agent.agentId),
  })

  const saveMutation = useMutation({
    mutationFn: () => api.updateAgentPrompt(agent.agentId, { content, changeReason }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['agentPrompt', agent.agentId] })
      setChangeReason('')
    },
  })

  const activateMutation = useMutation({
    mutationFn: (version: number) => api.activateAgentPromptVersion(agent.agentId, version),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['agentPrompt', agent.agentId] }),
  })

  if (promptQuery.isLoading) {
    return (
      <div className="modal-backdrop" onClick={onClose}>
        <div className="modal-panel" onClick={(e) => e.stopPropagation()}>
          <p>Cargando prompt...</p>
        </div>
      </div>
    )
  }

  if (promptQuery.error || !promptQuery.data) {
    return (
      <div className="modal-backdrop" onClick={onClose}>
        <div className="modal-panel" onClick={(e) => e.stopPropagation()}>
          <p className="error">No se pudo cargar el prompt de {agent.name}.</p>
        </div>
      </div>
    )
  }

  const snapshot = promptQuery.data
  const textareaValue = content || (content === '' && !saveMutation.isSuccess ? snapshot.activeContent : content)

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal-panel" onClick={(e) => e.stopPropagation()}>
        <h2>Prompt de {agent.name}</h2>
        <p className="hint">
          Versión activa: v{snapshot.activeVersion} — {snapshot.activeChangeReason}
        </p>

        <label>
          Instrucciones adicionales (no reemplazan las reglas de seguridad, siempre fijas en código)
          <textarea
            rows={8}
            defaultValue={snapshot.activeContent}
            onChange={(e) => setContent(e.target.value)}
            placeholder="Ej: Sé especialmente conservador con las proyecciones financieras."
          />
        </label>

        <label>
          Motivo del cambio
          <input
            type="text"
            value={changeReason}
            onChange={(e) => setChangeReason(e.target.value)}
            placeholder="Ej: Ajustar tono tras retro del fundador"
          />
        </label>

        <button
          disabled={!changeReason.trim() || saveMutation.isPending}
          onClick={() => saveMutation.mutate()}
        >
          Guardar (crea versión nueva)
        </button>
        {saveMutation.isError && <p className="error">No se pudo guardar el prompt.</p>}

        <h3>Historial de versiones</h3>
        <ul className="prompt-version-list">
          {snapshot.versions.map((v) => (
            <li key={v.version}>
              <span>
                v{v.version} — {v.changeReason} ({new Date(v.createdAt).toLocaleString()})
              </span>
              {v.version !== snapshot.activeVersion && (
                <button
                  disabled={activateMutation.isPending}
                  onClick={() => activateMutation.mutate(v.version)}
                >
                  Activar
                </button>
              )}
              {v.version === snapshot.activeVersion && <span className="leader-tag">activa</span>}
            </li>
          ))}
        </ul>

        <button onClick={onClose}>Cerrar</button>
      </div>
    </div>
  )
}

export default function AgentsPage() {
  const [selectedAgent, setSelectedAgent] = useState<AgentStatusResponse | null>(null)

  const agentsQuery = useQuery({
    queryKey: ['agentsStatus'],
    queryFn: api.agentsStatus,
    refetchInterval: 5_000,
  })

  const teamsQuery = useQuery({
    queryKey: ['teams'],
    queryFn: api.teams,
    refetchInterval: 5_000,
  })

  if (agentsQuery.isLoading || teamsQuery.isLoading) return <p>Cargando agentes...</p>
  if (agentsQuery.error || teamsQuery.error) {
    return <p className="error">No se pudo cargar el estado de los agentes.</p>
  }

  const agents = agentsQuery.data ?? []
  const teams = teamsQuery.data ?? []
  const byId = new Map(agents.map((agent) => [agent.agentId, agent]))
  const teamMemberIds = new Set(teams.flatMap((team) => team.members.map((member) => member.agentId)))

  const ceo = byId.get('ceo')
  const soloReports = agents.filter((agent) => agent.agentId !== 'ceo' && !teamMemberIds.has(agent.agentId))

  return (
    <div>
      <h1>Agents</h1>
      <ul className="orgtree">
        <li>
          {ceo && <AgentCard agent={ceo} onClick={() => setSelectedAgent(ceo)} />}
          <ul>
            {soloReports.map((agent) => (
              <li key={agent.agentId}>
                <AgentCard agent={agent} onClick={() => setSelectedAgent(agent)} />
              </li>
            ))}
            {teams.map((team) => {
              const leaderAgent = team.leaderAgentId ? byId.get(team.leaderAgentId) : undefined
              const otherMembers = team.members.filter((member) => member.agentId !== team.leaderAgentId)

              return (
                <li key={team.teamId}>
                  {leaderAgent && (
                    <AgentCard
                      agent={leaderAgent}
                      teamName={team.teamName ?? undefined}
                      isLeader
                      onClick={() => setSelectedAgent(leaderAgent)}
                    />
                  )}
                  {otherMembers.length > 0 && (
                    <ul>
                      {otherMembers.map((member) => {
                        const memberAgent = byId.get(member.agentId)
                        return memberAgent ? (
                          <li key={member.agentId}>
                            <AgentCard agent={memberAgent} onClick={() => setSelectedAgent(memberAgent)} />
                          </li>
                        ) : null
                      })}
                    </ul>
                  )}
                </li>
              )
            })}
          </ul>
        </li>
      </ul>

      {selectedAgent && <PromptEditor agent={selectedAgent} onClose={() => setSelectedAgent(null)} />}
    </div>
  )
}
```

- [ ] **Step 4: Agregar el CSS del modal y la lista de versiones a `index.css`**

Agregar al final del archivo:

```css
.orgcard {
  cursor: pointer;
}

.modal-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
}

.modal-panel {
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 24px;
  width: min(560px, 90vw);
  max-height: 85vh;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.modal-panel label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 0.9em;
  color: var(--muted);
}

.modal-panel textarea,
.modal-panel input[type='text'] {
  background: #0e141c;
  color: var(--text);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 8px;
  font-family: inherit;
}

.prompt-version-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.prompt-version-list li {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
  border-bottom: 1px solid var(--border);
  font-size: 0.9em;
}
```

- [ ] **Step 5: Compilar, lintear y verificar en el navegador**

Run: `cd app/frontend && npm run build && npm run lint`
Expected: build limpio, sin warnings nuevos (el warning preexistente de `SettingsPage.tsx` no cuenta).

Levantar el dev server (`npm run dev`) contra el backend real ya corriendo en `:8081` (reconstruir el contenedor Docker primero con `docker compose build && docker compose up -d` desde la raíz del repo, después de confirmar que no hay ninguna misión en curso — ver `CLAUDE.md`, "Importante antes de reconstruir/reiniciar el contenedor"), abrir `/agents` en el navegador, hacer click en una tarjeta, confirmar que el modal carga el prompt activo (vacío para todos, recién sembrado), escribir un texto de prueba + motivo, guardar, confirmar que aparece en el historial, y activar la versión anterior (v1) para confirmar el rollback.

- [ ] **Step 6: Commit**

```bash
git add app/frontend/src/api/types.ts app/frontend/src/api/client.ts \
        app/frontend/src/pages/AgentsPage.tsx app/frontend/src/index.css
git commit -m "Frontend: editor de prompt versionado dentro de Agents (click en tarjeta abre el panel)"
```

---

### Task 6: Documentación — `CLAUDE.md` + `docs/HISTORY.md`

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs/HISTORY.md`

**Interfaces:**
- Consumes: el estado final de Tasks 1-5.

- [ ] **Step 1: Documentar el prompt versionado en `CLAUDE.md`**

Agregar una subsección nueva dentro de "Command Center web", después del bloque de `### Endpoints de solo lectura` (que ya menciona `GET /teams` de la ronda anterior):

```markdown
### Prompt versionado por agente (`PromptMemoryService`)

Cada uno de los 14 agentes tiene un prompt propio, persistido y
versionado (`PromptVersion`, inmutable — `(:Agent)-[:HAS_PROMPT_VERSION]->(:PromptVersion)`
para el historial completo, `(:Agent)-[:HAS_ACTIVE_PROMPT]->(:PromptVersion)`
para exactamente la vigente, invariante garantizada transaccionalmente
en cada creación/rollback). Editar crea una versión nueva (nunca pisa
una vieja); "activar" una versión del historial hace rollback
reapuntando la relación activa, sin duplicar contenido.
`createdBy` queda fijo en `"human"` (mismo criterio que
`CustomerMemoryService` — no hay concepto de usuario/sesión en el
proyecto).

Solo tiene efecto real en los 6 agentes que ya ejecutan tareas
(`ceo`/`sales`/`product`/`finance`/`engineering`/`qa`): el contenido
activo se inyecta como una sección aparte y condicional ("CÓMO DEBES
RAZONAR...") dentro de `CeoService.systemPrompt(ceoPrompt)` (CEO,
resuelto por `ChatIntentRouter`/`MissionExecutor`) o de
`AgentRuntime.buildPrompt(...)` (los 5 delegados, resuelto por
`AgentRuntime` mismo) — nunca reemplaza las reglas anti-alucinación, el
FORMATO OBLIGATORIO, ni el `AgentResultSchema.SCHEMA` (que sigue yendo
por el parámetro `format`, fuera del texto). Deliberadamente **nunca**
llega a `toolDecisionSystemPrompt` (turno corto de decisión de
herramienta, contrato de salida binario). Los otros 8 agentes
(`devops`/`backend`/`frontend-ui`/`interaction-design`/`visual-design`/
`telemetry`/`growth-content`/`community`) guardan y versionan su
prompt igual, sin ningún efecto todavía (no ejecutan `AgentTask`
reales — ver "Proyecto B").

Endpoints: `GET`/`PUT /api/company/agents/{id}/prompt`,
`GET /api/company/agents/{id}/prompt/versions/{version}`,
`PUT /api/company/agents/{id}/prompt/versions/{version}/activate`.
Editable desde el organigrama de `AgentsPage.tsx`: click en cualquier
tarjeta abre un panel con el prompt activo, motivo del cambio
(obligatorio al guardar), y el historial completo con botón "Activar"
por versión.
```

- [ ] **Step 2: Agregar entrada nueva a `docs/HISTORY.md`**

Leer las últimas 2-3 entradas de `docs/HISTORY.md` para confirmar el formato de encabezado real (`### <título>`, sin fecha en el encabezado — ya corregido en la ronda anterior), y agregar al final:

```markdown
### Prompt versionado y editable por agente (los 14)

Pedido del usuario: que cada agente tenga un prompt propio,
persistido, versionado, editable desde el Command Center — separado
explícitamente de las reglas/policies que siguen fijas en código
(anti-alucinación, FORMATO OBLIGATORIO, `AgentResultSchema`). Modelo
de separación de conceptos acordado explícitamente: Agent identity /
Role-roleCode / Capabilities / Model / **Prompt** (cómo razonar en el
rol) / Policies (qué tiene permitido, en código) / AgentTask (qué está
ejecutando).

Decisión de diseño: `PromptVersion` inmutable con invariante
transaccional de "exactamente una activa" por agente; rollback
reactiva un nodo existente, nunca duplica contenido; alcance a los 14
agentes aunque solo 6 tengan efecto observable hoy (los otros 8 quedan
listos para cuando exista ejecución real — "Proyecto B" — sin otro
cambio arquitectónico). Mismo patrón ya probado con `Agent.model`: el
llamador resuelve el valor real desde Neo4j y lo pasa como parámetro
explícito; `CeoService` sigue sin depender de Neo4j directamente.
```

- [ ] **Step 3: Compilar y correr la suite completa una última vez**

Run: `cd app && mvn test`
Expected: BUILD SUCCESS, 189 tests, todos en verde (este task no toca código).

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md docs/HISTORY.md
git commit -m "Documentar el prompt versionado por agente en CLAUDE.md y docs/HISTORY.md"
```

## Después del plan (fuera de las tasks, requiere autorización del usuario)

**Verificación en vivo contra Neo4j real**: arrancar el jar de esta
rama en un puerto que no choque con ningún contenedor `company-core`
ya corriendo, apuntando al Neo4j real compartido, y confirmar por los
endpoints de la propia app (no `cypher-shell` directo, para no
disparar el bloqueo del clasificador de auto mode por
"Production Reads"/"Credential Materialization" ya visto en rondas
anteriores): los 14 agentes tienen exactamente una versión activa
(`GET /agents/{id}/prompt` para varios), crear una versión nueva
sube el número y la activa, y activar una versión vieja hace rollback
sin crear una versión nueva. **No ejecutar sin pedirle autorización
explícita al usuario primero.**
