# Consultas nuevas del chat: actividad, decisiones, misiones activas — plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Agregar 3 consultas nuevas al chat (`RECENT_ACTIVITY`, `RECENT_DECISIONS`, `ACTIVE_MISSIONS`), disponibles tanto por el atajo determinista de keywords como por la herramienta `query_company_memory` del CEO, siguiendo exactamente el mismo patrón que los 11 métodos ya existentes de `CompanyTools`.

**Architecture:** 3 métodos nuevos en `CompanyTools` (uno reusa `ActivityMemoryService.recent` ya existente, uno usa un método nuevo `MissionMemoryService.recentDecisions`, uno filtra `missionMemory.findAll(50)` con el mismo criterio de "activa" que ya usa `getCompanyStatus`). 3 topics nuevos sin parámetro `id`.

**Tech Stack:** Java 21, Spring Boot 4.1.1, JUnit 5 + Mockito.

**Spec:** `docs/superpowers/specs/2026-09-18-company-tools-new-queries-design.md`

## Global Constraints

- Los 3 métodos nuevos devuelven `String` ya formateado en español — mismo criterio anti-alucinación de siempre.
- `getActiveMissions` usa exactamente el mismo criterio de "activa" que ya usa `CompanyTools.getCompanyStatus` (status distinto de `AWAITING_INVESTOR`/`FAILED`/`COMPLETED`/`CANCELLED`, `environment=PRODUCTION`) — no se inventa un segundo criterio.
- Ninguno de los 3 topics nuevos requiere `id`.
- `mvn test` debe quedar en verde después de cada tarea.

---

### Task 1: 3 métodos nuevos en `CompanyTools` + `MissionMemoryService.recentDecisions`

**Files:**
- Create: `app/src/main/java/com/aicompany/core/model/DecisionActivity.java`
- Modify: `app/src/main/java/com/aicompany/core/service/MissionMemoryService.java` (agregar `recentDecisions`)
- Modify: `app/src/main/java/com/aicompany/core/service/CompanyTools.java` (nueva dependencia `ActivityMemoryService`, 3 métodos nuevos)
- Modify: `app/src/test/java/com/aicompany/core/service/CompanyToolsTest.java`

**Interfaces:**
- Produces: `DecisionActivity(String decisionId, String missionId, String decision, String reasoning, Instant decidedAt)`; `MissionMemoryService.recentDecisions(int limit) -> List<DecisionActivity>`; `CompanyTools.getRecentActivity()`, `CompanyTools.getRecentDecisions()`, `CompanyTools.getActiveMissions()` — todos usados por la Task 2.

- [ ] **Step 1: Crear `DecisionActivity.java`**

```java
package com.aicompany.core.model;

import java.time.Instant;

public record DecisionActivity(
        String decisionId,
        String missionId,
        String decision,
        String reasoning,
        Instant decidedAt
) {
}
```

- [ ] **Step 2: Agregar `recentDecisions` a `MissionMemoryService`**

En `app/src/main/java/com/aicompany/core/service/MissionMemoryService.java`, agregar el import:

```java
import com.aicompany.core.model.DecisionActivity;
```

Y agregar este método nuevo al final de la clase (antes de la llave de cierre):

```java
    /**
     * Últimas decisiones reales del inversionista humano, sin importar
     * la misión — usado por el chat cuando se pregunta "¿qué decisiones
     * tomé?" en general, no por una misión puntual (para eso ya está
     * {@code MissionStatusResponse}/{@code recordDecision}).
     */
    public List<DecisionActivity> recentDecisions(int limit) {
        try (var session = driver.session()) {
            return session.run(
                            "MATCH (d:Decision) " +
                                    "RETURN d.id AS id, d.missionId AS missionId, d.decision AS decision, " +
                                    "d.reasoning AS reasoning, d.decidedAt AS decidedAt " +
                                    "ORDER BY d.decidedAt DESC LIMIT $limit",
                            Map.of("limit", limit))
                    .list(r -> new DecisionActivity(
                            r.get("id").asString(),
                            r.get("missionId").asString(),
                            r.get("decision").asString(),
                            r.get("reasoning").asString(""),
                            Instant.parse(r.get("decidedAt").asString())
                    ));
        }
    }
```

- [ ] **Step 3: Agregar `ActivityMemoryService` como dependencia de `CompanyTools` y los 3 métodos nuevos**

En `app/src/main/java/com/aicompany/core/service/CompanyTools.java`, agregar el campo y actualizar el constructor:

```java
    private final ActivityMemoryService activityMemory;
```

(agregarlo junto a los otros campos, antes de `appProperties`)

```java
    public CompanyTools(
            MissionService missionService,
            MissionMemoryService missionMemory,
            OpportunityMemoryService opportunityMemory,
            CustomerMemoryService customerMemory,
            ConversationMemoryService conversationMemory,
            ActivityMemoryService activityMemory,
            AppProperties appProperties) {

        this.missionService = missionService;
        this.missionMemory = missionMemory;
        this.opportunityMemory = opportunityMemory;
        this.customerMemory = customerMemory;
        this.conversationMemory = conversationMemory;
        this.activityMemory = activityMemory;
        this.appProperties = appProperties;
    }
```

Agregar los 3 métodos nuevos al final de la clase, antes de `formatCandidate`:

```java
    /**
     * Línea de tiempo real de la empresa (misiones, tareas, evidencia,
     * decisiones) — reusa {@link ActivityMemoryService#recent}, la misma
     * fuente que ya alimenta la pantalla Activity del Command Center.
     */
    public String getRecentActivity() {

        var items = activityMemory.recent(20);

        if (items.isEmpty()) {
            return "Todavía no hay actividad registrada.";
        }

        var lines = items.stream()
                .map(i -> "[" + i.type() + "] " + i.description())
                .collect(Collectors.joining(" | "));

        return "Actividad reciente (" + items.size() + " evento(s)): " + lines;
    }

    /**
     * Decisiones reales del inversionista humano, sin filtrar por
     * misión puntual (para eso está {@link #getMission(String)}).
     */
    public String getRecentDecisions() {

        var decisions = missionMemory.recentDecisions(10);

        if (decisions.isEmpty()) {
            return "Todavía no se registró ninguna decisión real.";
        }

        var lines = decisions.stream()
                .map(d -> d.missionId() + ": " + d.decision() + " — " + d.reasoning())
                .collect(Collectors.joining(" | "));

        return "Últimas " + decisions.size() + " decisión(es) real(es): " + lines;
    }

    /**
     * Misiones activas (ni {@code AWAITING_INVESTOR}, {@code FAILED},
     * {@code COMPLETED} ni {@code CANCELLED}) con su id y status real —
     * mismo criterio de "activa" que ya usa {@link #getCompanyStatus()}
     * para el conteo agregado, acá como listado. Setea el foco
     * conversacional {@code type="MISSION"}, mismo criterio que
     * {@link #getPendingApprovals()}/{@link #getFailedMissions()}.
     */
    public String getActiveMissions() {

        var active = missionMemory.findAll(50).stream()
                .filter(m -> "PRODUCTION".equals(m.environment()))
                .filter(m -> m.status() != MissionStatus.AWAITING_INVESTOR
                        && m.status() != MissionStatus.FAILED
                        && m.status() != MissionStatus.COMPLETED
                        && m.status() != MissionStatus.CANCELLED)
                .toList();

        conversationMemory.setLastMentioned(
                "MISSION",
                active.stream().map(MissionResponse::missionId).toList()
        );

        if (active.isEmpty()) {
            return "No hay ninguna misión activa en este momento.";
        }

        var lines = active.stream()
                .map(m -> m.missionId() + " (" + m.status() + ", " + m.progress() + "%)")
                .collect(Collectors.joining(", "));

        return "Tenés " + active.size() + " misión(es) activa(s): " + lines + ".";
    }
```

- [ ] **Step 4: Arreglar el call-site de `ChatIntentRouterTest` que rompe compilación**

`ChatIntentRouterTest.java` también construye `CompanyTools` directamente (con la firma vieja de 6 argumentos) — el cambio del Step 3 rompe la compilación de ese archivo hasta que se actualice. En `app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java`, agregar el campo nuevo y actualizar la construcción de `companyTools`:

```java
    private final ActivityMemoryService activityMemory = mock(ActivityMemoryService.class);

    private final CompanyTools companyTools = new CompanyTools(
            missionService, missionMemory, opportunityMemory, customerMemory, conversationMemory, activityMemory, appProperties
    );
```

(reemplaza la construcción de 6 argumentos ya existente — no cambiar nada más en ese bloque de campos, ni la construcción de `router` en sí, que sigue recibiendo `companyTools` como antes).

- [ ] **Step 5: Agregar los tests correspondientes a `CompanyToolsTest`**

Actualizar la construcción de `tools` en `app/src/test/java/com/aicompany/core/service/CompanyToolsTest.java`:

```java
    private final ActivityMemoryService activityMemory = mock(ActivityMemoryService.class);

    private final CompanyTools tools = new CompanyTools(
            missionService, missionMemory, opportunityMemory, customerMemory, conversationMemory, activityMemory, appProperties
    );
```

Agregar estos 5 tests nuevos (por ejemplo, al final de la clase, antes del cierre):

```java
    @Test
    void getRecentActivityFormatsRealTimelineItems() {
        when(activityMemory.recent(20)).thenReturn(List.of(
                new com.aicompany.core.model.ActivityItem(
                        "MISSION", "MISSION-1", null, "Trabajo paralelo: WAITING_AGENT_RESULTS", Instant.now()
                )
        ));

        var response = tools.getRecentActivity();

        assertTrue(response.contains("[MISSION]"));
        assertTrue(response.contains("WAITING_AGENT_RESULTS"));
    }

    @Test
    void getRecentActivityReturnsDeterministicEmptyMessage() {
        when(activityMemory.recent(20)).thenReturn(List.of());

        var response = tools.getRecentActivity();

        assertEquals("Todavía no hay actividad registrada.", response);
    }

    @Test
    void getRecentDecisionsFormatsRealDecisions() {
        when(missionMemory.recentDecisions(10)).thenReturn(List.of(
                new com.aicompany.core.model.DecisionActivity(
                        "MISSION-1-DECISION-1", "MISSION-1", "APPROVE", "Se ve bien", Instant.now()
                )
        ));

        var response = tools.getRecentDecisions();

        assertTrue(response.contains("MISSION-1"));
        assertTrue(response.contains("APPROVE"));
        assertTrue(response.contains("Se ve bien"));
    }

    @Test
    void getRecentDecisionsReturnsDeterministicEmptyMessage() {
        when(missionMemory.recentDecisions(10)).thenReturn(List.of());

        var response = tools.getRecentDecisions();

        assertEquals("Todavía no se registró ninguna decisión real.", response);
    }

    @Test
    void getActiveMissionsFiltersByTheSameCriterionAsCompanyStatusAndSetsFocus() {
        var active = new MissionResponse("MISSION-1", MissionStatus.WAITING_AGENT_RESULTS, "PRODUCTION", 30, "x", "y", Instant.now());
        var awaiting = new MissionResponse("MISSION-2", MissionStatus.AWAITING_INVESTOR, "PRODUCTION", 95, "x", "y", Instant.now());
        var testActive = new MissionResponse("MISSION-T", MissionStatus.WAITING_AGENT_RESULTS, "TEST", 30, "x", "y", Instant.now());
        when(missionMemory.findAll(50)).thenReturn(List.of(active, awaiting, testActive));

        var response = tools.getActiveMissions();

        assertTrue(response.contains("1 misión"));
        assertTrue(response.contains("MISSION-1"));
        assertFalse(response.contains("MISSION-2"));
        assertFalse(response.contains("MISSION-T"));
        verify(conversationMemory).setLastMentioned("MISSION", List.of("MISSION-1"));
    }
```

- [ ] **Step 6: Correr toda la suite**

Run: `cd app && mvn test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
cd app && git add src/main/java/com/aicompany/core/model/DecisionActivity.java \
  src/main/java/com/aicompany/core/service/MissionMemoryService.java \
  src/main/java/com/aicompany/core/service/CompanyTools.java \
  src/test/java/com/aicompany/core/service/CompanyToolsTest.java \
  src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java
git commit -m "Agregar getRecentActivity, getRecentDecisions y getActiveMissions a CompanyTools"
```

---

### Task 2: Wirear los 3 topics nuevos en `ChatIntentRouter`

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java`
- Modify: `app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java`

**Interfaces:**
- Consumes: `CompanyTools.getRecentActivity()`, `getRecentDecisions()`, `getActiveMissions()` (Task 1).

- [ ] **Step 1: Agregar los 3 valores al enum `QueryIntent`**

En `app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java`, reemplazar:

```java
    private enum QueryIntent {
        AGENT_STATUS,
        MISSIONS_NEEDING_ATTENTION,
        FAILED_MISSIONS,
        TEST_MISSIONS,
        OPPORTUNITIES,
        LEADS,
        COMPANY_PROFIT,
        COMPANY_STATUS
    }
```

por:

```java
    private enum QueryIntent {
        AGENT_STATUS,
        MISSIONS_NEEDING_ATTENTION,
        FAILED_MISSIONS,
        TEST_MISSIONS,
        OPPORTUNITIES,
        LEADS,
        COMPANY_PROFIT,
        COMPANY_STATUS,
        RECENT_ACTIVITY,
        RECENT_DECISIONS,
        ACTIVE_MISSIONS
    }
```

- [ ] **Step 2: Agregar las 3 keywords a `detectQuery`**

Ubicar este bloque:

```java
        if (TEST_ENVIRONMENT.matcher(normalized).find() || normalized.contains("entorno de test")) {
            // "¿qué misiones están en prueba?" -- Mission.environment=TEST,
            // cualquier status. Reportado por el usuario: sin esto, ~25
            // misiones de desarrollo (MISSION-STRUCTURED-*, MVP-*, etc.)
            // contaminaban toda pregunta de negocio real.
            return QueryIntent.TEST_MISSIONS;
        }

        if (normalized.contains("aprobacion")
```

y reemplazarlo por:

```java
        if (TEST_ENVIRONMENT.matcher(normalized).find() || normalized.contains("entorno de test")) {
            // "¿qué misiones están en prueba?" -- Mission.environment=TEST,
            // cualquier status. Reportado por el usuario: sin esto, ~25
            // misiones de desarrollo (MISSION-STRUCTURED-*, MVP-*, etc.)
            // contaminaban toda pregunta de negocio real.
            return QueryIntent.TEST_MISSIONS;
        }

        if (normalized.contains("actividad")) {
            return QueryIntent.RECENT_ACTIVITY;
        }

        if (normalized.contains("decision")) {
            return QueryIntent.RECENT_DECISIONS;
        }

        if (normalized.contains("mision") && normalized.contains("activa")) {
            // Exige las dos palabras juntas -- "activa" sola aparece en
            // frases sin relación ninguna a misiones.
            return QueryIntent.ACTIVE_MISSIONS;
        }

        if (normalized.contains("aprobacion")
```

- [ ] **Step 3: Agregar los 3 `case` nuevos a `answerMemoryTopic`**

Ubicar:

```java
            case "COMPANY_STATUS" -> companyTools.getCompanyStatus();
            case "MISSION_DETAILS" -> (id == null || id.isBlank())
```

y reemplazarlo por:

```java
            case "COMPANY_STATUS" -> companyTools.getCompanyStatus();
            case "RECENT_ACTIVITY" -> companyTools.getRecentActivity();
            case "RECENT_DECISIONS" -> companyTools.getRecentDecisions();
            case "ACTIVE_MISSIONS" -> companyTools.getActiveMissions();
            case "MISSION_DETAILS" -> (id == null || id.isBlank())
```

- [ ] **Step 4: Agregar 3 tests de ruteo por keyword a `ChatIntentRouterTest`**

```java
    @Test
    void routesRecentActivityQueryWithDeterministicFormatting() {
        when(activityMemory.recent(20)).thenReturn(List.of(
                new com.aicompany.core.model.ActivityItem(
                        "MISSION", "MISSION-1", null, "Trabajo paralelo: WAITING_AGENT_RESULTS", Instant.now()
                )
        ));

        var response = router.route("¿Qué actividad reciente hay?");

        assertTrue(response.contains("[MISSION]"));
        verifyNoInteractions(ceoService);
    }

    @Test
    void routesRecentDecisionsQueryWithDeterministicFormatting() {
        when(missionMemory.recentDecisions(10)).thenReturn(List.of(
                new com.aicompany.core.model.DecisionActivity(
                        "MISSION-1-DECISION-1", "MISSION-1", "APPROVE", "Se ve bien", Instant.now()
                )
        ));

        var response = router.route("¿Qué decisiones tomé hasta ahora?");

        assertTrue(response.contains("APPROVE"));
        verifyNoInteractions(ceoService);
    }

    @Test
    void routesActiveMissionsQueryWithDeterministicFormatting() {
        var active = new MissionResponse("MISSION-1", MissionStatus.WAITING_AGENT_RESULTS, "PRODUCTION", 30, "x", "y", Instant.now());
        when(missionMemory.findAll(50)).thenReturn(List.of(active));

        var response = router.route("¿Qué misiones están activas?");

        assertTrue(response.contains("MISSION-1"));
        verifyNoInteractions(ceoService);
    }
```

Nota: `CompanyTools` en el test se construye desde la Task 1 de la ronda anterior con `activityMemory` incluido — estos tests solo necesitan estubear `activityMemory`/`missionMemory` directamente, mismo patrón que todos los tests existentes (`companyTools` real por encima de los mismos mocks).

- [ ] **Step 5: Correr toda la suite**

Run: `cd app && mvn test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
cd app && git add src/main/java/com/aicompany/core/service/ChatIntentRouter.java \
  src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java
git commit -m "Chat: agregar RECENT_ACTIVITY, RECENT_DECISIONS y ACTIVE_MISSIONS al atajo de keywords"
```

---

### Task 3: Exponer los 3 topics nuevos en la herramienta `query_company_memory` del CEO

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/CeoService.java`
- Modify: `app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java`

**Interfaces:**
- Ninguna interfaz nueva — solo extiende el schema ya existente y el test de cobertura de topics.

- [ ] **Step 1: Agregar los 3 topics al enum del schema**

En `app/src/main/java/com/aicompany/core/service/CeoService.java`, reemplazar:

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
                                                            "OPPORTUNITY_DETAILS"
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
                                                            "ACTIVE_MISSIONS"
                                                    ),
```

- [ ] **Step 2: Agregar la descripción de los 3 topics nuevos**

Ubicar el final del bloque `"description",` (justo antes de `),` que cierra el `Map.of` de `"topic"`, es decir, inmediatamente después de `+ "Opportunity)."` y antes de `),`):

```java
                                                            + "interna de la "
                                                            + "Opportunity)."
                                            ),
```

reemplazarlo por:

```java
                                                            + "interna de la "
                                                            + "Opportunity). "
                                                            + "RECENT_ACTIVITY: "
                                                            + "línea de tiempo "
                                                            + "reciente de la "
                                                            + "empresa (misiones, "
                                                            + "tareas, evidencia, "
                                                            + "decisiones). "
                                                            + "RECENT_DECISIONS: "
                                                            + "últimas decisiones "
                                                            + "reales del "
                                                            + "inversionista "
                                                            + "humano, sin "
                                                            + "filtrar por "
                                                            + "misión puntual. "
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

- [ ] **Step 3: Actualizar `passesCompanyMemoryQueryCallbackThatResolvesAllKnownTopics`**

En `app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java`, en ese mismo test, agregar los stubs y las 3 aserciones nuevas. Ubicar:

```java
        when(opportunityMemory.listCandidatesForMission("MISSION-1")).thenReturn(List.of());

        router.route("Hola, ¿cómo estás?");
```

y reemplazarlo por:

```java
        when(opportunityMemory.listCandidatesForMission("MISSION-1")).thenReturn(List.of());
        when(activityMemory.recent(20)).thenReturn(List.of());
        when(missionMemory.recentDecisions(10)).thenReturn(List.of());

        router.route("Hola, ¿cómo estás?");
```

Y ubicar la última aserción del test:

```java
        assertTrue(companyMemoryQuery.apply("ALGO_INEXISTENTE", null).contains("Dato no reconocido"));
```

y agregar, justo antes de esa línea:

```java
        assertTrue(companyMemoryQuery.apply("RECENT_ACTIVITY", null).contains("Todavía no hay actividad"));
        assertTrue(companyMemoryQuery.apply("RECENT_DECISIONS", null).contains("Todavía no se registró"));
        assertTrue(companyMemoryQuery.apply("ACTIVE_MISSIONS", null).contains("No hay ninguna misión activa"));
```

- [ ] **Step 4: Correr toda la suite**

Run: `cd app && mvn test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd app && git add src/main/java/com/aicompany/core/service/CeoService.java \
  src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java
git commit -m "query_company_memory expone RECENT_ACTIVITY, RECENT_DECISIONS y ACTIVE_MISSIONS al chat general"
```

---

## Verificación en vivo (después de las 3 tareas, fuera del ciclo TDD)

1. Rebuild y redeploy del contenedor `company-core` con esta rama.
2. Correr una misión real, aprobarla o rechazarla (para tener al menos una `Decision` real).
3. Chat: "¿qué actividad reciente hay?", "¿qué decisiones tomé?", "¿qué misiones están activas?" — confirmar datos reales.
4. Confirmar que `getActiveMissions` coincide exactamente con el número `activa(s)` que ya muestra `COMPANY_STATUS` para los mismos datos.
