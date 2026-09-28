# Rondas de evidencia (v2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que `REQUEST_MORE_EVIDENCE` re-ejecute de verdad cualquier misión (discovery, análisis y Engineering) con el pedido del inversionista, hasta un límite de vueltas que es una Financial Policy, y que todo se pueda seguir por el chat.

**Architecture:** `MissionService.recordDecision` valida el límite (policy `MAX_EVIDENCE_ROUNDS`), incrementa `Mission.evidenceRound` y llama a `MissionExecutor.reexecuteAsync`. La ronda viaja como número (`TeamMissionContext.round`, parámetro de `AgentTaskBatchRunner.run`) y define los ids de tarea (`TaskIds`: ronda 0 sin sufijo, `-R<n>` desde la 1). El pedido del inversionista lo reparte el CEO (`routeInvestorFeedback`) y Java lo antepone al objetivo de cada tarea. Los equipos reutilizan el último plan guardado; Engineering trabaja sobre el mismo repositorio sin recrear el scaffold. El chat arma todo en Java.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Neo4j driver plano, Kafka, JUnit 5 + Mockito; React + TS (solo la etiqueta de la policy).

**Spec:** `docs/superpowers/specs/2026-09-16-evidence-rounds-design.md` — la sección "Revisión 2026-09-27" manda sobre lo anterior.

## Global Constraints

- Jackson 3 (`tools.jackson.*`), sin `@Async` (executor `missionOrchestratorExecutor` + `CompletableFuture.runAsync`).
- `IllegalArgumentException`/`IllegalStateException` → 500 (convención, sin manejo nuevo en controllers).
- Todo `eventType` empieza por `EMPRESA_`; nuevo `EMPRESA_MISSION_EVIDENCE_ROUND_STARTED`.
- Nunca `format` + `tools` en la misma llamada.
- `MAX_EVIDENCE_ROUNDS` default **2**; chequeo `evidenceRound >= límite` → `IllegalStateException` **sin** registrar la `Decision`.
- Decidible sobre `AWAITING_INVESTOR` y `FAILED`; ambos re-ejecutan con `REQUEST_MORE_EVIDENCE`.
- Ronda 0 conserva los ids actuales; ronda n≥1 agrega `-R<n>` (también al `-PLAN` del líder: `MISSION-X-ENGINEERING-PLAN-R1`).
- Si el reparto del CEO falla o viene vacío para un agente, ese agente recibe el pedido completo.
- Respuestas del chat sobre rondas: 100% Java desde Neo4j.

## Review Focus

- Pedir más evidencia sobre una misión que todavía corre (`WAITING_AGENT_RESULTS`) → rechazo claro, sin registrar nada (ya lo cubre `DECIDABLE_STATUSES`; test en Task 2).
- `reasoning` vacío o null en `REQUEST_MORE_EVIDENCE` → la ronda corre igual con "(sin comentario del inversionista)" (test en Task 3).
- Un agente de Engineering que en la ronda 1 devuelve sus archivos idénticos → el commit no falla (`--allow-empty`) y `FILES_IN_COMMIT` sigue pasando (test en Task 5).
- Misión de equipo `FAILED` en la planificación (sin plan guardado) → la ronda replanifica con el pedido (test en Task 4).
- "¿Cómo va MISSION-X?" sobre una misión de antes de esta feature (sin `evidenceRound`) → se trata como ronda 0, sin romper (test en Task 6).

---

### Task 1: Ids por ronda, policy `MAX_EVIDENCE_ROUNDS` y memoria de la ronda

**Files:**
- Create: `app/src/main/java/com/aicompany/core/model/TaskIds.java`
- Test: `app/src/test/java/com/aicompany/core/model/TaskIdsTest.java`
- Modify: `app/src/main/java/com/aicompany/core/model/PolicyKey.java`, `.../service/CompanyPolicyService.java` (defaults), `.../service/MissionMemoryService.java`
- Modify: `app/frontend/src/api/types.ts` (union de `PolicyKey`), `app/frontend/src/pages/SettingsPage.tsx` (etiqueta)

**Interfaces:**
- Produces: `TaskIds.agentTask(String missionId, String agentId, int round)`, `TaskIds.planTask(String missionId, String leaderId, int round)`, `TaskIds.roundOf(String taskId)` (int, 0 sin sufijo); `PolicyKey.MAX_EVIDENCE_ROUNDS`; `MissionMemoryService.evidenceRound(String missionId)` (int, 0 si falta), `setEvidenceRound(String, int)`, `instructionOf(String)` (`Optional<String>`), `evidenceRequests(String missionId)` (`List<String>`: `reasoning` de cada `REQUEST_MORE_EVIDENCE` en orden de `recordedAt`).

- [ ] **Step 1: Test que falla**

```java
class TaskIdsTest {
    @Test
    void roundZeroKeepsTodaysIdsAndLaterRoundsGetASuffix() {
        assertEquals("MISSION-1-SALES", TaskIds.agentTask("MISSION-1", "sales", 0));
        assertEquals("MISSION-1-SALES-R1", TaskIds.agentTask("MISSION-1", "sales", 1));
        assertEquals("MISSION-1-ENGINEERING-PLAN", TaskIds.planTask("MISSION-1", "engineering", 0));
        assertEquals("MISSION-1-ENGINEERING-PLAN-R2", TaskIds.planTask("MISSION-1", "engineering", 2));
    }

    @Test
    void theRoundIsReadBackFromTheId() {
        assertEquals(0, TaskIds.roundOf("MISSION-1-SALES"));
        assertEquals(2, TaskIds.roundOf("MISSION-1-FRONTEND-UI-R2"));
        assertEquals(0, TaskIds.roundOf("MISSION-R2D2-SALES"));
    }
}
```

- [ ] **Step 2:** `cd app && mvn -q test -Dtest=TaskIdsTest` → FAIL (no compila: `TaskIds` no existe).
- [ ] **Step 3: Implementación**

```java
package com.aicompany.core.model;

import java.util.Locale;
import java.util.regex.Pattern;

/** Ids de tarea por ronda de evidencia (spec 2026-09-16, revisión 2026-09-27): ronda 0 sin sufijo, luego -R<n>. */
public final class TaskIds {

    private static final Pattern ROUND_SUFFIX = Pattern.compile("-R(\\d+)$");

    private TaskIds() {
    }

    public static String agentTask(String missionId, String agentId, int round) {
        return withRound(missionId + "-" + agentId.toUpperCase(Locale.ROOT), round);
    }

    public static String planTask(String missionId, String leaderId, int round) {
        return withRound(missionId + "-" + leaderId.toUpperCase(Locale.ROOT) + "-PLAN", round);
    }

    public static int roundOf(String taskId) {
        var matcher = ROUND_SUFFIX.matcher(taskId == null ? "" : taskId);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private static String withRound(String base, int round) {
        return round <= 0 ? base : base + "-R" + round;
    }
}
```

`roundOf("MISSION-R2D2-SALES")` da 0 porque el patrón exige `-R<dígitos>` al final.

- [ ] **Step 4:** `mvn -q test -Dtest=TaskIdsTest` → PASS.
- [ ] **Step 5: Policy y memoria.** `PolicyKey`: agregar `MAX_EVIDENCE_ROUNDS` al final. `CompanyPolicyService.defaults`: `map.put(PolicyKey.MAX_EVIDENCE_ROUNDS, 2.0);` (la siembra idempotente ya recorre el mapa). `MissionMemoryService`:

```java
public int evidenceRound(String missionId) {
    try (var session = driver.session()) {
        return session.run("MATCH (m:Mission {id:$id}) RETURN coalesce(m.evidenceRound, 0) AS r", Map.of("id", missionId))
                .list(r -> r.get("r").asInt()).stream().findFirst().orElse(0);
    }
}

public void setEvidenceRound(String missionId, int round) {
    try (var session = driver.session()) {
        session.executeWrite(tx -> {
            tx.run("MATCH (m:Mission {id:$id}) SET m.evidenceRound=$round", Map.of("id", missionId, "round", round));
            return null;
        });
    }
}

public Optional<String> instructionOf(String missionId) {
    try (var session = driver.session()) {
        return session.run("MATCH (m:Mission {id:$id}) RETURN m.instruction AS i", Map.of("id", missionId))
                .list(r -> r.get("i").isNull() ? null : r.get("i").asString()).stream()
                .filter(java.util.Objects::nonNull).findFirst();
    }
}

public List<String> evidenceRequests(String missionId) {
    try (var session = driver.session()) {
        return session.run("MATCH (:Mission {id:$id})-[:HAS_DECISION]->(d:Decision {decision:'REQUEST_MORE_EVIDENCE'}) "
                        + "RETURN coalesce(d.reasoning, '') AS r ORDER BY d.recordedAt", Map.of("id", missionId))
                .list(r -> r.get("r").asString());
    }
}
```

Antes de escribir `instructionOf` y `evidenceRequests`, confirmar con `grep -n "instruction\|recordedAt\|decision:" MissionMemoryService.java` los nombres reales de las propiedades (`m.instruction`, `d.decision`, `d.recordedAt`) y ajustar si difieren. Frontend: agregar `| 'MAX_EVIDENCE_ROUNDS'` a la union y `MAX_EVIDENCE_ROUNDS: 'Vueltas máximas de "más evidencia" por misión',` en `SettingsPage.tsx`.
- [ ] **Step 6:** `mvn -q test` (suite) y `cd frontend && npm run lint && npm run build` → verdes.
- [ ] **Step 7: Commit** `git commit -m "Rondas de evidencia: ids por ronda, policy MAX_EVIDENCE_ROUNDS y memoria de la ronda"`

---

### Task 2: `recordDecision` dispara la ronda (límite, FAILED, sin registrar si se pasa)

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/MissionService.java`, `.../model/DecisionResponse.java`
- Test: `app/src/test/java/com/aicompany/core/service/MissionServiceTest.java`

**Interfaces:**
- Consumes: `MissionMemoryService.evidenceRound/setEvidenceRound/instructionOf` (Task 1), `CompanyPolicyService.activeValue(PolicyKey.MAX_EVIDENCE_ROUNDS)`.
- Produces: `MissionExecutor.reexecuteAsync(String missionId, String instruction, int round, String investorRequest)` (firma; cuerpo en Task 4, acá un stub que devuelve `CompletableFuture.completedFuture(null)`). `DecisionResponse` gana `Integer evidenceRound` (null si no hubo ronda) con constructor de 4 args que lo deja en null. `MissionService` gana un constructor de 6 args con `CompanyPolicyService` (`@Autowired`); el de 5 delega con `null` (y `REQUEST_MORE_EVIDENCE` sin policy lanza `IllegalStateException("Falta CompanyPolicyService")`).

- [ ] **Step 1: Tests que fallan** (en `MissionServiceTest`, con `policies = mock(CompanyPolicyService.class)` y `new MissionService(memory, executor, eventPublisher, teamMemory, workspace, policies)`):

```java
@Test
void requestMoreEvidenceStartsTheNextRoundWithTheOriginalInstruction() {
    when(memory.find("MISSION-1")).thenReturn(Optional.of(mission("MISSION-1", MissionStatus.AWAITING_INVESTOR)));
    when(memory.evidenceRound("MISSION-1")).thenReturn(0);
    when(memory.instructionOf("MISSION-1")).thenReturn(Optional.of("Buscar un servicio"));
    when(policies.activeValue(PolicyKey.MAX_EVIDENCE_ROUNDS)).thenReturn(2.0);
    when(executor.reexecuteAsync(any(), any(), anyInt(), any())).thenReturn(CompletableFuture.completedFuture(null));

    var response = service.recordDecision("MISSION-1",
            new DecisionCommand(InvestorDecision.REQUEST_MORE_EVIDENCE, "Quiero precios reales")).orElseThrow();

    assertEquals(1, response.evidenceRound());
    verify(memory).setEvidenceRound("MISSION-1", 1);
    verify(executor).reexecuteAsync("MISSION-1", "Buscar un servicio", 1, "Quiero precios reales");
}

@Test
void aFailedMissionAlsoRunsAgainAndConsumesARound() {
    when(memory.find("MISSION-1")).thenReturn(Optional.of(mission("MISSION-1", MissionStatus.FAILED)));
    when(memory.evidenceRound("MISSION-1")).thenReturn(1);
    when(memory.instructionOf("MISSION-1")).thenReturn(Optional.of("x"));
    when(policies.activeValue(PolicyKey.MAX_EVIDENCE_ROUNDS)).thenReturn(2.0);
    when(executor.reexecuteAsync(any(), any(), anyInt(), any())).thenReturn(CompletableFuture.completedFuture(null));

    service.recordDecision("MISSION-1", new DecisionCommand(InvestorDecision.REQUEST_MORE_EVIDENCE, "reintenta"));

    verify(executor).reexecuteAsync("MISSION-1", "x", 2, "reintenta");
}

@Test
void atTheLimitItIsRejectedWithoutRecordingTheDecision() {
    when(memory.find("MISSION-1")).thenReturn(Optional.of(mission("MISSION-1", MissionStatus.AWAITING_INVESTOR)));
    when(memory.evidenceRound("MISSION-1")).thenReturn(2);
    when(policies.activeValue(PolicyKey.MAX_EVIDENCE_ROUNDS)).thenReturn(2.0);

    var ex = assertThrows(IllegalStateException.class, () -> service.recordDecision("MISSION-1",
            new DecisionCommand(InvestorDecision.REQUEST_MORE_EVIDENCE, "más")));

    assertTrue(ex.getMessage().contains("2 de 2"), ex.getMessage());
    verify(memory, never()).recordDecision(any(), any(), any(), any());
    verify(executor, never()).reexecuteAsync(any(), any(), anyInt(), any());
}

@Test
void aMissionStillRunningCannotAskForMoreEvidence() {
    when(memory.find("MISSION-1")).thenReturn(Optional.of(mission("MISSION-1", MissionStatus.WAITING_AGENT_RESULTS)));

    assertThrows(IllegalStateException.class, () -> service.recordDecision("MISSION-1",
            new DecisionCommand(InvestorDecision.REQUEST_MORE_EVIDENCE, "más")));
    verify(memory, never()).recordDecision(any(), any(), any(), any());
}
```

(`mission(id, status)` es un helper del test: `new MissionResponse(id, status, "TEST", 95, "x", "x", Instant.now(), null, null)`; si ya existe uno equivalente en el archivo, usarlo.)
- [ ] **Step 2:** `mvn -q test -Dtest=MissionServiceTest` → FAIL (no compila: firmas nuevas).
- [ ] **Step 3: Implementación.** Stub en `MissionExecutor`:

```java
public CompletableFuture<Void> reexecuteAsync(String missionId, String instruction, int round, String investorRequest) {
    return CompletableFuture.completedFuture(null);
}
```

En `recordDecision`, después del chequeo de `DECIDABLE_STATUSES` y **antes** de `memory.recordDecision`:

```java
Integer nextRound = null;
if (command.decision() == InvestorDecision.REQUEST_MORE_EVIDENCE) {
    if (policies == null) {
        throw new IllegalStateException("Falta CompanyPolicyService");
    }
    var current = memory.evidenceRound(missionId);
    var max = (int) policies.activeValue(PolicyKey.MAX_EVIDENCE_ROUNDS);
    if (current >= max) {
        throw new IllegalStateException("MISSION " + missionId + " ya usó " + current + " de " + max
                + " vueltas de evidencia: decide con APPROVE o REJECT (o sube MAX_EVIDENCE_ROUNDS en Settings).");
    }
    nextRound = current + 1;
}
```

Y al final, después de publicar `EMPRESA_MISSION_DECISION_RECORDED`:

```java
if (nextRound != null) {
    memory.setEvidenceRound(missionId, nextRound);
    var instruction = memory.instructionOf(missionId).orElse("");
    executor.reexecuteAsync(missionId, instruction, nextRound, command.reasoning())
            .whenComplete((ok, ex) -> {
                if (ex != null) {
                    log.error("MISSION {} - evidence round {} failed", missionId, nextRound, ex);
                }
            });
}
```

Devolver `new DecisionResponse(decisionId, missionId, command.decision(), recordedAt, nextRound)`. Si `MissionService` no tiene `log`, agregar `private static final Logger log = LoggerFactory.getLogger(MissionService.class);`. Mismo patrón `whenComplete` que `start()`: revisarlo y copiar su forma.
- [ ] **Step 4:** `mvn -q test -Dtest=MissionServiceTest` → PASS; `mvn -q test` → verde.
- [ ] **Step 5: Commit** `git commit -m "REQUEST_MORE_EVIDENCE dispara una ronda nueva con límite por policy (también sobre FAILED)"`

---

### Task 3: El CEO reparte el pedido del inversionista

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/CeoService.java`
- Test: `app/src/test/java/com/aicompany/core/service/CeoServiceInvestorFeedbackTest.java` (nuevo)

**Interfaces:**
- Produces: `Map<String, String> CeoService.routeInvestorFeedback(String instruction, String priorResults, String investorRequest, List<String> agentIds, String model)`: una entrada por cada `agentId`, nunca vacía (fallback: el pedido completo). Operación `INVESTOR_FEEDBACK_ROUTING`, `format` = schema con esas claves, sin `tools`.

- [ ] **Step 1: Tests que fallan** (mismo armado que `CeoServiceMemoryClaimTest`, con `OpenAiCompatibleClient ceo` en `Map.of("nvidia-ceo", ceo)`):

```java
@Test
void eachAgentGetsItsPartOfTheRequest() {
    when(ceo.complete(anyString(), anyList(), isNull(), eq(true), anyInt())).thenReturn(new OpenAiCompatibleClient.RemoteReply(
            "{\"sales\":\"Busca 3 precios reales\",\"finance\":\"Recalcula con esos precios\"}", List.of()));

    var routed = ceoService.routeInvestorFeedback("Buscar servicio", "resultados", "Quiero precios reales",
            List.of("sales", "finance"), "nvidia-ceo:m");

    assertEquals("Busca 3 precios reales", routed.get("sales"));
    assertEquals("Recalcula con esos precios", routed.get("finance"));
}

@Test
void anAgentLeftEmptyOrAFailingModelFallsBackToTheFullRequest() {
    when(ceo.complete(anyString(), anyList(), isNull(), eq(true), anyInt()))
            .thenReturn(new OpenAiCompatibleClient.RemoteReply("{\"sales\":\"\"}", List.of()))
            .thenThrow(new IllegalStateException("429"));

    var partial = ceoService.routeInvestorFeedback("i", "r", "Quiero precios reales", List.of("sales", "qa"), "nvidia-ceo:m");
    var failed = ceoService.routeInvestorFeedback("i", "r", "Quiero precios reales", List.of("sales"), "nvidia-ceo:m");

    assertEquals("Quiero precios reales", partial.get("sales"));
    assertEquals("Quiero precios reales", partial.get("qa"));
    assertEquals("Quiero precios reales", failed.get("sales"));
}

@Test
void withoutACommentTheRoundStillRuns() {
    var routed = ceoService.routeInvestorFeedback("i", "r", "  ", List.of("sales"), "nvidia-ceo:m");

    assertEquals("(sin comentario del inversionista)", routed.get("sales"));
    verifyNoInteractions(ceo);
}
```

Confirmar antes cómo `callModel` pasa `format` a `complete` (el 4º argumento es `json`: `format != null`) y ajustar los matchers si difiere.
- [ ] **Step 2:** `mvn -q test -Dtest=CeoServiceInvestorFeedbackTest` → FAIL (método inexistente).
- [ ] **Step 3: Implementación** en `CeoService`:

```java
static final String NO_INVESTOR_COMMENT = "(sin comentario del inversionista)";

/** Spec evidence-rounds §5 (revisión 2026-09-27): el CEO reparte el pedido; Java garantiza que nadie quede sin él. */
public Map<String, String> routeInvestorFeedback(String instruction, String priorResults, String investorRequest,
                                                 List<String> agentIds, String model) {
    var request = investorRequest == null || investorRequest.isBlank() ? NO_INVESTOR_COMMENT : investorRequest.strip();
    var routed = new LinkedHashMap<String, String>();
    agentIds.forEach(id -> routed.put(id, request));
    if (request.equals(NO_INVESTOR_COMMENT)) {
        return routed;
    }
    try {
        var properties = new LinkedHashMap<String, Object>();
        agentIds.forEach(id -> properties.put(id, Map.of("type", "string")));
        var schema = Map.<String, Object>of("type", "object", "properties", properties, "required", agentIds);
        var system = """
                Eres el CEO de Forjai. El inversionista pidió más evidencia sobre una misión. Reparte su pedido entre
                los agentes: para cada uno, qué debe buscar o corregir en esta nueva ronda, en una o dos frases, sin
                inventar datos. Si el pedido no le toca a un agente, deja su valor vacío. Responde solo el JSON.
                """;
        var user = "MISIÓN:\n" + instruction + "\n\nRESULTADOS DE LA RONDA ANTERIOR:\n" + priorResults
                + "\n\nPEDIDO DEL INVERSIONISTA:\n" + request;
        var reply = callModel("INVESTOR_FEEDBACK_ROUTING", "ceo", model,
                List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)),
                schema, null);
        var node = jsonMapper.readTree(normalizeJsonResponse(reply.content()));
        for (var id : agentIds) {
            var part = node.path(id).asString("");
            if (!part.isBlank()) {
                routed.put(id, part.strip() + "\n(Pedido original del inversionista: " + request + ")");
            }
        }
    } catch (Exception ex) {
        log.warn("INVESTOR_FEEDBACK_ROUTING failed, every agent gets the full request: {}", ex.getMessage());
    }
    return routed;
}
```

Revisar la firma real de `callModel` (orden de `format` y `tools`) y cómo se llama a `normalizeJsonResponse`; ajustar a ellas. Como el valor repartido lleva el pedido original entre paréntesis, el test de "cada agente recibe su parte" debe usar `startsWith` en vez de `assertEquals`: ajustar esas dos aserciones a `assertTrue(routed.get("sales").startsWith("Busca 3 precios reales"))`.
- [ ] **Step 4:** `mvn -q test -Dtest=CeoServiceInvestorFeedbackTest` → PASS; suite verde.
- [ ] **Step 5: Commit** `git commit -m "CeoService.routeInvestorFeedback: el CEO reparte el pedido y Java garantiza que nadie quede sin él"`

---

### Task 4: `reexecuteAsync` para discovery y equipos de análisis (plan reutilizado)

**Files:**
- Modify: `.../service/MissionExecutor.java`, `.../service/AgentTaskBatchRunner.java`, `.../service/AnalysisTeamStrategy.java`, `.../model/TeamMissionContext.java`, `.../service/TeamWorkPlanner.java`, `.../service/MissionMemoryService.java`
- Test: `app/src/test/java/com/aicompany/core/service/MissionExecutorTest.java`, `.../MissionExecutorTeamTest.java`

**Interfaces:**
- Consumes: `TaskIds` (Task 1), `routeInvestorFeedback` (Task 3), `reexecuteAsync` (firma de Task 2).
- Produces: `AgentTaskBatchRunner.run(String missionId, String instruction, List<AgentTaskDefinition> defs, Runnable onSubmitted, int round)` (el de 4 args delega con 0; `taskId = TaskIds.agentTask(missionId, agentId, round)` también en `replanFailedAgents`); `TeamMissionContext(String missionId, String instruction, TeamSnapshot team, TeamPlan plan, int round)` + constructor de 4 args con `round = 0`; `TeamWorkPlanner.plan(missionId, teamId, instruction, mode, int round)` (el de 4 delega con 0; usa `TaskIds.planTask`); `MissionMemoryService.lastTeamPlanJson(String missionId)` → `Optional<String>` (result de la tarea `kind='PLANNING'` `COMPLETED` más reciente por `updatedAt`); `MissionExecutor.investorBlock(String routedRequest)` → `"SOLICITUD DEL INVERSIONISTA (ronda de evidencia):\n" + routed`.

- [ ] **Step 1: Tests que fallan.** En `MissionExecutorTest` (discovery), con `companyPolicyService`, `memory` y `runtime` ya mockeados en el archivo:

```java
@Test
void anEvidenceRoundRerunsTheFiveAgentsWithSuffixedIdsAndTheInvestorRequest() {
    when(memory.teamId("MISSION-1")).thenReturn(Optional.empty());
    when(memory.tasks("MISSION-1")).thenReturn(List.of());
    when(ceoService.routeInvestorFeedback(any(), any(), any(), anyList(), any()))
            .thenAnswer(inv -> ((List<String>) inv.getArgument(3)).stream()
                    .collect(Collectors.toMap(id -> id, id -> "Busca precios reales")));
    // stub de runtime.execute y del resto como en el test de discovery existente del archivo

    executor.reexecuteAsync("MISSION-1", "Buscar servicio", 1, "Quiero precios reales").join();

    verify(memory).createTask(eq("MISSION-1-SALES-R1"), eq("MISSION-1"), eq("sales"), eq("MARKET_DISCOVERY"));
    verify(runtime).execute(eq("MISSION-1-SALES-R1"), eq("MISSION-1"), eq("sales"), eq("MARKET_DISCOVERY"),
            argThat(p -> p.contains("SOLICITUD DEL INVERSIONISTA") && p.contains("Busca precios reales")));
    verify(events).publish(eq("EMPRESA_MISSION_EVIDENCE_ROUND_STARTED"), eq("MISSION-1"), isNull(), eq("human"),
            argThat(m -> Integer.valueOf(1).equals(m.get("evidenceRound"))));
}
```

(Tomar el armado de stubs de `runtime.execute`, `ceoService.executeMission` y `companyPolicyService.activeValue` del test de discovery completo que ya exista en `MissionExecutorTest`; mismo flujo, distinta entrada.)

En `MissionExecutorTeamTest`:

```java
@Test
void anEvidenceRoundOfATeamReusesTheSavedPlanWithoutCallingThePlanner() {
    when(memory.teamId("MISSION-2")).thenReturn(Optional.of("TEAM-MARKETING-GROWTH"));
    when(memory.lastTeamPlanJson("MISSION-2")).thenReturn(Optional.of(planJson)); // plan de análisis de 2 miembros
    // teamMemory.snapshot("TEAM-MARKETING-GROWTH") y la estrategia ANALYSIS como en los tests existentes

    executor.reexecuteAsync("MISSION-2", "Plan de contenido", 1, "Más canales").join();

    verify(teamWorkPlanner, never()).plan(any(), any(), any(), any(), anyInt());
    verify(analysisStrategy).execute(argThat(c -> c.round() == 1 && c.plan().workTasks().stream()
            .allMatch(t -> t.objective().contains("SOLICITUD DEL INVERSIONISTA"))), any());
}

@Test
void aTeamMissionThatFailedBeforeHavingAPlanIsPlannedAgainWithTheRequest() {
    when(memory.teamId("MISSION-3")).thenReturn(Optional.of("TEAM-MARKETING-GROWTH"));
    when(memory.lastTeamPlanJson("MISSION-3")).thenReturn(Optional.empty());
    when(teamWorkPlanner.plan(eq("MISSION-3"), eq("TEAM-MARKETING-GROWTH"),
            argThat(i -> i.contains("Más canales")), any(), eq(1))).thenReturn(planned);

    executor.reexecuteAsync("MISSION-3", "Plan de contenido", 1, "Más canales").join();

    verify(teamWorkPlanner).plan(eq("MISSION-3"), eq("TEAM-MARKETING-GROWTH"), argThat(i -> i.contains("Más canales")),
            any(), eq(1));
}
```

(`planJson` = JSON de un `TeamPlan` de análisis con tareas de `growth-content` y `community`, igual al que ya usa el archivo; `planned` = el `TeamPlanResult` de los tests existentes.)
- [ ] **Step 2:** `mvn -q test -Dtest='MissionExecutorTest,MissionExecutorTeamTest'` → FAIL.
- [ ] **Step 3: Implementación.**
  1. `AgentTaskBatchRunner`: agregar el parámetro `round` y usar `TaskIds.agentTask(missionId, agentId, round)` en `run` y en `replanFailedAgents` (pasarle `round`).
  2. `TeamMissionContext`: agregar `int round` + constructor de 4 args. `AnalysisTeamStrategy` pasa `context.round()` al `run`.
  3. `TeamWorkPlanner.plan(..., int round)`: `taskId = TaskIds.planTask(missionId, leaderId, round)`; el de 4 args delega con 0.
  4. `MissionMemoryService.lastTeamPlanJson`:
     ```java
     public Optional<String> lastTeamPlanJson(String missionId) {
         try (var session = driver.session()) {
             return session.run("MATCH (:Mission {id:$id})-[:HAS_TASK]->(t:AgentTask {kind:'PLANNING', status:'COMPLETED'}) "
                             + "RETURN t.result AS r ORDER BY t.updatedAt DESC LIMIT 1", Map.of("id", missionId))
                     .list(r -> r.get("r").asString()).stream().findFirst();
         }
     }
     ```
  5. `MissionExecutor.reexecuteAsync` (reemplaza el stub): publica `EMPRESA_MISSION_EVIDENCE_ROUND_STARTED` (`events.publish(type, missionId, null, "human", Map.of("evidenceRound", round, "reasoning", request == null ? "" : request))`) y corre en `orchestratorExecutor` un `reexecuteInternal(missionId, instruction, round, request)` con el mismo `try/catch → safeFail` que `executeInternal`.
  6. `reexecuteInternal`:
     - `priorResults` = texto de las tareas `COMPLETED` de la ronda anterior: `memory.tasks(missionId)` filtradas por `TaskIds.roundOf(t.taskId()) == round - 1`, `agentId + ": " + result`, recortado a 12.000 caracteres.
     - **Discovery** (sin `teamId`): `advanceMission(DELEGATING, 10, "Ronda de evidencia " + round, ...)`; `routed = ceoService.routeInvestorFeedback(instruction, priorResults, request, ids de discoveryDefinitions, agentModel("ceo"))`; definiciones con `objective = investorBlock(routed.get(id)) + "\n" + objetivo original`; `batchRunner.run(missionId, instruction, defs, onSubmitted, round)`; `consolidateAgentOutcomes` igual que hoy.
     - **Equipo**: `plan = lastTeamPlanJson` → `jsonMapper.readValue(json, TeamPlan.class)`; si falta, `teamWorkPlanner.plan(missionId, teamId, instruction + "\n\n" + investorBlock(request), mode, round)`. Si se reutiliza, `team = teamMemory`/`TeamMemoryService.snapshot(teamId)` (usar el mismo acceso que `TeamWorkPlanner` y ya inyectado; si `MissionExecutor` no lo tiene, agregar `TeamMemoryService` al constructor y a los tests). `routed` sobre los `agentId` de las tareas del plan; plan con objetivos reescritos (`new PlannedTask(... investorBlock(routed.get(agentId)) + "\n" + objective ...)`, conservando `ownedPaths` y `assignments`); `context = new TeamMissionContext(missionId, instruction, team, plan, round)` y el resto como `executeTeamMission`. Extraer de `executeTeamMission` el tramo "estrategia + switch de consolidación" a un método `runTeamPlan(missionId, instruction, teamId, context)` y usarlo en ambos.
- [ ] **Step 4:** `mvn -q test -Dtest='MissionExecutorTest,MissionExecutorTeamTest,AgentTaskBatchRunner*'` → PASS; `mvn -q test` → verde.
- [ ] **Step 5: Commit** `git commit -m "MissionExecutor.reexecuteAsync: discovery y equipos de análisis re-ejecutan con el pedido (plan reutilizado)"`

---

### Task 5: Engineering en la ronda: mismo repositorio, sin scaffold nuevo

**Files:**
- Modify: `.../service/DevelopmentTeamStrategy.java`, `.../service/DevelopmentWorkspaceService.java`
- Test: `.../DevelopmentTeamStrategyTest.java`, `.../DevelopmentWorkspaceServiceTest.java`

**Interfaces:**
- Consumes: `TeamMissionContext.round()` (Task 4), `TaskIds` (Task 1).
- Produces: `DevelopmentWorkspaceService.headSha(String missionId)` → `String`; `commitAgentWork` con `--allow-empty`; `DevelopmentTeamStrategy.taskId(TeamMissionContext, String agentId)` reemplaza a `taskId(String, String)` en todos los usos.

- [ ] **Step 1: Tests que fallan.** En `DevelopmentWorkspaceServiceTest` (usa un repo Git real temporal):

```java
@Test
void committingTheSameFilesAgainIsAllowedAndKeepsThemInTheCommit() throws Exception {
    var result = new DevelopmentResult("v1", List.of(new DevelopmentResult.GeneratedFile("src/A.cs", "class A {}")));
    service.commitAgentWork("MISSION-9", "MISSION-9-BACKEND", "backend", "Iris", result);

    var second = service.commitAgentWork("MISSION-9", "MISSION-9-BACKEND-R1", "backend", "Iris", result);

    assertEquals(second.sha(), service.headSha("MISSION-9"));
    assertTrue(service.filesAtCommit("MISSION-9", second.sha()).contains("src/A.cs"));
}
```

(Usar los nombres reales del record de archivo de `DevelopmentResult` y del armado del servicio que ya usa el archivo.)

En `DevelopmentTeamStrategyTest`, con el armado de contexto/plan que ya usa el archivo, pero con `round = 1` y un workspace que ya tiene commits:

```java
@Test
void anEvidenceRoundDoesNotRecreateTheScaffoldAndUsesSuffixedTaskIds() {
    // contexto = el del test existente de generación, con new TeamMissionContext(..., 1)
    // workspace.headSha(missionId) devuelve "abc123"
    strategy.execute(roundOneContext, progress);

    verify(workspace, never()).commitAgentWork(any(), endsWith("-SCAFFOLD"), any(), any(), any());
    verify(memory).createTask(eq(missionId + "-ENGINEERING-R1"), eq(missionId), eq("engineering"), any(), eq("WORK"));
}
```
- [ ] **Step 2:** `mvn -q test -Dtest='DevelopmentWorkspaceServiceTest,DevelopmentTeamStrategyTest'` → FAIL.
- [ ] **Step 3: Implementación.**
  - `DevelopmentWorkspaceService.headSha`: `return git.run(missionWorkspace(missionId), "rev-parse", "HEAD").trim();` (declara `throws IOException`, como sus vecinos). En `commitAgentWork` agregar `"--allow-empty"` después de `"commit", "-q"`. `FILES_IN_COMMIT` lee `ls-tree` del árbol, así que un commit vacío conserva los archivos.
  - `DevelopmentTeamStrategy`: `static String taskId(TeamMissionContext context, String agentId) { return TaskIds.agentTask(context.missionId(), agentId, context.round()); }` y reemplazar las 5 llamadas a `taskId(missionId, ...)` (líneas ~106, 121, 140, 280, 706); si un método no tiene `context`, pasárselo. En `execute`, antes del scaffold:
    ```java
    CommittedWork scaffold;
    if (context.round() > 0) {
        // Ronda de evidencia: el repositorio ya existe; los agentes corrigen encima del código actual.
        var files = plan.profile().map(p -> ProjectScaffold.generate(p, plan.contextNames()))
                .orElse(List.of()).stream().map(DevelopmentResult.GeneratedFile::path).toList();
        scaffold = files.isEmpty() ? null : new CommittedWork(missionId + "-SCAFFOLD", "forjai", headSha(missionId), files);
    } else {
        // bloque try/catch actual de commitProjectScaffold
    }
    ```
    con `headSha` envolviendo `workspace.headSha` y convirtiendo `IOException` en `IllegalStateException("No se pudo leer el repositorio de la ronda anterior")`. El objetivo de cada `PlannedTask` ya llega con el bloque del inversionista (Task 4), y la generación ya muestra el código commiteado desde `generationHead` = HEAD.
- [ ] **Step 4:** `mvn -q test -Dtest='DevelopmentWorkspaceServiceTest,DevelopmentTeamStrategyTest'` → PASS; `mvn -q test` → verde.
- [ ] **Step 5: Commit** `git commit -m "Engineering en rondas de evidencia: mismo repositorio, sin scaffold nuevo, ids -R<n>"`

---

### Task 6: Todo lo de las rondas en el chat

**Files:**
- Modify: `.../service/ChatIntentRouter.java`
- Test: `.../service/ChatIntentRouterTest.java`

**Interfaces:**
- Consumes: `DecisionResponse.evidenceRound()` (Task 2), `MissionMemoryService.evidenceRound/evidenceRequests` (Task 1), `TaskIds.roundOf` (Task 1), `CompanyPolicyService.activeValue(MAX_EVIDENCE_ROUNDS)` (ya inyectado en el router; si no, agregarlo).

- [ ] **Step 1: Tests que fallan:**

```java
@Test
void askingForMoreEvidenceInTheChatSaysWhichRoundStartedAndWhoWorks() {
    when(missionService.recordDecision(eq("MISSION-1"), any())).thenReturn(Optional.of(
            new DecisionResponse("D1", "MISSION-1", InvestorDecision.REQUEST_MORE_EVIDENCE, Instant.now(), 1)));
    when(companyPolicyService.activeValue(PolicyKey.MAX_EVIDENCE_ROUNDS)).thenReturn(2.0);
    when(missionMemory.teamId("MISSION-1")).thenReturn(Optional.empty());

    var response = router.route("pide más evidencia sobre MISSION-1: quiero precios reales");

    assertTrue(response.contains("ronda 1 de 2"), response);
    assertTrue(response.contains("Sales, Product, Finance, Engineering y QA"), response);
}

@Test
void whenTheLimitIsReachedTheChatExplainsIt() {
    when(missionService.recordDecision(eq("MISSION-1"), any())).thenThrow(new IllegalStateException(
            "MISSION MISSION-1 ya usó 2 de 2 vueltas de evidencia: decide con APPROVE o REJECT"));

    var response = router.route("pide más evidencia sobre MISSION-1");

    assertTrue(response.contains("2 de 2"), response);
}

@Test
void missionStatusGroupsTasksByRoundAndShowsEachRequest() {
    // misión MISSION-5 en AWAITING_INVESTOR con tareas MISSION-5-SALES (COMPLETED) y MISSION-5-SALES-R1 (COMPLETED)
    when(missionMemory.evidenceRound("MISSION-5")).thenReturn(1);
    when(missionMemory.evidenceRequests("MISSION-5")).thenReturn(List.of("Quiero precios reales"));
    when(companyPolicyService.activeValue(PolicyKey.MAX_EVIDENCE_ROUNDS)).thenReturn(2.0);

    var response = router.route("¿Cómo va MISSION-5?");

    assertTrue(response.contains("Ronda de evidencia 1 de 2 (queda 1)"), response);
    assertTrue(response.contains("Ronda 0:") && response.contains("Ronda 1 (pedido: \"Quiero precios reales\")"), response);
}

@Test
void aMissionFromBeforeRoundsIsShownAsRoundZero() {
    // misión vieja: evidenceRound 0, sin pedidos
    when(missionMemory.evidenceRound("MISSION-6")).thenReturn(0);
    when(missionMemory.evidenceRequests("MISSION-6")).thenReturn(List.of());

    var response = router.route("¿Cómo va MISSION-6?");

    assertFalse(response.contains("Ronda de evidencia"), response);
}

@Test
void companyStatusCountsMissionsRerunningForEvidence() {
    // una misión en WAITING_AGENT_RESULTS con evidenceRound 1
    var response = router.route("dame un status");

    assertTrue(response.contains("1 re-ejecutándose por más evidencia"), response);
}
```

(Armar las misiones con los helpers `MissionResponse`/`AgentTask` que ya usa el archivo, como en `routesExplicitMissionIdStatusQueryDeterministicallyWithoutOllama`.)
- [ ] **Step 2:** `mvn -q test -Dtest=ChatIntentRouterTest` → FAIL.
- [ ] **Step 3: Implementación.**
  - Respuesta de decisión (hoy "Decisión registrada: …"): si `response.evidenceRound() != null`, devolver `"Arrancó la ronda " + n + " de " + max + " de más evidencia sobre " + missionId + ". Trabajan: " + quiénes + ". Te aviso por aquí y por correo cuando vuelva a esperar tu decisión."`; quiénes = `"Sales, Product, Finance, Engineering y QA"` en discovery o los nombres de los miembros del equipo (`teamMemory.snapshot(teamId)`). Si `recordDecision` lanza `IllegalStateException`, devolver `"No arrancó otra ronda: " + mensaje`. Aplicarlo en los dos lugares que llaman a `recordDecision` (~644 y ~866).
  - `formatMissionStatus`: si `evidenceRound > 0` o hay pedidos, agregar `"Ronda de evidencia " + r + " de " + max + " (queda " + (max - r) + ")."` y reemplazar la línea de tareas por grupos `"Ronda 0: " + tareas` / `"Ronda k (pedido: \"" + pedido[k-1] + "\"): " + tareas`, agrupando con `TaskIds.roundOf(t.taskId())`. Sin rondas, la salida no cambia.
  - `formatCompanyStatus`: contar misiones activas (no terminales) con `missionMemory.evidenceRound(id) > 0` y, si hay alguna, agregar `", " + n + " re-ejecutándose por más evidencia"` a la línea de misiones.
- [ ] **Step 4:** `mvn -q test -Dtest=ChatIntentRouterTest` → PASS; `mvn -q test` → verde.
- [ ] **Step 5: Commit** `git commit -m "Chat: rondas de evidencia visibles (arranque, límite, rondas agrupadas y status)"`

---

### Task 7: Documentación y verificación en vivo por el chat

**Files:** `CLAUDE.md`, `docs/HISTORY.md`, `docs/EVENTS.md`, `docs/superpowers/specs/2026-09-16-evidence-rounds-design.md` (línea de `evidenceRound` en `AgentTask`: se deriva del sufijo con `TaskIds.roundOf`, sin propiedad nueva).

- [ ] **Step 1:** `CLAUDE.md`: en "Flujo de misión" punto 7, `REQUEST_MORE_EVIDENCE` re-ejecuta (ronda `-R<n>`, límite `MAX_EVIDENCE_ROUNDS`, también sobre `FAILED`); en Financial Policies, catálogo de 8; en Chat, lo de las rondas. `docs/EVENTS.md`: `EMPRESA_MISSION_EVIDENCE_ROUND_STARTED` (`missionId`, `evidenceRound`, `reasoning`).
- [ ] **Step 2 (en vivo, por el chat, tras redeploy seguro):** (a) una misión de discovery `TEST` hasta `AWAITING_INVESTOR`; en el chat "pide más evidencia sobre MISSION-… : <pedido>" → "Arrancó la ronda 1 de 2…"; seguir con "¿cómo va MISSION-…?" hasta `AWAITING_INVESTOR` (tareas `-R1`, pedido visible); pedir otra (ronda 2) y una tercera → rechazo "2 de 2". (b) una misión de Engineering `TEST` (`VERIFIED`) → pedir más evidencia con un cambio concreto → ronda 1 sobre el mismo repo (commits nuevos con `Forjai-Task …-R1`, sin commit de scaffold nuevo), sandbox otra vez, "¿cómo va?" con ambas rondas. (c) "dame un status" durante una ronda.
- [ ] **Step 3:** `docs/HISTORY.md`: decisiones del fundador, bugs reales y la verificación en vivo.
- [ ] **Step 4: Commit** `git commit -m "Documentar rondas de evidencia v2 y su verificación en vivo"`

---

## Self-review

- **Cobertura del spec**: límite como policy → T1/T2; FAILED → T2; reparto del CEO con fallback → T3; discovery y análisis con plan reutilizado → T4; Engineering en el mismo repo → T5; ids ronda 0 / `-R<n>` → T1/T4/T5; evento Kafka → T4/T7; chat (arranque, límite, "¿cómo va?", status, correo) → T6 (el correo ya sale de `advanceMission`); verificación en vivo por el chat → T7.
- **Tipos**: `TaskIds.agentTask/planTask/roundOf` (T1) usados en T4/T5/T6; `reexecuteAsync(String,String,int,String)` (T2) implementado en T4; `TeamMissionContext.round()` (T4) usado en T5; `DecisionResponse.evidenceRound()` (T2) usado en T6.
- **Decisiones del fundador** (spec, revisión 2026-09-27): todas las misiones, policy, FAILED re-ejecuta, chat como ventana principal.
