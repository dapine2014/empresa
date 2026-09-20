# Company Chat: grounding anti-alucinación + ProductStatus — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** El Company Chat nunca vuelve a afirmar que el desarrollo/producto de una misión avanzó sin evidencia real — introduce `ProductStatus` (separado de `MissionStatus`) y una rama 100% determinista en `ChatIntentRouter` para preguntas sobre una misión puntual.

**Architecture:** Nuevo enum `ProductStatus` + servicio determinista `ProductStatusService` (sin persistencia, calcula sobre señales reales existentes en Neo4j vía `MissionMemoryService`/`CustomerMemoryService`). `ChatIntentRouter` gana una rama nueva que resuelve un `MISSION-<id>` explícito 100% en Java (nunca pasa por Ollama) y extiende su mecanismo de referencia pronominal (`handleReference`) para el mismo grounding sin id explícito. `CeoService` recibe un refuerzo de prompt best-effort para el resto del chat general.

**Tech Stack:** Java 21, Spring Boot 4.1.1, JUnit 5 + Mockito (sin Neo4j/Kafka/Ollama reales en la suite unitaria — mismo criterio ya establecido en el proyecto).

**Spec:** `docs/superpowers/specs/2026-09-20-chat-grounding-product-status-design.md`

## Global Constraints

- `MissionStatus` (workflow de análisis/decisión) nunca se usa para inferir `ProductStatus` (estado real del producto) — son preguntas distintas, en ningún punto del código uno deriva del otro.
- `DELIVERY_FEASIBILITY` (AgentTask de `engineering`, discovery) **nunca** implica `ProductStatus.DESIGN` ni `DEVELOPMENT`. `QUALITY_RISK_REVIEW` (AgentTask de `qa`, discovery) **nunca** implica `ProductStatus.QA` real.
- Cuando falta un dato, la respuesta debe contener exactamente la frase **"No tengo ese dato registrado."** (literal, tal cual) — puede ir seguida de contexto adicional, pero esa oración exacta debe estar presente.
- `PUBLISHED`, `QA`, `DEVELOPMENT` de `ProductStatus` quedan modelados pero inalcanzables en esta ronda (siempre `false` en `ProductStatusService`) — son el punto de enganche del Proyecto B (ejecución real de código/infra), fuera de alcance de este plan.
- Sin cambios de API REST ni de frontend en esta ronda — `productStatus` es de uso interno del chat únicamente.
- Toda rama nueva que resuelva un `MISSION-<id>` explícito o una referencia de foco debe responder **sin llamar a Ollama** (100% determinista en Java) — mismo criterio que el resto de `ChatIntentRouter` (`formatAgentStatus`, `formatCompanyStatus`, etc.).
- Los tests de esta feature no dependen de Docker/Neo4j/Ollama reales — mocks/fakes, mismo criterio que la suite unitaria existente.

---

## File Structure

- **Create** `app/src/main/java/com/aicompany/core/model/ProductStatus.java` — el enum nuevo.
- **Create** `app/src/main/java/com/aicompany/core/service/ProductStatusService.java` — motor de reglas determinista.
- **Create** `app/src/test/java/com/aicompany/core/service/ProductStatusServiceTest.java`.
- **Modify** `app/src/main/java/com/aicompany/core/service/CustomerMemoryService.java` — nuevo método `transactionCount`.
- **Modify** `app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java` — nueva rama de estado de misión puntual, nuevo predicado de referencia `PRODUCT_STATUS`, extensión de `formatLastMentioned`.
- **Modify** `app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java` — nuevo mock/param de constructor, tests nuevos.
- **Modify** `app/src/main/java/com/aicompany/core/service/CeoService.java` — refuerzo del system prompt de `chat()`.
- **Modify** `/home/alex/Documentos/projectos/empresa/CLAUDE.md` — documentar `ProductStatus`/`ProductStatusService` y la nueva rama del router (regla del propio archivo: "cuando avances más allá del núcleo actual, actualiza este archivo").

---

### Task 1: `ProductStatus` + `ProductStatusService`

**Files:**
- Create: `app/src/main/java/com/aicompany/core/model/ProductStatus.java`
- Modify: `app/src/main/java/com/aicompany/core/service/CustomerMemoryService.java` (agregar método al final de la clase, antes de la llave de cierre)
- Create: `app/src/main/java/com/aicompany/core/service/ProductStatusService.java`
- Test: `app/src/test/java/com/aicompany/core/service/ProductStatusServiceTest.java`

**Interfaces:**
- Consumes: `MissionMemoryService.tasks(String missionId) -> List<AgentTask>` (ya existe); `CustomerMemoryService.totalRevenueAndCost(String missionId) -> double[]{revenue, cost}` (ya existe); `AppProperties.seedCapitalUsd() -> double` (ya existe, record `AppProperties(String name, double seedCapitalUsd, int challengeDays)`); `AgentTask.agentId()/action()/status()` (record ya existente: `AgentTask(String taskId, String missionId, String agentId, String action, String status, String result, Instant updatedAt)`).
- Produces: `ProductStatus` enum con constantes `DISCOVERY, DESIGN, DEVELOPMENT, QA, PUBLISHED, MONETIZING, BUSINESS_SUCCESS`; `CustomerMemoryService.transactionCount(String missionId) -> long`; `ProductStatusService.resolve(String missionId) -> ProductStatus` — consumido por Task 2 y Task 3 (`ChatIntentRouter`).

- [ ] **Step 1: Crear el enum `ProductStatus`**

```java
package com.aicompany.core.model;

public enum ProductStatus {
    DISCOVERY,
    DESIGN,
    DEVELOPMENT,
    QA,
    PUBLISHED,
    MONETIZING,
    BUSINESS_SUCCESS
}
```

- [ ] **Step 2: Agregar `transactionCount` a `CustomerMemoryService`**

Agregar este método público al final de la clase (antes de la llave de cierre), junto a los demás métodos de agregación (`totalRevenueAndCost`, `companyWideTotalRevenueAndCost`):

```java
    /**
     * Cantidad real de {@code Transaction} registradas para una misión —
     * usado por {@link ProductStatusService} para derivar
     * {@code ProductStatus.MONETIZING}: una transacción real con
     * {@code revenueUsd=0, costUsd=0} (caso raro pero posible) igual debe
     * contar como evidencia de venta cerrada, a diferencia de mirar si
     * {@link #totalRevenueAndCost} es distinto de cero.
     */
    public long transactionCount(String missionId) {
        try (var session = driver.session()) {
            return session.run(
                    "MATCH (m:Mission {id:$missionId})-[:HAS_TRANSACTION]->(t:Transaction) "
                            + "RETURN count(t) AS total",
                    Map.of("missionId", missionId)
            ).single().get("total").asLong();
        }
    }
```

(No lleva test directo — `CustomerMemoryService` es un `*MemoryService` de integración Neo4j, mismo criterio ya establecido en el proyecto de "sin test directo" para esa familia de clases; se ejercita indirectamente vía `ProductStatusServiceTest` con un mock.)

- [ ] **Step 3: Escribir `ProductStatusServiceTest` (falla porque `ProductStatusService` no existe todavía)**

```java
package com.aicompany.core.service;

import com.aicompany.core.config.AppProperties;
import com.aicompany.core.model.AgentTask;
import com.aicompany.core.model.ProductStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductStatusServiceTest {

    private final MissionMemoryService missionMemory = mock(MissionMemoryService.class);
    private final CustomerMemoryService customerMemory = mock(CustomerMemoryService.class);
    private final AppProperties appProperties = new AppProperties("Forjai", 50.0, 60);

    private final ProductStatusService service =
            new ProductStatusService(missionMemory, customerMemory, appProperties);

    private static AgentTask task(String agentId, String action, String status) {
        return new AgentTask("TASK-1", "MISSION-1", agentId, action, status, "{}", Instant.now());
    }

    @Test
    void defaultsToDiscoveryWithNoRealSignal() {
        when(missionMemory.tasks("MISSION-1")).thenReturn(List.of(
                task("sales", "MARKET_DISCOVERY", "COMPLETED")
        ));
        when(customerMemory.totalRevenueAndCost("MISSION-1")).thenReturn(new double[]{0.0, 0.0});
        when(customerMemory.transactionCount("MISSION-1")).thenReturn(0L);

        assertEquals(ProductStatus.DISCOVERY, service.resolve("MISSION-1"));
    }

    @Test
    void movesToDesignOnceOfferDesignIsCompleted() {
        when(missionMemory.tasks("MISSION-1")).thenReturn(List.of(
                task("product", "OFFER_DESIGN", "COMPLETED"),
                task("engineering", "DELIVERY_FEASIBILITY", "COMPLETED")
        ));
        when(customerMemory.totalRevenueAndCost("MISSION-1")).thenReturn(new double[]{0.0, 0.0});
        when(customerMemory.transactionCount("MISSION-1")).thenReturn(0L);

        assertEquals(ProductStatus.DESIGN, service.resolve("MISSION-1"));
    }

    @Test
    void deliveryFeasibilityAloneNeverMeansDesignOrDevelopment() {
        // Regla dura del pedido original: DELIVERY_FEASIBILITY (estudio de
        // factibilidad de engineering durante el discovery) no implica ni
        // DESIGN ni DEVELOPMENT.
        when(missionMemory.tasks("MISSION-1")).thenReturn(List.of(
                task("engineering", "DELIVERY_FEASIBILITY", "COMPLETED")
        ));
        when(customerMemory.totalRevenueAndCost("MISSION-1")).thenReturn(new double[]{0.0, 0.0});
        when(customerMemory.transactionCount("MISSION-1")).thenReturn(0L);

        assertEquals(ProductStatus.DISCOVERY, service.resolve("MISSION-1"));
    }

    @Test
    void qualityRiskReviewNeverCountsAsRealQa() {
        // Regla dura del pedido original: QUALITY_RISK_REVIEW (discovery de
        // qa) nunca implica ProductStatus.QA real.
        when(missionMemory.tasks("MISSION-1")).thenReturn(List.of(
                task("qa", "QUALITY_RISK_REVIEW", "COMPLETED")
        ));
        when(customerMemory.totalRevenueAndCost("MISSION-1")).thenReturn(new double[]{0.0, 0.0});
        when(customerMemory.transactionCount("MISSION-1")).thenReturn(0L);

        assertEquals(ProductStatus.DISCOVERY, service.resolve("MISSION-1"));
    }

    @Test
    void movesToMonetizingWithARealTransactionEvenWithZeroNetProfit() {
        when(missionMemory.tasks("MISSION-1")).thenReturn(List.of());
        when(customerMemory.totalRevenueAndCost("MISSION-1")).thenReturn(new double[]{0.0, 0.0});
        when(customerMemory.transactionCount("MISSION-1")).thenReturn(1L);

        assertEquals(ProductStatus.MONETIZING, service.resolve("MISSION-1"));
    }

    @Test
    void movesToBusinessSuccessWhenNetProfitExceedsSeedCapital() {
        when(missionMemory.tasks("MISSION-1")).thenReturn(List.of());
        when(customerMemory.totalRevenueAndCost("MISSION-1")).thenReturn(new double[]{200.0, 50.0});
        when(customerMemory.transactionCount("MISSION-1")).thenReturn(1L);

        assertEquals(ProductStatus.BUSINESS_SUCCESS, service.resolve("MISSION-1"));
    }
}
```

- [ ] **Step 4: Ejecutar los tests para confirmar que fallan (por compilación: `ProductStatusService` no existe)**

Run: `cd app && mvn test -Dtest=ProductStatusServiceTest`
Expected: FAIL (error de compilación — `cannot find symbol: class ProductStatusService`)

- [ ] **Step 5: Implementar `ProductStatusService`**

```java
package com.aicompany.core.service;

import com.aicompany.core.config.AppProperties;
import com.aicompany.core.model.ProductStatus;
import org.springframework.stereotype.Service;

/**
 * Deriva el estado real del PRODUCTO (distinto de {@code MissionStatus},
 * que es el workflow de análisis/decisión interno) a partir de señales
 * reales en Company Memory. Nunca persiste nada nuevo: se recalcula en
 * cada consulta, siempre fresco. Ver
 * docs/superpowers/specs/2026-09-20-chat-grounding-product-status-design.md.
 *
 * <p>Reglas evaluadas de la señal más fuerte a la más débil — la primera
 * que matchea gana:
 *
 * <ol>
 *   <li>{@code BUSINESS_SUCCESS}: netProfit &gt; seedCapitalUsd.</li>
 *   <li>{@code MONETIZING}: existe al menos una {@code Transaction} real
 *       para la misión.</li>
 *   <li>{@code PUBLISHED}/{@code QA}/{@code DEVELOPMENT}: hoy no existe
 *       ninguna señal real para estos tres — punto de enganche del
 *       Proyecto B (ejecución real de código/infra tras aprobación).
 *       Siempre {@code false} hasta que ese proyecto exista.
 *       <b>Importante</b>: una {@code AgentTask} {@code QUALITY_RISK_REVIEW}
 *       (discovery de {@code qa}) nunca cuenta como evidencia de
 *       {@code QA} real — son conceptos distintos.</li>
 *   <li>{@code DESIGN}: la {@code AgentTask} {@code OFFER_DESIGN} de esta
 *       misión está {@code COMPLETED}.</li>
 *   <li>{@code DISCOVERY}: default.</li>
 * </ol>
 */
@Service
public class ProductStatusService {

    private final MissionMemoryService missionMemory;
    private final CustomerMemoryService customerMemory;
    private final AppProperties appProperties;

    public ProductStatusService(
            MissionMemoryService missionMemory,
            CustomerMemoryService customerMemory,
            AppProperties appProperties) {

        this.missionMemory = missionMemory;
        this.customerMemory = customerMemory;
        this.appProperties = appProperties;
    }

    public ProductStatus resolve(String missionId) {

        if (isBusinessSuccess(missionId)) {
            return ProductStatus.BUSINESS_SUCCESS;
        }

        if (isMonetizing(missionId)) {
            return ProductStatus.MONETIZING;
        }

        if (isPublished(missionId)) {
            return ProductStatus.PUBLISHED;
        }

        if (isQaValidated(missionId)) {
            return ProductStatus.QA;
        }

        if (isInDevelopment(missionId)) {
            return ProductStatus.DEVELOPMENT;
        }

        if (isDesigned(missionId)) {
            return ProductStatus.DESIGN;
        }

        return ProductStatus.DISCOVERY;
    }

    private boolean isBusinessSuccess(String missionId) {

        var totals = customerMemory.totalRevenueAndCost(missionId);
        var netProfit = totals[0] - totals[1];

        return netProfit > appProperties.seedCapitalUsd();
    }

    private boolean isMonetizing(String missionId) {
        return customerMemory.transactionCount(missionId) > 0;
    }

    /**
     * Punto de enganche del Proyecto B — hoy no existe ningún
     * artefacto/repositorio/build real registrado en Company Memory.
     */
    private boolean isPublished(String missionId) {
        return false;
    }

    /**
     * Punto de enganche del Proyecto B — QA real sobre un producto que ya
     * existe, distinto de {@code QUALITY_RISK_REVIEW}.
     */
    private boolean isQaValidated(String missionId) {
        return false;
    }

    /**
     * Punto de enganche del Proyecto B — {@code AgentTask} de desarrollo
     * real, evento {@code EMPRESA_DEVELOPMENT_STARTED}, o
     * artefacto/repositorio/build.
     */
    private boolean isInDevelopment(String missionId) {
        return false;
    }

    private boolean isDesigned(String missionId) {

        return missionMemory.tasks(missionId).stream()
                .anyMatch(t -> "OFFER_DESIGN".equals(t.action())
                        && "COMPLETED".equals(t.status()));
    }
}
```

- [ ] **Step 6: Ejecutar los tests y confirmar que pasan**

Run: `cd app && mvn test -Dtest=ProductStatusServiceTest`
Expected: PASS (6 tests)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/aicompany/core/model/ProductStatus.java \
        app/src/main/java/com/aicompany/core/service/ProductStatusService.java \
        app/src/main/java/com/aicompany/core/service/CustomerMemoryService.java \
        app/src/test/java/com/aicompany/core/service/ProductStatusServiceTest.java
git commit -m "Agregar ProductStatus y ProductStatusService (estado real de producto, separado del workflow)"
```

---

### Task 2: `ChatIntentRouter` — rama determinista para `MISSION-<id>` explícito

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java`
- Test: `app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java`

**Interfaces:**
- Consumes: `ProductStatusService.resolve(String) -> ProductStatus` (Task 1); `MissionMemoryService.find(String) -> Optional<MissionResponse>` (ya existe); `MissionMemoryService.tasks(String) -> List<AgentTask>` (ya existe); `MissionMemoryService.latestTaskPerAgent() -> List<AgentStatusResponse>` (ya existe).
- Produces: `ChatIntentRouter` gana un noveno parámetro de constructor `ProductStatusService productStatusService` (al final, después de `appProperties`) — todo código que instancie `ChatIntentRouter` manualmente (hoy solo `ChatIntentRouterTest`) debe actualizarse.

- [ ] **Step 1: Escribir los tests nuevos en `ChatIntentRouterTest` (fallan por compilación: constructor con 9 params, `ProductStatus` sin importar)**

Primero, actualizar el bloque de mocks y el constructor del router (reemplazar el bloque existente):

```java
    private final MissionService missionService = mock(MissionService.class);
    private final CeoService ceoService = mock(CeoService.class);
    private final MissionMemoryService missionMemory = mock(MissionMemoryService.class);
    private final OpportunityMemoryService opportunityMemory = mock(OpportunityMemoryService.class);
    private final CustomerMemoryService customerMemory = mock(CustomerMemoryService.class);
    private final CompanyMemoryService companyMemory = mock(CompanyMemoryService.class);
    private final ConversationMemoryService conversationMemory = mock(ConversationMemoryService.class);
    private final com.aicompany.core.config.AppProperties appProperties =
            new com.aicompany.core.config.AppProperties("Forjai", 50.0, 60);
    private final ProductStatusService productStatusService = mock(ProductStatusService.class);

    private final ChatIntentRouter router = new ChatIntentRouter(
            missionService, ceoService, missionMemory, opportunityMemory, customerMemory, companyMemory,
            conversationMemory, appProperties, productStatusService
    );
```

Agregar el import correspondiente junto a los demás `import com.aicompany.core.model.*;` del archivo, y `java.util.Locale` junto a los demás imports de `java.util.*` (usado por el test de protección anti-alucinación de este mismo step, para normalizar la respuesta a minúsculas antes de buscar las substrings prohibidas):

```java
import com.aicompany.core.model.AgentTask;
import com.aicompany.core.model.ProductStatus;
```

```java
import java.util.Locale;
```

Agregar estos tres tests nuevos (después de `returnsNotFoundMessageWhenDecisionMissionDoesNotExist`, antes del bloque de `AGENT_STATUS`):

```java
    @Test
    void routesExplicitMissionIdStatusQueryDeterministicallyWithoutOllama() {

        var mission = new MissionResponse(
                "MISSION-1789884929871", MissionStatus.COMPLETED, "PRODUCTION", 100,
                "Decisión del inversionista", "Aprobada", Instant.now()
        );
        when(missionMemory.find("MISSION-1789884929871")).thenReturn(Optional.of(mission));
        when(missionMemory.tasks("MISSION-1789884929871")).thenReturn(List.of(
                new AgentTask("T1", "MISSION-1789884929871", "sales", "MARKET_DISCOVERY", "COMPLETED", "{}", Instant.now()),
                new AgentTask("T2", "MISSION-1789884929871", "product", "OFFER_DESIGN", "COMPLETED", "{}", Instant.now()),
                new AgentTask("T4", "MISSION-1789884929871", "engineering", "DELIVERY_FEASIBILITY", "COMPLETED", "{}", Instant.now())
        ));
        when(missionMemory.latestTaskPerAgent()).thenReturn(List.of(
                new AgentStatusResponse("engineering", "Neo", "Engineering", "x", "IDLE",
                        "MISSION-1789884929871", "DELIVERY_FEASIBILITY", "COMPLETED", Instant.now())
        ));
        when(productStatusService.resolve("MISSION-1789884929871")).thenReturn(ProductStatus.DESIGN);

        var response = router.route("¿Cómo va MISSION-1789884929871?");

        assertTrue(response.contains("workflowStatus=COMPLETED"));
        assertTrue(response.contains("productStatus=DESIGN"));
        assertTrue(response.contains("no puedo afirmar que el desarrollo haya comenzado"));
        verifyNoInteractions(ceoService);
    }

    @Test
    void returnsNotRegisteredMessageWhenQueriedMissionDoesNotExist() {

        when(missionMemory.find("MISSION-999")).thenReturn(Optional.empty());

        var response = router.route("¿Cómo va MISSION-999?");

        assertEquals(
                "No tengo ese dato registrado. No existe ninguna misión con id MISSION-999 en Company Memory.",
                response
        );
        verifyNoInteractions(ceoService);
    }

    @Test
    void hallucinationProtectionCompletedMissionWithIdleAgentsNeverImpliesDevelopmentInProgress() {
        // Test de protección directo contra el bug real reportado por el
        // usuario: MISSION-1789884929871 estaba COMPLETED, todos los
        // agentes IDLE, y la última tarea de engineering era
        // DELIVERY_FEASIBILITY=COMPLETED -- el CEO afirmó "El desarrollo
        // del MVP está en curso" sin ninguna evidencia real.
        var mission = new MissionResponse(
                "MISSION-1789884929871", MissionStatus.COMPLETED, "PRODUCTION", 100,
                "Decisión del inversionista", "Aprobada", Instant.now()
        );
        when(missionMemory.find("MISSION-1789884929871")).thenReturn(Optional.of(mission));
        when(missionMemory.tasks("MISSION-1789884929871")).thenReturn(List.of(
                new AgentTask("T4", "MISSION-1789884929871", "engineering", "DELIVERY_FEASIBILITY", "COMPLETED", "{}", Instant.now())
        ));
        when(missionMemory.latestTaskPerAgent()).thenReturn(List.of(
                new AgentStatusResponse("engineering", "Neo", "Engineering", "x", "IDLE",
                        "MISSION-1789884929871", "DELIVERY_FEASIBILITY", "COMPLETED", Instant.now())
        ));
        when(productStatusService.resolve("MISSION-1789884929871")).thenReturn(ProductStatus.DISCOVERY);

        var response = router.route("¿Cómo va el desarrollo de MISSION-1789884929871?");

        var lower = response.toLowerCase(Locale.ROOT);
        assertFalse(lower.contains("en curso"));
        assertFalse(lower.contains("desarrollando"));
        assertTrue(response.contains("productStatus=DISCOVERY"));
        assertTrue(response.contains("no puedo afirmar que el desarrollo haya comenzado"));
        verifyNoInteractions(ceoService);
    }
```

- [ ] **Step 2: Ejecutar los tests para confirmar que fallan (compilación)**

Run: `cd app && mvn test -Dtest=ChatIntentRouterTest`
Expected: FAIL (error de compilación — constructor de `ChatIntentRouter` no tiene 9 parámetros todavía)

- [ ] **Step 3: Agregar el campo y parámetro de constructor en `ChatIntentRouter`**

Agregar el import (junto a los demás `import com.aicompany.core.model.*;`):

```java
import com.aicompany.core.model.AgentTask;
import com.aicompany.core.model.ProductStatus;
```

Reemplazar el bloque de campos y constructor existente:

```java
    private final MissionService missionService;
    private final CeoService ceoService;
    private final MissionMemoryService missionMemory;
    private final OpportunityMemoryService opportunityMemory;
    private final CustomerMemoryService customerMemory;
    private final CompanyMemoryService companyMemory;
    private final ConversationMemoryService conversationMemory;
    private final AppProperties appProperties;
    private final ProductStatusService productStatusService;

    public ChatIntentRouter(
            MissionService missionService,
            CeoService ceoService,
            MissionMemoryService missionMemory,
            OpportunityMemoryService opportunityMemory,
            CustomerMemoryService customerMemory,
            CompanyMemoryService companyMemory,
            ConversationMemoryService conversationMemory,
            AppProperties appProperties,
            ProductStatusService productStatusService) {

        this.missionService = missionService;
        this.ceoService = ceoService;
        this.missionMemory = missionMemory;
        this.opportunityMemory = opportunityMemory;
        this.customerMemory = customerMemory;
        this.companyMemory = companyMemory;
        this.conversationMemory = conversationMemory;
        this.appProperties = appProperties;
        this.productStatusService = productStatusService;
    }
```

- [ ] **Step 4: Agregar la rama nueva en `resolve()` y los métodos que la implementan**

En `resolve()`, insertar justo después del bloque `if (decision != null) { return handleDecision(decision, message); }` y antes de `var referenceMatcher = REFERENCE_PRONOUN.matcher(message);`:

```java
        var missionStatusId = detectMissionStatusQuery(message);

        if (missionStatusId != null) {
            return handleMissionStatusQuery(missionStatusId);
        }
```

Agregar estos métodos nuevos (por ejemplo, después de `handleDecision`):

```java
    /**
     * Un {@code MISSION-<id>} explícito que no fue ni inicio ni decisión —
     * el fundador está preguntando por el estado real de esa misión
     * puntual. Reportado en vivo: sin esta rama, este caso caía al chat
     * general y el CEO alucinó "el desarrollo del MVP está en curso"
     * sobre una misión COMPLETED con agentes IDLE.
     */
    private String detectMissionStatusQuery(String message) {

        var matcher = MISSION_ID.matcher(message);

        return matcher.find() ? matcher.group(1).toUpperCase(Locale.ROOT) : null;
    }

    /**
     * Resuelve 100% en Java, sin pasar por Ollama — mismo criterio que
     * {@code formatAgentStatus}/{@code formatCompanyStatus}. Es la única
     * garantía dura de esta feature (ver
     * docs/superpowers/specs/2026-09-20-chat-grounding-product-status-design.md).
     */
    private String handleMissionStatusQuery(String missionId) {

        var mission = missionMemory.find(missionId);

        if (mission.isEmpty()) {
            return "No tengo ese dato registrado. No existe ninguna misión con id "
                    + missionId + " en Company Memory.";
        }

        conversationMemory.setLastMentioned("MISSION", List.of(missionId));

        return formatMissionStatus(mission.get(), missionMemory.tasks(missionId));
    }

    /**
     * {@code workflowStatus} (MissionStatus) y {@code productStatus}
     * (ProductStatusService) son preguntas distintas — nunca se infiere
     * una de la otra.
     */
    private String formatMissionStatus(MissionResponse mission, List<AgentTask> tasks) {

        var productStatus = productStatusService.resolve(mission.missionId());

        var taskLines = tasks.stream()
                .map(t -> t.agentId() + "=" + t.action() + " " + t.status())
                .collect(Collectors.joining(", "));

        var involvedAgentIds = tasks.stream()
                .map(AgentTask::agentId)
                .collect(Collectors.toSet());

        var agentStatusLines = missionMemory.latestTaskPerAgent().stream()
                .filter(a -> involvedAgentIds.contains(a.agentId()))
                .map(a -> a.name() + " (" + a.role() + "): " + a.status())
                .collect(Collectors.joining(", "));

        var closing = productStatus.ordinal() < ProductStatus.DEVELOPMENT.ordinal()
                ? " No tengo registro de ninguna AgentTask de desarrollo real, evento de "
                        + "desarrollo iniciado, ni artefacto/repositorio/build para esta "
                        + "misión — no puedo afirmar que el desarrollo haya comenzado."
                : "";

        return mission.missionId() + ": workflowStatus=" + mission.status()
                + " (esto es el estado del proceso de análisis/decisión interno, "
                + "NO implica nada sobre si el producto está en desarrollo, publicado "
                + "o generando ingresos). productStatus=" + productStatus
                + ". Tareas de esta misión: " + taskLines
                + ". Estado actual de los agentes involucrados: " + agentStatusLines
                + "." + closing;
    }
```

- [ ] **Step 5: Ejecutar los tests y confirmar que pasan**

Run: `cd app && mvn test -Dtest=ChatIntentRouterTest`
Expected: PASS (todos los tests existentes + los 3 nuevos)

- [ ] **Step 6: Ejecutar la suite completa para descartar regresiones**

Run: `cd app && mvn test`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java \
        app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java
git commit -m "Chat: resolver preguntas sobre una misión puntual 100% en Java, sin alucinar estado de desarrollo"
```

---

### Task 3: `ChatIntentRouter` — referencia pronominal a `ProductStatus` sin id explícito

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java`
- Test: `app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java`

**Interfaces:**
- Consumes: `ProductStatusService.resolve(String) -> ProductStatus` (Task 1); `enum ReferencePredicate` existente (`ENVIRONMENT_TEST, FAILED, NEEDS_APPROVAL`).
- Produces: `ReferencePredicate.PRODUCT_STATUS` (nueva constante); `formatProductStatusAnswer(List<MissionResponse>) -> String` (privado, usado solo dentro de la clase).

- [ ] **Step 1: Escribir los tests nuevos (fallan: `ReferencePredicate.PRODUCT_STATUS` no existe todavía)**

Agregar después de `resolvesReferenceWhenOnlySomeOfTheMentionedMissionsMatch`:

```java
    @Test
    void resolvesProductStatusReferenceAgainstTheFocusWithoutAnyExplicitMissionId() {

        when(conversationMemory.lastMentioned()).thenReturn(
                Optional.of(new LastMentioned("MISSION", List.of("MISSION-1")))
        );
        when(missionMemory.findByIds(List.of("MISSION-1"))).thenReturn(List.of(
                new MissionResponse("MISSION-1", MissionStatus.COMPLETED, "PRODUCTION", 100, "x", "y", Instant.now())
        ));
        when(productStatusService.resolve("MISSION-1")).thenReturn(ProductStatus.DISCOVERY);

        var response = router.route("¿esas están en desarrollo?");

        assertTrue(response.contains("MISSION-1: productStatus=DISCOVERY"));
        assertTrue(response.contains("no asumas que el desarrollo comenzó"));
        verifyNoInteractions(ceoService);
    }

    @Test
    void lastMentionedTopicIncludesRealProductStatus() {

        when(companyMemory.agentName("ceo")).thenReturn(Optional.of("Alex"));
        when(companyMemory.teamRosterDescription()).thenReturn("- Sofia (Sales)");
        when(conversationMemory.lastMentioned()).thenReturn(
                Optional.of(new LastMentioned("MISSION", List.of("MISSION-1")))
        );
        when(missionMemory.findByIds(List.of("MISSION-1"))).thenReturn(List.of(
                new MissionResponse("MISSION-1", MissionStatus.COMPLETED, "PRODUCTION", 100, "x", "y", Instant.now())
        ));
        when(productStatusService.resolve("MISSION-1")).thenReturn(ProductStatus.DESIGN);
        when(ceoService.chat(anyString(), anyString(), any(), anyString(), any())).thenReturn("ok");

        router.route("contame más sobre esas");

        var captor = org.mockito.ArgumentCaptor.forClass(java.util.function.Function.class);
        verify(ceoService).chat(anyString(), anyString(), any(), anyString(), captor.capture());
        @SuppressWarnings("unchecked")
        var companyMemoryQuery = (java.util.function.Function<String, String>) captor.getValue();

        assertTrue(companyMemoryQuery.apply("LAST_MENTIONED").contains("productStatus=DESIGN"));
    }
```

(Mismo patrón exacto que el test existente `passesCompanyMemoryQueryCallbackThatResolvesAllKnownTopics` — capturar sin castear, castear solo al leer `getValue()`.)

- [ ] **Step 2: Ejecutar los tests para confirmar que fallan (compilación)**

Run: `cd app && mvn test -Dtest=ChatIntentRouterTest`
Expected: FAIL (compilación — `ProductStatus.DISCOVERY`/`DESIGN` ya existen de Task 1, pero `formatProductStatusAnswer` implícito y `ReferencePredicate.PRODUCT_STATUS` no existen)

- [ ] **Step 3: Agregar `ReferencePredicate.PRODUCT_STATUS`, el patrón y la detección**

Reemplazar el enum existente:

```java
    private enum ReferencePredicate {
        ENVIRONMENT_TEST,
        FAILED,
        NEEDS_APPROVAL,
        PRODUCT_STATUS
    }
```

Agregar la constante de patrón junto a `FOCUS_QUANTIFIER`:

```java
    private static final Pattern PRODUCT_STATUS_PREDICATE =
            Pattern.compile("\\b(desarroll\\w*|mvp|publicad\\w*|lanzad\\w*)\\b");
```

Reemplazar `detectReferencePredicate`:

```java
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

        if (PRODUCT_STATUS_PREDICATE.matcher(normalized).find()) {
            return ReferencePredicate.PRODUCT_STATUS;
        }

        return null;
    }
```

- [ ] **Step 4: Special-case `PRODUCT_STATUS` en `handleReference`, agregar `formatProductStatusAnswer` y actualizar los dos `switch` exhaustivos**

Reemplazar el final de `handleReference` (desde `log.info("CHAT_INTENT_REFERENCE predicate={}...` hasta el final del método):

```java
        log.info("CHAT_INTENT_REFERENCE predicate={} focusSize={}", predicate, focus.get().ids().size());

        var missions = missionMemory.findByIds(focus.get().ids());

        if (predicate == ReferencePredicate.PRODUCT_STATUS) {
            return formatProductStatusAnswer(missions);
        }

        return formatReferenceAnswer(predicate, missions);
    }
```

Agregar el nuevo método (por ejemplo, justo después de `formatReferenceAnswer`):

```java
    /**
     * PRODUCT_STATUS no encaja en el patrón "cuántas de estas coinciden
     * con X" de {@link #formatReferenceAnswer} — cada misión del foco
     * tiene su propio productStatus real, así que se lista una por una.
     * Mismo criterio de grounding que {@code handleMissionStatusQuery}:
     * nunca se afirma desarrollo real sin evidencia.
     */
    private String formatProductStatusAnswer(List<MissionResponse> missions) {

        if (missions.isEmpty()) {
            return "No tengo ese dato registrado. No hay ninguna misión en el foco de esta conversación.";
        }

        var statuses = missions.stream()
                .collect(Collectors.toMap(
                        MissionResponse::missionId,
                        m -> productStatusService.resolve(m.missionId())
                ));

        var lines = missions.stream()
                .map(m -> m.missionId() + ": productStatus=" + statuses.get(m.missionId()))
                .collect(Collectors.joining(", "));

        var anyBeforeDevelopment = statuses.values().stream()
                .anyMatch(s -> s.ordinal() < ProductStatus.DEVELOPMENT.ordinal());

        var closing = anyBeforeDevelopment
                ? " Ninguna de estas misiones tiene evidencia real de desarrollo (AgentTask de "
                        + "desarrollo, evento de desarrollo iniciado o artefacto/repositorio/build) "
                        + "salvo que se indique lo contrario arriba — no asumas que el desarrollo "
                        + "comenzó solo porque el workflow de análisis haya terminado."
                : "";

        return "Estado de producto real: " + lines + "." + closing;
    }
```

Actualizar los dos `switch` exhaustivos existentes agregando el caso `PRODUCT_STATUS` (nunca se llega a él en runtime porque `handleReference` lo intercepta antes, pero el compilador exige exhaustividad sobre el enum):

En `matchesReferencePredicate`:

```java
    private boolean matchesReferencePredicate(ReferencePredicate predicate, MissionResponse mission) {

        return switch (predicate) {
            case ENVIRONMENT_TEST -> "TEST".equals(mission.environment());
            case FAILED -> mission.status() == MissionStatus.FAILED;
            case NEEDS_APPROVAL -> mission.status() == MissionStatus.AWAITING_INVESTOR;
            case PRODUCT_STATUS -> throw new IllegalStateException(
                    "PRODUCT_STATUS se resuelve en formatProductStatusAnswer, nunca aquí");
        };
    }
```

En `formatReferenceAnswer` (el `switch` de `description`):

```java
        var description = switch (predicate) {
            case ENVIRONMENT_TEST -> "pertenecen al entorno de pruebas";
            case FAILED -> "fallaron";
            case NEEDS_APPROVAL -> "están esperando tu aprobación (AWAITING_INVESTOR)";
            case PRODUCT_STATUS -> throw new IllegalStateException(
                    "PRODUCT_STATUS se resuelve en formatProductStatusAnswer, nunca aquí");
        };
```

- [ ] **Step 5: Extender `formatLastMentioned` con `productStatus`**

Reemplazar el método:

```java
    private String formatLastMentioned() {

        var focus = conversationMemory.lastMentioned();

        if (focus.isEmpty()) {
            return "No hay ninguna mención reciente de misiones en esta conversación.";
        }

        var missions = missionMemory.findByIds(focus.get().ids());

        var lines = missions.stream()
                .map(m -> m.missionId() + " (environment=" + m.environment()
                        + ", workflowStatus=" + m.status()
                        + ", productStatus=" + productStatusService.resolve(m.missionId()) + ")")
                .collect(Collectors.joining(", "));

        return "Las últimas misiones mencionadas fueron: " + lines + ".";
    }
```

- [ ] **Step 6: Ejecutar los tests y confirmar que pasan**

Run: `cd app && mvn test -Dtest=ChatIntentRouterTest`
Expected: PASS (todos los tests existentes + los 2 nuevos de este task)

- [ ] **Step 7: Ejecutar la suite completa**

Run: `cd app && mvn test`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java \
        app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java
git commit -m "Chat: grounding de productStatus también en referencias pronominales sin id explícito"
```

---

### Task 4: `CeoService` — refuerzo del system prompt + documentación

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/CeoService.java`
- Modify: `/home/alex/Documentos/projectos/empresa/CLAUDE.md`

**Interfaces:**
- Consumes: ninguna nueva — solo texto agregado al `system` de `chat()`.
- Produces: ninguna — cierre de la feature.

Esta es la única parte de la feature que **no** tiene garantía dura (ver spec, sección "Limitación conocida") — es una instrucción al LLM para el chat general que no cae en ninguna rama determinista de las Tasks 2/3. No lleva test nuevo dedicado: `systemPrompt()`/`toolDecisionSystemPrompt()` ya son strings no testeados directamente hoy (mismo criterio existente — `CeoServiceToolFormatGuardTest` testea guards estructurales, no contenido de prompt), y mockear la cadena fluida de `RestClient` para capturar el `system` exacto de `chat()` sería una inversión de test desproporcionada para un string que el propio diseño ya documenta como best-effort.

- [ ] **Step 1: Agregar las reglas nuevas al `system` de `chat()`**

En `CeoService.chat`, extender la concatenación de `system` (el bloque que termina en `+ " recomendación sobre algo que no existe en Company" + " Memory.";`) agregando:

```java
                + "\nRegla dura sobre estado de misiones: el workflowStatus de una"
                + " misión (CREATED/PLANNING/.../AWAITING_INVESTOR/COMPLETED) es el"
                + " estado del proceso de ANÁLISIS INTERNO — nunca lo uses para"
                + " afirmar nada sobre el estado real del producto (si está en"
                + " desarrollo, publicado o generando ingresos). Una AgentTask"
                + " DELIVERY_FEASIBILITY completada es un estudio de factibilidad,"
                + " NO significa que el desarrollo haya comenzado. Si te preguntan"
                + " por el estado de desarrollo/negocio de una misión y no tenés"
                + " ese dato exacto en este mensaje ni de query_company_memory,"
                + " respondé exactamente: \"No tengo ese dato registrado.\" — nunca"
                + " asumas que un paso avanzó porque otro paso anterior terminó.";
```

- [ ] **Step 2: Ejecutar la suite completa de tests**

Run: `cd app && mvn test`
Expected: PASS (sin regresiones — `CeoServiceChatHistoryTest`/`CeoServiceToolFormatGuardTest` no dependen del contenido exacto de este string)

- [ ] **Step 3: Actualizar `CLAUDE.md`**

En la sección "### Chat Intent Router (`ChatIntentRouter`)" de `/home/alex/Documentos/projectos/empresa/CLAUDE.md`, agregar un párrafo nuevo después del punto numerado 6 (antes del párrafo que empieza con "`Mission.environment`..."):

```markdown
`ProductStatus` (`DISCOVERY/DESIGN/DEVELOPMENT/QA/PUBLISHED/MONETIZING/BUSINESS_SUCCESS`,
`model/ProductStatus.java`) es el estado real del *producto*, deliberadamente
separado de `MissionStatus` (el workflow de análisis/decisión) — nunca se
infiere uno del otro. `ProductStatusService.resolve(missionId)` lo calcula en
cada consulta a partir de señales reales (`AgentTask` `OFFER_DESIGN`
completada → `DESIGN`; `Transaction` real → `MONETIZING`; `netProfit` sobre
capital semilla → `BUSINESS_SUCCESS`), sin persistir nada nuevo.
`DEVELOPMENT`/`QA`/`PUBLISHED` quedan modelados pero **inalcanzables** hoy
(siempre `false` en el servicio) — son el punto de enganche de una futura
ejecución real de código/infraestructura tras la aprobación del
inversionista, todavía sin diseñar. Un `MISSION-<id>` explícito en el chat
que no sea inicio ni decisión se resuelve **100% en Java** contra
`ProductStatusService` + `MissionMemoryService` (nunca pasa por Ollama) —
mismo motivo que llevó a esto: el chat afirmó una vez "el desarrollo está en
curso" sobre una misión `COMPLETED` con agentes `IDLE`, sin ninguna
evidencia real.
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/CeoService.java CLAUDE.md
git commit -m "Chat: reforzar el system prompt contra confundir workflowStatus con estado de producto; documentar ProductStatus"
```

---

## Self-Review (completado antes de entregar el plan)

- **Cobertura del spec**: sección 1 (separación de conceptos) → Global Constraints + Task 2/4; sección 2 (`ProductStatusService`) → Task 1; sección 3 (rama determinista `MISSION-<id>`) → Task 2; sección 4 (referencia pronominal) → Task 3; sección 5 (refuerzo de prompt) → Task 4; sección 6 (sin REST/frontend) → respetado, ningún task toca `MissionController`/`MissionResponse`/frontend; testing (`ProductStatusServiceTest`, test de protección anti-alucinación, `ChatIntentRouterTest`) → Tasks 1-3; nota sobre la regla 12 (integración `APPROVE`→tareas→`WORKING`) → explícitamente fuera de alcance (Proyecto B), documentado en el spec, no en este plan.
- **Placeholders**: ninguno — todo paso trae el código completo a escribir/reemplazar.
- **Consistencia de tipos**: `ProductStatusService.resolve(String) -> ProductStatus` usado igual en Task 2 y Task 3; `AgentTask.agentId()/action()/status()` y `AgentStatusResponse.agentId()/name()/role()/status()` usados con los nombres reales del código existente (verificados por lectura directa, no supuestos); el noveno parámetro de constructor de `ChatIntentRouter` (`productStatusService`) es consistente entre el archivo principal y el test en las tres tasks que lo tocan.
