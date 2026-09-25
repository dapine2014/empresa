# Proyecto B (subproyecto 1, revisión 2026-09-24): misiones por equipo + generación real de código — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Una misión puede declarar `teamId`; el líder de ese equipo descompone el trabajo entre sus miembros según `roleCode`/`capabilities`; Engineering genera código real (workspace + Git + un commit por agente + validación estática de Vera) y Creative/Marketing producen `AgentResult` con el runtime actual. Las misiones sin `teamId` siguen exactamente igual.

**Architecture:** `MissionExecutor` sigue siendo el dueño de la máquina de estados y gana una sola bifurcación: sin `teamId` → las 5 definiciones de discovery vía `AgentTaskBatchRunner` (bloque extraído tal cual); con `teamId` → `TeamWorkPlanner` (genérico, el líder planifica con `format`, `TeamPlanValidator` determinista) → `TeamExecutionStrategy` por tipo de equipo (`AnalysisTeamStrategy` reusa `AgentTaskBatchRunner`; `DevelopmentTeamStrategy` usa `DevelopmentRuntime` + `DevelopmentWorkspaceService` + `StaticWorkspaceValidator`). La consolidación del CEO sigue en `MissionExecutor`; para desarrollo, Java agrega un bloque fijo "Estado verificable".

**Tech Stack:** Java 21, Spring Boot 4.1.1, Jackson 3 (`tools.jackson.*`), `neo4j-java-driver` con Cypher a mano, JUnit 5 + Mockito; `git` real vía `ProcessBuilder` (sin librería Git); React + Vite + TS en `app/frontend/`.

**Spec:** `docs/superpowers/specs/2026-09-21-development-generation-design.md` (revisión 2026-09-24)

## Global Constraints

- Rama de trabajo: `misiones-por-equipo` (ya creada). Cada commit termina con la línea `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Comandos Maven siempre desde `app/`: `mvn test -Dtest=Clase` para un test, `mvn test` para la suite (hoy 217 tests en verde). Frontend desde `app/frontend/`: `npm run lint` y `npm run build`.
- Jackson es `tools.jackson.*` en el código de la app — nunca `com.fasterxml.jackson.*`.
- No reintroducir `@Async`; executors explícitos inyectados con `@Qualifier` + `CompletableFuture.supplyAsync/runAsync`.
- Nunca combinar `format` y `tools` en la misma llamada a Ollama (`CeoService.rejectFormatCombinedWithTools` lo garantiza). Todas las llamadas nuevas usan `format` y **sin** `tools`.
- Todo `eventType` empieza con `EMPRESA_`. Eventos nuevos: `EMPRESA_TEAM_PLAN_CREATED`, `EMPRESA_TEAM_PLAN_REJECTED`, `EMPRESA_TASK_COMMITTED`, `EMPRESA_STATIC_VALIDATION_COMPLETED`.
- `AgentRuntime`, `AgentResult`, `AgentResultSchema` y el flujo de discovery **no cambian de comportamiento**: los tests existentes de `MissionExecutorTest` solo cambian en la línea de construcción.
- `teamId` válidos, exactos y case-sensitive: `TEAM-ENGINEERING`, `TEAM-CREATIVE-PRODUCT-INTELLIGENCE`, `TEAM-MARKETING-GROWTH` (`TeamMemoryService.KNOWN_TEAM_IDS`). El LLM nunca lee ni escribe `teamId`.
- `validationStatus` ∈ `STATICALLY_VALIDATED` | `UNVALIDATED` | `FAILED`, calculado por Java. Nada de build/test/ejecución del código generado, ni sandbox.
- Frase fija del bloque "Estado verificable": `Esta fase no ejecuta código: no se puede afirmar que el juego compile, se ejecute o pase tests.`
- Autor de cada commit: `<Agent.name> <<agentId>@agents.forjai.local>`; committer `Forjai company-core <company-core@forjai.local>`; trailers `Forjai-Mission: <missionId>` y `Forjai-Task: <taskId>`.
- Workspace: `products.workspace-root` (`PRODUCTS_WORKSPACE_ROOT`, default `${user.home}/forjai-products`); en Docker, volumen `~/forjai-products:/data/forjai-products`.
- Tope de contexto de la revisión de Vera: 60.000 caracteres en total, 8.000 por archivo.
- Controllers sin manejo fino de errores: `IllegalArgumentException`/`IllegalStateException` → 500 (convención del proyecto).
- `*MemoryService` no llevan test directo (integración con Neo4j); se validan vía los tests de quienes los mockean y la verificación en vivo.
- No tocar la misión `MISSION-1790304372795` ni reconstruir el contenedor con una misión en curso.

## Review Focus

- Un agente devuelve un archivo con ruta dentro de `.git/` (p. ej. `.git/config` o `src/.git/hooks/x`): debe rechazarse sin reintento y nunca escribirse — test en Task 2.
- Un agente devuelve la misma ruta dos veces en `files`: el commit debe contener un solo archivo (gana el último contenido) y `AgentTask.files` no debe repetir la ruta — test en Task 9.
- Un agente usa `\` como separador (`src\game.js`): es corregible, debe reintentarse con feedback, no fallar ni escribir un archivo con `\` en el nombre — test en Task 11.
- El fundador escribe el id en minúsculas (`team-engineering`) en el chat: por diseño no se reconoce (exacto), la misión arranca sin equipo — test en Task 15 que fija ese comportamiento.
- El repo generado supera el tope de revisión: Vera debe recibir marcas explícitas de truncado/omisión, nunca contenido cortado en silencio — test en Task 14.

---

## File Structure

**Contratos (agent/model, model):**
- Create `app/src/main/java/com/aicompany/core/agent/model/DevelopmentResult.java` — contrato de una tarea de desarrollo.
- Create `app/src/main/java/com/aicompany/core/agent/model/DevelopmentResultSchema.java`
- Create `app/src/main/java/com/aicompany/core/agent/model/TeamPlan.java` — plan del líder.
- Create `app/src/main/java/com/aicompany/core/agent/model/TeamPlanSchema.java`
- Create `app/src/main/java/com/aicompany/core/agent/model/StaticReviewResult.java` — revisión de Vera.
- Create `app/src/main/java/com/aicompany/core/agent/model/StaticReviewResultSchema.java`
- Create `app/src/main/java/com/aicompany/core/model/TeamExecutionMode.java`, `StaticCheck.java`, `StaticValidationStatus.java`, `TeamPlanResult.java`, `TeamMissionContext.java`, `TeamExecutionResult.java`

**Validación (agent/validation):**
- Create `OwnedPaths.java`, `DevelopmentPathValidationGate.java`, `TeamPlanValidator.java`, `RepositoryEvidenceGate.java`, `ForbiddenClaimsGuard.java`

**Runtime y servicios:**
- Create `app/src/main/java/com/aicompany/core/agent/DevelopmentRuntime.java`
- Create `app/src/main/java/com/aicompany/core/service/GitCommandRunner.java`, `DevelopmentWorkspaceService.java`, `StaticWorkspaceValidator.java`, `TeamWorkPlanner.java`, `AgentTaskBatchRunner.java`, `TeamExecutionStrategy.java`, `MissionProgress.java`, `AnalysisTeamStrategy.java`, `DevelopmentTeamStrategy.java`
- Modify `CeoService.java` (3 métodos nuevos), `MissionExecutor.java` (extracción + bifurcación), `MissionService.java` (teamId + borrado de workspace), `MissionMemoryService.java` (teamId, kind, artefactos), `TeamMemoryService.java` (`teamType`), `ProductStatusService.java`, `ChatIntentRouter.java`, `MissionController.java`
- Modify records `MissionCommand.java`, `MissionResponse.java`, `AgentTask.java`

**Infra y frontend:**
- Modify `app/src/main/resources/application.yml`, `app/Dockerfile`, `docker-compose.yml`
- Modify `app/frontend/src/api/types.ts`, `pages/MissionsPage.tsx`, `pages/MissionDetailPage.tsx`, `statusColor.ts`

**Docs:** `docs/EVENTS.md`, `CLAUDE.md`, `docs/HISTORY.md`

---

### Task 1: Contratos nuevos

**Files:**
- Create: `app/src/main/java/com/aicompany/core/agent/model/DevelopmentResult.java`
- Create: `app/src/main/java/com/aicompany/core/agent/model/DevelopmentResultSchema.java`
- Create: `app/src/main/java/com/aicompany/core/agent/model/TeamPlan.java`
- Create: `app/src/main/java/com/aicompany/core/agent/model/TeamPlanSchema.java`
- Create: `app/src/main/java/com/aicompany/core/agent/model/StaticReviewResult.java`
- Create: `app/src/main/java/com/aicompany/core/agent/model/StaticReviewResultSchema.java`
- Create: `app/src/main/java/com/aicompany/core/model/TeamExecutionMode.java`
- Create: `app/src/main/java/com/aicompany/core/model/StaticCheck.java`
- Create: `app/src/main/java/com/aicompany/core/model/StaticValidationStatus.java`
- Test: `app/src/test/java/com/aicompany/core/model/StaticValidationStatusTest.java`

**Interfaces:**
- Produces: `DevelopmentResult(String summary, List<GeneratedFile> files)` + `GeneratedFile(String path, String content)`; `TeamPlan(String summary, String techStack, String entryPoint, List<PlannedTask> tasks)` con `KIND_WORK`/`KIND_VALIDATION`, `tasksOrEmpty()`, `workTasks()`, `validationTask()`; `PlannedTask(String agentId, String kind, String action, String objective, List<String> requiredCapabilities, List<String> ownedPaths)` con `requiredCapabilitiesOrEmpty()`/`ownedPathsOrEmpty()`; `StaticReviewResult(String verdict, List<Finding> findings, List<String> missingFiles, String architectureConsistency, List<String> notValidatableWithoutExecution, List<AgentResult.Evidence> evidence)` + `Finding(String path, String severity, String description)`, `findingsOrEmpty()`, `hasBlocker()`; `*Schema.SCHEMA` (`Map<String, Object>`); `TeamExecutionMode {ANALYSIS, DEVELOPMENT}` + `forTeamType(String)`; `StaticCheck(String check, String status, String detail, String sha, List<String> paths)` + `pass(...)`/`fail(...)`/`passed()`; `StaticValidationStatus {STATICALLY_VALIDATED, UNVALIDATED, FAILED}` + `compute(List<StaticCheck>, StaticReviewResult)`.

- [ ] **Step 1: Escribir el test de `StaticValidationStatus` y `TeamExecutionMode` (falla: las clases no existen)**

```java
package com.aicompany.core.model;

import com.aicompany.core.agent.model.StaticReviewResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StaticValidationStatusTest {

    private static StaticReviewResult review(String severity) {
        var findings = severity == null
                ? List.<StaticReviewResult.Finding>of()
                : List.of(new StaticReviewResult.Finding("web/game.js", severity, "detalle"));
        return new StaticReviewResult("ISSUES_FOUND", findings, List.of(), "coherente",
                List.of("No se puede verificar la ejecución."), List.of());
    }

    private static final List<StaticCheck> ALL_PASS = List.of(
            StaticCheck.pass("COMMIT_EXISTS", "ok", "abc", List.of()),
            StaticCheck.pass("ENTRY_POINT", "ok", null, List.of("web/index.html"))
    );

    @Test
    void staticallyValidatedWhenChecksPassAndReviewHasNoBlocker() {
        assertEquals(StaticValidationStatus.STATICALLY_VALIDATED,
                StaticValidationStatus.compute(ALL_PASS, review("MAJOR")));
    }

    @Test
    void failedWhenAnyDeterministicCheckFails() {
        var checks = List.of(
                StaticCheck.pass("COMMIT_EXISTS", "ok", "abc", List.of()),
                StaticCheck.fail("ENTRY_POINT", "no existe", null, List.of("web/index.html"))
        );
        assertEquals(StaticValidationStatus.FAILED, StaticValidationStatus.compute(checks, review(null)));
    }

    @Test
    void failedWhenReviewReportsABlocker() {
        assertEquals(StaticValidationStatus.FAILED,
                StaticValidationStatus.compute(ALL_PASS, review("BLOCKER")));
    }

    @Test
    void unvalidatedWhenChecksPassButReviewIsMissing() {
        assertEquals(StaticValidationStatus.UNVALIDATED, StaticValidationStatus.compute(ALL_PASS, null));
    }

    @Test
    void failedWhenThereAreNoChecksAtAll() {
        assertEquals(StaticValidationStatus.FAILED, StaticValidationStatus.compute(List.of(), review(null)));
    }

    @Test
    void onlyEngineeringUsesTheDevelopmentMode() {
        assertEquals(TeamExecutionMode.DEVELOPMENT, TeamExecutionMode.forTeamType("ENGINEERING"));
        assertEquals(TeamExecutionMode.ANALYSIS, TeamExecutionMode.forTeamType("MARKETING_GROWTH"));
        assertEquals(TeamExecutionMode.ANALYSIS, TeamExecutionMode.forTeamType("CREATIVE_PRODUCT_INTELLIGENCE"));
    }
}
```

- [ ] **Step 2: Correr el test para confirmar que falla**

Run: `cd app && mvn test -Dtest=StaticValidationStatusTest`
Expected: FAIL de compilación (`StaticValidationStatus`, `StaticCheck`, `StaticReviewResult`, `TeamExecutionMode` no existen).

- [ ] **Step 3: Crear `DevelopmentResult` y `DevelopmentResultSchema`**

```java
package com.aicompany.core.agent.model;

import java.util.List;

/**
 * Contrato de una tarea de DESARROLLO real — separado de {@link AgentResult}
 * (discovery). Ver docs/superpowers/specs/2026-09-21-development-generation-design.md §6.
 */
public record DevelopmentResult(
        String summary,
        List<GeneratedFile> files
) {
    public record GeneratedFile(String path, String content) {
    }
}
```

```java
package com.aicompany.core.agent.model;

import java.util.List;
import java.util.Map;

/** JSON Schema formal de {@link DevelopmentResult}, usado como "format" en Ollama. */
public final class DevelopmentResultSchema {

    private DevelopmentResultSchema() {
    }

    private static final Map<String, Object> FILE_ITEM_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "path", Map.of("type", "string", "minLength", 1),
                    "content", Map.of("type", "string", "minLength", 1)
            ),
            "required", List.of("path", "content"),
            "additionalProperties", false
    );

    public static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "summary", Map.of("type", "string", "minLength", 1),
                    "files", Map.of("type", "array", "items", FILE_ITEM_SCHEMA, "minItems", 1)
            ),
            "required", List.of("summary", "files"),
            "additionalProperties", false
    );
}
```

- [ ] **Step 4: Crear `TeamPlan` y `TeamPlanSchema`**

```java
package com.aicompany.core.agent.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Plan de trabajo del líder de un equipo ({@code TeamWorkPlanner}). Nunca
 * se usa sin pasar antes por {@code TeamPlanValidator}. Ver spec §3-4.
 */
public record TeamPlan(
        String summary,
        String techStack,
        String entryPoint,
        List<PlannedTask> tasks
) {

    public static final String KIND_WORK = "WORK";
    public static final String KIND_VALIDATION = "VALIDATION";

    public record PlannedTask(
            String agentId,
            String kind,
            String action,
            String objective,
            List<String> requiredCapabilities,
            List<String> ownedPaths
    ) {
        public List<String> requiredCapabilitiesOrEmpty() {
            return requiredCapabilities == null ? List.of() : requiredCapabilities;
        }

        public List<String> ownedPathsOrEmpty() {
            return ownedPaths == null ? List.of() : ownedPaths;
        }
    }

    public List<PlannedTask> tasksOrEmpty() {
        return tasks == null ? List.of() : tasks;
    }

    public List<PlannedTask> workTasks() {
        return tasksOrEmpty().stream()
                .filter(Objects::nonNull)
                .filter(t -> KIND_WORK.equals(t.kind()))
                .toList();
    }

    public Optional<PlannedTask> validationTask() {
        return tasksOrEmpty().stream()
                .filter(Objects::nonNull)
                .filter(t -> KIND_VALIDATION.equals(t.kind()))
                .findFirst();
    }
}
```

```java
package com.aicompany.core.agent.model;

import java.util.List;
import java.util.Map;

/** JSON Schema formal de {@link TeamPlan}. techStack/entryPoint pueden ir vacíos (equipos de análisis). */
public final class TeamPlanSchema {

    private TeamPlanSchema() {
    }

    private static final Map<String, Object> TASK_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "agentId", Map.of("type", "string", "minLength", 1),
                    "kind", Map.of("type", "string", "enum", List.of(TeamPlan.KIND_WORK, TeamPlan.KIND_VALIDATION)),
                    "action", Map.of("type", "string", "minLength", 1),
                    "objective", Map.of("type", "string", "minLength", 1),
                    "requiredCapabilities", Map.of(
                            "type", "array", "items", Map.of("type", "string"), "minItems", 1),
                    "ownedPaths", Map.of("type", "array", "items", Map.of("type", "string"))
            ),
            "required", List.of("agentId", "kind", "action", "objective", "requiredCapabilities", "ownedPaths"),
            "additionalProperties", false
    );

    public static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "summary", Map.of("type", "string", "minLength", 1),
                    "techStack", Map.of("type", "string"),
                    "entryPoint", Map.of("type", "string"),
                    "tasks", Map.of("type", "array", "items", TASK_SCHEMA, "minItems", 1)
            ),
            "required", List.of("summary", "techStack", "entryPoint", "tasks"),
            "additionalProperties", false
    );
}
```

- [ ] **Step 5: Crear `StaticReviewResult` y `StaticReviewResultSchema`**

```java
package com.aicompany.core.agent.model;

import java.util.List;
import java.util.Objects;

/**
 * Revisión ESTÁTICA del código generado (capa 2 de la validación, spec §7).
 * El estado final ({@code StaticValidationStatus}) lo calcula Java, no este record.
 */
public record StaticReviewResult(
        String verdict,
        List<Finding> findings,
        List<String> missingFiles,
        String architectureConsistency,
        List<String> notValidatableWithoutExecution,
        List<AgentResult.Evidence> evidence
) {

    public record Finding(String path, String severity, String description) {
    }

    public List<Finding> findingsOrEmpty() {
        return findings == null ? List.of() : findings;
    }

    public boolean hasBlocker() {
        return findingsOrEmpty().stream()
                .filter(Objects::nonNull)
                .anyMatch(f -> "BLOCKER".equals(f.severity()));
    }
}
```

```java
package com.aicompany.core.agent.model;

import java.util.List;
import java.util.Map;

/** JSON Schema formal de {@link StaticReviewResult}. */
public final class StaticReviewResultSchema {

    private StaticReviewResultSchema() {
    }

    private static final Map<String, Object> STRING_ARRAY =
            Map.of("type", "array", "items", Map.of("type", "string"));

    private static final Map<String, Object> FINDING_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "path", Map.of("type", "string"),
                    "severity", Map.of("type", "string", "enum", List.of("BLOCKER", "MAJOR", "MINOR")),
                    "description", Map.of("type", "string", "minLength", 1)
            ),
            "required", List.of("path", "severity", "description"),
            "additionalProperties", false
    );

    private static final Map<String, Object> EVIDENCE_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "description", Map.of("type", "string", "minLength", 1),
                    "source", Map.of("type", "string"),
                    "sourceType", Map.of("type", "string",
                            "enum", List.of("WEB", "CUSTOMER", "TRANSACTION", "INTERNAL", "NONE")),
                    "verified", Map.of("type", "boolean")
            ),
            "required", List.of("description", "source", "sourceType", "verified"),
            "additionalProperties", false
    );

    public static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "verdict", Map.of("type", "string", "enum", List.of("NO_EVIDENT_ISSUES", "ISSUES_FOUND")),
                    "findings", Map.of("type", "array", "items", FINDING_SCHEMA),
                    "missingFiles", STRING_ARRAY,
                    "architectureConsistency", Map.of("type", "string", "minLength", 1),
                    "notValidatableWithoutExecution", Map.of(
                            "type", "array", "items", Map.of("type", "string"), "minItems", 1),
                    "evidence", Map.of("type", "array", "items", EVIDENCE_SCHEMA, "minItems", 1)
            ),
            "required", List.of("verdict", "findings", "missingFiles", "architectureConsistency",
                    "notValidatableWithoutExecution", "evidence"),
            "additionalProperties", false
    );
}
```

- [ ] **Step 6: Crear `TeamExecutionMode`, `StaticCheck`, `StaticValidationStatus`**

```java
package com.aicompany.core.model;

/** Estrategia de ejecución de un equipo, derivada de {@code Team.type} (catálogo fijo en código). */
public enum TeamExecutionMode {
    ANALYSIS,
    DEVELOPMENT;

    public static TeamExecutionMode forTeamType(String teamType) {
        return "ENGINEERING".equals(teamType) ? DEVELOPMENT : ANALYSIS;
    }
}
```

```java
package com.aicompany.core.model;

import java.util.List;

/** Resultado de un chequeo determinista de la capa 1 (StaticWorkspaceValidator). */
public record StaticCheck(
        String check,
        String status,
        String detail,
        String sha,
        List<String> paths
) {

    public static final String PASS = "PASS";
    public static final String FAIL = "FAIL";

    public static StaticCheck pass(String check, String detail, String sha, List<String> paths) {
        return new StaticCheck(check, PASS, detail, sha, paths == null ? List.of() : List.copyOf(paths));
    }

    public static StaticCheck fail(String check, String detail, String sha, List<String> paths) {
        return new StaticCheck(check, FAIL, detail, sha, paths == null ? List.of() : List.copyOf(paths));
    }

    public boolean passed() {
        return PASS.equals(status);
    }
}
```

```java
package com.aicompany.core.model;

import com.aicompany.core.agent.model.StaticReviewResult;

import java.util.List;

/**
 * Estado de la validación estática, calculado por Java (spec §7) — nunca
 * autodeclarado por el agente validador.
 */
public enum StaticValidationStatus {
    STATICALLY_VALIDATED,
    UNVALIDATED,
    FAILED;

    public static StaticValidationStatus compute(List<StaticCheck> checks, StaticReviewResult review) {

        if (checks == null || checks.isEmpty() || checks.stream().anyMatch(c -> !c.passed())) {
            return FAILED;
        }

        if (review == null) {
            return UNVALIDATED;
        }

        return review.hasBlocker() ? FAILED : STATICALLY_VALIDATED;
    }
}
```

- [ ] **Step 7: Correr el test y la suite**

Run: `cd app && mvn test -Dtest=StaticValidationStatusTest` → PASS (6 tests).
Run: `cd app && mvn test` → BUILD SUCCESS, 0 failures.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/aicompany/core/agent/model/ app/src/main/java/com/aicompany/core/model/TeamExecutionMode.java \
        app/src/main/java/com/aicompany/core/model/StaticCheck.java app/src/main/java/com/aicompany/core/model/StaticValidationStatus.java \
        app/src/test/java/com/aicompany/core/model/StaticValidationStatusTest.java
git commit -m "Contratos de misiones por equipo: TeamPlan, DevelopmentResult, StaticReviewResult, StaticValidationStatus" \
           -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: `OwnedPaths` + `DevelopmentPathValidationGate`

**Files:**
- Create: `app/src/main/java/com/aicompany/core/agent/validation/OwnedPaths.java`
- Create: `app/src/main/java/com/aicompany/core/agent/validation/DevelopmentPathValidationGate.java`
- Test: `app/src/test/java/com/aicompany/core/agent/validation/OwnedPathsTest.java`
- Test: `app/src/test/java/com/aicompany/core/agent/validation/DevelopmentPathValidationGateTest.java`

**Interfaces:**
- Consumes: `DevelopmentResult` (Task 1).
- Produces: `OwnedPaths.normalize(String)`, `isSafe(String)`, `covers(String owned, String path)`, `coveredByAny(List<String> owned, String path)`, `overlap(String a, String b)` (todos `static`); `DevelopmentPathValidationGate.validate(DevelopmentResult) -> ValidationResult(boolean valid, List<String> errors)`.

- [ ] **Step 1: Escribir los tests (fallan: las clases no existen)**

```java
package com.aicompany.core.agent.validation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OwnedPathsTest {

    @Test
    void normalizesLeadingDotSlashAndTrailingSlash() {
        assertEquals("web/ui", OwnedPaths.normalize("./web/ui/"));
    }

    @Test
    void coversTheExactFileAndEverythingUnderAFolder() {
        assertTrue(OwnedPaths.covers("web/ui", "web/ui/hud.js"));
        assertTrue(OwnedPaths.covers("web/index.html", "web/index.html"));
        assertFalse(OwnedPaths.covers("web/ui", "web/uikit/x.js"));
        assertFalse(OwnedPaths.covers("web/ui", "web/game/x.js"));
    }

    @Test
    void detectsOverlapInBothDirections() {
        assertTrue(OwnedPaths.overlap("web", "web/ui"));
        assertTrue(OwnedPaths.overlap("web/ui", "web"));
        assertFalse(OwnedPaths.overlap("web/ui", "web/game"));
    }

    @Test
    void unsafePathsAreRejected() {
        assertFalse(OwnedPaths.isSafe("/etc"));
        assertFalse(OwnedPaths.isSafe("C:\\x"));
        assertFalse(OwnedPaths.isSafe("../x"));
        assertFalse(OwnedPaths.isSafe("web\\ui"));
        assertFalse(OwnedPaths.isSafe(".git/config"));
        assertFalse(OwnedPaths.isSafe("   "));
        assertTrue(OwnedPaths.isSafe("web/ui"));
    }

    @Test
    void coveredByAnyHandlesNullOwnedList() {
        assertFalse(OwnedPaths.coveredByAny(null, "web/x.js"));
        assertTrue(OwnedPaths.coveredByAny(List.of("docs", "web"), "web/x.js"));
    }
}
```

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevelopmentPathValidationGateTest {

    private final DevelopmentPathValidationGate gate = new DevelopmentPathValidationGate();

    private DevelopmentResult resultWithPath(String path) {
        return new DevelopmentResult("resumen", List.of(new DevelopmentResult.GeneratedFile(path, "contenido")));
    }

    @Test
    void acceptsARealRelativePath() {
        assertTrue(gate.validate(resultWithPath("src/backend/Program.cs")).valid());
    }

    @Test
    void rejectsAnAbsolutePath() {
        assertFalse(gate.validate(resultWithPath("/etc/passwd")).valid());
    }

    @Test
    void rejectsAWindowsStyleAbsolutePath() {
        assertFalse(gate.validate(resultWithPath("C:\\Windows\\System32\\evil.dll")).valid());
    }

    @Test
    void rejectsPathTraversal() {
        assertFalse(gate.validate(resultWithPath("../../etc/passwd")).valid());
        assertFalse(gate.validate(resultWithPath("src/../../secrets.txt")).valid());
    }

    @Test
    void rejectsABlankPath() {
        assertFalse(gate.validate(resultWithPath("   ")).valid());
    }

    // Review Focus: una ruta dentro de .git podría reescribir la config o los hooks del repo.
    @Test
    void rejectsPathsInsideGitInternals() {
        assertFalse(gate.validate(resultWithPath(".git/config")).valid());
        assertFalse(gate.validate(resultWithPath("src/.git/hooks/pre-commit")).valid());
    }

    @Test
    void treatsANullResultAsValid() {
        assertTrue(gate.validate(null).valid());
    }
}
```

- [ ] **Step 2: Correr los tests para confirmar que fallan**

Run: `cd app && mvn test -Dtest='OwnedPathsTest,DevelopmentPathValidationGateTest'`
Expected: FAIL de compilación.

- [ ] **Step 3: Implementar `OwnedPaths`**

```java
package com.aicompany.core.agent.validation;

import java.util.List;

/**
 * Reglas puras sobre rutas relativas de un workspace de misión: seguridad,
 * pertenencia a los ownedPaths de un agente y solapamiento entre agentes.
 * Un ownedPath puede ser un archivo ("web/index.html") o una carpeta ("web/ui").
 */
public final class OwnedPaths {

    private OwnedPaths() {
    }

    public static String normalize(String path) {

        if (path == null) {
            return "";
        }

        var p = path.strip();

        while (p.startsWith("./")) {
            p = p.substring(2);
        }

        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }

        return p;
    }

    public static boolean isSafe(String path) {

        var p = normalize(path);

        if (p.isEmpty() || p.startsWith("/") || p.matches("^[a-zA-Z]:.*") || p.contains("\\")) {
            return false;
        }

        for (var segment : p.split("/")) {
            if (segment.isEmpty() || segment.equals("..") || segment.equals(".git")) {
                return false;
            }
        }

        return true;
    }

    public static boolean covers(String owned, String path) {

        var o = normalize(owned);
        var q = normalize(path);

        if (o.isEmpty() || q.isEmpty()) {
            return false;
        }

        return q.equals(o) || q.startsWith(o + "/");
    }

    public static boolean coveredByAny(List<String> owned, String path) {
        return owned != null && owned.stream().anyMatch(o -> covers(o, path));
    }

    public static boolean overlap(String a, String b) {
        return covers(a, b) || covers(b, a);
    }
}
```

- [ ] **Step 4: Implementar `DevelopmentPathValidationGate`**

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Gate de seguridad antes de escribir cualquier archivo a disco — mismo
 * espíritu que {@link EvidenceValidationGate}: rechaza de inmediato, SIN
 * reintento (spec §6). Las rutas fuera de los ownedPaths del agente NO son
 * responsabilidad de este gate (son corregibles y se reintentan en
 * {@code DevelopmentRuntime}).
 */
@Component
public class DevelopmentPathValidationGate {

    private static final Logger log = LoggerFactory.getLogger(DevelopmentPathValidationGate.class);

    public ValidationResult validate(DevelopmentResult result) {

        if (result == null || result.files() == null) {
            return new ValidationResult(true, List.of());
        }

        var errors = new ArrayList<String>();

        for (var file : result.files()) {
            if (file != null) {
                validatePath(file.path(), errors);
            }
        }

        if (!errors.isEmpty()) {
            log.warn("DEVELOPMENT_PATH_INVALID errors={}", errors);
            return new ValidationResult(false, List.copyOf(errors));
        }

        return new ValidationResult(true, List.of());
    }

    private void validatePath(String path, List<String> errors) {

        if (path == null || path.isBlank()) {
            errors.add("Ruta de archivo vacía.");
            return;
        }

        if (path.startsWith("/") || path.matches("^[a-zA-Z]:.*")) {
            errors.add("Ruta absoluta no permitida: \"" + path + "\"");
            return;
        }

        for (var segment : path.split("[/\\\\]")) {

            if (segment.equals("..")) {
                errors.add("Ruta con path traversal no permitida: \"" + path + "\"");
                return;
            }

            if (segment.equals(".git")) {
                errors.add("Ruta dentro de .git no permitida: \"" + path + "\"");
                return;
            }
        }
    }

    public record ValidationResult(boolean valid, List<String> errors) {
    }
}
```

- [ ] **Step 5: Correr los tests**

Run: `cd app && mvn test -Dtest='OwnedPathsTest,DevelopmentPathValidationGateTest'` → PASS (12 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aicompany/core/agent/validation/OwnedPaths.java \
        app/src/main/java/com/aicompany/core/agent/validation/DevelopmentPathValidationGate.java \
        app/src/test/java/com/aicompany/core/agent/validation/OwnedPathsTest.java \
        app/src/test/java/com/aicompany/core/agent/validation/DevelopmentPathValidationGateTest.java
git commit -m "Agregar OwnedPaths y DevelopmentPathValidationGate (rutas absolutas, .., .git sin reintento)" \
           -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: `TeamPlanValidator` (TDD)

**Files:**
- Create: `app/src/main/java/com/aicompany/core/agent/validation/TeamPlanValidator.java`
- Test: `app/src/test/java/com/aicompany/core/agent/validation/TeamPlanValidatorTest.java`

**Interfaces:**
- Consumes: `TeamPlan`, `TeamExecutionMode` (Task 1); `OwnedPaths` (Task 2); `TeamSnapshot(String teamId, String teamName, String status, String leaderAgentId, List<TeamMemberInfo> members)` y `TeamMemberInfo(String agentId, String name, String role, String roleCode, List<String> capabilities, String model)` (ya existen en `com.aicompany.core.model`).
- Produces: `TeamPlanValidator.validate(TeamPlan plan, TeamSnapshot team, TeamExecutionMode mode) -> List<String>` (vacía = válido).

- [ ] **Step 1: Escribir el test (falla: la clase no existe)**

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.TeamPlan;
import com.aicompany.core.agent.model.TeamPlan.PlannedTask;
import com.aicompany.core.model.TeamExecutionMode;
import com.aicompany.core.model.TeamMemberInfo;
import com.aicompany.core.model.TeamSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TeamPlanValidatorTest {

    private final TeamPlanValidator validator = new TeamPlanValidator();

    private static TeamMemberInfo member(String id, String name, List<String> capabilities) {
        return new TeamMemberInfo(id, name, "rol", "ROLE", capabilities, "qwen3:8b");
    }

    private static TeamSnapshot engineeringTeam() {
        return new TeamSnapshot("TEAM-ENGINEERING", "Engineering Team", "ACTIVE", "engineering", List.of(
                member("engineering", "Neo", List.of("arquitectura backend", "descomposición técnica del trabajo")),
                member("qa", "Vera", List.of("QA", "análisis de errores")),
                member("devops", "Diego", List.of("infraestructura", "SRE")),
                member("backend", "Iris", List.of("backend", "lógica de negocio")),
                member("frontend-ui", "Mila", List.of("frontend", "Game UI"))
        ));
    }

    private static List<PlannedTask> validTasks() {
        return new ArrayList<>(List.of(
                new PlannedTask("engineering", "WORK", "ARCHITECTURE", "Estructura base",
                        List.of("arquitectura backend"), List.of("web/index.html", "docs")),
                new PlannedTask("frontend-ui", "WORK", "GAME_UI", "HUD y menús",
                        List.of("Game UI"), List.of("web/ui")),
                new PlannedTask("backend", "WORK", "GAME_LOGIC", "Lógica del juego",
                        List.of("lógica de negocio"), List.of("web/game")),
                new PlannedTask("devops", "WORK", "DEV_INFRA", "Scripts de servidor local",
                        List.of("infraestructura"), List.of("infra")),
                new PlannedTask("qa", "VALIDATION", "STATIC_REVIEW", "Revisar el código",
                        List.of("QA"), List.of())
        ));
    }

    private static TeamPlan plan(List<PlannedTask> tasks) {
        return new TeamPlan("Juego de navegador", "HTML5 + JavaScript", "web/index.html", tasks);
    }

    private List<String> validateDev(TeamPlan plan) {
        return validator.validate(plan, engineeringTeam(), TeamExecutionMode.DEVELOPMENT);
    }

    @Test
    void acceptsAValidDevelopmentPlan() {
        assertEquals(List.of(), validateDev(plan(validTasks())));
    }

    @Test
    void rejectsAnAgentOutsideTheTeam() {
        var tasks = validTasks();
        tasks.add(new PlannedTask("finance", "WORK", "COSTS", "Costos", List.of("finanzas"), List.of("costs")));
        var errors = validateDev(plan(tasks));
        assertTrue(errors.stream().anyMatch(e -> e.contains("finance") && e.contains("no es miembro")), errors.toString());
    }

    @Test
    void rejectsAnInventedCapability() {
        var tasks = validTasks();
        tasks.set(1, new PlannedTask("frontend-ui", "WORK", "GAME_UI", "HUD", List.of("Unity experto"), List.of("web/ui")));
        var errors = validateDev(plan(tasks));
        assertTrue(errors.stream().anyMatch(e -> e.contains("Unity experto")), errors.toString());
    }

    @Test
    void rejectsOverlappingOwnedPaths() {
        var tasks = validTasks();
        tasks.set(2, new PlannedTask("backend", "WORK", "GAME_LOGIC", "Lógica", List.of("lógica de negocio"), List.of("web")));
        var errors = validateDev(plan(tasks));
        assertTrue(errors.stream().anyMatch(e -> e.contains("se solapan")), errors.toString());
    }

    @Test
    void requiresEveryMemberInDevelopmentMode() {
        var tasks = validTasks();
        tasks.removeIf(t -> t.agentId().equals("devops"));
        var errors = validateDev(plan(tasks));
        assertTrue(errors.stream().anyMatch(e -> e.contains("devops")), errors.toString());
    }

    @Test
    void requiresExactlyOneValidationTask() {
        var tasks = validTasks();
        tasks.removeIf(t -> t.kind().equals("VALIDATION"));
        tasks.add(new PlannedTask("qa", "WORK", "TESTS", "Tests", List.of("QA"), List.of("tests")));
        var errors = validateDev(plan(tasks));
        assertTrue(errors.stream().anyMatch(e -> e.contains("exactamente una tarea VALIDATION")), errors.toString());
    }

    @Test
    void validationMustGoToAMemberWithQaCapability() {
        var tasks = validTasks();
        tasks.removeIf(t -> t.agentId().equals("qa") || t.agentId().equals("backend"));
        tasks.add(new PlannedTask("backend", "VALIDATION", "STATIC_REVIEW", "Revisar", List.of("backend"), List.of()));
        tasks.add(new PlannedTask("qa", "WORK", "TESTS", "Tests", List.of("QA"), List.of("tests")));
        var errors = validateDev(plan(tasks));
        assertTrue(errors.stream().anyMatch(e -> e.contains("capability QA")), errors.toString());
    }

    @Test
    void entryPointMustBeInsideSomeWorkOwnedPaths() {
        var errors = validateDev(new TeamPlan("x", "HTML5", "main.js", validTasks()));
        assertTrue(errors.stream().anyMatch(e -> e.contains("entryPoint")), errors.toString());
    }

    @Test
    void rejectsUnsafeOwnedPaths() {
        var tasks = validTasks();
        tasks.set(3, new PlannedTask("devops", "WORK", "DEV_INFRA", "Infra", List.of("infraestructura"), List.of("../infra")));
        var errors = validateDev(plan(tasks));
        assertTrue(errors.stream().anyMatch(e -> e.contains("../infra")), errors.toString());
    }

    @Test
    void rejectsTwoTasksForTheSameAgent() {
        var tasks = validTasks();
        tasks.add(new PlannedTask("backend", "WORK", "MORE", "Más", List.of("backend"), List.of("web/extra")));
        var errors = validateDev(plan(tasks));
        assertTrue(errors.stream().anyMatch(e -> e.contains("más de una tarea")), errors.toString());
    }

    @Test
    void rejectsActionsThatAreNotUpperSnakeCase() {
        var tasks = validTasks();
        tasks.set(0, new PlannedTask("engineering", "WORK", "architecture", "Base",
                List.of("arquitectura backend"), List.of("web/index.html")));
        var errors = validateDev(plan(tasks));
        assertTrue(errors.stream().anyMatch(e -> e.contains("action")), errors.toString());
    }

    @Test
    void analysisModeRejectsValidationTasksButAllowsPartialTeams() {
        var marketing = new TeamSnapshot("TEAM-MARKETING-GROWTH", "Marketing & Growth", "ACTIVE", "growth-content", List.of(
                member("growth-content", "Kira", List.of("growth", "SEO")),
                member("community", "Nora", List.of("Discord", "moderación"))
        ));
        var onlyKira = new TeamPlan("Plan", "", "", List.of(
                new PlannedTask("growth-content", "WORK", "SEO_PLAN", "Plan SEO", List.of("SEO"), List.of())));
        assertEquals(List.of(), validator.validate(onlyKira, marketing, TeamExecutionMode.ANALYSIS));

        var withValidation = new TeamPlan("Plan", "", "", List.of(
                new PlannedTask("growth-content", "WORK", "SEO_PLAN", "Plan SEO", List.of("SEO"), List.of()),
                new PlannedTask("community", "VALIDATION", "REVIEW", "Revisar", List.of("moderación"), List.of())));
        var errors = validator.validate(withValidation, marketing, TeamExecutionMode.ANALYSIS);
        assertTrue(errors.stream().anyMatch(e -> e.contains("VALIDATION")), errors.toString());
    }
}
```

- [ ] **Step 2: Correr el test para confirmar que falla**

Run: `cd app && mvn test -Dtest=TeamPlanValidatorTest` → FAIL de compilación.

- [ ] **Step 3: Implementar `TeamPlanValidator`**

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.TeamPlan;
import com.aicompany.core.agent.model.TeamPlan.PlannedTask;
import com.aicompany.core.model.TeamExecutionMode;
import com.aicompany.core.model.TeamMemberInfo;
import com.aicompany.core.model.TeamSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Validación determinista del plan del líder (spec §4). Reglas comunes a
 * los 3 equipos + reglas propias de la estrategia de desarrollo. Nunca
 * llama a un modelo. Lista vacía = plan válido.
 */
@Component
public class TeamPlanValidator {

    public List<String> validate(TeamPlan plan, TeamSnapshot team, TeamExecutionMode mode) {

        var errors = new ArrayList<String>();

        if (plan == null) {
            errors.add("El plan está vacío.");
            return errors;
        }

        var membersById = new LinkedHashMap<String, TeamMemberInfo>();
        for (var member : team.members()) {
            membersById.put(member.agentId(), member);
        }

        if (plan.summary() == null || plan.summary().isBlank()) {
            errors.add("summary no puede estar vacío.");
        }

        validateCommonTaskRules(plan, team, membersById, errors);

        if (plan.workTasks().isEmpty()) {
            errors.add("El plan debe tener al menos una tarea WORK.");
        }

        if (mode == TeamExecutionMode.ANALYSIS) {
            if (plan.validationTask().isPresent()) {
                errors.add("Este equipo no admite tareas VALIDATION: usa kind=\"WORK\" en todas.");
            }
        } else {
            validateDevelopmentRules(plan, membersById, errors);
        }

        return errors;
    }

    private void validateCommonTaskRules(
            TeamPlan plan, TeamSnapshot team, Map<String, TeamMemberInfo> membersById, List<String> errors) {

        var seen = new HashSet<String>();

        for (var task : plan.tasksOrEmpty()) {

            if (task == null) {
                errors.add("El plan contiene una tarea nula.");
                continue;
            }

            var member = membersById.get(task.agentId());

            if (member == null) {
                errors.add("El agente \"" + task.agentId() + "\" no es miembro de " + team.teamId()
                        + ". Miembros válidos: " + membersById.keySet());
                continue;
            }

            if (!seen.add(task.agentId())) {
                errors.add("El agente " + task.agentId() + " tiene más de una tarea; asigna a lo sumo una por agente.");
            }

            if (!TeamPlan.KIND_WORK.equals(task.kind()) && !TeamPlan.KIND_VALIDATION.equals(task.kind())) {
                errors.add("kind inválido para " + task.agentId() + ": \"" + task.kind() + "\" (usa WORK o VALIDATION).");
            }

            if (task.action() == null || !task.action().matches("[A-Z_]+")) {
                errors.add("action de " + task.agentId() + " debe estar en MAYÚSCULAS_CON_GUIONES_BAJOS (recibido: \""
                        + task.action() + "\").");
            }

            if (task.objective() == null || task.objective().isBlank()) {
                errors.add("objective de " + task.agentId() + " no puede estar vacío.");
            }

            if (task.requiredCapabilitiesOrEmpty().isEmpty()) {
                errors.add("La tarea de " + task.agentId()
                        + " debe declarar requiredCapabilities copiadas textualmente de sus capabilities.");
            }

            for (var capability : task.requiredCapabilitiesOrEmpty()) {
                if (!member.capabilities().contains(capability)) {
                    errors.add("La capability \"" + capability + "\" no pertenece a " + task.agentId()
                            + ". Sus capabilities reales son: " + member.capabilities());
                }
            }
        }
    }

    private void validateDevelopmentRules(
            TeamPlan plan, Map<String, TeamMemberInfo> membersById, List<String> errors) {

        var assigned = plan.tasksOrEmpty().stream()
                .filter(Objects::nonNull)
                .map(PlannedTask::agentId)
                .toList();

        for (var memberId : membersById.keySet()) {
            if (!assigned.contains(memberId)) {
                errors.add("Falta una tarea para " + memberId
                        + ": en un equipo de desarrollo todos los miembros deben recibir exactamente una tarea.");
            }
        }

        var validationTasks = plan.tasksOrEmpty().stream()
                .filter(Objects::nonNull)
                .filter(t -> TeamPlan.KIND_VALIDATION.equals(t.kind()))
                .toList();

        if (validationTasks.size() != 1) {
            errors.add("Debe haber exactamente una tarea VALIDATION (hay " + validationTasks.size() + ").");
        } else {
            var validator = membersById.get(validationTasks.get(0).agentId());
            if (validator != null && !validator.capabilities().contains("QA")) {
                errors.add("La tarea VALIDATION debe asignarse a un miembro con la capability QA; "
                        + validator.agentId() + " no la tiene.");
            }
        }

        if (plan.techStack() == null || plan.techStack().isBlank()) {
            errors.add("techStack no puede estar vacío en un equipo de desarrollo.");
        }

        if (plan.entryPoint() == null || plan.entryPoint().isBlank()) {
            errors.add("entryPoint no puede estar vacío en un equipo de desarrollo.");
        }

        var work = plan.workTasks().stream()
                .filter(t -> membersById.containsKey(t.agentId()))
                .toList();

        for (var task : work) {

            if (task.ownedPathsOrEmpty().isEmpty()) {
                errors.add("La tarea WORK de " + task.agentId() + " debe declarar ownedPaths.");
            }

            for (var path : task.ownedPathsOrEmpty()) {
                if (!OwnedPaths.isSafe(path)) {
                    errors.add("ownedPath inseguro en " + task.agentId() + ": \"" + path
                            + "\" (sin rutas absolutas, \"..\", \"\\\" ni \".git\").");
                }
            }
        }

        for (int i = 0; i < work.size(); i++) {
            for (int j = i + 1; j < work.size(); j++) {
                var a = work.get(i);
                var b = work.get(j);
                for (var pathA : a.ownedPathsOrEmpty()) {
                    for (var pathB : b.ownedPathsOrEmpty()) {
                        if (OwnedPaths.overlap(pathA, pathB)) {
                            errors.add("Los ownedPaths \"" + pathA + "\" (" + a.agentId() + ") y \"" + pathB
                                    + "\" (" + b.agentId() + ") se solapan.");
                        }
                    }
                }
            }
        }

        if (plan.entryPoint() != null && !plan.entryPoint().isBlank()
                && work.stream().noneMatch(t -> OwnedPaths.coveredByAny(t.ownedPathsOrEmpty(), plan.entryPoint()))) {
            errors.add("entryPoint \"" + plan.entryPoint() + "\" no cae dentro de los ownedPaths de ninguna tarea WORK.");
        }
    }
}
```

- [ ] **Step 4: Correr el test**

Run: `cd app && mvn test -Dtest=TeamPlanValidatorTest` → PASS (12 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/agent/validation/TeamPlanValidator.java \
        app/src/test/java/com/aicompany/core/agent/validation/TeamPlanValidatorTest.java
git commit -m "Agregar TeamPlanValidator: reglas deterministas del plan del líder" \
           -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: `Mission.teamId` (backend, validado)

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/model/MissionCommand.java`
- Modify: `app/src/main/java/com/aicompany/core/model/MissionResponse.java`
- Modify: `app/src/main/java/com/aicompany/core/service/MissionMemoryService.java`
- Modify: `app/src/main/java/com/aicompany/core/service/TeamMemoryService.java`
- Modify: `app/src/main/java/com/aicompany/core/service/MissionService.java`
- Modify: `app/src/main/java/com/aicompany/core/controller/MissionController.java`
- Test: `app/src/test/java/com/aicompany/core/service/MissionServiceTest.java`

**Interfaces:**
- Produces: `MissionCommand.teamId()` + `teamIdOrNull()`; `MissionResponse.teamId()` (9º componente; se mantiene un constructor de 8 argumentos que pone `teamId=null`); `MissionMemoryService.ensureMission(String missionId, String instruction, String environment, FinancialCriteriaCommand fc, String teamId)` (reemplaza a la de 4 argumentos); `MissionMemoryService.teamId(String missionId) -> Optional<String>`; `TeamMemoryService.teamType(String teamId) -> Optional<String>` (`static`); `MissionService(MissionMemoryService, MissionExecutor, CompanyEventPublisher, TeamMemoryService)`; `MissionService.start(String, String, String, FinancialCriteriaCommand, String teamId)` (la de 4 argumentos queda y delega con `teamId=null`).

- [ ] **Step 1: Adaptar `MissionServiceTest` al constructor y firma nuevos, y agregar los tests nuevos**

Agregar el campo al inicio de la clase (justo después de `class MissionServiceTest {`):

```java
    private final TeamMemoryService teamMemory = mock(TeamMemoryService.class);
```

Actualizar todas las construcciones y las verificaciones de `ensureMission` (mecánico):

```bash
cd app
sed -i 's/new MissionService(\([^;]*\));/new MissionService(\1, teamMemory);/' src/test/java/com/aicompany/core/service/MissionServiceTest.java
sed -i 's/verify(memory).ensureMission("MISSION-001", "Investigar una oportunidad", "PRODUCTION", null);/verify(memory).ensureMission("MISSION-001", "Investigar una oportunidad", "PRODUCTION", null, null);/' src/test/java/com/aicompany/core/service/MissionServiceTest.java
sed -i 's/verify(memory).ensureMission("MISSION-001", "Investigar una oportunidad", "PRODUCTION", criteria);/verify(memory).ensureMission("MISSION-001", "Investigar una oportunidad", "PRODUCTION", criteria, null);/' src/test/java/com/aicompany/core/service/MissionServiceTest.java
sed -i 's/ensureMission(any(), any(), any(), any());/ensureMission(any(), any(), any(), any(), any());/' src/test/java/com/aicompany/core/service/MissionServiceTest.java
grep -n "new MissionService(\|ensureMission(" src/test/java/com/aicompany/core/service/MissionServiceTest.java
```

Expected del `grep`: todas las construcciones terminan en `, teamMemory)` y todos los `ensureMission` tienen 5 argumentos.

Agregar estos tests al final de la clase (antes de la última `}`); agregar los imports `com.aicompany.core.model.TeamMemberInfo`, `com.aicompany.core.model.TeamSnapshot` si no están:

```java
    private static TeamSnapshot activeEngineering() {
        return new TeamSnapshot("TEAM-ENGINEERING", "Engineering Team", "ACTIVE", "engineering", List.of(
                new TeamMemberInfo("engineering", "Neo", "Cloud Architect", "CLOUD_ARCHITECT_LEAD_BACKEND",
                        List.of("arquitectura backend"), "qwen3:8b")));
    }

    @Test
    void startRejectsAnUnknownTeamIdWithoutPersistingTheMission() {
        var memory = mock(MissionMemoryService.class);
        var service = new MissionService(memory, mock(MissionExecutor.class), mock(CompanyEventPublisher.class), teamMemory);

        assertThrows(IllegalArgumentException.class,
                () -> service.start("MISSION-7", "Crear un juego", "PRODUCTION", null, "TEAM-INVENTADO"));
        verify(memory, never()).ensureMission(any(), any(), any(), any(), any());
    }

    @Test
    void startRejectsATeamThatIsNotActive() {
        var memory = mock(MissionMemoryService.class);
        when(teamMemory.snapshot("TEAM-ENGINEERING")).thenReturn(
                new TeamSnapshot("TEAM-ENGINEERING", "Engineering Team", "INACTIVE", "engineering",
                        activeEngineering().members()));
        var service = new MissionService(memory, mock(MissionExecutor.class), mock(CompanyEventPublisher.class), teamMemory);

        assertThrows(IllegalArgumentException.class,
                () -> service.start("MISSION-7", "Crear un juego", "PRODUCTION", null, "TEAM-ENGINEERING"));
        verify(memory, never()).ensureMission(any(), any(), any(), any(), any());
    }

    @Test
    void startPersistsAValidActiveTeamId() {
        var memory = mock(MissionMemoryService.class);
        var executor = mock(MissionExecutor.class);
        when(executor.executeAsync(anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(teamMemory.snapshot("TEAM-ENGINEERING")).thenReturn(activeEngineering());
        var mission = new MissionResponse("MISSION-7", MissionStatus.CREATED, "PRODUCTION", 0, "Creada",
                "Misión recibida", Instant.now(), null, "TEAM-ENGINEERING");
        when(memory.find("MISSION-7")).thenReturn(Optional.of(mission));
        var service = new MissionService(memory, executor, mock(CompanyEventPublisher.class), teamMemory);

        var response = service.start("MISSION-7", "Crear un juego", "PRODUCTION", null, "TEAM-ENGINEERING");

        verify(memory).ensureMission("MISSION-7", "Crear un juego", "PRODUCTION", null, "TEAM-ENGINEERING");
        assertEquals("TEAM-ENGINEERING", response.teamId());
    }
```

(Si el archivo no importa todavía `CompletableFuture`, `Instant`, `Optional`, `MissionResponse`, `MissionStatus`, agregarlos.)

- [ ] **Step 2: Correr el test para confirmar que falla**

Run: `cd app && mvn test -Dtest=MissionServiceTest` → FAIL de compilación (constructor de 4 argumentos, `start` de 5, `MissionResponse` de 9 no existen).

- [ ] **Step 3: `MissionCommand` y `MissionResponse`**

`MissionCommand.java` completo (mantener los imports existentes):

```java
public record MissionCommand(
        @NotBlank String missionId,
        @NotBlank String instruction,
        String environment,
        FinancialCriteriaCommand financialCriteria,
        String teamId
) {
    public String environmentOrDefault() {
        return environment == null || environment.isBlank()
                ? "PRODUCTION"
                : environment.toUpperCase(Locale.ROOT);
    }

    /** Exacto (sin cambiar mayúsculas): el backend valida contra el catálogo fijo de equipos. */
    public String teamIdOrNull() {
        return teamId == null || teamId.isBlank() ? null : teamId.strip();
    }
}
```

`MissionResponse.java`:

```java
public record MissionResponse(
        String missionId,
        MissionStatus status,
        String environment,
        int progress,
        String currentStep,
        String message,
        Instant updatedAt,
        FinancialCriteriaResponse financialCriteria,
        String teamId
) {
    /** Misiones sin equipo (discovery) — mantiene compatibles los call-sites existentes. */
    public MissionResponse(
            String missionId, MissionStatus status, String environment, int progress,
            String currentStep, String message, Instant updatedAt, FinancialCriteriaResponse financialCriteria) {
        this(missionId, status, environment, progress, currentStep, message, updatedAt, financialCriteria, null);
    }
}
```

- [ ] **Step 4: `TeamMemoryService.teamType`**

Agregar debajo de `KNOWN_TEAM_IDS`:

```java
    /** Tipo real del equipo desde el catálogo fijo en código ({@link #TEAMS}); vacío si el id no existe. */
    public static Optional<String> teamType(String teamId) {
        return TEAMS.stream()
                .filter(team -> team.teamId().equals(teamId))
                .map(TeamDefinition::teamType)
                .findFirst();
    }
```

(Agregar `import java.util.Optional;`.)

- [ ] **Step 5: `MissionMemoryService` — persistir y leer `teamId`**

Reemplazar la firma y el cuerpo de `ensureMission` (mantener el comentario existente sobre `financialCriteria`):

```java
    public void ensureMission(
            String missionId,
            String instruction,
            String environment,
            FinancialCriteriaCommand financialCriteria,
            String teamId) {

        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                var financialCriteriaSet = financialCriteria == null
                        ? ""
                        : ", m.financialCriteriaMetric=$metric, m.financialCriteriaTargetAmount=$targetAmount, "
                                + "m.financialCriteriaCurrency=$currency, m.financialCriteriaDeadline=$deadline";
                // teamId es inmutable: mismo criterio que financialCriteria, un re-arranque
                // sin teamId nunca borra el ya declarado.
                var teamIdSet = teamId == null ? "" : ", m.teamId=$teamId";
                var params = financialCriteriaParams(missionId, instruction, environment, financialCriteria);
                params.put("teamId", teamId);
                tx.run("MERGE (m:Mission {id:$id}) SET m.name=$name, m.instruction=$instruction, "
                                + "m.environment=$environment, m.status='CREATED', m.progress=0, "
                                + "m.currentStep='Creada', m.message='Misión recibida', m.updatedAt=$updatedAt"
                                + financialCriteriaSet + teamIdSet,
                        params);
                tx.run("MATCH (m:Mission {id:$id}), (c:Company {id:'AI-COMPANY'}) MERGE (c)-[:HAS_MISSION]->(m)", Map.of("id", missionId));
                tx.run("MATCH (m:Mission {id:$id}), (a:Agent {id:'ceo'}) MERGE (m)-[:LED_BY]->(a)", Map.of("id", missionId));
                return null;
            });
        }
    }
```

(`financialCriteriaParams` ya devuelve un `HashMap` mutable, así que `params.put` funciona.)

Agregar el lookup dedicado y un helper, junto a `financialCriteria(...)`:

```java
    public Optional<String> teamId(String missionId) {
        try (var session = driver.session()) {
            return session.run("MATCH (m:Mission {id:$id}) RETURN m.teamId AS teamId", Map.of("id", missionId))
                    .list(r -> nullableString(r.get("teamId")))
                    .stream()
                    .filter(Objects::nonNull)
                    .findFirst();
        }
    }

    private static String nullableString(org.neo4j.driver.Value value) {
        return value == null || value.isNull() ? null : value.asString();
    }
```

(Agregar `import java.util.Objects;` si falta.)

En `find`, `findByIds` y `findAll`: agregar `, m.teamId AS teamId` al final de la cláusula `RETURN` de cada query, y pasar `nullableString(r.get("teamId"))` como **último** argumento de cada `new MissionResponse(...)` (queda con 9 argumentos). En `find` queda así:

```java
            var records = session.run("MATCH (m:Mission {id:$id}) RETURN m.status AS status, coalesce(m.environment, 'TEST') AS environment, m.progress AS progress, m.currentStep AS step, m.message AS message, m.updatedAt AS updatedAt, "
                            + "m.financialCriteriaMetric AS financialCriteriaMetric, m.financialCriteriaTargetAmount AS financialCriteriaTargetAmount, "
                            + "m.financialCriteriaCurrency AS financialCriteriaCurrency, m.financialCriteriaDeadline AS financialCriteriaDeadline, "
                            + "m.teamId AS teamId",
                    Map.of("id", missionId)).list();
            return records.stream().findFirst().map(r -> new MissionResponse(
                    missionId,
                    MissionStatus.valueOf(r.get("status").asString()),
                    r.get("environment").asString(),
                    r.get("progress").asInt(),
                    r.get("step").asString(),
                    r.get("message").asString(),
                    Instant.parse(r.get("updatedAt").asString()),
                    mapFinancialCriteria(r),
                    nullableString(r.get("teamId"))
            ));
```

Verificación: `grep -n "new MissionResponse(" app/src/main/java/com/aicompany/core/service/MissionMemoryService.java` → los 3 call-sites pasan `nullableString(r.get("teamId"))`.

- [ ] **Step 6: `MissionService` — constructor, `start` con `teamId` y validación**

Reemplazar campos, constructor y `start`:

```java
    private final MissionMemoryService memory;
    private final MissionExecutor executor;
    private final CompanyEventPublisher events;
    private final TeamMemoryService teamMemory;

    public MissionService(
            MissionMemoryService memory,
            MissionExecutor executor,
            CompanyEventPublisher events,
            TeamMemoryService teamMemory) {
        this.memory = memory;
        this.executor = executor;
        this.events = events;
        this.teamMemory = teamMemory;
    }

    public MissionResponse start(String missionId, String instruction, String environment, FinancialCriteriaCommand financialCriteria) {
        return start(missionId, instruction, environment, financialCriteria, null);
    }

    public MissionResponse start(
            String missionId,
            String instruction,
            String environment,
            FinancialCriteriaCommand financialCriteria,
            String teamId) {

        validateFinancialCriteria(financialCriteria);
        validateTeam(teamId);

        memory.ensureMission(missionId, instruction, environment, financialCriteria, teamId);

        events.publishMission(
                "EMPRESA_MISSION_CREATED",
                missionId,
                "CREATED",
                0,
                "Creada",
                "Misión recibida"
        );

        executor.executeAsync(missionId, instruction)
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        log.error("MISSION {} - async future failed", missionId, error);
                    } else {
                        log.info("MISSION {} - async orchestration finished", missionId);
                    }
                });

        return memory.find(missionId).orElseThrow();
    }

    /**
     * teamId es opcional; si viene, tiene que ser uno de los 3 equipos del
     * catálogo fijo, existir en Neo4j con status ACTIVE y tener líder y
     * miembros reales (spec §1). Nunca lo decide ni lo corrige un modelo.
     */
    private void validateTeam(String teamId) {

        if (teamId == null) {
            return;
        }

        if (!TeamMemoryService.KNOWN_TEAM_IDS.contains(teamId)) {
            throw new IllegalArgumentException("teamId desconocido: " + teamId
                    + ". Valores válidos: " + TeamMemoryService.KNOWN_TEAM_IDS);
        }

        var team = teamMemory.snapshot(teamId);

        if (team == null || !"ACTIVE".equals(team.status())) {
            throw new IllegalArgumentException("El equipo " + teamId + " no existe o no está ACTIVE en Company Memory.");
        }

        if (team.leaderAgentId() == null || team.members().isEmpty()) {
            throw new IllegalArgumentException("El equipo " + teamId + " no tiene líder o miembros en Company Memory.");
        }
    }
```

- [ ] **Step 7: `MissionController`**

Reemplazar la llamada existente por:

```java
                missionService.start(command.missionId(), command.instruction(), command.environmentOrDefault(),
                        command.financialCriteria(), command.teamIdOrNull())
```

- [ ] **Step 8: Correr los tests**

Run: `cd app && mvn test -Dtest=MissionServiceTest` → PASS.
Run: `cd app && mvn test` → BUILD SUCCESS, 0 failures (los 37 call-sites de `new MissionResponse(` en `ChatIntentRouterTest` siguen compilando por el constructor de 8 argumentos).

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/aicompany/core/model/MissionCommand.java app/src/main/java/com/aicompany/core/model/MissionResponse.java \
        app/src/main/java/com/aicompany/core/service/MissionMemoryService.java app/src/main/java/com/aicompany/core/service/TeamMemoryService.java \
        app/src/main/java/com/aicompany/core/service/MissionService.java app/src/main/java/com/aicompany/core/controller/MissionController.java \
        app/src/test/java/com/aicompany/core/service/MissionServiceTest.java
git commit -m "Mission.teamId: campo opcional, inmutable y validado contra los equipos ACTIVE" \
           -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: `AgentTask` con artefactos + `ProductStatus.DEVELOPMENT`

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/model/AgentTask.java`
- Modify: `app/src/main/java/com/aicompany/core/service/MissionMemoryService.java`
- Modify: `app/src/main/java/com/aicompany/core/service/ProductStatusService.java`
- Test: `app/src/test/java/com/aicompany/core/service/ProductStatusServiceTest.java`

**Interfaces:**
- Produces: `AgentTask` gana `kind`, `workspacePath`, `commitSha`, `List<String> files`, `validationStatus`, `staticChecks` (componentes 8-13; se mantiene el constructor de 7 argumentos); `MissionMemoryService.createTask(String taskId, String missionId, String agentId, String action, String kind)`; `recordTaskArtifact(String taskId, String workspacePath, String commitSha, List<String> files)`; `recordStaticValidation(String taskId, String validationStatus, String staticChecksJson)`.

- [ ] **Step 1: Escribir los tests nuevos de `ProductStatusServiceTest` (fallan: constructor de 13 argumentos no existe)**

Agregar a `ProductStatusServiceTest` (reusa los mocks `missionMemory`/`customerMemory` y el campo `service` que ya define la clase):

```java
    private static AgentTask devTask(String kind, String status, String commitSha) {
        return new AgentTask("MISSION-9-BACKEND", "MISSION-9", "backend", "GAME_LOGIC", status, "{}",
                Instant.parse("2026-09-24T00:00:00Z"), kind, "/data/forjai-products/MISSION-9", commitSha,
                List.of("web/game/main.js"), null, null);
    }

    @Test
    void committedWorkTaskMeansDevelopment() {
        when(missionMemory.tasks("MISSION-9")).thenReturn(List.of(devTask("WORK", "COMPLETED", "a".repeat(40))));
        when(customerMemory.totalRevenueAndCost("MISSION-9")).thenReturn(new double[]{0.0, 0.0});
        when(customerMemory.transactionCount("MISSION-9")).thenReturn(0L);

        assertEquals(ProductStatus.DEVELOPMENT, service.resolve("MISSION-9"));
    }

    @Test
    void workTaskWithoutCommitIsNotDevelopment() {
        when(missionMemory.tasks("MISSION-9")).thenReturn(List.of(devTask("WORK", "FAILED", null)));
        when(customerMemory.totalRevenueAndCost("MISSION-9")).thenReturn(new double[]{0.0, 0.0});
        when(customerMemory.transactionCount("MISSION-9")).thenReturn(0L);

        assertEquals(ProductStatus.DISCOVERY, service.resolve("MISSION-9"));
    }
```

(Mismos stubs "sin ventas" que los tests existentes del archivo: `totalRevenueAndCost` → `{0.0, 0.0}` y `transactionCount` → `0L`.)

- [ ] **Step 2: Correr el test para confirmar que falla**

Run: `cd app && mvn test -Dtest=ProductStatusServiceTest` → FAIL de compilación.

- [ ] **Step 3: `AgentTask`**

```java
public record AgentTask(
        String taskId,
        String missionId,
        String agentId,
        String action,
        String status,
        String result,
        Instant updatedAt,
        String kind,
        String workspacePath,
        String commitSha,
        List<String> files,
        String validationStatus,
        String staticChecks
) {
    /** Tareas de discovery: sin tipo ni artefactos. */
    public AgentTask(String taskId, String missionId, String agentId, String action,
                     String status, String result, Instant updatedAt) {
        this(taskId, missionId, agentId, action, status, result, updatedAt, null, null, null, null, null, null);
    }
}
```

(Agregar `import java.util.List;`.)

- [ ] **Step 4: `MissionMemoryService` — kind y artefactos**

Reemplazar `createTask` por las dos versiones (la de 4 argumentos delega; `Map.of` no admite `null`, por eso `HashMap`):

```java
    public void createTask(String taskId, String missionId, String agentId, String action) {
        createTask(taskId, missionId, agentId, action, null);
    }

    /** kind: PLANNING | WORK | VALIDATION para misiones de equipo; null en discovery. */
    public void createTask(String taskId, String missionId, String agentId, String action, String kind) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                var params = new HashMap<String, Object>();
                params.put("taskId", taskId);
                params.put("missionId", missionId);
                params.put("agentId", agentId);
                params.put("action", action);
                params.put("kind", kind);
                params.put("updatedAt", Instant.now().toString());
                tx.run("MATCH (m:Mission {id:$missionId}), (a:Agent {id:$agentId}) " +
                                "MERGE (t:AgentTask {id:$taskId}) SET t.missionId=$missionId, t.agentId=$agentId, " +
                                "t.action=$action, t.kind=$kind, t.status='PENDING', t.updatedAt=$updatedAt " +
                                "MERGE (m)-[:HAS_TASK]->(t) MERGE (a)-[:ASSIGNED_TASK]->(t) " +
                                "MERGE (m)-[:INVOLVES_AGENT]->(a)",
                        params);
                return null;
            });
        }
    }

    /** Artefacto real de una tarea de desarrollo: el commit es la prueba del trabajo (spec §6). */
    public void recordTaskArtifact(String taskId, String workspacePath, String commitSha, List<String> files) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH (t:AgentTask {id:$id}) SET t.workspacePath=$workspacePath, t.commitSha=$commitSha, "
                                + "t.files=$files, t.updatedAt=$updatedAt",
                        Map.of("id", taskId, "workspacePath", workspacePath, "commitSha", commitSha,
                                "files", files, "updatedAt", Instant.now().toString()));
                return null;
            });
        }
    }

    /** Estado calculado por Java + chequeos deterministas, en la tarea VALIDATION (spec §7). */
    public void recordStaticValidation(String taskId, String validationStatus, String staticChecksJson) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH (t:AgentTask {id:$id}) SET t.validationStatus=$validationStatus, "
                                + "t.staticChecks=$staticChecks, t.updatedAt=$updatedAt",
                        Map.of("id", taskId, "validationStatus", validationStatus,
                                "staticChecks", staticChecksJson, "updatedAt", Instant.now().toString()));
                return null;
            });
        }
    }
```

Reemplazar `tasks(...)`:

```java
    public List<AgentTask> tasks(String missionId) {
        try (var session = driver.session()) {
            return session.run("MATCH (t:AgentTask {missionId:$missionId}) RETURN t.id AS id, t.agentId AS agentId, "
                                    + "t.action AS action, t.status AS status, t.result AS result, t.updatedAt AS updatedAt, "
                                    + "t.kind AS kind, t.workspacePath AS workspacePath, t.commitSha AS commitSha, "
                                    + "t.files AS files, t.validationStatus AS validationStatus, t.staticChecks AS staticChecks "
                                    + "ORDER BY t.id",
                            Map.of("missionId", missionId))
                    .list(r -> new AgentTask(
                            r.get("id").asString(), missionId,
                            r.get("agentId").asString(), r.get("action").asString(), r.get("status").asString(),
                            r.get("result").asString(""), Instant.parse(r.get("updatedAt").asString()),
                            nullableString(r.get("kind")),
                            nullableString(r.get("workspacePath")),
                            nullableString(r.get("commitSha")),
                            r.get("files").isNull() ? null : r.get("files").asList(v -> v.asString()),
                            nullableString(r.get("validationStatus")),
                            nullableString(r.get("staticChecks"))));
        }
    }
```

- [ ] **Step 5: `ProductStatusService.isInDevelopment`**

```java
    /**
     * DEVELOPMENT = existe una tarea WORK completada con un commit real
     * (spec §9) — el commit es la prueba, no el nombre de la acción.
     */
    private boolean isInDevelopment(String missionId) {
        return missionMemory.tasks(missionId).stream()
                .anyMatch(t -> "WORK".equals(t.kind())
                        && "COMPLETED".equals(t.status())
                        && t.commitSha() != null
                        && !t.commitSha().isBlank());
    }
```

- [ ] **Step 6: Correr los tests**

Run: `cd app && mvn test -Dtest=ProductStatusServiceTest` → PASS.
Run: `cd app && mvn test` → BUILD SUCCESS, 0 failures.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/aicompany/core/model/AgentTask.java app/src/main/java/com/aicompany/core/service/MissionMemoryService.java \
        app/src/main/java/com/aicompany/core/service/ProductStatusService.java app/src/test/java/com/aicompany/core/service/ProductStatusServiceTest.java
git commit -m "AgentTask con kind/commit/archivos/validación; ProductStatus.DEVELOPMENT alcanzable por commit real" \
           -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: `CeoService` — plan del líder, generación y revisión estática

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/CeoService.java`
- Test: `app/src/test/java/com/aicompany/core/service/CeoServiceTeamCallsTest.java`

**Interfaces:**
- Consumes: `TeamPlan`/`TeamPlanSchema`, `DevelopmentResult`/`DevelopmentResultSchema`, `StaticReviewResult`/`StaticReviewResultSchema` (Task 1).
- Produces: `CeoService.planTeamWork(String agentId, String prompt, String agentPrompt, String model) -> TeamPlan`; `generateDevelopmentArtifact(String agentId, String prompt, String agentPrompt, String model) -> DevelopmentResult`; `reviewStaticWorkspace(String agentId, String prompt, String agentPrompt, String model) -> StaticReviewResult`. Los tres lanzan `IllegalStateException` si la llamada o el parseo fallan.

- [ ] **Step 1: Escribir el test (falla: los métodos no existen)**

```java
package com.aicompany.core.service;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.evidence.EvidenceAcquisitionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * Sin Ollama real (mismo criterio que CeoServiceToolFormatGuardTest): solo
 * el camino de error — cualquier fallo de la llamada o del parseo se
 * normaliza a IllegalStateException, que es lo que reintentan
 * TeamWorkPlanner y DevelopmentRuntime.
 */
class CeoServiceTeamCallsTest {

    private final CeoService ceoService = new CeoService(
            mock(RestClient.class),
            JsonMapper.builder().build(),
            mock(EvidenceAcquisitionService.class),
            mock(CompanyEventPublisher.class),
            new SimpleMeterRegistry()
    );

    @Test
    void planTeamWorkFailsWithIllegalStateException() {
        assertThrows(IllegalStateException.class,
                () -> ceoService.planTeamWork("engineering", "prompt", "", "qwen3:8b"));
    }

    @Test
    void generateDevelopmentArtifactFailsWithIllegalStateException() {
        assertThrows(IllegalStateException.class,
                () -> ceoService.generateDevelopmentArtifact("backend", "prompt", "", "qwen3:8b"));
    }

    @Test
    void reviewStaticWorkspaceFailsWithIllegalStateException() {
        assertThrows(IllegalStateException.class,
                () -> ceoService.reviewStaticWorkspace("qa", "prompt", "", "qwen3:8b"));
    }
}
```

- [ ] **Step 2: Correr el test para confirmar que falla**

Run: `cd app && mvn test -Dtest=CeoServiceTeamCallsTest` → FAIL de compilación.

- [ ] **Step 3: Implementar los 3 métodos**

Imports nuevos en `CeoService.java`:

```java
import com.aicompany.core.agent.model.DevelopmentResult;
import com.aicompany.core.agent.model.DevelopmentResultSchema;
import com.aicompany.core.agent.model.StaticReviewResult;
import com.aicompany.core.agent.model.StaticReviewResultSchema;
import com.aicompany.core.agent.model.TeamPlan;
import com.aicompany.core.agent.model.TeamPlanSchema;
```

Agregar inmediatamente después de `executeMission(...)`:

```java
    /** Plan del líder de un equipo (spec §3): format TeamPlanSchema, sin tools. */
    public TeamPlan planTeamWork(String agentId, String prompt, String agentPrompt, String model) {
        return callStructured("TEAM_PLANNING", agentId, prompt, agentPrompt, model,
                TeamPlanSchema.SCHEMA, TeamPlan.class);
    }

    /** Código real de una tarea WORK (spec §6): format DevelopmentResultSchema, sin tools. */
    public DevelopmentResult generateDevelopmentArtifact(String agentId, String prompt, String agentPrompt, String model) {
        return callStructured("DEVELOPMENT_TASK", agentId, prompt, agentPrompt, model,
                DevelopmentResultSchema.SCHEMA, DevelopmentResult.class);
    }

    /** Revisión estática del repo (spec §7, capa 2): format StaticReviewResultSchema, sin tools. */
    public StaticReviewResult reviewStaticWorkspace(String agentId, String prompt, String agentPrompt, String model) {
        return callStructured("STATIC_REVIEW", agentId, prompt, agentPrompt, model,
                StaticReviewResultSchema.SCHEMA, StaticReviewResult.class);
    }

    /**
     * Una sola llamada con format y SIN tools (regla dura del proyecto). El
     * reintento con corrección vive en el llamador (TeamWorkPlanner /
     * DevelopmentRuntime), igual que el turno final de executeAgentTask.
     */
    private <T> T callStructured(
            String operation, String agentId, String prompt, String agentPrompt, String model,
            Object schema, Class<T> type) {

        String response = null;

        try {

            var messages = List.<Map<String, Object>>of(
                    Map.of("role", "system", "content", teamSystemPrompt(agentId, agentPrompt)),
                    Map.of("role", "user", "content", prompt)
            );

            response = callModel(operation, agentId, model, messages, schema, null, false).content();

            var result = jsonMapper.readValue(normalizeJsonResponse(response), type);

            log.info("{}_PARSED agent={}", operation, agentId);

            return result;

        } catch (Exception ex) {

            log.error("{}_ERROR agent={} model={} reason={} response={}",
                    operation, agentId, model, ex.getMessage(), response);

            throw new IllegalStateException(
                    "El agente " + agentId + " no devolvió un JSON válido para " + operation + ": "
                            + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()),
                    ex);
        }
    }

    /**
     * System prompt de un miembro de equipo (no el del CEO). El prompt
     * versionado del agente entra como sección aparte y nunca reemplaza
     * estas reglas (mismo criterio que AgentRuntime.buildPrompt).
     */
    private String teamSystemPrompt(String agentId, String agentPrompt) {

        var agentPromptBlock = (agentPrompt == null || agentPrompt.isBlank())
                ? ""
                : "\nCÓMO DEBES RAZONAR (definido por el fundador para vos, no reemplaza las reglas de abajo):\n"
                        + agentPrompt + "\n";

        return """
                Estás trabajando dentro de Forjai como el agente %s, miembro de un equipo real.
                Forjai es una empresa real operada principalmente por agentes de IA.
                %s
                REGLAS:
                - No inventes archivos, commits, resultados de ejecución, clientes ni evidencia.
                - En esta fase nadie ejecuta código: nunca afirmes que algo compila, se ejecuta o pasa tests.
                - Una hipótesis NO es un hecho.
                - Responde ÚNICAMENTE con JSON válido que cumpla el formato pedido, sin Markdown ni texto adicional.

                Responde en español.
                """.formatted(agentId, agentPromptBlock);
    }
```

- [ ] **Step 4: Correr los tests**

Run: `cd app && mvn test -Dtest=CeoServiceTeamCallsTest` → PASS (3 tests).
Run: `cd app && mvn test` → BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/CeoService.java app/src/test/java/com/aicompany/core/service/CeoServiceTeamCallsTest.java
git commit -m "CeoService: planTeamWork, generateDevelopmentArtifact y reviewStaticWorkspace (format sin tools)" \
           -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: `TeamWorkPlanner` (TDD)

**Files:**
- Create: `app/src/main/java/com/aicompany/core/model/TeamPlanResult.java`
- Create: `app/src/main/java/com/aicompany/core/service/TeamWorkPlanner.java`
- Test: `app/src/test/java/com/aicompany/core/service/TeamWorkPlannerTest.java`

**Interfaces:**
- Consumes: `TeamPlanValidator.validate` (Task 3); `CeoService.planTeamWork` (Task 6); `MissionMemoryService.createTask(..., kind)` (Task 5), `updateTask`, `setAgentStatus`; `TeamMemoryService.snapshot(String)`; `CompanyMemoryService.agentModel(String, String)`; `PromptMemoryService.activePrompt(String)`.
- Produces: `TeamPlanResult(TeamSnapshot team, TeamPlan plan)`; `TeamWorkPlanner.plan(String missionId, String teamId, String instruction, TeamExecutionMode mode) -> TeamPlanResult` (lanza `IllegalStateException` si el equipo no es usable o el plan no es válido tras 3 intentos). Id de la tarea del plan: `<missionId>-<LIDER_EN_MAYÚSCULAS>-PLAN`.

- [ ] **Step 1: Escribir el test (falla: las clases no existen)**

```java
package com.aicompany.core.service;

import com.aicompany.core.agent.model.TeamPlan;
import com.aicompany.core.agent.model.TeamPlan.PlannedTask;
import com.aicompany.core.agent.validation.TeamPlanValidator;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.TeamExecutionMode;
import com.aicompany.core.model.TeamMemberInfo;
import com.aicompany.core.model.TeamSnapshot;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TeamWorkPlannerTest {

    private final TeamMemoryService teamMemory = mock(TeamMemoryService.class);
    private final CeoService ceoService = mock(CeoService.class);
    private final CompanyMemoryService companyMemory = mock(CompanyMemoryService.class);
    private final PromptMemoryService promptMemory = mock(PromptMemoryService.class);
    private final MissionMemoryService memory = mock(MissionMemoryService.class);
    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);

    private final TeamWorkPlanner planner = new TeamWorkPlanner(
            teamMemory, ceoService, companyMemory, promptMemory, memory, events,
            new TeamPlanValidator(), JsonMapper.builder().build(), "qwen3:8b");

    {
        when(companyMemory.agentModel(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(promptMemory.activePrompt(anyString())).thenReturn("");
    }

    private static TeamSnapshot marketing(String status) {
        return new TeamSnapshot("TEAM-MARKETING-GROWTH", "Marketing & Growth", status, "growth-content", List.of(
                new TeamMemberInfo("growth-content", "Kira", "Growth", "GROWTH_CONTENT_COMMUNITY", List.of("SEO", "growth"), "qwen3:8b"),
                new TeamMemberInfo("community", "Nora", "Community", "COMMUNITY_MANAGER", List.of("Discord"), "qwen3:8b")));
    }

    private static TeamPlan validPlan() {
        return new TeamPlan("Plan de lanzamiento", "", "", List.of(
                new PlannedTask("growth-content", "WORK", "SEO_PLAN", "Plan SEO", List.of("SEO"), List.of())));
    }

    private static TeamPlan planWithOutsider() {
        return new TeamPlan("Plan", "", "", List.of(
                new PlannedTask("finance", "WORK", "COSTS", "Costos", List.of("finanzas"), List.of())));
    }

    @Test
    void returnsAValidPlanAndPersistsItAsTheLeadersPlanningTask() {
        when(teamMemory.snapshot("TEAM-MARKETING-GROWTH")).thenReturn(marketing("ACTIVE"));
        when(ceoService.planTeamWork(eq("growth-content"), anyString(), anyString(), eq("qwen3:8b"))).thenReturn(validPlan());

        var result = planner.plan("MISSION-5", "TEAM-MARKETING-GROWTH", "Lanzar el juego", TeamExecutionMode.ANALYSIS);

        assertEquals("growth-content", result.team().leaderAgentId());
        assertEquals(1, result.plan().tasksOrEmpty().size());
        verify(memory).createTask("MISSION-5-GROWTH-CONTENT-PLAN", "MISSION-5", "growth-content", "TEAM_PLANNING", "PLANNING");
        verify(memory).updateTask(eq("MISSION-5-GROWTH-CONTENT-PLAN"), eq("COMPLETED"), anyString());
        verify(events).publish(eq("EMPRESA_TEAM_PLAN_CREATED"), eq("MISSION-5"), anyString(), eq("growth-content"), anyMap());
    }

    @Test
    void theRosterWithRealCapabilitiesGoesIntoThePrompt() {
        when(teamMemory.snapshot("TEAM-MARKETING-GROWTH")).thenReturn(marketing("ACTIVE"));
        var prompt = ArgumentCaptor.forClass(String.class);
        when(ceoService.planTeamWork(eq("growth-content"), prompt.capture(), anyString(), anyString())).thenReturn(validPlan());

        planner.plan("MISSION-5", "TEAM-MARKETING-GROWTH", "Lanzar el juego", TeamExecutionMode.ANALYSIS);

        assertTrue(prompt.getValue().contains("agentId=community"));
        assertTrue(prompt.getValue().contains("Discord"));
        assertTrue(prompt.getValue().contains("Lanzar el juego"));
    }

    @Test
    void retriesWithTheExactValidationErrorsAsCorrection() {
        when(teamMemory.snapshot("TEAM-MARKETING-GROWTH")).thenReturn(marketing("ACTIVE"));
        var prompt = ArgumentCaptor.forClass(String.class);
        when(ceoService.planTeamWork(eq("growth-content"), prompt.capture(), anyString(), anyString()))
                .thenReturn(planWithOutsider())
                .thenReturn(validPlan());

        planner.plan("MISSION-5", "TEAM-MARKETING-GROWTH", "Lanzar el juego", TeamExecutionMode.ANALYSIS);

        var second = prompt.getAllValues().get(1);
        assertTrue(second.contains("CORRECCIÓN DEL INTENTO ANTERIOR"));
        assertTrue(second.contains("finance"));
        verify(events).publish(eq("EMPRESA_TEAM_PLAN_REJECTED"), eq("MISSION-5"), anyString(), eq("growth-content"), anyMap());
    }

    @Test
    void failsAfterThreeInvalidPlansAndReturnsTheLeaderToIdle() {
        when(teamMemory.snapshot("TEAM-MARKETING-GROWTH")).thenReturn(marketing("ACTIVE"));
        when(ceoService.planTeamWork(anyString(), anyString(), anyString(), anyString())).thenReturn(planWithOutsider());

        var ex = assertThrows(IllegalStateException.class,
                () -> planner.plan("MISSION-5", "TEAM-MARKETING-GROWTH", "x", TeamExecutionMode.ANALYSIS));

        assertTrue(ex.getMessage().contains("no produjo un plan válido"));
        verify(ceoService, times(3)).planTeamWork(anyString(), anyString(), anyString(), anyString());
        verify(memory).updateTask(eq("MISSION-5-GROWTH-CONTENT-PLAN"), eq("FAILED"), anyString());
        var inOrder = inOrder(memory);
        inOrder.verify(memory).setAgentStatus("growth-content", "WORKING");
        inOrder.verify(memory).setAgentStatus("growth-content", "IDLE");
    }

    @Test
    void anUnparseableResponseCountsAsARejectedAttempt() {
        when(teamMemory.snapshot("TEAM-MARKETING-GROWTH")).thenReturn(marketing("ACTIVE"));
        when(ceoService.planTeamWork(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("JSON inválido"))
                .thenReturn(validPlan());

        var result = planner.plan("MISSION-5", "TEAM-MARKETING-GROWTH", "x", TeamExecutionMode.ANALYSIS);

        assertNotNull(result.plan());
        verify(ceoService, times(2)).planTeamWork(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void refusesAnInactiveTeamWithoutCallingTheModel() {
        when(teamMemory.snapshot("TEAM-MARKETING-GROWTH")).thenReturn(marketing("INACTIVE"));

        assertThrows(IllegalStateException.class,
                () -> planner.plan("MISSION-5", "TEAM-MARKETING-GROWTH", "x", TeamExecutionMode.ANALYSIS));
        verifyNoInteractions(ceoService);
    }
}
```

- [ ] **Step 2: Correr el test para confirmar que falla**

Run: `cd app && mvn test -Dtest=TeamWorkPlannerTest` → FAIL de compilación.

- [ ] **Step 3: Crear `TeamPlanResult`**

```java
package com.aicompany.core.model;

import com.aicompany.core.agent.model.TeamPlan;

/** Plan del líder ya validado + el equipo real sobre el que se validó. */
public record TeamPlanResult(TeamSnapshot team, TeamPlan plan) {
}
```

- [ ] **Step 4: Implementar `TeamWorkPlanner`**

```java
package com.aicompany.core.service;

import com.aicompany.core.agent.model.TeamPlan;
import com.aicompany.core.agent.validation.TeamPlanValidator;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.TeamExecutionMode;
import com.aicompany.core.model.TeamMemberInfo;
import com.aicompany.core.model.TeamPlanResult;
import com.aicompany.core.model.TeamSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * El líder del equipo descompone la misión en tareas para sus miembros
 * reales (spec §3). Genérico para los 3 equipos: la diferencia de reglas
 * entre análisis y desarrollo vive en TeamPlanValidator. Sin plan por
 * defecto: si el líder no logra un plan válido, la misión falla.
 */
@Service
public class TeamWorkPlanner {

    private static final Logger log = LoggerFactory.getLogger(TeamWorkPlanner.class);

    private static final int MAX_PLAN_RETRIES = 2;

    private final TeamMemoryService teamMemory;
    private final CeoService ceoService;
    private final CompanyMemoryService companyMemory;
    private final PromptMemoryService promptMemory;
    private final MissionMemoryService memory;
    private final CompanyEventPublisher events;
    private final TeamPlanValidator validator;
    private final JsonMapper jsonMapper;
    private final String defaultAgentModel;

    public TeamWorkPlanner(
            TeamMemoryService teamMemory,
            CeoService ceoService,
            CompanyMemoryService companyMemory,
            PromptMemoryService promptMemory,
            MissionMemoryService memory,
            CompanyEventPublisher events,
            TeamPlanValidator validator,
            JsonMapper jsonMapper,
            @Value("${ollama.agent-model}") String defaultAgentModel) {

        this.teamMemory = teamMemory;
        this.ceoService = ceoService;
        this.companyMemory = companyMemory;
        this.promptMemory = promptMemory;
        this.memory = memory;
        this.events = events;
        this.validator = validator;
        this.jsonMapper = jsonMapper;
        this.defaultAgentModel = defaultAgentModel;
    }

    public TeamPlanResult plan(String missionId, String teamId, String instruction, TeamExecutionMode mode) {

        var team = teamMemory.snapshot(teamId);

        if (team == null || !"ACTIVE".equals(team.status())) {
            throw new IllegalStateException("El equipo " + teamId + " no existe o no está ACTIVE en Company Memory.");
        }

        if (team.leaderAgentId() == null || team.members().isEmpty()) {
            throw new IllegalStateException("El equipo " + teamId + " no tiene líder o miembros en Company Memory.");
        }

        var leaderId = team.leaderAgentId();
        var taskId = missionId + "-" + leaderId.toUpperCase(Locale.ROOT) + "-PLAN";

        memory.createTask(taskId, missionId, leaderId, "TEAM_PLANNING", "PLANNING");
        memory.updateTask(taskId, "RUNNING", "El líder está descomponiendo el trabajo.");
        memory.setAgentStatus(leaderId, "WORKING");

        try {

            var model = companyMemory.agentModel(leaderId, defaultAgentModel);
            var leaderPrompt = promptMemory.activePrompt(leaderId);
            var basePrompt = buildPrompt(team, instruction, mode);
            String feedback = null;

            for (int attempt = 0; attempt <= MAX_PLAN_RETRIES; attempt++) {

                var prompt = feedback == null ? basePrompt : basePrompt + correctionBlock(feedback);

                TeamPlan plan;

                try {
                    plan = ceoService.planTeamWork(leaderId, prompt, leaderPrompt, model);
                } catch (Exception ex) {
                    feedback = "- " + safeMessage(ex, "Respuesta no procesable.");
                    publishRejected(missionId, taskId, leaderId, attempt, feedback);
                    continue;
                }

                var errors = validator.validate(plan, team, mode);

                if (errors.isEmpty()) {

                    memory.updateTask(taskId, "COMPLETED", toJson(plan));

                    events.publish("EMPRESA_TEAM_PLAN_CREATED", missionId, taskId, leaderId,
                            Map.of("teamId", teamId, "tasks", plan.tasksOrEmpty().size()));

                    log.info("MISSION {} - team plan accepted team={} tasks={}",
                            missionId, teamId, plan.tasksOrEmpty().size());

                    return new TeamPlanResult(team, plan);
                }

                feedback = "- " + String.join("\n- ", errors);
                publishRejected(missionId, taskId, leaderId, attempt, feedback);
            }

            var message = "El líder " + leaderId + " no produjo un plan válido para " + teamId
                    + " después de " + (MAX_PLAN_RETRIES + 1) + " intentos:\n" + feedback;

            memory.updateTask(taskId, "FAILED", message);

            throw new IllegalStateException(message);

        } finally {
            memory.setAgentStatus(leaderId, "IDLE");
        }
    }

    private void publishRejected(String missionId, String taskId, String leaderId, int attempt, String feedback) {
        log.warn("MISSION {} - team plan rejected attempt={} errors={}", missionId, attempt + 1, feedback);
        events.publish("EMPRESA_TEAM_PLAN_REJECTED", missionId, taskId, leaderId,
                Map.of("attempt", attempt + 1, "errors", feedback));
    }

    private String buildPrompt(TeamSnapshot team, String instruction, TeamExecutionMode mode) {

        var leaderName = team.members().stream()
                .filter(m -> m.agentId().equals(team.leaderAgentId()))
                .map(TeamMemberInfo::name)
                .findFirst()
                .orElse(team.leaderAgentId());

        var roster = team.members().stream()
                .map(m -> "- agentId=" + m.agentId() + " | nombre=" + m.name() + " | rol=" + m.role()
                        + " | roleCode=" + m.roleCode() + " | capabilities=" + m.capabilities())
                .collect(Collectors.joining("\n"));

        var common = """
                Eres %s, líder de %s (%s) en Forjai. Descompón la misión en tareas para los miembros
                REALES de tu equipo.

                MISIÓN:
                %s

                MIEMBROS DEL EQUIPO (datos reales de Company Memory; no existen otros):
                %s

                REGLAS DEL PLAN:
                - Usa solo agentId de la lista anterior. Nunca asignes tareas a agentes fuera del equipo.
                - A lo sumo una tarea por agente.
                - requiredCapabilities: copia TEXTUALMENTE capabilities de la lista del agente asignado; nunca inventes una.
                - action: identificador corto en MAYÚSCULAS_CON_GUIONES_BAJOS.
                - objective: qué debe entregar ese agente, concreto y verificable.
                """.formatted(leaderName, team.teamName(), team.teamId(), instruction, roster);

        if (mode == TeamExecutionMode.ANALYSIS) {
            return common + """
                    - kind: siempre "WORK" (este equipo no tiene tareas de validación).
                    - techStack y entryPoint: déjalos como "" y ownedPaths como [].
                    """;
        }

        return common + """
                - Este equipo produce CÓDIGO REAL en un repositorio Git: todos los miembros deben recibir exactamente una tarea.
                - Exactamente una tarea kind="VALIDATION", asignada al miembro que tenga la capability "QA":
                  revisará el código sin ejecutarlo. Esa tarea lleva ownedPaths [].
                - Las demás tareas son kind="WORK" y declaran ownedPaths: rutas relativas (carpetas o archivos) que solo
                  ese agente puede escribir. Los ownedPaths de agentes distintos no pueden solaparse.
                  Nunca uses rutas absolutas, "..", "\\" ni ".git".
                - techStack: la tecnología elegida; debe permitir un MVP pequeño y completo con la capacidad real del equipo.
                - entryPoint: ruta relativa del punto de entrada del proyecto; debe caer dentro de los ownedPaths de una tarea WORK.
                """;
    }

    private String correctionBlock(String feedback) {
        return """

                CORRECCIÓN DEL INTENTO ANTERIOR

                El plan anterior fue rechazado por validaciones deterministas.
                Corrige únicamente estos errores:
                %s
                """.formatted(feedback);
    }

    private String toJson(TeamPlan plan) {
        try {
            return jsonMapper.writeValueAsString(plan);
        } catch (JacksonException ex) {
            throw new IllegalStateException("No se pudo serializar el plan del equipo.", ex);
        }
    }

    private String safeMessage(Exception ex, String defaultMessage) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? defaultMessage : ex.getMessage();
    }
}
```

- [ ] **Step 5: Correr los tests**

Run: `cd app && mvn test -Dtest=TeamWorkPlannerTest` → PASS (6 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aicompany/core/model/TeamPlanResult.java app/src/main/java/com/aicompany/core/service/TeamWorkPlanner.java \
        app/src/test/java/com/aicompany/core/service/TeamWorkPlannerTest.java
git commit -m "Agregar TeamWorkPlanner: el líder del equipo planifica con reintento y validación determinista" \
           -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Extraer `AgentTaskBatchRunner` de `MissionExecutor` (sin cambio de comportamiento)

**Files:**
- Create: `app/src/main/java/com/aicompany/core/service/AgentTaskBatchRunner.java`
- Modify: `app/src/main/java/com/aicompany/core/service/MissionExecutor.java`
- Modify: `app/src/test/java/com/aicompany/core/service/MissionExecutorTest.java` (solo la línea de construcción)

**Interfaces:**
- Consumes: `AgentRuntime.execute(String taskId, String missionId, String agentId, String action, String instruction) -> CompletableFuture<AgentResult>`; `AgentExecutionOutcome.success/failure`.
- Produces: `AgentTaskBatchRunner.AgentTaskDefinition(String agentId, String action, String objective, String kind)`; `AgentTaskBatchRunner.run(String missionId, String instruction, List<AgentTaskDefinition> definitions, Runnable onSubmitted) -> List<AgentExecutionOutcome>`; constructor `AgentTaskBatchRunner(MissionMemoryService, AgentRuntime, CompanyEventPublisher)`; `MissionExecutor(MissionMemoryService, AgentTaskBatchRunner, CeoService, CompanyMemoryService, PromptMemoryService, String defaultCeoModel, Executor, CompanyEventPublisher, JsonMapper, ContradictionDetector, CompanyPolicyService, OpportunityMemoryService, AlertMailService)`; método privado `MissionExecutor.consolidateAgentOutcomes(String missionId, String instruction, List<AgentExecutionOutcome> outcomes, double seedCapitalUsd)` (lo reusa Task 13).

- [ ] **Step 1: Crear `AgentTaskBatchRunner` (código movido tal cual de `MissionExecutor`)**

```java
package com.aicompany.core.service;

import com.aicompany.core.agent.AgentRuntime;
import com.aicompany.core.agent.model.AgentResult;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.AgentExecutionOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Crear tareas → ejecutarlas en paralelo vía AgentRuntime → esperar a cada
 * agente por separado ("agent failure ≠ mission failure") → replanificar
 * los fallidos. Extraído tal cual de MissionExecutor para que lo usen el
 * flujo de discovery y AnalysisTeamStrategy (spec §2).
 */
@Service
public class AgentTaskBatchRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskBatchRunner.class);

    /**
     * Reintentos a nivel de MISIÓN para un agente que ya agotó sus 3
     * intentos internos: una segunda oportunidad completa desde cero, no
     * una corrección incremental.
     */
    private static final int MAX_AGENT_REPLANS = 1;

    /** kind == null en discovery (la tarea se crea exactamente como antes). */
    public record AgentTaskDefinition(String agentId, String action, String objective, String kind) {
    }

    private final MissionMemoryService memory;
    private final AgentRuntime runtime;
    private final CompanyEventPublisher events;

    public AgentTaskBatchRunner(MissionMemoryService memory, AgentRuntime runtime, CompanyEventPublisher events) {
        this.memory = memory;
        this.runtime = runtime;
        this.events = events;
    }

    public List<AgentExecutionOutcome> run(
            String missionId,
            String instruction,
            List<AgentTaskDefinition> definitions,
            Runnable onSubmitted) {

        var definitionsByAgent = definitions.stream()
                .collect(Collectors.toMap(AgentTaskDefinition::agentId, Function.identity()));

        var futuresByAgent = new LinkedHashMap<String, CompletableFuture<AgentResult>>();

        for (var definition : definitions) {

            var agentId = definition.agentId();
            var taskId = missionId + "-" + agentId.toUpperCase();

            log.info("MISSION {} - creating task {} for agent {}", missionId, taskId, agentId);

            createTask(taskId, missionId, definition);

            events.publishTask("EMPRESA_TASK_CREATED", taskId, missionId, agentId, "PENDING", "Tarea creada.");

            futuresByAgent.put(agentId, runtime.execute(
                    taskId, missionId, agentId, definition.action(),
                    instruction + "\nObjetivo específico: " + definition.objective()));
        }

        onSubmitted.run();

        List<AgentExecutionOutcome> outcomes = new ArrayList<>();

        for (var entry : futuresByAgent.entrySet()) {

            var agentId = entry.getKey();

            try {

                outcomes.add(AgentExecutionOutcome.success(agentId, entry.getValue().join()));

            } catch (Exception ex) {

                var reason = safeMessage(ex, "El agente no completó su tarea.");

                log.warn("MISSION {} - agent {} did not complete, continuing with partial results: {}",
                        missionId, agentId, reason);

                outcomes.add(AgentExecutionOutcome.failure(agentId, reason));
            }
        }

        log.info("MISSION {} - all agent tasks settled", missionId);

        return replanFailedAgents(missionId, instruction, definitionsByAgent, outcomes);
    }

    private void createTask(String taskId, String missionId, AgentTaskDefinition definition) {
        if (definition.kind() == null) {
            memory.createTask(taskId, missionId, definition.agentId(), definition.action());
        } else {
            memory.createTask(taskId, missionId, definition.agentId(), definition.action(), definition.kind());
        }
    }

    private List<AgentExecutionOutcome> replanFailedAgents(
            String missionId,
            String instruction,
            Map<String, AgentTaskDefinition> definitionsByAgent,
            List<AgentExecutionOutcome> outcomes) {

        var settled = new ArrayList<AgentExecutionOutcome>();

        for (var outcome : outcomes) {

            var current = outcome;
            var replanAttempt = 0;

            while (!current.completed() && replanAttempt < MAX_AGENT_REPLANS) {

                replanAttempt++;

                var agentId = current.agentId();
                var definition = definitionsByAgent.get(agentId);
                var taskId = missionId + "-" + agentId.toUpperCase();

                log.warn("MISSION {} - replanning agent {} (attempt {} of {}) after: {}",
                        missionId, agentId, replanAttempt, MAX_AGENT_REPLANS, current.error());

                events.publish(
                        "EMPRESA_MISSION_REPLANNED",
                        missionId,
                        taskId,
                        agentId,
                        Map.of(
                                "replanAttempt", replanAttempt,
                                "previousError", current.error() == null ? "" : current.error()
                        )
                );

                createTask(taskId, missionId, definition);

                events.publishTask(
                        "EMPRESA_TASK_CREATED",
                        taskId,
                        missionId,
                        agentId,
                        "PENDING",
                        "Tarea replanificada a nivel de misión (intento " + replanAttempt
                                + " de " + MAX_AGENT_REPLANS + ")."
                );

                var future = runtime.execute(taskId, missionId, agentId, definition.action(),
                        instruction + "\nObjetivo específico: " + definition.objective());

                try {

                    current = AgentExecutionOutcome.success(agentId, future.join());

                    log.info("MISSION {} - agent {} recovered after replan attempt {}",
                            missionId, agentId, replanAttempt);

                } catch (Exception ex) {

                    current = AgentExecutionOutcome.failure(agentId,
                            safeMessage(ex, "El agente no completó su tarea."));
                }
            }

            settled.add(current);
        }

        return settled;
    }

    private String safeMessage(Exception ex, String defaultMessage) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? defaultMessage : ex.getMessage();
    }
}
```

- [ ] **Step 2: Refactorizar `MissionExecutor`**

1. Borrar `MAX_AGENT_REPLANS`, el record `AgentDefinition` y el método `replanFailedAgents` (ahora viven en `AgentTaskBatchRunner`).
2. Reemplazar el campo `private final AgentRuntime runtime;` por `private final AgentTaskBatchRunner batchRunner;`, el parámetro `AgentRuntime runtime` del constructor por `AgentTaskBatchRunner batchRunner` (misma posición, 2º) y `this.runtime = runtime;` por `this.batchRunner = batchRunner;`. Quitar el import de `AgentRuntime` y los que queden sin uso (`LinkedHashMap`, `Map` si ya no se usan — el compilador no falla por imports sin uso, pero dejarlos limpios).
3. Reemplazar el método `executeInternal` completo por estas tres piezas:

```java
    private void executeInternal(
            String missionId,
            String instruction) {

        log.info("MISSION {} - async execution started", missionId);

        try {

            advanceMission(
                    missionId,
                    MissionStatus.PLANNING,
                    5,
                    "Planificación",
                    "CEO está definiendo el trabajo de la misión."
            );

            log.info("MISSION {} -> PLANNING", missionId);

            advanceMission(
                    missionId,
                    MissionStatus.DELEGATING,
                    10,
                    "Delegación",
                    "Asignando tareas paralelas a Sales, Product, Finance, Engineering y QA."
            );

            log.info("MISSION {} -> DELEGATING", missionId);

            var seedCapitalUsd = companyPolicyService.activeValue(PolicyKey.SEED_CAPITAL_USD);
            var financialCriteria = memory.financialCriteria(missionId).orElse(null);

            var outcomes = batchRunner.run(
                    missionId,
                    instruction,
                    discoveryDefinitions(seedCapitalUsd, financialCriteria),
                    () -> {
                        advanceMission(
                                missionId,
                                MissionStatus.WAITING_AGENT_RESULTS,
                                30,
                                "Trabajo paralelo",
                                "Los agentes están trabajando en paralelo."
                        );
                        log.info("MISSION {} -> WAITING_AGENT_RESULTS", missionId);
                    }
            );

            consolidateAgentOutcomes(missionId, instruction, outcomes, seedCapitalUsd);

        } catch (Exception ex) {

            log.error("MISSION {} - execution failed", missionId, ex);

            safeFail(missionId, ex);
        }
    }

    /** Las 5 tareas fijas de discovery — misiones SIN teamId, sin cambios. */
    private List<AgentTaskBatchRunner.AgentTaskDefinition> discoveryDefinitions(
            double seedCapitalUsd,
            FinancialCriteriaResponse financialCriteria) {

        return List.of(
                new AgentTaskBatchRunner.AgentTaskDefinition(
                        "sales",
                        "MARKET_DISCOVERY",
                        "Identificar perfiles de clientes y señales de demanda que deban validarse.",
                        null
                ),
                new AgentTaskBatchRunner.AgentTaskDefinition(
                        "product",
                        "OFFER_DESIGN",
                        "Definir una oferta mínima vendible alineada con las restricciones de capital.",
                        null
                ),
                new AgentTaskBatchRunner.AgentTaskDefinition(
                        "finance",
                        "UNIT_ECONOMICS",
                        financeObjective(seedCapitalUsd, financialCriteria),
                        null
                ),
                new AgentTaskBatchRunner.AgentTaskDefinition(
                        "engineering",
                        "DELIVERY_FEASIBILITY",
                        "Evaluar la capacidad de entregar la oferta con los recursos tecnológicos disponibles.",
                        null
                ),
                new AgentTaskBatchRunner.AgentTaskDefinition(
                        "qa",
                        "QUALITY_RISK_REVIEW",
                        "Identificar, de forma independiente a los demás agentes (esta tarea corre en paralelo, no tiene acceso a sus resultados), riesgos, huecos de evidencia y supuestos no verificados en la oportunidad de negocio descrita en la misión, antes de comprometer capital.",
                        null
                )
        );
    }

    /**
     * EVALUATING → CONSOLIDATING → AWAITING_INVESTOR sobre resultados de
     * AgentRuntime (AgentResult). Lo usan discovery y los equipos de
     * análisis (AnalysisTeamStrategy).
     */
    private void consolidateAgentOutcomes(
            String missionId,
            String instruction,
            List<AgentExecutionOutcome> outcomes,
            double seedCapitalUsd) {

        advanceMission(
                missionId,
                MissionStatus.EVALUATING,
                70,
                "Evaluación",
                "Todos los agentes terminaron. CEO está revisando resultados."
        );

        var agentResults = outcomes.stream()
                .filter(AgentExecutionOutcome::completed)
                .map(AgentExecutionOutcome::result)
                .toList();

        var failedAgents = outcomes.stream()
                .filter(outcome -> !outcome.completed())
                .toList();

        if (agentResults.isEmpty()) {
            throw new IllegalStateException(
                    "Los " + failedAgents.size() + " agente(s) de la misión fallaron: "
                            + failedAgents.stream()
                                    .map(o -> o.agentId() + " (" + o.error() + ")")
                                    .collect(Collectors.joining("; "))
            );
        }

        if (!failedAgents.isEmpty()) {
            log.warn("MISSION {} - continuing with partial results, failed agents={}",
                    missionId, failedAgents.stream().map(AgentExecutionOutcome::agentId).toList());
        }

        /*
         * Flujo Opportunity -> Customer candidato (100% nivel 🟢): la misión
         * produjo al menos un resultado, así que hay una oportunidad que
         * registrar; los candidatos quedan como Customer {status:'LEAD'}.
         */
        opportunityMemory.recordOpportunity(missionId, instruction);

        for (var result : agentResults) {
            opportunityMemory.recordCandidates(missionId, result.agent(), result.customerCandidates());
        }

        var structuredResults = serializeAgentResults(agentResults);

        log.info("MISSION {} - structured agent results generated agents={}", missionId, agentResults.size());

        var contradictions = contradictionDetector.detect(
                agentResults,
                seedCapitalUsd,
                companyPolicyService.activeValue(PolicyKey.CONTRADICTION_SEED_CAPITAL_MULTIPLE)
        );

        if (!contradictions.isEmpty()) {
            log.warn("MISSION {} - contradictions detected: {}", missionId, contradictions);
        }

        var resultsForCeo = structuredResults;

        if (!contradictions.isEmpty()) {
            resultsForCeo += "\n\nCONTRADICCIONES_DETECTADAS "
                    + "(reglas deterministas, no del modelo; "
                    + "no las ignores al consolidar):\n- "
                    + String.join("\n- ", contradictions);
        }

        if (!failedAgents.isEmpty()) {
            resultsForCeo += "\n\nAGENTES_FALLIDOS (no completaron su tarea "
                    + "tras agotar reintentos; este es un "
                    + "resultado PARCIAL — decide cómo abordar "
                    + "el hueco en tu recomendación, no lo "
                    + "ignores):\n- "
                    + failedAgents.stream()
                            .map(o -> o.agentId() + ": " + o.error())
                            .collect(Collectors.joining("\n- "));
        }

        advanceMission(
                missionId,
                MissionStatus.CONSOLIDATING,
                85,
                "Consolidación",
                "CEO está consolidando la recomendación."
        );

        var finalResult = ceoService.executeMission(
                instruction,
                resultsForCeo,
                promptMemory.activePrompt("ceo"),
                companyMemory.agentModel("ceo", defaultCeoModel)
        );

        advanceMission(
                missionId,
                MissionStatus.AWAITING_INVESTOR,
                95,
                "Recomendación",
                finalResult
        );

        log.info("MISSION {} -> AWAITING_INVESTOR", missionId);
    }
```

- [ ] **Step 3: Actualizar la construcción en `MissionExecutorTest`**

Reemplazar el bloque `private final MissionExecutor executor = new MissionExecutor(...)` por:

```java
    private final MissionExecutor executor = new MissionExecutor(
            memory, new AgentTaskBatchRunner(memory, runtime, events), ceoService, companyMemory, promptMemory,
            "qwen2.5-coder:14b", Runnable::run, events, jsonMapper,
            contradictionDetector, companyPolicyService, opportunityMemory, alertMailService
    );
```

Si existen otras construcciones de `MissionExecutor` en el archivo o en otros tests: `grep -rn "new MissionExecutor(" app/src/test` y aplicar el mismo cambio (el 2º argumento pasa a ser `new AgentTaskBatchRunner(memory, runtime, events)`).

- [ ] **Step 4: Correr los tests de `MissionExecutor` sin tocar ningún otro assert**

Run: `cd app && mvn test -Dtest=MissionExecutorTest` → PASS, mismos tests que antes, sin modificaciones funcionales.
Run: `cd app && mvn test` → BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/AgentTaskBatchRunner.java app/src/main/java/com/aicompany/core/service/MissionExecutor.java \
        app/src/test/java/com/aicompany/core/service/MissionExecutorTest.java
git commit -m "Extraer AgentTaskBatchRunner y consolidateAgentOutcomes de MissionExecutor (sin cambio de comportamiento)" \
           -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Git real — `GitCommandRunner`, `DevelopmentWorkspaceService`, config, Docker y borrado del workspace

**Files:**
- Create: `app/src/main/java/com/aicompany/core/service/GitCommandRunner.java`
- Create: `app/src/main/java/com/aicompany/core/service/DevelopmentWorkspaceService.java`
- Modify: `app/src/main/resources/application.yml`
- Modify: `app/Dockerfile`, `docker-compose.yml`
- Modify: `app/src/main/java/com/aicompany/core/service/MissionService.java` (borrar workspace)
- Test: `app/src/test/java/com/aicompany/core/service/DevelopmentWorkspaceServiceTest.java`
- Test: `app/src/test/java/com/aicompany/core/service/MissionServiceTest.java`

**Interfaces:**
- Consumes: `DevelopmentResult` (Task 1); `OwnedPaths` (Task 2).
- Produces: `GitCommandRunner.run(Path dir, String... args) -> String` (stdout; `IOException` si falla); `DevelopmentWorkspaceService(String workspaceRoot, GitCommandRunner git)`; `missionWorkspace(String missionId) -> Path`; `commitAgentWork(String missionId, String taskId, String agentId, String agentName, DevelopmentResult result) -> CommitRecord(String sha, List<String> files)` (`IOException` si falla); `filesAtCommit(String missionId, String sha) -> List<String>`; `readFileAtCommit(String missionId, String sha, String path) -> String`; `deleteWorkspace(String missionId)`; `static authorEmail(String agentId) -> String`; `MissionService(MissionMemoryService, MissionExecutor, CompanyEventPublisher, TeamMemoryService, DevelopmentWorkspaceService)`.

- [ ] **Step 1: Escribir `DevelopmentWorkspaceServiceTest` (falla: las clases no existen)**

```java
package com.aicompany.core.service;

import com.aicompany.core.agent.model.DevelopmentResult;
import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Contra un repo Git REAL en un directorio temporal (no se mockea ProcessBuilder). */
class DevelopmentWorkspaceServiceTest {

    @TempDir
    Path tempRoot;

    private final GitCommandRunner git = new GitCommandRunner();

    private DevelopmentWorkspaceService workspace() {
        return new DevelopmentWorkspaceService(tempRoot.toString(), git);
    }

    private static DevelopmentResult result(GeneratedFile... files) {
        return new DevelopmentResult("Estructura base del juego", List.of(files));
    }

    @Test
    void createsOneCommitPerAgentWithTheAgentAsAuthorAndTaskTrailers() throws Exception {
        var ws = workspace();

        var neo = ws.commitAgentWork("MISSION-1", "MISSION-1-ENGINEERING", "engineering", "Neo",
                result(new GeneratedFile("web/index.html", "<html></html>")));
        var mila = ws.commitAgentWork("MISSION-1", "MISSION-1-FRONTEND-UI", "frontend-ui", "Mila",
                result(new GeneratedFile("web/ui/hud.js", "export const hud = 1;")));

        var dir = ws.missionWorkspace("MISSION-1");
        assertEquals("Neo <engineering@agents.forjai.local>",
                git.run(dir, "show", "-s", "--format=%an <%ae>", neo.sha()).trim());
        assertEquals("MISSION-1-FRONTEND-UI",
                git.run(dir, "show", "-s", "--format=%(trailers:key=Forjai-Task,valueonly)", mila.sha()).trim());
        assertEquals("web/ui/hud.js",
                git.run(dir, "show", "--name-only", "--format=", mila.sha()).trim());
        assertEquals(List.of("web/ui/hud.js"), mila.files());
        assertEquals(40, neo.sha().length());
    }

    // Review Focus: la misma ruta dos veces no debe duplicar archivos ni entradas en AgentTask.files.
    @Test
    void duplicatedPathsKeepTheLastContentAndAppearOnce() throws Exception {
        var ws = workspace();

        var record = ws.commitAgentWork("MISSION-2", "MISSION-2-BACKEND", "backend", "Iris", result(
                new GeneratedFile("web/game/main.js", "v1"),
                new GeneratedFile("./web/game/main.js", "v2")));

        assertEquals(List.of("web/game/main.js"), record.files());
        assertEquals("v2", ws.readFileAtCommit("MISSION-2", record.sha(), "web/game/main.js"));
    }

    @Test
    void neverWritesInsideGitInternalsEvenIfTheGateWasBypassed() {
        var ws = workspace();

        assertThrows(IllegalStateException.class, () -> ws.commitAgentWork("MISSION-3", "T", "backend", "Iris",
                result(new GeneratedFile(".git/config", "[core]"))));
    }

    @Test
    void listsFilesAtACommitAndDeletesTheWorkspace() throws Exception {
        var ws = workspace();
        var record = ws.commitAgentWork("MISSION-4", "T", "engineering", "Neo",
                result(new GeneratedFile("web/index.html", "<html></html>")));

        assertEquals(List.of("web/index.html"), ws.filesAtCommit("MISSION-4", record.sha()));

        ws.deleteWorkspace("MISSION-4");

        assertFalse(Files.exists(ws.missionWorkspace("MISSION-4")));
        assertDoesNotThrow(() -> ws.deleteWorkspace("MISSION-4"));
    }

    @Test
    void rejectsMissionIdsThatEscapeTheWorkspaceRoot() {
        assertThrows(IllegalArgumentException.class, () -> workspace().missionWorkspace("../fuera"));
    }
}
```

- [ ] **Step 2: Correr el test para confirmar que falla**

Run: `cd app && mvn test -Dtest=DevelopmentWorkspaceServiceTest` → FAIL de compilación.

- [ ] **Step 3: Implementar `GitCommandRunner`**

```java
package com.aicompany.core.service;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Ejecuta git real vía ProcessBuilder (sin librería Git en el classpath).
 * Aislado de la config del usuario/sistema (GIT_CONFIG_GLOBAL/NOSYSTEM) y
 * con safe.directory=* porque en Docker el workspace es un bind mount de
 * otro dueño.
 */
@Component
public class GitCommandRunner {

    private static final long TIMEOUT_SECONDS = 30;

    public String run(Path dir, String... args) throws IOException {

        var command = new ArrayList<String>();
        command.add("git");
        command.add("-c");
        command.add("safe.directory=*");
        command.addAll(List.of(args));

        var builder = new ProcessBuilder(command).directory(dir.toFile());
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");

        var process = builder.start();
        var stderr = CompletableFuture.supplyAsync(() -> readQuietly(process.getErrorStream()));
        var stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        boolean finished;

        try {
            finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("git " + args[0] + " interrumpido", ex);
        }

        if (!finished) {
            process.destroyForcibly();
            throw new IOException("git " + args[0] + " superó el timeout de " + TIMEOUT_SECONDS + "s");
        }

        if (process.exitValue() != 0) {
            throw new IOException("git " + String.join(" ", args) + " falló (" + process.exitValue() + "): "
                    + stderr.join().trim());
        }

        return stdout;
    }

    private static String readQuietly(InputStream stream) {
        try {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "";
        }
    }
}
```

- [ ] **Step 4: Implementar `DevelopmentWorkspaceService`**

```java
package com.aicompany.core.service;

import com.aicompany.core.agent.model.DevelopmentResult;
import com.aicompany.core.agent.validation.OwnedPaths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Workspace real por misión = un repo Git real (spec §6). Cada agente
 * produce exactamente un commit con solo sus archivos, con él como autor y
 * trailers que lo enlazan a su misión/tarea. Nunca ejecuta el código.
 */
@Service
public class DevelopmentWorkspaceService {

    public record CommitRecord(String sha, List<String> files) {
    }

    private final Path workspaceRoot;
    private final GitCommandRunner git;

    public DevelopmentWorkspaceService(
            @Value("${products.workspace-root}") String workspaceRoot,
            GitCommandRunner git) {
        this.workspaceRoot = Path.of(workspaceRoot).toAbsolutePath().normalize();
        this.git = git;
    }

    public static String authorEmail(String agentId) {
        return agentId + "@agents.forjai.local";
    }

    public Path missionWorkspace(String missionId) {

        var dir = workspaceRoot.resolve(missionId).normalize();

        if (!dir.startsWith(workspaceRoot) || dir.equals(workspaceRoot)) {
            throw new IllegalArgumentException("missionId fuera del workspace: " + missionId);
        }

        return dir;
    }

    /**
     * Escribe los archivos (ya validados por DevelopmentPathValidationGate
     * y por ownedPaths en DevelopmentRuntime) y hace un commit solo con
     * ellos. Rutas repetidas: gana el último contenido, aparecen una vez.
     */
    public synchronized CommitRecord commitAgentWork(
            String missionId, String taskId, String agentId, String agentName, DevelopmentResult result)
            throws IOException {

        var dir = missionWorkspace(missionId);
        Files.createDirectories(dir);

        if (!Files.exists(dir.resolve(".git"))) {
            git.run(dir, "init", "-q");
        }

        var contentByPath = new LinkedHashMap<String, String>();

        for (var file : result.files()) {
            if (file != null) {
                contentByPath.put(OwnedPaths.normalize(file.path()), file.content());
            }
        }

        var gitDir = dir.resolve(".git");

        for (var entry : contentByPath.entrySet()) {

            var target = dir.resolve(entry.getKey()).normalize();

            // Defensa en profundidad: los gates ya deberían haber rechazado esto.
            if (!target.startsWith(dir) || target.startsWith(gitDir)) {
                throw new IllegalStateException("Ruta fuera del workspace permitido: " + entry.getKey());
            }

            Files.createDirectories(target.getParent());
            Files.writeString(target, entry.getValue(), StandardCharsets.UTF_8);
        }

        var paths = new ArrayList<>(contentByPath.keySet());

        var addArgs = new ArrayList<String>(List.of("add", "--"));
        addArgs.addAll(paths);
        git.run(dir, addArgs.toArray(String[]::new));

        var message = result.summary()
                + "\n\nForjai-Mission: " + missionId
                + "\nForjai-Task: " + taskId;

        git.run(dir,
                "-c", "user.name=Forjai company-core",
                "-c", "user.email=company-core@forjai.local",
                "-c", "commit.gpgsign=false",
                "commit", "-q",
                "--author", agentName + " <" + authorEmail(agentId) + ">",
                "-m", message);

        var sha = git.run(dir, "rev-parse", "HEAD").trim();

        return new CommitRecord(sha, List.copyOf(paths));
    }

    public List<String> filesAtCommit(String missionId, String sha) throws IOException {
        return lines(git.run(missionWorkspace(missionId), "ls-tree", "-r", "--name-only", sha));
    }

    public String readFileAtCommit(String missionId, String sha, String path) throws IOException {
        return git.run(missionWorkspace(missionId), "show", sha + ":" + OwnedPaths.normalize(path));
    }

    public void deleteWorkspace(String missionId) throws IOException {

        var dir = missionWorkspace(missionId);

        if (!Files.exists(dir)) {
            return;
        }

        try (var walk = Files.walk(dir)) {
            for (var path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    static List<String> lines(String output) {
        return Arrays.stream(output.split("\n"))
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .toList();
    }
}
```

- [ ] **Step 5: Config, Dockerfile y docker-compose**

`application.yml` — agregar después del bloque `ollama:`:

```yaml
products:
  workspace-root: ${PRODUCTS_WORKSPACE_ROOT:${user.home}/forjai-products}
```

`app/Dockerfile` — en el stage runtime, después de `FROM eclipse-temurin:21-jre`:

```dockerfile
RUN apt-get update \
    && apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/*
```

`docker-compose.yml` — en `company-core.environment` agregar `PRODUCTS_WORKSPACE_ROOT: /data/forjai-products`, y agregar al servicio:

```yaml
    volumes:
      - ${HOME}/forjai-products:/data/forjai-products
```

- [ ] **Step 6: `MissionService.delete` borra también el workspace**

Agregar el campo/parámetro `DevelopmentWorkspaceService workspace` como **5º** parámetro del constructor (después de `teamMemory`) y, en `delete`, inmediatamente después de `memory.deleteMission(missionId);`:

```java
        try {
            workspace.deleteWorkspace(missionId);
        } catch (Exception ex) {
            // Neo4j ya quedó limpio; el directorio huérfano no debe revertir el borrado.
            log.warn("MISSION {} - no se pudo borrar el workspace: {}", missionId, ex.getMessage());
        }
```

En `MissionServiceTest`: agregar el campo `private final DevelopmentWorkspaceService workspace = mock(DevelopmentWorkspaceService.class);` y actualizar las construcciones:

```bash
cd app
sed -i 's/, teamMemory);/, teamMemory, workspace);/' src/test/java/com/aicompany/core/service/MissionServiceTest.java
grep -c "teamMemory, workspace)" src/test/java/com/aicompany/core/service/MissionServiceTest.java
```

Agregar al test existente que borra `MISSION-42` con éxito (el que hace `assertTrue(service.delete("MISSION-42"))`) la línea:

```java
        verify(workspace).deleteWorkspace("MISSION-42");
```

y agregar a un test de borrado rechazado (p. ej. el de `MISSION-001`):

```java
        verify(workspace, never()).deleteWorkspace(anyString());
```

(`deleteWorkspace` declara `IOException`: agregar `throws Exception` a la firma de esos dos métodos de test.)

- [ ] **Step 7: Correr los tests**

Run: `cd app && mvn test -Dtest='DevelopmentWorkspaceServiceTest,MissionServiceTest'` → PASS (requiere `git` en el `PATH`).
Run: `cd app && mvn test` → BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/GitCommandRunner.java app/src/main/java/com/aicompany/core/service/DevelopmentWorkspaceService.java \
        app/src/main/resources/application.yml app/Dockerfile docker-compose.yml \
        app/src/main/java/com/aicompany/core/service/MissionService.java \
        app/src/test/java/com/aicompany/core/service/DevelopmentWorkspaceServiceTest.java app/src/test/java/com/aicompany/core/service/MissionServiceTest.java
git commit -m "Workspace Git real por misión: un commit por agente con autor y trailers; borrar misión borra su workspace" \
           -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: `RepositoryEvidenceGate` + `ForbiddenClaimsGuard` (TDD)

**Files:**
- Create: `app/src/main/java/com/aicompany/core/agent/validation/RepositoryEvidenceGate.java`
- Create: `app/src/main/java/com/aicompany/core/agent/validation/ForbiddenClaimsGuard.java`
- Test: `app/src/test/java/com/aicompany/core/agent/validation/RepositoryEvidenceGateTest.java`
- Test: `app/src/test/java/com/aicompany/core/agent/validation/ForbiddenClaimsGuardTest.java`

**Interfaces:**
- Consumes: `StaticReviewResult`, `AgentResult.Evidence(String description, String source, String sourceType, boolean verified)`.
- Produces: `RepositoryEvidenceGate.citation(String missionId, String sha, String path) -> String` (`static`); `RepositoryEvidenceGate.validate(StaticReviewResult review, String missionId, Map<String, Set<String>> filesBySha) -> List<String>`; `ForbiddenClaimsGuard.violations(List<String> texts) -> List<String>`.

- [ ] **Step 1: Escribir los tests (fallan: las clases no existen)**

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.AgentResult;
import com.aicompany.core.agent.model.StaticReviewResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RepositoryEvidenceGateTest {

    private static final String SHA = "a".repeat(40);
    private final RepositoryEvidenceGate gate = new RepositoryEvidenceGate();
    private final Map<String, Set<String>> filesBySha = Map.of(SHA, Set.of("web/index.html", "web/game/main.js"));

    private static StaticReviewResult reviewWith(List<AgentResult.Evidence> evidence) {
        return new StaticReviewResult("NO_EVIDENT_ISSUES", List.of(), List.of(), "coherente",
                List.of("No se verificó la ejecución."), evidence);
    }

    private static AgentResult.Evidence internal(String source) {
        return new AgentResult.Evidence("Revisé el archivo", source, "INTERNAL", true);
    }

    @Test
    void acceptsACitationOfARealFileInARealCommit() {
        var source = RepositoryEvidenceGate.citation("MISSION-1", SHA, "web/game/main.js");
        assertEquals(List.of(), gate.validate(reviewWith(List.of(internal(source))), "MISSION-1", filesBySha));
    }

    @Test
    void requiresAtLeastOneInternalCitation() {
        var errors = gate.validate(reviewWith(List.of()), "MISSION-1", filesBySha);
        assertFalse(errors.isEmpty());
    }

    @Test
    void rejectsAShaThatIsNotACommitOfTheMission() {
        var source = RepositoryEvidenceGate.citation("MISSION-1", "b".repeat(40), "web/index.html");
        var errors = gate.validate(reviewWith(List.of(internal(source))), "MISSION-1", filesBySha);
        assertTrue(errors.stream().anyMatch(e -> e.contains("no es un commit")), errors.toString());
    }

    @Test
    void rejectsAFileThatDoesNotExistInThatCommit() {
        var source = RepositoryEvidenceGate.citation("MISSION-1", SHA, "web/inventado.js");
        var errors = gate.validate(reviewWith(List.of(internal(source))), "MISSION-1", filesBySha);
        assertTrue(errors.stream().anyMatch(e -> e.contains("web/inventado.js")), errors.toString());
    }

    @Test
    void rejectsAMalformedSourceAndAnotherMissionsWorkspace() {
        assertFalse(gate.validate(reviewWith(List.of(internal("web/index.html"))), "MISSION-1", filesBySha).isEmpty());
        var other = RepositoryEvidenceGate.citation("MISSION-2", SHA, "web/index.html");
        assertFalse(gate.validate(reviewWith(List.of(internal(other))), "MISSION-1", filesBySha).isEmpty());
    }
}
```

```java
package com.aicompany.core.agent.validation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ForbiddenClaimsGuardTest {

    private final ForbiddenClaimsGuard guard = new ForbiddenClaimsGuard();

    @Test
    void flagsAffirmativeExecutionClaims() {
        assertFalse(guard.violations(List.of("El juego funciona correctamente.")).isEmpty());
        assertFalse(guard.violations(List.of("El proyecto compila sin errores")).isEmpty());
        assertFalse(guard.violations(List.of("Todo pasa los tests.")).isEmpty());
    }

    @Test
    void allowsNegatedStatementsAboutWhatCannotBeVerified() {
        assertEquals(List.of(), guard.violations(List.of("No se puede afirmar que el juego funciona sin ejecutarlo.")));
        assertEquals(List.of(), guard.violations(List.of("Sin ejecución no sabemos si compila.")));
    }

    @Test
    void doesNotConfuseRelatedWords() {
        assertEquals(List.of(), guard.violations(List.of("La funcionalidad del HUD está separada de la lógica.")));
        assertEquals(List.of(), guard.violations(List.of("Falta un script de compilación.")));
    }

    @Test
    void checksEachSentenceSeparately() {
        var violations = guard.violations(List.of("No hay tests. El juego funciona."));
        assertEquals(1, violations.size());
    }
}
```

- [ ] **Step 2: Correr los tests para confirmar que fallan**

Run: `cd app && mvn test -Dtest='RepositoryEvidenceGateTest,ForbiddenClaimsGuardTest'` → FAIL de compilación.

- [ ] **Step 3: Implementar `RepositoryEvidenceGate`**

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.StaticReviewResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Cada evidencia INTERNAL de la revisión estática debe citar un archivo
 * real de un commit real de ESTA misión (spec §7). Determinista, con
 * reintento en DevelopmentRuntime.
 */
@Component
public class RepositoryEvidenceGate {

    private static final Pattern CITATION = Pattern.compile("^workspace:([^@]+)@([0-9a-f]{40})/(.+)$");

    public static String citation(String missionId, String sha, String path) {
        return "workspace:" + missionId + "@" + sha + "/" + path;
    }

    public List<String> validate(StaticReviewResult review, String missionId, Map<String, Set<String>> filesBySha) {

        var errors = new ArrayList<String>();
        var internal = 0;

        var evidence = review == null || review.evidence() == null ? List.<com.aicompany.core.agent.model.AgentResult.Evidence>of() : review.evidence();

        for (var item : evidence.stream().filter(Objects::nonNull).toList()) {

            if (!"INTERNAL".equals(item.sourceType())) {
                continue;
            }

            internal++;

            var source = item.source() == null ? "" : item.source().strip();
            var matcher = CITATION.matcher(source);

            if (!matcher.matches()) {
                errors.add("La evidencia \"" + item.description() + "\" debe citar source=\"workspace:" + missionId
                        + "@<sha de 40 caracteres>/<ruta>\" (recibido: \"" + source + "\").");
                continue;
            }

            var citedMission = matcher.group(1);
            var sha = matcher.group(2);
            var path = matcher.group(3);

            if (!missionId.equals(citedMission)) {
                errors.add("La evidencia cita el workspace de " + citedMission + ", no el de " + missionId + ".");
            } else if (!filesBySha.containsKey(sha)) {
                errors.add("El sha " + sha + " no es un commit de esta misión. Commits válidos: " + filesBySha.keySet());
            } else if (!filesBySha.get(sha).contains(path)) {
                errors.add("El archivo \"" + path + "\" no existe en el commit " + sha + ".");
            }
        }

        if (internal == 0) {
            errors.add("La revisión debe citar al menos una evidencia sourceType=INTERNAL del repositorio de la misión.");
        }

        return errors;
    }
}
```

- [ ] **Step 4: Implementar `ForbiddenClaimsGuard`**

```java
package com.aicompany.core.agent.validation;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Heurística léxica (mismo espíritu que HEDGE_MARKERS de
 * ContradictionDetector): en esta fase nadie ejecuta código, así que una
 * afirmación de que compila/se ejecuta/funciona/pasa tests se rechaza. Una
 * oración con negación ("no", "sin", "nunca") se permite: es justamente lo
 * que se quiere que Vera diga. Puede tener falsos negativos.
 */
@Component
public class ForbiddenClaimsGuard {

    private static final List<Pattern> CLAIMS = List.of(
            Pattern.compile("\\bcompila\\b"),
            Pattern.compile("\\bcompilado\\b"),
            Pattern.compile("\\bse ejecuta\\b"),
            Pattern.compile("\\bejecuta correctamente\\b"),
            Pattern.compile("\\bfunciona\\b"),
            Pattern.compile("\\bfuncionan\\b"),
            Pattern.compile("\\bpasa(n)? (los|las) (tests|pruebas)\\b"),
            Pattern.compile("\\bes jugable\\b"),
            Pattern.compile("\\bcorre correctamente\\b")
    );

    private static final Pattern NEGATION = Pattern.compile("\\b(no|sin|nunca|imposible)\\b");

    public List<String> violations(List<String> texts) {

        var violations = new ArrayList<String>();

        if (texts == null) {
            return violations;
        }

        for (var text : texts) {

            if (text == null) {
                continue;
            }

            for (var sentence : text.split("[.!?\\n]")) {

                var normalized = normalize(sentence);

                if (normalized.isBlank() || NEGATION.matcher(normalized).find()) {
                    continue;
                }

                if (CLAIMS.stream().anyMatch(p -> p.matcher(normalized).find())) {
                    violations.add("Afirmación no verificable sin ejecutar código: \"" + sentence.strip()
                            + "\". Esta fase no ejecuta código; decláralo en notValidatableWithoutExecution.");
                }
            }
        }

        return violations;
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }
}
```

- [ ] **Step 5: Correr los tests**

Run: `cd app && mvn test -Dtest='RepositoryEvidenceGateTest,ForbiddenClaimsGuardTest'` → PASS (9 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aicompany/core/agent/validation/RepositoryEvidenceGate.java \
        app/src/main/java/com/aicompany/core/agent/validation/ForbiddenClaimsGuard.java \
        app/src/test/java/com/aicompany/core/agent/validation/RepositoryEvidenceGateTest.java \
        app/src/test/java/com/aicompany/core/agent/validation/ForbiddenClaimsGuardTest.java
git commit -m "Gates de la revisión estática: citas reales del repo y guard de afirmaciones de ejecución" \
           -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: `DevelopmentRuntime` — generación y revisión con reintento (TDD)

**Files:**
- Create: `app/src/main/java/com/aicompany/core/agent/DevelopmentRuntime.java`
- Test: `app/src/test/java/com/aicompany/core/agent/DevelopmentRuntimeTest.java`

**Interfaces:**
- Consumes: `CeoService.generateDevelopmentArtifact`/`reviewStaticWorkspace` (Task 6); `DevelopmentPathValidationGate`, `OwnedPaths` (Task 2); `RepositoryEvidenceGate`, `ForbiddenClaimsGuard` (Task 10); `EvidenceValidationGate.validate(List<AgentResult.Evidence>) -> ValidationResult(boolean valid, List<String> errors)` (existente).
- Produces: `DevelopmentRuntime.generate(String taskId, String missionId, String agentId, String prompt, List<String> ownedPaths) -> CompletableFuture<DevelopmentResult>` (en éxito deja la tarea en `GENERATED`, **no** `COMPLETED`: la completa el commit); `DevelopmentRuntime.review(String taskId, String missionId, String agentId, String prompt, Map<String, Set<String>> filesBySha) -> CompletableFuture<StaticReviewResult>` (en éxito `COMPLETED` + `EMPRESA_TASK_COMPLETED`). En fallo, ambos dejan `FAILED` + `EMPRESA_TASK_FAILED` y completan el future excepcionalmente.

- [ ] **Step 1: Escribir `DevelopmentRuntimeTest` (falla: la clase no existe)**

```java
package com.aicompany.core.agent;

import com.aicompany.core.agent.model.AgentResult;
import com.aicompany.core.agent.model.DevelopmentResult;
import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;
import com.aicompany.core.agent.model.StaticReviewResult;
import com.aicompany.core.agent.validation.DevelopmentPathValidationGate;
import com.aicompany.core.agent.validation.EvidenceValidationGate;
import com.aicompany.core.agent.validation.ForbiddenClaimsGuard;
import com.aicompany.core.agent.validation.RepositoryEvidenceGate;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.service.CeoService;
import com.aicompany.core.service.CompanyMemoryService;
import com.aicompany.core.service.MissionMemoryService;
import com.aicompany.core.service.PromptMemoryService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DevelopmentRuntimeTest {

    private static final String SHA = "c".repeat(40);

    private final MissionMemoryService memory = mock(MissionMemoryService.class);
    private final CompanyMemoryService companyMemory = mock(CompanyMemoryService.class);
    private final PromptMemoryService promptMemory = mock(PromptMemoryService.class);
    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    private final CeoService ceoService = mock(CeoService.class);

    private final DevelopmentRuntime runtime = new DevelopmentRuntime(
            ceoService, memory, companyMemory, promptMemory, "qwen3:8b", Runnable::run, events,
            new DevelopmentPathValidationGate(), new EvidenceValidationGate(), new RepositoryEvidenceGate(),
            new ForbiddenClaimsGuard(), JsonMapper.builder().build());

    {
        when(companyMemory.agentModel(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(promptMemory.activePrompt(anyString())).thenReturn("");
    }

    private static DevelopmentResult dev(String path) {
        return new DevelopmentResult("resumen", List.of(new GeneratedFile(path, "contenido")));
    }

    private static StaticReviewResult review(String architecture, String source) {
        return new StaticReviewResult("NO_EVIDENT_ISSUES", List.of(), List.of(), architecture,
                List.of("No se puede verificar la ejecución del juego."),
                List.of(new AgentResult.Evidence("Revisé main.js", source, "INTERNAL", true)));
    }

    private final Map<String, Set<String>> filesBySha = Map.of(SHA, Set.of("web/game/main.js"));
    private final String validSource = RepositoryEvidenceGate.citation("MISSION-1", SHA, "web/game/main.js");

    @Test
    void generateLeavesTheTaskGeneratedNotCompleted() throws Exception {
        when(ceoService.generateDevelopmentArtifact(eq("backend"), anyString(), anyString(), eq("qwen3:8b")))
                .thenReturn(dev("web/game/main.js"));

        var result = runtime.generate("T-1", "MISSION-1", "backend", "prompt", List.of("web/game")).get();

        assertEquals("web/game/main.js", result.files().get(0).path());
        verify(memory).updateTask(eq("T-1"), eq("GENERATED"), anyString());
        verify(memory, never()).updateTask(eq("T-1"), eq("COMPLETED"), anyString());
        verify(events, never()).publishTask(eq("EMPRESA_TASK_COMPLETED"), any(), any(), any(), any(), any());
    }

    @Test
    void anUnsafePathFailsTheTaskWithoutRetrying() {
        when(ceoService.generateDevelopmentArtifact(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(dev("../etc/passwd"));

        var future = runtime.generate("T-1", "MISSION-1", "backend", "prompt", List.of("web/game"));

        assertThrows(ExecutionException.class, future::get);
        verify(ceoService, times(1)).generateDevelopmentArtifact(anyString(), anyString(), anyString(), anyString());
        verify(memory).updateTask(eq("T-1"), eq("FAILED"), anyString());
    }

    @Test
    void aPathOutsideOwnedPathsIsRetriedWithCorrection() throws Exception {
        when(ceoService.generateDevelopmentArtifact(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(dev("docs/notas.md"))
                .thenReturn(dev("web/game/main.js"));

        runtime.generate("T-1", "MISSION-1", "backend", "prompt", List.of("web/game")).get();

        verify(ceoService).generateDevelopmentArtifact(anyString(),
                argThat(p -> p.contains("CORRECCIÓN DEL INTENTO ANTERIOR") && p.contains("docs/notas.md")),
                anyString(), anyString());
    }

    // Review Focus: "\" como separador es corregible → reintento, nunca un archivo con "\" en el nombre.
    @Test
    void aBackslashSeparatorIsRetriedNotWritten() throws Exception {
        when(ceoService.generateDevelopmentArtifact(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(dev("web\\game\\main.js"))
                .thenReturn(dev("web/game/main.js"));

        var result = runtime.generate("T-1", "MISSION-1", "backend", "prompt", List.of("web/game")).get();

        assertEquals("web/game/main.js", result.files().get(0).path());
        verify(ceoService, times(2)).generateDevelopmentArtifact(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void exhaustingRetriesFailsAndReturnsTheAgentToIdle() {
        when(ceoService.generateDevelopmentArtifact(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(dev("docs/notas.md"));

        assertThrows(ExecutionException.class,
                () -> runtime.generate("T-1", "MISSION-1", "backend", "prompt", List.of("web/game")).get());

        verify(ceoService, times(3)).generateDevelopmentArtifact(anyString(), anyString(), anyString(), anyString());
        var inOrder = inOrder(memory);
        inOrder.verify(memory).setAgentStatus("backend", "WORKING");
        inOrder.verify(memory).setAgentStatus("backend", "IDLE");
    }

    @Test
    void usesTheAgentsOwnPersistedModel() throws Exception {
        when(companyMemory.agentModel("backend", "qwen3:8b")).thenReturn("llama3:8b");
        when(ceoService.generateDevelopmentArtifact(eq("backend"), anyString(), anyString(), eq("llama3:8b")))
                .thenReturn(dev("web/game/main.js"));

        runtime.generate("T-1", "MISSION-1", "backend", "prompt", List.of("web/game")).get();

        verify(ceoService).generateDevelopmentArtifact(eq("backend"), anyString(), anyString(), eq("llama3:8b"));
    }

    @Test
    void reviewCompletesTheValidationTask() throws Exception {
        when(ceoService.reviewStaticWorkspace(eq("qa"), anyString(), anyString(), anyString()))
                .thenReturn(review("La arquitectura es coherente con el plan.", validSource));

        var result = runtime.review("T-QA", "MISSION-1", "qa", "prompt", filesBySha).get();

        assertEquals("NO_EVIDENT_ISSUES", result.verdict());
        verify(memory).updateTask(eq("T-QA"), eq("COMPLETED"), anyString());
        verify(events).publishTask(eq("EMPRESA_TASK_COMPLETED"), eq("T-QA"), eq("MISSION-1"), eq("qa"), eq("COMPLETED"), anyString());
    }

    @Test
    void reviewClaimingTheGameWorksIsRetried() throws Exception {
        when(ceoService.reviewStaticWorkspace(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(review("El juego funciona correctamente.", validSource))
                .thenReturn(review("La arquitectura es coherente con el plan.", validSource));

        runtime.review("T-QA", "MISSION-1", "qa", "prompt", filesBySha).get();

        verify(ceoService, times(2)).reviewStaticWorkspace(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void reviewCitingAnInventedFileIsRetried() throws Exception {
        var invented = RepositoryEvidenceGate.citation("MISSION-1", SHA, "web/inventado.js");
        when(ceoService.reviewStaticWorkspace(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(review("Coherente.", invented))
                .thenReturn(review("Coherente.", validSource));

        runtime.review("T-QA", "MISSION-1", "qa", "prompt", filesBySha).get();

        verify(ceoService).reviewStaticWorkspace(anyString(),
                argThat(p -> p.contains("web/inventado.js")), anyString(), anyString());
    }
}
```

(Si `EvidenceValidationGate` no tiene constructor sin argumentos, construirlo igual que lo hace su propio test existente: `grep -rn "new EvidenceValidationGate(" app/src/test`.)

- [ ] **Step 2: Correr el test para confirmar que falla**

Run: `cd app && mvn test -Dtest=DevelopmentRuntimeTest` → FAIL de compilación.

- [ ] **Step 3: Implementar `DevelopmentRuntime`**

```java
package com.aicompany.core.agent;

import com.aicompany.core.agent.model.DevelopmentResult;
import com.aicompany.core.agent.model.StaticReviewResult;
import com.aicompany.core.agent.validation.DevelopmentPathValidationGate;
import com.aicompany.core.agent.validation.EvidenceValidationGate;
import com.aicompany.core.agent.validation.ForbiddenClaimsGuard;
import com.aicompany.core.agent.validation.OwnedPaths;
import com.aicompany.core.agent.validation.RepositoryEvidenceGate;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.service.CeoService;
import com.aicompany.core.service.CompanyMemoryService;
import com.aicompany.core.service.MissionMemoryService;
import com.aicompany.core.service.PromptMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Espejo de AgentRuntime para los contratos de desarrollo (spec §6-7) —
 * AgentRuntime no se toca. Mismo reintento (MAX_RESULT_RETRIES + 1), mismo
 * WORKING/IDLE con finally, feedback determinista como CORRECCIÓN. Errores
 * "fatales" (ruta insegura) fallan sin reintento.
 */
@Service
public class DevelopmentRuntime {

    private static final Logger log = LoggerFactory.getLogger(DevelopmentRuntime.class);

    private static final int MAX_RESULT_RETRIES = 2;
    private static final Set<String> VERDICTS = Set.of("NO_EVIDENT_ISSUES", "ISSUES_FOUND");
    private static final Set<String> SEVERITIES = Set.of("BLOCKER", "MAJOR", "MINOR");

    @FunctionalInterface
    interface Attempt<T> {
        T call(String prompt, String model, String agentPrompt);
    }

    record Verdict(List<String> fatal, List<String> retryable) {
        boolean isOk() {
            return fatal.isEmpty() && retryable.isEmpty();
        }
    }

    private final CeoService ceoService;
    private final MissionMemoryService memory;
    private final CompanyMemoryService companyMemory;
    private final PromptMemoryService promptMemory;
    private final String defaultAgentModel;
    private final Executor agentTaskExecutor;
    private final CompanyEventPublisher events;
    private final DevelopmentPathValidationGate pathGate;
    private final EvidenceValidationGate evidenceGate;
    private final RepositoryEvidenceGate repositoryEvidenceGate;
    private final ForbiddenClaimsGuard forbiddenClaimsGuard;
    private final JsonMapper jsonMapper;

    public DevelopmentRuntime(
            CeoService ceoService,
            MissionMemoryService memory,
            CompanyMemoryService companyMemory,
            PromptMemoryService promptMemory,
            @Value("${ollama.agent-model}") String defaultAgentModel,
            @Qualifier("agentTaskExecutor") Executor agentTaskExecutor,
            CompanyEventPublisher events,
            DevelopmentPathValidationGate pathGate,
            EvidenceValidationGate evidenceGate,
            RepositoryEvidenceGate repositoryEvidenceGate,
            ForbiddenClaimsGuard forbiddenClaimsGuard,
            JsonMapper jsonMapper) {

        this.ceoService = ceoService;
        this.memory = memory;
        this.companyMemory = companyMemory;
        this.promptMemory = promptMemory;
        this.defaultAgentModel = defaultAgentModel;
        this.agentTaskExecutor = agentTaskExecutor;
        this.events = events;
        this.pathGate = pathGate;
        this.evidenceGate = evidenceGate;
        this.repositoryEvidenceGate = repositoryEvidenceGate;
        this.forbiddenClaimsGuard = forbiddenClaimsGuard;
        this.jsonMapper = jsonMapper;
    }

    public CompletableFuture<DevelopmentResult> generate(
            String taskId, String missionId, String agentId, String prompt, List<String> ownedPaths) {

        return submit(taskId, missionId, agentId, () -> executeWithRetries(
                taskId, missionId, agentId, prompt,
                (attemptPrompt, model, agentPrompt) ->
                        ceoService.generateDevelopmentArtifact(agentId, attemptPrompt, agentPrompt, model),
                result -> verifyGenerated(result, ownedPaths),
                "GENERATED"));
    }

    public CompletableFuture<StaticReviewResult> review(
            String taskId, String missionId, String agentId, String prompt, Map<String, Set<String>> filesBySha) {

        return submit(taskId, missionId, agentId, () -> executeWithRetries(
                taskId, missionId, agentId, prompt,
                (attemptPrompt, model, agentPrompt) ->
                        ceoService.reviewStaticWorkspace(agentId, attemptPrompt, agentPrompt, model),
                review -> verifyReview(review, missionId, filesBySha),
                "COMPLETED"));
    }

    private <T> CompletableFuture<T> submit(String taskId, String missionId, String agentId, Supplier<T> work) {
        try {
            return CompletableFuture.supplyAsync(work, agentTaskExecutor);
        } catch (Exception ex) {
            var message = safeMessage(ex, "No se pudo iniciar la tarea.");
            memory.updateTask(taskId, "FAILED", message);
            events.publishTask("EMPRESA_TASK_FAILED", taskId, missionId, agentId, "FAILED", message);
            return CompletableFuture.failedFuture(ex);
        }
    }

    private <T> T executeWithRetries(
            String taskId, String missionId, String agentId, String prompt,
            Attempt<T> attempt, Function<T, Verdict> verify, String successStatus) {

        memory.updateTask(taskId, "RUNNING", "Agente iniciado.");

        var model = companyMemory.agentModel(agentId, defaultAgentModel);
        var agentPrompt = promptMemory.activePrompt(agentId);

        memory.setAgentStatus(agentId, "WORKING");
        events.publishTask("EMPRESA_TASK_STARTED", taskId, missionId, agentId, "RUNNING", "Agente iniciado.");

        try {

            String feedback = null;

            for (int i = 0; i <= MAX_RESULT_RETRIES; i++) {

                if (i > 0) {
                    events.publishTask("EMPRESA_TASK_RETRY", taskId, missionId, agentId, "RETRYING",
                            "Reintentando. Intento " + (i + 1) + " de " + (MAX_RESULT_RETRIES + 1));
                }

                var attemptPrompt = feedback == null ? prompt : prompt + correctionBlock(feedback);

                T result;

                try {
                    result = attempt.call(attemptPrompt, model, agentPrompt);
                } catch (Exception ex) {
                    feedback = "- " + safeMessage(ex, "El agente no devolvió una respuesta procesable.");
                    continue;
                }

                var verdict = verify.apply(result);

                if (!verdict.fatal().isEmpty()) {
                    throw new IllegalStateException("Resultado rechazado sin reintento: "
                            + String.join("; ", verdict.fatal()));
                }

                if (verdict.isOk()) {

                    var json = toJson(result);
                    memory.updateTask(taskId, successStatus, json);

                    if ("COMPLETED".equals(successStatus)) {
                        events.publishTask("EMPRESA_TASK_COMPLETED", taskId, missionId, agentId, "COMPLETED", json);
                    }

                    log.info("TASK {} - agent {} produced a valid {}", taskId, agentId, successStatus);

                    return result;
                }

                feedback = "- " + String.join("\n- ", verdict.retryable());
            }

            throw new IllegalStateException("El agente " + agentId + " no produjo un resultado válido después de "
                    + (MAX_RESULT_RETRIES + 1) + " intentos:\n" + feedback);

        } catch (RuntimeException ex) {

            var message = safeMessage(ex, "Error inesperado");
            memory.updateTask(taskId, "FAILED", message);
            events.publishTask("EMPRESA_TASK_FAILED", taskId, missionId, agentId, "FAILED", message);
            log.error("TASK {} - agent {} failed: {}", taskId, agentId, message);
            throw ex;

        } finally {
            memory.setAgentStatus(agentId, "IDLE");
        }
    }

    private Verdict verifyGenerated(DevelopmentResult result, List<String> ownedPaths) {

        if (result == null || result.files() == null || result.files().isEmpty()) {
            return new Verdict(List.of(), List.of("Debes devolver al menos un archivo en files."));
        }

        var gate = pathGate.validate(result);

        if (!gate.valid()) {
            return new Verdict(gate.errors(), List.of());
        }

        var retryable = new ArrayList<String>();

        for (var file : result.files().stream().filter(Objects::nonNull).toList()) {

            if (file.path().contains("\\")) {
                retryable.add("Usa \"/\" como separador de rutas, no \"\\\": \"" + file.path() + "\".");
            } else if (!OwnedPaths.coveredByAny(ownedPaths, file.path())) {
                retryable.add("La ruta \"" + file.path() + "\" está fuera de tus ownedPaths " + ownedPaths + ".");
            }
        }

        return new Verdict(List.of(), retryable);
    }

    private Verdict verifyReview(StaticReviewResult review, String missionId, Map<String, Set<String>> filesBySha) {

        if (review == null) {
            return new Verdict(List.of(), List.of("La revisión está vacía."));
        }

        var errors = new ArrayList<String>();

        if (!VERDICTS.contains(review.verdict())) {
            errors.add("verdict debe ser NO_EVIDENT_ISSUES o ISSUES_FOUND.");
        }

        for (var finding : review.findingsOrEmpty()) {
            if (finding == null || !SEVERITIES.contains(finding.severity())) {
                errors.add("Cada finding debe tener severity BLOCKER, MAJOR o MINOR.");
            }
        }

        if (review.notValidatableWithoutExecution() == null || review.notValidatableWithoutExecution().isEmpty()) {
            errors.add("notValidatableWithoutExecution no puede estar vacío: esta fase no ejecuta código.");
        }

        var evidence = evidenceGate.validate(review.evidence());
        if (!evidence.valid()) {
            errors.addAll(evidence.errors());
        }

        errors.addAll(repositoryEvidenceGate.validate(review, missionId, filesBySha));

        var texts = new ArrayList<String>();
        if (review.architectureConsistency() != null) {
            texts.add(review.architectureConsistency());
        }
        review.findingsOrEmpty().stream()
                .filter(Objects::nonNull)
                .map(StaticReviewResult.Finding::description)
                .forEach(texts::add);

        errors.addAll(forbiddenClaimsGuard.violations(texts));

        return new Verdict(List.of(), errors);
    }

    private String correctionBlock(String feedback) {
        return """

                CORRECCIÓN DEL INTENTO ANTERIOR

                El resultado anterior fue rechazado por validaciones deterministas.
                Corrige únicamente estos errores:
                %s
                """.formatted(feedback);
    }

    private String toJson(Object value) {
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("No se pudo serializar el resultado.", ex);
        }
    }

    private String safeMessage(Exception ex, String defaultMessage) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? defaultMessage : ex.getMessage();
    }
}
```

- [ ] **Step 4: Correr los tests**

Run: `cd app && mvn test -Dtest=DevelopmentRuntimeTest` → PASS (9 tests).
Run: `cd app && mvn test` → BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/agent/DevelopmentRuntime.java app/src/test/java/com/aicompany/core/agent/DevelopmentRuntimeTest.java
git commit -m "Agregar DevelopmentRuntime: generación de código y revisión estática con reintento determinista" \
           -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: `StaticWorkspaceValidator` — capa 1 contra Git real (TDD)

**Files:**
- Create: `app/src/main/java/com/aicompany/core/service/StaticWorkspaceValidator.java`
- Test: `app/src/test/java/com/aicompany/core/service/StaticWorkspaceValidatorTest.java`

**Interfaces:**
- Consumes: `DevelopmentWorkspaceService.missionWorkspace`/`authorEmail`/`commitAgentWork` (Task 9); `GitCommandRunner.run` (Task 9); `OwnedPaths` (Task 2); `StaticCheck` (Task 1).
- Produces: `StaticWorkspaceValidator.CommittedWork(String taskId, String agentId, String commitSha, List<String> files)`; `StaticWorkspaceValidator.validate(String missionId, List<CommittedWork> work, String entryPoint, List<String> allowedPaths) -> List<StaticCheck>`. Nombres de chequeo: `REPOSITORY_EXISTS`, `COMMIT_EXISTS`, `COMMIT_AUTHOR`, `COMMIT_TASK_TRAILER`, `FILES_IN_COMMIT`, `COMMIT_READABLE`, `HEAD_READABLE`, `NO_SYMLINKS`, `PATHS_WITHIN_OWNED`, `ENTRY_POINT`.

- [ ] **Step 1: Escribir el test (falla: la clase no existe)**

```java
package com.aicompany.core.service;

import com.aicompany.core.agent.model.DevelopmentResult;
import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;
import com.aicompany.core.model.StaticCheck;
import com.aicompany.core.service.StaticWorkspaceValidator.CommittedWork;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StaticWorkspaceValidatorTest {

    @TempDir
    Path tempRoot;

    private final GitCommandRunner git = new GitCommandRunner();
    private DevelopmentWorkspaceService workspace;
    private StaticWorkspaceValidator validator;
    private final List<CommittedWork> work = new ArrayList<>();
    private static final List<String> ALLOWED = List.of("web/index.html", "web/game");

    @BeforeEach
    void setUp() throws Exception {
        workspace = new DevelopmentWorkspaceService(tempRoot.toString(), git);
        validator = new StaticWorkspaceValidator(workspace, git);

        var neo = workspace.commitAgentWork("M-1", "M-1-ENGINEERING", "engineering", "Neo",
                new DevelopmentResult("base", List.of(new GeneratedFile("web/index.html", "<html></html>"))));
        work.add(new CommittedWork("M-1-ENGINEERING", "engineering", neo.sha(), neo.files()));

        var iris = workspace.commitAgentWork("M-1", "M-1-BACKEND", "backend", "Iris",
                new DevelopmentResult("lógica", List.of(new GeneratedFile("web/game/main.js", "export const x = 1;"))));
        work.add(new CommittedWork("M-1-BACKEND", "backend", iris.sha(), iris.files()));
    }

    private static StaticCheck find(List<StaticCheck> checks, String name) {
        return checks.stream().filter(c -> c.check().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void allChecksPassForARealWellFormedRepository() {
        var checks = validator.validate("M-1", work, "web/index.html", ALLOWED);
        assertTrue(checks.stream().allMatch(StaticCheck::passed), checks.toString());
    }

    @Test
    void failsWhenTheCommitAuthorIsNotTheAgent() {
        var wrong = new CommittedWork("M-1-BACKEND", "frontend-ui", work.get(1).commitSha(), work.get(1).files());
        var checks = validator.validate("M-1", List.of(work.get(0), wrong), "web/index.html", ALLOWED);
        assertTrue(checks.stream().anyMatch(c -> c.check().equals("COMMIT_AUTHOR") && !c.passed()), checks.toString());
    }

    @Test
    void failsWhenTheTrailerDoesNotMatchTheTask() {
        var wrong = new CommittedWork("M-1-OTRA", "backend", work.get(1).commitSha(), work.get(1).files());
        var checks = validator.validate("M-1", List.of(wrong), "web/index.html", ALLOWED);
        assertFalse(find(checks, "COMMIT_TASK_TRAILER").passed());
    }

    @Test
    void failsWhenADeclaredFileIsNotInTheCommit() {
        var wrong = new CommittedWork("M-1-BACKEND", "backend", work.get(1).commitSha(), List.of("web/game/fantasma.js"));
        var checks = validator.validate("M-1", List.of(wrong), "web/index.html", ALLOWED);
        assertFalse(find(checks, "FILES_IN_COMMIT").passed());
    }

    @Test
    void failsForAShaThatDoesNotExist() {
        var wrong = new CommittedWork("M-1-BACKEND", "backend", "d".repeat(40), List.of("web/game/main.js"));
        var checks = validator.validate("M-1", List.of(wrong), "web/index.html", ALLOWED);
        assertFalse(find(checks, "COMMIT_EXISTS").passed());
    }

    @Test
    void failsWhenTheEntryPointIsMissing() {
        var checks = validator.validate("M-1", work, "web/main.html", List.of("web"));
        assertFalse(find(checks, "ENTRY_POINT").passed());
    }

    @Test
    void failsWhenFilesAreOutsideTheOwnedPaths() {
        var checks = validator.validate("M-1", work, "web/index.html", List.of("web/index.html"));
        assertFalse(find(checks, "PATHS_WITHIN_OWNED").passed());
    }

    @Test
    void failsWhenTheRepositoryContainsASymlink() throws Exception {
        var dir = workspace.missionWorkspace("M-1");
        Files.createSymbolicLink(dir.resolve("web/game/link.js"), Path.of("/etc/passwd"));
        git.run(dir, "add", "web/game/link.js");
        git.run(dir, "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false",
                "commit", "-q", "-m", "symlink");

        var checks = validator.validate("M-1", work, "web/index.html", ALLOWED);
        assertFalse(find(checks, "NO_SYMLINKS").passed());
    }

    @Test
    void failsWhenThereIsNoRepository() {
        var checks = validator.validate("M-SIN-REPO", work, "web/index.html", ALLOWED);
        assertEquals(1, checks.size());
        assertFalse(checks.get(0).passed());
    }
}
```

- [ ] **Step 2: Correr el test para confirmar que falla**

Run: `cd app && mvn test -Dtest=StaticWorkspaceValidatorTest` → FAIL de compilación.

- [ ] **Step 3: Implementar `StaticWorkspaceValidator`**

```java
package com.aicompany.core.service;

import com.aicompany.core.agent.validation.OwnedPaths;
import com.aicompany.core.model.StaticCheck;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Capa 1 de la validación estática (spec §7): chequeos deterministas
 * contra el repo Git real de la misión. Sin LLM, sin ejecutar código.
 */
@Component
public class StaticWorkspaceValidator {

    public record CommittedWork(String taskId, String agentId, String commitSha, List<String> files) {
    }

    private final DevelopmentWorkspaceService workspace;
    private final GitCommandRunner git;

    public StaticWorkspaceValidator(DevelopmentWorkspaceService workspace, GitCommandRunner git) {
        this.workspace = workspace;
        this.git = git;
    }

    public List<StaticCheck> validate(
            String missionId, List<CommittedWork> work, String entryPoint, List<String> allowedPaths) {

        var checks = new ArrayList<StaticCheck>();
        var dir = workspace.missionWorkspace(missionId);

        if (!Files.isDirectory(dir.resolve(".git"))) {
            checks.add(StaticCheck.fail("REPOSITORY_EXISTS", "No existe un repositorio Git en " + dir, null, List.of()));
            return checks;
        }

        checks.add(StaticCheck.pass("REPOSITORY_EXISTS", dir.toString(), null, List.of()));

        for (var item : work) {
            validateCommit(dir, item, checks);
        }

        validateHead(dir, entryPoint, allowedPaths, checks);

        return checks;
    }

    private void validateCommit(Path dir, CommittedWork item, List<StaticCheck> checks) {

        var sha = item.commitSha();
        String type;

        try {
            type = git.run(dir, "cat-file", "-t", sha).trim();
        } catch (IOException ex) {
            type = "";
        }

        if (!"commit".equals(type)) {
            checks.add(StaticCheck.fail("COMMIT_EXISTS",
                    "El commit de " + item.agentId() + " no existe en el repositorio.", sha, List.of()));
            return;
        }

        checks.add(StaticCheck.pass("COMMIT_EXISTS", "Commit de " + item.agentId(), sha, List.of()));

        try {

            var email = git.run(dir, "show", "-s", "--format=%ae", sha).trim();
            var expectedEmail = DevelopmentWorkspaceService.authorEmail(item.agentId());
            checks.add(email.equals(expectedEmail)
                    ? StaticCheck.pass("COMMIT_AUTHOR", "Autor " + email, sha, List.of())
                    : StaticCheck.fail("COMMIT_AUTHOR", "Autor " + email + ", esperado " + expectedEmail, sha, List.of()));

            var trailer = git.run(dir, "show", "-s", "--format=%(trailers:key=Forjai-Task,valueonly)", sha).trim();
            checks.add(trailer.equals(item.taskId())
                    ? StaticCheck.pass("COMMIT_TASK_TRAILER", "Forjai-Task=" + trailer, sha, List.of())
                    : StaticCheck.fail("COMMIT_TASK_TRAILER",
                            "Forjai-Task=" + trailer + ", esperado " + item.taskId(), sha, List.of()));

            var inCommit = Set.copyOf(DevelopmentWorkspaceService.lines(
                    git.run(dir, "ls-tree", "-r", "--name-only", sha)));
            var declared = item.files() == null ? List.<String>of() : item.files();
            var missing = declared.stream().filter(f -> !inCommit.contains(f)).toList();

            if (declared.isEmpty()) {
                checks.add(StaticCheck.fail("FILES_IN_COMMIT",
                        "La tarea de " + item.agentId() + " no declaró archivos.", sha, List.of()));
            } else if (!missing.isEmpty()) {
                checks.add(StaticCheck.fail("FILES_IN_COMMIT",
                        "Archivos declarados que no están en el commit: " + missing, sha, missing));
            } else {
                checks.add(StaticCheck.pass("FILES_IN_COMMIT",
                        declared.size() + " archivo(s) presentes en el commit", sha, declared));
            }

        } catch (IOException ex) {
            checks.add(StaticCheck.fail("COMMIT_READABLE",
                    "No se pudo leer el commit de " + item.agentId() + ": " + ex.getMessage(), sha, List.of()));
        }
    }

    private void validateHead(Path dir, String entryPoint, List<String> allowedPaths, List<StaticCheck> checks) {

        List<String> entries;

        try {
            entries = DevelopmentWorkspaceService.lines(git.run(dir, "ls-tree", "-r", "HEAD"));
        } catch (IOException ex) {
            checks.add(StaticCheck.fail("HEAD_READABLE", "No se pudo leer HEAD: " + ex.getMessage(), null, List.of()));
            return;
        }

        var symlinks = new ArrayList<String>();
        var outside = new ArrayList<String>();

        for (var entry : entries) {

            var tab = entry.indexOf('\t');
            var space = entry.indexOf(' ');

            if (tab < 0 || space < 0) {
                continue;
            }

            var mode = entry.substring(0, space);
            var path = entry.substring(tab + 1);

            if ("120000".equals(mode)) {
                symlinks.add(path);
            }

            if (!OwnedPaths.coveredByAny(allowedPaths, path)) {
                outside.add(path);
            }
        }

        checks.add(symlinks.isEmpty()
                ? StaticCheck.pass("NO_SYMLINKS", "Sin symlinks en HEAD", null, List.of())
                : StaticCheck.fail("NO_SYMLINKS", "Symlinks en el repositorio: " + symlinks, null, symlinks));

        checks.add(outside.isEmpty()
                ? StaticCheck.pass("PATHS_WITHIN_OWNED",
                        entries.size() + " archivo(s) dentro de los ownedPaths del plan", null, List.of())
                : StaticCheck.fail("PATHS_WITHIN_OWNED",
                        "Archivos fuera de los ownedPaths del plan: " + outside, null, outside));

        if (entryPoint == null || entryPoint.isBlank()) {
            checks.add(StaticCheck.fail("ENTRY_POINT", "El plan no declaró entryPoint.", null, List.of()));
            return;
        }

        var normalizedEntryPoint = OwnedPaths.normalize(entryPoint);

        try {
            var content = git.run(dir, "show", "HEAD:" + normalizedEntryPoint);
            checks.add(content.isBlank()
                    ? StaticCheck.fail("ENTRY_POINT", "El entryPoint " + normalizedEntryPoint + " está vacío.",
                            null, List.of(normalizedEntryPoint))
                    : StaticCheck.pass("ENTRY_POINT", "entryPoint " + normalizedEntryPoint + " presente",
                            null, List.of(normalizedEntryPoint)));
        } catch (IOException ex) {
            checks.add(StaticCheck.fail("ENTRY_POINT", "El entryPoint " + normalizedEntryPoint + " no existe en HEAD.",
                    null, List.of(normalizedEntryPoint)));
        }
    }
}
```

- [ ] **Step 4: Correr los tests**

Run: `cd app && mvn test -Dtest=StaticWorkspaceValidatorTest` → PASS (9 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/StaticWorkspaceValidator.java app/src/test/java/com/aicompany/core/service/StaticWorkspaceValidatorTest.java
git commit -m "Agregar StaticWorkspaceValidator: chequeos deterministas contra el repo Git real" \
           -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 13: `TeamExecutionStrategy`, `AnalysisTeamStrategy` y bifurcación por equipo en `MissionExecutor`

**Files:**
- Create: `app/src/main/java/com/aicompany/core/model/TeamMissionContext.java`
- Create: `app/src/main/java/com/aicompany/core/model/TeamExecutionResult.java`
- Create: `app/src/main/java/com/aicompany/core/service/MissionProgress.java`
- Create: `app/src/main/java/com/aicompany/core/service/TeamExecutionStrategy.java`
- Create: `app/src/main/java/com/aicompany/core/service/AnalysisTeamStrategy.java`
- Modify: `app/src/main/java/com/aicompany/core/service/MissionExecutor.java`
- Modify: `app/src/test/java/com/aicompany/core/service/MissionExecutorTest.java` (solo construcción)
- Test: `app/src/test/java/com/aicompany/core/service/MissionExecutorTeamTest.java`

**Interfaces:**
- Consumes: `TeamWorkPlanner.plan` (Task 7); `AgentTaskBatchRunner.run` (Task 8); `MissionMemoryService.teamId` (Task 4); `TeamMemoryService.teamType` (Task 4); `consolidateAgentOutcomes` (Task 8).
- Produces: `TeamMissionContext(String missionId, String instruction, TeamSnapshot team, TeamPlan plan)`; `sealed interface TeamExecutionResult` con `AgentOutcomes(List<AgentExecutionOutcome> outcomes)` y `Development(String resultsForCeo, String verifiableState)`; `MissionProgress.advance(MissionStatus, int, String, String)`; `TeamExecutionStrategy { TeamExecutionMode mode(); TeamExecutionResult execute(TeamMissionContext, MissionProgress); }`; constructor final de `MissionExecutor` = el de Task 8 + `TeamWorkPlanner teamWorkPlanner, List<TeamExecutionStrategy> teamStrategies` al final.

- [ ] **Step 1: Crear los tipos**

```java
package com.aicompany.core.model;

import com.aicompany.core.agent.model.TeamPlan;

public record TeamMissionContext(String missionId, String instruction, TeamSnapshot team, TeamPlan plan) {
}
```

```java
package com.aicompany.core.model;

import java.util.List;

/**
 * Lo que devuelve una estrategia de equipo a MissionExecutor, que sigue
 * siendo el dueño de la consolidación: resultados de AgentRuntime (se
 * consolidan como discovery) o el reporte de desarrollo + el bloque
 * "Estado verificable" generado por Java.
 */
public sealed interface TeamExecutionResult {

    record AgentOutcomes(List<AgentExecutionOutcome> outcomes) implements TeamExecutionResult {
    }

    record Development(String resultsForCeo, String verifiableState) implements TeamExecutionResult {
    }
}
```

```java
package com.aicompany.core.service;

import com.aicompany.core.model.MissionStatus;

/** Callback a MissionExecutor.advanceMission: la estrategia nunca persiste transiciones por su cuenta. */
@FunctionalInterface
public interface MissionProgress {
    void advance(MissionStatus status, int progress, String currentStep, String message);
}
```

```java
package com.aicompany.core.service;

import com.aicompany.core.model.TeamExecutionMode;
import com.aicompany.core.model.TeamExecutionResult;
import com.aicompany.core.model.TeamMissionContext;

/** Cómo ejecuta un tipo de equipo las tareas del plan ya validado (spec §2). */
public interface TeamExecutionStrategy {

    TeamExecutionMode mode();

    TeamExecutionResult execute(TeamMissionContext context, MissionProgress progress);
}
```

- [ ] **Step 2: Crear `AnalysisTeamStrategy`**

```java
package com.aicompany.core.service;

import com.aicompany.core.agent.model.TeamPlan;
import com.aicompany.core.model.MissionStatus;
import com.aicompany.core.model.TeamExecutionMode;
import com.aicompany.core.model.TeamExecutionResult;
import com.aicompany.core.model.TeamMissionContext;
import org.springframework.stereotype.Service;

/**
 * Creative / Product Intelligence y Marketing & Growth: las tareas WORK
 * del plan corren con el AgentRuntime actual (gates, reintento,
 * replanificación) vía AgentTaskBatchRunner. Sin capacidades nuevas.
 */
@Service
public class AnalysisTeamStrategy implements TeamExecutionStrategy {

    private final AgentTaskBatchRunner batchRunner;

    public AnalysisTeamStrategy(AgentTaskBatchRunner batchRunner) {
        this.batchRunner = batchRunner;
    }

    @Override
    public TeamExecutionMode mode() {
        return TeamExecutionMode.ANALYSIS;
    }

    @Override
    public TeamExecutionResult execute(TeamMissionContext context, MissionProgress progress) {

        var definitions = context.plan().workTasks().stream()
                .map(t -> new AgentTaskBatchRunner.AgentTaskDefinition(
                        t.agentId(), t.action(), t.objective(), TeamPlan.KIND_WORK))
                .toList();

        var outcomes = batchRunner.run(
                context.missionId(),
                context.instruction(),
                definitions,
                () -> progress.advance(
                        MissionStatus.WAITING_AGENT_RESULTS,
                        30,
                        "Trabajo del equipo",
                        "Los miembros de " + context.team().teamName() + " están trabajando en paralelo."));

        return new TeamExecutionResult.AgentOutcomes(outcomes);
    }
}
```

- [ ] **Step 3: Escribir `MissionExecutorTeamTest` (falla: el constructor de `MissionExecutor` no acepta planner/estrategias)**

```java
package com.aicompany.core.service;

import com.aicompany.core.agent.AgentRuntime;
import com.aicompany.core.agent.model.AgentResult;
import com.aicompany.core.agent.model.TeamPlan;
import com.aicompany.core.agent.model.TeamPlan.PlannedTask;
import com.aicompany.core.agent.validation.ContradictionDetector;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MissionExecutorTeamTest {

    private final MissionMemoryService memory = mock(MissionMemoryService.class);
    private final AgentRuntime runtime = mock(AgentRuntime.class);
    private final CeoService ceoService = mock(CeoService.class);
    private final CompanyMemoryService companyMemory = mock(CompanyMemoryService.class);
    private final PromptMemoryService promptMemory = mock(PromptMemoryService.class);
    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    private final ContradictionDetector contradictionDetector = mock(ContradictionDetector.class);
    private final CompanyPolicyService companyPolicyService = mock(CompanyPolicyService.class);
    private final OpportunityMemoryService opportunityMemory = mock(OpportunityMemoryService.class);
    private final AlertMailService alertMailService = mock(AlertMailService.class);
    private final TeamWorkPlanner planner = mock(TeamWorkPlanner.class);
    private final TeamExecutionStrategy analysis = mock(TeamExecutionStrategy.class);
    private final TeamExecutionStrategy development = mock(TeamExecutionStrategy.class);

    private final MissionExecutor executor = new MissionExecutor(
            memory, new AgentTaskBatchRunner(memory, runtime, events), ceoService, companyMemory, promptMemory,
            "qwen2.5-coder:14b", Runnable::run, events, JsonMapper.builder().build(), contradictionDetector,
            companyPolicyService, opportunityMemory, alertMailService, planner, List.of(analysis, development));

    {
        when(companyMemory.agentModel(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(promptMemory.activePrompt(anyString())).thenReturn("");
        when(companyPolicyService.activeValue(PolicyKey.SEED_CAPITAL_USD)).thenReturn(50.0);
        when(companyPolicyService.activeValue(PolicyKey.CONTRADICTION_SEED_CAPITAL_MULTIPLE)).thenReturn(100.0);
        when(analysis.mode()).thenReturn(TeamExecutionMode.ANALYSIS);
        when(development.mode()).thenReturn(TeamExecutionMode.DEVELOPMENT);
        when(ceoService.executeMission(anyString(), anyString(), anyString(), anyString())).thenReturn("consolidado");
    }

    private static TeamPlanResult planned(String teamId) {
        var team = new TeamSnapshot(teamId, "Equipo", "ACTIVE", "engineering", List.of(
                new TeamMemberInfo("engineering", "Neo", "rol", "ROLE", List.of("x"), "qwen3:8b")));
        var plan = new TeamPlan("plan", "HTML5", "web/index.html", List.of(
                new PlannedTask("engineering", "WORK", "BUILD", "Construir", List.of("x"), List.of("web"))));
        return new TeamPlanResult(team, plan);
    }

    @Test
    void anEngineeringMissionNeverCreatesDiscoveryTasks() throws Exception {
        when(memory.teamId("M-1")).thenReturn(Optional.of("TEAM-ENGINEERING"));
        when(planner.plan("M-1", "TEAM-ENGINEERING", "crear un juego", TeamExecutionMode.DEVELOPMENT))
                .thenReturn(planned("TEAM-ENGINEERING"));
        when(development.execute(any(), any()))
                .thenReturn(new TeamExecutionResult.Development("reporte", "ESTADO VERIFICABLE ..."));

        executor.executeAsync("M-1", "crear un juego").get();

        verifyNoInteractions(runtime);
        verify(memory, never()).createTask(anyString(), anyString(), eq("finance"), anyString());
        verify(memory, never()).createTask(anyString(), anyString(), eq("sales"), anyString());
        verify(memory, never()).createTask(anyString(), anyString(), eq("product"), anyString());
        verify(analysis, never()).execute(any(), any());
    }

    @Test
    void developmentResultsEndWithTheJavaGeneratedVerifiableState() throws Exception {
        when(memory.teamId("M-1")).thenReturn(Optional.of("TEAM-ENGINEERING"));
        when(planner.plan(anyString(), anyString(), anyString(), any())).thenReturn(planned("TEAM-ENGINEERING"));
        when(development.execute(any(), any()))
                .thenReturn(new TeamExecutionResult.Development("reporte", "ESTADO VERIFICABLE X"));

        executor.executeAsync("M-1", "crear un juego").get();

        verify(ceoService).executeMission(eq("crear un juego"), eq("reporte"), anyString(), anyString());
        verify(memory).updateMission(eq("M-1"), eq(MissionStatus.AWAITING_INVESTOR), anyInt(), anyString(),
                eq("consolidado\n\nESTADO VERIFICABLE X"));
    }

    @Test
    void anAnalysisTeamIsConsolidatedLikeDiscovery() throws Exception {
        when(memory.teamId("M-2")).thenReturn(Optional.of("TEAM-MARKETING-GROWTH"));
        when(planner.plan("M-2", "TEAM-MARKETING-GROWTH", "lanzar", TeamExecutionMode.ANALYSIS))
                .thenReturn(planned("TEAM-MARKETING-GROWTH"));
        var result = new AgentResult(
                "growth-content", "SEO_PLAN", "NOT_VALIDATED",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                "recomendación", 0.5);
        when(analysis.execute(any(), any())).thenReturn(new TeamExecutionResult.AgentOutcomes(
                List.of(AgentExecutionOutcome.success("growth-content", result))));
        when(contradictionDetector.detect(any(), anyDouble(), anyDouble())).thenReturn(List.of());

        executor.executeAsync("M-2", "lanzar").get();

        verify(development, never()).execute(any(), any());
        verify(memory).updateMission(eq("M-2"), eq(MissionStatus.AWAITING_INVESTOR), anyInt(), anyString(), eq("consolidado"));
    }

    @Test
    void anInvalidPlanFailsTheMission() throws Exception {
        when(memory.teamId("M-3")).thenReturn(Optional.of("TEAM-ENGINEERING"));
        when(planner.plan(anyString(), anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("El líder engineering no produjo un plan válido"));

        executor.executeAsync("M-3", "x").get();

        verify(memory).updateMission(eq("M-3"), eq(MissionStatus.FAILED), eq(100), anyString(),
                contains("no produjo un plan válido"));
    }

    @Test
    void aMissionWithoutTeamNeverCallsThePlanner() throws Exception {
        when(memory.teamId("M-4")).thenReturn(Optional.empty());

        executor.executeAsync("M-4", "x").get();

        verifyNoInteractions(planner);
    }
}
```

(`AgentResult` se construye real, con los mismos 12 argumentos que usa el helper `stubAgent` de `MissionExecutorTest`: un mock no se puede serializar con Jackson en `serializeAgentResults`.)

- [ ] **Step 4: Correr el test para confirmar que falla**

Run: `cd app && mvn test -Dtest=MissionExecutorTeamTest` → FAIL de compilación.

- [ ] **Step 5: Bifurcación en `MissionExecutor`**

1. Agregar campos y parámetros de constructor (al final de la lista existente):

```java
    private final TeamWorkPlanner teamWorkPlanner;
    private final List<TeamExecutionStrategy> teamStrategies;
```

```java
            AlertMailService alertMailService,
            TeamWorkPlanner teamWorkPlanner,
            List<TeamExecutionStrategy> teamStrategies) {
        ...
        this.teamWorkPlanner = teamWorkPlanner;
        this.teamStrategies = teamStrategies;
```

2. En `executeInternal`, como **primera** sentencia dentro del `try` (antes del `advanceMission(PLANNING...)` de discovery):

```java
            var teamId = memory.teamId(missionId).orElse(null);

            if (teamId != null) {
                executeTeamMission(missionId, instruction, teamId);
                return;
            }
```

3. Agregar los métodos nuevos:

```java
    /**
     * Misión con teamId (spec §2): el líder planifica, el plan se valida
     * en Java y la estrategia del tipo de equipo ejecuta. Nunca crea las 5
     * tareas fijas de discovery.
     */
    private void executeTeamMission(String missionId, String instruction, String teamId) {

        var teamType = TeamMemoryService.teamType(teamId)
                .orElseThrow(() -> new IllegalStateException("teamId desconocido: " + teamId));

        var mode = TeamExecutionMode.forTeamType(teamType);

        var strategy = teamStrategies.stream()
                .filter(s -> s.mode() == mode)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No hay estrategia de ejecución para " + mode));

        advanceMission(
                missionId,
                MissionStatus.PLANNING,
                5,
                "Planificación del equipo",
                "El líder de " + teamId + " está descomponiendo el trabajo."
        );

        var planned = teamWorkPlanner.plan(missionId, teamId, instruction, mode);

        advanceMission(
                missionId,
                MissionStatus.DELEGATING,
                10,
                "Delegación",
                "Plan del líder validado: " + planned.plan().tasksOrEmpty().size()
                        + " tareas para " + planned.team().teamName() + "."
        );

        var context = new TeamMissionContext(missionId, instruction, planned.team(), planned.plan());

        var result = strategy.execute(context, (status, progress, step, message) ->
                advanceMission(missionId, status, progress, step, message));

        switch (result) {
            case TeamExecutionResult.AgentOutcomes outcomes -> consolidateAgentOutcomes(
                    missionId, instruction, outcomes.outcomes(),
                    companyPolicyService.activeValue(PolicyKey.SEED_CAPITAL_USD));
            case TeamExecutionResult.Development development ->
                    consolidateDevelopment(missionId, instruction, development);
        }
    }

    /**
     * El CEO consolida; el bloque "Estado verificable" lo agrega Java al
     * final, así que los hechos verificables no dependen de la redacción
     * del modelo (spec §8).
     */
    private void consolidateDevelopment(
            String missionId, String instruction, TeamExecutionResult.Development development) {

        advanceMission(
                missionId,
                MissionStatus.CONSOLIDATING,
                85,
                "Consolidación",
                "CEO está consolidando el resultado del equipo."
        );

        var finalResult = ceoService.executeMission(
                instruction,
                development.resultsForCeo(),
                promptMemory.activePrompt("ceo"),
                companyMemory.agentModel("ceo", defaultCeoModel)
        );

        advanceMission(
                missionId,
                MissionStatus.AWAITING_INVESTOR,
                95,
                "Recomendación",
                finalResult + "\n\n" + development.verifiableState()
        );

        log.info("MISSION {} -> AWAITING_INVESTOR (team development)", missionId);
    }
```

(Imports nuevos: `TeamExecutionMode`, `TeamExecutionResult`, `TeamMissionContext` de `com.aicompany.core.model`.)

4. En `MissionExecutorTest`, agregar `, mock(TeamWorkPlanner.class), List.of()` al final de la construcción de `MissionExecutor`.

- [ ] **Step 6: Correr los tests**

Run: `cd app && mvn test -Dtest='MissionExecutorTeamTest,MissionExecutorTest'` → PASS.
Run: `cd app && mvn test` → BUILD SUCCESS. (Spring no arranca en la suite, pero la app real necesita un bean `TeamExecutionStrategy` de cada modo: `DevelopmentTeamStrategy` llega en Task 14; hasta entonces `executeTeamMission` con Engineering lanzaría "No hay estrategia", lo que es aceptable porque nada se despliega entre Task 13 y 14.)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/aicompany/core/model/TeamMissionContext.java app/src/main/java/com/aicompany/core/model/TeamExecutionResult.java \
        app/src/main/java/com/aicompany/core/service/MissionProgress.java app/src/main/java/com/aicompany/core/service/TeamExecutionStrategy.java \
        app/src/main/java/com/aicompany/core/service/AnalysisTeamStrategy.java app/src/main/java/com/aicompany/core/service/MissionExecutor.java \
        app/src/test/java/com/aicompany/core/service/MissionExecutorTeamTest.java app/src/test/java/com/aicompany/core/service/MissionExecutorTest.java
git commit -m "MissionExecutor: misiones con teamId planificadas por el líder y ejecutadas por estrategia de equipo" \
           -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 14: `DevelopmentTeamStrategy` — código, commits, validación estática y estado verificable (TDD)

**Files:**
- Create: `app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java`
- Test: `app/src/test/java/com/aicompany/core/service/DevelopmentTeamStrategyTest.java`

**Interfaces:**
- Consumes: `DevelopmentRuntime.generate`/`review` (Task 11); `DevelopmentWorkspaceService.commitAgentWork`/`missionWorkspace`/`filesAtCommit`/`readFileAtCommit` (Task 9); `StaticWorkspaceValidator.validate` + `CommittedWork` (Task 12); `StaticValidationStatus.compute` (Task 1); `MissionMemoryService.createTask(..., kind)`, `recordTaskArtifact`, `recordStaticValidation`, `updateTask`, `recordEvidence(String taskId, String missionId, String agentId, List<AgentResult.Evidence>)`; `RepositoryEvidenceGate.citation` (Task 10).
- Produces: `DevelopmentTeamStrategy` (`mode() == DEVELOPMENT`); `static String renderRepositoryContext(Map<String, String> contentsByPath, int totalBudgetChars, int perFileBudgetChars)` (package-private, testeable).

- [ ] **Step 1: Escribir el test (falla: la clase no existe)**

```java
package com.aicompany.core.service;

import com.aicompany.core.agent.DevelopmentRuntime;
import com.aicompany.core.agent.model.AgentResult;
import com.aicompany.core.agent.model.DevelopmentResult;
import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;
import com.aicompany.core.agent.model.StaticReviewResult;
import com.aicompany.core.agent.model.TeamPlan;
import com.aicompany.core.agent.model.TeamPlan.PlannedTask;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.*;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DevelopmentTeamStrategyTest {

    private static final String SHA_NEO = "1".repeat(40);
    private static final String SHA_MILA = "2".repeat(40);

    private final MissionMemoryService memory = mock(MissionMemoryService.class);
    private final DevelopmentRuntime runtime = mock(DevelopmentRuntime.class);
    private final DevelopmentWorkspaceService workspace = mock(DevelopmentWorkspaceService.class);
    private final StaticWorkspaceValidator validator = mock(StaticWorkspaceValidator.class);
    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    private final MissionProgress progress = mock(MissionProgress.class);

    private final DevelopmentTeamStrategy strategy = new DevelopmentTeamStrategy(
            memory, runtime, workspace, validator, events, JsonMapper.builder().build());

    private static TeamMissionContext context() {
        var team = new TeamSnapshot("TEAM-ENGINEERING", "Engineering Team", "ACTIVE", "engineering", List.of(
                new TeamMemberInfo("engineering", "Neo", "Arquitecto", "R", List.of("arquitectura backend"), "m"),
                new TeamMemberInfo("frontend-ui", "Mila", "UI", "R", List.of("Game UI"), "m"),
                new TeamMemberInfo("qa", "Vera", "QA", "R", List.of("QA"), "m")));
        var plan = new TeamPlan("Juego de navegador", "HTML5 + JS", "web/index.html", List.of(
                new PlannedTask("engineering", "WORK", "ARCHITECTURE", "Base", List.of("arquitectura backend"), List.of("web/index.html")),
                new PlannedTask("frontend-ui", "WORK", "GAME_UI", "HUD", List.of("Game UI"), List.of("web/ui")),
                new PlannedTask("qa", "VALIDATION", "STATIC_REVIEW", "Revisar", List.of("QA"), List.of())));
        return new TeamMissionContext("M-1", "crear un juego", team, plan);
    }

    private static DevelopmentResult dev(String path) {
        return new DevelopmentResult("resumen de " + path, List.of(new GeneratedFile(path, "contenido")));
    }

    private static StaticReviewResult cleanReview() {
        return new StaticReviewResult("NO_EVIDENT_ISSUES", List.of(), List.of(), "Coherente con el plan.",
                List.of("No se verificó la ejecución."),
                List.of(new AgentResult.Evidence("main", "workspace:M-1@" + SHA_MILA + "/web/ui/hud.js", "INTERNAL", true)));
    }

    private void stubHappyPath() throws Exception {
        when(runtime.generate(eq("M-1-ENGINEERING"), anyString(), anyString(), anyString(), anyList()))
                .thenReturn(CompletableFuture.completedFuture(dev("web/index.html")));
        when(runtime.generate(eq("M-1-FRONTEND-UI"), anyString(), anyString(), anyString(), anyList()))
                .thenReturn(CompletableFuture.completedFuture(dev("web/ui/hud.js")));
        when(workspace.missionWorkspace("M-1")).thenReturn(Path.of("/data/forjai-products/M-1"));
        when(workspace.commitAgentWork(eq("M-1"), eq("M-1-ENGINEERING"), eq("engineering"), eq("Neo"), any()))
                .thenReturn(new DevelopmentWorkspaceService.CommitRecord(SHA_NEO, List.of("web/index.html")));
        when(workspace.commitAgentWork(eq("M-1"), eq("M-1-FRONTEND-UI"), eq("frontend-ui"), eq("Mila"), any()))
                .thenReturn(new DevelopmentWorkspaceService.CommitRecord(SHA_MILA, List.of("web/ui/hud.js")));
        when(workspace.filesAtCommit(eq("M-1"), anyString())).thenReturn(List.of("web/index.html", "web/ui/hud.js"));
        when(workspace.readFileAtCommit(eq("M-1"), anyString(), anyString())).thenReturn("contenido");
        when(validator.validate(eq("M-1"), anyList(), eq("web/index.html"), anyList()))
                .thenReturn(List.of(StaticCheck.pass("ENTRY_POINT", "ok", null, List.of("web/index.html"))));
    }

    @Test
    void commitsOnePerAgentInPlanOrderAndRecordsTheArtifact() throws Exception {
        stubHappyPath();
        when(runtime.review(eq("M-1-QA"), eq("M-1"), eq("qa"), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        strategy.execute(context(), progress);

        InOrder inOrder = inOrder(workspace);
        inOrder.verify(workspace).commitAgentWork(eq("M-1"), eq("M-1-ENGINEERING"), eq("engineering"), eq("Neo"), any());
        inOrder.verify(workspace).commitAgentWork(eq("M-1"), eq("M-1-FRONTEND-UI"), eq("frontend-ui"), eq("Mila"), any());
        verify(memory).createTask("M-1-QA", "M-1", "qa", "STATIC_REVIEW", "VALIDATION");
        verify(memory).recordTaskArtifact("M-1-FRONTEND-UI", "/data/forjai-products/M-1", SHA_MILA, List.of("web/ui/hud.js"));
        verify(memory).updateTask(eq("M-1-FRONTEND-UI"), eq("COMPLETED"), anyString());
        verify(events).publish(eq("EMPRESA_TASK_COMMITTED"), eq("M-1"), eq("M-1-FRONTEND-UI"), eq("frontend-ui"), anyMap());
    }

    @Test
    void aCleanReviewIsStaticallyValidatedAndTheStateCitesRealShas() throws Exception {
        stubHappyPath();
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        var result = (TeamExecutionResult.Development) strategy.execute(context(), progress);

        verify(memory).recordStaticValidation(eq("M-1-QA"), eq("STATICALLY_VALIDATED"), anyString());
        verify(memory).recordEvidence(eq("M-1-QA"), eq("M-1"), eq("qa"), anyList());
        assertTrue(result.verifiableState().contains(SHA_NEO));
        assertTrue(result.verifiableState().contains(SHA_MILA));
        assertTrue(result.verifiableState().contains(
                "Esta fase no ejecuta código: no se puede afirmar que el juego compile, se ejecute o pase tests."));
        assertTrue(result.resultsForCeo().contains("STATICALLY_VALIDATED"));
    }

    @Test
    void aFailedCommitFailsOnlyThatTaskAndIsReported() throws Exception {
        stubHappyPath();
        when(workspace.commitAgentWork(eq("M-1"), eq("M-1-FRONTEND-UI"), anyString(), anyString(), any()))
                .thenThrow(new java.io.IOException("git commit falló"));
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        var result = (TeamExecutionResult.Development) strategy.execute(context(), progress);

        verify(memory).updateTask(eq("M-1-FRONTEND-UI"), eq("FAILED"), contains("sin commit"));
        verify(memory, never()).recordTaskArtifact(eq("M-1-FRONTEND-UI"), anyString(), anyString(), anyList());
        assertTrue(result.resultsForCeo().contains("AGENTES_FALLIDOS"));
    }

    @Test
    void noCommitAtAllFailsTheMissionAndTheValidationTask() throws Exception {
        stubHappyPath();
        when(workspace.commitAgentWork(anyString(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new java.io.IOException("sin git"));

        assertThrows(IllegalStateException.class, () -> strategy.execute(context(), progress));
        verify(memory).updateTask(eq("M-1-QA"), eq("FAILED"), anyString());
        verify(runtime, never()).review(anyString(), anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void aReviewThatNeverCompletesLeavesTheWorkUnvalidated() throws Exception {
        stubHappyPath();
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("reintentos agotados")));

        var result = (TeamExecutionResult.Development) strategy.execute(context(), progress);

        verify(memory).recordStaticValidation(eq("M-1-QA"), eq("UNVALIDATED"), anyString());
        assertTrue(result.verifiableState().contains("UNVALIDATED"));
    }

    // Review Focus: si el repo supera el tope, Vera recibe marcas explícitas, nunca un corte silencioso.
    @Test
    void theReviewContextMarksTruncatedAndOmittedFiles() {
        var contents = new LinkedHashMap<String, String>();
        contents.put("web/a.js", "a".repeat(50));
        contents.put("web/b.js", "b".repeat(50));
        contents.put("web/c.js", "c".repeat(50));

        var rendered = DevelopmentTeamStrategy.renderRepositoryContext(contents, 80, 40);

        assertTrue(rendered.contains("### web/a.js"));
        assertTrue(rendered.contains("[TRUNCADO"));
        assertTrue(rendered.contains("web/c.js (NO INCLUIDO"));
        assertFalse(rendered.contains("c".repeat(50)));
    }
}
```

- [ ] **Step 2: Correr el test para confirmar que falla**

Run: `cd app && mvn test -Dtest=DevelopmentTeamStrategyTest` → FAIL de compilación.

- [ ] **Step 3: Implementar `DevelopmentTeamStrategy`**

```java
package com.aicompany.core.service;

import com.aicompany.core.agent.DevelopmentRuntime;
import com.aicompany.core.agent.model.DevelopmentResult;
import com.aicompany.core.agent.model.StaticReviewResult;
import com.aicompany.core.agent.model.TeamPlan;
import com.aicompany.core.agent.model.TeamPlan.PlannedTask;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.MissionStatus;
import com.aicompany.core.model.StaticCheck;
import com.aicompany.core.model.StaticValidationStatus;
import com.aicompany.core.model.TeamExecutionMode;
import com.aicompany.core.model.TeamExecutionResult;
import com.aicompany.core.model.TeamMemberInfo;
import com.aicompany.core.model.TeamMissionContext;
import com.aicompany.core.service.StaticWorkspaceValidator.CommittedWork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Engineering (spec §6-8): código real en paralelo → un commit por agente
 * (secuencial, en el orden del plan) → chequeos deterministas → revisión
 * estática del validador → reporte para el CEO + "Estado verificable".
 * Nunca ejecuta el código generado.
 */
@Service
public class DevelopmentTeamStrategy implements TeamExecutionStrategy {

    private static final Logger log = LoggerFactory.getLogger(DevelopmentTeamStrategy.class);

    static final int REVIEW_TOTAL_BUDGET_CHARS = 60_000;
    static final int REVIEW_FILE_BUDGET_CHARS = 8_000;
    static final String NO_EXECUTION_DISCLAIMER =
            "Esta fase no ejecuta código: no se puede afirmar que el juego compile, se ejecute o pase tests.";

    private final MissionMemoryService memory;
    private final DevelopmentRuntime runtime;
    private final DevelopmentWorkspaceService workspace;
    private final StaticWorkspaceValidator staticValidator;
    private final CompanyEventPublisher events;
    private final JsonMapper jsonMapper;

    public DevelopmentTeamStrategy(
            MissionMemoryService memory,
            DevelopmentRuntime runtime,
            DevelopmentWorkspaceService workspace,
            StaticWorkspaceValidator staticValidator,
            CompanyEventPublisher events,
            JsonMapper jsonMapper) {

        this.memory = memory;
        this.runtime = runtime;
        this.workspace = workspace;
        this.staticValidator = staticValidator;
        this.events = events;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public TeamExecutionMode mode() {
        return TeamExecutionMode.DEVELOPMENT;
    }

    @Override
    public TeamExecutionResult execute(TeamMissionContext context, MissionProgress progress) {

        var missionId = context.missionId();
        var plan = context.plan();
        var work = plan.workTasks();
        var validation = plan.validationTask()
                .orElseThrow(() -> new IllegalStateException("El plan no tiene tarea VALIDATION."));
        var validationTaskId = taskId(missionId, validation.agentId());

        for (var task : work) {
            createTask(missionId, task, TeamPlan.KIND_WORK);
        }
        createTask(missionId, validation, TeamPlan.KIND_VALIDATION);

        var futures = new LinkedHashMap<PlannedTask, CompletableFuture<DevelopmentResult>>();

        for (var task : work) {
            futures.put(task, runtime.generate(taskId(missionId, task.agentId()), missionId, task.agentId(),
                    buildWorkPrompt(context, task), task.ownedPathsOrEmpty()));
        }

        progress.advance(MissionStatus.WAITING_AGENT_RESULTS, 30, "Desarrollo en paralelo",
                "Los miembros de " + context.team().teamName() + " están generando código.");

        var generated = new LinkedHashMap<PlannedTask, DevelopmentResult>();
        var failures = new ArrayList<String>();

        for (var entry : futures.entrySet()) {
            try {
                generated.put(entry.getKey(), entry.getValue().join());
            } catch (Exception ex) {
                failures.add(entry.getKey().agentId() + ": " + safeMessage(ex, "no generó código"));
            }
        }

        progress.advance(MissionStatus.EVALUATING, 60, "Commits por agente",
                "Registrando un commit por agente en el repositorio de la misión.");

        var committed = new ArrayList<CommittedWork>();

        for (var entry : generated.entrySet()) {

            var task = entry.getKey();
            var id = taskId(missionId, task.agentId());

            try {

                var record = workspace.commitAgentWork(missionId, id, task.agentId(),
                        agentName(context, task.agentId()), entry.getValue());

                memory.recordTaskArtifact(id, workspace.missionWorkspace(missionId).toString(),
                        record.sha(), record.files());
                memory.updateTask(id, "COMPLETED", toJson(entry.getValue()));

                events.publish("EMPRESA_TASK_COMMITTED", missionId, id, task.agentId(),
                        Map.of("commitSha", record.sha(), "files", record.files()));
                events.publishTask("EMPRESA_TASK_COMPLETED", id, missionId, task.agentId(), "COMPLETED",
                        "Commit " + record.sha());

                committed.add(new CommittedWork(id, task.agentId(), record.sha(), record.files()));

            } catch (Exception ex) {

                var message = "Archivos generados pero sin commit: " + safeMessage(ex, "error de Git");
                memory.updateTask(id, "FAILED", message);
                events.publishTask("EMPRESA_TASK_FAILED", id, missionId, task.agentId(), "FAILED", message);
                failures.add(task.agentId() + ": " + message);
            }
        }

        if (committed.isEmpty()) {

            var message = "No hay código commiteado que validar.";
            memory.updateTask(validationTaskId, "FAILED", message);
            events.publishTask("EMPRESA_TASK_FAILED", validationTaskId, missionId, validation.agentId(), "FAILED", message);

            throw new IllegalStateException("Ninguna tarea de desarrollo produjo un commit: " + String.join("; ", failures));
        }

        progress.advance(MissionStatus.EVALUATING, 75, "Validación estática",
                "Chequeos deterministas y revisión estática de " + validation.agentId() + ".");

        var allowedPaths = work.stream().flatMap(t -> t.ownedPathsOrEmpty().stream()).toList();
        var checks = staticValidator.validate(missionId, committed, plan.entryPoint(), allowedPaths);

        StaticReviewResult review = null;
        String reviewError = null;

        try {

            var headSha = committed.get(committed.size() - 1).commitSha();
            var filesBySha = new LinkedHashMap<String, Set<String>>();

            for (var item : committed) {
                filesBySha.put(item.commitSha(), new HashSet<>(workspace.filesAtCommit(missionId, item.commitSha())));
            }

            var contents = new LinkedHashMap<String, String>();
            for (var path : workspace.filesAtCommit(missionId, headSha).stream().sorted().toList()) {
                contents.put(path, workspace.readFileAtCommit(missionId, headSha, path));
            }

            var repositoryContext = renderRepositoryContext(contents, REVIEW_TOTAL_BUDGET_CHARS, REVIEW_FILE_BUDGET_CHARS);

            review = runtime.review(validationTaskId, missionId, validation.agentId(),
                    buildReviewPrompt(context, validation, committed, checks, headSha, repositoryContext),
                    filesBySha).join();

        } catch (Exception ex) {
            reviewError = safeMessage(ex, "La revisión estática no se completó.");
            log.warn("MISSION {} - static review did not complete: {}", missionId, reviewError);
        }

        var status = StaticValidationStatus.compute(checks, review);

        memory.recordStaticValidation(validationTaskId, status.name(), toJson(checks));

        if (review != null && review.evidence() != null && !review.evidence().isEmpty()) {
            memory.recordEvidence(validationTaskId, missionId, validation.agentId(), review.evidence());
        }

        var failedChecks = checks.stream().filter(c -> !c.passed()).count();

        events.publish("EMPRESA_STATIC_VALIDATION_COMPLETED", missionId, validationTaskId, validation.agentId(),
                Map.of("validationStatus", status.name(), "failedChecks", failedChecks));

        return new TeamExecutionResult.Development(
                resultsForCeo(context, committed, checks, review, reviewError, status, failures),
                verifiableState(context, committed, checks, status, failures));
    }

    private void createTask(String missionId, PlannedTask task, String kind) {
        var id = taskId(missionId, task.agentId());
        memory.createTask(id, missionId, task.agentId(), task.action(), kind);
        events.publishTask("EMPRESA_TASK_CREATED", id, missionId, task.agentId(), "PENDING", "Tarea creada.");
    }

    static String taskId(String missionId, String agentId) {
        return missionId + "-" + agentId.toUpperCase(Locale.ROOT);
    }

    private static String agentName(TeamMissionContext context, String agentId) {
        return context.team().members().stream()
                .filter(m -> m.agentId().equals(agentId))
                .map(TeamMemberInfo::name)
                .findFirst()
                .orElse(agentId);
    }

    private String buildWorkPrompt(TeamMissionContext context, PlannedTask task) {

        var plan = context.plan();

        var others = plan.tasksOrEmpty().stream()
                .filter(t -> t != null && !t.agentId().equals(task.agentId()))
                .map(t -> "- " + t.agentId() + " (" + t.kind() + "): " + t.objective() + " | ownedPaths=" + t.ownedPathsOrEmpty())
                .collect(Collectors.joining("\n"));

        return """
                MISIÓN DEL EQUIPO %s:
                %s

                PLAN DEL LÍDER:
                %s
                Tecnología: %s
                Punto de entrada: %s

                TU TAREA (%s, action=%s):
                %s
                Solo puedes escribir archivos dentro de estos ownedPaths: %s

                TAREAS DEL RESTO DEL EQUIPO (corren en paralelo; no verás su código, respeta sus rutas e interfaces):
                %s

                REGLAS:
                - Escribe código fuente REAL y completo para tu parte, no pseudocódigo ni placeholders.
                - Rutas relativas con "/" como separador; nunca rutas absolutas, "..", "\\" ni ".git".
                - Nadie va a ejecutar este código en esta fase: no afirmes en summary que compila o funciona.
                - summary: qué archivos escribiste y qué hace cada uno.

                FORMATO: {"summary": "...", "files": [{"path": "...", "content": "..."}]}
                """.formatted(
                context.team().teamName(), context.instruction(),
                plan.summary(), plan.techStack(), plan.entryPoint(),
                task.agentId(), task.action(), task.objective(), task.ownedPathsOrEmpty(),
                others);
    }

    private String buildReviewPrompt(
            TeamMissionContext context, PlannedTask validation, List<CommittedWork> committed,
            List<StaticCheck> checks, String headSha, String repositoryContext) {

        var commits = committed.stream()
                .map(c -> "- " + c.agentId() + ": sha=" + c.commitSha() + " archivos=" + c.files())
                .collect(Collectors.joining("\n"));

        var checkLines = checks.stream()
                .map(c -> "- " + c.check() + ": " + c.status() + " — " + c.detail())
                .collect(Collectors.joining("\n"));

        return """
                Eres el agente %s y validas ESTÁTICAMENTE el código generado por %s para la misión %s.
                Nadie ejecutó este código.

                OBJETIVO DE TU TAREA: %s

                PLAN DEL LÍDER: %s
                Tecnología: %s | Punto de entrada: %s

                COMMITS POR AGENTE:
                %s

                CHEQUEOS DETERMINISTAS (hechos por Java; no los contradigas):
                %s

                CÓDIGO DEL REPOSITORIO (HEAD = %s):
                %s

                QUÉ DEBES HACER:
                - Revisa coherencia, errores evidentes, archivos faltantes (missingFiles), consistencia entre
                  arquitectura y código, y riesgos técnicos.
                - findings: cada uno con path, severity (BLOCKER si impide que el proyecto tenga sentido como MVP;
                  MAJOR; MINOR) y description.
                - verdict: NO_EVIDENT_ISSUES o ISSUES_FOUND.
                - notValidatableWithoutExecution: lo que NO puede validarse sin ejecutar (compilación, ejecución,
                  rendimiento, jugabilidad...). Nunca vacío.
                - evidence: cita los archivos reales que revisaste con sourceType "INTERNAL", verified true y source
                  exactamente "workspace:%s@<sha>/<ruta>", usando un sha de la lista de commits y una ruta que exista en ese commit.
                - NUNCA afirmes que el juego compila, se ejecuta, funciona o pasa tests.
                """.formatted(
                validation.agentId(), context.team().teamName(), context.missionId(),
                validation.objective(),
                context.plan().summary(), context.plan().techStack(), context.plan().entryPoint(),
                commits, checkLines, headSha, repositoryContext, context.missionId());
    }

    /** Contenido real del repo con tope explícito; lo truncado u omitido queda marcado (Review Focus). */
    static String renderRepositoryContext(Map<String, String> contentsByPath, int totalBudgetChars, int perFileBudgetChars) {

        var out = new StringBuilder();
        var remaining = totalBudgetChars;

        for (var entry : contentsByPath.entrySet()) {

            if (remaining <= 0) {
                out.append("### ").append(entry.getKey())
                        .append(" (NO INCLUIDO: se agotó el presupuesto de revisión — revisión parcial)\n");
                continue;
            }

            var content = entry.getValue() == null ? "" : entry.getValue();
            var limit = Math.min(perFileBudgetChars, remaining);

            out.append("### ").append(entry.getKey()).append("\n");

            if (content.length() > limit) {
                out.append(content, 0, limit)
                        .append("\n[TRUNCADO: archivo revisado parcialmente, ")
                        .append(content.length() - limit).append(" caracteres omitidos]\n");
                remaining -= limit;
            } else {
                out.append(content).append("\n");
                remaining -= content.length();
            }
        }

        return out.toString();
    }

    private String resultsForCeo(
            TeamMissionContext context, List<CommittedWork> committed, List<StaticCheck> checks,
            StaticReviewResult review, String reviewError, StaticValidationStatus status, List<String> failures) {

        var out = new StringBuilder();

        out.append("MISIÓN DE EQUIPO: ").append(context.team().teamName())
                .append(" (").append(context.team().teamId()).append(")\n");
        out.append("PLAN DEL LÍDER: ").append(context.plan().summary())
                .append(" | Tecnología: ").append(context.plan().techStack())
                .append(" | Punto de entrada: ").append(context.plan().entryPoint()).append("\n\n");

        out.append("COMMITS POR AGENTE:\n");
        for (var c : committed) {
            out.append("- ").append(agentName(context, c.agentId())).append(" (").append(c.agentId()).append("): ")
                    .append(c.commitSha()).append(" ").append(c.files()).append("\n");
        }

        out.append("\nCHEQUEOS DETERMINISTAS:\n");
        for (var check : checks) {
            out.append("- ").append(check.check()).append(": ").append(check.status())
                    .append(" — ").append(check.detail()).append("\n");
        }

        out.append("\nREVISIÓN ESTÁTICA: ");
        if (review == null) {
            out.append("no se completó (").append(reviewError).append(")\n");
        } else {
            out.append(review.verdict()).append("\n");
            for (var finding : review.findingsOrEmpty()) {
                if (finding != null) {
                    out.append("- [").append(finding.severity()).append("] ").append(finding.path())
                            .append(": ").append(finding.description()).append("\n");
                }
            }
            out.append("No validable sin ejecución: ").append(review.notValidatableWithoutExecution()).append("\n");
        }

        out.append("\nVALIDATION_STATUS: ").append(status.name()).append("\n");

        if (!failures.isEmpty()) {
            out.append("\nAGENTES_FALLIDOS (resultado PARCIAL — no lo ignores):\n- ")
                    .append(String.join("\n- ", failures)).append("\n");
        }

        out.append("\nREGLA: ").append(NO_EXECUTION_DISCLAIMER)
                .append(" No afirmes que el juego está terminado ni que funciona.\n");

        return out.toString();
    }

    private String verifiableState(
            TeamMissionContext context, List<CommittedWork> committed, List<StaticCheck> checks,
            StaticValidationStatus status, List<String> failures) {

        var passed = checks.stream().filter(StaticCheck::passed).count();

        var out = new StringBuilder();
        out.append("ESTADO VERIFICABLE (generado por Forjai, no por un modelo)\n");
        out.append("Workspace: ").append(workspace.missionWorkspace(context.missionId())).append("\n");

        for (var c : committed) {
            out.append("- ").append(agentName(context, c.agentId())).append(" (").append(c.agentId()).append("): commit ")
                    .append(c.commitSha()).append(" — ").append(c.files().size()).append(" archivo(s): ")
                    .append(String.join(", ", c.files())).append("\n");
        }

        for (var failure : failures) {
            out.append("- Sin commit: ").append(failure).append("\n");
        }

        out.append("Validación estática: ").append(status.name())
                .append(" (").append(passed).append("/").append(checks.size()).append(" chequeos deterministas en PASS)\n");
        out.append(NO_EXECUTION_DISCLAIMER);

        return out.toString();
    }

    private String toJson(Object value) {
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("No se pudo serializar.", ex);
        }
    }

    private static String safeMessage(Exception ex, String defaultMessage) {
        var cause = ex.getCause() != null && ex instanceof java.util.concurrent.CompletionException ? ex.getCause() : ex;
        return cause.getMessage() == null || cause.getMessage().isBlank() ? defaultMessage : cause.getMessage();
    }
}
```

- [ ] **Step 4: Correr los tests**

Run: `cd app && mvn test -Dtest=DevelopmentTeamStrategyTest` → PASS (6 tests).
Run: `cd app && mvn test` → BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java app/src/test/java/com/aicompany/core/service/DevelopmentTeamStrategyTest.java
git commit -m "Agregar DevelopmentTeamStrategy: código real, commit por agente, validación estática y estado verificable" \
           -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 15: Chat — `teamId` explícito y exacto, estado de la misión con equipo y validación

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java`
- Test: `app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java`

**Interfaces:**
- Consumes: `TeamMemoryService.KNOWN_TEAM_IDS`; `MissionService.start(..., String teamId)` (Task 4); `MissionResponse.teamId()`; `AgentTask.commitSha()/validationStatus()/kind()` (Task 5).
- Produces: comportamiento del chat (sin API nueva).

- [ ] **Step 1: Escribir los tests nuevos (fallan)**

Agregar a `ChatIntentRouterTest` (imports de `AgentTask`, `anyString`, `isNull`, `never` según falte):

```java
    private static MissionResponse created(String id, String teamId) {
        return new MissionResponse(id, MissionStatus.CREATED, "PRODUCTION", 0, "Creada", "Misión recibida",
                Instant.parse("2026-09-24T00:00:00Z"), null, teamId);
    }

    @Test
    void anExplicitExactTeamIdStartsATeamMission() {
        var message = "CEO, inicia una misión para TEAM-ENGINEERING para crear un videojuego.";
        when(missionService.start(anyString(), eq(message), eq("PRODUCTION"), isNull(), eq("TEAM-ENGINEERING")))
                .thenReturn(created("MISSION-1", "TEAM-ENGINEERING"));

        var response = router.route(message);

        verify(missionService).start(anyString(), eq(message), eq("PRODUCTION"), isNull(), eq("TEAM-ENGINEERING"));
        assertTrue(response.contains("TEAM-ENGINEERING"));
    }

    @Test
    void anUnknownTeamTokenDoesNotStartAnyMission() {
        var response = router.route("CEO, inicia una misión para TEAM-DESIGN para crear un logo.");

        verifyNoInteractions(missionService);
        assertTrue(response.contains("TEAM-DESIGN"));
    }

    @Test
    void aTeamNameWithoutTheExactIdStartsAMissionWithoutTeam() {
        var message = "CEO, inicia una misión exclusivamente para el Engineering Team.";
        when(missionService.start(anyString(), eq(message), eq("PRODUCTION"), isNull())).thenReturn(created("MISSION-2", null));

        router.route(message);

        verify(missionService).start(anyString(), eq(message), eq("PRODUCTION"), isNull());
        verify(missionService, never()).start(anyString(), anyString(), anyString(), any(), anyString());
    }

    // Review Focus: el id es exacto; en minúsculas no se reconoce y la misión arranca sin equipo.
    @Test
    void aLowercaseTeamIdIsNotRecognized() {
        var message = "CEO, inicia una misión para team-engineering.";
        when(missionService.start(anyString(), eq(message), eq("PRODUCTION"), isNull())).thenReturn(created("MISSION-3", null));

        router.route(message);

        verify(missionService).start(anyString(), eq(message), eq("PRODUCTION"), isNull());
    }

    @Test
    void theMissionStatusLookupShowsTeamCommitsAndValidation() {
        var mission = new MissionResponse("MISSION-77", MissionStatus.AWAITING_INVESTOR, "PRODUCTION", 95, "Recomendación",
                "ok", Instant.parse("2026-09-24T00:00:00Z"), null, "TEAM-ENGINEERING");
        when(missionMemory.find("MISSION-77")).thenReturn(Optional.of(mission));
        when(missionMemory.tasks("MISSION-77")).thenReturn(List.of(
                new AgentTask("MISSION-77-BACKEND", "MISSION-77", "backend", "GAME_LOGIC", "COMPLETED", "{}",
                        Instant.parse("2026-09-24T00:00:00Z"), "WORK", "/data/forjai-products/MISSION-77",
                        "abcdef1234567890abcdef1234567890abcdef12", List.of("web/game/main.js"), null, null),
                new AgentTask("MISSION-77-QA", "MISSION-77", "qa", "STATIC_REVIEW", "COMPLETED", "{}",
                        Instant.parse("2026-09-24T00:00:00Z"), "VALIDATION", null, null, null, "STATICALLY_VALIDATED", "[]")));
        when(productStatusService.resolve("MISSION-77")).thenReturn(ProductStatus.DEVELOPMENT);

        var response = router.route("¿Cómo va MISSION-77?");

        assertTrue(response.contains("TEAM-ENGINEERING"));
        assertTrue(response.contains("backend=abcdef1"));
        assertTrue(response.contains("STATICALLY_VALIDATED"));
    }
```

- [ ] **Step 2: Correr los tests para confirmar que fallan**

Run: `cd app && mvn test -Dtest=ChatIntentRouterTest` → FAIL (los 5 nuevos).

- [ ] **Step 3: Implementar en `ChatIntentRouter`**

Agregar junto a los demás `Pattern`:

```java
    /**
     * Solo el id EXACTO de uno de los 3 equipos (case-sensitive, mismo
     * principio determinista que MISSION-<id>). Nunca se infiere el equipo
     * de frases como "Engineering Team" (spec §1).
     */
    private static final Pattern TEAM_ID_TOKEN =
            Pattern.compile("\\bTEAM-[A-Z]+(?:-[A-Z]+)*\\b");
```

Agregar helpers:

```java
    private String detectTeamToken(String message) {
        var matcher = TEAM_ID_TOKEN.matcher(message);
        return matcher.find() ? matcher.group() : null;
    }

    private String unknownTeamMessage(String teamToken) {
        return "No inicié ninguna misión: " + teamToken + " no es un equipo de Forjai. Equipos válidos: "
                + TeamMemoryService.KNOWN_TEAM_IDS.stream().sorted().toList() + ".";
    }

    /** Sin equipo se llama la sobrecarga de siempre (mismo contrato que antes de esta feature). */
    private MissionResponse startMission(String missionId, String message, String teamId) {
        return teamId == null
                ? missionService.start(missionId, message, "PRODUCTION", null)
                : missionService.start(missionId, message, "PRODUCTION", null, teamId);
    }

    private static String teamSuffix(String teamId) {
        return teamId == null ? "" : " Equipo responsable: " + teamId + ".";
    }
```

En `resolve`, reemplazar el bloque de `missionStartMatcher.find()` por:

```java
        if (missionStartMatcher.find()) {

            var missionId = missionStartMatcher.group(1).toUpperCase(Locale.ROOT);
            var teamId = detectTeamToken(message);

            if (teamId != null && !TeamMemoryService.KNOWN_TEAM_IDS.contains(teamId)) {
                return unknownTeamMessage(teamId);
            }

            // Una misión iniciada por un comando real de chat del
            // fundador es trabajo real, no una prueba de desarrollo.
            var response = startMission(missionId, message, teamId);

            return "He recibido " + missionId + ". Estado: " + response.status() + "."
                    + teamSuffix(teamId)
                    + " La misión está procesándose en segundo plano. Consulta "
                    + "/api/company/missions/" + missionId
                    + "/details para ver el progreso y las tareas.";
        }
```

En `handleFreeMissionStart`, reemplazar desde `var missionId = ...` hasta el `return` por:

```java
        var teamId = detectTeamToken(message);

        if (teamId != null && !TeamMemoryService.KNOWN_TEAM_IDS.contains(teamId)) {
            return unknownTeamMessage(teamId);
        }

        var missionId = "MISSION-" + Instant.now().toEpochMilli();

        log.info("CHAT_INTENT_FREE_MISSION_START missionId={} teamId={}", missionId, teamId);

        // Una misión iniciada por un comando real de chat del fundador
        // es trabajo real, no una prueba de desarrollo.
        var response = startMission(missionId, message, teamId);

        conversationMemory.setLastMentioned("MISSION", List.of(missionId));

        return "Creé la misión " + missionId + " con tu descripción y la mandé a "
                + "procesar en segundo plano. Estado: " + response.status() + "."
                + teamSuffix(teamId)
                + " Consulta /api/company/missions/" + missionId
                + "/details para ver el progreso y las tareas.";
```

En `formatMissionStatus`, cambiar la última línea del `return` a:

```java
                + "." + closing + formatFinancialCriteria(mission) + formatTeamExecution(mission, tasks);
```

y agregar:

```java
    /** Equipo, commits reales y estado de validación — 100% desde Neo4j, nunca redactado por el LLM. */
    private String formatTeamExecution(MissionResponse mission, List<AgentTask> tasks) {

        if (mission.teamId() == null) {
            return "";
        }

        var out = new StringBuilder(" Equipo responsable: ").append(mission.teamId()).append(".");

        var commits = tasks.stream()
                .filter(t -> t.commitSha() != null && !t.commitSha().isBlank())
                .map(t -> t.agentId() + "=" + t.commitSha().substring(0, Math.min(7, t.commitSha().length())))
                .collect(Collectors.joining(", "));

        if (!commits.isEmpty()) {
            out.append(" Commits: ").append(commits).append(".");
        }

        tasks.stream()
                .filter(t -> t.validationStatus() != null)
                .findFirst()
                .ifPresent(t -> out.append(" Validación estática: ").append(t.validationStatus())
                        .append(" (esta fase no ejecuta código)."));

        return out.toString();
    }
```

- [ ] **Step 4: Correr los tests**

Run: `cd app && mvn test -Dtest=ChatIntentRouterTest` → PASS (todos, incluidos los existentes).
Run: `cd app && mvn test` → BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java
git commit -m "Chat: teamId solo por id exacto; estado de misión muestra equipo, commits y validación" \
           -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 16: Frontend — selector de equipo y detalle de artefactos

**Files:**
- Modify: `app/frontend/src/api/types.ts`
- Modify: `app/frontend/src/pages/MissionsPage.tsx`
- Modify: `app/frontend/src/pages/MissionDetailPage.tsx`
- Modify: `app/frontend/src/statusColor.ts`

**Interfaces:**
- Consumes: `MissionResponse.teamId`, `AgentTask.kind/commitSha/files/validationStatus/staticChecks`, `MissionCommand.teamId` (Tasks 4-5).

- [ ] **Step 1: `types.ts`**

En `MissionResponse` agregar `teamId: string | null`. Reemplazar `AgentTask`:

```ts
export interface AgentTask {
  taskId: string
  missionId: string
  agentId: string
  action: string
  status: string
  result: string
  updatedAt: string
  // Misiones por equipo: PLANNING | WORK | VALIDATION (null en discovery)
  kind: string | null
  workspacePath: string | null
  commitSha: string | null
  files: string[] | null
  // Solo en la tarea VALIDATION: STATICALLY_VALIDATED | UNVALIDATED | FAILED
  validationStatus: string | null
  // JSON de StaticCheck[]
  staticChecks: string | null
}

export interface StaticCheck {
  check: string
  status: 'PASS' | 'FAIL'
  detail: string
  sha: string | null
  paths: string[]
}
```

En `MissionCommand` agregar `teamId: string | null`.

- [ ] **Step 2: `MissionsPage.tsx` — selector "Equipo responsable"**

Agregar el estado junto a los demás `useState`:

```tsx
  const [teamId, setTeamId] = useState('')
```

En `api.startMission({...})` agregar `teamId: teamId || null,` después de `environment,`. En `onSuccess` agregar `setTeamId('')`. Después del `<label>` de "Entorno":

```tsx
      <label>
        Equipo responsable
        <select value={teamId} onChange={(e) => setTeamId(e.target.value)}>
          <option value="">Sin equipo</option>
          <option value="TEAM-ENGINEERING">Engineering Team</option>
          <option value="TEAM-CREATIVE-PRODUCT-INTELLIGENCE">Creative / Product Intelligence</option>
          <option value="TEAM-MARKETING-GROWTH">Marketing &amp; Growth</option>
        </select>
      </label>
```

- [ ] **Step 3: `MissionDetailPage.tsx` — equipo, commits y validación**

Agregar el import `import type { StaticCheck } from '../api/types'` y, fuera del componente:

```tsx
function parseChecks(raw: string | null): StaticCheck[] {
  if (!raw) return []
  try {
    return JSON.parse(raw) as StaticCheck[]
  } catch {
    return []
  }
}
```

En el párrafo de encabezado, después del entorno:

```tsx
        {mission.teamId && <>{' — '}👥 {mission.teamId}</>}
```

Reemplazar la tabla de tareas:

```tsx
      <h2>Tareas por agente</h2>
      <table className="data-table">
        <thead>
          <tr>
            <th>Agente</th>
            <th>Tipo</th>
            <th>Acción</th>
            <th>Estado</th>
            <th>Commit</th>
            <th>Archivos</th>
            <th>Actualizada</th>
          </tr>
        </thead>
        <tbody>
          {tasks.map((task) => (
            <tr key={task.taskId}>
              <td>{task.agentId.toUpperCase()}</td>
              <td>{task.kind ?? '—'}</td>
              <td>{task.action}</td>
              <td>
                {statusDot(task.status)} {task.status}
              </td>
              <td>{task.commitSha ? <code>{task.commitSha.slice(0, 7)}</code> : '—'}</td>
              <td>{task.files && task.files.length > 0 ? task.files.join(', ') : '—'}</td>
              <td>{new Date(task.updatedAt).toLocaleString()}</td>
            </tr>
          ))}
        </tbody>
      </table>

      {tasks
        .filter((task) => task.validationStatus)
        .map((task) => (
          <section key={`${task.taskId}-validation`}>
            <h2>Validación estática</h2>
            <p>
              {statusDot(task.validationStatus ?? '')} <strong>{task.validationStatus}</strong> — revisada por{' '}
              {task.agentId.toUpperCase()}. Esta fase no ejecuta código.
            </p>
            <ul>
              {parseChecks(task.staticChecks).map((check, index) => (
                <li key={`${check.check}-${index}`}>
                  {check.status === 'PASS' ? '🟢' : '🔴'} {check.check}: {check.detail}
                </li>
              ))}
            </ul>
          </section>
        ))}
```

- [ ] **Step 4: `statusColor.ts`**

Agregar `'STATICALLY_VALIDATED'` a `GREEN` y `'GENERATED', 'UNVALIDATED'` a `YELLOW`.

- [ ] **Step 5: Lint y build**

Run: `cd app/frontend && npm run lint && npm run build`
Expected: sin errores de oxlint; `tsc` + `vite build` en verde.

- [ ] **Step 6: Commit**

```bash
git add app/frontend/src/api/types.ts app/frontend/src/pages/MissionsPage.tsx app/frontend/src/pages/MissionDetailPage.tsx app/frontend/src/statusColor.ts
git commit -m "Command Center: selector de equipo responsable; commits y validación estática en el detalle de misión" \
           -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 17: Documentación + verificación en vivo

**Files:**
- Modify: `docs/EVENTS.md`, `CLAUDE.md`, `docs/HISTORY.md`

- [ ] **Step 1: `docs/EVENTS.md`**

Agregar al catálogo, con el mismo formato de las entradas existentes: `EMPRESA_TEAM_PLAN_CREATED` (data: `teamId`, `tasks`), `EMPRESA_TEAM_PLAN_REJECTED` (data: `attempt`, `errors`), `EMPRESA_TASK_COMMITTED` (data: `commitSha`, `files`), `EMPRESA_STATIC_VALIDATION_COMPLETED` (data: `validationStatus`, `failedChecks`).

- [ ] **Step 2: `CLAUDE.md`**

1. En "Estado del repositorio", en la línea de `docs/superpowers/specs/`, sacar "development generation (`2026-09-21`)" de la lista de "todavía no".
2. En "Flujo de misión", agregar tras el paso 1: "Si la misión tiene `teamId`, `MissionExecutor` no crea las 5 tareas fijas: ver 'Misiones por equipo'."
3. Agregar una sección nueva después de "Flujo de misión":

```markdown
### Misiones por equipo (`Mission.teamId`)

`teamId` opcional, inmutable y validado en `MissionService.start` (uno de los 3 de `TeamMemoryService.KNOWN_TEAM_IDS`, `status=ACTIVE`, con líder y miembros). En el chat solo se reconoce el id exacto (`TEAM-ENGINEERING`…), nunca el nombre del equipo. Con `teamId`: `TeamWorkPlanner` (el líder planifica con `format`, sin `tools`) → `TeamPlanValidator` (miembros reales, `requiredCapabilities` textuales, en desarrollo: todos los miembros, una `VALIDATION` para quien tenga `QA`, `ownedPaths` sin solapamiento, `entryPoint`) con 3 intentos y sin plan por defecto → `TeamExecutionStrategy` por `Team.type`: `AnalysisTeamStrategy` (Creative, Marketing: `AgentRuntime` actual vía `AgentTaskBatchRunner`) o `DevelopmentTeamStrategy` (Engineering). Sin `teamId`, discovery exactamente como antes.

**Engineering**: `DevelopmentRuntime` genera `DevelopmentResult` en paralelo (ruta insegura → falla sin reintento; fuera de `ownedPaths` o con `\` → reintento; la tarea queda `GENERATED`) → `DevelopmentWorkspaceService` hace un commit por agente en `products.workspace-root/<missionId>/` (autor = el agente, trailers `Forjai-Mission`/`Forjai-Task`; si el commit falla, la tarea falla) → `StaticWorkspaceValidator` (capa 1, Git real) → revisión estática del validador (`StaticReviewResult`, gates `RepositoryEvidenceGate` + `ForbiddenClaimsGuard`) → `validationStatus` calculado por Java (`STATICALLY_VALIDATED`/`UNVALIDATED`/`FAILED`) → el CEO consolida y Java agrega el bloque "Estado verificable". Nunca se ejecuta el código generado. `ProductStatus.DEVELOPMENT` = tarea `WORK` completada con `commitSha`; `QA` sigue inalcanzable. Borrar una misión borra también su workspace. En Docker el workspace es el volumen `~/forjai-products` (archivos creados como root: en el host usar `git -c safe.directory='*'`).
```

- [ ] **Step 3: Suite completa y frontend**

Run: `cd app && mvn test` → BUILD SUCCESS, 0 failures.
Run: `cd app/frontend && npm run lint && npm run build` → en verde.

- [ ] **Step 4: Verificación en vivo (obligatoria)**

1. Confirmar que no hay misiones en curso (sin eso, no reconstruir):

```bash
PW=$(grep NEO4J_PASSWORD .env | cut -d= -f2-)
docker exec neo4j cypher-shell -u neo4j -p "$PW" "MATCH (m:Mission) WHERE m.status IN ['PLANNING','DELEGATING','WAITING_AGENT_RESULTS','EVALUATING','CONSOLIDATING'] RETURN count(m) AS running"
```

Expected: `running = 0`.

2. Reconstruir y levantar (el directorio del volumen se crea antes para que no lo cree Docker como root):

```bash
mkdir -p ~/forjai-products
docker compose build && docker compose up -d
curl -s localhost:8081/actuator/health
docker exec ai-company-core git --version
```

Expected: `"status":"UP"` y una versión de git.

3. Lanzar una misión real de Engineering (environment `TEST` para no mezclarla con misiones de negocio):

```bash
curl -s -X POST localhost:8081/api/company/missions -H 'Content-Type: application/json' -d '{
  "missionId": "MISSION-TEAM-VERIFY-1",
  "instruction": "Crear un videojuego de navegador pequeño y completo que el equipo pueda terminar como MVP.",
  "environment": "TEST",
  "teamId": "TEAM-ENGINEERING"
}'
```

4. Esperar a `AWAITING_INVESTOR` o `FAILED` consultando `curl -s localhost:8081/api/company/missions/MISSION-TEAM-VERIFY-1` cada pocos minutos (con `qwen3:8b` puede tardar bastante).

5. Verificar con datos reales, anotando la salida:

```bash
# Ninguna tarea de discovery; tarea de plan del líder; kind/commit por tarea
docker exec neo4j cypher-shell -u neo4j -p "$PW" "MATCH (t:AgentTask {missionId:'MISSION-TEAM-VERIFY-1'}) RETURN t.agentId, t.kind, t.action, t.status, t.commitSha, t.files, t.validationStatus ORDER BY t.id"
# Commits reales por agente, con autor y trailer
git -c safe.directory='*' -C ~/forjai-products/MISSION-TEAM-VERIFY-1 log --format='%H %an <%ae> | %(trailers:key=Forjai-Task,valueonly)'
git -c safe.directory='*' -C ~/forjai-products/MISSION-TEAM-VERIFY-1 ls-tree -r --name-only HEAD
# Evidencia de la validadora citando shas reales
docker exec neo4j cypher-shell -u neo4j -p "$PW" "MATCH (:AgentTask {id:'MISSION-TEAM-VERIFY-1-QA'})-[:HAS_EVIDENCE]->(e:Evidence) RETURN e.source, e.sourceType"
# Mensaje final con el bloque Estado verificable
curl -s localhost:8081/api/company/missions/MISSION-TEAM-VERIFY-1 | grep -o 'ESTADO VERIFICABLE[^"]*' | head -c 2000
```

Criterios: no existen tareas de `sales`/`product`/`finance`; los 5 miembros de Engineering tienen tarea; cada `commitSha` de Neo4j existe en `git log` con el agente correcto como autor y el `taskId` en el trailer; la tarea de Vera tiene `validationStatus` y evidencia `workspace:MISSION-TEAM-VERIFY-1@<sha>/<ruta>`; el mensaje final contiene el bloque "Estado verificable" con la frase fija. Si algo falla, **no** documentar como verificado: arreglar (con su test) y repetir.

6. Abrir `http://localhost:8081/missions/MISSION-TEAM-VERIFY-1` y confirmar que se ven equipo, commits, archivos y la sección "Validación estática".

- [ ] **Step 5: `docs/HISTORY.md`**

Agregar una entrada "Misiones por equipo + generación real de código (Proyecto B, subproyecto 1)" con: el diagnóstico (`MISSION-1790304372795`), las decisiones que cambiaron frente al spec original (tabla del spec), los bugs reales encontrados durante la implementación/verificación, y la verificación en vivo con la salida real del Step 4 (ids, shas, `validationStatus`). La misión de verificación queda en Neo4j y en disco para que el fundador la revise; no borrarla.

- [ ] **Step 6: Commit**

```bash
git add docs/EVENTS.md CLAUDE.md docs/HISTORY.md
git commit -m "Documentar misiones por equipo y registrar la verificación en vivo" \
           -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Self-review

- **Cobertura del spec**: §1 teamId/chat/frontend → Tasks 4, 15, 16; §2 arquitectura (bifurcación, `AgentTaskBatchRunner`, estrategias, contrato común, estados) → Tasks 8, 13; §3 planner → Task 7; §4 validador → Task 3; §5 análisis → Task 13; §6 desarrollo (runtime, gates, workspace, commits por agente, falla de commit = falla de tarea, Docker) → Tasks 2, 9, 11, 14; §7 validación estática en dos capas + `validationStatus` por Java → Tasks 1, 10, 11, 12, 14; §8 consolidación + "Estado verificable" → Tasks 13, 14; §9 persistencia + `ProductStatus.DEVELOPMENT` → Tasks 5, 14; §10 borrado del workspace → Task 9; §11 eventos → Tasks 7, 14, 17; §12 frontend → Task 16; §13 autonomía → sin código (sin gate nuevo). Testing + verificación en vivo → cada task + Task 17.
- **Consistencia de tipos**: `createTask(taskId, missionId, agentId, action, kind)` (Task 5) usado en Tasks 7, 8, 14; `DevelopmentRuntime.generate(taskId, missionId, agentId, prompt, ownedPaths)`/`review(taskId, missionId, agentId, prompt, filesBySha)` (Task 11) usados en Task 14; `commitAgentWork(missionId, taskId, agentId, agentName, result) -> CommitRecord(sha, files)` (Task 9) usado en Tasks 12, 14; `StaticWorkspaceValidator.validate(missionId, work, entryPoint, allowedPaths)` (Task 12) usado en Task 14; `TeamWorkPlanner.plan(missionId, teamId, instruction, mode) -> TeamPlanResult` (Task 7) usado en Task 13; constructor final de `MissionExecutor` (Task 13) = Task 8 + `teamWorkPlanner, teamStrategies`.
- **Ids de tarea**: plan `<missionId>-<LÍDER>-PLAN`; trabajo y validación `<missionId>-<AGENTID en mayúsculas>` (mismo patrón que discovery).
