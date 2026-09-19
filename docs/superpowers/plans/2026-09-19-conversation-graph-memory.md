# Memoria conversacional como grafo histórico por día — plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reestructurar la memoria conversacional del CEO (hoy: `Conversation`→`Message` plano + un puntero mutable de "foco") en un grafo real por día calendario (`Conversation`→`Chat`→`Message`, con edges `MENTIONS` hacia las entidades reales discutidas), y exponer dos consultas nuevas: "¿qué hablamos el [día]?" y "¿en qué días hablamos de X?".

**Architecture:** Nuevo nodo `Chat {date}` entre `Conversation` y `Message` (uno por día calendario, `MERGE` idempotente). `ConversationMemoryService` gana un `Clock` inyectado (ya existe como bean en `CoreConfig` desde la ronda de NVIDIA) para resolver "hoy"/"ayer" y para escribir la fecha del día actual. Dos topics nuevos en `query_company_memory` (`CHAT_HISTORY`, `MENTIONED_DATES`) siguen exactamente el patrón ya establecido de `MISSION_DETAILS`/`OPPORTUNITY_DETAILS`: un chequeo propio en `ChatIntentRouter.resolve()` (no el enum `QueryIntent`, porque ambos necesitan un parámetro) + un método formateador en `CompanyTools` + un caso en `answerMemoryTopic`.

**Tech Stack:** Java 21, Spring Boot 4.1.1, `neo4j-java-driver` (Cypher a mano), JUnit 5 + Mockito.

**Spec:** `docs/superpowers/specs/2026-09-19-conversation-graph-memory-design.md`

## Global Constraints

- **Solo chat con el CEO** — no se agrega ninguna capacidad de chatear con agentes individuales.
- **Agrupación por día calendario únicamente** — un `Chat` nuevo por cada fecha real (`YYYY-MM-DD`), sin heurística de cambio de tema ni ventana de inactividad.
- **El foco actual (`lastMentionedType`/`lastMentionedIds`/`lastMentionedAt`) no se toca** — sigue siendo el único mecanismo para resolver pronombres demostrativos del turno actual. Los edges `MENTIONS` son un rastro histórico *adicional*, nunca un reemplazo.
- **Resolución de fecha deliberadamente acotada**: solo `"hoy"`, `"ayer"`, y una fecha explícita `DD/MM` o `DD/MM/YYYY` — nada de nombres de día de la semana ni expresiones relativas más complejas.
- **`recentMessages(20)` no cambia** — la memoria "más allá de la ventana" se resuelve con recuperación explícita bajo demanda (las dos consultas nuevas), nunca subiendo el límite fijo ni generando resúmenes automáticos vía LLM.
- Ambas consultas nuevas formatean 100% en Java (mismo criterio anti-alucinación de siempre) — el CEO solo parafrasea sobre el resultado real, nunca inventa.
- `mvn test` debe quedar en verde después de cada tarea.

---

### Task 1: `Chat` por día en `ConversationMemoryService` + migración

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/ConversationMemoryService.java`
- Modify: `app/src/main/java/com/aicompany/core/config/CompanyMemoryInitializer.java`

**Interfaces:**
- Consumes: bean `Clock` ya existente en `app/src/main/java/com/aicompany/core/config/CoreConfig.java` (`Clock.systemDefaultZone()`, agregado en la ronda anterior de NVIDIA).
- Produces: `ConversationMemoryService(Driver driver, Clock clock)` (constructor cambia — gana `Clock`); `void recordChatMention(String type, List<String> ids)`; `List<ConversationTurn> chatHistoryForDate(String date)`; `List<String> daysMentioning(String id)`; `void migrateMessagesToChats()` — usados por las Tasks 2 y 3.

Este task no tiene test unitario directo (integración Neo4j pura, mismo criterio ya establecido para el resto de los `*MemoryService` — ver `CLAUDE.md`). Se verifica en vivo al final del plan.

- [ ] **Step 1: Reemplazar `ConversationMemoryService.java` completo**

Reemplazar todo el contenido de `app/src/main/java/com/aicompany/core/service/ConversationMemoryService.java` por:

```java
package com.aicompany.core.service;

import com.aicompany.core.model.ConversationTurn;
import com.aicompany.core.model.LastMentioned;
import org.neo4j.driver.Driver;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Un solo hilo de conversación global ({@code Conversation {id:'MAIN'}}
 * — no hay concepto de usuario/sesión en esta app, un solo fundador
 * opera todo). Los mensajes ya no cuelgan directo de {@code Conversation}:
 * cada uno vive bajo un {@code Chat {date}} (uno por día calendario,
 * {@code MERGE} idempotente por fecha) — {@code (:Conversation)-[:HAS_CHAT]->
 * (:Chat)-[:HAS_MESSAGE]->(:Message)}. Esto convierte la conversación en
 * un grafo histórico real (se puede preguntar "¿qué hablamos el [día]?"
 * y "¿en qué días hablamos de X?", ver {@link #chatHistoryForDate}/
 * {@link #daysMentioning}) en vez de solo una lista plana de mensajes.
 *
 * <p>El "foco actual" ({@link #setLastMentioned}/{@link #lastMentioned})
 * sigue viviendo como propiedades directas del nodo {@code Conversation},
 * sin cambios — resuelve pronombres del turno actual ("esas"/"esos"),
 * un problema distinto de la memoria histórica por día. Los edges
 * {@code MENTIONS} del {@code Chat} de hoy hacia una entidad real
 * ({@link #recordChatMention}) son un rastro histórico *adicional*, no
 * un reemplazo del foco.
 */
@Service
public class ConversationMemoryService {

    private final Driver driver;
    private final Clock clock;

    public ConversationMemoryService(Driver driver, Clock clock) {
        this.driver = driver;
        this.clock = clock;
    }

    public void recordMessage(String role, String content) {
        var today = LocalDate.now(clock).toString();
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MERGE (c:Conversation {id:'MAIN'}) "
                                + "MERGE (c)-[:HAS_CHAT]->(chat:Chat {date:$today}) "
                                + "CREATE (msg:Message {id:$id, role:$role, content:$content, createdAt:$createdAt}) "
                                + "MERGE (chat)-[:HAS_MESSAGE]->(msg)",
                        Map.of(
                                "today", today,
                                "id", UUID.randomUUID().toString(),
                                "role", role,
                                "content", content,
                                "createdAt", Instant.now().toString()
                        ));
                return null;
            });
        }
    }

    public void setLastMentioned(String type, List<String> ids) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MERGE (c:Conversation {id:'MAIN'}) "
                                + "SET c.lastMentionedType=$type, c.lastMentionedIds=$ids, c.lastMentionedAt=$now",
                        Map.of(
                                "type", type,
                                "ids", ids,
                                "now", Instant.now().toString()
                        ));
                return null;
            });
        }
    }

    /**
     * Registra que el {@code Chat} de hoy mencionó estas entidades reales
     * — rastro histórico independiente del foco mutable de arriba, para
     * poder responder después "¿en qué días hablamos de MISSION-5?" (ver
     * {@link #daysMentioning}). {@code type} solo soporta los mismos dos
     * valores que ya soporta el foco ({@code "MISSION"}/{@code "CUSTOMER"}
     * — cualquier otro valor se trata como {@code Customer}, ver el switch
     * de abajo, mismo criterio simple de dos ramas ya usado en el resto
     * del proyecto). Sin efecto si {@code ids} está vacío.
     */
    public void recordChatMention(String type, List<String> ids) {

        if (ids.isEmpty()) {
            return;
        }

        var label = "MISSION".equals(type) ? "Mission" : "Customer";
        var today = LocalDate.now(clock).toString();

        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MERGE (c:Conversation {id:'MAIN'}) "
                                + "MERGE (c)-[:HAS_CHAT]->(chat:Chat {date:$today}) "
                                + "WITH chat UNWIND $ids AS id "
                                + "MATCH (e:" + label + " {id:id}) "
                                + "MERGE (chat)-[:MENTIONS]->(e)",
                        Map.of("today", today, "ids", ids));
                return null;
            });
        }
    }

    /**
     * Los últimos {@code limit} turnos reales, en orden cronológico
     * (el más viejo primero) — para darle al chat general continuidad
     * de charla real (p. ej. "recordá que mi color favorito es el
     * verde" seguido de "¿cuál es mi color favorito?"). Distinto del
     * "foco" de {@link #lastMentioned}, que resuelve referencias a
     * misiones, no continuidad conversacional. No filtra por
     * {@code Chat}/fecha a propósito: es una ventana de continuidad
     * inmediata sobre el hilo completo, no memoria histórica por día
     * (para eso está {@link #chatHistoryForDate}).
     */
    public List<ConversationTurn> recentMessages(int limit) {
        try (var session = driver.session()) {
            var records = session.run(
                    "MATCH (c:Conversation {id:'MAIN'})-[:HAS_CHAT]->(:Chat)-[:HAS_MESSAGE]->(m:Message) "
                            + "RETURN m.role AS role, m.content AS content "
                            + "ORDER BY m.createdAt DESC LIMIT $limit",
                    Map.of("limit", limit)
            ).list();

            var turns = records.stream()
                    .map(r -> new ConversationTurn(r.get("role").asString(), r.get("content").asString()))
                    .toList();

            return turns.reversed();
        }
    }

    /**
     * Todos los mensajes reales de un día calendario puntual (formato
     * {@code YYYY-MM-DD}), en orden cronológico — la respuesta real a
     * "¿qué hablamos el [día]?". Lista vacía si ese día no tiene ningún
     * {@code Chat} registrado (nunca lanza).
     */
    public List<ConversationTurn> chatHistoryForDate(String date) {
        try (var session = driver.session()) {
            var records = session.run(
                    "MATCH (chat:Chat {date:$date})-[:HAS_MESSAGE]->(m:Message) "
                            + "RETURN m.role AS role, m.content AS content "
                            + "ORDER BY m.createdAt",
                    Map.of("date", date)
            ).list();

            return records.stream()
                    .map(r -> new ConversationTurn(r.get("role").asString(), r.get("content").asString()))
                    .toList();
        }
    }

    /**
     * Las fechas reales (orden cronológico) en las que el {@code Chat}
     * de ese día mencionó la entidad {@code id} — ver
     * {@link #recordChatMention}. Sin importar el label real de la
     * entidad (Mission o Customer): las relaciones {@code MENTIONS} solo
     * apuntan a esos dos tipos, así que no hace falta filtrar por label
     * acá para leer.
     */
    public List<String> daysMentioning(String id) {
        try (var session = driver.session()) {
            return session.run(
                    "MATCH (chat:Chat)-[:MENTIONS]->(e {id:$id}) "
                            + "RETURN chat.date AS date ORDER BY chat.date",
                    Map.of("id", id)
            ).list(r -> r.get("date").asString());
        }
    }

    public Optional<LastMentioned> lastMentioned() {
        try (var session = driver.session()) {
            var records = session.run(
                    "MATCH (c:Conversation {id:'MAIN'}) "
                            + "WHERE c.lastMentionedIds IS NOT NULL "
                            + "RETURN c.lastMentionedType AS type, c.lastMentionedIds AS ids"
            ).list();

            return records.stream().findFirst().map(r -> new LastMentioned(
                    r.get("type").asString(),
                    r.get("ids").asList(v -> v.asString())
            ));
        }
    }

    /**
     * Reengancha los {@code Message} que quedaron colgando directo de
     * {@code Conversation} (de antes de que existiera {@code Chat}) bajo
     * el {@code Chat} correspondiente a la fecha real de su
     * {@code createdAt} (los primeros 10 caracteres de un
     * {@code Instant} en formato ISO-8601, {@code "YYYY-MM-DD..."}, son
     * exactamente la fecha calendario). Idempotente — corre en cada
     * arranque vía {@code CompanyMemoryInitializer}; un {@code Message}
     * que ya cuelga de un {@code Chat} no matchea el patrón
     * ({@code (:Conversation)-[:HAS_MESSAGE]->(:Message)} directo) y se
     * ignora.
     */
    public void migrateMessagesToChats() {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH (c:Conversation {id:'MAIN'})-[r:HAS_MESSAGE]->(m:Message) "
                                + "WITH c, r, m, left(m.createdAt, 10) AS date "
                                + "MERGE (c)-[:HAS_CHAT]->(chat:Chat {date:date}) "
                                + "MERGE (chat)-[:HAS_MESSAGE]->(m) "
                                + "DELETE r");
                return null;
            });
        }
    }
}
```

- [ ] **Step 2: Ejecutar la suite completa y confirmar que sigue en verde**

Run: `cd app && mvn test`
Expected: BUILD SUCCESS, mismo número de tests que antes de este task (235) — este task no agrega tests directos (integración Neo4j) y no debería romper ningún test existente (`ConversationMemoryService` se mockea en todos los tests que lo usan, nunca se construye directo con `new`).

- [ ] **Step 3: Cablear la migración en `CompanyMemoryInitializer`**

Reemplazar el contenido completo de `app/src/main/java/com/aicompany/core/config/CompanyMemoryInitializer.java` por:

```java
package com.aicompany.core.config;

import com.aicompany.core.service.CompanyMemoryService;
import com.aicompany.core.service.ConversationMemoryService;
import com.aicompany.core.service.MissionMemoryService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class CompanyMemoryInitializer {
    private final CompanyMemoryService memory;
    private final MissionMemoryService missionMemory;
    private final ConversationMemoryService conversationMemory;

    public CompanyMemoryInitializer(
            CompanyMemoryService memory,
            MissionMemoryService missionMemory,
            ConversationMemoryService conversationMemory) {
        this.memory = memory;
        this.missionMemory = missionMemory;
        this.conversationMemory = conversationMemory;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeAfterReady() {
        memory.initialize();
        missionMemory.backfillMissionEnvironment();
        conversationMemory.migrateMessagesToChats();
    }
}
```

- [ ] **Step 4: Ejecutar la suite completa y confirmar que sigue en verde**

Run: `cd app && mvn test`
Expected: BUILD SUCCESS, 235/235 (sin tests nuevos en este task, `CompanyMemoryInitializer` tampoco tiene test directo — mismo criterio que el resto del arranque, sin `@SpringBootTest` en este proyecto).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/ConversationMemoryService.java \
        app/src/main/java/com/aicompany/core/config/CompanyMemoryInitializer.java
git commit -m "Chat por dia calendario en ConversationMemoryService + migracion de Message"
```

---

### Task 2: `CompanyTools.getChatHistory`/`getDaysMentioning` + registrar menciones en los call-sites existentes

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/CompanyTools.java`
- Modify: `app/src/test/java/com/aicompany/core/service/CompanyToolsTest.java`

**Interfaces:**
- Consumes: `ConversationMemoryService.recordChatMention(String, List<String>)`, `.chatHistoryForDate(String)`, `.daysMentioning(String)` (Task 1).
- Produces: `CompanyTools.getChatHistory(String date)`, `CompanyTools.getDaysMentioning(String id)` — usados por la Task 3.

`CompanyTools` tiene 6 puntos que ya llaman `conversationMemory.setLastMentioned(...)`: `getPendingApprovals` (línea ~179), `getFailedMissions` (~203), `getTestMissions` (~228), `getOpportunity` (~283), `getMission` (~352), `getActiveMissions` (~474). Cada uno gana una llamada a `recordChatMention` inmediatamente después, con el mismo `type`/`ids` que ya usa `setLastMentioned` — reusa exactamente la misma detección existente, sin lógica nueva de "qué se mencionó".

- [ ] **Step 1: Agregar `recordChatMention` después de cada `setLastMentioned` existente**

En `app/src/main/java/com/aicompany/core/service/CompanyTools.java`, reemplazar (los 3 bloques casi idénticos de `getPendingApprovals`/`getFailedMissions`/`getTestMissions`):

```java
        conversationMemory.setLastMentioned(
                "MISSION",
                awaitingApproval.stream().map(MissionResponse::missionId).toList()
        );
```

por:

```java
        var awaitingApprovalIds = awaitingApproval.stream().map(MissionResponse::missionId).toList();
        conversationMemory.setLastMentioned("MISSION", awaitingApprovalIds);
        conversationMemory.recordChatMention("MISSION", awaitingApprovalIds);
```

```java
        conversationMemory.setLastMentioned(
                "MISSION",
                failed.stream().map(MissionResponse::missionId).toList()
        );
```

por:

```java
        var failedIds = failed.stream().map(MissionResponse::missionId).toList();
        conversationMemory.setLastMentioned("MISSION", failedIds);
        conversationMemory.recordChatMention("MISSION", failedIds);
```

```java
        conversationMemory.setLastMentioned(
                "MISSION",
                test.stream().map(MissionResponse::missionId).toList()
        );
```

por:

```java
        var testIds = test.stream().map(MissionResponse::missionId).toList();
        conversationMemory.setLastMentioned("MISSION", testIds);
        conversationMemory.recordChatMention("MISSION", testIds);
```

En `getOpportunity`, reemplazar:

```java
        if (!candidates.isEmpty()) {
            conversationMemory.setLastMentioned(
                    "CUSTOMER",
                    candidates.stream().map(LeadResponse::id).toList()
            );
        }
```

por:

```java
        if (!candidates.isEmpty()) {
            var candidateIds = candidates.stream().map(LeadResponse::id).toList();
            conversationMemory.setLastMentioned("CUSTOMER", candidateIds);
            conversationMemory.recordChatMention("CUSTOMER", candidateIds);
        }
```

En `getMission`, reemplazar:

```java
        conversationMemory.setLastMentioned("MISSION", List.of(missionId));
```

(la que está dentro de `getMission`, justo después de `if (details.isEmpty())`) por:

```java
        conversationMemory.setLastMentioned("MISSION", List.of(missionId));
        conversationMemory.recordChatMention("MISSION", List.of(missionId));
```

En `getActiveMissions`, reemplazar:

```java
        conversationMemory.setLastMentioned(
                "MISSION",
                active.stream().map(MissionResponse::missionId).toList()
        );
```

por:

```java
        var activeIds = active.stream().map(MissionResponse::missionId).toList();
        conversationMemory.setLastMentioned("MISSION", activeIds);
        conversationMemory.recordChatMention("MISSION", activeIds);
```

- [ ] **Step 2: Agregar `getChatHistory`/`getDaysMentioning` al final de la clase**

En `app/src/main/java/com/aicompany/core/service/CompanyTools.java`, agregar estos dos métodos nuevos, antes de `formatCandidate` (el último método de la clase):

```java
    /**
     * Transcript real de un día calendario puntual (formato
     * {@code YYYY-MM-DD}) — la respuesta a "¿qué hablamos el [día]?".
     * Nunca inventa contenido: si ese día no tiene ningún {@code Chat}
     * registrado, lo dice explícitamente.
     */
    public String getChatHistory(String date) {

        var messages = conversationMemory.chatHistoryForDate(date);

        if (messages.isEmpty()) {
            return "No hubo conversación registrada ese día.";
        }

        var lines = messages.stream()
                .map(m -> m.role() + ": " + m.content())
                .collect(Collectors.joining(" | "));

        return "Charla del " + date + " (" + messages.size() + " mensaje(s)): " + lines;
    }

    /**
     * Los días reales en los que se mencionó esta entidad en el chat —
     * la respuesta a "¿en qué días hablamos de MISSION-X?". Acepta
     * cualquier id real (misión u otra entidad), no solo
     * {@code MISSION-<n>} — el atajo determinista de keywords en
     * {@code ChatIntentRouter} solo lo dispara con un
     * {@code MISSION-<id>} explícito, pero el chat general del CEO
     * puede pedirlo con cualquier id real vía la herramienta.
     */
    public String getDaysMentioning(String id) {

        var dates = conversationMemory.daysMentioning(id);

        if (dates.isEmpty()) {
            return "No encontré menciones de " + id + " en el historial de chat.";
        }

        return "Hablamos de " + id + " en " + dates.size() + " día(s): " + String.join(", ", dates) + ".";
    }
```

- [ ] **Step 3: Escribir los tests nuevos en `CompanyToolsTest`**

Agregar estos 6 tests al final de `app/src/test/java/com/aicompany/core/service/CompanyToolsTest.java`, antes del cierre de la clase:

```java
    @Test
    void getChatHistoryFormatsRealTranscriptForThatDate() {
        when(conversationMemory.chatHistoryForDate("2026-09-19")).thenReturn(List.of(
                new com.aicompany.core.model.ConversationTurn("user", "hola"),
                new com.aicompany.core.model.ConversationTurn("ceo", "hola, en qué te ayudo")
        ));

        var response = tools.getChatHistory("2026-09-19");

        assertTrue(response.contains("2026-09-19"));
        assertTrue(response.contains("user: hola"));
        assertTrue(response.contains("ceo: hola, en qué te ayudo"));
    }

    @Test
    void getChatHistoryReturnsDeterministicMessageWhenNoChatThatDay() {
        when(conversationMemory.chatHistoryForDate("2020-01-01")).thenReturn(List.of());

        var response = tools.getChatHistory("2020-01-01");

        assertEquals("No hubo conversación registrada ese día.", response);
    }

    @Test
    void getDaysMentioningFormatsRealDates() {
        when(conversationMemory.daysMentioning("MISSION-5")).thenReturn(
                List.of("2026-09-10", "2026-09-12")
        );

        var response = tools.getDaysMentioning("MISSION-5");

        assertTrue(response.contains("MISSION-5"));
        assertTrue(response.contains("2026-09-10"));
        assertTrue(response.contains("2026-09-12"));
    }

    @Test
    void getDaysMentioningReturnsDeterministicMessageWhenNeverMentioned() {
        when(conversationMemory.daysMentioning("MISSION-999")).thenReturn(List.of());

        var response = tools.getDaysMentioning("MISSION-999");

        assertEquals("No encontré menciones de MISSION-999 en el historial de chat.", response);
    }

    @Test
    void getPendingApprovalsAlsoRecordsChatMention() {
        var mission = new MissionResponse(
                "MISSION-1", MissionStatus.AWAITING_INVESTOR, "PRODUCTION", 0, "x", "y", Instant.now()
        );
        when(missionMemory.findAll(50)).thenReturn(List.of(mission));

        tools.getPendingApprovals();

        verify(conversationMemory).recordChatMention("MISSION", List.of("MISSION-1"));
    }

    @Test
    void getActiveMissionsAlsoRecordsChatMention() {
        var mission = new MissionResponse(
                "MISSION-2", MissionStatus.DELEGATING, "PRODUCTION", 40, "x", "y", Instant.now()
        );
        when(missionMemory.findAll(50)).thenReturn(List.of(mission));

        tools.getActiveMissions();

        verify(conversationMemory).recordChatMention("MISSION", List.of("MISSION-2"));
    }
```

- [ ] **Step 4: Ejecutar los tests nuevos y confirmar que pasan**

Run: `cd app && mvn test -Dtest=CompanyToolsTest`
Expected: PASS, todos los tests de esta clase en verde (los 6 nuevos más los ya existentes).

- [ ] **Step 5: Ejecutar la suite completa**

Run: `cd app && mvn test`
Expected: BUILD SUCCESS, 241/241 (235 + 6 nuevos).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/CompanyTools.java \
        app/src/test/java/com/aicompany/core/service/CompanyToolsTest.java
git commit -m "Agregar getChatHistory/getDaysMentioning y registrar MENTIONS en CompanyTools"
```

---

### Task 3: Consultas nuevas en `ChatIntentRouter` (`CHAT_HISTORY`, `MENTIONED_DATES`)

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java`
- Modify: `app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java`

**Interfaces:**
- Consumes: `CompanyTools.getChatHistory(String)`/`.getDaysMentioning(String)` (Task 2); bean `Clock` ya existente en `CoreConfig` (Task 4 de la ronda de NVIDIA).
- Produces: `ChatIntentRouter(MissionService, CeoService, MissionMemoryService, OpportunityMemoryService, CompanyMemoryService, ConversationMemoryService, CompanyTools, Clock)` — constructor gana `Clock` como 8º parámetro; casos `CHAT_HISTORY`/`MENTIONED_DATES` en `answerMemoryTopic` — usados por la Task 4 (schema de `CeoService`).

- [ ] **Step 1: Agregar `Clock` al constructor y los patrones/constantes nuevos**

En `app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java`, agregar los imports:

```java
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
```

Reemplazar el campo y constructor:

```java
    private final MissionService missionService;
    private final CeoService ceoService;
    private final MissionMemoryService missionMemory;
    private final OpportunityMemoryService opportunityMemory;
    private final CompanyMemoryService companyMemory;
    private final ConversationMemoryService conversationMemory;
    private final CompanyTools companyTools;

    public ChatIntentRouter(
            MissionService missionService,
            CeoService ceoService,
            MissionMemoryService missionMemory,
            OpportunityMemoryService opportunityMemory,
            CompanyMemoryService companyMemory,
            ConversationMemoryService conversationMemory,
            CompanyTools companyTools) {

        this.missionService = missionService;
        this.ceoService = ceoService;
        this.missionMemory = missionMemory;
        this.opportunityMemory = opportunityMemory;
        this.companyMemory = companyMemory;
        this.conversationMemory = conversationMemory;
        this.companyTools = companyTools;
    }
```

por:

```java
    private final MissionService missionService;
    private final CeoService ceoService;
    private final MissionMemoryService missionMemory;
    private final OpportunityMemoryService opportunityMemory;
    private final CompanyMemoryService companyMemory;
    private final ConversationMemoryService conversationMemory;
    private final CompanyTools companyTools;
    private final Clock clock;

    public ChatIntentRouter(
            MissionService missionService,
            CeoService ceoService,
            MissionMemoryService missionMemory,
            OpportunityMemoryService opportunityMemory,
            CompanyMemoryService companyMemory,
            ConversationMemoryService conversationMemory,
            CompanyTools companyTools,
            Clock clock) {

        this.missionService = missionService;
        this.ceoService = ceoService;
        this.missionMemory = missionMemory;
        this.opportunityMemory = opportunityMemory;
        this.companyMemory = companyMemory;
        this.conversationMemory = conversationMemory;
        this.companyTools = companyTools;
        this.clock = clock;
    }
```

Agregar esta constante nueva, junto a las demás `Pattern` ya existentes (por ejemplo, después de `RECENT_DECISIONS_QUERY`):

```java
    // Fecha explícita DD/MM o DD/MM/YYYY -- deliberadamente acotado, ver
    // el spec: nada de nombres de día de la semana ni expresiones
    // relativas más complejas ("la semana pasada").
    private static final Pattern EXPLICIT_DATE =
            Pattern.compile("\\b(\\d{1,2})/(\\d{1,2})(?:/(\\d{4}))?\\b");
```

- [ ] **Step 2: Agregar `resolveChatHistoryQuery`/`handleChatHistoryQuery`**

Agregar estos dos métodos nuevos después de `handleMissionDetailsQuery` (justo antes de `resolveOpportunityForMissionQuery`):

```java
    /**
     * "¿qué hablamos el [día]?" — memoria histórica real por día
     * calendario (ver {@code ConversationMemoryService.chatHistoryForDate}),
     * distinta de la ventana de continuidad inmediata
     * ({@code recentMessages}). Exige tanto una palabra de "charla
     * pasada" (para no disparar con cualquier mención suelta de "hoy"/
     * "ayer") como una referencia de día reconocida.
     */
    private Optional<String> resolveChatHistoryQuery(String message) {

        var normalized = normalize(message);

        var talksAboutHistory = normalized.contains("hablamos")
                || normalized.contains("hablaste")
                || normalized.contains("charlamos")
                || normalized.contains("conversamos")
                || normalized.contains("dijimos");

        if (!talksAboutHistory) {
            return Optional.empty();
        }

        var explicitDateMatcher = EXPLICIT_DATE.matcher(message);

        if (explicitDateMatcher.find()) {

            var day = Integer.parseInt(explicitDateMatcher.group(1));
            var month = Integer.parseInt(explicitDateMatcher.group(2));
            var year = explicitDateMatcher.group(3) != null
                    ? Integer.parseInt(explicitDateMatcher.group(3))
                    : LocalDate.now(clock).getYear();

            try {
                return Optional.of(LocalDate.of(year, month, day).toString());
            } catch (DateTimeException ex) {
                return Optional.empty();
            }
        }

        if (normalized.contains("ayer")) {
            return Optional.of(LocalDate.now(clock).minusDays(1).toString());
        }

        if (normalized.contains("hoy")) {
            return Optional.of(LocalDate.now(clock).toString());
        }

        return Optional.empty();
    }

    private String handleChatHistoryQuery(String date) {

        log.info("CHAT_INTENT_CHAT_HISTORY date={}", date);

        return companyTools.getChatHistory(date);
    }
```

- [ ] **Step 3: Agregar `resolveMentionedDatesQuery`/`handleMentionedDatesQuery`**

Agregar estos dos métodos nuevos justo después de `handleOpportunityForMissionQuery` (antes de `resolveCustomerReference`):

```java
    /**
     * "¿en qué días hablamos de MISSION-X?" — mismo criterio que
     * {@link #resolveOpportunityForMissionQuery}: solo actúa con un
     * {@code MISSION-<id>} explícito en el mensaje.
     */
    private Optional<String> resolveMentionedDatesQuery(String message) {

        var normalized = normalize(message);

        var hasKeyword = normalized.contains("dias")
                && (normalized.contains("hablamos") || normalized.contains("mencion"));

        if (!hasKeyword) {
            return Optional.empty();
        }

        var missionIdMatcher = MISSION_ID.matcher(message);

        if (!missionIdMatcher.find()) {
            return Optional.empty();
        }

        return Optional.of(missionIdMatcher.group(1).toUpperCase(Locale.ROOT));
    }

    private String handleMentionedDatesQuery(String id) {

        log.info("CHAT_INTENT_MENTIONED_DATES id={}", id);

        return companyTools.getDaysMentioning(id);
    }
```

- [ ] **Step 4: Cablear los dos chequeos nuevos en `resolve()`**

En `app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java`, dentro de `resolve(String message)`, reemplazar:

```java
        var opportunityForMissionId = resolveOpportunityForMissionQuery(message);

        if (opportunityForMissionId.isPresent()) {
            return handleOpportunityForMissionQuery(opportunityForMissionId.get());
        }

        var missionDetailsId = resolveMissionDetailsQuery(message);

        if (missionDetailsId.isPresent()) {
            return handleMissionDetailsQuery(missionDetailsId.get());
        }
```

por:

```java
        var opportunityForMissionId = resolveOpportunityForMissionQuery(message);

        if (opportunityForMissionId.isPresent()) {
            return handleOpportunityForMissionQuery(opportunityForMissionId.get());
        }

        var mentionedDatesId = resolveMentionedDatesQuery(message);

        if (mentionedDatesId.isPresent()) {
            return handleMentionedDatesQuery(mentionedDatesId.get());
        }

        var missionDetailsId = resolveMissionDetailsQuery(message);

        if (missionDetailsId.isPresent()) {
            return handleMissionDetailsQuery(missionDetailsId.get());
        }

        var chatHistoryDate = resolveChatHistoryQuery(message);

        if (chatHistoryDate.isPresent()) {
            return handleChatHistoryQuery(chatHistoryDate.get());
        }
```

(`resolveMentionedDatesQuery` va antes que `resolveMissionDetailsQuery` a propósito: ambos pueden matchear un mensaje con "días" + "MISSION-<id>", pero `resolveMissionDetailsQuery` exige palabras como "detalles"/"progreso" que no se solapan en la práctica — el orden solo documenta cuál es más específico, no resuelve una colisión real verificada).

- [ ] **Step 5: Agregar los dos casos nuevos en `answerMemoryTopic`**

En `app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java`, dentro de `answerMemoryTopic`, reemplazar:

```java
            case "OPPORTUNITY_DETAILS" -> (id == null || id.isBlank())
                    ? "Para consultar los prospectos de una oportunidad necesito el MISSION-<id> exacto."
                    : companyTools.getOpportunity(id);
            default -> "Dato no reconocido: " + topic + ".";
```

por:

```java
            case "OPPORTUNITY_DETAILS" -> (id == null || id.isBlank())
                    ? "Para consultar los prospectos de una oportunidad necesito el MISSION-<id> exacto."
                    : companyTools.getOpportunity(id);
            case "CHAT_HISTORY" -> (id == null || id.isBlank())
                    ? "Para consultar la charla de un día necesito la fecha (por ejemplo 2026-09-19)."
                    : companyTools.getChatHistory(id);
            case "MENTIONED_DATES" -> (id == null || id.isBlank())
                    ? "Para consultar en qué días hablamos de algo necesito su id exacto."
                    : companyTools.getDaysMentioning(id);
            default -> "Dato no reconocido: " + topic + ".";
```

- [ ] **Step 6: Actualizar `ChatIntentRouterTest` — constructor + tests nuevos**

En `app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java`, agregar los imports:

```java
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
```

(`Instant`/`ZoneOffset` pueden ya estar importados — si `Instant` ya está, no duplicar el import).

Reemplazar la construcción de `router`:

```java
    private final ChatIntentRouter router = new ChatIntentRouter(
            missionService, ceoService, missionMemory, opportunityMemory, companyMemory, conversationMemory, companyTools
    );
```

por:

```java
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-19T15:00:00Z"), ZoneOffset.UTC);

    private final ChatIntentRouter router = new ChatIntentRouter(
            missionService, ceoService, missionMemory, opportunityMemory, companyMemory, conversationMemory, companyTools, clock
    );
```

Agregar estos 5 tests nuevos, al final de la clase (antes del cierre):

```java
    @Test
    void routesChatHistoryQueryForToday() {
        when(conversationMemory.chatHistoryForDate("2026-09-19")).thenReturn(List.of(
                new com.aicompany.core.model.ConversationTurn("user", "hola")
        ));

        var response = router.route("¿qué hablamos hoy?");

        assertTrue(response.contains("2026-09-19"));
        verifyNoInteractions(ceoService);
    }

    @Test
    void routesChatHistoryQueryForYesterday() {
        when(conversationMemory.chatHistoryForDate("2026-09-18")).thenReturn(List.of());

        var response = router.route("¿de qué hablamos ayer?");

        assertEquals("No hubo conversación registrada ese día.", response);
        verifyNoInteractions(ceoService);
    }

    @Test
    void routesChatHistoryQueryForExplicitDate() {
        when(conversationMemory.chatHistoryForDate("2026-01-15")).thenReturn(List.of());

        router.route("¿qué hablamos el 15/01/2026?");

        verify(conversationMemory).chatHistoryForDate("2026-01-15");
    }

    @Test
    void routesMentionedDatesQueryWithExplicitMissionId() {
        when(conversationMemory.daysMentioning("MISSION-5")).thenReturn(List.of("2026-09-10"));

        var response = router.route("¿en qué días hablamos de MISSION-5?");

        assertTrue(response.contains("2026-09-10"));
        verifyNoInteractions(ceoService);
    }

    @Test
    void doesNotRouteToChatHistoryWithoutADayReference() {
        // "hablamos" solo, sin "hoy"/"ayer"/fecha explícita, no alcanza --
        // cae al chat general (nunca se adivina qué día).
        when(ceoService.chat(anyString(), anyString(), any(), anyString(), any())).thenReturn("ignored");
        when(companyMemory.agentName("ceo")).thenReturn(Optional.of("Alex"));
        when(companyMemory.teamRosterDescription()).thenReturn("- Sofia (Sales)");

        router.route("¿de qué hablamos la última vez?");

        verify(ceoService).chat(anyString(), anyString(), any(), anyString(), any());
    }
```

Extender el test `passesCompanyMemoryQueryCallbackThatResolvesAllKnownTopics` agregando estas dos líneas justo antes de la línea `assertTrue(companyMemoryQuery.apply("ALGO_INEXISTENTE", null).contains("Dato no reconocido"));`:

```java
        when(conversationMemory.chatHistoryForDate("2026-09-19")).thenReturn(List.of());
        when(conversationMemory.daysMentioning("MISSION-1")).thenReturn(List.of());
        assertTrue(companyMemoryQuery.apply("CHAT_HISTORY", "2026-09-19").contains("No hubo conversación"));
        assertTrue(companyMemoryQuery.apply("MENTIONED_DATES", "MISSION-1").contains("No encontré menciones"));
```

- [ ] **Step 7: Ejecutar los tests de esta clase y confirmar que pasan**

Run: `cd app && mvn test -Dtest=ChatIntentRouterTest`
Expected: PASS, todos los tests en verde (los 5 nuevos, más las 2 líneas agregadas al test existente, más todos los ya existentes).

- [ ] **Step 8: Ejecutar la suite completa**

Run: `cd app && mvn test`
Expected: BUILD SUCCESS, 246/246 (241 + 5 nuevos).

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java \
        app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java
git commit -m "Chat: agregar CHAT_HISTORY y MENTIONED_DATES al atajo de keywords"
```

---

### Task 4: Exponer `CHAT_HISTORY`/`MENTIONED_DATES` en `query_company_memory`

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/CeoService.java`

**Interfaces:**
- Consumes: nada nuevo — solo agrega 2 valores al `enum` del schema y su descripción; `answerMemoryTopic` (Task 3) ya sabe resolverlos.

Esta tarea es puramente de configuración del schema — no hace falta ningún test nuevo (`CeoServiceCompanyMemoryTopicTest` solo prueba el parseo genérico de `topic`/`id` desde el JSON del tool-call, no topics específicos; ese parseo ya funciona para cualquier string de topic sin cambios).

- [ ] **Step 1: Agregar los 2 topics nuevos al enum del schema**

En `app/src/main/java/com/aicompany/core/service/CeoService.java`, dentro de `COMPANY_MEMORY_TOOLS`, reemplazar:

```java
                                                    "enum", List.of(
                                                            "AGENT_STATUS",
                                                            "MISSIONS_NEEDING_ATTENTION",
                                                            "FAILED_MISSIONS",
                                                            "TEST_MISSIONS",
                                                            "LAST_MENTIONED",
                                                            "OPPORTUNITIES",
                                                            "LEADS",
                                                            "COMPANY_PROFIT",
                                                            "COMPANY_STATUS",
                                                            "MISSION_DETAILS",
                                                            "OPPORTUNITY_DETAILS",
                                                            "RECENT_ACTIVITY",
                                                            "RECENT_DECISIONS",
                                                            "ACTIVE_MISSIONS"
                                                    ),
```

por:

```java
                                                    "enum", List.of(
                                                            "AGENT_STATUS",
                                                            "MISSIONS_NEEDING_ATTENTION",
                                                            "FAILED_MISSIONS",
                                                            "TEST_MISSIONS",
                                                            "LAST_MENTIONED",
                                                            "OPPORTUNITIES",
                                                            "LEADS",
                                                            "COMPANY_PROFIT",
                                                            "COMPANY_STATUS",
                                                            "MISSION_DETAILS",
                                                            "OPPORTUNITY_DETAILS",
                                                            "RECENT_ACTIVITY",
                                                            "RECENT_DECISIONS",
                                                            "ACTIVE_MISSIONS",
                                                            "CHAT_HISTORY",
                                                            "MENTIONED_DATES"
                                                    ),
```

- [ ] **Step 2: Agregar la descripción de los 2 topics nuevos**

En el mismo bloque, reemplazar el final de la cadena de descripción:

```java
                                                            + "ACTIVE_MISSIONS: "
                                                            + "misiones reales "
                                                            + "en curso (ni "
                                                            + "esperando "
                                                            + "aprobación, ni "
                                                            + "fallidas, ni "
                                                            + "completadas, ni "
                                                            + "canceladas) con "
                                                            + "su status y "
                                                            + "progreso real."
                                                    ),
```

por:

```java
                                                            + "ACTIVE_MISSIONS: "
                                                            + "misiones reales "
                                                            + "en curso (ni "
                                                            + "esperando "
                                                            + "aprobación, ni "
                                                            + "fallidas, ni "
                                                            + "completadas, ni "
                                                            + "canceladas) con "
                                                            + "su status y "
                                                            + "progreso real. "
                                                            + "CHAT_HISTORY: "
                                                            + "transcript real "
                                                            + "de la charla de "
                                                            + "un día puntual -- "
                                                            + "requiere el "
                                                            + "parámetro id con "
                                                            + "la fecha exacta "
                                                            + "en formato "
                                                            + "YYYY-MM-DD. "
                                                            + "MENTIONED_DATES: "
                                                            + "los días reales "
                                                            + "en los que se "
                                                            + "mencionó una "
                                                            + "entidad puntual "
                                                            + "en el chat -- "
                                                            + "requiere el "
                                                            + "parámetro id con "
                                                            + "el id real de "
                                                            + "esa entidad "
                                                            + "(p. ej. un "
                                                            + "MISSION-<numero>)."
                                                    ),
```

- [ ] **Step 3: Ejecutar la suite completa**

Run: `cd app && mvn test`
Expected: BUILD SUCCESS, 246/246 (sin cambios en el conteo — este task no agrega tests).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/CeoService.java
git commit -m "Exponer CHAT_HISTORY y MENTIONED_DATES en query_company_memory"
```

---

## Verificación en vivo (después de completar las 4 tareas)

1. **Migración**: reconstruir/redeploy Docker, confirmar con Cypher que los `Message` reales ya existentes (de todas las rondas anteriores de esta sesión) quedaron reenganchados bajo `Chat` nodes con la fecha correcta de su `createdAt`, y que `(:Conversation)-[:HAS_MESSAGE]->(:Message)` directo ya no existe (0 resultados).
2. **`recordMessage` escribe bajo el `Chat` de hoy**: mandar un mensaje real por el chat, confirmar con Cypher que aterrizó bajo `(:Chat {date:'<hoy>'})`, no directo de `Conversation`.
3. **`"¿qué hablamos hoy?"`**: debe traer el mensaje real recién mandado.
4. **`"¿de qué hablamos ayer?"`**: si no hay nada, confirmar el mensaje determinista "No hubo conversación registrada ese día."; si hay migración de datos viejos, confirmar que trae contenido real de esa fecha.
5. **`"¿en qué días hablamos de MISSION-X?"`** contra una misión real que ya se haya mencionado en consultas anteriores (p. ej. `getActiveMissions`/`getPendingApprovals`) — confirmar que devuelve al menos la fecha de hoy.
6. Las 3 preguntas anteriores con `CHAT_INTENT_CHAT_HISTORY`/`CHAT_INTENT_MENTIONED_DATES` en el log y **cero** `CEO_CHAT` (100% determinista).
7. Documentar los resultados reales en `CLAUDE.md`, siguiendo la convención ya establecida del proyecto.
