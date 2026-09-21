# Proyecto B (subproyecto 1): generación real de código — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `APPROVE` sobre una misión dispara generación real de código por Neo/Iris/Mila (Engineering Team), persistida en un repo Git real por misión — sin ejecutar ese código, sin build/test, sin deploy, sin gasto real. `ProductStatus.DEVELOPMENT` deja de estar fijo en `false`.

**Architecture:** Contrato nuevo (`DevelopmentResult`) deliberadamente separado de `AgentResult` — `AgentRuntime` no se toca. `DevelopmentRuntime` (clase nueva, espejo de `AgentRuntime`) llama a `CeoService.generateDevelopmentArtifact` (método nuevo, misma disciplina de "único cliente de Ollama"), valida cada ruta de archivo contra path traversal (`DevelopmentPathValidationGate`, sin reintento — mismo criterio que `EvidenceValidationGate`), y `DevelopmentWorkspaceService` escribe a disco + hace un solo commit consolidado después de que las 3 tareas paralelas ya terminaron (nunca durante la ejecución paralela). `MissionExecutor` gana una segunda orquestación (`executeDevelopmentAsync`), disparada desde `MissionService.recordDecision` en `APPROVE`.

**Tech Stack:** Java 21, Spring Boot 4.1.1, JUnit 5 + Mockito (sin Neo4j/Kafka/Ollama reales en la suite unitaria); `git` real vía `ProcessBuilder` (sin librería Git nueva en el classpath).

**Spec:** `docs/superpowers/specs/2026-09-21-development-generation-design.md`

## Global Constraints

- `AgentRuntime`/`AgentResult`/`AgentResultSchema` (discovery) **no se tocan** — todo lo nuevo vive en clases paralelas (`DevelopmentRuntime`/`DevelopmentResult`/`DevelopmentResultSchema`).
- Cada `GeneratedFile.path` se valida **antes** de escribir nada a disco — rutas absolutas, `..`, o vacías rechazan la tarea de inmediato, **sin reintento** (misma asimetría que `EvidenceValidationGate`).
- Los 3 `DevelopmentRuntime` en paralelo **solo escriben archivos**, nunca tocan Git directamente — el `git init`/`add`/`commit` es un paso único y secuencial en `MissionExecutor`, después de que las 3 tareas ya asentaron.
- Mismo criterio "agent failure ≠ mission failure": si al menos 1 de los 3 agentes de desarrollo completa, la misión sigue (con resultado parcial); si los 3 fallan, la misión termina en `FAILED` (y queda `DECIDABLE` de nuevo — el inversionista puede volver a `APPROVE` para reintentar, efecto colateral aceptado, no diseñado aparte).
- Sin replanificación automática de tareas de desarrollo en esta ronda (`MissionExecutor.replanFailedAgents` sigue siendo específico de discovery).
- Sin herramientas (`tools`) en la llamada de generación de código — una sola llamada a Ollama por intento, `format: DevelopmentResultSchema.SCHEMA`, sin `tools` (nunca combinar ambos, mismo guard ya existente `rejectFormatCombinedWithTools`).
- `ProductStatus.QA`/`PUBLISHED` siguen devolviendo `false` — no se tocan en esta ronda.
- Sin cambios de frontend en esta ronda.
- Tests sin Neo4j/Kafka/Ollama reales (mocks/fakes); el commit de Git sí se testea contra un directorio temporal real (es la forma más simple y realista de probarlo, sin mockear `ProcessBuilder`).

---

## File Structure

- **Create** `app/src/main/java/com/aicompany/core/agent/model/DevelopmentResult.java`
- **Create** `app/src/main/java/com/aicompany/core/agent/model/DevelopmentResultSchema.java`
- **Create** `app/src/main/java/com/aicompany/core/model/DevelopmentExecutionOutcome.java`
- **Create** `app/src/main/java/com/aicompany/core/agent/validation/DevelopmentPathValidationGate.java`
- **Modify** `app/src/main/java/com/aicompany/core/service/CeoService.java` — nuevo método `generateDevelopmentArtifact`.
- **Modify** `app/src/main/java/com/aicompany/core/service/MissionMemoryService.java` — nuevo método `instruction(missionId)`.
- **Create** `app/src/main/java/com/aicompany/core/agent/DevelopmentRuntime.java`
- **Create** `app/src/main/java/com/aicompany/core/service/DevelopmentWorkspaceService.java`
- **Modify** `app/src/main/resources/application.yml` — nueva config `products.workspace-root`.
- **Modify** `app/src/main/java/com/aicompany/core/service/MissionExecutor.java` — nuevo método `executeDevelopmentAsync` + dependencias nuevas.
- **Modify** `app/src/main/java/com/aicompany/core/service/MissionService.java` — `APPROVE` dispara `executeDevelopmentAsync` en vez de terminar en `COMPLETED` directo.
- **Modify** `app/src/main/java/com/aicompany/core/service/ProductStatusService.java` — `isInDevelopment` real.

---

### Task 1: Contratos nuevos — `DevelopmentResult`, `DevelopmentResultSchema`, `DevelopmentExecutionOutcome`

**Files:**
- Create: `app/src/main/java/com/aicompany/core/agent/model/DevelopmentResult.java`
- Create: `app/src/main/java/com/aicompany/core/agent/model/DevelopmentResultSchema.java`
- Create: `app/src/main/java/com/aicompany/core/model/DevelopmentExecutionOutcome.java`

**Interfaces:**
- Produces: `DevelopmentResult(String summary, List<GeneratedFile> files)` con `record GeneratedFile(String path, String content)` anidado; `DevelopmentResultSchema.SCHEMA` (`Map<String, Object>`); `DevelopmentExecutionOutcome(String agentId, boolean completed, DevelopmentResult result, String error)` con `success(agentId, result)`/`failure(agentId, error)` — consumidos por Tasks 2-6.

- [ ] **Step 1: Crear `DevelopmentResult`**

```java
package com.aicompany.core.agent.model;

import java.util.List;

/**
 * Contrato de una tarea de DESARROLLO real — deliberadamente separado de
 * {@link AgentResult} (el contrato de discovery: hechos/hipótesis/
 * evidencia/cálculos no tienen sentido para "escribir código"). Ver
 * docs/superpowers/specs/2026-09-21-development-generation-design.md,
 * decisión 3.
 */
public record DevelopmentResult(
        String summary,
        List<GeneratedFile> files
) {
    public record GeneratedFile(String path, String content) {
    }
}
```

- [ ] **Step 2: Crear `DevelopmentResultSchema`**

```java
package com.aicompany.core.agent.model;

import java.util.List;
import java.util.Map;

/**
 * JSON Schema formal de {@link DevelopmentResult}, mismo patrón que
 * {@link AgentResultSchema} — usado como valor del campo "format" en la
 * llamada a Ollama para forzar la forma exacta del contrato.
 */
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
                    "files", Map.of(
                            "type", "array",
                            "items", FILE_ITEM_SCHEMA,
                            "minItems", 1
                    )
            ),
            "required", List.of("summary", "files"),
            "additionalProperties", false
    );
}
```

- [ ] **Step 3: Crear `DevelopmentExecutionOutcome`**

```java
package com.aicompany.core.model;

import com.aicompany.core.agent.model.DevelopmentResult;

/**
 * Espejo de {@link AgentExecutionOutcome} para tareas de desarrollo —
 * mismo criterio "agent failure ≠ mission failure" aplicado a la fase de
 * ejecución, no solo a discovery.
 */
public record DevelopmentExecutionOutcome(
        String agentId,
        boolean completed,
        DevelopmentResult result,
        String error) {

    public static DevelopmentExecutionOutcome success(String agentId, DevelopmentResult result) {
        return new DevelopmentExecutionOutcome(agentId, true, result, null);
    }

    public static DevelopmentExecutionOutcome failure(String agentId, String error) {
        return new DevelopmentExecutionOutcome(agentId, false, null, error);
    }
}
```

- [ ] **Step 4: Compilar y correr la suite completa**

Run: `cd app && mvn test`
Expected: BUILD SUCCESS, mismo conteo de tests que antes (166) — estos 3 archivos son solo contratos nuevos, nada todavía los usa.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/agent/model/DevelopmentResult.java \
        app/src/main/java/com/aicompany/core/agent/model/DevelopmentResultSchema.java \
        app/src/main/java/com/aicompany/core/model/DevelopmentExecutionOutcome.java
git commit -m "Agregar contratos de desarrollo: DevelopmentResult, DevelopmentResultSchema, DevelopmentExecutionOutcome"
```

---

### Task 2: `DevelopmentPathValidationGate` (TDD)

**Files:**
- Create: `app/src/main/java/com/aicompany/core/agent/validation/DevelopmentPathValidationGate.java`
- Test: `app/src/test/java/com/aicompany/core/agent/validation/DevelopmentPathValidationGateTest.java`

**Interfaces:**
- Consumes: `DevelopmentResult` (Task 1).
- Produces: `DevelopmentPathValidationGate.validate(DevelopmentResult) -> ValidationResult(boolean valid, List<String> errors)` — consumido por Task 4.

- [ ] **Step 1: Escribir el test (falla: la clase no existe)**

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
        return new DevelopmentResult(
                "resumen",
                List.of(new DevelopmentResult.GeneratedFile(path, "contenido"))
        );
    }

    @Test
    void acceptsARealRelativePath() {
        var validation = gate.validate(resultWithPath("src/backend/Program.cs"));
        assertTrue(validation.valid());
        assertTrue(validation.errors().isEmpty());
    }

    @Test
    void rejectsAnAbsolutePath() {
        var validation = gate.validate(resultWithPath("/etc/passwd"));
        assertFalse(validation.valid());
    }

    @Test
    void rejectsAWindowsStyleAbsolutePath() {
        var validation = gate.validate(resultWithPath("C:\\Windows\\System32\\evil.dll"));
        assertFalse(validation.valid());
    }

    @Test
    void rejectsPathTraversal() {
        var validation = gate.validate(resultWithPath("../../etc/passwd"));
        assertFalse(validation.valid());
    }

    @Test
    void rejectsPathTraversalInTheMiddleOfThePath() {
        var validation = gate.validate(resultWithPath("src/../../secrets.txt"));
        assertFalse(validation.valid());
    }

    @Test
    void rejectsABlankPath() {
        var validation = gate.validate(resultWithPath("   "));
        assertFalse(validation.valid());
    }

    @Test
    void acceptsAResultWithMultipleValidFiles() {
        var result = new DevelopmentResult(
                "resumen",
                List.of(
                        new DevelopmentResult.GeneratedFile("src/A.java", "contenido A"),
                        new DevelopmentResult.GeneratedFile("src/B.java", "contenido B")
                )
        );
        assertTrue(gate.validate(result).valid());
    }

    @Test
    void treatsANullResultAsValid() {
        assertTrue(gate.validate(null).valid());
    }
}
```

- [ ] **Step 2: Ejecutar el test para confirmar que falla (compilación)**

Run: `cd app && mvn test -Dtest=DevelopmentPathValidationGateTest`
Expected: FAIL (compilación — la clase no existe)

- [ ] **Step 3: Implementar `DevelopmentPathValidationGate`**

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Gate de seguridad obligatorio antes de escribir cualquier archivo a
 * disco — mismo espíritu que {@link EvidenceValidationGate}: rechaza de
 * inmediato, **sin reintento** (una ruta insegura no es un error de forma
 * que el modelo pueda corregir con feedback útil). Ver
 * docs/superpowers/specs/2026-09-21-development-generation-design.md,
 * decisión 5.
 */
@Component
public class DevelopmentPathValidationGate {

    private static final Logger log =
            LoggerFactory.getLogger(DevelopmentPathValidationGate.class);

    public ValidationResult validate(DevelopmentResult result) {

        if (result == null || result.files() == null) {
            return new ValidationResult(true, List.of());
        }

        var errors = new ArrayList<String>();

        for (var file : result.files()) {

            if (file == null) {
                continue;
            }

            validatePath(file.path(), errors);
        }

        if (!errors.isEmpty()) {

            log.warn(
                    "DEVELOPMENT_PATH_INVALID errors={}",
                    errors
            );

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
        }
    }

    public record ValidationResult(boolean valid, List<String> errors) {
    }
}
```

- [ ] **Step 4: Ejecutar el test y confirmar que pasa**

Run: `cd app && mvn test -Dtest=DevelopmentPathValidationGateTest`
Expected: PASS (8 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/agent/validation/DevelopmentPathValidationGate.java \
        app/src/test/java/com/aicompany/core/agent/validation/DevelopmentPathValidationGateTest.java
git commit -m "Agregar DevelopmentPathValidationGate: rechaza rutas absolutas/path traversal antes de escribir a disco"
```

---

### Task 3: `CeoService.generateDevelopmentArtifact`

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/CeoService.java`
- Test: `app/src/test/java/com/aicompany/core/service/CeoServiceDevelopmentTest.java` (nuevo)

**Interfaces:**
- Consumes: `DevelopmentResult`/`DevelopmentResultSchema` (Task 1).
- Produces: `CeoService.generateDevelopmentArtifact(String agentId, String prompt, String model) -> DevelopmentResult` — consumido por Task 4.

- [ ] **Step 1: Escribir el test nuevo (falla: el método no existe)**

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
 * Sin Ollama real: `RestClient` mockeado no puede devolver una respuesta
 * real, así que esta clase solo prueba el camino de error (respuesta
 * vacía/no parseable) — mismo criterio ya establecido para el resto de
 * `CeoService` (`CeoServiceToolFormatGuardTest`/`CeoServiceChatHistoryTest`
 * tampoco ejercitan una llamada real a Ollama).
 */
class CeoServiceDevelopmentTest {

    private final CeoService ceoService = new CeoService(
            mock(RestClient.class),
            JsonMapper.builder().build(),
            mock(EvidenceAcquisitionService.class),
            mock(CompanyEventPublisher.class),
            new SimpleMeterRegistry()
    );

    @Test
    void throwsWhenOllamaResponseCannotBeParsedAsADevelopmentResult() {
        assertThrows(
                IllegalStateException.class,
                () -> ceoService.generateDevelopmentArtifact("engineering", "prompt de prueba", "qwen3:8b")
        );
    }
}
```

- [ ] **Step 2: Ejecutar el test para confirmar que falla (compilación)**

Run: `cd app && mvn test -Dtest=CeoServiceDevelopmentTest`
Expected: FAIL (compilación — `generateDevelopmentArtifact` no existe)

- [ ] **Step 3: Implementar `generateDevelopmentArtifact`**

Agregar los imports (junto a los demás de `CeoService.java`):

```java
import com.aicompany.core.agent.model.DevelopmentResult;
import com.aicompany.core.agent.model.DevelopmentResultSchema;
```

Agregar el método nuevo inmediatamente después de `executeMission` (línea 910, antes de `private String systemPrompt()`):

```java
    /**
     * Genera un {@link DevelopmentResult} real (código, no análisis) para
     * un agente del Engineering Team — una sola llamada a Ollama por
     * intento (`format: DevelopmentResultSchema.SCHEMA`, sin `tools`: no
     * hace falta el turno de decisión de herramienta de
     * {@link #executeAgentTask} en esta primera ronda). El reintento
     * (hasta {@code MAX_RESULT_RETRIES + 1} veces) y la validación de
     * rutas viven en {@code DevelopmentRuntime}, no acá — esta llamada es
     * la ejecución de un solo intento, igual que el turno final de
     * {@link #executeAgentTask}.
     */
    public DevelopmentResult generateDevelopmentArtifact(
            String agentId,
            String prompt,
            String model) {

        var system =
                systemPrompt()
                        + "\nTu rol específico en esta tarea es: "
                        + agentId
                        + ".";

        var messages = List.<Map<String, Object>>of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", prompt)
        );

        var finalTurn =
                callModel(
                        "DEVELOPMENT_TASK",
                        agentId,
                        model,
                        messages,
                        DevelopmentResultSchema.SCHEMA,
                        null,
                        false
                );

        var response = finalTurn.content();

        try {

            var normalizedResponse =
                    normalizeJsonResponse(response);

            log.info(
                    "DEVELOPMENT_RESULT_RAW agent={} response={}",
                    agentId,
                    response
            );

            var result =
                    jsonMapper.readValue(
                            normalizedResponse,
                            DevelopmentResult.class
                    );

            log.info(
                    "DEVELOPMENT_RESULT_PARSED agent={} files={}",
                    agentId,
                    result.files().size()
            );

            return result;

        } catch (Exception ex) {

            log.error(
                    "DEVELOPMENT_RESULT_PARSE_ERROR agent={} model={} reason={}",
                    agentId,
                    model,
                    ex.getMessage(),
                    ex
            );

            throw new IllegalStateException(
                    "El agente "
                            + agentId
                            + " no devolvió un DevelopmentResult JSON válido.",
                    ex
            );
        }
    }
```

(`callModel(operation, actor, model, messages, format, tools, think)` con `think=false` es exactamente la sobrecarga privada de 7 argumentos que ya usa el turno final de `executeAgentTask` — mismo patrón, no hace falta ningún cambio en `callModel` mismo).

- [ ] **Step 4: Ejecutar el test y confirmar que pasa**

Run: `cd app && mvn test -Dtest=CeoServiceDevelopmentTest`
Expected: PASS

- [ ] **Step 5: Ejecutar la suite completa**

Run: `cd app && mvn test`
Expected: PASS (166 previos + 1 nuevo = 167)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/CeoService.java \
        app/src/test/java/com/aicompany/core/service/CeoServiceDevelopmentTest.java
git commit -m "CeoService: agregar generateDevelopmentArtifact para tareas de desarrollo real"
```

---

### Task 4: `MissionMemoryService.instruction` + `DevelopmentRuntime`

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/MissionMemoryService.java`
- Create: `app/src/main/java/com/aicompany/core/agent/DevelopmentRuntime.java`
- Test: `app/src/test/java/com/aicompany/core/agent/DevelopmentRuntimeTest.java` (nuevo)

**Interfaces:**
- Consumes: `CeoService.generateDevelopmentArtifact` (Task 3); `DevelopmentPathValidationGate` (Task 2); `CompanyMemoryService.agentModel(String, String)` (ya existe).
- Produces: `MissionMemoryService.instruction(String missionId) -> Optional<String>` (consumido por Task 6); `DevelopmentRuntime.execute(String taskId, String missionId, String agentId, String action, String prompt) -> CompletableFuture<DevelopmentResult>` (consumido por Task 6).

- [ ] **Step 1: Agregar `MissionMemoryService.instruction`**

Agregar este método nuevo (por ejemplo, junto a `find`):

```java
    /**
     * Instrucción original de la misión, ya persistida desde
     * {@link #ensureMission} pero nunca expuesta hasta ahora — la
     * necesita {@code MissionService.recordDecision} para poder arrancar
     * la fase de desarrollo tras un `APPROVE` sin que el llamador (el
     * fundador, vía chat o el endpoint de decisión) tenga que repetirla.
     */
    public Optional<String> instruction(String missionId) {
        try (var session = driver.session()) {
            return session.run(
                            "MATCH (m:Mission {id:$id}) RETURN m.instruction AS instruction",
                            Map.of("id", missionId))
                    .list(r -> r.get("instruction").asString())
                    .stream().findFirst();
        }
    }
```

(sin test directo — mismo criterio ya establecido para toda la familia `*MemoryService`).

- [ ] **Step 2: Escribir `DevelopmentRuntimeTest` (falla: la clase no existe)**

```java
package com.aicompany.core.agent;

import com.aicompany.core.agent.model.DevelopmentResult;
import com.aicompany.core.agent.validation.DevelopmentPathValidationGate;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.service.CeoService;
import com.aicompany.core.service.CompanyMemoryService;
import com.aicompany.core.service.MissionMemoryService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DevelopmentRuntimeTest {

    private final MissionMemoryService memory = mock(MissionMemoryService.class);
    private final CompanyMemoryService companyMemory = mock(CompanyMemoryService.class);
    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    private final CeoService ceoService = mock(CeoService.class);
    private final DevelopmentPathValidationGate pathGate = new DevelopmentPathValidationGate();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private final DevelopmentRuntime runtime = new DevelopmentRuntime(
            ceoService, memory, companyMemory, "qwen3:8b", Runnable::run, events, pathGate, jsonMapper
    );

    private DevelopmentResult devResult(String path) {
        return new DevelopmentResult(
                "resumen",
                List.of(new DevelopmentResult.GeneratedFile(path, "contenido"))
        );
    }

    @Test
    void succeedsOnFirstAttemptWithoutRetrying() throws Exception {

        when(companyMemory.agentModel("engineering", "qwen3:8b")).thenReturn("qwen3:8b");
        when(ceoService.generateDevelopmentArtifact(eq("engineering"), anyString(), eq("qwen3:8b")))
                .thenReturn(devResult("src/Program.cs"));

        var result = runtime.execute("TASK-1", "MISSION-1", "engineering", "ARCHITECTURE_DEVELOPMENT", "prompt").get();

        assertEquals("resumen", result.summary());
        verify(ceoService, times(1)).generateDevelopmentArtifact(eq("engineering"), anyString(), eq("qwen3:8b"));
        verify(memory).updateTask(eq("TASK-1"), eq("COMPLETED"), anyString());
    }

    @Test
    void retriesWhenThePathGateRejectsTheFirstAttempt() throws Exception {

        when(companyMemory.agentModel("engineering", "qwen3:8b")).thenReturn("qwen3:8b");
        when(ceoService.generateDevelopmentArtifact(eq("engineering"), anyString(), eq("qwen3:8b")))
                .thenReturn(devResult("../etc/passwd"))
                .thenReturn(devResult("src/Program.cs"));

        var result = runtime.execute("TASK-1", "MISSION-1", "engineering", "ARCHITECTURE_DEVELOPMENT", "prompt").get();

        assertEquals("src/Program.cs", result.files().get(0).path());
        verify(ceoService, times(2)).generateDevelopmentArtifact(eq("engineering"), anyString(), eq("qwen3:8b"));
    }

    @Test
    void failsTheTaskAfterExhaustingRetriesOnPersistentInvalidPaths() {

        when(companyMemory.agentModel("engineering", "qwen3:8b")).thenReturn("qwen3:8b");
        when(ceoService.generateDevelopmentArtifact(eq("engineering"), anyString(), eq("qwen3:8b")))
                .thenReturn(devResult("../etc/passwd"));

        var future = runtime.execute("TASK-1", "MISSION-1", "engineering", "ARCHITECTURE_DEVELOPMENT", "prompt");

        assertThrows(ExecutionException.class, future::get);
        verify(ceoService, times(3)).generateDevelopmentArtifact(eq("engineering"), anyString(), eq("qwen3:8b"));
        verify(memory).updateTask(eq("TASK-1"), eq("FAILED"), anyString());
    }

    @Test
    void alwaysReturnsAgentStatusToIdleEvenAfterExhaustingRetries() {

        when(companyMemory.agentModel("engineering", "qwen3:8b")).thenReturn("qwen3:8b");
        when(ceoService.generateDevelopmentArtifact(eq("engineering"), anyString(), eq("qwen3:8b")))
                .thenReturn(devResult("../etc/passwd"));

        var future = runtime.execute("TASK-1", "MISSION-1", "engineering", "ARCHITECTURE_DEVELOPMENT", "prompt");

        assertThrows(ExecutionException.class, future::get);

        var inOrder = inOrder(memory);
        inOrder.verify(memory).setAgentStatus("engineering", "WORKING");
        inOrder.verify(memory).setAgentStatus("engineering", "IDLE");
    }

    @Test
    void resolvesTheRealPerAgentModelBeforeCallingCeoService() throws Exception {

        when(companyMemory.agentModel("backend", "qwen3:8b")).thenReturn("llama3:8b");
        when(ceoService.generateDevelopmentArtifact(eq("backend"), anyString(), eq("llama3:8b")))
                .thenReturn(devResult("src/api.py"));

        runtime.execute("TASK-2", "MISSION-1", "backend", "BACKEND_DEVELOPMENT", "prompt").get();

        verify(ceoService).generateDevelopmentArtifact(eq("backend"), anyString(), eq("llama3:8b"));
    }
}
```

- [ ] **Step 3: Ejecutar los tests para confirmar que fallan (compilación)**

Run: `cd app && mvn test -Dtest=DevelopmentRuntimeTest`
Expected: FAIL (compilación — `DevelopmentRuntime` no existe)

- [ ] **Step 4: Implementar `DevelopmentRuntime`**

```java
package com.aicompany.core.agent;

import com.aicompany.core.agent.model.DevelopmentResult;
import com.aicompany.core.agent.validation.DevelopmentPathValidationGate;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.service.CeoService;
import com.aicompany.core.service.CompanyMemoryService;
import com.aicompany.core.service.MissionMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Espejo deliberado de {@code AgentRuntime} para un contrato distinto
 * ({@link DevelopmentResult}, no {@code AgentResult}) — ver
 * docs/superpowers/specs/2026-09-21-development-generation-design.md,
 * decisión 4. No se generalizó {@code AgentRuntime} para aceptar un
 * contrato pluggable: evita tocar código de discovery ya probado en
 * producción para una necesidad que hoy tiene un solo caso de uso.
 */
@Service
public class DevelopmentRuntime {

    private static final Logger log =
            LoggerFactory.getLogger(DevelopmentRuntime.class);

    private static final int MAX_RESULT_RETRIES = 2;

    private final CeoService ceoService;
    private final MissionMemoryService memory;
    private final CompanyMemoryService companyMemory;
    private final String defaultAgentModel;
    private final Executor agentTaskExecutor;
    private final CompanyEventPublisher events;
    private final DevelopmentPathValidationGate pathGate;
    private final JsonMapper jsonMapper;

    public DevelopmentRuntime(
            CeoService ceoService,
            MissionMemoryService memory,
            CompanyMemoryService companyMemory,
            @Value("${ollama.agent-model}") String defaultAgentModel,
            @Qualifier("agentTaskExecutor") Executor agentTaskExecutor,
            CompanyEventPublisher events,
            DevelopmentPathValidationGate pathGate,
            JsonMapper jsonMapper) {

        this.ceoService = ceoService;
        this.memory = memory;
        this.companyMemory = companyMemory;
        this.defaultAgentModel = defaultAgentModel;
        this.agentTaskExecutor = agentTaskExecutor;
        this.events = events;
        this.pathGate = pathGate;
        this.jsonMapper = jsonMapper;
    }

    public CompletableFuture<DevelopmentResult> execute(
            String taskId,
            String missionId,
            String agentId,
            String action,
            String prompt) {

        log.info(
                "TASK {} - submitting development agent {} action {}",
                taskId, agentId, action
        );

        try {

            return CompletableFuture.supplyAsync(
                    () -> executeInternal(taskId, missionId, agentId, prompt),
                    agentTaskExecutor
            );

        } catch (Exception ex) {

            var message = safeMessage(ex, "No se pudo iniciar la tarea de desarrollo.");

            memory.updateTask(taskId, "FAILED", message);

            events.publishTask(
                    "EMPRESA_TASK_FAILED", taskId, missionId, agentId, "FAILED", message
            );

            log.error("TASK {} - could not submit development task", taskId, ex);

            return CompletableFuture.failedFuture(ex);
        }
    }

    private DevelopmentResult executeInternal(
            String taskId,
            String missionId,
            String agentId,
            String prompt) {

        log.info("TASK {} - development agent {} started", taskId, agentId);

        memory.updateTask(taskId, "RUNNING", "Agente iniciado.");

        var model = companyMemory.agentModel(agentId, defaultAgentModel);

        memory.setAgentStatus(agentId, "WORKING");

        events.publishTask(
                "EMPRESA_TASK_STARTED", taskId, missionId, agentId, "RUNNING", "Agente iniciado."
        );

        try {

            DevelopmentResult result = null;
            String validationFeedback = null;

            for (int attempt = 0; attempt <= MAX_RESULT_RETRIES; attempt++) {

                var attemptPrompt = prompt;

                if (validationFeedback != null) {

                    attemptPrompt += """

                            CORRECCIÓN DEL INTENTO ANTERIOR

                            El resultado anterior fue rechazado.
                            Corrige únicamente los errores indicados.

                            ERRORES:
                            %s

                            Nunca uses rutas absolutas ni ".." en los paths.
                            """.formatted(validationFeedback);
                }

                if (attempt > 0) {

                    events.publishTask(
                            "EMPRESA_TASK_RETRY", taskId, missionId, agentId, "RETRYING",
                            "Reintentando resultado de desarrollo. Intento "
                                    + (attempt + 1) + " de " + (MAX_RESULT_RETRIES + 1)
                    );
                }

                try {

                    result = ceoService.generateDevelopmentArtifact(agentId, attemptPrompt, model);

                } catch (Exception ex) {

                    var reason = safeMessage(ex, "El agente no devolvió una respuesta procesable.");

                    if (attempt == MAX_RESULT_RETRIES) {

                        throw new IllegalStateException(
                                "El agente " + agentId
                                        + " no produjo un DevelopmentResult procesable después de "
                                        + (MAX_RESULT_RETRIES + 1) + " intentos: " + reason,
                                ex
                        );
                    }

                    validationFeedback = reason;
                    continue;
                }

                var validation = pathGate.validate(result);

                if (validation.valid()) {
                    break;
                }

                validationFeedback = String.join("\n- ", validation.errors());

                if (attempt == MAX_RESULT_RETRIES) {

                    throw new IllegalStateException(
                            "Rutas de archivo inválidas después de "
                                    + (MAX_RESULT_RETRIES + 1) + " intentos: " + validationFeedback
                    );
                }
            }

            var resultJson = toJson(result);

            memory.updateTask(taskId, "COMPLETED", resultJson);

            events.publishTask(
                    "EMPRESA_TASK_COMPLETED", taskId, missionId, agentId, "COMPLETED", resultJson
            );

            log.info(
                    "TASK {} - development agent {} completed files={}",
                    taskId, agentId, result.files().size()
            );

            return result;

        } catch (Exception ex) {

            var message = safeMessage(ex, "Error inesperado");

            memory.updateTask(taskId, "FAILED", message);

            events.publishTask(
                    "EMPRESA_TASK_FAILED", taskId, missionId, agentId, "FAILED", message
            );

            log.error("TASK {} - development agent {} failed", taskId, agentId, ex);

            throw new RuntimeException(ex);

        } finally {

            memory.setAgentStatus(agentId, "IDLE");
        }
    }

    private String toJson(DevelopmentResult result) {
        try {
            return jsonMapper.writeValueAsString(result);
        } catch (JacksonException ex) {
            throw new IllegalStateException("No se pudo serializar el resultado de desarrollo.", ex);
        }
    }

    private String safeMessage(Exception ex, String defaultMessage) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? defaultMessage : ex.getMessage();
    }
}
```

**Nota sobre el `catch` final**: a diferencia de `AgentRuntime` (que hace `throw ex;` porque su método declara `throws` implícitamente vía excepciones no chequeadas del mismo tipo), acá se envuelve en `new RuntimeException(ex)` para que `executeInternal` (que devuelve `DevelopmentResult`, sin cláusula `throws`) siga compilando sin declarar excepciones chequeadas — el `IllegalStateException` ya lanzado más arriba en el método es una `RuntimeException` y se propaga tal cual sin este envoltorio adicional (el único camino que llega a este `catch` con algo que no sea ya una `RuntimeException` sería una excepción chequeada de una dependencia futura; hoy no hay ninguna, así que en la práctica este envoltorio nunca duplica un wrapping — confirmarlo al implementar: si `ex` ya es una `RuntimeException`, relanzarla tal cual con `throw ex;` en vez de envolverla de nuevo, igual que hace `AgentRuntime`).

- [ ] **Step 5: Ejecutar los tests y confirmar que pasan**

Run: `cd app && mvn test -Dtest=DevelopmentRuntimeTest`
Expected: PASS (5 tests)

- [ ] **Step 6: Ejecutar la suite completa**

Run: `cd app && mvn test`
Expected: PASS (167 previos + 5 nuevos = 172)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/MissionMemoryService.java \
        app/src/main/java/com/aicompany/core/agent/DevelopmentRuntime.java \
        app/src/test/java/com/aicompany/core/agent/DevelopmentRuntimeTest.java
git commit -m "Agregar DevelopmentRuntime (espejo de AgentRuntime para tareas de desarrollo real) y MissionMemoryService.instruction"
```

---

### Task 5: `DevelopmentWorkspaceService` (workspace + Git real)

**Files:**
- Create: `app/src/main/java/com/aicompany/core/service/DevelopmentWorkspaceService.java`
- Test: `app/src/test/java/com/aicompany/core/service/DevelopmentWorkspaceServiceTest.java` (nuevo)
- Modify: `app/src/main/resources/application.yml`

**Interfaces:**
- Consumes: `DevelopmentResult` (Task 1).
- Produces: `DevelopmentWorkspaceService.writeFiles(String missionId, String subdirectory, DevelopmentResult result)`; `DevelopmentWorkspaceService.commitWorkspace(String missionId, String commitMessage)` — consumidos por Task 6.

- [ ] **Step 1: Agregar la config nueva a `application.yml`**

Agregar, junto al bloque `ollama:` existente (mismo estilo: variable de entorno con default vía `${...:...}`):

```yaml
products:
  workspace-root: ${PRODUCTS_WORKSPACE_ROOT:${user.home}/forjai-products}
```

- [ ] **Step 2: Escribir `DevelopmentWorkspaceServiceTest` (falla: la clase no existe)**

```java
package com.aicompany.core.service;

import com.aicompany.core.agent.model.DevelopmentResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contra un directorio temporal REAL (no un mock de ProcessBuilder) —
 * probar que `git init`/`add`/`commit` realmente ocurren es más simple y
 * realista así que mockeando la ejecución de un proceso externo.
 */
class DevelopmentWorkspaceServiceTest {

    private final Path tempRoot = Path.of(
            System.getProperty("java.io.tmpdir"),
            "forjai-dev-workspace-test-" + UUID.randomUUID()
    );

    private final DevelopmentWorkspaceService workspace =
            new DevelopmentWorkspaceService(tempRoot.toString());

    @AfterEach
    void cleanup() throws IOException {
        if (Files.exists(tempRoot)) {
            try (var walk = Files.walk(tempRoot)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.delete(path);
                            } catch (IOException ignored) {
                                // best-effort cleanup de un directorio temporal de test
                            }
                        });
            }
        }
    }

    @Test
    void writesRealFilesUnderTheAgentSubdirectory() throws IOException {

        var result = new DevelopmentResult(
                "resumen",
                List.of(new DevelopmentResult.GeneratedFile("src/Program.cs", "// contenido real"))
        );

        workspace.writeFiles("MISSION-1", "architecture", result);

        var written = tempRoot.resolve("MISSION-1").resolve("architecture").resolve("src/Program.cs");

        assertTrue(Files.exists(written));
        assertEquals("// contenido real", Files.readString(written));
    }

    @Test
    void commitsARealGitRepositoryAfterWritingFiles() throws IOException, InterruptedException {

        var result = new DevelopmentResult(
                "resumen",
                List.of(new DevelopmentResult.GeneratedFile("README.md", "# Proyecto generado"))
        );

        workspace.writeFiles("MISSION-2", "architecture", result);
        workspace.commitWorkspace("MISSION-2", "Desarrollo inicial generado");

        var missionDir = tempRoot.resolve("MISSION-2");

        assertTrue(Files.exists(missionDir.resolve(".git")));

        var log = new ProcessBuilder("git", "log", "--oneline")
                .directory(missionDir.toFile())
                .start();
        log.waitFor();
        var output = new String(log.getInputStream().readAllBytes());

        assertTrue(output.contains("Desarrollo inicial generado"));
    }

    @Test
    void commitWorkspaceNeverThrowsEvenIfCalledWithoutAnyFilesWritten() {
        assertDoesNotThrow(() -> workspace.commitWorkspace("MISSION-EMPTY", "commit vacío"));
    }
}
```

- [ ] **Step 3: Ejecutar los tests para confirmar que fallan (compilación)**

Run: `cd app && mvn test -Dtest=DevelopmentWorkspaceServiceTest`
Expected: FAIL (compilación — la clase no existe)

- [ ] **Step 4: Implementar `DevelopmentWorkspaceService`**

```java
package com.aicompany.core.service;

import com.aicompany.core.agent.model.DevelopmentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Workspace real por misión — un repo Git real, sin ejecutar ni desplegar
 * nada (ver docs/superpowers/specs/2026-09-21-development-generation-design.md,
 * decisión 6). Los `DevelopmentRuntime` en paralelo solo escriben
 * archivos (vía {@link #writeFiles}); el commit (vía
 * {@link #commitWorkspace}) es un paso único y secuencial, llamado
 * después de que las tareas paralelas ya asentaron — nunca durante la
 * escritura concurrente, para no correr dos `git add`/`commit` a la vez
 * sobre el mismo índice.
 */
@Service
public class DevelopmentWorkspaceService {

    private static final Logger log =
            LoggerFactory.getLogger(DevelopmentWorkspaceService.class);

    private final Path workspaceRoot;

    public DevelopmentWorkspaceService(
            @Value("${products.workspace-root}") String workspaceRoot) {
        this.workspaceRoot = Path.of(workspaceRoot);
    }

    public Path missionWorkspace(String missionId) {
        return workspaceRoot.resolve(missionId);
    }

    /**
     * Escribe los archivos reales de un {@link DevelopmentResult} ya
     * validado — nunca llamar sin pasar antes por
     * {@code DevelopmentPathValidationGate}. Cada agente escribe bajo su
     * propio subdirectorio de convención (evita que dos agentes en
     * paralelo escriban el mismo archivo).
     */
    public void writeFiles(String missionId, String subdirectory, DevelopmentResult result) throws IOException {

        var agentDir = missionWorkspace(missionId).resolve(subdirectory).normalize();

        Files.createDirectories(agentDir);

        for (var file : result.files()) {

            var target = agentDir.resolve(file.path()).normalize();

            if (!target.startsWith(agentDir)) {
                // Defensa en profundidad — DevelopmentPathValidationGate ya
                // debería haber rechazado esto antes de llegar acá.
                throw new IllegalStateException("Ruta fuera del workspace: " + file.path());
            }

            Files.createDirectories(target.getParent());
            Files.writeString(target, file.content(), StandardCharsets.UTF_8);
        }
    }

    /**
     * {@code git init} (si hace falta) + {@code add -A} + un solo commit
     * consolidado. Best-effort: un fallo acá no debe tumbar la misión
     * (mismo criterio que {@code AlertMailService.send}) — se loguea
     * `WARN` y se sigue.
     */
    public void commitWorkspace(String missionId, String commitMessage) {

        var missionDir = missionWorkspace(missionId);

        try {

            Files.createDirectories(missionDir);

            if (!Files.exists(missionDir.resolve(".git"))) {
                runGit(missionDir, "init");
            }

            runGit(missionDir, "add", "-A");
            runGit(
                    missionDir,
                    "-c", "user.name=Forjai Engineering Team",
                    "-c", "user.email=engineering@forjai.local",
                    "commit", "-m", commitMessage, "--allow-empty"
            );

        } catch (Exception ex) {

            log.warn(
                    "DEVELOPMENT_COMMIT_FAILED missionId={} reason={}",
                    missionId,
                    ex.getMessage()
            );
        }
    }

    private void runGit(Path dir, String... args) throws IOException, InterruptedException {

        var command = new ArrayList<String>();
        command.add("git");
        command.addAll(List.of(args));

        var process = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();

        var finished = process.waitFor(30, TimeUnit.SECONDS);

        if (!finished) {
            process.destroyForcibly();
            throw new IOException("git " + String.join(" ", args) + " superó el timeout");
        }

        if (process.exitValue() != 0) {

            var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            throw new IOException("git " + String.join(" ", args) + " falló: " + output);
        }
    }
}
```

- [ ] **Step 5: Ejecutar los tests y confirmar que pasan**

Run: `cd app && mvn test -Dtest=DevelopmentWorkspaceServiceTest`
Expected: PASS (3 tests — requiere `git` real instalado en el `PATH` del entorno de test; si no está disponible, el segundo test fallaría de forma obvia y ruidosa, no silenciosa)

- [ ] **Step 6: Ejecutar la suite completa**

Run: `cd app && mvn test`
Expected: PASS (172 previos + 3 nuevos = 175)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/DevelopmentWorkspaceService.java \
        app/src/test/java/com/aicompany/core/service/DevelopmentWorkspaceServiceTest.java \
        app/src/main/resources/application.yml
git commit -m "Agregar DevelopmentWorkspaceService: workspace real por misión + commit Git consolidado"
```

---

### Task 6: `MissionExecutor.executeDevelopmentAsync` + `MissionService.recordDecision` dispara desarrollo

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/MissionExecutor.java`
- Modify: `app/src/main/java/com/aicompany/core/service/MissionService.java`
- Test: `app/src/test/java/com/aicompany/core/service/MissionExecutorDevelopmentTest.java` (nuevo)
- Modify: `app/src/test/java/com/aicompany/core/service/MissionServiceTest.java`

**Interfaces:**
- Consumes: `DevelopmentRuntime.execute(...)` (Task 4); `DevelopmentWorkspaceService.writeFiles`/`commitWorkspace` (Task 5); `MissionMemoryService.instruction` (Task 4); `DevelopmentExecutionOutcome` (Task 1).
- Produces: `MissionExecutor.executeDevelopmentAsync(String missionId, String instruction) -> CompletableFuture<Void>`.

- [ ] **Step 1: Escribir `MissionExecutorDevelopmentTest` (falla: el método no existe)**

```java
package com.aicompany.core.service;

import com.aicompany.core.agent.DevelopmentRuntime;
import com.aicompany.core.agent.AgentRuntime;
import com.aicompany.core.agent.model.DevelopmentResult;
import com.aicompany.core.agent.validation.ContradictionDetector;
import com.aicompany.core.config.AppProperties;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.AgentTask;
import com.aicompany.core.model.MissionStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MissionExecutorDevelopmentTest {

    private final MissionMemoryService memory = mock(MissionMemoryService.class);
    private final AgentRuntime runtime = mock(AgentRuntime.class);
    private final DevelopmentRuntime developmentRuntime = mock(DevelopmentRuntime.class);
    private final DevelopmentWorkspaceService developmentWorkspace = mock(DevelopmentWorkspaceService.class);
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
            contradictionDetector, appProperties, opportunityMemory, alertMailService,
            developmentRuntime, developmentWorkspace
    );

    private DevelopmentResult devResult(String summary) {
        return new DevelopmentResult(summary, List.of(new DevelopmentResult.GeneratedFile("src/A.txt", "x")));
    }

    @Test
    void reachesCompletedWhenAllThreeDevelopmentAgentsSucceed() throws Exception {

        when(memory.tasks("MISSION-1")).thenReturn(List.of());

        when(developmentRuntime.execute(anyString(), eq("MISSION-1"), eq("engineering"), eq("ARCHITECTURE_DEVELOPMENT"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(devResult("arquitectura lista")));
        when(developmentRuntime.execute(anyString(), eq("MISSION-1"), eq("backend"), eq("BACKEND_DEVELOPMENT"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(devResult("backend listo")));
        when(developmentRuntime.execute(anyString(), eq("MISSION-1"), eq("frontend-ui"), eq("FRONTEND_DEVELOPMENT"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(devResult("frontend listo")));

        executor.executeDevelopmentAsync("MISSION-1", "instrucción real").get();

        verify(memory).updateMission(eq("MISSION-1"), eq(MissionStatus.COMPLETED), anyInt(), anyString(), anyString());
        verify(developmentWorkspace, times(3)).writeFiles(eq("MISSION-1"), anyString(), any());
        verify(developmentWorkspace).commitWorkspace(eq("MISSION-1"), anyString());
    }

    @Test
    void continuesWithPartialResultsWhenOneDevelopmentAgentFails() throws Exception {

        when(memory.tasks("MISSION-1")).thenReturn(List.of());

        when(developmentRuntime.execute(anyString(), eq("MISSION-1"), eq("engineering"), eq("ARCHITECTURE_DEVELOPMENT"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(devResult("arquitectura lista")));
        when(developmentRuntime.execute(anyString(), eq("MISSION-1"), eq("backend"), eq("BACKEND_DEVELOPMENT"), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("fallo real")));
        when(developmentRuntime.execute(anyString(), eq("MISSION-1"), eq("frontend-ui"), eq("FRONTEND_DEVELOPMENT"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(devResult("frontend listo")));

        executor.executeDevelopmentAsync("MISSION-1", "instrucción real").get();

        verify(memory).updateMission(eq("MISSION-1"), eq(MissionStatus.COMPLETED), anyInt(), anyString(), anyString());
        verify(developmentWorkspace, times(2)).writeFiles(eq("MISSION-1"), anyString(), any());
    }

    @Test
    void failsTheMissionWhenAllThreeDevelopmentAgentsFail() throws Exception {

        when(memory.tasks("MISSION-1")).thenReturn(List.of());

        when(developmentRuntime.execute(anyString(), eq("MISSION-1"), anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("fallo real")));

        executor.executeDevelopmentAsync("MISSION-1", "instrucción real").get();

        verify(memory).updateMission(eq("MISSION-1"), eq(MissionStatus.FAILED), anyInt(), anyString(), anyString());
        verify(developmentWorkspace, never()).commitWorkspace(anyString(), anyString());
    }

    @Test
    void includesCompletedDiscoveryTasksAsContextInTheDevelopmentPrompt() throws Exception {

        when(memory.tasks("MISSION-1")).thenReturn(List.of(
                new AgentTask("MISSION-1-PRODUCT", "MISSION-1", "product", "OFFER_DESIGN", "COMPLETED", "{\"recommendation\":\"oferta real\"}", Instant.now())
        ));

        when(developmentRuntime.execute(anyString(), eq("MISSION-1"), anyString(), anyString(), contains("oferta real")))
                .thenReturn(CompletableFuture.completedFuture(devResult("ok")));

        executor.executeDevelopmentAsync("MISSION-1", "instrucción real").get();

        verify(developmentRuntime, times(3)).execute(anyString(), eq("MISSION-1"), anyString(), anyString(), contains("oferta real"));
    }
}
```

- [ ] **Step 2: Ejecutar el test para confirmar que falla (compilación)**

Run: `cd app && mvn test -Dtest=MissionExecutorDevelopmentTest`
Expected: FAIL (compilación — constructor de `MissionExecutor` no tiene 14 parámetros todavía, `executeDevelopmentAsync` no existe)

- [ ] **Step 3: Extender el constructor de `MissionExecutor`**

Agregar los imports:

```java
import com.aicompany.core.agent.DevelopmentRuntime;
import com.aicompany.core.agent.model.DevelopmentResult;
import com.aicompany.core.model.DevelopmentExecutionOutcome;
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
    private final DevelopmentRuntime developmentRuntime;
    private final DevelopmentWorkspaceService developmentWorkspace;

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
            AlertMailService alertMailService,
            DevelopmentRuntime developmentRuntime,
            DevelopmentWorkspaceService developmentWorkspace) {

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
        this.developmentRuntime = developmentRuntime;
        this.developmentWorkspace = developmentWorkspace;
    }
```

- [ ] **Step 4: Agregar `executeDevelopmentAsync` y sus métodos privados**

Agregar (por ejemplo, justo después de `executeAsync`/antes de `executeInternal` de discovery):

```java
    private record DevelopmentDefinition(
            String agentId,
            String action,
            String subdirectory,
            String objective) {
    }

    private static final List<DevelopmentDefinition> DEVELOPMENT_DEFINITIONS = List.of(
            new DevelopmentDefinition(
                    "engineering", "ARCHITECTURE_DEVELOPMENT", "architecture",
                    "Definir la arquitectura técnica y el andamiaje inicial del backend."
            ),
            new DevelopmentDefinition(
                    "backend", "BACKEND_DEVELOPMENT", "backend",
                    "Implementar la lógica de negocio y las APIs principales."
            ),
            new DevelopmentDefinition(
                    "frontend-ui", "FRONTEND_DEVELOPMENT", "frontend",
                    "Implementar la interfaz de usuario inicial."
            )
    );

    /**
     * Segunda orquestación de {@code MissionExecutor}, disparada desde
     * {@code MissionService.recordDecision} cuando el inversionista
     * aprueba la misión — mismo patrón que {@link #executeAsync} para
     * discovery (async, un método público que delega a uno interno sobre
     * {@code orchestratorExecutor}), pero para las 3 tareas de desarrollo
     * real del Engineering Team. Ver
     * docs/superpowers/specs/2026-09-21-development-generation-design.md.
     */
    public CompletableFuture<Void> executeDevelopmentAsync(
            String missionId,
            String instruction) {

        log.info("MISSION {} - submitting development orchestration", missionId);

        try {

            return CompletableFuture.runAsync(
                    () -> executeDevelopmentInternal(missionId, instruction),
                    orchestratorExecutor
            );

        } catch (Exception ex) {

            log.error("MISSION {} - could not submit development orchestration", missionId, ex);

            safeFail(missionId, ex);

            return CompletableFuture.failedFuture(ex);
        }
    }

    private void executeDevelopmentInternal(String missionId, String instruction) {

        log.info("MISSION {} - development execution started", missionId);

        try {

            var discoveryContext =
                    memory.tasks(missionId).stream()
                            .filter(t -> "OFFER_DESIGN".equals(t.action())
                                    || "DELIVERY_FEASIBILITY".equals(t.action()))
                            .filter(t -> "COMPLETED".equals(t.status()))
                            .map(t -> t.agentId() + " (" + t.action() + "):\n" + t.result())
                            .collect(Collectors.joining("\n\n"));

            var contextForPrompt =
                    discoveryContext.isBlank() ? "(sin contexto adicional)" : discoveryContext;

            var futuresByAgent =
                    new LinkedHashMap<String, CompletableFuture<DevelopmentResult>>();
            var subdirectoryByAgent =
                    new LinkedHashMap<String, String>();

            for (var definition : DEVELOPMENT_DEFINITIONS) {

                var agentId = definition.agentId();
                var taskId = missionId + "-" + agentId.toUpperCase() + "-DEV";

                memory.createTask(taskId, missionId, agentId, definition.action());

                events.publishTask(
                        "EMPRESA_TASK_CREATED", taskId, missionId, agentId, "PENDING",
                        "Tarea de desarrollo creada."
                );

                var prompt = buildDevelopmentPrompt(
                        agentId, definition.objective(), instruction, contextForPrompt
                );

                var future =
                        developmentRuntime.execute(
                                taskId, missionId, agentId, definition.action(), prompt
                        );

                futuresByAgent.put(agentId, future);
                subdirectoryByAgent.put(agentId, definition.subdirectory());
            }

            advanceMission(
                    missionId, MissionStatus.EXECUTING, 97, "Desarrollo",
                    "Los agentes están generando código real en paralelo."
            );

            var outcomes = new ArrayList<DevelopmentExecutionOutcome>();

            for (var entry : futuresByAgent.entrySet()) {

                var agentId = entry.getKey();

                try {

                    var result = entry.getValue().join();

                    outcomes.add(DevelopmentExecutionOutcome.success(agentId, result));

                } catch (Exception ex) {

                    var reason = safeMessage(ex, "El agente no completó su tarea de desarrollo.");

                    log.warn(
                            "MISSION {} - development agent {} did not complete: {}",
                            missionId, agentId, reason
                    );

                    outcomes.add(DevelopmentExecutionOutcome.failure(agentId, reason));
                }
            }

            var completedOutcomes =
                    outcomes.stream().filter(DevelopmentExecutionOutcome::completed).toList();
            var failedOutcomes =
                    outcomes.stream().filter(o -> !o.completed()).toList();

            if (completedOutcomes.isEmpty()) {

                throw new IllegalStateException(
                        "Los " + failedOutcomes.size()
                                + " agente(s) de desarrollo fallaron: "
                                + failedOutcomes.stream()
                                        .map(o -> o.agentId() + " (" + o.error() + ")")
                                        .collect(Collectors.joining("; "))
                );
            }

            for (var outcome : completedOutcomes) {

                try {

                    developmentWorkspace.writeFiles(
                            missionId,
                            subdirectoryByAgent.get(outcome.agentId()),
                            outcome.result()
                    );

                } catch (Exception ex) {

                    log.warn(
                            "MISSION {} - could not write files for agent {}: {}",
                            missionId, outcome.agentId(), ex.getMessage()
                    );
                }
            }

            var commitMessage =
                    "Desarrollo generado por Forjai Engineering Team\n\n"
                            + completedOutcomes.stream()
                                    .map(o -> "- " + o.agentId() + ": " + o.result().summary())
                                    .collect(Collectors.joining("\n"));

            developmentWorkspace.commitWorkspace(missionId, commitMessage);

            var statusMessage =
                    failedOutcomes.isEmpty()
                            ? "Los 3 agentes completaron el desarrollo real."
                            : "Desarrollo parcial — agentes fallidos: "
                                    + failedOutcomes.stream()
                                            .map(DevelopmentExecutionOutcome::agentId)
                                            .collect(Collectors.joining(", "));

            advanceMission(
                    missionId, MissionStatus.COMPLETED, 100, "Desarrollo completado", statusMessage
            );

            log.info("MISSION {} -> COMPLETED (desarrollo)", missionId);

        } catch (Exception ex) {

            log.error("MISSION {} - development execution failed", missionId, ex);

            safeFail(missionId, ex);
        }
    }

    private String buildDevelopmentPrompt(
            String agentId,
            String objective,
            String instruction,
            String discoveryContext) {

        return """
                Estás trabajando dentro de Forjai como el agente %s del Engineering Team.
                La misión ya fue aprobada por el inversionista humano — tu tarea es
                generar código real (archivos completos, no solo un plan) para
                arrancar el desarrollo.

                OBJETIVO ESPECÍFICO: %s

                REGLAS:
                - Genera archivos de código reales y completos, no pseudocódigo ni
                  placeholders sin terminar.
                - Cada "path" debe ser relativo (nunca empezar con "/", nunca
                  contener "..").
                - Responde ÚNICAMENTE con JSON válido, sin Markdown, sin texto
                  antes o después.

                MISIÓN:
                %s

                CONTEXTO DE DISCOVERY YA VALIDADO:
                %s
                """.formatted(agentId, objective, instruction, discoveryContext);
    }
```

- [ ] **Step 5: `MissionService.recordDecision` — `APPROVE` dispara desarrollo**

Reemplazar el bloque del `switch` y lo que sigue en `recordDecision`:

```java
        var newStatus = switch (command.decision()) {
            case APPROVE -> MissionStatus.EXECUTING;
            case REJECT -> MissionStatus.CANCELLED;
            case REQUEST_MORE_EVIDENCE -> null;
        };

        if (newStatus != null) {

            memory.updateMission(
                    missionId,
                    newStatus,
                    96,
                    "Decisión del inversionista",
                    command.reasoning()
            );

            events.publishMission(
                    "EMPRESA_MISSION_UPDATED",
                    missionId,
                    newStatus.name(),
                    96,
                    "Decisión del inversionista",
                    command.reasoning()
            );
        }

        if (command.decision() == InvestorDecision.APPROVE) {

            var missionInstruction = memory.instruction(missionId).orElse("");

            executor.executeDevelopmentAsync(missionId, missionInstruction)
                    .whenComplete((ignored, error) -> {
                        if (error != null) {
                            log.error("MISSION {} - development async future failed", missionId, error);
                        } else {
                            log.info("MISSION {} - development async orchestration finished", missionId);
                        }
                    });
        }
```

Agregar el import correspondiente:

```java
import com.aicompany.core.model.InvestorDecision;
```

- [ ] **Step 6: Actualizar `MissionServiceTest`**

Los casos existentes que verifican `APPROVE -> COMPLETED` ahora deben esperar `EXECUTING` (progreso `96`, no `100`). Localizar el/los tests con `verify(memory).updateMission(eq(missionId), eq(MissionStatus.COMPLETED), ...)` para el caso `APPROVE` y actualizarlos a `MissionStatus.EXECUTING`/`96`. Agregar también un `when(memory.instruction(anyString())).thenReturn(Optional.of("instrucción de prueba"));` en el setup de esos casos (o mockear expresamente en cada test que lo necesite) para que `recordDecision` no explote al buscar la instrucción — y `verify(executor).executeDevelopmentAsync(eq(missionId), anyString());` donde corresponda. Los casos de `REJECT`/`REQUEST_MORE_EVIDENCE` no cambian.

- [ ] **Step 7: Ejecutar los tests y confirmar que pasan**

Run: `cd app && mvn test -Dtest=MissionExecutorDevelopmentTest,MissionServiceTest`
Expected: PASS

- [ ] **Step 8: Ejecutar la suite completa**

Run: `cd app && mvn test`
Expected: PASS (175 previos + 4 nuevos de `MissionExecutorDevelopmentTest` = 179, más los ajustes de `MissionServiceTest` sin cambiar su conteo)

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/MissionExecutor.java \
        app/src/main/java/com/aicompany/core/service/MissionService.java \
        app/src/test/java/com/aicompany/core/service/MissionExecutorDevelopmentTest.java \
        app/src/test/java/com/aicompany/core/service/MissionServiceTest.java
git commit -m "APPROVE dispara desarrollo real: MissionExecutor.executeDevelopmentAsync + MissionService.recordDecision"
```

---

### Task 7: `ProductStatus.DEVELOPMENT` se vuelve alcanzable

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/ProductStatusService.java`
- Modify: `app/src/test/java/com/aicompany/core/service/ProductStatusServiceTest.java`

**Interfaces:**
- Consumes: `MissionMemoryService.tasks(String)` (ya existe).

- [ ] **Step 1: Escribir los tests nuevos (fallan: `isInDevelopment` sigue devolviendo `false`)**

Agregar a `ProductStatusServiceTest.java`:

```java
    @Test
    void movesToDevelopmentWhenAnyDevelopmentTaskIsCompleted() {
        when(missionMemory.tasks("MISSION-1")).thenReturn(List.of(
                task("engineering", "ARCHITECTURE_DEVELOPMENT", "COMPLETED")
        ));
        when(customerMemory.totalRevenueAndCost("MISSION-1")).thenReturn(new double[]{0.0, 0.0});
        when(customerMemory.transactionCount("MISSION-1")).thenReturn(0L);

        assertEquals(ProductStatus.DEVELOPMENT, service.resolve("MISSION-1"));
    }

    @Test
    void qualityRiskReviewStillNeverCountsAsDevelopmentOrQa() {
        // Regla dura del spec original de ProductStatus, sigue vigente:
        // QUALITY_RISK_REVIEW (discovery de qa) no implica ni QA ni
        // DEVELOPMENT.
        when(missionMemory.tasks("MISSION-1")).thenReturn(List.of(
                task("qa", "QUALITY_RISK_REVIEW", "COMPLETED")
        ));
        when(customerMemory.totalRevenueAndCost("MISSION-1")).thenReturn(new double[]{0.0, 0.0});
        when(customerMemory.transactionCount("MISSION-1")).thenReturn(0L);

        assertEquals(ProductStatus.DISCOVERY, service.resolve("MISSION-1"));
    }

    @Test
    void incompleteDevelopmentTaskDoesNotProduceDevelopment() {
        when(missionMemory.tasks("MISSION-1")).thenReturn(List.of(
                task("backend", "BACKEND_DEVELOPMENT", "FAILED")
        ));
        when(customerMemory.totalRevenueAndCost("MISSION-1")).thenReturn(new double[]{0.0, 0.0});
        when(customerMemory.transactionCount("MISSION-1")).thenReturn(0L);

        assertEquals(ProductStatus.DISCOVERY, service.resolve("MISSION-1"));
    }
```

(reusa el helper `task(String, String, String)` ya existente en este archivo de test).

- [ ] **Step 2: Ejecutar los tests para confirmar que fallan**

Run: `cd app && mvn test -Dtest=ProductStatusServiceTest`
Expected: FAIL (`movesToDevelopmentWhenAnyDevelopmentTaskIsCompleted` falla — `isInDevelopment` sigue fija en `false`)

- [ ] **Step 3: Implementar `isInDevelopment`**

Agregar el import:

```java
import java.util.Set;
```

Reemplazar el método:

```java
    private static final Set<String> DEVELOPMENT_ACTIONS = Set.of(
            "ARCHITECTURE_DEVELOPMENT", "BACKEND_DEVELOPMENT", "FRONTEND_DEVELOPMENT"
    );

    /**
     * Primera señal real de Proyecto B (subproyecto 1: generación de
     * código) — ver
     * docs/superpowers/specs/2026-09-21-development-generation-design.md.
     * Mismo patrón exacto que {@link #isDesigned}: una {@code AgentTask}
     * real completada, no una inferencia. {@code QUALITY_RISK_REVIEW}
     * (discovery de {@code qa}) sigue sin contar — no está en
     * {@code DEVELOPMENT_ACTIONS}.
     */
    private boolean isInDevelopment(String missionId) {

        return missionMemory.tasks(missionId).stream()
                .anyMatch(t -> DEVELOPMENT_ACTIONS.contains(t.action())
                        && "COMPLETED".equals(t.status()));
    }
```

(quitar el javadoc anterior de "Punto de enganche del Proyecto B" de este método específico — sigue siendo cierto para `isPublished`/`isQaValidated`, que no cambian en esta ronda).

- [ ] **Step 4: Ejecutar los tests y confirmar que pasan**

Run: `cd app && mvn test -Dtest=ProductStatusServiceTest`
Expected: PASS (7 previos + 3 nuevos = 10)

- [ ] **Step 5: Ejecutar la suite completa**

Run: `cd app && mvn test`
Expected: PASS (179 previos + 3 nuevos = 182)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/ProductStatusService.java \
        app/src/test/java/com/aicompany/core/service/ProductStatusServiceTest.java
git commit -m "ProductStatus.DEVELOPMENT deja de estar fijo en false: señal real desde AgentTask de desarrollo completada"
```

---

## Self-Review (completado antes de entregar el plan)

- **Cobertura del spec**: decisión 1 (APPROVE → EXECUTING) → Task 6; decisión 2 (3 tareas paralelas, contexto de discovery) → Task 6; decisión 3 (`DevelopmentResult` separado de `AgentResult`) → Task 1; decisión 4 (`CeoService`/`DevelopmentRuntime`, `AgentRuntime` intacto) → Tasks 3-4; decisión 5 (gate de rutas, sin reintento) → Task 2; decisión 6 (workspace + commit único secuencial) → Task 5; decisión 7 (persistencia vía `AgentTask`, sin nodo nuevo) → Task 6 (usa `memory.createTask`/`updateTask` ya existentes, sin nodo `CodeArtifact`); decisión 8 (`ProductStatus.DEVELOPMENT` real) → Task 7; decisión 9 (sin gate humano nuevo) → respetado, ningún task agrega un endpoint/decisión nueva más allá del `APPROVE` ya existente. Testing (unit + verificación en vivo) → cada task trae su test; la verificación en vivo queda para después de que el plan esté implementado y revisado, documentada en `docs/HISTORY.md`, no como una task de este plan.
- **Placeholders**: ninguno — todo paso trae el código completo.
- **Consistencia de tipos**: `DevelopmentResult`/`DevelopmentResultSchema`/`DevelopmentExecutionOutcome` (Task 1) usados con los mismos nombres de campo en Tasks 3-7; `DevelopmentRuntime.execute(taskId, missionId, agentId, action, prompt) -> CompletableFuture<DevelopmentResult>` consistente entre Task 4 (producción) y Task 6 (consumo); `DevelopmentWorkspaceService.writeFiles(missionId, subdirectory, result)`/`commitWorkspace(missionId, message)` consistente entre Task 5 y Task 6; el 14º/13º parámetro del constructor de `MissionExecutor` (`developmentRuntime`, `developmentWorkspace`) aparece en el mismo orden en Task 6's producción y en su propio test.
- **Riesgo señalado explícitamente**: la nota en Task 4 Step 4 sobre el `catch` final de `DevelopmentRuntime.executeInternal` — el envoltorio `new RuntimeException(ex)` es defensivo pero podría duplicar un wrapping innecesario si `ex` ya es una `RuntimeException` (que siempre es el caso hoy); el propio paso deja explícita la alternativa correcta (`throw ex;` si ya es `RuntimeException`, igual que `AgentRuntime`) para que quien implemente no la pase por alto.
