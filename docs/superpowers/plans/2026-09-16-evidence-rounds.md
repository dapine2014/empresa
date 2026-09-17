# Motor de Aprobaciones Humanas — Evidence Rounds Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Hacer que `REQUEST_MORE_EVIDENCE` dispare una re-ejecución real de los 5 agentes de la misión (con el pedido del inversionista repartido por el CEO), en vez de ser un no-op como hoy, hasta un límite configurable de vueltas.

**Architecture:** `MissionService.recordDecision` incrementa un contador `Mission.evidenceRound` (atómico en Neo4j, con el límite `company.max-evidence-rounds` como guarda) y dispara `MissionExecutor.reexecuteAsync`, que reentra la máquina de estados existente (`DELEGATING → ... → AWAITING_INVESTOR`) con `taskId` sufijados por ronda (`-R{n}`) para preservar el historial completo de cada vuelta en Neo4j. Antes de re-ejecutar, `CeoService.routeInvestorFeedback` (una llamada nueva a Ollama con `format`, sin `tools`) reparte el reasoning del inversionista entre los 5 agentes; el texto resultante se antepone a la instrucción de cada agente.

**Tech Stack:** Spring Boot 4.1.1 / Java 21, `neo4j-java-driver` (Cypher a mano), Ollama vía `RestClient` (`tools.jackson.*`, no `com.fasterxml.jackson.*`), JUnit 5 + Mockito.

**Spec:** `docs/superpowers/specs/2026-09-16-evidence-rounds-design.md`

## Global Constraints

- Trabajar siempre dentro de `app/` para comandos Maven.
- Java 21, Spring Boot 4.1.1. Jackson es `tools.jackson.*` en el código de la app — nunca `com.fasterxml.jackson.*`.
- No reintroducir `@Async` en ninguna ruta de orquestación — `MissionExecutor` sigue usando executors explícitos inyectados con `@Qualifier` + `CompletableFuture.supplyAsync/runAsync`.
- Nunca combinar `format` y `tools` en la misma llamada a Ollama (`CeoService.rejectFormatCombinedWithTools` ya lo garantiza en runtime) — `routeInvestorFeedback` usa `format` solo, sin `tools`.
- Todo `eventType` de Kafka debe empezar con `EMPRESA_` (`CompanyEventPublisher.publish` ya lo garantiza en runtime).
- `MissionMemoryService` (y el resto de `*MemoryService`) no llevan test directo — integración Neo4j, mismo criterio ya establecido en el proyecto (se valida a través de los tests de `MissionService`/`MissionExecutor` que los mockean).
- Tests: `mvn test -Dtest=Clase#metodo` desde `app/`; `mvn test` para toda la suite.
- Ningún controller de este proyecto maneja HTTP finamente — una `IllegalStateException` sin capturar cae al handler default de Spring (500). No agregar manejo de errores nuevo en `MissionController`.

---

### Task 1: Configuración — `company.max-evidence-rounds`

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/config/AppProperties.java`
- Modify: `app/src/main/java/com/aicompany/core/config/CoreConfig.java`
- Modify: `app/src/main/resources/application.yml`
- Modify: `app/src/test/java/com/aicompany/core/service/MissionExecutorTest.java` (constructor de `AppProperties` roto por el nuevo campo)
- Modify: `app/src/test/java/com/aicompany/core/service/CustomerServiceTest.java` (mismo motivo)

**Interfaces:**
- Produces: `AppProperties.maxEvidenceRounds() -> int`, usado por la Task 5 (`MissionService`).

No hay lógica nueva que testear en este paso (es solo un campo de configuración ya cableado por Spring vía `@Value`); la validación es que la suite completa siga compilando y pasando después del cambio.

- [ ] **Step 1: Agregar el campo al record `AppProperties`**

```java
package com.aicompany.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "company")
public record AppProperties(
        String name,
        double seedCapitalUsd,
        int challengeDays,
        int maxEvidenceRounds) {}
```

- [ ] **Step 2: Agregar el parámetro al `@Bean` en `CoreConfig`**

```java
    @Bean
    AppProperties appProperties(
            @Value("${company.name}") String name,
            @Value("${company.seed-capital-usd}") double seedCapitalUsd,
            @Value("${company.challenge-days}") int challengeDays,
            @Value("${company.max-evidence-rounds}") int maxEvidenceRounds) {
        return new AppProperties(name, seedCapitalUsd, challengeDays, maxEvidenceRounds);
    }
```

- [ ] **Step 3: Agregar el default a `application.yml`**

En la sección `company:` existente:

```yaml
company:
  name: Forjai
  events:
    topic: EMPRESA_EVENTS
  seed-capital-usd: 50
  challenge-days: 60
  max-evidence-rounds: 2
```

- [ ] **Step 4: Arreglar las instanciaciones directas de `AppProperties` en tests**

En `MissionExecutorTest.java`:

```java
    private final AppProperties appProperties = new AppProperties("Forjai", 50.0, 60, 2);
```

En `CustomerServiceTest.java`, la misma línea (buscar `new AppProperties("Forjai", 50.0, 60)` y agregar `, 2` al final).

- [ ] **Step 5: Confirmar que compila y la suite existente sigue pasando**

Run: `cd app && mvn test -Dtest=MissionExecutorTest,CustomerServiceTest`
Expected: PASS (mismo comportamiento que antes, solo compila con el campo nuevo).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aicompany/core/config/AppProperties.java \
        app/src/main/java/com/aicompany/core/config/CoreConfig.java \
        app/src/main/resources/application.yml \
        app/src/test/java/com/aicompany/core/service/MissionExecutorTest.java \
        app/src/test/java/com/aicompany/core/service/CustomerServiceTest.java
git commit -m "Agregar company.max-evidence-rounds a AppProperties"
```

---

### Task 2: `MissionMemoryService` — ronda de evidencia, instrucción e historial por ronda

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/MissionMemoryService.java`

**Interfaces:**
- Consumes: nada nuevo (mismo `Driver` ya inyectado).
- Produces (usados por Task 4 y Task 5):
  - `MissionMemoryService.incrementEvidenceRound(String missionId, int maxRounds) -> Optional<Integer>` — vacío si el límite ya se alcanzó, si no el nuevo número de ronda.
  - `MissionMemoryService.instructionOf(String missionId) -> Optional<String>`
  - `MissionMemoryService.tasksForRound(String missionId, int round) -> List<AgentTask>`

Sin test directo (Cypher puro, mismo criterio ya establecido para el resto de `*MemoryService` — se ejercitan indirectamente por los tests de `MissionService`/`MissionExecutor`, que mockean esta clase).

- [ ] **Step 1: Inicializar `evidenceRound` en `ensureMission`**

Reemplazar el método completo:

```java
    public void ensureMission(String missionId, String instruction, String environment) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MERGE (m:Mission {id:$id}) SET m.name=$name, m.instruction=$instruction, m.environment=$environment, m.status='CREATED', m.progress=0, m.currentStep='Creada', m.message='Misión recibida', m.evidenceRound=0, m.updatedAt=$updatedAt",
                        Map.of("id", missionId,
                                "name", missionId.equals("MISSION-001") ? "MISSION-001 — Descubrimiento del primer negocio" : missionId,
                                "instruction", instruction,
                                "environment", environment,
                                "updatedAt", Instant.now().toString()));
                tx.run("MATCH (m:Mission {id:$id}), (c:Company {id:'AI-COMPANY'}) MERGE (c)-[:HAS_MISSION]->(m)", Map.of("id", missionId));
                tx.run("MATCH (m:Mission {id:$id}), (a:Agent {id:'ceo'}) MERGE (m)-[:LED_BY]->(a)", Map.of("id", missionId));
                return null;
            });
        }
    }
```

- [ ] **Step 2: Agregar `incrementEvidenceRound`**

Insertar después de `recordDecision`:

```java
    /**
     * Compara y aumenta {@code Mission.evidenceRound} en una sola
     * operación atómica — evita una condición de carrera entre leer el
     * valor actual y escribir el incrementado si dos decisiones llegaran
     * a superponerse. Vacío si la misión ya alcanzó {@code maxRounds}
     * (el llamador, {@code MissionService.recordDecision}, lo trata como
     * "límite agotado" y no re-ejecuta nada).
     */
    public Optional<Integer> incrementEvidenceRound(String missionId, int maxRounds) {
        try (var session = driver.session()) {
            return session.executeWrite(tx -> {
                var records = tx.run(
                        "MATCH (m:Mission {id:$id}) " +
                                "WHERE coalesce(m.evidenceRound, 0) < $max " +
                                "SET m.evidenceRound = coalesce(m.evidenceRound, 0) + 1 " +
                                "RETURN m.evidenceRound AS evidenceRound",
                        Map.of("id", missionId, "max", maxRounds)
                ).list();

                return records.stream()
                        .findFirst()
                        .map(r -> r.get("evidenceRound").asInt());
            });
        }
    }

    /**
     * Instrucción original de la misión, guardada por {@link #ensureMission}
     * pero no expuesta por {@link MissionResponse} (evitaría sincronizar
     * `api/types.ts` en el frontend sin que ninguna pantalla la necesite).
     * Uso interno de {@code MissionService.recordDecision} para re-ejecutar
     * una vuelta de evidencia con la misma instrucción original.
     */
    public Optional<String> instructionOf(String missionId) {
        try (var session = driver.session()) {
            var records = session.run(
                    "MATCH (m:Mission {id:$id}) RETURN m.instruction AS instruction",
                    Map.of("id", missionId)
            ).list();

            return records.stream()
                    .findFirst()
                    .map(r -> r.get("instruction").asString());
        }
    }

    /**
     * Tareas de una ronda de evidencia puntual — {@code taskId} termina en
     * {@code "-R" + round} (ver {@code MissionExecutor}, que arma ese id).
     * Usado por {@code MissionExecutor.reexecuteAsync} para releer los
     * resultados de la ronda anterior y pasárselos al CEO al repartir el
     * feedback del inversionista entre los agentes.
     */
    public List<AgentTask> tasksForRound(String missionId, int round) {
        try (var session = driver.session()) {
            return session.run(
                            "MATCH (t:AgentTask {missionId:$missionId}) WHERE t.id ENDS WITH $suffix " +
                                    "RETURN t.id AS id, t.agentId AS agentId, t.action AS action, " +
                                    "t.status AS status, t.result AS result, t.updatedAt AS updatedAt " +
                                    "ORDER BY t.id",
                            Map.of("missionId", missionId, "suffix", "-R" + round))
                    .list(r -> new AgentTask(
                            r.get("id").asString(), missionId,
                            r.get("agentId").asString(), r.get("action").asString(), r.get("status").asString(),
                            r.get("result").asString(""), Instant.parse(r.get("updatedAt").asString())));
        }
    }
```

- [ ] **Step 3: Compilar**

Run: `cd app && mvn -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/MissionMemoryService.java
git commit -m "Agregar evidenceRound, instructionOf y tasksForRound a MissionMemoryService"
```

---

### Task 3: `CeoService.routeInvestorFeedback` — reparto del pedido del inversionista

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/CeoService.java`
- Test: `app/src/test/java/com/aicompany/core/service/CeoServiceInvestorFeedbackTest.java` (nuevo)

**Interfaces:**
- Consumes: `callModel(String operation, String actor, String model, List<Map<String,Object>> messages, Object format, List<Map<String,Object>> tools, Boolean think) -> ModelMessage` (privado, ya existente); `normalizeJsonResponse(String) -> String` (privado, ya existente); `jsonMapper` (campo ya existente).
- Produces (usado por Task 4): `CeoService.routeInvestorFeedback(String instruction, String priorAgentResultsJson, String investorReasoning) -> Map<String,String>` (siempre con las 5 claves `sales/product/finance/engineering/qa`, nunca lanza — si Ollama devuelve algo no parseable, retorna `Map.of()` y se loguea el error). `CeoService.parseInvestorFeedback(String rawResponse) -> Map<String,String>` (package-private, sin red, para testear el parseo — mismo patrón que `SerperSearchAdapter.parseResults`).

- [ ] **Step 1: Escribir el test que falla — `CeoServiceInvestorFeedbackTest`**

```java
package com.aicompany.core.service;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.evidence.EvidenceAcquisitionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * Parseo de la respuesta de `routeInvestorFeedback` — extraído a un método
 * package-private (`parseInvestorFeedback`) para poder testearlo sin
 * mockear la cadena fluida de `RestClient`, mismo criterio que
 * `SerperSearchAdapter.parseResults` ("package-private para testear sin
 * red", ver CLAUDE.md).
 */
class CeoServiceInvestorFeedbackTest {

    private final CeoService ceoService = new CeoService(
            mock(RestClient.class),
            "qwen2.5-coder:14b",
            "qwen3:8b",
            JsonMapper.builder().build(),
            mock(EvidenceAcquisitionService.class),
            mock(CompanyEventPublisher.class),
            new SimpleMeterRegistry()
    );

    @Test
    void parsesFeedbackForAllFiveAgents() {

        var json = """
                {"sales":"validar precios reales de competidores","product":"","finance":"","engineering":"","qa":""}
                """;

        var feedback = ceoService.parseInvestorFeedback(json);

        assertEquals(5, feedback.size());
        assertEquals("validar precios reales de competidores", feedback.get("sales"));
        assertEquals("", feedback.get("product"));
        assertEquals("", feedback.get("finance"));
        assertEquals("", feedback.get("engineering"));
        assertEquals("", feedback.get("qa"));
    }

    @Test
    void stripsMarkdownFencesBeforeParsing() {

        var json = """
                ```json
                {"sales":"","product":"revisar margen","finance":"","engineering":"","qa":""}
                ```
                """;

        var feedback = ceoService.parseInvestorFeedback(json);

        assertEquals("revisar margen", feedback.get("product"));
    }

    @Test
    void throwsOnMalformedJson() {

        assertThrows(Exception.class, () -> ceoService.parseInvestorFeedback("no es json"));
    }
}
```

- [ ] **Step 2: Ejecutar el test y confirmar que falla**

Run: `cd app && mvn test -Dtest=CeoServiceInvestorFeedbackTest`
Expected: FAIL — `parseInvestorFeedback` no existe todavía (error de compilación).

- [ ] **Step 3: Agregar el schema, el record y los dos métodos a `CeoService`**

Insertar después del método `executeMission` (antes de `systemPrompt()`):

```java
    private static final Map<String, Object> INVESTOR_FEEDBACK_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "sales", Map.of("type", "string"),
                    "product", Map.of("type", "string"),
                    "finance", Map.of("type", "string"),
                    "engineering", Map.of("type", "string"),
                    "qa", Map.of("type", "string")
            ),
            "required", List.of("sales", "product", "finance", "engineering", "qa")
    );

    private record InvestorFeedbackByAgent(
            String sales,
            String product,
            String finance,
            String engineering,
            String qa) {
    }

    /**
     * Reparte el `reasoning` de una decisión `REQUEST_MORE_EVIDENCE` entre
     * los 5 agentes antes de una nueva ronda — el CEO decide qué parte del
     * pedido le corresponde a cada rol (desviación deliberada de "nunca el
     * modelo decidiendo el flujo": acá decide contenido de un prompt, no
     * una transición de estado; ver `docs/superpowers/specs/2026-09-16-
     * evidence-rounds-design.md`). Nunca lanza: si Ollama no devuelve algo
     * parseable, se loguea y se re-ejecuta la ronda sin bloque adicional
     * para ningún agente (mejor una ronda sin ese contexto extra que
     * bloquear la re-ejecución entera por esta llamada auxiliar).
     */
    public Map<String, String> routeInvestorFeedback(
            String instruction,
            String priorAgentResultsJson,
            String investorReasoning) {

        var prompt = """
                Actúa como CEO de Forjai.
                El inversionista humano pidió más evidencia sobre esta
                misión antes de decidir. Reparte su pedido entre los
                agentes que corresponda -- cada agente solo debe recibir
                la parte que le aplica a su rol. Si un agente no necesita
                nada, devolvé un string vacío para él. No inventes pedidos
                que el inversionista no hizo.

                INSTRUCCIÓN ORIGINAL DE LA MISIÓN:
                %s

                RESULTADOS DE AGENTES DE LA VUELTA ANTERIOR:
                %s

                PEDIDO DEL INVERSIONISTA:
                %s
                """.formatted(instruction, priorAgentResultsJson, investorReasoning);

        var messages = List.<Map<String, Object>>of(
                Map.of("role", "system", "content", systemPrompt()),
                Map.of("role", "user", "content", prompt)
        );

        var response = callModel(
                "INVESTOR_FEEDBACK_ROUTING", "ceo", ceoModel, messages,
                INVESTOR_FEEDBACK_SCHEMA, null, false
        ).content();

        try {

            return parseInvestorFeedback(response);

        } catch (Exception ex) {

            log.error(
                    "No se pudo repartir el feedback del inversionista entre "
                            + "los agentes; se re-ejecuta la ronda sin bloque "
                            + "adicional para ningún agente",
                    ex
            );

            return Map.of();
        }
    }

    Map<String, String> parseInvestorFeedback(String rawResponse) {

        var normalized = normalizeJsonResponse(rawResponse);

        var parsed = jsonMapper.readValue(normalized, InvestorFeedbackByAgent.class);

        var feedback = new java.util.LinkedHashMap<String, String>();
        feedback.put("sales", orEmpty(parsed.sales()));
        feedback.put("product", orEmpty(parsed.product()));
        feedback.put("finance", orEmpty(parsed.finance()));
        feedback.put("engineering", orEmpty(parsed.engineering()));
        feedback.put("qa", orEmpty(parsed.qa()));

        return feedback;
    }

    private String orEmpty(String value) {
        return value == null ? "" : value;
    }
```

- [ ] **Step 4: Ejecutar el test y confirmar que pasa**

Run: `cd app && mvn test -Dtest=CeoServiceInvestorFeedbackTest`
Expected: PASS (3/3)

- [ ] **Step 5: Correr toda la suite de `CeoService`**

Run: `cd app && mvn test -Dtest=CeoServiceInvestorFeedbackTest,CeoServiceToolFormatGuardTest,CeoServiceChatHistoryTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/CeoService.java \
        app/src/test/java/com/aicompany/core/service/CeoServiceInvestorFeedbackTest.java
git commit -m "Agregar CeoService.routeInvestorFeedback para repartir el pedido del inversionista"
```

---

### Task 4: `MissionExecutor` — rondas de evidencia con historial y feedback del CEO

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/MissionExecutor.java`
- Modify: `app/src/test/java/com/aicompany/core/service/MissionExecutorTest.java`
- Modify: `docs/EVENTS.md`

**Interfaces:**
- Consumes: `MissionMemoryService.tasksForRound(String, int) -> List<AgentTask>`, `CeoService.routeInvestorFeedback(String, String, String) -> Map<String,String>` (Task 2 y 3).
- Produces (usado por Task 5): `MissionExecutor.reexecuteAsync(String missionId, String instruction, int evidenceRound, String investorReasoning) -> CompletableFuture<Void>`.

- [ ] **Step 1: Escribir los tests que fallan en `MissionExecutorTest`**

Agregar al final de la clase (antes de los métodos privados `stubAgent`/`stubFailingAgent`):

```java
    @Test
    void reexecuteAsyncCreatesTasksWithRoundSuffixAndReachesAwaitingInvestorAgain() throws Exception {
        stubAgent("sales");
        stubAgent("product");
        stubAgent("finance");
        stubAgent("engineering");
        stubAgent("qa");

        when(memory.tasksForRound(eq("MISSION-1"), eq(0))).thenReturn(List.of());
        when(ceoService.routeInvestorFeedback(anyString(), anyString(), anyString()))
                .thenReturn(Map.of(
                        "sales", "", "product", "", "finance", "", "engineering", "", "qa", ""));
        when(contradictionDetector.detect(any(), anyDouble())).thenReturn(List.of());
        when(ceoService.executeMission(anyString(), anyString())).thenReturn("consolidado ronda 1");

        var taskIdCaptor = ArgumentCaptor.forClass(String.class);

        executor.reexecuteAsync("MISSION-1", "instrucción", 1, "falta validar precios reales").get();

        verify(runtime, times(5)).execute(
                taskIdCaptor.capture(), eq("MISSION-1"), anyString(), anyString(), anyString());

        assertTrue(taskIdCaptor.getAllValues().stream()
                .allMatch(taskId -> taskId.endsWith("-R1")));

        verify(memory).updateMission(
                eq("MISSION-1"), eq(MissionStatus.AWAITING_INVESTOR), anyInt(), anyString(), anyString());

        verify(events).publish(
                eq("EMPRESA_MISSION_EVIDENCE_ROUND_STARTED"), eq("MISSION-1"), any(), eq("human"), any());
    }

    @Test
    void reexecuteAsyncAppendsInvestorFeedbackOnlyForAgentsThatReceivedIt() throws Exception {
        stubAgent("sales");
        stubAgent("product");
        stubAgent("finance");
        stubAgent("engineering");
        stubAgent("qa");

        when(memory.tasksForRound(eq("MISSION-1"), eq(1))).thenReturn(List.of());
        when(ceoService.routeInvestorFeedback(anyString(), anyString(), eq("falta validar precios reales")))
                .thenReturn(Map.of(
                        "sales", "Confirmá precios reales de al menos 3 competidores.",
                        "product", "", "finance", "", "engineering", "", "qa", ""));
        when(contradictionDetector.detect(any(), anyDouble())).thenReturn(List.of());
        when(ceoService.executeMission(anyString(), anyString())).thenReturn("consolidado ronda 2");

        var salesInstructionCaptor = ArgumentCaptor.forClass(String.class);
        var productInstructionCaptor = ArgumentCaptor.forClass(String.class);

        executor.reexecuteAsync("MISSION-1", "instrucción", 2, "falta validar precios reales").get();

        verify(runtime).execute(
                anyString(), eq("MISSION-1"), eq("sales"), anyString(), salesInstructionCaptor.capture());
        verify(runtime).execute(
                anyString(), eq("MISSION-1"), eq("product"), anyString(), productInstructionCaptor.capture());

        assertTrue(salesInstructionCaptor.getValue().contains("SOLICITUD DEL INVERSIONISTA"));
        assertTrue(salesInstructionCaptor.getValue().contains("Confirmá precios reales"));
        assertFalse(productInstructionCaptor.getValue().contains("SOLICITUD DEL INVERSIONISTA"));
    }

    @Test
    void initialExecutionUsesRoundZeroTaskIdsAndEmptyFeedback() throws Exception {
        stubAgent("sales");
        stubAgent("product");
        stubAgent("finance");
        stubAgent("engineering");
        stubAgent("qa");

        when(contradictionDetector.detect(any(), anyDouble())).thenReturn(List.of());
        when(ceoService.executeMission(anyString(), anyString())).thenReturn("consolidado");

        var taskIdCaptor = ArgumentCaptor.forClass(String.class);

        executor.executeAsync("MISSION-1", "instrucción").get();

        verify(runtime, times(5)).execute(
                taskIdCaptor.capture(), eq("MISSION-1"), anyString(), anyString(), anyString());

        assertTrue(taskIdCaptor.getAllValues().stream()
                .allMatch(taskId -> taskId.endsWith("-R0")));

        verify(ceoService, never()).routeInvestorFeedback(anyString(), anyString(), anyString());
    }
```

Agregar el import que falte al principio del archivo (los tres tests nuevos usan `Map.of(...)` para stubear `routeInvestorFeedback`, y `java.util.Map` todavía no está importado en este archivo):

```java
import java.util.Map;
```

- [ ] **Step 2: Ejecutar los tests y confirmar que fallan**

Run: `cd app && mvn test -Dtest=MissionExecutorTest`
Expected: FAIL — `reexecuteAsync` y `memory.tasksForRound`/`ceoService.routeInvestorFeedback` no existen todavía (error de compilación).

- [ ] **Step 3: Agregar el import de `AgentTask` a `MissionExecutor`**

Junto a los imports existentes:

```java
import com.aicompany.core.model.AgentTask;
```

- [ ] **Step 4: Reescribir `executeAsync` y agregar `reexecuteAsync`**

Reemplazar el método `executeAsync` completo y agregar `reexecuteAsync` justo después:

```java
    public CompletableFuture<Void> executeAsync(
            String missionId,
            String instruction) {

        log.info(
                "MISSION {} - submitting orchestration",
                missionId
        );

        events.publishMission(
                "EMPRESA_MISSION_STARTED",
                missionId,
                "PLANNING",
                0,
                "Iniciando",
                "Orquestación enviada al runtime."
        );

        try {

            return CompletableFuture.runAsync(
                    () -> executeInternal(
                            missionId,
                            instruction,
                            0,
                            Map.of()
                    ),
                    orchestratorExecutor
            );

        } catch (Exception ex) {

            log.error(
                    "MISSION {} - could not submit orchestration",
                    missionId,
                    ex
            );

            safeFail(
                    missionId,
                    ex
            );

            return CompletableFuture.failedFuture(ex);
        }
    }

    /**
     * Dispara una vuelta adicional de evidencia tras un
     * {@code REQUEST_MORE_EVIDENCE} del inversionista
     * ({@code MissionService.recordDecision}, que ya validó el límite de
     * vueltas antes de llamar acá). Reentra el mismo camino que
     * {@code executeInternal} usa siempre — no hay un {@code MissionStatus}
     * nuevo — pero con {@code taskId} sufijados por ronda para preservar el
     * historial completo (ver spec, "Historial de tareas").
     */
    public CompletableFuture<Void> reexecuteAsync(
            String missionId,
            String instruction,
            int evidenceRound,
            String investorReasoning) {

        log.info(
                "MISSION {} - submitting evidence round {} orchestration",
                missionId,
                evidenceRound
        );

        events.publish(
                "EMPRESA_MISSION_EVIDENCE_ROUND_STARTED",
                missionId,
                null,
                "human",
                Map.of(
                        "evidenceRound", evidenceRound,
                        "reasoning", investorReasoning
                )
        );

        try {

            return CompletableFuture.runAsync(
                    () -> {

                        var feedback = buildInvestorFeedback(
                                missionId,
                                instruction,
                                evidenceRound,
                                investorReasoning
                        );

                        executeInternal(
                                missionId,
                                instruction,
                                evidenceRound,
                                feedback
                        );
                    },
                    orchestratorExecutor
            );

        } catch (Exception ex) {

            log.error(
                    "MISSION {} - could not submit evidence round {} orchestration",
                    missionId,
                    evidenceRound,
                    ex
            );

            safeFail(
                    missionId,
                    ex
            );

            return CompletableFuture.failedFuture(ex);
        }
    }

    private Map<String, String> buildInvestorFeedback(
            String missionId,
            String instruction,
            int evidenceRound,
            String investorReasoning) {

        var priorTasks =
                memory.tasksForRound(missionId, evidenceRound - 1);

        var priorResults =
                priorTasks.stream()
                        .filter(t -> "COMPLETED".equals(t.status()))
                        .map(this::parsePersistedResult)
                        .filter(java.util.Objects::nonNull)
                        .toList();

        var priorResultsJson =
                serializeAgentResults(priorResults);

        return ceoService.routeInvestorFeedback(
                instruction,
                priorResultsJson,
                investorReasoning
        );
    }

    private AgentResult parsePersistedResult(AgentTask task) {

        try {

            return jsonMapper.readValue(
                    task.result(),
                    AgentResult.class
            );

        } catch (Exception ex) {

            log.warn(
                    "MISSION {} - could not parse persisted result for task {}, "
                            + "skipping it when routing investor feedback",
                    task.missionId(),
                    task.taskId(),
                    ex
            );

            return null;
        }
    }

    private String buildAgentInstruction(
            String instruction,
            String objective,
            int evidenceRound,
            String investorFeedback) {

        var agentInstruction =
                instruction
                        + "\nObjetivo específico: "
                        + objective;

        if (investorFeedback != null && !investorFeedback.isBlank()) {

            agentInstruction += """


                    SOLICITUD DEL INVERSIONISTA (ronda %d de evidencia adicional)

                    %s
                    """.formatted(evidenceRound, investorFeedback);
        }

        return agentInstruction;
    }
```

- [ ] **Step 5: Cambiar la firma de `executeInternal` y usar `buildAgentInstruction`/`taskId` con sufijo de ronda**

Reemplazar la firma y el cuerpo del primer bloque (creación de las 5 tareas iniciales) — desde `private void executeInternal(` hasta el cierre del `for (var definition : definitions) { ... }`:

```java
    private void executeInternal(
            String missionId,
            String instruction,
            int evidenceRound,
            Map<String, String> investorFeedbackByAgent) {

        log.info(
                "MISSION {} - async execution started (evidenceRound={})",
                missionId,
                evidenceRound
        );

        try {

            advanceMission(
                    missionId,
                    MissionStatus.PLANNING,
                    5,
                    "Planificación",
                    "CEO está definiendo el trabajo de la misión."
            );

            log.info(
                    "MISSION {} -> PLANNING",
                    missionId
            );

            advanceMission(
                    missionId,
                    MissionStatus.DELEGATING,
                    10,
                    "Delegación",
                    "Asignando tareas paralelas a Sales, Product, Finance, Engineering y QA."
            );

            log.info(
                    "MISSION {} -> DELEGATING",
                    missionId
            );

            var definitions = List.of(

                    new AgentDefinition(
                            "sales",
                            "MARKET_DISCOVERY",
                            "Identificar perfiles de clientes y señales de demanda que deban validarse."
                    ),

                    new AgentDefinition(
                            "product",
                            "OFFER_DESIGN",
                            "Definir una oferta mínima vendible alineada con las restricciones de capital."
                    ),

                    new AgentDefinition(
                            "finance",
                            "UNIT_ECONOMICS",
                            "Estimar costos, precio, margen y condiciones necesarias para superar US$50 de utilidad neta."
                    ),

                    new AgentDefinition(
                            "engineering",
                            "DELIVERY_FEASIBILITY",
                            "Evaluar la capacidad de entregar la oferta con los recursos tecnológicos disponibles."
                    ),

                    new AgentDefinition(
                            "qa",
                            "QUALITY_RISK_REVIEW",
                            "Identificar, de forma independiente a los demás agentes (esta tarea corre en paralelo, no tiene acceso a sus resultados), riesgos, huecos de evidencia y supuestos no verificados en la oportunidad de negocio descrita en la misión, antes de comprometer capital."
                    )
            );

            var definitionsByAgent =
                    definitions.stream()
                            .collect(Collectors.toMap(
                                    AgentDefinition::agentId,
                                    definition -> definition
                            ));

            var futuresByAgent =
                    new LinkedHashMap<String, CompletableFuture<AgentResult>>();

            for (var definition : definitions) {

                var agentId = definition.agentId();
                var action = definition.action();
                var objective = definition.objective();

                var taskId =
                        missionId
                                + "-"
                                + agentId.toUpperCase()
                                + "-R"
                                + evidenceRound;

                log.info(
                        "MISSION {} - creating task {} for agent {}",
                        missionId,
                        taskId,
                        agentId
                );

                memory.createTask(
                        taskId,
                        missionId,
                        agentId,
                        action
                );

                events.publishTask(
                        "EMPRESA_TASK_CREATED",
                        taskId,
                        missionId,
                        agentId,
                        "PENDING",
                        "Tarea creada."
                );

                var future =
                        runtime.execute(
                                taskId,
                                missionId,
                                agentId,
                                action,
                                buildAgentInstruction(
                                        instruction,
                                        objective,
                                        evidenceRound,
                                        investorFeedbackByAgent.getOrDefault(agentId, "")
                                )
                        );

                futuresByAgent.put(agentId, future);
            }
```

El resto del método (desde `advanceMission(..., WAITING_AGENT_RESULTS, ...)` hasta el final del `try`) no cambia, **excepto** la línea que llama a `replanFailedAgents`, que pasa a incluir `evidenceRound` e `investorFeedbackByAgent`:

```java
            outcomes = replanFailedAgents(
                    missionId,
                    instruction,
                    evidenceRound,
                    investorFeedbackByAgent,
                    definitionsByAgent,
                    outcomes
            );
```

- [ ] **Step 6: Cambiar la firma de `replanFailedAgents`**

Reemplazar el método completo:

```java
    private List<AgentExecutionOutcome> replanFailedAgents(
            String missionId,
            String instruction,
            int evidenceRound,
            Map<String, String> investorFeedbackByAgent,
            Map<String, AgentDefinition> definitionsByAgent,
            List<AgentExecutionOutcome> outcomes) {

        var settled = new ArrayList<AgentExecutionOutcome>();

        for (var outcome : outcomes) {

            var current = outcome;
            var replanAttempt = 0;

            while (!current.completed() && replanAttempt < MAX_AGENT_REPLANS) {

                replanAttempt++;

                var agentId = current.agentId();
                var definition = definitionsByAgent.get(agentId);
                var taskId = missionId + "-" + agentId.toUpperCase() + "-R" + evidenceRound;

                log.warn(
                        "MISSION {} - replanning agent {} (attempt {} of {}) after: {}",
                        missionId,
                        agentId,
                        replanAttempt,
                        MAX_AGENT_REPLANS,
                        current.error()
                );

                events.publish(
                        "EMPRESA_MISSION_REPLANNED",
                        missionId,
                        taskId,
                        agentId,
                        Map.of(
                                "replanAttempt", replanAttempt,
                                "previousError",
                                current.error() == null ? "" : current.error()
                        )
                );

                memory.createTask(
                        taskId,
                        missionId,
                        agentId,
                        definition.action()
                );

                events.publishTask(
                        "EMPRESA_TASK_CREATED",
                        taskId,
                        missionId,
                        agentId,
                        "PENDING",
                        "Tarea replanificada a nivel de misión (intento "
                                + replanAttempt
                                + " de "
                                + MAX_AGENT_REPLANS
                                + ")."
                );

                var future =
                        runtime.execute(
                                taskId,
                                missionId,
                                agentId,
                                definition.action(),
                                buildAgentInstruction(
                                        instruction,
                                        definition.objective(),
                                        evidenceRound,
                                        investorFeedbackByAgent.getOrDefault(agentId, "")
                                )
                        );

                try {

                    var result = future.join();

                    current = AgentExecutionOutcome.success(agentId, result);

                    log.info(
                            "MISSION {} - agent {} recovered after replan attempt {}",
                            missionId,
                            agentId,
                            replanAttempt
                    );

                } catch (Exception ex) {

                    current = AgentExecutionOutcome.failure(
                            agentId,
                            safeMessage(ex, "El agente no completó su tarea.")
                    );
                }
            }

            settled.add(current);
        }

        return settled;
    }
```

- [ ] **Step 7: Ejecutar `MissionExecutorTest` completo y confirmar que pasa**

Run: `cd app && mvn test -Dtest=MissionExecutorTest`
Expected: PASS (todos los tests, incluidos los 3 nuevos y los ya existentes sin modificar).

- [ ] **Step 8: Documentar el evento nuevo en `docs/EVENTS.md`**

Agregar al final del archivo:

```markdown

EMPRESA_MISSION_EVIDENCE_ROUND_STARTED (`MissionExecutor.reexecuteAsync`): se publica al arrancar cada vuelta adicional de agentes disparada por `REQUEST_MORE_EVIDENCE`, antes de repartir el feedback del inversionista con `CeoService.routeInvestorFeedback`. `data: {evidenceRound, reasoning}`. Límite de vueltas: `company.max-evidence-rounds` (`MissionService.recordDecision` rechaza la decisión con `IllegalStateException` si ya se alcanzó) — ver "Motor de aprobaciones humanas" en `CLAUDE.md`.
```

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/MissionExecutor.java \
        app/src/test/java/com/aicompany/core/service/MissionExecutorTest.java \
        docs/EVENTS.md
git commit -m "MissionExecutor: reexecuteAsync re-ejecuta los 5 agentes en REQUEST_MORE_EVIDENCE"
```

---

### Task 5: `MissionService.recordDecision` — límite de vueltas y disparo de la re-ejecución

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/MissionService.java`
- Modify: `app/src/test/java/com/aicompany/core/service/MissionServiceTest.java`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: `AppProperties.maxEvidenceRounds()` (Task 1); `MissionMemoryService.incrementEvidenceRound(String, int) -> Optional<Integer>`, `MissionMemoryService.instructionOf(String) -> Optional<String>` (Task 2); `MissionExecutor.reexecuteAsync(String, String, int, String) -> CompletableFuture<Void>` (Task 4).
- Produces: comportamiento nuevo de `MissionService.recordDecision` — sin cambio de firma pública.

- [ ] **Step 1: Reescribir el test existente que ya no describe el comportamiento correcto, y agregar los dos casos nuevos**

En `MissionServiceTest.java`, reemplazar el test `requestingMoreEvidenceDoesNotChangeMissionStatus` por estos tres:

```java
    @Test
    void requestingMoreEvidenceWithinLimitTriggersReexecutionWithoutChangingStatus() {
        var memory = mock(MissionMemoryService.class);
        var executor = mock(MissionExecutor.class);
        var eventPublisher = mock(CompanyEventPublisher.class);
        var appProperties = new AppProperties("Forjai", 50.0, 60, 2);

        var awaitingInvestor = new MissionResponse(
                "MISSION-001", MissionStatus.AWAITING_INVESTOR, "PRODUCTION", 95,
                "Recomendación", "informe final",
                Instant.parse("2026-09-12T00:00:00Z")
        );
        when(memory.find("MISSION-001")).thenReturn(Optional.of(awaitingInvestor));
        when(memory.incrementEvidenceRound("MISSION-001", 2)).thenReturn(Optional.of(1));
        when(memory.instructionOf("MISSION-001")).thenReturn(Optional.of("Investigar una oportunidad"));
        when(executor.reexecuteAsync(eq("MISSION-001"), anyString(), eq(1), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        var service = new MissionService(memory, executor, eventPublisher, appProperties);
        var response = service.recordDecision(
                "MISSION-001",
                new DecisionCommand(InvestorDecision.REQUEST_MORE_EVIDENCE, "Falta validar precios reales")
        );

        assertTrue(response.isPresent());
        verify(memory).recordDecision(
                eq("MISSION-001"), anyString(), eq(InvestorDecision.REQUEST_MORE_EVIDENCE), anyString()
        );
        verify(memory, never()).updateMission(anyString(), any(), anyInt(), anyString(), anyString());
        verify(executor).reexecuteAsync(
                "MISSION-001", "Investigar una oportunidad", 1, "Falta validar precios reales"
        );
        verify(eventPublisher).publish(
                eq("EMPRESA_MISSION_DECISION_RECORDED"), eq("MISSION-001"), any(), eq("human"), any()
        );
    }

    @Test
    void requestingMoreEvidenceAfterLimitReachedThrowsAndDoesNotRecordDecision() {
        var memory = mock(MissionMemoryService.class);
        var executor = mock(MissionExecutor.class);
        var eventPublisher = mock(CompanyEventPublisher.class);
        var appProperties = new AppProperties("Forjai", 50.0, 60, 2);

        var awaitingInvestor = new MissionResponse(
                "MISSION-001", MissionStatus.AWAITING_INVESTOR, "PRODUCTION", 95,
                "Recomendación", "informe final",
                Instant.parse("2026-09-12T00:00:00Z")
        );
        when(memory.find("MISSION-001")).thenReturn(Optional.of(awaitingInvestor));
        when(memory.incrementEvidenceRound("MISSION-001", 2)).thenReturn(Optional.empty());

        var service = new MissionService(memory, executor, eventPublisher, appProperties);

        assertThrows(IllegalStateException.class, () -> service.recordDecision(
                "MISSION-001",
                new DecisionCommand(InvestorDecision.REQUEST_MORE_EVIDENCE, "Otra vuelta más")
        ));

        verify(memory, never()).recordDecision(anyString(), anyString(), any(), anyString());
        verify(executor, never()).reexecuteAsync(anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void approvingAMissionDoesNotTouchEvidenceRoundOrReexecute() {
        var memory = mock(MissionMemoryService.class);
        var executor = mock(MissionExecutor.class);
        var eventPublisher = mock(CompanyEventPublisher.class);
        var appProperties = new AppProperties("Forjai", 50.0, 60, 2);

        var awaitingInvestor = new MissionResponse(
                "MISSION-001", MissionStatus.AWAITING_INVESTOR, "PRODUCTION", 95,
                "Recomendación", "informe final",
                Instant.parse("2026-09-12T00:00:00Z")
        );
        when(memory.find("MISSION-001")).thenReturn(Optional.of(awaitingInvestor));

        var service = new MissionService(memory, executor, eventPublisher, appProperties);
        service.recordDecision(
                "MISSION-001",
                new DecisionCommand(InvestorDecision.APPROVE, "Datos suficientes, aprobado")
        );

        verify(memory, never()).incrementEvidenceRound(anyString(), anyInt());
        verify(executor, never()).reexecuteAsync(anyString(), anyString(), anyInt(), anyString());
    }
```

Actualizar también los **otros** cinco tests existentes en el archivo (`startPersistsMissionAndSubmitsItForAsynchronousExecution`, `returnsEmptyWhenRecordingDecisionOnMissionThatDoesNotExist`, `rejectsDecisionWhenMissionIsStillRunning`, `approvingAMissionMarksItCompleted`, `rejectingAMissionMarksItCancelled`, `allowsDecisionOnAFailedMission`): en cada uno, agregar la línea

```java
        var appProperties = new AppProperties("Forjai", 50.0, 60, 2);
```

junto a las demás variables mockeadas, y agregar `appProperties` como cuarto argumento en cada `new MissionService(memory, executor, eventPublisher, appProperties)`.

Agregar el import que falte al principio del archivo:

```java
import com.aicompany.core.config.AppProperties;
```

- [ ] **Step 2: Ejecutar el test y confirmar que falla**

Run: `cd app && mvn test -Dtest=MissionServiceTest`
Expected: FAIL — no compila (`MissionService` todavía no tiene el cuarto parámetro `AppProperties`, ni `incrementEvidenceRound`/`instructionOf`/`reexecuteAsync` están cableados).

- [ ] **Step 3: Reescribir `MissionService`**

Reemplazar el archivo completo:

```java
package com.aicompany.core.service;

import com.aicompany.core.config.AppProperties;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.DecisionCommand;
import com.aicompany.core.model.DecisionResponse;
import com.aicompany.core.model.InvestorDecision;
import com.aicompany.core.model.MissionResponse;
import com.aicompany.core.model.MissionStatus;
import com.aicompany.core.model.MissionStatusResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class MissionService {

    private static final Logger log = LoggerFactory.getLogger(MissionService.class);

    private final MissionMemoryService memory;
    private final MissionExecutor executor;
    private final CompanyEventPublisher events;
    private final AppProperties appProperties;

    public MissionService(
            MissionMemoryService memory,
            MissionExecutor executor,
            CompanyEventPublisher events,
            AppProperties appProperties) {
        this.memory = memory;
        this.executor = executor;
        this.events = events;
        this.appProperties = appProperties;
    }

    public MissionResponse start(String missionId, String instruction, String environment) {
        memory.ensureMission(missionId, instruction, environment);

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

    public Optional<MissionResponse> status(String missionId) {
        return memory.find(missionId);
    }

    /**
     * Misiones recientes para el panel "Missions" del Command Center web
     * — límite fijo, v1 no expone paginación.
     */
    public List<MissionResponse> list() {
        return memory.findAll(50);
    }

    public Optional<MissionStatusResponse> details(String missionId) {
        return memory.find(missionId)
                .map(mission -> new MissionStatusResponse(
                        mission,
                        memory.tasks(missionId)
                ));
    }

    /**
     * Solo se puede decidir sobre una misión que ya terminó su
     * orquestación (con resultado, parcial o total) o que falló del todo
     * -- no tiene sentido "aprobar" una misión que todavía está
     * corriendo agentes.
     */
    private static final Set<MissionStatus> DECIDABLE_STATUSES =
            Set.of(MissionStatus.AWAITING_INVESTOR, MissionStatus.FAILED);

    public Optional<DecisionResponse> recordDecision(
            String missionId,
            DecisionCommand command) {

        var mission = memory.find(missionId);

        if (mission.isEmpty()) {
            return Optional.empty();
        }

        if (!DECIDABLE_STATUSES.contains(mission.get().status())) {
            throw new IllegalStateException(
                    "Solo se puede registrar una decisión sobre una misión "
                            + "en AWAITING_INVESTOR o FAILED (estado actual: "
                            + mission.get().status()
                            + ")"
            );
        }

        Integer newEvidenceRound = null;

        if (command.decision() == InvestorDecision.REQUEST_MORE_EVIDENCE) {

            var incremented =
                    memory.incrementEvidenceRound(
                            missionId,
                            appProperties.maxEvidenceRounds()
                    );

            if (incremented.isEmpty()) {

                throw new IllegalStateException(
                        "La misión " + missionId + " ya alcanzó el límite de "
                                + appProperties.maxEvidenceRounds()
                                + " vueltas de evidencia adicional; usa APPROVE o REJECT."
                );
            }

            newEvidenceRound = incremented.get();
        }

        var decisionId = missionId + "-DECISION-" + Instant.now().toEpochMilli();

        memory.recordDecision(
                missionId,
                decisionId,
                command.decision(),
                command.reasoning()
        );

        var newStatus = switch (command.decision()) {
            case APPROVE -> MissionStatus.COMPLETED;
            case REJECT -> MissionStatus.CANCELLED;
            case REQUEST_MORE_EVIDENCE -> null;
        };

        if (newStatus != null) {

            memory.updateMission(
                    missionId,
                    newStatus,
                    100,
                    "Decisión del inversionista",
                    command.reasoning()
            );

            events.publishMission(
                    "EMPRESA_MISSION_UPDATED",
                    missionId,
                    newStatus.name(),
                    100,
                    "Decisión del inversionista",
                    command.reasoning()
            );

        } else {

            var instruction = memory.instructionOf(missionId).orElseThrow();

            executor.reexecuteAsync(missionId, instruction, newEvidenceRound, command.reasoning())
                    .whenComplete((ignored, error) -> {
                        if (error != null) {
                            log.error("MISSION {} - evidence round async future failed", missionId, error);
                        } else {
                            log.info("MISSION {} - evidence round orchestration finished", missionId);
                        }
                    });
        }

        events.publish(
                "EMPRESA_MISSION_DECISION_RECORDED",
                missionId,
                null,
                "human",
                Map.of(
                        "decision", command.decision().name(),
                        "reasoning", command.reasoning()
                )
        );

        return Optional.of(new DecisionResponse(
                decisionId,
                missionId,
                command.decision(),
                Instant.now()
        ));
    }
}
```

- [ ] **Step 4: Ejecutar `MissionServiceTest` y confirmar que pasa**

Run: `cd app && mvn test -Dtest=MissionServiceTest`
Expected: PASS (9/9: 6 existentes actualizados + 3 nuevos).

- [ ] **Step 5: Correr toda la suite**

Run: `cd app && mvn test`
Expected: BUILD SUCCESS, todos los tests pasan.

- [ ] **Step 6: Actualizar `CLAUDE.md` — sección "Decisión del inversionista humano"**

Reemplazar el párrafo:

```markdown
`POST /missions/{missionId}/decision` (`DecisionCommand{decision: APPROVE|REJECT|REQUEST_MORE_EVIDENCE, reasoning}`) — el punto de `empresa.md` §5 nivel 🔴 donde el fundador registra su decisión real. Solo sobre una misión en `AWAITING_INVESTOR` o `FAILED` (`IllegalStateException` si no). `APPROVE` → `COMPLETED`, `REJECT` → `CANCELLED`, `REQUEST_MORE_EVIDENCE` no cambia el estado (sin re-ejecución automática todavía). `MissionMemoryService.recordDecision` persiste `(:Mission)-[:HAS_DECISION]->(:Decision {decision, reasoning, decidedAt})` — `decisionId` incluye timestamp, no es idempotente (una misión puede acumular varias decisiones). Publica `EMPRESA_MISSION_DECISION_RECORDED` siempre, más `EMPRESA_MISSION_UPDATED` cuando cambia el estado.
```

por:

```markdown
`POST /missions/{missionId}/decision` (`DecisionCommand{decision: APPROVE|REJECT|REQUEST_MORE_EVIDENCE, reasoning}`) — el punto de `empresa.md` §5 nivel 🔴 donde el fundador registra su decisión real. Solo sobre una misión en `AWAITING_INVESTOR` o `FAILED` (`IllegalStateException` si no). `APPROVE` → `COMPLETED`, `REJECT` → `CANCELLED`. `REQUEST_MORE_EVIDENCE` dispara una re-ejecución real de los 5 agentes (`MissionExecutor.reexecuteAsync`), ver "Motor de aprobaciones humanas" más abajo — no cambia `MissionStatus` de por sí (`newStatus` queda `null` en el `switch`), pero ya no es un no-op. `MissionMemoryService.recordDecision` persiste `(:Mission)-[:HAS_DECISION]->(:Decision {decision, reasoning, decidedAt})` — `decisionId` incluye timestamp, no es idempotente (una misión puede acumular varias decisiones). Publica `EMPRESA_MISSION_DECISION_RECORDED` siempre, más `EMPRESA_MISSION_UPDATED` solo cuando la decisión sí cambia el estado (`APPROVE`/`REJECT`).

**Motor de aprobaciones humanas (`REQUEST_MORE_EVIDENCE`)**: `Mission.evidenceRound` (int, default 0) cuenta las vueltas de evidencia adicional ya usadas; `company.max-evidence-rounds` (default 2) es el tope — `MissionMemoryService.incrementEvidenceRound` compara-y-aumenta atómicamente, y `MissionService.recordDecision` lanza `IllegalStateException` si ya se alcanzó (el inversionista debe resolver con `APPROVE`/`REJECT`). Al pasar el chequeo, `MissionExecutor.reexecuteAsync` reentra el mismo camino que la ejecución inicial (`DELEGATING → ... → AWAITING_INVESTOR`, sin `MissionStatus` nuevo) pero con `taskId` sufijados por ronda (`missionId-AGENTID-R{n}`, ronda 0 = ejecución original) — a diferencia de `replanFailedAgents` (que sí sobrescribe el mismo `AgentTask` por id), cada vuelta de evidencia crea nodos nuevos y preserva el historial completo en Neo4j, porque son decisiones de un inversionista humano, no reintentos internos. Antes de re-ejecutar, `CeoService.routeInvestorFeedback` (llamada nueva a Ollama, `format` sin `tools`) reparte el `reasoning` del inversionista entre los 5 agentes — el resultado se antepone a la instrucción de cada agente como bloque `SOLICITUD DEL INVERSIONISTA` solo si le tocó algo; nunca lanza (si no es parseable, la ronda sigue sin ese contexto extra). Publica `EMPRESA_MISSION_EVIDENCE_ROUND_STARTED` al arrancar cada vuelta (`docs/EVENTS.md`).
```

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/MissionService.java \
        app/src/test/java/com/aicompany/core/service/MissionServiceTest.java \
        CLAUDE.md
git commit -m "MissionService: REQUEST_MORE_EVIDENCE dispara reexecuteAsync con límite de vueltas"
```
