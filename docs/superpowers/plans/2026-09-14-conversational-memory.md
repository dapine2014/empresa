# Memoria conversacional del Company Chat — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** El Company Chat resuelve referencias conversacionales ("esas"/"esos" misiones) determinísticamente contra un foco de entidades mencionadas recientemente, persistido en Neo4j — nunca pasando historial crudo al LLM.

**Architecture:** Nuevo `ConversationMemoryService` (nodos `Conversation`/`Message` en Neo4j, más el "foco actual" como propiedades del nodo `Conversation`). `ChatIntentRouter` gana un chequeo de pronombre demostrativo antes del matcheo de keywords existente: con foco + predicado reconocido resuelve 100% determinístico contra el dato real actual (`MissionMemoryService.findByIds`, nuevo); sin predicado reconocido cae al chat general con un topic nuevo `LAST_MENTIONED` grounded. Todo mensaje (entrante y respuesta) se persiste, sin importar qué camino del router lo resolvió.

**Tech Stack:** Java 21, Spring Boot 4.1, Neo4j (driver plano, Cypher a mano), JUnit 5 + Mockito.

**Spec:** `docs/superpowers/specs/2026-09-14-conversational-memory-design.md`

## Global Constraints

- Un solo hilo de conversación global (`Conversation {id:'MAIN'}`), sin sesiones por usuario.
- Persistencia en Neo4j, no en memoria del proceso.
- Foco soportado en v1: solo `type="MISSION"`.
- Nunca pasar historial de texto crudo al LLM — el fallback a chat general debe seguir siendo grounded vía `query_company_memory`.
- Servicios que solo hablan con Neo4j (`ConversationMemoryService`, cambios en `MissionMemoryService`) no llevan test unitario directo — mismo criterio ya establecido en todo el proyecto para los `*MemoryService`.
- Índice `RANGE` en `Message.createdAt` debe crearse desde el primer commit de este plan (lección de la sesión de profiling de Neo4j: no repetir el patrón de `latestTaskPerAgent()` que ordena en memoria por falta de índice).

---

## Task 1: `ConversationMemoryService` + `LastMentioned` + esquema Neo4j

**Files:**
- Create: `app/src/main/java/com/aicompany/core/model/LastMentioned.java`
- Create: `app/src/main/java/com/aicompany/core/service/ConversationMemoryService.java`
- Modify: `app/src/main/java/com/aicompany/core/service/CompanyMemoryService.java` (agregar constraints/índice al final de `initializeSchema()`)

**Interfaces:**
- Produces: `LastMentioned(String type, List<String> ids)` (record).
- Produces: `ConversationMemoryService.recordMessage(String role, String content)` — `void`.
- Produces: `ConversationMemoryService.setLastMentioned(String type, List<String> ids)` — `void`.
- Produces: `ConversationMemoryService.lastMentioned()` — `Optional<LastMentioned>`.

- [ ] **Step 1: Crear el record `LastMentioned`**

```java
package com.aicompany.core.model;

import java.util.List;

/**
 * "Foco" actual de la conversación del Command Center: qué entidades
 * mencionó la última consulta de listado (p. ej. una lista de misiones)
 * — permite resolver referencias como "esas"/"esos" en el mensaje
 * siguiente sin pasarle historial de texto al LLM. Único {@code type}
 * soportado hoy: {@code "MISSION"}.
 */
public record LastMentioned(String type, List<String> ids) {
}
```

- [ ] **Step 2: Crear `ConversationMemoryService`**

```java
package com.aicompany.core.service;

import com.aicompany.core.model.LastMentioned;
import org.neo4j.driver.Driver;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Un solo hilo de conversación global ({@code Conversation {id:'MAIN'}}
 * — no hay concepto de usuario/sesión en esta app, un solo fundador
 * opera todo). Cada mensaje (entrante o respuesta final) se persiste vía
 * {@link #recordMessage}, sin importar qué camino de
 * {@code ChatIntentRouter} lo resolvió.
 *
 * <p>El "foco actual" ({@link #setLastMentioned}/{@link #lastMentioned})
 * vive como propiedades directas del nodo {@code Conversation}, no como
 * un nodo aparte -- es un valor mutable de "último estado", no un hecho
 * histórico que valga versionar. Permite que el router resuelva
 * referencias conversacionales ("esas"/"esos") consultando el dato real
 * actual de esas entidades, nunca el texto de una respuesta anterior que
 * puede estar desactualizado.
 */
@Service
public class ConversationMemoryService {

    private final Driver driver;

    public ConversationMemoryService(Driver driver) {
        this.driver = driver;
    }

    public void recordMessage(String role, String content) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MERGE (c:Conversation {id:'MAIN'}) "
                                + "CREATE (msg:Message {id:$id, role:$role, content:$content, createdAt:$createdAt}) "
                                + "MERGE (c)-[:HAS_MESSAGE]->(msg)",
                        Map.of(
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
}
```

- [ ] **Step 3: Agregar constraints + índice al esquema**

En `CompanyMemoryService.initializeSchema()`, dentro del mismo `try (var session = driver.session())`, agregar después de la última línea existente (`session.run("CREATE CONSTRAINT strategy_id ...")`):

```java
            session.run("CREATE CONSTRAINT conversation_id IF NOT EXISTS FOR (c:Conversation) REQUIRE c.id IS UNIQUE").consume();
            session.run("CREATE CONSTRAINT message_id IF NOT EXISTS FOR (msg:Message) REQUIRE msg.id IS UNIQUE").consume();
            session.run("CREATE INDEX message_created_at IF NOT EXISTS FOR (msg:Message) ON (msg.createdAt)").consume();
```

- [ ] **Step 4: Compilar y correr toda la suite (sin tests nuevos en este task — servicios Neo4j sin test directo, mismo criterio del resto del proyecto)**

Run: `cd app && mvn test`
Expected: BUILD SUCCESS, mismo número de tests que antes de este task (no debería haber ninguno roto).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/model/LastMentioned.java app/src/main/java/com/aicompany/core/service/ConversationMemoryService.java app/src/main/java/com/aicompany/core/service/CompanyMemoryService.java
git commit -m "Agregar ConversationMemoryService y esquema Neo4j para memoria conversacional"
```

---

## Task 2: `MissionMemoryService.findByIds`

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/MissionMemoryService.java`

**Interfaces:**
- Consumes: nada nuevo (usa `MissionResponse`, `MissionStatus` ya existentes).
- Produces: `MissionMemoryService.findByIds(List<String> missionIds)` — `List<MissionResponse>`, mismo shape que `findAll`/`find` (con `environment` vía `coalesce`).

- [ ] **Step 1: Implementar `findByIds`**

Agregar en `MissionMemoryService.java`, cerca de `findAll`:

```java
    /**
     * Trae el dato real y actual de un conjunto puntual de misiones por
     * id -- usado por {@code ChatIntentRouter} para resolver referencias
     * conversacionales ("esas"/"esos") contra el estado real de las
     * misiones mencionadas, nunca contra el texto de una respuesta
     * anterior que puede estar desactualizado.
     */
    public List<MissionResponse> findByIds(List<String> missionIds) {
        try (var session = driver.session()) {
            return session.run(
                            "MATCH (m:Mission) WHERE m.id IN $ids RETURN m.id AS id, m.status AS status, " +
                                    "coalesce(m.environment, 'TEST') AS environment, " +
                                    "m.progress AS progress, m.currentStep AS step, " +
                                    "m.message AS message, m.updatedAt AS updatedAt",
                            Map.of("ids", missionIds))
                    .list(r -> new MissionResponse(
                            r.get("id").asString(),
                            MissionStatus.valueOf(r.get("status").asString()),
                            r.get("environment").asString(),
                            r.get("progress").asInt(),
                            r.get("step").asString(),
                            r.get("message").asString(),
                            Instant.parse(r.get("updatedAt").asString())
                    ));
        }
    }
```

- [ ] **Step 2: Compilar (sin test directo, integración Neo4j)**

Run: `cd app && mvn -q compile`
Expected: sin errores.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/MissionMemoryService.java
git commit -m "Agregar MissionMemoryService.findByIds para resolver focos de misiones"
```

---

## Task 3: Resolución de referencias en `ChatIntentRouter` (TDD)

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java`
- Test: `app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java`

**Interfaces:**
- Consumes: `ConversationMemoryService.lastMentioned()`/`setLastMentioned(String, List<String>)`/`recordMessage(String, String)` (Task 1); `MissionMemoryService.findByIds(List<String>)` (Task 2).
- Produces: `ChatIntentRouter` con constructor de 7 parámetros (agrega `ConversationMemoryService conversationMemory` al final); comportamiento nuevo descrito abajo.

- [ ] **Step 1: Agregar el mock de `ConversationMemoryService` al test y actualizar el constructor del router bajo prueba**

En `ChatIntentRouterTest.java`, agregar el campo mock y pasarlo al constructor:

```java
    private final ConversationMemoryService conversationMemory = mock(ConversationMemoryService.class);

    private final ChatIntentRouter router = new ChatIntentRouter(
            missionService, ceoService, missionMemory, opportunityMemory, customerMemory, companyMemory, conversationMemory
    );
```

(reemplaza la construcción de `router` existente, que hoy tiene 6 argumentos).

- [ ] **Step 2: Escribir el test que falla — pronombre + predicado reconocido + foco existente**

Agregar en `ChatIntentRouterTest.java`:

```java
    @Test
    void resolvesReferenceToLastMentionedMissionsAgainstRealCurrentData() {
        // El ejemplo real reportado por el usuario: "¿Qué necesita mi
        // aprobación?" lista misiones, "pero esas están en prueba" debe
        // resolverse contra el dato REAL actual de esos ids puntuales,
        // no contra el texto de la respuesta anterior.
        when(conversationMemory.lastMentioned()).thenReturn(
                Optional.of(new LastMentioned("MISSION", List.of("MISSION-DEBUG-007", "MISSION-STRUCTURED-001")))
        );
        when(missionMemory.findByIds(List.of("MISSION-DEBUG-007", "MISSION-STRUCTURED-001"))).thenReturn(List.of(
                new MissionResponse("MISSION-DEBUG-007", MissionStatus.FAILED, "TEST", 100, "x", "y", Instant.now()),
                new MissionResponse("MISSION-STRUCTURED-001", MissionStatus.FAILED, "TEST", 100, "x", "y", Instant.now())
        ));

        var response = router.route("pero esas están en prueba");

        assertTrue(response.contains("Correcto"));
        assertTrue(response.contains("2"));
        verifyNoInteractions(ceoService);
    }

    @Test
    void resolvesReferenceWhenOnlySomeOfTheMentionedMissionsMatch() {
        when(conversationMemory.lastMentioned()).thenReturn(
                Optional.of(new LastMentioned("MISSION", List.of("MISSION-001", "MISSION-DEBUG-007")))
        );
        when(missionMemory.findByIds(List.of("MISSION-001", "MISSION-DEBUG-007"))).thenReturn(List.of(
                new MissionResponse("MISSION-001", MissionStatus.AWAITING_INVESTOR, "PRODUCTION", 95, "x", "y", Instant.now()),
                new MissionResponse("MISSION-DEBUG-007", MissionStatus.FAILED, "TEST", 100, "x", "y", Instant.now())
        ));

        var response = router.route("¿esas están en prueba?");

        assertTrue(response.contains("1"));
        assertTrue(response.contains("2"));
        assertTrue(response.contains("MISSION-DEBUG-007"));
        verifyNoInteractions(ceoService);
    }

    @Test
    void respondsDeterministicallyWhenReferenceHasNoFocusYet() {
        when(conversationMemory.lastMentioned()).thenReturn(Optional.empty());

        var response = router.route("¿esas están en prueba?");

        assertTrue(response.contains("No tengo claro"));
        verifyNoInteractions(ceoService);
    }

    @Test
    void fallsBackToGeneralChatWithLastMentionedToolWhenPredicateNotRecognized() {
        when(companyMemory.agentName("ceo")).thenReturn(Optional.of("Alex"));
        when(companyMemory.teamRosterDescription()).thenReturn("- Sofia (Sales)");
        when(conversationMemory.lastMentioned()).thenReturn(
                Optional.of(new LastMentioned("MISSION", List.of("MISSION-001")))
        );
        when(ceoService.chat(eq("Alex"), eq("- Sofia (Sales)"), eq("contame más sobre esas"), any()))
                .thenReturn("Ahí va el detalle.");

        var response = router.route("contame más sobre esas");

        assertEquals("Ahí va el detalle.", response);
    }

    @Test
    void recordsEveryMessageRegardlessOfWhichPathHandledIt() {
        when(missionMemory.findAll(50)).thenReturn(List.of());

        router.route("¿Qué necesita mi aprobación?");

        // No hace nada raro con el resultado; solo confirmamos que el
        // router graba el turno completo (entrada + respuesta final).
        verify(conversationMemory).recordMessage("user", "¿Qué necesita mi aprobación?");
        verify(conversationMemory).recordMessage(eq("ceo"), anyString());
    }

    @Test
    void testMissionsQueryRecordsItsResultsAsTheNewFocus() {
        var testMission = new MissionResponse("MISSION-DEBUG-007", MissionStatus.FAILED, "TEST", 100, "x", "y", Instant.now());
        when(missionMemory.findAll(50)).thenReturn(List.of(testMission));

        router.route("¿Qué misiones están en prueba?");

        verify(conversationMemory).setLastMentioned("MISSION", List.of("MISSION-DEBUG-007"));
    }
```

Agregar los imports que falten al inicio del archivo de test:

```java
import com.aicompany.core.model.LastMentioned;
import java.util.Optional;
```

(revisar si `Optional`/`List` ya están importados antes de duplicar el import).

- [ ] **Step 3: Correr los tests nuevos y confirmar que fallan por compilación (constructor/símbolos que no existen todavía)**

Run: `cd app && mvn -Dtest=ChatIntentRouterTest test`
Expected: FAILURE de compilación (`ConversationMemoryService`/`LastMentioned` sin resolver en el constructor del router, o el 7mo argumento no matchea el constructor actual de 6).

- [ ] **Step 4: Modificar `ChatIntentRouter` — constructor + campo nuevo**

Agregar el campo y el parámetro del constructor (junto a los otros `*MemoryService`):

```java
    private final ConversationMemoryService conversationMemory;

    public ChatIntentRouter(
            MissionService missionService,
            CeoService ceoService,
            MissionMemoryService missionMemory,
            OpportunityMemoryService opportunityMemory,
            CustomerMemoryService customerMemory,
            CompanyMemoryService companyMemory,
            ConversationMemoryService conversationMemory) {

        this.missionService = missionService;
        this.ceoService = ceoService;
        this.missionMemory = missionMemory;
        this.opportunityMemory = opportunityMemory;
        this.customerMemory = customerMemory;
        this.companyMemory = companyMemory;
        this.conversationMemory = conversationMemory;
    }
```

- [ ] **Step 5: Envolver `route()` para grabar cada turno, y agregar el chequeo de referencia**

Reemplazar el método `route(String message)` completo por:

```java
    public String route(String message) {
        var response = resolve(message);
        conversationMemory.recordMessage("user", message);
        conversationMemory.recordMessage("ceo", response);
        return response;
    }

    private String resolve(String message) {

        var missionStartMatcher = MISSION_START.matcher(message);

        if (missionStartMatcher.find()) {

            var missionId = missionStartMatcher.group(1).toUpperCase(Locale.ROOT);
            // Una misión iniciada por un comando real de chat del
            // fundador es trabajo real, no una prueba de desarrollo.
            var response = missionService.start(missionId, message, "PRODUCTION");

            return "He recibido " + missionId + ". Estado: " + response.status()
                    + ". La misión está procesándose en segundo plano. Consulta "
                    + "/api/company/missions/" + missionId
                    + "/details para ver el progreso y las tareas.";
        }

        var decision = detectDecision(message);

        if (decision != null) {
            return handleDecision(decision, message);
        }

        var referenceMatcher = REFERENCE_PRONOUN.matcher(normalize(message));

        if (referenceMatcher.find()) {
            return handleReference(message);
        }

        var query = detectQuery(message);

        if (query != null) {
            return handleQuery(query);
        }

        return ceoService.chat(
                companyMemory.agentName("ceo").orElse("CEO"),
                companyMemory.teamRosterDescription(),
                message,
                this::answerMemoryTopic
        );
    }
```

(Nota: el cuerpo de arriba es exactamente el `route()` actual, renombrado a `resolve()`, movido debajo del chequeo de decisión — sin otro cambio salvo la línea de `referenceMatcher` agregada antes de `detectQuery`.)

- [ ] **Step 6: Agregar el patrón `REFERENCE_PRONOUN` y el enum/detector de predicado**

Junto a los otros `Pattern` estáticos (cerca de `TEST_ENVIRONMENT`):

```java
    // Boundary explícito por el mismo motivo que TEST_ENVIRONMENT: evitar
    // falsos positivos dentro de otras palabras.
    private static final Pattern REFERENCE_PRONOUN =
            Pattern.compile("\\b(esas|esos|estas|estos|ellas|ellos)\\b");
```

Junto al enum `QueryIntent` existente, agregar:

```java
    private enum ReferencePredicate {
        ENVIRONMENT_TEST,
        FAILED,
        NEEDS_APPROVAL
    }

    private ReferencePredicate detectReferencePredicate(String normalized) {

        if (TEST_ENVIRONMENT.matcher(normalized).find()) {
            return ReferencePredicate.ENVIRONMENT_TEST;
        }

        if (normalized.contains("fallaron")
                || normalized.contains("fallidas")
                || normalized.contains("fallida")) {
            return ReferencePredicate.FAILED;
        }

        if (normalized.contains("aprobacion") || normalized.contains("necesita")) {
            return ReferencePredicate.NEEDS_APPROVAL;
        }

        return null;
    }
```

- [ ] **Step 7: Agregar `handleReference` + `formatReferenceAnswer` + `matchesReferencePredicate`**

```java
    /**
     * Resuelve un pronombre demostrativo ("esas"/"esos") contra el foco
     * de la conversación -- nunca contra el texto de una respuesta
     * anterior, siempre contra el dato real y actual de esas entidades
     * puntuales en Neo4j. Si el predicado no matchea nada reconocido,
     * cae al chat general con el foco disponible vía el topic
     * {@code LAST_MENTIONED} (grounded, no historial crudo al LLM).
     */
    private String handleReference(String message) {

        var focus = conversationMemory.lastMentioned();

        if (focus.isEmpty()) {
            return "No tengo claro a qué te referís — no mencioné ninguna misión todavía en esta conversación.";
        }

        var predicate = detectReferencePredicate(normalize(message));

        if (predicate == null) {
            return ceoService.chat(
                    companyMemory.agentName("ceo").orElse("CEO"),
                    companyMemory.teamRosterDescription(),
                    message,
                    this::answerMemoryTopic
            );
        }

        var missions = missionMemory.findByIds(focus.get().ids());

        return formatReferenceAnswer(predicate, missions);
    }

    private String formatReferenceAnswer(ReferencePredicate predicate, List<MissionResponse> missions) {

        var matching = missions.stream()
                .filter(m -> matchesReferencePredicate(predicate, m))
                .toList();

        var total = missions.size();

        var description = switch (predicate) {
            case ENVIRONMENT_TEST -> "pertenecen al entorno de pruebas";
            case FAILED -> "fallaron";
            case NEEDS_APPROVAL -> "están esperando tu aprobación (AWAITING_INVESTOR)";
        };

        if (matching.size() == total) {
            return "Correcto. Esas " + total + " misión(es) " + description + ".";
        }

        if (matching.isEmpty()) {
            return "No, ninguna de esas " + total + " misión(es) " + description + ".";
        }

        var matchingIds = matching.stream()
                .map(MissionResponse::missionId)
                .collect(Collectors.joining(", "));

        return matching.size() + " de " + total + " misión(es) " + description + ": " + matchingIds + ".";
    }

    private boolean matchesReferencePredicate(ReferencePredicate predicate, MissionResponse mission) {

        return switch (predicate) {
            case ENVIRONMENT_TEST -> "TEST".equals(mission.environment());
            case FAILED -> mission.status() == MissionStatus.FAILED;
            case NEEDS_APPROVAL -> mission.status() == MissionStatus.AWAITING_INVESTOR;
        };
    }
```

- [ ] **Step 8: Conectar `setLastMentioned` en los 3 formatters de misiones**

En `formatMissionsNeedingAttention`, justo después de calcular `awaitingApproval` y antes del `if (awaitingApproval.isEmpty())`:

```java
        conversationMemory.setLastMentioned(
                "MISSION",
                awaitingApproval.stream().map(MissionResponse::missionId).toList()
        );
```

En `formatFailedMissions`, en el mismo punto respecto a `failed`:

```java
        conversationMemory.setLastMentioned(
                "MISSION",
                failed.stream().map(MissionResponse::missionId).toList()
        );
```

En `formatTestMissions`, en el mismo punto respecto a `test`:

```java
        conversationMemory.setLastMentioned(
                "MISSION",
                test.stream().map(MissionResponse::missionId).toList()
        );
```

(las tres van *antes* del `if (...isEmpty())` de cada método, así el foco se graba incluso con lista vacía — coherente con "esas" pudiendo referirse a "ninguna").

- [ ] **Step 9: Agregar el topic `LAST_MENTIONED` a `answerMemoryTopic` + su formatter**

En el `switch` de `answerMemoryTopic`, agregar:

```java
            case "LAST_MENTIONED" -> formatLastMentioned();
```

Nuevo método, junto a los otros formatters:

```java
    private String formatLastMentioned() {

        var focus = conversationMemory.lastMentioned();

        if (focus.isEmpty()) {
            return "No hay ninguna mención reciente de misiones en esta conversación.";
        }

        var missions = missionMemory.findByIds(focus.get().ids());

        var lines = missions.stream()
                .map(m -> m.missionId() + " (environment=" + m.environment() + ", status=" + m.status() + ")")
                .collect(Collectors.joining(", "));

        return "Las últimas misiones mencionadas fueron: " + lines + ".";
    }
```

- [ ] **Step 10: Correr los tests y confirmar que pasan**

Run: `cd app && mvn -Dtest=ChatIntentRouterTest test`
Expected: BUILD SUCCESS, todos los tests (los 6 nuevos + los ya existentes) en verde.

- [ ] **Step 11: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java
git commit -m "Resolver referencias conversacionales (esas/esos) contra el foco real en Neo4j"
```

---

## Task 4: `LAST_MENTIONED` en el tool schema de `CeoService`

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/CeoService.java`

**Interfaces:**
- Consumes: nada nuevo del lado de tipos — solo agrega un valor al `enum` (lista de strings) ya existente de `query_company_memory`.

- [ ] **Step 1: Agregar `LAST_MENTIONED` al enum y su descripción**

En el bloque `COMPANY_MEMORY_TOOLS`, agregar `"LAST_MENTIONED"` a la lista del `enum` y una frase a la `description` (mismo patrón que las demás):

```java
                                    "enum", List.of(
                                                            "AGENT_STATUS",
                                                            "MISSIONS_NEEDING_ATTENTION",
                                                            "FAILED_MISSIONS",
                                                            "TEST_MISSIONS",
                                                            "LAST_MENTIONED",
                                                            "OPPORTUNITIES",
                                                            "COMPANY_PROFIT"
                                                    ),
```

Y sumar a la cadena de `"description"` (después del bloque de `TEST_MISSIONS`, antes de `"OPPORTUNITIES: "`):

```java
                                                            + "LAST_MENTIONED: "
                                                            + "detalle real de "
                                                            + "las últimas "
                                                            + "misiones "
                                                            + "mencionadas en "
                                                            + "esta "
                                                            + "conversación. "
```

- [ ] **Step 2: Correr toda la suite backend**

Run: `cd app && mvn test`
Expected: BUILD SUCCESS, todos los tests en verde (incluyendo `CeoServiceToolFormatGuardTest`, que no debería verse afectado por este cambio de enum/descripción).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/CeoService.java
git commit -m "Agregar LAST_MENTIONED al tool query_company_memory"
```

---

## Task 5: Verificación en vivo y documentación

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Reconstruir y reiniciar Docker**

Run: `cd /home/alex/Documentos/projectos/empresa && docker compose build && docker compose up -d`
Esperar a `curl -s http://localhost:8081/actuator/health` → `{"status":"UP"}`.

- [ ] **Step 2: Reproducir el ejemplo real completo contra el chat real**

```bash
curl -s -X POST http://localhost:8081/api/company/chat -H "Content-Type: application/json" -d '{"message": "¿Qué misiones están en prueba?"}'
curl -s -X POST http://localhost:8081/api/company/chat -H "Content-Type: application/json" -d '{"message": "pero esas están en prueba"}'
```

Expected: la segunda respuesta empieza con "Correcto." y menciona la cantidad real de misiones (todas, ya que la primera consulta ya filtró por `TEST`).

Confirmar en el log (`docker logs ai-company-core --since 2m`) que la segunda llamada **no** generó ningún `CEO_CHAT`/`OLLAMA_METRICS` (resuelta 100% determinística).

- [ ] **Step 3: Reproducir el caso de fallback (predicado no reconocido)**

```bash
curl -s -X POST http://localhost:8081/api/company/chat -H "Content-Type: application/json" -d '{"message": "contame más sobre esas"}'
```

Expected: respuesta generada por el LLM (sí debería aparecer `CEO_CHAT_TOOL_CALL topic=LAST_MENTIONED` en el log si el modelo pide la herramienta), citando datos reales de las misiones del foco, no inventados.

- [ ] **Step 4: Confirmar persistencia en Neo4j**

```bash
NEO4J_PW=$(grep NEO4J_PASSWORD /home/alex/Documentos/projectos/empresa/.env | cut -d= -f2)
docker exec neo4j cypher-shell -u neo4j -p "$NEO4J_PW" "MATCH (c:Conversation {id:'MAIN'})-[:HAS_MESSAGE]->(m:Message) RETURN count(m) AS totalMessages, c.lastMentionedType AS type, size(c.lastMentionedIds) AS focusSize"
```

Expected: `totalMessages` ≥ 6 (3 turnos × 2 mensajes), `type = "MISSION"`, `focusSize` > 0.

- [ ] **Step 5: Documentar en `CLAUDE.md`**

Agregar una sección nueva `### Memoria conversacional del Company Chat` (después de la sección `### Mission.environment...`), describiendo: el problema real reportado por el usuario, el diseño (foco en Neo4j, resolución determinista + fallback grounded), qué se implementó vs. lo explícitamente diferido (spec `docs/superpowers/specs/2026-09-14-conversational-memory-design.md`), y los resultados de la verificación en vivo del Step 2-4 (con números reales, no inventados).

- [ ] **Step 6: Commit y push final**

```bash
cd /home/alex/Documentos/projectos/empresa
git add CLAUDE.md
git commit -m "Documentar memoria conversacional del Company Chat en CLAUDE.md"
git push origin master
```
