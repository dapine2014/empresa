# Bitácora de misión — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** que cada misión tenga una bitácora persistente de lo que pasa en desarrollo, que el chat (Neo, Aria y las
preguntas de estado) y el Command Center la lean, y que Neo la reciba al planificar.

**Architecture:**
- `CompanyEventPublisher` es el único punto por donde sale un evento. Desde ahí, antes de mandarlo a Kafka, guarda cada
  evento con `missionId` como `(:Mission)-[:HAS_EVENT]->(:MissionEvent)` (`MissionEventLog`), con un resumen en español
  armado en Java (`MissionEventText`).
- Se publican los eventos que hoy solo quedan en el log del contenedor.
- `AgentActivity` (puro) arma el estado real de un agente.
- `ChatIntentRouter` responde en Java las preguntas de estado y les antepone los datos reales a las menciones.
- `MissionEventDigest` (puro) resume un incremento para el plan de Neo.
- La API y `MissionDetailPage` muestran la línea de tiempo.

**Tech Stack:** Spring Boot 4.1.1, Java 21, Neo4j (driver plano), Jackson 3 (`tools.jackson.*`), JUnit 5 + Mockito,
React + TS + @tanstack/react-query (oxlint).

**Spec:** `docs/superpowers/specs/2026-10-17-bitacora-de-mision-design.md`

## Global Constraints

**Convenciones generales del proyecto**
- Determinismo: todo lo que se puede decidir en Java se decide en Java. Ningún texto de la bitácora ni respuesta de
  estado sale de un modelo.
- Jackson 3: `tools.jackson.*`, nunca `com.fasterxml.jackson.*` (salvo las anotaciones `com.fasterxml.jackson.annotation`
  que ya usa el proyecto).
- Sin `@Async`.
- Kafka sigue siendo solo productor. Todo `eventType` empieza por `EMPRESA_`.
- Neo4j: driver plano y Cypher a mano en un `*MemoryService` o servicio de memoria. Los `*MemoryService` no tienen test
  directo; se prueba la lógica pura y se mockea el servicio.
- Ningún controller agrega manejo fino de errores.
- Comentarios y textos en español, con la densidad de comentarios del código vecino. Cada decisión nueva cita
  «bitácora (spec 2026-10-17)».
- Nunca stagear `.superpowers/`. Cada commit termina con `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

**Reglas de la bitácora (valores exactos del spec)**
- Tope de 500 eventos por misión.
- `summary` de 300 caracteres como máximo.
- `reasons` de `EMPRESA_TASK_ATTEMPT_REJECTED`: las 3 primeras, de 200 caracteres como máximo cada una.
- `MissionEventLog.record` nunca lanza. Si falla, loguea `MISSION_EVENT_LOG_FAILED` y el evento igual sale a Kafka.
- En el chat las horas van en la hora local del fundador: UTC-5 (`ZoneOffset.ofHours(-5)`), formato `HH:mm`.

**Misiones**
- Misiones vivas: estado que no es `COMPLETED`, `CANCELLED`, `FAILED` ni `INTERRUPTED`.
- `BLOCKED` y `AWAITING_*` cuentan como «detenida, esperando al fundador».

**Ids de agentes (roster real)**

| Agente | id |
|---|---|
| Neo | `engineering` |
| Aria | `product-owner` |
| Iris | `backend` |
| Vera | `qa` |
| Kael | `interaction-design` |
| Mila | `frontend-ui` |
| Diego | `devops` |

## Review Focus

1. **Neo4j falla al escribir el evento.** `publish` igual manda a Kafka y no lanza → test en Task 2.
2. **Mención a un agente con tareas en dos misiones vivas.** `AgentActivity.describe` lista las dos, la más reciente
   primero → test en Task 4.
3. **Mención sin misiones vivas, con el historial nombrando una misión borrada.** Responde «Libre» y no nombra la misión
   borrada → test en Task 5.
4. **Más de 500 eventos.** El Cypher de `record` borra los más viejos en la misma transacción. Como los
   `*MemoryService` no tienen test directo, se fija por inspección en la revisión de la Task 2 y en vivo; queda anotado
   en el ledger.
5. **«¿qué hace Iris?» sin @.** Se resuelve el nombre contra el roster y responde Java → test en Task 5.

---

### Task 1: `MissionEventText` — el resumen en español de cada evento

**Files:**
- Create: `app/src/main/java/com/aicompany/core/event/MissionEventText.java`
- Test: `app/src/test/java/com/aicompany/core/event/MissionEventTextTest.java`

**Interfaces:**
- Produces:
  - `public static String summary(String eventType, String agentName, Map<String, Object> data)`: devuelve como mucho
    300 caracteres y nunca null.
  - `public static final int MAX_SUMMARY = 300;`

- [ ] **Step 1: Write the failing test**

```java
package com.aicompany.core.event;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Bitácora (spec 2026-10-17): Java arma el resumen de cada evento, nunca un modelo. */
class MissionEventTextTest {

    @Test void missionUpdatedShowsStatusStepAndMessage() {
        assertEquals("Misión → WAITING_AGENT_RESULTS: Desarrollo por capas — Kael (2/3) está generando [game]",
                MissionEventText.summary("EMPRESA_MISSION_UPDATED", "Alex", Map.of("status", "WAITING_AGENT_RESULTS",
                        "progress", 56, "currentStep", "Desarrollo por capas",
                        "message", "Kael (2/3) está generando [game]")));
    }

    @Test void taskCommittedShowsFilesAndRepairRound() {
        assertEquals("Kael commiteó 1 archivo (game/Main.cs) — reparación 2",
                MissionEventText.summary("EMPRESA_TASK_COMMITTED", "Kael",
                        Map.of("commitSha", "abc", "files", List.of("game/Main.cs"), "repairRound", 2)));
        assertEquals("Iris commiteó 3 archivos (a.cs, b.cs, c.cs)",
                MissionEventText.summary("EMPRESA_TASK_COMMITTED", "Iris",
                        Map.of("files", List.of("a.cs", "b.cs", "c.cs"))));
        assertEquals("Vera commiteó 5 archivos (a, b, c…)",
                MissionEventText.summary("EMPRESA_TASK_COMMITTED", "Vera",
                        Map.of("files", List.of("a", "b", "c", "d", "e"))));
    }

    @Test void sandboxEvents() {
        assertEquals("Sandbox arrancó (vuelta 1)",
                MissionEventText.summary("EMPRESA_SANDBOX_STARTED", "sandbox", Map.of("round", 1, "commitSha", "x")));
        assertEquals("Sandbox FAIL en build: 7 errores de compilación",
                MissionEventText.summary("EMPRESA_SANDBOX_FINISHED", "sandbox", Map.of("overall", "FAIL",
                        "failedStep", "build", "testsPassed", 0, "testsFailed", 0, "compileErrors", 7, "round", 0)));
        assertEquals("Sandbox FAIL en test: 27 tests OK, 4 fallan",
                MissionEventText.summary("EMPRESA_SANDBOX_FINISHED", "sandbox", Map.of("overall", "FAIL",
                        "failedStep", "test", "testsPassed", 27, "testsFailed", 4, "compileErrors", 0, "round", 1)));
        assertEquals("Sandbox PASS: 25 tests",
                MissionEventText.summary("EMPRESA_SANDBOX_VERIFICATION_COMPLETED", "sandbox", Map.of("overall", "PASS",
                        "testsPassed", 25, "testsFailed", 0)));
    }

    @Test void correctionRepairTriageRejectionAndModel() {
        assertEquals("Corrección automática ronda 18",
                MissionEventText.summary("EMPRESA_MISSION_CORRECTION_STARTED", "system", Map.of("round", 18)));
        assertEquals("Errores de compilación devueltos a Vera: 4 (2 REPETIDOS) en ESC_07.cs",
                MissionEventText.summary("EMPRESA_REPAIR_REQUESTED", "Vera",
                        Map.of("errors", 4, "repeated", 2, "files", List.of("ESC_07.cs"))));
        assertEquals("Triage de Neo: ESC_04_x → TEST_WRONG",
                MissionEventText.summary("EMPRESA_TEST_TRIAGE_COMPLETED", "Neo",
                        Map.of("verdicts", List.of("ESC_04_x → TEST_WRONG"))));
        assertEquals("Triage de Neo falló: NullPointerException",
                MissionEventText.summary("EMPRESA_TEST_TRIAGE_COMPLETED", "Neo", Map.of("error", "NullPointerException")));
        assertEquals("Intento 2 de Kael rechazado: falta X; falta Y",
                MissionEventText.summary("EMPRESA_TASK_ATTEMPT_REJECTED", "Kael",
                        Map.of("attempt", 2, "reasons", List.of("falta X", "falta Y"))));
        assertEquals("Modelo de Aria: nvidia:a → bedrock:b (PAID)",
                MissionEventText.summary("EMPRESA_MODEL_SWITCHED", "Aria",
                        Map.of("operation", "X", "from", "nvidia:a", "to", "bedrock:b", "kind", "PAID")));
    }

    @Test void unknownTypesUseTheGenericTemplateAndEverythingIsCapped() {
        assertEquals("REQUIREMENTS APPROVED por human",
                MissionEventText.summary("EMPRESA_REQUIREMENTS_APPROVED", "human", Map.of("version", 1)));
        var longMessage = "x".repeat(1000);
        var text = MissionEventText.summary("EMPRESA_MISSION_UPDATED", "Alex",
                Map.of("status", "PLANNING", "currentStep", "Plan", "message", longMessage));
        assertEquals(MissionEventText.MAX_SUMMARY, text.length());
        assertTrue(text.endsWith("…"));
        assertEquals("TASK RETRY por Kael", MissionEventText.summary("EMPRESA_TASK_RETRY", "Kael", null));
        assertEquals("Misión → PLANNING", MissionEventText.summary("EMPRESA_MISSION_UPDATED", null,
                Map.of("status", "PLANNING")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd app && mvn -q test -Dtest=MissionEventTextTest`
Expected: FAIL de compilación («cannot find symbol MissionEventText»).

- [ ] **Step 3: Write minimal implementation**

```java
package com.aicompany.core.event;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bitácora (spec 2026-10-17): el resumen en español de cada evento, armado por Java (nunca por un modelo). */
public final class MissionEventText {

    public static final int MAX_SUMMARY = 300;

    private MissionEventText() {
    }

    public static String summary(String eventType, String agentName, Map<String, Object> data) {
        var d = data == null ? Map.<String, Object>of() : data;
        var who = agentName == null || agentName.isBlank() ? "Forjai" : agentName;
        var text = switch (eventType == null ? "" : eventType) {
            case "EMPRESA_MISSION_UPDATED" -> "Misión → " + str(d, "status")
                    + (str(d, "currentStep").isEmpty() ? "" : ": " + str(d, "currentStep"))
                    + (str(d, "message").isEmpty() ? "" : " — " + str(d, "message"));
            case "EMPRESA_TASK_COMMITTED" -> {
                var files = list(d, "files");
                var shown = files.size() <= 3 ? String.join(", ", files) : String.join(", ", files.subList(0, 3)) + "…";
                yield who + " commiteó " + files.size() + (files.size() == 1 ? " archivo" : " archivos")
                        + (files.isEmpty() ? "" : " (" + shown + ")")
                        + (d.get("repairRound") == null ? "" : " — reparación " + d.get("repairRound"))
                        + (d.get("testTriageRound") == null ? "" : " — corrección del triage " + d.get("testTriageRound"));
            }
            case "EMPRESA_SANDBOX_STARTED" -> "Sandbox arrancó (vuelta " + d.getOrDefault("round", 0) + ")";
            case "EMPRESA_SANDBOX_FINISHED", "EMPRESA_SANDBOX_VERIFICATION_COMPLETED" -> sandbox(d);
            case "EMPRESA_MISSION_CORRECTION_STARTED" -> "Corrección automática ronda " + d.get("round");
            case "EMPRESA_REPAIR_REQUESTED" -> "Errores de compilación devueltos a " + who + ": " + d.get("errors")
                    + (num(d, "repeated") > 0 ? " (" + d.get("repeated") + " REPETIDOS)" : "")
                    + (list(d, "files").isEmpty() ? "" : " en " + String.join(", ", list(d, "files")));
            case "EMPRESA_TEST_TRIAGE_COMPLETED" -> d.get("error") != null
                    ? "Triage de " + who + " falló: " + d.get("error")
                    : "Triage de " + who + ": " + String.join("; ", list(d, "verdicts"));
            case "EMPRESA_TASK_ATTEMPT_REJECTED" -> "Intento " + d.get("attempt") + " de " + who + " rechazado: "
                    + String.join("; ", list(d, "reasons"));
            case "EMPRESA_MODEL_SWITCHED" -> "Modelo de " + who + ": " + str(d, "from") + " → " + str(d, "to")
                    + " (" + str(d, "kind") + ")";
            default -> (eventType == null ? "EVENTO" : eventType.replaceFirst("^EMPRESA_", "").replace('_', ' '))
                    + " por " + who;
        };
        return text.length() <= MAX_SUMMARY ? text : text.substring(0, MAX_SUMMARY - 1) + "…";
    }

    private static String sandbox(Map<String, Object> d) {
        var overall = str(d, "overall");
        if ("PASS".equals(overall)) {
            return "Sandbox PASS: " + d.getOrDefault("testsPassed", 0) + " tests";
        }
        var step = str(d, "failedStep");
        var head = "Sandbox " + overall + (step.isEmpty() ? "" : " en " + step);
        if (num(d, "compileErrors") > 0) {
            return head + ": " + d.get("compileErrors") + " errores de compilación";
        }
        if (d.get("testsPassed") != null || d.get("testsFailed") != null) {
            return head + ": " + d.getOrDefault("testsPassed", 0) + " tests OK, " + d.getOrDefault("testsFailed", 0)
                    + " fallan";
        }
        return head;
    }

    private static String str(Map<String, Object> d, String key) {
        var v = d.get(key);
        return v == null ? "" : String.valueOf(v).strip();
    }

    private static long num(Map<String, Object> d, String key) {
        return d.get(key) instanceof Number n ? n.longValue() : 0;
    }

    private static List<String> list(Map<String, Object> d, String key) {
        return d.get(key) instanceof List<?> l ? l.stream().filter(Objects::nonNull).map(String::valueOf).toList()
                : List.of();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd app && mvn -q test -Dtest=MissionEventTextTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/event/MissionEventText.java app/src/test/java/com/aicompany/core/event/MissionEventTextTest.java
git commit -m "Bitácora: resumen en español de cada evento (MissionEventText)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: `MissionEventLog` — guardar y leer la bitácora desde el publicador

**Files:**
- Create: `app/src/main/java/com/aicompany/core/event/MissionEventLog.java`
- Create: `app/src/main/java/com/aicompany/core/model/MissionEventView.java`
- Modify: `app/src/main/java/com/aicompany/core/event/CompanyEventPublisher.java` (setter opcional + llamada en `publish`)
- Modify: `app/src/main/java/com/aicompany/core/service/CompanyMemoryService.java:56-58` (constraint)
- Modify: `app/src/main/java/com/aicompany/core/service/MissionMemoryService.java:507` (borrado en cascada: agregar `HAS_EVENT`)
- Test: `app/src/test/java/com/aicompany/core/event/CompanyEventPublisherTest.java` (nuevo)

**Interfaces:**
- Consumes: `MissionEventText.summary(String, String, Map<String,Object>)` (Task 1).
- Produces:
  - `public record MissionEventView(String id, String type, java.time.Instant at, String agentId, String agentName,
    String taskId, String summary, Integer round, Integer increment)` en `com.aicompany.core.model`.
  - `MissionEventLog` (`@Service`, constructor `(Driver driver, CompanyMemoryService companyMemory)`):
    - `void record(CompanyEvent event)`: nunca lanza.
    - `List<MissionEventView> recent(String missionId, int limit)`: del más nuevo al más viejo.
    - `List<MissionEventView> recentAll(int limit)`: de todas las misiones, para Activity.
    - `static Integer roundOf(Map<String,Object> data)` e `static Integer incrementOf(Map<String,Object> data)`.
    - `public static final int MAX_EVENTS_PER_MISSION = 500;`
  - `CompanyEventPublisher.setMissionEventLog(MissionEventLog log)`.

- [ ] **Step 1: Write the failing test**

```java
package com.aicompany.core.event;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Bitácora (spec 2026-10-17): el publicador guarda el evento antes de Kafka y una falla de Neo4j no lo frena. */
class CompanyEventPublisherTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final MissionEventLog log = mock(MissionEventLog.class);
    private final CompanyEventPublisher publisher = new CompanyEventPublisher(kafka, JsonMapper.builder().build(), "EMPRESA_EVENTS");

    {
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(new CompletableFuture<>());
        publisher.setMissionEventLog(log);
    }

    @Test void aMissionEventIsRecordedAndSent() {
        publisher.publish("EMPRESA_SANDBOX_STARTED", "M-1", null, "sandbox", Map.of("round", 0));
        verify(log).record(argThat(e -> "M-1".equals(e.missionId()) && "EMPRESA_SANDBOX_STARTED".equals(e.eventType())));
        verify(kafka).send(eq("EMPRESA_EVENTS"), eq("M-1"), anyString());
    }

    // Review Focus 1: Neo4j caído no frena el evento.
    @Test void aLogFailureStillSendsToKafka() {
        doThrow(new RuntimeException("neo4j down")).when(log).record(any());
        assertDoesNotThrow(() -> publisher.publish("EMPRESA_TASK_COMMITTED", "M-1", "T-1", "qa", Map.of()));
        verify(kafka).send(eq("EMPRESA_EVENTS"), eq("M-1"), anyString());
    }

    @Test void eventsWithoutMissionAreNotRecorded() {
        publisher.publish("EMPRESA_MODEL_DOWN", null, null, "system", Map.of("model", "x"));
        verify(log, never()).record(any());
        verify(kafka).send(eq("EMPRESA_EVENTS"), anyString(), anyString());
    }

    @Test void roundAndIncrementComeFromTheData() {
        assertEquals(3, MissionEventLog.roundOf(Map.of("round", 3)));
        assertEquals(2, MissionEventLog.roundOf(Map.of("repairRound", 2)));
        assertNull(MissionEventLog.roundOf(Map.of()));
        assertEquals(2, MissionEventLog.incrementOf(Map.of("increment", 2)));
        assertNull(MissionEventLog.incrementOf(Map.of("increment", "x")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd app && mvn -q test -Dtest=CompanyEventPublisherTest`
Expected: FAIL de compilación («cannot find symbol MissionEventLog / setMissionEventLog»).

- [ ] **Step 3: Write the implementation**

Crear `MissionEventView`:

```java
package com.aicompany.core.model;

import java.time.Instant;

/** Bitácora (spec 2026-10-17): un evento de la misión tal como lo guarda Java (resumen armado por MissionEventText). */
public record MissionEventView(String id, String type, Instant at, String agentId, String agentName, String taskId,
                               String summary, Integer round, Integer increment) {
}
```

Crear `MissionEventLog`. `at` se guarda como texto ISO, igual que el `updatedAt` que ya lee `ActivityMemoryService`.

```java
package com.aicompany.core.event;

import com.aicompany.core.model.MissionEventView;
import com.aicompany.core.service.CompanyMemoryService;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bitácora (spec 2026-10-17): cada evento EMPRESA_* con misión queda en (:Mission)-[:HAS_EVENT]->(:MissionEvent), con
 * los últimos 500 por misión. Nunca frena el evento: si Neo4j falla, se loguea y Kafka lo recibe igual.
 */
@Service
public class MissionEventLog {

    private static final Logger log = LoggerFactory.getLogger(MissionEventLog.class);
    public static final int MAX_EVENTS_PER_MISSION = 500;

    private final Driver driver;
    private final CompanyMemoryService companyMemory;

    public MissionEventLog(Driver driver, CompanyMemoryService companyMemory) {
        this.driver = driver;
        this.companyMemory = companyMemory;
    }

    public void record(CompanyEvent event) {
        if (event == null || event.missionId() == null) {
            return;
        }
        try {
            var name = agentName(event.agentId());
            var params = new HashMap<String, Object>();
            params.put("mission", event.missionId());
            params.put("id", event.eventId());
            params.put("type", event.eventType());
            params.put("at", event.timestamp().toString());
            params.put("agentId", event.agentId());
            params.put("taskId", event.taskId());
            params.put("summary", MissionEventText.summary(event.eventType(), name, event.data()));
            params.put("round", roundOf(event.data()));
            params.put("increment", incrementOf(event.data()));
            params.put("max", MAX_EVENTS_PER_MISSION);
            try (var session = driver.session()) {
                session.executeWrite(tx -> {
                    tx.run("MATCH (m:Mission {id:$mission}) CREATE (m)-[:HAS_EVENT]->(:MissionEvent {id:$id, "
                            + "missionId:$mission, type:$type, at:$at, agentId:$agentId, taskId:$taskId, "
                            + "summary:$summary, round:$round, increment:$increment})", params).consume();
                    // Review Focus 4: tope por misión, en la misma transacción.
                    tx.run("MATCH (:Mission {id:$mission})-[:HAS_EVENT]->(e:MissionEvent) WITH e ORDER BY e.at DESC "
                            + "SKIP $max DETACH DELETE e", params).consume();
                    return null;
                });
            }
        } catch (Exception ex) {
            log.warn("MISSION_EVENT_LOG_FAILED mission={} type={} error={}", event.missionId(), event.eventType(),
                    ex.getMessage());
        }
    }

    public List<MissionEventView> recent(String missionId, int limit) {
        try (var session = driver.session()) {
            return session.run("MATCH (:Mission {id:$id})-[:HAS_EVENT]->(e:MissionEvent) RETURN e ORDER BY e.at DESC "
                    + "LIMIT $limit", Map.of("id", missionId, "limit", limit)).list(this::view);
        }
    }

    public List<MissionEventView> recentAll(int limit) {
        try (var session = driver.session()) {
            return session.run("MATCH (e:MissionEvent) RETURN e ORDER BY e.at DESC LIMIT $limit",
                    Map.of("limit", limit)).list(this::view);
        }
    }

    private MissionEventView view(Record r) {
        var e = r.get("e");
        var agentId = e.get("agentId").isNull() ? null : e.get("agentId").asString();
        return new MissionEventView(e.get("id").asString(), e.get("type").asString(),
                Instant.parse(e.get("at").asString()), agentId, agentName(agentId),
                e.get("taskId").isNull() ? null : e.get("taskId").asString(), e.get("summary").asString(),
                e.get("round").isNull() ? null : e.get("round").asInt(),
                e.get("increment").isNull() ? null : e.get("increment").asInt());
    }

    private String agentName(String agentId) {
        if (agentId == null) {
            return null;
        }
        try {
            return companyMemory.agents().stream().filter(a -> agentId.equals(a.get("id")))
                    .map(a -> String.valueOf(a.get("name"))).findFirst().orElse(agentId);
        } catch (Exception ex) {
            return agentId;
        }
    }

    public static Integer roundOf(Map<String, Object> data) {
        if (data == null) {
            return null;
        }
        for (var key : List.of("round", "repairRound", "testTriageRound")) {
            if (data.get(key) instanceof Number n) {
                return n.intValue();
            }
        }
        return null;
    }

    public static Integer incrementOf(Map<String, Object> data) {
        return data != null && data.get("increment") instanceof Number n ? n.intValue() : null;
    }
}
```

En `CompanyEventPublisher` agregar el campo, el setter y la llamada al principio de `publish`, después de crear `event`
y antes de serializar:

```java
    private MissionEventLog missionEventLog;

    /** Bitácora (spec 2026-10-17). Setter opcional: sin él, el publicador sigue como antes. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMissionEventLog(MissionEventLog missionEventLog) {
        this.missionEventLog = missionEventLog;
    }
```

```java
        if (missionEventLog != null && missionId != null) {
            try {
                missionEventLog.record(event);
            } catch (RuntimeException ex) {
                log.warn("MISSION_EVENT_LOG_FAILED mission={} type={} error={}", missionId, eventType, ex.getMessage());
            }
        }
```

En `CompanyMemoryService`, junto a las otras constraints (línea ~58):

```java
            session.run("CREATE CONSTRAINT mission_event_id IF NOT EXISTS FOR (e:MissionEvent) REQUIRE e.id IS UNIQUE").consume();
```

En `MissionMemoryService` (borrado, línea ~507), agregar `HAS_EVENT` a la lista y cambiar el comentario a
«…2026-10-16: el contrato; 2026-10-17: la bitácora.»:

```java
                                "OPTIONAL MATCH (m)-[:HAS_TASK|HAS_DECISION|HAS_OPPORTUNITY|HAS_REQUIREMENTS|HAS_CONTRACT"
                                + "|HAS_INTERACTION_DESIGN|HAS_JOINT_REVIEW|HAS_EVENT]->(n) " +
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd app && mvn -q test -Dtest=CompanyEventPublisherTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/event/MissionEventLog.java app/src/main/java/com/aicompany/core/model/MissionEventView.java app/src/main/java/com/aicompany/core/event/CompanyEventPublisher.java app/src/main/java/com/aicompany/core/service/CompanyMemoryService.java app/src/main/java/com/aicompany/core/service/MissionMemoryService.java app/src/test/java/com/aicompany/core/event/CompanyEventPublisherTest.java
git commit -m "Bitácora: cada evento con misión queda en Neo4j (MissionEventLog), sin frenar a Kafka

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Los eventos que faltan

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java` (sandbox por vuelta, reparación, triage y evento final)
- Modify: `app/src/main/java/com/aicompany/core/agent/DevelopmentRuntime.java:270` (intento rechazado)
- Modify: `app/src/main/java/com/aicompany/core/service/CeoService.java:1842,1862,1875` (cambio de modelo)
- Modify: `docs/EVENTS.md`
- Test: `app/src/test/java/com/aicompany/core/service/DevelopmentTeamStrategyTest.java`, `app/src/test/java/com/aicompany/core/agent/DevelopmentRuntimeTest.java`, `app/src/test/java/com/aicompany/core/service/CeoServiceTest.java` (los existentes; agregar casos)

**Interfaces:**
- Consumes: `CompanyEventPublisher.publish(type, missionId, taskId, agentId, data)`,
  `ModelCallContext.current()` (`missionId()`, `taskId()`), `DevelopmentTeamStrategy.repairKey` y el set
  `previousCompileErrors` (ya existen).
- Produces: los tipos de evento y las claves de `data` de la tabla. `MissionEventText` (Task 1) ya los conoce; Tasks 5
  y 6 los leen por tipo.

| Tipo | data |
|---|---|
| `EMPRESA_SANDBOX_STARTED` | `round` (int, la vuelta del ciclo), `commitSha` |
| `EMPRESA_SANDBOX_FINISHED` | `round`, `overall`, `failedStep` (`""` si pasó), `testsPassed`, `testsFailed`, `compileErrors` (int) |
| `EMPRESA_REPAIR_REQUESTED` | `errors` (int), `repeated` (int), `files` (lista de nombres de archivo, sin carpetas, sin repetidos) |
| `EMPRESA_TEST_TRIAGE_COMPLETED` | `verdicts` (lista `"<test> → <VERDICT>"`), o bien `error` (texto) |
| `EMPRESA_TASK_ATTEMPT_REJECTED` | `attempt` (int, 1-based), `reasons` (las 3 primeras, de 200 caracteres como máximo cada una) |
| `EMPRESA_MODEL_SWITCHED` | `operation`, `from`, `to`, `kind` (`FREE` \| `PAID` \| `LOCAL`) |

Agentes de cada evento:
- `EMPRESA_REPAIR_REQUESTED`: el agente dueño.
- `EMPRESA_TEST_TRIAGE_COMPLETED`: el líder (`leader`).
- `EMPRESA_SANDBOX_*`: `"sandbox"`.
- `EMPRESA_MODEL_SWITCHED`: el `actor`.

Ruling sobre el spec: el spec nombra solo `SANDBOX_STARTED` y pide verificar que el evento final traiga el paso que
falló. El evento final sale una sola vez, después de todas las vueltas. Por eso se agrega `EMPRESA_SANDBOX_FINISHED`
por vuelta, que es el que da en la bitácora «terminó y en qué paso falló». Además, el evento final
(`EMPRESA_SANDBOX_VERIFICATION_COMPLETED`, líneas ~496 y ~719) suma `failedStep` y `compileErrors`.

- [ ] **Step 1: Write the failing tests**

En `DevelopmentTeamStrategyTest`, siguiendo el estilo de los tests existentes que corren un incremento con el sandbox
mockeado (buscar el que verifica `"EMPRESA_SANDBOX_VERIFICATION_COMPLETED"` o el que hace una reparación de compilación
y copiar su arreglo), agregar:

```java
    // Bitácora (spec 2026-10-17): cada vuelta del sandbox y cada reparación quedan como evento.
    @Test
    void eachSandboxRoundAndEachRepairArePublished() {
        // Arreglo: el mismo de un test existente donde el primer sandbox falla en build con un error en un archivo de un
        // agente y el segundo pasa (p. ej. el que prueba MAX_REPAIR_ROUNDS o «la corrección vuelve al dueño»).
        // ... (copiar el arreglo del test elegido) ...
        verify(events).publish(eq("EMPRESA_SANDBOX_STARTED"), eq(MISSION), isNull(), eq("sandbox"),
                argThat(d -> Integer.valueOf(0).equals(d.get("round"))));
        verify(events).publish(eq("EMPRESA_SANDBOX_FINISHED"), eq(MISSION), isNull(), eq("sandbox"),
                argThat(d -> "FAIL".equals(d.get("overall")) && "build".equals(d.get("failedStep"))
                        && ((Integer) d.get("compileErrors")) >= 1));
        verify(events).publish(eq("EMPRESA_REPAIR_REQUESTED"), eq(MISSION), anyString(), anyString(),
                argThat(d -> ((Integer) d.get("errors")) >= 1 && Integer.valueOf(0).equals(d.get("repeated"))));
        verify(events).publish(eq("EMPRESA_SANDBOX_STARTED"), eq(MISSION), isNull(), eq("sandbox"),
                argThat(d -> Integer.valueOf(1).equals(d.get("round"))));
    }
```

Si el test copiado usa otros nombres para el id de la misión o para el mock del publicador (`MISSION`, `events`), usar
esos.

Para el triage, junto al test existente que ejercita `triageAndRepair` (buscar `TEST_TRIAGE`):

```java
    @Test
    void theTriageVerdictIsPublished() {
        // Arreglo: el del test existente del triage con un veredicto CODE_WRONG válido.
        // ...
        verify(events).publish(eq("EMPRESA_TEST_TRIAGE_COMPLETED"), eq(MISSION), anyString(), anyString(),
                argThat(d -> d.get("verdicts") instanceof List<?> l && !l.isEmpty()
                        && String.valueOf(l.get(0)).contains("CODE_WRONG")));
    }
```

En `DevelopmentRuntimeTest`, junto al test existente de un primer intento con una falla retryable y un segundo que pasa:

```java
    // Bitácora (spec 2026-10-17): el rechazo con reintento queda en la bitácora con sus motivos.
    @Test
    void aRejectedAttemptIsPublishedWithItsReasons() {
        // Arreglo: el del test existente (primer intento rechazado por un gate, segundo válido).
        // ...
        verify(events).publish(eq("EMPRESA_TASK_ATTEMPT_REJECTED"), anyString(), anyString(), anyString(),
                argThat(d -> Integer.valueOf(1).equals(d.get("attempt"))
                        && d.get("reasons") instanceof List<?> l && !l.isEmpty() && l.size() <= 3
                        && l.stream().allMatch(r -> String.valueOf(r).length() <= 200)));
    }
```

En `CeoServiceTest`, junto al test existente del respaldo gratis (`MODEL_FREE_FALLBACK`, la cadena gratis):

```java
    // Bitácora (spec 2026-10-17): el cambio de modelo dentro de una misión queda en la bitácora.
    @Test
    void aModelSwitchInsideAMissionIsPublished() {
        // Arreglo: el del test existente donde el modelo gratis cae y responde el siguiente de la cadena.
        // Envolver la llamada en ModelCallContext.with("M-1", "T-1", () -> ...).
        // ...
        verify(events).publish(eq("EMPRESA_MODEL_SWITCHED"), eq("M-1"), eq("T-1"), anyString(),
                argThat(d -> "FREE".equals(d.get("kind")) && d.get("from") != null && d.get("to") != null));
    }

    @Test
    void aModelSwitchOutsideAMissionIsNotPublished() {
        // El mismo arreglo, sin ModelCallContext.
        // ...
        verify(events, never()).publish(eq("EMPRESA_MODEL_SWITCHED"), any(), any(), any(), any());
    }
```

Si alguno de esos arreglos no existe tal cual, construir el mínimo con los mismos mocks que usa el archivo, y anotarlo
en el reporte.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd app && mvn -q test -Dtest='DevelopmentTeamStrategyTest,DevelopmentRuntimeTest,CeoServiceTest'`
Expected: FAIL en los tests nuevos («Wanted but not invoked: publish("EMPRESA_SANDBOX_STARTED"…)»).

- [ ] **Step 3: Write the implementation**

`DevelopmentTeamStrategy`, en el ciclo de corrección (`for (int round = 0; ; round++)`):

Antes de `var verified = verifyComponents(missionId, headSha, components);`:

```java
            events.publish("EMPRESA_SANDBOX_STARTED", missionId, null, "sandbox",
                    Map.of("round", round, "commitSha", headSha));
```

Después de calcular `sandboxResult`/`sandboxError`, antes de `if (sandboxResult == null || sandboxResult.passed())`:

```java
            if (sandboxResult != null) {
                events.publish("EMPRESA_SANDBOX_FINISHED", missionId, null, "sandbox", sandboxData(sandboxResult, round));
            }
```

Un helper estático en la misma clase (`compileErrors(SandboxResult)` ya existe):

```java
    /** Bitácora (spec 2026-10-17): resultado de una vuelta del sandbox, con el primer paso que falló. */
    static Map<String, Object> sandboxData(SandboxResult result, int round) {
        var failed = result.stepsOrEmpty().stream()
                .filter(s -> "FAIL".equals(s.status()) || "TIMEOUT".equals(s.status()))
                .map(s -> ComponentSandbox.stepName(s.name())).findFirst().orElse("");
        var data = new java.util.LinkedHashMap<String, Object>();
        data.put("round", round);
        data.put("overall", result.overall());
        data.put("failedStep", failed);
        data.put("testsPassed", result.testsPassed());
        data.put("testsFailed", result.testsFailed());
        data.put("compileErrors", compileErrors(result).size());
        return data;
    }
```

En el bucle de reparación por dueño (`for (var entry : errorsByTask.entrySet())`), después de calcular `id` y antes del
`try`:

```java
                var repeatedCount = (int) entry.getValue().stream()
                        .filter(e -> previousCompileErrors.contains(repairKey(e))).count();
                events.publish("EMPRESA_REPAIR_REQUESTED", missionId, id, task.agentId(), Map.of(
                        "errors", entry.getValue().size(), "repeated", repeatedCount,
                        "files", entry.getValue().stream().map(e -> e.path().substring(e.path().lastIndexOf('/') + 1))
                                .distinct().toList()));
```

En `triageAndRepair`, justo después de `var triage = runtime.triage(...).join();`:

```java
            events.publish("EMPRESA_TEST_TRIAGE_COMPLETED", missionId, triageId, leader, Map.of("verdicts",
                    triage.verdictsOrEmpty().stream().filter(Objects::nonNull)
                            .map(v -> v.test() + " → " + v.verdict()).toList()));
```

En el `catch (Exception ex)` final de `triageAndRepair` (el que agrega «no se completó»):

```java
            events.publish("EMPRESA_TEST_TRIAGE_COMPLETED", missionId, triageId, leader,
                    Map.of("error", safeMessage(ex, "sin detalle")));
```

Si `triageId` o `leader` no están en el alcance del `catch`, declararlos antes del `try`.

En los dos `events.publish("EMPRESA_SANDBOX_VERIFICATION_COMPLETED", …)` (líneas ~496 y ~719), reemplazar el `Map.of`
por:

```java
                    sandboxData(sandboxResult, -1));
```

`round` = -1 marca que es el resultado final, no una vuelta.

`DevelopmentRuntime`, en el bucle de intentos, justo antes de `feedback = "- " + String.join("\n- ", verdict.retryable());`:

```java
                events.publish("EMPRESA_TASK_ATTEMPT_REJECTED", missionId, taskId, agentId, Map.of(
                        "attempt", i + 1,
                        "reasons", verdict.retryable().stream().limit(3)
                                .map(r -> r.length() <= 200 ? r : r.substring(0, 199) + "…").toList()));
```

`CeoService.viaFallback`: después de cada `log.warn("MODEL_FREE_FALLBACK …")`, `log.warn("MODEL_PAID_FALLBACK …")` y
`log.warn("MODEL_FALLBACK …")`, llamar a un helper con `kind` `"FREE"`, `"PAID"` y `"LOCAL"`, y el destino `free`,
`paid` o `fallback`:

```java
    /** Bitácora (spec 2026-10-17): el cambio de modelo, solo si la llamada pertenece a una misión. */
    private void publishSwitch(String operation, String actor, String from, String to, String kind) {
        var context = ModelCallContext.current();
        if (context == null || context.missionId() == null || events == null) {
            return;
        }
        try {
            events.publish("EMPRESA_MODEL_SWITCHED", context.missionId(), context.taskId(), actor,
                    Map.of("operation", operation, "from", from, "to", to, "kind", kind));
        } catch (RuntimeException ex) {
            log.warn("MODEL_SWITCH_EVENT_FAILED actor={} error={}", actor, ex.getMessage());
        }
    }
```

En `docs/EVENTS.md`, agregar una sección «Bitácora (2026-10-17)» con los 6 tipos y su `data` (copiar la tabla de
arriba), y anotar que `EMPRESA_SANDBOX_VERIFICATION_COMPLETED` suma `round` (-1), `failedStep` y `compileErrors`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd app && mvn -q test -Dtest='DevelopmentTeamStrategyTest,DevelopmentRuntimeTest,CeoServiceTest'`
Expected: PASS. Si un test existente verificaba el `Map.of("overall", …)` exacto de `SANDBOX_VERIFICATION_COMPLETED`,
actualizarlo al mapa nuevo y anotarlo en el reporte.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java app/src/main/java/com/aicompany/core/agent/DevelopmentRuntime.java app/src/main/java/com/aicompany/core/service/CeoService.java docs/EVENTS.md app/src/test/java/com/aicompany/core/service/DevelopmentTeamStrategyTest.java app/src/test/java/com/aicompany/core/agent/DevelopmentRuntimeTest.java app/src/test/java/com/aicompany/core/service/CeoServiceTest.java
git commit -m "Bitácora: eventos del sandbox por vuelta, reparaciones, triage, intentos rechazados y cambio de modelo

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: `AgentActivity` — el estado real de un agente (puro)

**Files:**
- Create: `app/src/main/java/com/aicompany/core/service/AgentActivity.java`
- Test: `app/src/test/java/com/aicompany/core/service/AgentActivityTest.java`

**Interfaces:**
- Consumes: `MissionResponse`, `AgentTask`, `MissionEventView` (Task 2), `MissionStatus`.
- Produces:
  - `public record MissionState(MissionResponse mission, List<AgentTask> tasks, List<MissionEventView> events)`: los
    eventos van del más nuevo al más viejo.
  - `public static String describe(String agentId, String agentName, List<MissionState> missions, Instant now)`.
  - `public static String missionBlock(MissionState state, int maxEvents)`: lo usa la Task 5 para Neo y Aria.
  - `public static String eventLine(MissionEventView e)`: `HH:mm` en UTC-5 + «Agente: resumen».
  - `public static boolean live(MissionStatus s)` y `public static boolean stopped(MissionStatus s)`.
  - `public static final java.time.ZoneOffset FOUNDER_ZONE = java.time.ZoneOffset.ofHours(-5);`

- [ ] **Step 1: Write the failing test**

```java
package com.aicompany.core.service;

import com.aicompany.core.model.AgentTask;
import com.aicompany.core.model.MissionEventView;
import com.aicompany.core.model.MissionResponse;
import com.aicompany.core.model.MissionStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Bitácora (spec 2026-10-17): el estado real de un agente, armado por Java para el chat. */
class AgentActivityTest {

    private static final Instant NOW = Instant.parse("2026-10-17T16:30:00Z");

    private static MissionResponse mission(String id, MissionStatus status, String step, String message, Instant at) {
        return new MissionResponse(id, status, "TEST", 50, step, message, at, null, "TEAM-DEVELOPMENT");
    }

    private static AgentTask task(String id, String agent, String status, Instant at) {
        return new AgentTask(id, "M", agent, "WORK", status, "", at, "WORK", null, null, List.of(), null, null);
    }

    private static MissionEventView event(String agentId, String name, String summary, Instant at) {
        return new MissionEventView("e-" + at, "EMPRESA_TASK_COMMITTED", at, agentId, name, null, summary, null, null);
    }

    @Test void workingNowWhenTheStepNamesTheAgent() {
        var pong = new AgentActivity.MissionState(mission("MISSION-PONG", MissionStatus.WAITING_AGENT_RESULTS,
                "Desarrollo por capas", "Kael (2/3) está generando [game]", NOW),
                List.of(task("MISSION-PONG-INTERACTION-DESIGN-R18", "interaction-design", "RUNNING",
                        NOW.minusSeconds(120))),
                List.of(event("interaction-design", "Kael", "Kael commiteó 1 archivo (game/Main.cs)", NOW.minusSeconds(600))));
        var text = AgentActivity.describe("interaction-design", "Kael", List.of(pong), NOW);
        assertTrue(text.startsWith("Kael — trabajando ahora en MISSION-PONG"), text);
        assertTrue(text.contains("WAITING_AGENT_RESULTS · Desarrollo por capas"), text);
        assertTrue(text.contains("MISSION-PONG-INTERACTION-DESIGN-R18 (WORK, RUNNING, hace 2 min)"), text);
        assertTrue(text.contains("11:20 Kael: Kael commiteó 1 archivo (game/Main.cs)"), text);   // 16:20 UTC = 11:20 UTC-5
    }

    // Review Focus 2: dos misiones vivas, la más reciente primero.
    @Test void twoLiveMissionsAreListedMostRecentFirst() {
        var old = new AgentActivity.MissionState(mission("MISSION-A", MissionStatus.EVALUATING, "Sandbox", "",
                NOW.minusSeconds(3600)), List.of(task("MISSION-A-BACKEND", "backend", "COMPLETED", NOW.minusSeconds(3600))),
                List.of());
        var recent = new AgentActivity.MissionState(mission("MISSION-B", MissionStatus.DELEGATING, "Delegación", "",
                NOW.minusSeconds(60)), List.of(task("MISSION-B-BACKEND", "backend", "PENDING", NOW.minusSeconds(60))),
                List.of());
        var text = AgentActivity.describe("backend", "Iris", List.of(old, recent), NOW);
        assertTrue(text.indexOf("MISSION-B") < text.indexOf("MISSION-A"), text);
        assertTrue(text.contains("MISSION-A-BACKEND (WORK, COMPLETED, hace 60 min)"), text);
    }

    @Test void stoppedMissionsSayTheyWaitForTheFounder() {
        var blocked = new AgentActivity.MissionState(mission("MISSION-C", MissionStatus.BLOCKED, "Bloqueada", "", NOW),
                List.of(task("MISSION-C-QA", "qa", "COMPLETED", NOW)), List.of());
        assertTrue(AgentActivity.describe("qa", "Vera", List.of(blocked), NOW).contains("detenida, esperando al fundador"));
    }

    // Review Focus 3: sin misiones vivas con tareas suyas → libre (aunque haya misiones terminadas).
    @Test void freeWhenNoLiveMissionHasItsTasks() {
        var done = new AgentActivity.MissionState(mission("MISSION-D", MissionStatus.COMPLETED, "Fin", "", NOW),
                List.of(task("MISSION-D-BACKEND", "backend", "COMPLETED", NOW)), List.of());
        var other = new AgentActivity.MissionState(mission("MISSION-E", MissionStatus.PLANNING, "Plan", "", NOW),
                List.of(task("MISSION-E-QA", "qa", "PENDING", NOW)), List.of());
        assertEquals("Iris — libre: sin tareas en misiones activas.",
                AgentActivity.describe("backend", "Iris", List.of(done, other), NOW));
    }

    @Test void neoCoordinatesWhenItsTeamHasALiveMission() {
        var pong = new AgentActivity.MissionState(mission("MISSION-PONG", MissionStatus.EVALUATING, "Sandbox", "", NOW),
                List.of(task("MISSION-PONG-QA", "qa", "RUNNING", NOW)), List.of());
        var text = AgentActivity.describe("engineering", "Neo", List.of(pong), NOW);
        assertTrue(text.startsWith("Neo — sin tareas abiertas; coordina como líder técnico MISSION-PONG"), text);
        assertTrue(text.contains("la corrección la reparte Java por dueño de archivo"), text);
    }

    @Test void missionBlockCarriesStateAndTheLastEvents() {
        var events = java.util.stream.IntStream.range(0, 30).mapToObj(i -> event("qa", "Vera", "evento " + i,
                NOW.minusSeconds(i * 60L))).toList();
        var block = AgentActivity.missionBlock(new AgentActivity.MissionState(mission("MISSION-PONG",
                MissionStatus.EVALUATING, "Sandbox", "Compilando", NOW), List.of(), events), 20);
        assertTrue(block.startsWith("MISSION-PONG: EVALUATING · Sandbox — Compilando"), block);
        assertTrue(block.contains("evento 0") && block.contains("evento 19") && !block.contains("evento 20"), block);
    }

    @Test void liveAndStopped() {
        assertTrue(AgentActivity.live(MissionStatus.BLOCKED));
        assertFalse(AgentActivity.live(MissionStatus.INTERRUPTED));
        assertTrue(AgentActivity.stopped(MissionStatus.AWAITING_INVESTOR));
        assertFalse(AgentActivity.stopped(MissionStatus.EVALUATING));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd app && mvn -q test -Dtest=AgentActivityTest`
Expected: FAIL de compilación.

- [ ] **Step 3: Write the implementation**

```java
package com.aicompany.core.service;

import com.aicompany.core.model.AgentTask;
import com.aicompany.core.model.MissionEventView;
import com.aicompany.core.model.MissionResponse;
import com.aicompany.core.model.MissionStatus;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Bitácora (spec 2026-10-17): lo que un agente está haciendo de verdad, armado en Java desde las misiones vivas, sus
 * tareas y la bitácora. Lo usa el chat para no dejar que el modelo invente (Neo e Iris hablaron de una misión borrada).
 */
public final class AgentActivity {

    public static final ZoneOffset FOUNDER_ZONE = ZoneOffset.ofHours(-5);
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm").withZone(FOUNDER_ZONE);
    private static final Set<MissionStatus> ENDED = Set.of(MissionStatus.COMPLETED, MissionStatus.CANCELLED,
            MissionStatus.FAILED, MissionStatus.INTERRUPTED);
    private static final String LEADER = "engineering";

    public record MissionState(MissionResponse mission, List<AgentTask> tasks, List<MissionEventView> events) {
        public MissionState {
            tasks = tasks == null ? List.of() : tasks;
            events = events == null ? List.of() : events;
        }
    }

    private AgentActivity() {
    }

    public static boolean live(MissionStatus s) {
        return s != null && !ENDED.contains(s);
    }

    public static boolean stopped(MissionStatus s) {
        return s == MissionStatus.BLOCKED || (s != null && s.name().startsWith("AWAITING_"));
    }

    public static String describe(String agentId, String agentName, List<MissionState> missions, Instant now) {
        var live = missions.stream().filter(m -> live(m.mission().status()))
                .sorted(Comparator.comparing((MissionState m) -> m.mission().updatedAt(),
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        var own = live.stream().filter(m -> m.tasks().stream().anyMatch(t -> agentId.equals(t.agentId()))).toList();
        if (own.isEmpty()) {
            if (LEADER.equals(agentId) && !live.isEmpty()) {
                return agentName + " — sin tareas abiertas; coordina como líder técnico " + live.stream()
                        .map(m -> m.mission().missionId()).collect(Collectors.joining(", "))
                        + ": la corrección la reparte Java por dueño de archivo; Neo entra en el plan y en el triage de tests.";
            }
            return agentName + " — libre: sin tareas en misiones activas.";
        }
        var lines = new java.util.ArrayList<String>();
        for (var m : own) {
            var mission = m.mission();
            var working = !stopped(mission.status()) && mission.message() != null
                    && mission.message().contains(agentName);
            var head = (lines.isEmpty() ? agentName + " — " : "") + (working ? "trabajando ahora en "
                    : stopped(mission.status()) ? "detenida, esperando al fundador: " : "en ") + mission.missionId()
                    + " (" + mission.status() + " · " + mission.currentStep() + ")";
            lines.add(head);
            m.tasks().stream().filter(t -> agentId.equals(t.agentId()))
                    .max(Comparator.comparing(AgentTask::updatedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                    .ifPresent(t -> lines.add("  Última tarea: " + t.taskId() + " (" + t.action() + ", " + t.status()
                            + (t.updatedAt() == null ? "" : ", hace " + Duration.between(t.updatedAt(), now).toMinutes()
                            + " min") + ")"));
            m.events().stream().filter(e -> agentId.equals(e.agentId())).findFirst()
                    .ifPresent(e -> lines.add("  Último evento: " + eventLine(e)));
        }
        return String.join("\n", lines);
    }

    public static String missionBlock(MissionState state, int maxEvents) {
        var m = state.mission();
        var events = state.events().stream().limit(maxEvents).map(e -> "- " + eventLine(e))
                .collect(Collectors.joining("\n"));
        return m.missionId() + ": " + m.status() + " · " + m.currentStep()
                + (m.message() == null || m.message().isBlank() ? "" : " — " + m.message())
                + "\nBitácora (más reciente primero):\n" + (events.isEmpty() ? "- (sin bitácora: misión anterior al 2026-10-17)" : events);
    }

    public static String eventLine(MissionEventView e) {
        return HOUR.format(e.at()) + " " + (e.agentName() == null ? "Forjai" : e.agentName()) + ": " + e.summary();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd app && mvn -q test -Dtest=AgentActivityTest`
Expected: PASS. Si una aserción de texto choca con la implementación por un detalle de formato, mandan los textos del
test.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/AgentActivity.java app/src/test/java/com/aicompany/core/service/AgentActivityTest.java
git commit -m "Bitácora: estado real de cada agente armado en Java (AgentActivity)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Chat — estado por Java y datos reales para Neo, Aria y los demás

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java` (`resolveReplies` ~línea 328, `agentReply` ~528, `missionData` ~595, setter nuevo)
- Test: `app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java`

**Interfaces:**
- Consumes:
  - `AgentActivity.describe`, `missionBlock`, `eventLine` y `MissionState` (Task 4).
  - `MissionEventLog.recent(missionId, limit)` (Task 2).
  - `missionMemory.findAll(int)`, `missionMemory.tasks(String)` y `missionMemory.find(String)` (ya existen).
- Produces: `public void setMissionEventLog(com.aicompany.core.event.MissionEventLog log)` en `ChatIntentRouter`,
  inyectado como los demás setters (`@Autowired(required = false)` si ese es el patrón del archivo; mirar
  `setRecoveryMemory`).

Reglas:
- **Estado sin modelo.** Una mención `@X` o un nombre del roster (sin @), seguidos de una pregunta de estado, responden
  `AgentActivity.describe` sin llamar al modelo. Ese texto queda en la conversación con el id del agente, igual que una
  respuesta de modelo.
  - Regex sobre el mensaje normalizado (minúsculas, sin tildes, con `normalize` del archivo):
    `(que (estas|esta|estan) (haciendo|realizando|trabajando)|en que (estas|esta|andas|anda)|que (haces|hace)\b|estado de)`.
  - Sin @, el nombre se busca como palabra entera entre los `name` de `companyMemory.agents()` y debe ir junto a la
    pregunta (p. ej. «¿qué hace Iris?»). Si hay dos nombres, responde cada uno en orden.
- **Bitácora de una misión.** `(que paso|bitacora|historial)( en| de)? MISSION-X` responde un mensaje del CEO con los
  últimos 15 eventos (`AgentActivity.eventLine`) y nunca llama a un modelo. Si no hay eventos: «MISSION-X: sin bitácora
  (misión anterior al 2026-10-17)».
- **Bitácora sin misión.** `que (esta pasando|pasa) en desarrollo` o el mensaje `bitacora` solo responde, para cada
  misión viva del Development Group (`teamId == "TEAM-DEVELOPMENT"`), `missionBlock(state, 5)`. Sin misiones: «No hay
  misiones activas en desarrollo.».
- **Datos para Neo y Aria.** Una mención a `engineering` o `product-owner` sin pregunta de estado antepone
  `CeoService.JAVA_MEMORY_DATA_MARKER` + los bloques + la regla. Los bloques son:
  - si el mensaje trae `MISSION-X`, el `missionBlock(state, 20)` de esa misión (además de lo que ya agrega
    `missionData`);
  - si no, el de las misiones vivas del Development Group, hasta 3.

  La regla, literal: «Responde solo con lo que está en estos datos. No prometas tiempos, avisos ni pasos que no figuren
  aquí; si algo no está, di que no lo sabes. Las misiones que no aparecen aquí no están activas.»
- **Datos para los demás agentes.** Una mención a cualquier otro agente encendido, sin pregunta de estado y sin
  `MISSION-X`, antepone el marcador + `ESTADO REAL (Java):\n` + `describe(...)` + la misma regla.
- **Orden de las reglas.**
  - Las de bitácora (sin mención) van antes de `return List.of(ceoReply(resolve(message)))`.
  - La de estado con mención va dentro de `agentReply`, antes de llamar al modelo y después del chequeo de agente
    apagado.
  - La de estado sin @ va antes del bloque de menciones.

- [ ] **Step 1: Write the failing tests**

Usar `stubAgents()` y `AGENTS` del archivo. Si el roster de `AGENTS` no trae a Iris (`backend`), Neo (`engineering`) y
Aria (`product-owner`), agregarlos con el mismo formato.

```java
    // Bitácora (spec 2026-10-17): «¿qué está realizando?» lo responde Java, nunca el modelo.
    @Test
    void aStatusQuestionToAnAgentIsAnsweredByJava() {
        stubAgents();
        var pong = new MissionResponse("MISSION-PONG", MissionStatus.WAITING_AGENT_RESULTS, "TEST", 56,
                "Desarrollo por capas", "Kael (2/3) está generando [game]", java.time.Instant.now(), null, "TEAM-DEVELOPMENT");
        when(missionMemory.findAll(anyInt())).thenReturn(List.of(pong));
        when(missionMemory.tasks("MISSION-PONG")).thenReturn(List.of(new AgentTask("MISSION-PONG-BACKEND-R18",
                "MISSION-PONG", "backend", "WORK", "COMPLETED", "", java.time.Instant.now(), "WORK", null, null, List.of(),
                null, null)));

        var replies = router.routeReplies("@Iris que esta realizando");

        assertEquals("backend", replies.get(0).agentId());
        assertTrue(replies.get(0).text().contains("MISSION-PONG-BACKEND-R18"), replies.get(0).text());
        verify(ceoService, never()).agentChat(any(), any(), anyList(), any(), any(), any(), any());
    }

    // Review Focus 5: sin @, con el nombre del roster.
    @Test
    void aStatusQuestionWithTheNameAloneIsAnsweredByJava() {
        stubAgents();
        when(missionMemory.findAll(anyInt())).thenReturn(List.of());

        var replies = router.routeReplies("¿qué hace Iris?");

        assertEquals("Iris — libre: sin tareas en misiones activas.", replies.get(0).text());
        verify(ceoService, never()).agentChat(any(), any(), anyList(), any(), any(), any(), any());
        verify(ceoService, never()).chat(any(), any(), anyList(), any(), any(), any(), any());
    }

    // Review Focus 3: el historial nombra una misión borrada; la respuesta no.
    @Test
    void withNoLiveMissionTheAgentIsFreeEvenIfTheHistoryNamesAnOldMission() {
        stubAgents();
        when(missionMemory.findAll(anyInt())).thenReturn(List.of());
        when(conversationMemory.recentMessages(anyInt())).thenReturn(List.of(
                new ConversationTurn("backend", "Trabajo en MISSION-KAEL-CRM-1792045685")));

        var text = router.routeReplies("@Iris que estas haciendo").get(0).text();

        assertFalse(text.contains("MISSION-KAEL-CRM"), text);
        assertTrue(text.contains("libre"), text);
    }

    @Test
    void neoGetsTheRealLogOfTheLiveDevelopmentMissions() {
        stubAgents();
        var pong = new MissionResponse("MISSION-PONG", MissionStatus.EVALUATING, "TEST", 80, "Sandbox", "Compilando",
                java.time.Instant.now(), null, "TEAM-DEVELOPMENT");
        when(missionMemory.findAll(anyInt())).thenReturn(List.of(pong));
        when(missionMemory.tasks("MISSION-PONG")).thenReturn(List.of());
        router.setMissionEventLog(eventLog);
        when(eventLog.recent(eq("MISSION-PONG"), anyInt())).thenReturn(List.of(new MissionEventView("e1",
                "EMPRESA_SANDBOX_FINISHED", java.time.Instant.parse("2026-10-17T16:00:00Z"), "sandbox", "sandbox", null,
                "Sandbox FAIL en build: 7 errores de compilación", 0, null)));
        when(ceoService.agentChat(any(), any(), anyList(), any(), any(), any(), any())).thenReturn("ok");

        router.routeReplies("@Neo como vamos con el pong");

        verify(ceoService).agentChat(argThat(s -> s.agentId().equals("engineering")), any(), anyList(),
                argThat(m -> m.startsWith(CeoService.JAVA_MEMORY_DATA_MARKER)
                        && m.contains("MISSION-PONG: EVALUATING · Sandbox")
                        && m.contains("11:00 sandbox: Sandbox FAIL en build: 7 errores de compilación")
                        && m.contains("No prometas tiempos, avisos ni pasos que no figuren aquí")
                        && m.endsWith("@Neo como vamos con el pong")), any(), any(), any());
    }

    @Test
    void theLogOfAMissionIsAnsweredByJava() {
        stubAgents();
        router.setMissionEventLog(eventLog);
        when(eventLog.recent(eq("MISSION-PONG"), eq(15))).thenReturn(List.of(new MissionEventView("e1",
                "EMPRESA_MISSION_CORRECTION_STARTED", java.time.Instant.parse("2026-10-17T17:15:00Z"), "system", null, null,
                "Corrección automática ronda 22", 22, null)));

        var text = router.routeReplies("¿qué pasó en MISSION-PONG?").get(0).text();

        assertTrue(text.contains("12:15 Forjai: Corrección automática ronda 22"), text);
        verify(ceoService, never()).chat(any(), any(), anyList(), any(), any(), any(), any());
    }
```

Declarar en la clase de test `private final com.aicompany.core.event.MissionEventLog eventLog =
mock(com.aicompany.core.event.MissionEventLog.class);` y los imports que falten (`MissionEventView`, `AgentTask`,
`MissionResponse`, `MissionStatus`, `ConversationTurn`).

**Tests existentes que cambian** (ruling: el spec §3.3 antepone el estado real a toda mención):
`aMentionIsAnsweredByThatAgentAndRecordedWithItsId` stubbea `agentChat(…, eq("@Kira ideas"), …)`. Cambiar ese matcher
por `argThat(m -> m.endsWith("@Kira ideas") && m.contains("ESTADO REAL (Java)"))`. Si otros tests usan `eq(<mensaje>)`
en `agentChat`, cambiarlos igual a `endsWith(<mensaje>)`. Anotar en el reporte cuáles se tocaron.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd app && mvn -q test -Dtest=ChatIntentRouterTest`
Expected: FAIL en los 5 tests nuevos.

- [ ] **Step 3: Write the implementation**

En `ChatIntentRouter`:

```java
    private com.aicompany.core.event.MissionEventLog missionEventLog;

    /** Bitácora (spec 2026-10-17). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMissionEventLog(com.aicompany.core.event.MissionEventLog missionEventLog) {
        this.missionEventLog = missionEventLog;
    }

    static final Pattern STATUS_QUESTION = Pattern.compile(
            "(que (estas|esta|estan) (haciendo|realizando|trabajando)|en que (estas|esta|andas|anda)|que (haces|hace)\\b|estado de)");
    private static final Pattern MISSION_LOG = Pattern.compile("(que paso|bitacora|historial)( en| de)? mission-");
    private static final Pattern DEVELOPMENT_LOG = Pattern.compile("^(que (esta pasando|pasa) en desarrollo|bitacora)\\??$");
    static final String REAL_DATA_RULE = "Responde solo con lo que está en estos datos. No prometas tiempos, avisos ni pasos "
            + "que no figuren aquí; si algo no está, di que no lo sabes. Las misiones que no aparecen aquí no están activas.";
    private static final java.util.Set<String> LOG_READERS = java.util.Set.of("engineering", "product-owner");

    private List<AgentActivity.MissionState> liveStates(boolean developmentOnly) {
        return missionMemory.findAll(200).stream()
                .filter(m -> AgentActivity.live(m.status()))
                .filter(m -> !developmentOnly || "TEAM-DEVELOPMENT".equals(m.teamId()))
                .map(m -> new AgentActivity.MissionState(m, missionMemory.tasks(m.missionId()), events(m.missionId(), 20)))
                .toList();
    }

    private List<com.aicompany.core.model.MissionEventView> events(String missionId, int limit) {
        try {
            return missionEventLog == null ? List.of() : missionEventLog.recent(missionId, limit);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String describe(String agentId, String name) {
        return AgentActivity.describe(agentId, name, liveStates(false), java.time.Instant.now());
    }
```

En `resolveReplies`, después del chequeo de gobernanza y de `var agents = companyMemory.agents();`, y antes de las
menciones:

```java
        var normalized = normalize(message);
        // Bitácora (spec 2026-10-17): «¿qué hace Iris?» sin @ lo responde Java.
        if (MentionResolver.resolve(message, agents).agentIds().isEmpty() && STATUS_QUESTION.matcher(normalized).find()) {
            var named = agents.stream().filter(a -> Pattern.compile("\\b" + Pattern.quote(normalize(String.valueOf(
                    a.get("name")))) + "\\b").matcher(normalized).find()).toList();
            if (!named.isEmpty()) {
                return named.stream().map(a -> new ChatReply(String.valueOf(a.get("id")), String.valueOf(a.get("name")),
                        describe(String.valueOf(a.get("id")), String.valueOf(a.get("name"))))).toList();
            }
        }
```

Antes de `return List.of(ceoReply(resolve(message)));` (al final de `resolveReplies`):

```java
        var logMatcher = MISSION_LOG.matcher(normalized);
        if (logMatcher.find()) {
            var id = MISSION_ID.matcher(message);
            if (id.find()) {
                var missionId = id.group(1).toUpperCase(Locale.ROOT);
                var lines = events(missionId, 15).stream().map(AgentActivity::eventLine).toList();
                return List.of(ceoReply(lines.isEmpty() ? missionId + ": sin bitácora (misión anterior al 2026-10-17)"
                        : "Bitácora de " + missionId + " (hora Colombia, más reciente primero):\n- " + String.join("\n- ", lines)));
            }
        }
        if (DEVELOPMENT_LOG.matcher(normalized.strip()).matches()) {
            var states = liveStates(true);
            return List.of(ceoReply(states.isEmpty() ? "No hay misiones activas en desarrollo."
                    : states.stream().map(s -> AgentActivity.missionBlock(s, 5)).collect(Collectors.joining("\n\n"))));
        }
```

En `agentReply`, después del bloque de agente apagado:

```java
        // Bitácora (spec 2026-10-17): el estado lo responde Java.
        if (STATUS_QUESTION.matcher(normalize(message)).find()) {
            return new ChatReply(agentId, name, describe(agentId, name));
        }
```

Y reemplazar `var data = missionData(message, agentId);` por:

```java
        var data = withRealState(missionData(message, agentId), message, agentId, name);
```

con:

```java
    /** Bitácora (spec 2026-10-17): Neo y Aria leen la bitácora; los demás, su estado real. Nunca el historial solo. */
    private MissionData withRealState(MissionData data, String message, String agentId, String name) {
        String block;
        if (LOG_READERS.contains(agentId)) {
            var id = MISSION_ID.matcher(message);
            var states = id.find()
                    ? missionMemory.find(id.group(1).toUpperCase(Locale.ROOT)).map(m -> List.of(new AgentActivity.MissionState(
                            m, missionMemory.tasks(m.missionId()), events(m.missionId(), 20)))).orElse(List.of())
                    : liveStates(true).stream().limit(3).toList();
            block = "BITÁCORA REAL DE DESARROLLO (Java):\n" + (states.isEmpty() ? "No hay misiones activas en desarrollo."
                    : states.stream().map(s -> AgentActivity.missionBlock(s, 20)).collect(Collectors.joining("\n\n")));
        } else if (!MISSION_ID.matcher(message).find()) {
            block = "ESTADO REAL (Java):\n" + describe(agentId, name);
        } else {
            return data;
        }
        var body = data.injected() ? data.message().substring(CeoService.JAVA_MEMORY_DATA_MARKER.length()).strip() : message;
        return new MissionData(CeoService.JAVA_MEMORY_DATA_MARKER + "\n" + block + "\n" + REAL_DATA_RULE + "\n\n"
                + (data.injected() ? body : "MENSAJE DEL FUNDADOR:\n" + message), true, data.anyValidated());
    }
```

Si `MissionData.message()` cuando `injected` no empieza exactamente por el marcador, ajustar el `substring` para que
quede una sola vez el marcador y el «MENSAJE DEL FUNDADOR» siga al final. El test
`neoGetsTheRealLogOfTheLiveDevelopmentMissions` exige que empiece por el marcador y termine en el mensaje.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd app && mvn -q test -Dtest=ChatIntentRouterTest`
Expected: PASS (los nuevos y los existentes, con los matchers ajustados).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java
git commit -m "Chat: el estado de un agente y la bitácora los responde Java; Neo y Aria leen la bitácora real

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Neo planifica con lo que pasó en el incremento

**Files:**
- Create: `app/src/main/java/com/aicompany/core/event/MissionEventDigest.java`
- Modify: `app/src/main/java/com/aicompany/core/service/MissionExecutor.java` (`planAndRunIncrement` ~734, `reexecuteTeamMission` ~465, setter de `MissionEventLog`)
- Test: `app/src/test/java/com/aicompany/core/event/MissionEventDigestTest.java`, `app/src/test/java/com/aicompany/core/service/MissionExecutorTest.java` (agregar un caso)

**Interfaces:**
- Consumes:
  - `MissionEventLog.recent(missionId, 500)` (Task 2).
  - Los tipos y el `data` de la Task 3. Ojo: la bitácora guarda solo `summary`, `round` e `increment`, no el `data`. Por
    eso el resumen se arma desde `type`, `summary`, `agentName`, `round` e `increment` de cada `MissionEventView`.
  - `CorrectionReport.fingerprint(String)`.
- Produces:
  - `public static String digest(List<MissionEventView> events, int increment)`: devuelve `""` si no hay eventos de
    ese incremento.
  - `public static final String HEADER = "LO QUE PASÓ EN EL INCREMENTO ";`

Cómo se define «del incremento»:
- `EMPRESA_INCREMENT_STARTED` trae `increment` (columna `increment`).
- Los eventos de un incremento N son los que van desde el `INCREMENT_STARTED` con `increment == N` hasta el siguiente
  `INCREMENT_STARTED`, en orden de `at`.
- Si no hay `INCREMENT_STARTED` para N y N == 1, cuentan todos los eventos anteriores al primer `INCREMENT_STARTED`.

Qué lleva el resumen:
- **Rondas:** los `EMPRESA_MISSION_CORRECTION_STARTED` del tramo, con sus números (`round`).
- **Resultado:** el último `EMPRESA_SANDBOX_VERIFICATION_COMPLETED` del tramo: su `summary`.
- **Fallas repetidas:** del `summary` de `EMPRESA_REPAIR_REQUESTED` y de `EMPRESA_SANDBOX_FINISHED`, la huella
  `CorrectionReport.fingerprint(summary)`. Se listan las 5 más frecuentes con su cantidad, solo si aparecen 2 veces o
  más.
- **Triage:** los `summary` de `EMPRESA_TEST_TRIAGE_COMPLETED`.
- **Reparaciones por agente:** cantidad de `EMPRESA_REPAIR_REQUESTED` por `agentName`, de mayor a menor.

- [ ] **Step 1: Write the failing test**

```java
package com.aicompany.core.event;

import com.aicompany.core.model.MissionEventView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Bitácora (spec 2026-10-17): Neo planifica sabiendo lo que pasó en el incremento anterior. */
class MissionEventDigestTest {

    private static Instant t(int minute) {
        return Instant.parse("2026-10-17T15:00:00Z").plusSeconds(minute * 60L);
    }

    private static MissionEventView e(int minute, String type, String agent, String summary, Integer round, Integer inc) {
        return new MissionEventView("e" + minute, type, t(minute), null, agent, null, summary, round, inc);
    }

    @Test void summarizesRoundsRepeatedFailuresTriageAndRepairs() {
        var events = new ArrayList<>(List.of(
                e(0, "EMPRESA_INCREMENT_STARTED", "human", "INCREMENT STARTED por human", 11, 2),
                e(1, "EMPRESA_REPAIR_REQUESTED", "Vera", "Errores de compilación devueltos a Vera: 4 en ESC_07.cs", 0, null),
                e(2, "EMPRESA_MISSION_CORRECTION_STARTED", "Forjai", "Corrección automática ronda 12", 12, null),
                e(3, "EMPRESA_REPAIR_REQUESTED", "Vera", "Errores de compilación devueltos a Vera: 4 en ESC_07.cs", 0, null),
                e(4, "EMPRESA_REPAIR_REQUESTED", "Kael", "Errores de compilación devueltos a Kael: 1 en Main.cs", 0, null),
                e(5, "EMPRESA_TEST_TRIAGE_COMPLETED", "Neo", "Triage de Neo: ESC_04_x → TEST_WRONG", null, null),
                e(6, "EMPRESA_SANDBOX_VERIFICATION_COMPLETED", "sandbox", "Sandbox PASS: 25 tests", -1, null),
                e(7, "EMPRESA_INCREMENT_STARTED", "human", "INCREMENT STARTED por human", 16, 3),
                e(8, "EMPRESA_REPAIR_REQUESTED", "Iris", "otra cosa del incremento 3", 0, null)));
        java.util.Collections.reverse(events);   // la bitácora llega del más nuevo al más viejo

        var digest = MissionEventDigest.digest(events, 2);

        assertTrue(digest.startsWith(MissionEventDigest.HEADER + "2 (Java):"), digest);
        assertTrue(digest.contains("Rondas de corrección: 1 (12)"), digest);
        assertTrue(digest.contains("Resultado final: Sandbox PASS: 25 tests"), digest);
        assertTrue(digest.contains("2× Errores de compilación devueltos a Vera: 4 en ESC_07.cs"), digest);
        assertFalse(digest.contains("1× "), digest);
        assertTrue(digest.contains("Triage de Neo: ESC_04_x → TEST_WRONG"), digest);
        assertTrue(digest.contains("Reparaciones por agente: Vera 2, Kael 1"), digest);
        assertFalse(digest.contains("incremento 3") || digest.contains("Iris"), digest);
    }

    @Test void emptyWithoutEventsOfThatIncrement() {
        assertEquals("", MissionEventDigest.digest(List.of(), 2));
        assertEquals("", MissionEventDigest.digest(List.of(e(0, "EMPRESA_INCREMENT_STARTED", "human", "x", 1, 3)), 2));
    }
}
```

En `MissionExecutorTest`, junto al test existente de `buildIncrementAsync` (buscar `buildIncrementAsync`), agregar un
caso. Stubbear `eventLog.recent(eq(MISSION), eq(500))` con un `INCREMENT_STARTED` del incremento 1 y un
`MISSION_CORRECTION_STARTED`, llamar a `buildIncrementAsync(MISSION, 2, 5)` y verificar que la instrucción que recibe
`teamWorkPlanner.plan(...)` contiene `MissionEventDigest.HEADER + "1 (Java):"`. Seguir el arreglo y los nombres del
test existente.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd app && mvn -q test -Dtest='MissionEventDigestTest,MissionExecutorTest'`
Expected: FAIL.

- [ ] **Step 3: Write the implementation**

```java
package com.aicompany.core.event;

import com.aicompany.core.model.MissionEventView;
import com.aicompany.core.recovery.CorrectionReport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Bitácora (spec 2026-10-17): lo que pasó en un incremento, armado por Java, para el plan de Neo. */
public final class MissionEventDigest {

    public static final String HEADER = "LO QUE PASÓ EN EL INCREMENTO ";

    private MissionEventDigest() {
    }

    public static String digest(List<MissionEventView> newestFirst, int increment) {
        var ordered = newestFirst.stream().sorted(Comparator.comparing(MissionEventView::at)).toList();
        var span = new ArrayList<MissionEventView>();
        var inside = increment == 1 && ordered.stream().noneMatch(e -> isStart(e) && Integer.valueOf(1).equals(e.increment()));
        for (var e : ordered) {
            if (isStart(e)) {
                if (inside && !span.isEmpty() || inside && !Integer.valueOf(increment).equals(e.increment())) {
                    break;
                }
                inside = Integer.valueOf(increment).equals(e.increment());
                continue;
            }
            if (inside) {
                span.add(e);
            }
        }
        if (span.isEmpty()) {
            return "";
        }
        var lines = new ArrayList<String>();
        var rounds = span.stream().filter(e -> type(e, "MISSION_CORRECTION_STARTED")).map(MissionEventView::round)
                .map(String::valueOf).toList();
        lines.add("Rondas de corrección: " + rounds.size() + (rounds.isEmpty() ? "" : " (" + String.join(", ", rounds) + ")"));
        span.stream().filter(e -> type(e, "SANDBOX_VERIFICATION_COMPLETED")).reduce((a, b) -> b)
                .ifPresent(e -> lines.add("Resultado final: " + e.summary()));
        var byFingerprint = new LinkedHashMap<String, List<MissionEventView>>();
        span.stream().filter(e -> type(e, "REPAIR_REQUESTED") || type(e, "SANDBOX_FINISHED"))
                .forEach(e -> byFingerprint.computeIfAbsent(CorrectionReport.fingerprint(e.summary()), k -> new ArrayList<>()).add(e));
        var repeated = byFingerprint.values().stream().filter(l -> l.size() >= 2)
                .sorted(Comparator.comparingInt((List<MissionEventView> l) -> l.size()).reversed()).limit(5)
                .map(l -> l.size() + "× " + l.get(0).summary()).toList();
        if (!repeated.isEmpty()) {
            lines.add("Fallas repetidas:\n  - " + String.join("\n  - ", repeated));
        }
        span.stream().filter(e -> type(e, "TEST_TRIAGE_COMPLETED")).forEach(e -> lines.add(e.summary()));
        Map<String, Long> repairs = span.stream().filter(e -> type(e, "REPAIR_REQUESTED"))
                .collect(Collectors.groupingBy(e -> e.agentName() == null ? "?" : e.agentName(), LinkedHashMap::new,
                        Collectors.counting()));
        if (!repairs.isEmpty()) {
            lines.add("Reparaciones por agente: " + repairs.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .map(x -> x.getKey() + " " + x.getValue()).collect(Collectors.joining(", ")));
        }
        return HEADER + increment + " (Java):\n" + String.join("\n", lines);
    }

    private static boolean isStart(MissionEventView e) {
        return type(e, "INCREMENT_STARTED");
    }

    private static boolean type(MissionEventView e, String suffix) {
        return ("EMPRESA_" + suffix).equals(e.type());
    }
}
```

Nota para el implementador: lo que manda es el test. La lógica del tramo debe dar solo los eventos entre el
`INCREMENT_STARTED` de N y el siguiente `INCREMENT_STARTED`. Si el bucle de arriba no lo cumple en algún caso del test,
simplificarlo a: buscar el índice del `INCREMENT_STARTED` de N, tomar desde ahí hasta el siguiente `INCREMENT_STARTED`,
y para N = 1 sin `INCREMENT_STARTED`, desde el principio.

En `MissionExecutor`, agregar el setter `setMissionEventLog(MissionEventLog)` (`@Autowired(required = false)`) y un
helper:

```java
    /** Bitácora (spec 2026-10-17): lo que pasó en el incremento, para el plan de Neo; "" si no hay bitácora. */
    private String incrementDigest(String missionId, int increment) {
        if (missionEventLog == null || increment < 1) {
            return "";
        }
        try {
            var digest = com.aicompany.core.event.MissionEventDigest.digest(missionEventLog.recent(missionId, 500), increment);
            return digest.isEmpty() ? "" : "\n\n" + digest;
        } catch (Exception ex) {
            log.warn("MISSION {} - could not read the event log: {}", missionId, ex.getMessage());
            return "";
        }
    }
```

En `planAndRunIncrement`, después de `var withIncrement = withIncrement(...)`:

```java
        withIncrement = withIncrement + incrementDigest(missionId, increment - 1);
```

Si `withIncrement` es `var` efectivamente final y se usa en una lambda, declarar una variable nueva y usarla en las
tres llamadas siguientes.

En `reexecuteTeamMission`, dentro de `if (current > 0) { … }`, después de reasignar `instruction`:

```java
                instruction = instruction + incrementDigest(missionId, current);
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd app && mvn -q test -Dtest='MissionEventDigestTest,MissionExecutorTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/event/MissionEventDigest.java app/src/main/java/com/aicompany/core/service/MissionExecutor.java app/src/test/java/com/aicompany/core/event/MissionEventDigestTest.java app/src/test/java/com/aicompany/core/service/MissionExecutorTest.java
git commit -m "Bitácora: Neo planifica el incremento y la corrección con lo que pasó antes

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: API, Activity y línea de tiempo en el Command Center

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/controller/MissionController.java` (endpoint)
- Modify: `app/src/main/java/com/aicompany/core/service/MissionService.java` (método `events`)
- Modify: `app/src/main/java/com/aicompany/core/service/ActivityMemoryService.java` (UNION con `MissionEvent`)
- Modify: `app/frontend/src/api/types.ts`, `app/frontend/src/api/client.ts`, `app/frontend/src/pages/MissionDetailPage.tsx`, `app/frontend/src/pages/ActivityPage.tsx`
- Test: `app/src/test/java/com/aicompany/core/service/MissionServiceTest.java` (un caso)

**Interfaces:**
- Consumes: `MissionEventLog.recent(missionId, limit)` y `MissionEventView` (Task 2).
- Produces:
  - `GET /api/company/missions/{missionId}/events?limit=100` → `List<MissionEventView>`. `limit` se acota a 1..500 y
    vale 100 por defecto.
  - `MissionService.events(String missionId, int limit)` y `MissionService.setMissionEventLog(MissionEventLog)`.

- [ ] **Step 1: Write the failing test**

En `MissionServiceTest`:

```java
    // Bitácora (spec 2026-10-17): la API acota el límite y sin bitácora devuelve vacío.
    @Test
    void eventsAreCappedAndEmptyWithoutLog() {
        assertEquals(List.of(), service.events("M-1", 100));
        var log = mock(com.aicompany.core.event.MissionEventLog.class);
        service.setMissionEventLog(log);
        service.events("M-1", 10_000);
        verify(log).recent("M-1", 500);
        service.events("M-1", 0);
        verify(log).recent("M-1", 1);
    }
```

Usar el nombre de la instancia del servicio que tenga el archivo (`service`, `missionService`…).

- [ ] **Step 2: Run test to verify it fails**

Run: `cd app && mvn -q test -Dtest=MissionServiceTest`
Expected: FAIL de compilación.

- [ ] **Step 3: Write the implementation**

`MissionService`:

```java
    private com.aicompany.core.event.MissionEventLog missionEventLog;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMissionEventLog(com.aicompany.core.event.MissionEventLog missionEventLog) {
        this.missionEventLog = missionEventLog;
    }

    /** Bitácora (spec 2026-10-17): del más nuevo al más viejo; límite 1..500. */
    public List<com.aicompany.core.model.MissionEventView> events(String missionId, int limit) {
        return missionEventLog == null ? List.of()
                : missionEventLog.recent(missionId, Math.max(1, Math.min(500, limit)));
    }
```

`MissionController`, junto a `joint-review`:

```java
    /** Bitácora (spec 2026-10-17): eventos de la misión, del más nuevo al más viejo. */
    @GetMapping("/{missionId}/events")
    public List<com.aicompany.core.model.MissionEventView> events(@PathVariable String missionId,
                                                                  @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return missionService.events(missionId, limit);
    }
```

Agregar el import de `RequestParam` si falta.

`ActivityMemoryService.recent`, agregar al `CALL { … }` un `UNION ALL`:

```
                              UNION ALL
                              MATCH (ev:MissionEvent)
                              OPTIONAL MATCH (a3:Agent {id: ev.agentId})
                              RETURN 'EVENT' AS type, ev.missionId AS missionId, ev.agentId AS agentId,
                                     (coalesce(a3.name, ev.agentId, 'Forjai') + ': ' + ev.summary) AS description,
                                     ev.at AS timestamp
```

`api/types.ts`:

```ts
export interface MissionEventView {
  id: string
  type: string
  at: string
  agentId: string | null
  agentName: string | null
  taskId: string | null
  summary: string
  round: number | null
  increment: number | null
}
```

Y en `ActivityItem.type` sumar `| 'EVENT'`.

`api/client.ts`, junto a `jointReview`:

```ts
  missionEvents: (missionId: string, limit = 100) =>
    request<MissionEventView[]>(`/api/company/missions/${missionId}/events?limit=${limit}`),
```

Importar `MissionEventView` donde están los demás tipos.

`MissionDetailPage.tsx`: un componente nuevo y su uso, debajo de `<MissionJointReview …/>`, o donde esté la revisión
conjunta en el render principal.

```tsx
const SANDBOX_TYPES = ['SANDBOX_', 'REPAIR_', 'TEST_TRIAGE_']

/** Bitácora (spec 2026-10-17): lo que pasó en la misión, del más nuevo al más viejo. */
function MissionEventLog({ missionId, live }: { missionId: string; live: boolean }) {
  const [agent, setAgent] = useState('')
  const [sandboxOnly, setSandboxOnly] = useState(false)
  const query = useQuery({
    queryKey: ['missionEvents', missionId],
    queryFn: () => api.missionEvents(missionId, 200),
    refetchInterval: live ? 5_000 : false,
  })
  const events = query.data ?? []
  const agents = Array.from(new Set(events.map((e) => e.agentName ?? 'Forjai')))
  const shown = events.filter(
    (e) =>
      (agent === '' || (e.agentName ?? 'Forjai') === agent) &&
      (!sandboxOnly || SANDBOX_TYPES.some((p) => e.type.startsWith('EMPRESA_' + p))),
  )
  return (
    <section>
      <h2>Bitácora</h2>
      {events.length === 0 ? (
        <p className="hint">Sin bitácora (misión anterior al 2026-10-17).</p>
      ) : (
        <>
          <p>
            <select value={agent} onChange={(e) => setAgent(e.target.value)}>
              <option value="">Todos los agentes</option>
              {agents.map((a) => (
                <option key={a} value={a}>
                  {a}
                </option>
              ))}
            </select>{' '}
            <label>
              <input type="checkbox" checked={sandboxOnly} onChange={(e) => setSandboxOnly(e.target.checked)} /> solo
              sandbox
            </label>
          </p>
          <ul className="activity-list">
            {shown.map((e) => (
              <li key={e.id}>
                <span className="activity-time">{new Date(e.at).toLocaleTimeString()}</span>
                <span className="activity-type">{e.agentName ?? 'Forjai'}</span>
                <span className="activity-desc">{e.summary}</span>
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  )
}
```

`live` = el estado de la misión no está en `COMPLETED`, `CANCELLED`, `FAILED` ni `INTERRUPTED`. Calcularlo en el
componente principal con los datos ya cargados de la misión e importar `useState` si falta.

`ActivityPage.tsx`: sumar `EVENT: '📝 Evento',` a `TYPE_LABEL`.

- [ ] **Step 4: Run tests, lint and build**

Run: `cd app && mvn -q test -Dtest=MissionServiceTest` → PASS.
Run: `cd app/frontend && npm run lint && npm run build` → sin errores.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/controller/MissionController.java app/src/main/java/com/aicompany/core/service/MissionService.java app/src/main/java/com/aicompany/core/service/ActivityMemoryService.java app/src/test/java/com/aicompany/core/service/MissionServiceTest.java app/frontend/src/api/types.ts app/frontend/src/api/client.ts app/frontend/src/pages/MissionDetailPage.tsx app/frontend/src/pages/ActivityPage.tsx
git commit -m "Command Center: bitácora de la misión (línea de tiempo con filtros) y eventos en Activity

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Documentación y suite completa

**Files:**
- Modify: `docs/ARCHITECTURE.md`, `docs/HISTORY.md`, `docs/FALTANTES.md`, `CLAUDE.md`

- [ ] **Step 1: ARCHITECTURE.** Agregar un párrafo «**Bitácora de misión (2026-10-17)**» en la sección de misiones
  (cerca de «Corrección automática», línea ~42) con:
  - `MissionEventLog` en `CompanyEventPublisher` (con misión, tope 500, nunca frena, `MISSION_EVENT_LOG_FAILED`);
  - `MissionEventText`;
  - los 6 eventos nuevos;
  - `AgentActivity` y las reglas del chat (estado por Java; Neo y Aria con bitácora; los demás con su estado; la regla
    literal);
  - `MissionEventDigest` en el plan de Neo;
  - `GET /missions/{id}/events`, la sección «Bitácora» de Missions y `EVENT` en Activity.

  En la sección del chat, mencionar las reglas nuevas y su lugar en el orden.
- [ ] **Step 2: HISTORY.** Agregar una entrada «Bitácora de misión (2026-10-17)» con:
  - la causa: Neo e Iris respondieron sobre `MISSION-KAEL-CRM-1792045685` (borrada) e inventaron tiempos;
  - la decisión del fundador, citada del spec;
  - el ruling de `EMPRESA_SANDBOX_FINISHED` por vuelta;
  - que queda pendiente la verificación en vivo.
- [ ] **Step 3: FALTANTES.**
  - Agregar `[ ]` «Verificar en vivo la bitácora: preguntar a @Iris y @Neo con una misión en curso y ver la línea de
    tiempo en Missions».
  - En la fila «Ver el sandbox en el Command Center» de la sección 2, anotar «en parte: la bitácora muestra cada vuelta
    del sandbox (2026-10-17); falta el log en vivo».
- [ ] **Step 4: CLAUDE.md.** En «Arquitectura en grande», en la línea del **Chat**, agregar «(las preguntas de estado de
  un agente y la bitácora de una misión las responde Java)». En la línea de **Flujo de misión**, agregar «; cada evento
  con misión queda en la bitácora (`MissionEventLog`)».
- [ ] **Step 5: Suite completa.**
  Run: `cd app && mvn test` → PASS (anotar el número de tests en el ledger).
  Run: `cd app/frontend && npm run lint && npm run build` → sin errores.
- [ ] **Step 6: Commit**

```bash
git add docs/ARCHITECTURE.md docs/HISTORY.md docs/FALTANTES.md CLAUDE.md
git commit -m "Docs: bitácora de misión

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Self-Review

| Spec | Task |
|---|---|
| Parte 1: modelo, `id` único, `summary` de 300 caracteres como máximo, tope 500, borrado, nunca lanza, sin misión no se guarda | 1 y 2 |
| Parte 2: los 5 eventos del spec, más `SANDBOX_FINISHED` (ruling) y el evento final enriquecido | 3 |
| Parte 3.1: estado por Java (con y sin @), «qué pasó en MISSION-X», «qué está pasando en desarrollo» | 4 y 5 |
| Parte 3.2: Neo y Aria con bitácora (hasta 3 misiones, 20 eventos, regla literal) | 5 |
| Parte 3.3: los demás agentes con su estado | 5 |
| Parte 4: resumen del incremento para Neo (incremento nuevo y ronda de corrección) | 6 |
| Parte 5: API, MissionDetailPage con filtros y polling, Activity | 7 |
| Compatibilidad: sin bitácora → «misión anterior al 2026-10-17» | 4, 5 y 7 |
| Review Focus 1-5 | 2, 4, 5 y (4) inspección |

**Placeholders.** Los arreglos de los tests de las Tasks 3 y 6 reutilizan los tests existentes del mismo archivo. El
plan nombra cuál copiar y qué verificar, porque esos arreglos mockean 10 o más colaboradores que el implementador ve en
el archivo.

**Tipos coherentes en todas las tareas:**
- `MissionEventView(id, type, at, agentId, agentName, taskId, summary, round, increment)`: Tasks 2, 4, 5, 6 y 7.
- `AgentActivity.MissionState(mission, tasks, events)`: Tasks 4 y 5.
- `MissionEventLog.recent(String, int)`: Tasks 2, 5, 6 y 7.
