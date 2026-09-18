# CompanyTools: consultas con parámetros reales para el chat — plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reemplazar el `switch` de `ChatIntentRouter.answerMemoryTopic` por una clase `CompanyTools` con métodos tipados que soportan parámetros reales (`missionId`), disponible tanto para el atajo determinista de keywords como para la herramienta `query_company_memory` del chat general del CEO.

**Architecture:** `CompanyTools` (nueva) consolida toda la lógica de formateo determinista que hoy vive dispersa como métodos privados de `ChatIntentRouter`. `ChatIntentRouter` y `CeoService` la consumen por composición (inyección de constructor). La herramienta del CEO gana un parámetro `id` opcional y dos topics nuevos (`MISSION_DETAILS`, `OPPORTUNITY_DETAILS`) que antes solo estaban disponibles vía el atajo determinista, nunca para el chat general.

**Tech Stack:** Java 21, Spring Boot 4.1.1, JUnit 5 + Mockito.

**Spec:** `docs/superpowers/specs/2026-09-18-company-tools-design.md`

## Global Constraints

- Todos los métodos de `CompanyTools` devuelven `String` ya formateado en español — nunca datos crudos que el LLM tendría que interpretar/contar (mismo criterio anti-alucinación de todo el proyecto).
- Ningún comportamiento observable existente cambia en las Tasks 1-2 — es una relocación de código, no una reescritura de lógica. Los tests existentes deben poder seguir pasando sin reescribir sus cuerpos (solo el bloque de construcción del router en `ChatIntentRouterTest`).
- `CompanyTools` nunca hace mutaciones de gobernanza (`approve`/`reject`/`recordDecision`) — eso se queda exclusivamente en `MissionService`, sin tocar.
- `mvn test` debe quedar en verde después de cada tarea.

---

### Task 1: Crear `CompanyTools` con los 11 métodos de consulta

**Files:**
- Create: `app/src/main/java/com/aicompany/core/service/CompanyTools.java`
- Create: `app/src/test/java/com/aicompany/core/service/CompanyToolsTest.java`

**Interfaces:**
- Produces: `CompanyTools(MissionService, MissionMemoryService, OpportunityMemoryService, CustomerMemoryService, ConversationMemoryService, AppProperties)` con los métodos públicos `getCompanyStatus()`, `getAgentStatus()`, `getPendingApprovals()`, `getFailedMissions()`, `getTestMissions()`, `getOpportunities()`, `getOpportunity(String missionId)`, `getProspects()`, `getFinancialStatus()`, `getMission(String missionId)`, `getLastMentioned()`, `formatCandidate(LeadResponse)` — todos usados por la Task 2.
- Esta tarea NO toca `ChatIntentRouter` — `CompanyTools` queda como código nuevo, standalone, completamente cubierto por su propio test.

- [ ] **Step 1: Crear `CompanyTools.java` completo**

```java
package com.aicompany.core.service;

import com.aicompany.core.config.AppProperties;
import com.aicompany.core.model.AgentStatusResponse;
import com.aicompany.core.model.LeadResponse;
import com.aicompany.core.model.MissionResponse;
import com.aicompany.core.model.MissionStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Consultas reales y actuales de la empresa contra Neo4j, formateadas
 * 100% en Java — contar/enumerar/formatear un estado real es una tarea
 * determinista, nunca se le pide al LLM que la arme (mismo criterio
 * anti-alucinación de todo el proyecto: el bug real de "dame un status"
 * rellenando placeholders inventados, documentado en {@code CLAUDE.md}).
 * Único punto de acceso a estas consultas: tanto el atajo determinista de
 * {@link ChatIntentRouter} (keywords, sin tocar Ollama) como la
 * herramienta {@code query_company_memory} que {@link CeoService#chat}
 * puede pedir para el chat general llaman a los mismos métodos acá —
 * antes de esta clase, cada camino tenía su propia copia del formateo
 * dentro de {@code ChatIntentRouter}, y una consulta que necesitaba un
 * parámetro (p. ej. el detalle de una misión puntual) nunca quedaba
 * disponible para el chat general, solo para el atajo de keywords.
 */
@Service
public class CompanyTools {

    private final MissionService missionService;
    private final MissionMemoryService missionMemory;
    private final OpportunityMemoryService opportunityMemory;
    private final CustomerMemoryService customerMemory;
    private final ConversationMemoryService conversationMemory;
    private final AppProperties appProperties;

    public CompanyTools(
            MissionService missionService,
            MissionMemoryService missionMemory,
            OpportunityMemoryService opportunityMemory,
            CustomerMemoryService customerMemory,
            ConversationMemoryService conversationMemory,
            AppProperties appProperties) {

        this.missionService = missionService;
        this.missionMemory = missionMemory;
        this.opportunityMemory = opportunityMemory;
        this.customerMemory = customerMemory;
        this.conversationMemory = conversationMemory;
        this.appProperties = appProperties;
    }

    /**
     * Snapshot agregado y 100% real de la empresa. Cada número acá sale
     * de una consulta real a Neo4j, nunca del modelo.
     */
    public String getCompanyStatus() {

        var missions = missionMemory.findAll(50).stream()
                .filter(m -> "PRODUCTION".equals(m.environment()))
                .toList();

        var active = missions.stream()
                .filter(m -> m.status() != MissionStatus.AWAITING_INVESTOR
                        && m.status() != MissionStatus.FAILED
                        && m.status() != MissionStatus.COMPLETED
                        && m.status() != MissionStatus.CANCELLED)
                .count();

        var awaitingInvestor = missions.stream()
                .filter(m -> m.status() == MissionStatus.AWAITING_INVESTOR)
                .count();

        var failed = missions.stream()
                .filter(m -> m.status() == MissionStatus.FAILED)
                .count();

        var agentStatuses = missionMemory.latestTaskPerAgent();

        var working = agentStatuses.stream().filter(a -> "WORKING".equals(a.status())).count();
        var idle = agentStatuses.stream().filter(a -> "IDLE".equals(a.status())).count();

        var opportunities = opportunityMemory.countOpportunities();

        var customerCounts = customerMemory.countCustomersAndProspects();
        var customers = customerCounts[0];
        var prospects = customerCounts[1];

        var totals = customerMemory.companyWideTotalRevenueAndCost();
        var revenue = totals[0];
        var netProfit = totals[0] - totals[1];

        return String.format(
                Locale.ROOT,
                "Estado actual de Forjai: capital disponible US$%.2f. "
                        + "Agentes: %d trabajando, %d inactivo(s). "
                        + "Misiones (producción): %d activa(s), %d esperando tu aprobación, %d fallida(s). "
                        + "Oportunidades registradas: %d. Prospectos (leads): %d. Clientes reales: %d. "
                        + "Ingresos: US$%.2f. Beneficio neto: US$%.2f.",
                appProperties.seedCapitalUsd(), working, idle,
                active, awaitingInvestor, failed,
                opportunities, prospects, customers,
                revenue, netProfit
        );
    }

    public String getAgentStatus() {

        var lines = missionMemory.latestTaskPerAgent().stream()
                .map(this::formatOneAgentStatus)
                .collect(Collectors.joining("; "));

        return "Estado real de los agentes: " + lines + ".";
    }

    /**
     * {@code a.status()} (WORKING/IDLE) es el estado propio del agente,
     * distinto de {@code a.taskStatus()} (el status de su última
     * AgentTask).
     */
    private String formatOneAgentStatus(AgentStatusResponse a) {

        var base = statusDot(a.status()) + " " + a.name() + " (" + a.role() + "): " + a.status();

        if (a.missionId() == null) {
            return base;
        }

        if ("WORKING".equals(a.status())) {
            return base + " (" + a.missionId()
                    + (a.action() == null ? "" : ", " + a.action())
                    + ")";
        }

        return base + " — última tarea: " + a.action() + " (" + a.missionId()
                + "), resultado: " + a.taskStatus();
    }

    private static final Set<String> STATUS_GREEN =
            Set.of("RUNNING", "WORKING", "ACTIVE", "COMPLETED");
    private static final Set<String> STATUS_RED =
            Set.of("FAILED", "CANCELLED");
    private static final Set<String> STATUS_YELLOW = Set.of(
            "PENDING", "WAITING", "AWAITING_INVESTOR", "CONSOLIDATING",
            "EVALUATING", "WAITING_AGENT_RESULTS", "PLANNING", "DELEGATING", "CREATED"
    );

    /**
     * Mismo mapeo semántico que {@code statusColor.ts} del frontend.
     */
    private static String statusDot(String status) {
        var upper = status.toUpperCase(Locale.ROOT);
        if (STATUS_GREEN.contains(upper)) return "🟢";
        if (STATUS_RED.contains(upper)) return "🔴";
        if (STATUS_YELLOW.contains(upper)) return "🟡";
        return "⚪";
    }

    /**
     * Estrictamente {@code AWAITING_INVESTOR} — una misión {@code FAILED}
     * no "necesita aprobación", tiene su propia consulta
     * ({@link #getFailedMissions()}).
     */
    public String getPendingApprovals() {

        var awaitingApproval = missionMemory.findAll(50).stream()
                .filter(m -> m.status() == MissionStatus.AWAITING_INVESTOR)
                .filter(m -> "PRODUCTION".equals(m.environment()))
                .toList();

        conversationMemory.setLastMentioned(
                "MISSION",
                awaitingApproval.stream().map(MissionResponse::missionId).toList()
        );

        if (awaitingApproval.isEmpty()) {
            return "No hay ninguna misión que necesite tu aprobación en este momento.";
        }

        var lines = awaitingApproval.stream()
                .map(m -> m.missionId() + " (" + m.status() + ")")
                .collect(Collectors.joining(", "));

        return "Tenés " + awaitingApproval.size()
                + " misión(es) que necesitan tu aprobación: " + lines + ".";
    }

    public String getFailedMissions() {

        var failed = missionMemory.findAll(50).stream()
                .filter(m -> m.status() == MissionStatus.FAILED)
                .filter(m -> "PRODUCTION".equals(m.environment()))
                .toList();

        conversationMemory.setLastMentioned(
                "MISSION",
                failed.stream().map(MissionResponse::missionId).toList()
        );

        if (failed.isEmpty()) {
            return "No hay ninguna misión fallida en este momento.";
        }

        var lines = failed.stream()
                .map(MissionResponse::missionId)
                .collect(Collectors.joining(", "));

        return "Tenés " + failed.size() + " misión(es) fallida(s): " + lines + ".";
    }

    /**
     * {@code environment == "TEST"}, cualquier status.
     */
    public String getTestMissions() {

        var test = missionMemory.findAll(50).stream()
                .filter(m -> "TEST".equals(m.environment()))
                .toList();

        conversationMemory.setLastMentioned(
                "MISSION",
                test.stream().map(MissionResponse::missionId).toList()
        );

        if (test.isEmpty()) {
            return "No hay ninguna misión en entorno de prueba en este momento.";
        }

        var lines = test.stream()
                .map(m -> m.missionId() + " (" + m.status() + ")")
                .collect(Collectors.joining(", "));

        return "Tenés " + test.size() + " misión(es) en entorno de prueba: " + lines + ".";
    }

    public String getOpportunities() {

        var opportunities = opportunityMemory.listRecent(20);

        if (opportunities.isEmpty()) {
            return "Todavía no hay ninguna oportunidad identificada.";
        }

        var lines = opportunities.stream()
                .map(o -> o.id() + " (misión " + o.missionId() + ", estado "
                        + o.status() + "): " + o.description())
                .collect(Collectors.joining(" | "));

        return "Tenés " + opportunities.size()
                + " oportunidad(es) identificada(s): " + lines;
    }

    /**
     * A diferencia de {@link #getOpportunities()} (lista global, sin
     * prospectos), trae la Opportunity real de una misión puntual junto
     * con sus prospectos reales ordenados por confidence descendente, y
     * setea el foco conversacional {@code type="CUSTOMER"} — solo cuando
     * hay algo real que enfocar (una oportunidad sin prospectos todavía
     * no debe pisar un foco previo, posiblemente todavía útil, con una
     * lista vacía).
     */
    public String getOpportunity(String missionId) {

        var opportunity = opportunityMemory.findByMissionId(missionId);

        if (opportunity.isEmpty()) {
            return "No encontré ninguna oportunidad para " + missionId + ".";
        }

        var candidates = opportunityMemory.listCandidatesForMission(missionId);

        if (!candidates.isEmpty()) {
            conversationMemory.setLastMentioned(
                    "CUSTOMER",
                    candidates.stream().map(LeadResponse::id).toList()
            );
        }

        var header = "Oportunidad " + opportunity.get().id() + " (misión " + opportunity.get().missionId()
                + ", estado " + opportunity.get().status() + "): " + opportunity.get().description();

        if (candidates.isEmpty()) {
            return header + " Todavía no hay ningún prospecto real identificado para esta oportunidad.";
        }

        var lines = candidates.stream()
                .map(this::formatCandidate)
                .collect(Collectors.joining(" | "));

        return header + " Prospectos reales identificados (" + candidates.size()
                + "), ordenados por probabilidad: " + lines;
    }

    public String getProspects() {

        var leads = opportunityMemory.listLeads();

        if (leads.isEmpty()) {
            return "No hay ningún lead activo para contactar.";
        }

        var lines = leads.stream()
                .map(l -> l.id() + " (misión " + l.missionId() + "): "
                        + l.name() + " — " + l.description())
                .collect(Collectors.joining(" | "));

        return "Tenés " + leads.size()
                + " lead(s) activo(s) para contactar: " + lines;
    }

    public String getFinancialStatus() {

        var totals = customerMemory.companyWideTotalRevenueAndCost();
        var revenue = totals[0];
        var cost = totals[1];
        var netProfit = revenue - cost;

        return String.format(
                Locale.ROOT,
                "Ingresos totales reales: US$%.2f. Costos totales reales: "
                        + "US$%.2f. Utilidad neta real: US$%.2f.",
                revenue, cost, netProfit
        );
    }

    /**
     * Detalle real de una misión puntual (status, progreso, paso actual,
     * tareas de cada agente) — antes solo disponible para el atajo
     * determinista de keywords, nunca para el chat general del CEO vía
     * herramienta (esa limitación se cierra en la Task 3 de este plan).
     */
    public String getMission(String missionId) {

        var details = missionService.details(missionId);

        if (details.isEmpty()) {
            return "No encontré la misión " + missionId + ".";
        }

        conversationMemory.setLastMentioned("MISSION", List.of(missionId));

        var mission = details.get().mission();

        var tasksSummary = details.get().tasks().isEmpty()
                ? "sin tareas registradas todavía"
                : details.get().tasks().stream()
                        .map(t -> t.agentId() + ": " + t.status())
                        .collect(Collectors.joining(", "));

        return "La misión " + mission.missionId() + " está en " + mission.status()
                + " (" + mission.progress() + "% completado, paso actual: "
                + mission.currentStep() + "). " + mission.message()
                + ". Tareas de agentes: " + tasksSummary + ".";
    }

    /**
     * Detalle real del foco actual de la conversación — ramifica por
     * {@code type} porque un foco puede ser {@code MISSION} o
     * {@code CUSTOMER} (ver {@link #getOpportunity}).
     */
    public String getLastMentioned() {

        var focus = conversationMemory.lastMentioned();

        if (focus.isEmpty()) {
            return "No hay ninguna mención reciente de misiones ni prospectos en esta conversación.";
        }

        if ("CUSTOMER".equals(focus.get().type())) {
            return formatLastMentionedCustomers(focus.get().ids());
        }

        var missions = missionMemory.findByIds(focus.get().ids());

        var lines = missions.stream()
                .map(m -> m.missionId() + " (environment=" + m.environment() + ", status=" + m.status() + ")")
                .collect(Collectors.joining(", "));

        return "Las últimas misiones mencionadas fueron: " + lines + ".";
    }

    private String formatLastMentionedCustomers(List<String> ids) {

        var candidates = opportunityMemory.findCandidatesByIds(ids);

        if (candidates.isEmpty()) {
            return "No hay datos reales registrados para los prospectos mencionados recientemente.";
        }

        var lines = candidates.stream()
                .map(this::formatCandidate)
                .collect(Collectors.joining(" | "));

        return "Los últimos prospectos mencionados fueron: " + lines + ".";
    }

    /**
     * Fragmento de formato compartido — también usado por
     * {@code ChatIntentRouter.formatCustomerReferenceAnswer} (la lógica
     * de "contactalo" se queda en el router porque es desambiguación de
     * una referencia conversacional, no una consulta de datos de la
     * empresa, pero necesita el mismo formato de salida). Antepone el
     * {@code status} real cuando ya no es {@code LEAD} (p. ej.
     * {@code DESCARTADO}/{@code CONVERTIDO}): {@code findCandidatesByIds}
     * deliberadamente no filtra por status, así que sin esta marca un
     * lead ya descartado o convertido se presentaría como si siguiera
     * activo.
     */
    public String formatCandidate(LeadResponse c) {

        var status = c.status();

        var statusPrefix = (status != null && !status.isBlank() && !"LEAD".equals(status))
                ? "(" + status + ") "
                : "";

        return statusPrefix + c.name() + " (confidence="
                + String.format(Locale.ROOT, "%.2f", c.confidence())
                + ", fuente: " + c.source() + "): " + c.description();
    }
}
```

- [ ] **Step 2: Crear `CompanyToolsTest.java` completo**

```java
package com.aicompany.core.service;

import com.aicompany.core.config.AppProperties;
import com.aicompany.core.model.AgentStatusResponse;
import com.aicompany.core.model.AgentTask;
import com.aicompany.core.model.LastMentioned;
import com.aicompany.core.model.LeadResponse;
import com.aicompany.core.model.MissionResponse;
import com.aicompany.core.model.MissionStatus;
import com.aicompany.core.model.MissionStatusResponse;
import com.aicompany.core.model.OpportunitySummary;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompanyToolsTest {

    private final MissionService missionService = mock(MissionService.class);
    private final MissionMemoryService missionMemory = mock(MissionMemoryService.class);
    private final OpportunityMemoryService opportunityMemory = mock(OpportunityMemoryService.class);
    private final CustomerMemoryService customerMemory = mock(CustomerMemoryService.class);
    private final ConversationMemoryService conversationMemory = mock(ConversationMemoryService.class);
    private final AppProperties appProperties = new AppProperties("Forjai", 50.0, 60);

    private final CompanyTools tools = new CompanyTools(
            missionService, missionMemory, opportunityMemory, customerMemory, conversationMemory, appProperties
    );

    @Test
    void getCompanyStatusAggregatesRealDataAcrossServices() {
        var missions = List.of(
                new MissionResponse("MISSION-1", MissionStatus.WAITING_AGENT_RESULTS, "PRODUCTION", 30, "x", "x", Instant.now()),
                new MissionResponse("MISSION-2", MissionStatus.AWAITING_INVESTOR, "PRODUCTION", 95, "x", "x", Instant.now()),
                new MissionResponse("MISSION-3", MissionStatus.FAILED, "PRODUCTION", 100, "x", "x", Instant.now()),
                new MissionResponse("MISSION-TEST", MissionStatus.FAILED, "TEST", 100, "x", "x", Instant.now())
        );
        when(missionMemory.findAll(50)).thenReturn(missions);
        when(missionMemory.latestTaskPerAgent()).thenReturn(List.of(
                new AgentStatusResponse("sales", "Sofia", "Sales", "x", "WORKING", "MISSION-1", "MARKET_DISCOVERY", "RUNNING", Instant.now()),
                new AgentStatusResponse("finance", "Max", "Finance", "x", "IDLE", null, null, null, Instant.now())
        ));
        when(opportunityMemory.countOpportunities()).thenReturn(4L);
        when(customerMemory.countCustomersAndProspects()).thenReturn(new long[]{2L, 3L});
        when(customerMemory.companyWideTotalRevenueAndCost()).thenReturn(new double[]{150.0, 50.0});

        var response = tools.getCompanyStatus();

        assertTrue(response.contains("US$50.00"));
        assertTrue(response.contains("1 trabajando"));
        assertTrue(response.contains("1 inactivo"));
        assertTrue(response.contains("1 activa"));
        assertTrue(response.contains("1 esperando tu aprobación"));
        assertTrue(response.contains("1 fallida"));
        assertTrue(response.contains("Oportunidades registradas: 4"));
        assertTrue(response.contains("Clientes reales: 2"));
        assertTrue(response.contains("US$150.00"));
        assertTrue(response.contains("US$100.00"));
    }

    @Test
    void getAgentStatusFormatsWorkingAndIdleAgents() {
        when(missionMemory.latestTaskPerAgent()).thenReturn(List.of(
                new AgentStatusResponse("sales", "Sofia", "Director of Sales AI", "x", "WORKING", "MISSION-1", "MARKET_DISCOVERY", "RUNNING", Instant.now()),
                new AgentStatusResponse("ceo", "Alex", "Chief Executive Officer AI", "x", "IDLE", null, null, null, null)
        ));

        var response = tools.getAgentStatus();

        assertTrue(response.contains("🟢 Sofia (Director of Sales AI): WORKING (MISSION-1, MARKET_DISCOVERY)"));
        assertTrue(response.contains("⚪ Alex (Chief Executive Officer AI): IDLE"));
    }

    @Test
    void getPendingApprovalsFiltersStrictlyAwaitingInvestorInProductionAndSetsFocus() {
        var awaiting = new MissionResponse("MISSION-1", MissionStatus.AWAITING_INVESTOR, "PRODUCTION", 95, "x", "y", Instant.now());
        var testAwaiting = new MissionResponse("MISSION-T", MissionStatus.AWAITING_INVESTOR, "TEST", 95, "x", "y", Instant.now());
        var failed = new MissionResponse("MISSION-3", MissionStatus.FAILED, "PRODUCTION", 100, "x", "y", Instant.now());
        when(missionMemory.findAll(50)).thenReturn(List.of(awaiting, testAwaiting, failed));

        var response = tools.getPendingApprovals();

        assertTrue(response.contains("1 misión"));
        assertTrue(response.contains("MISSION-1"));
        assertFalse(response.contains("MISSION-T"));
        assertFalse(response.contains("MISSION-3"));
        verify(conversationMemory).setLastMentioned("MISSION", List.of("MISSION-1"));
    }

    @Test
    void getPendingApprovalsReturnsDeterministicEmptyMessage() {
        when(missionMemory.findAll(50)).thenReturn(List.of());

        var response = tools.getPendingApprovals();

        assertEquals("No hay ninguna misión que necesite tu aprobación en este momento.", response);
    }

    @Test
    void getFailedMissionsFiltersStrictlyFailedInProductionAndSetsFocus() {
        var awaiting = new MissionResponse("MISSION-1", MissionStatus.AWAITING_INVESTOR, "PRODUCTION", 95, "x", "y", Instant.now());
        var failed = new MissionResponse("MISSION-3", MissionStatus.FAILED, "PRODUCTION", 100, "x", "z", Instant.now());
        when(missionMemory.findAll(50)).thenReturn(List.of(awaiting, failed));

        var response = tools.getFailedMissions();

        assertTrue(response.contains("1 misión"));
        assertTrue(response.contains("MISSION-3"));
        verify(conversationMemory).setLastMentioned("MISSION", List.of("MISSION-3"));
    }

    @Test
    void getTestMissionsFiltersByEnvironmentAndSetsFocus() {
        var testOne = new MissionResponse("MISSION-DEBUG-007", MissionStatus.AWAITING_INVESTOR, "TEST", 95, "x", "y", Instant.now());
        var realOne = new MissionResponse("MISSION-001", MissionStatus.AWAITING_INVESTOR, "PRODUCTION", 95, "x", "y", Instant.now());
        when(missionMemory.findAll(50)).thenReturn(List.of(testOne, realOne));

        var response = tools.getTestMissions();

        assertTrue(response.contains("1 misión"));
        assertTrue(response.contains("MISSION-DEBUG-007"));
        verify(conversationMemory).setLastMentioned("MISSION", List.of("MISSION-DEBUG-007"));
    }

    @Test
    void getOpportunitiesListsRecentWithoutId() {
        when(opportunityMemory.listRecent(20)).thenReturn(List.of(
                new OpportunitySummary("MISSION-1-OPPORTUNITY", "MISSION-1", "asesoría a microempresas", "IDENTIFIED", Instant.now())
        ));

        var response = tools.getOpportunities();

        assertTrue(response.contains("1 oportunidad"));
        assertTrue(response.contains("asesoría a microempresas"));
    }

    @Test
    void getOpportunityBringsCandidatesOrderedByConfidenceAndSetsCustomerFocus() {
        when(opportunityMemory.findByMissionId("MISSION-1")).thenReturn(Optional.of(
                new OpportunitySummary("MISSION-1-OPPORTUNITY", "MISSION-1", "asesoría a microempresas", "IDENTIFIED", Instant.now())
        ));
        when(opportunityMemory.listCandidatesForMission("MISSION-1")).thenReturn(List.of(
                new LeadResponse(
                        "MISSION-1-CANDIDATE-SALES-0", "Panadería El Sol",
                        "Identificada en estudio de mercado", "https://example.com", "WEB",
                        "MISSION-1", "MISSION-1-OPPORTUNITY", Instant.now(),
                        "LEAD", null, null, 0.8
                )
        ));

        var response = tools.getOpportunity("MISSION-1");

        assertTrue(response.contains("asesoría a microempresas"));
        assertTrue(response.contains("Panadería El Sol"));
        assertTrue(response.contains("0.80"));
        verify(conversationMemory).setLastMentioned("CUSTOMER", List.of("MISSION-1-CANDIDATE-SALES-0"));
    }

    @Test
    void getOpportunityDoesNotSetFocusWhenNoCandidatesYet() {
        when(opportunityMemory.findByMissionId("MISSION-5")).thenReturn(Optional.of(
                new OpportunitySummary("MISSION-5-OPPORTUNITY", "MISSION-5", "consultoría fiscal", "IDENTIFIED", Instant.now())
        ));
        when(opportunityMemory.listCandidatesForMission("MISSION-5")).thenReturn(List.of());

        var response = tools.getOpportunity("MISSION-5");

        assertTrue(response.contains("Todavía no hay ningún prospecto real identificado"));
        verify(conversationMemory, never()).setLastMentioned(any(), any());
    }

    @Test
    void getOpportunityReportsWhenOpportunityNotFound() {
        when(opportunityMemory.findByMissionId("MISSION-404")).thenReturn(Optional.empty());

        var response = tools.getOpportunity("MISSION-404");

        assertTrue(response.contains("No encontré ninguna oportunidad"));
    }

    @Test
    void getProspectsListsGlobalLeads() {
        when(opportunityMemory.listLeads()).thenReturn(List.of(
                new LeadResponse(
                        "MISSION-1-CANDIDATE-SALES-0", "Panadería El Sol",
                        "Identificada en estudio de mercado", "https://example.com", "WEB",
                        "MISSION-1", "MISSION-1-OPPORTUNITY", Instant.now(),
                        "LEAD", null, null, 0.8
                )
        ));

        var response = tools.getProspects();

        assertTrue(response.contains("1 lead"));
        assertTrue(response.contains("Panadería El Sol"));
    }

    @Test
    void getFinancialStatusComputesNetProfit() {
        when(customerMemory.companyWideTotalRevenueAndCost()).thenReturn(new double[]{120.0, 40.0});

        var response = tools.getFinancialStatus();

        assertTrue(response.contains("120.00"));
        assertTrue(response.contains("40.00"));
        assertTrue(response.contains("80.00"));
    }

    @Test
    void getMissionReturnsFormattedDetailsAndSetsFocus() {
        var mission = new MissionResponse(
                "MISSION-1789701859658", MissionStatus.WAITING_AGENT_RESULTS, "PRODUCTION",
                35, "Esperando resultados", "Los agentes están trabajando en paralelo.", Instant.now()
        );
        var tasks = List.of(new AgentTask(
                "MISSION-1789701859658-SALES", "MISSION-1789701859658", "sales",
                "MARKET_DISCOVERY", "RUNNING", "", Instant.now()
        ));
        when(missionService.details("MISSION-1789701859658"))
                .thenReturn(Optional.of(new MissionStatusResponse(mission, tasks)));

        var response = tools.getMission("MISSION-1789701859658");

        assertTrue(response.contains("MISSION-1789701859658"));
        assertTrue(response.contains("WAITING_AGENT_RESULTS"));
        assertTrue(response.contains("35"));
        assertTrue(response.contains("sales"));
        verify(conversationMemory).setLastMentioned("MISSION", List.of("MISSION-1789701859658"));
    }

    @Test
    void getMissionReportsNotFound() {
        when(missionService.details("MISSION-404")).thenReturn(Optional.empty());

        var response = tools.getMission("MISSION-404");

        assertEquals("No encontré la misión MISSION-404.", response);
    }

    @Test
    void getLastMentionedFormatsMissionFocus() {
        when(conversationMemory.lastMentioned()).thenReturn(
                Optional.of(new LastMentioned("MISSION", List.of("MISSION-1")))
        );
        when(missionMemory.findByIds(List.of("MISSION-1"))).thenReturn(List.of(
                new MissionResponse("MISSION-1", MissionStatus.FAILED, "TEST", 100, "x", "y", Instant.now())
        ));

        var response = tools.getLastMentioned();

        assertTrue(response.contains("MISSION-1"));
        assertTrue(response.contains("environment=TEST"));
    }

    @Test
    void getLastMentionedFormatsCustomerFocus() {
        when(conversationMemory.lastMentioned()).thenReturn(
                Optional.of(new LastMentioned("CUSTOMER", List.of("MISSION-1-CANDIDATE-SALES-0")))
        );
        when(opportunityMemory.findCandidatesByIds(List.of("MISSION-1-CANDIDATE-SALES-0"))).thenReturn(List.of(
                new LeadResponse(
                        "MISSION-1-CANDIDATE-SALES-0", "Panadería El Sol",
                        "Identificada en estudio de mercado", "https://example.com", "WEB",
                        "MISSION-1", "MISSION-1-OPPORTUNITY", Instant.now(),
                        "LEAD", null, null, 0.8
                )
        ));

        var response = tools.getLastMentioned();

        assertTrue(response.contains("Panadería El Sol"));
    }

    @Test
    void getLastMentionedReportsNoFocus() {
        when(conversationMemory.lastMentioned()).thenReturn(Optional.empty());

        var response = tools.getLastMentioned();

        assertEquals("No hay ninguna mención reciente de misiones ni prospectos en esta conversación.", response);
    }

    @Test
    void formatCandidatePrefixesNonLeadStatus() {
        var discarded = new LeadResponse(
                "ID-1", "Panadería El Sol", "descripción", "https://example.com", "WEB",
                "MISSION-1", "MISSION-1-OPPORTUNITY", Instant.now(),
                "DESCARTADO", "no responde", Instant.now(), 0.5
        );

        var formatted = tools.formatCandidate(discarded);

        assertTrue(formatted.startsWith("(DESCARTADO) Panadería El Sol"));
    }

    @Test
    void formatCandidateHasNoPrefixForLeadStatus() {
        var active = new LeadResponse(
                "ID-1", "Panadería El Sol", "descripción", "https://example.com", "WEB",
                "MISSION-1", "MISSION-1-OPPORTUNITY", Instant.now(),
                "LEAD", null, null, 0.5
        );

        var formatted = tools.formatCandidate(active);

        assertTrue(formatted.startsWith("Panadería El Sol"));
    }
}
```

- [ ] **Step 3: Correr la suite y confirmar que todo pasa**

Run: `cd app && mvn test`
Expected: PASS — `CompanyToolsTest` nuevo en verde, todo lo demás sin cambios (esta tarea no toca `ChatIntentRouter`).

- [ ] **Step 4: Commit**

```bash
cd app && git add src/main/java/com/aicompany/core/service/CompanyTools.java \
  src/test/java/com/aicompany/core/service/CompanyToolsTest.java
git commit -m "Agregar CompanyTools: consultas reales de la empresa, formateadas 100% en Java"
```

---

### Task 2: Wirear `CompanyTools` en `ChatIntentRouter` y borrar la lógica duplicada

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java`
- Modify: `app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java`

**Interfaces:**
- Consumes: los 11 métodos + `formatCandidate` de `CompanyTools` (Task 1).
- Produces: `ChatIntentRouter` con constructor de 7 parámetros (antes 8 — se eliminan `customerMemory`/`appProperties`, se agrega `companyTools`), usado por la Task 3.

Antes de empezar, releé el archivo actual completo (`app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java`) para confirmar que los números de línea de abajo coinciden — si no coinciden exactamente, ubicá cada bloque por su contenido (nombre de método/campo), no por el número de línea a ciegas.

- [ ] **Step 1: Actualizar imports**

Reemplazar el bloque de imports (líneas 1-23) por:

```java
package com.aicompany.core.service;

import com.aicompany.core.model.DecisionCommand;
import com.aicompany.core.model.InvestorDecision;
import com.aicompany.core.model.LeadResponse;
import com.aicompany.core.model.MissionResponse;
import com.aicompany.core.model.MissionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
```

(Se eliminan `AgentStatusResponse`, `AppProperties`, `MissionStatusResponse` y `OpportunitySummary` — ya no se usan directamente en este archivo, toda esa lógica vive ahora en `CompanyTools`.)

- [ ] **Step 2: Actualizar campos y constructor**

Reemplazar el bloque de campos + constructor (línea ~130 a ~157, desde `private final MissionService missionService;` hasta el cierre del constructor) por:

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

- [ ] **Step 3: Simplificar `handleMissionDetailsQuery`**

Reemplazar el método completo (~línea 386-402, incluyendo el javadoc que lo antecede) por:

```java
    private String handleMissionDetailsQuery(String missionId) {

        log.info(
                "CHAT_INTENT_MISSION_DETAILS missionId={}",
                missionId
        );

        return companyTools.getMission(missionId);
    }
```

Borrar por completo el método `formatMissionDetails` que le seguía (usaba `MissionStatusResponse` — ya migrado a `CompanyTools.getMission`).

- [ ] **Step 4: Simplificar `handleOpportunityForMissionQuery`**

Reemplazar el método completo (~línea 449-472) por:

```java
    private String handleOpportunityForMissionQuery(String missionId) {

        log.info("CHAT_INTENT_OPPORTUNITY_DETAILS missionId={}", missionId);

        return companyTools.getOpportunity(missionId);
    }
```

Borrar por completo el método `formatOpportunityWithCandidates` que le seguía.

- [ ] **Step 5: Reemplazar `formatCandidateCore` por una llamada a `companyTools.formatCandidate`**

Borrar el método `formatCandidateCore` completo (el bloque con el javadoc largo sobre "Fragmento de formato compartido...").

En `formatCustomerReferenceAnswer` (la lógica de "contactalo", que se queda en este archivo), reemplazar:

```java
        return intro + formatCandidateCore(candidate) + "." + contactNote + clarifyNote;
```

por:

```java
        return intro + companyTools.formatCandidate(candidate) + "." + contactNote + clarifyNote;
```

- [ ] **Step 6: Simplificar `answerMemoryTopic`**

Reemplazar el método completo (con su javadoc) por:

```java
    /**
     * Resuelve un {@code topic} real delegando a {@link CompanyTools} —
     * llamado tanto por el atajo de keywords ({@link #handleQuery}, sin
     * pasar por Ollama) como por la herramienta {@code query_company_memory}
     * que {@link CeoService#chat} puede pedir para el chat general.
     */
    String answerMemoryTopic(String topic) {

        return switch (topic) {
            case "AGENT_STATUS" -> companyTools.getAgentStatus();
            case "MISSIONS_NEEDING_ATTENTION" -> companyTools.getPendingApprovals();
            case "FAILED_MISSIONS" -> companyTools.getFailedMissions();
            case "TEST_MISSIONS" -> companyTools.getTestMissions();
            case "LAST_MENTIONED" -> companyTools.getLastMentioned();
            case "OPPORTUNITIES" -> companyTools.getOpportunities();
            case "LEADS" -> companyTools.getProspects();
            case "COMPANY_PROFIT" -> companyTools.getFinancialStatus();
            case "COMPANY_STATUS" -> companyTools.getCompanyStatus();
            default -> "Dato no reconocido: " + topic + ".";
        };
    }
```

- [ ] **Step 7: Borrar todos los formatters ya migrados a `CompanyTools`**

Borrar por completo, en cualquier orden, estos métodos y campos (todo su contenido ya vive en `CompanyTools`, migrado en la Task 1):
- `formatCompanyStatus` (con su javadoc largo sobre "dame un status")
- `formatAgentStatus`
- `formatOneAgentStatus`
- Los 3 campos estáticos `STATUS_GREEN`, `STATUS_RED`, `STATUS_YELLOW`
- `statusDot` (con su javadoc)
- `formatMissionsNeedingAttention` (con su javadoc)
- `formatFailedMissions`
- `formatTestMissions` (con su javadoc)
- `formatLastMentioned` (con su javadoc)
- `formatLastMentionedCustomers` (con su javadoc)
- `formatOpportunities`
- `formatLeads`
- `formatCompanyProfit`

- [ ] **Step 8: Actualizar `ChatIntentRouterTest` — solo el bloque de construcción**

Reemplazar las líneas 24-36 (desde `private final MissionService missionService` hasta el cierre de la construcción de `router`) por:

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
    private final CompanyTools companyTools = new CompanyTools(
            missionService, missionMemory, opportunityMemory, customerMemory, conversationMemory, appProperties
    );

    private final ChatIntentRouter router = new ChatIntentRouter(
            missionService, ceoService, missionMemory, opportunityMemory, companyMemory, conversationMemory, companyTools
    );
```

Nota clave para quien implemente esta tarea: **ningún otro método de este archivo de test debería necesitar cambios**. `companyTools` es una instancia REAL (no un mock) construida sobre los mismos mocks (`missionMemory`, `opportunityMemory`, `customerMemory`, `conversationMemory`, `missionService`) que ya usa el resto de la clase — cualquier `when(missionMemory. ...)`/`when(opportunityMemory. ...)`/`when(customerMemory. ...)`/`when(missionService. ...)` que ya exista en un test sigue funcionando exactamente igual, porque sigue siendo el mismo mock, solo que ahora lo lee `CompanyTools` en vez de `ChatIntentRouter` directamente. Si algún test falla después de este cambio, es señal de un error real en la migración (Steps 1-7), no algo que deba "arreglarse" reescribiendo el test.

- [ ] **Step 9: Correr toda la suite**

Run: `cd app && mvn test`
Expected: PASS — los ~44 tests de `ChatIntentRouterTest` deben seguir pasando sin haber tocado sus cuerpos, más los nuevos de `CompanyToolsTest` de la Task 1.

Si algo falla, no "arregles" el test cambiando su assertion — compará contra el comportamiento antes de esta tarea (`git diff` contra el commit de la Task 1) para encontrar qué parte de la migración (Steps 1-7) quedó incompleta o inconsistente.

- [ ] **Step 10: Commit**

```bash
cd app && git add src/main/java/com/aicompany/core/service/ChatIntentRouter.java \
  src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java
git commit -m "ChatIntentRouter delega en CompanyTools; borra la logica de formateo duplicada"
```

---

### Task 3: Parámetro `id` en `query_company_memory` — `MISSION_DETAILS` y `OPPORTUNITY_DETAILS`

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/CeoService.java`
- Modify: `app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java`
- Modify: `app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java`
- Create: `app/src/test/java/com/aicompany/core/service/CeoServiceCompanyMemoryTopicTest.java`

**Interfaces:**
- Consumes: `CompanyTools.getMission(String)`/`getOpportunity(String)` (Task 1).
- Produces: `CeoService.chat(..., BiFunction<String, String, String> companyMemoryQuery)` (antes `Function<String, String>`); `ChatIntentRouter.answerMemoryTopic(String topic, String id)` (antes solo `topic`); nuevo record package-private `CeoService.CompanyMemoryQuery(String topic, String id)`.

- [ ] **Step 1: Cambiar `parseCompanyMemoryTopic`/`detectInlineCompanyMemoryTopic` para extraer también `id`**

En `app/src/main/java/com/aicompany/core/service/CeoService.java`, ubicar el record `ToolCall` (justo antes de `parseCompanyMemoryTopic`) y agregar al lado:

```java
    record CompanyMemoryQuery(String topic, String id) {
    }
```

Reemplazar `parseCompanyMemoryTopic` completo (firma incluida — pasa de `private` a sin modificador, package-private, mismo patrón ya usado en el proyecto para testear sin reflection, ver `SerializerSearchAdapter.parseResults`) por:

```java
    @SuppressWarnings("unchecked")
    CompanyMemoryQuery parseCompanyMemoryTopic(Map<String, Object> rawToolCall) {

        var function = (Map<String, Object>) rawToolCall.get("function");

        if (function == null) {
            return null;
        }

        var name = String.valueOf(function.get("name"));
        var arguments = function.get("arguments");

        String topic = null;
        String id = null;

        if (arguments instanceof Map<?, ?> argMap) {
            var topicValue = argMap.get("topic");
            topic = topicValue == null ? null : String.valueOf(topicValue);
            var idValue = argMap.get("id");
            id = idValue == null ? null : String.valueOf(idValue);
        }

        if (!"query_company_memory".equals(name)
                || topic == null
                || topic.isBlank()) {
            return null;
        }

        return new CompanyMemoryQuery(topic, id);
    }
```

Reemplazar `detectInlineCompanyMemoryTopic` completo por:

```java
    CompanyMemoryQuery detectInlineCompanyMemoryTopic(String content) {

        if (content == null || content.isBlank()) {
            return null;
        }

        try {

            var normalized = normalizeJsonResponse(content);
            var node = jsonMapper.readTree(normalized);

            if (!node.isObject()) {
                return null;
            }

            var name = node.path("name").asString(null);
            var argumentsNode = node.path("arguments");

            if (!"query_company_memory".equals(name)
                    || !argumentsNode.isObject()) {
                return null;
            }

            var topic = argumentsNode.path("topic").asString(null);

            if (topic == null || topic.isBlank()) {
                return null;
            }

            var id = argumentsNode.path("id").asString(null);

            return new CompanyMemoryQuery(topic, id);

        } catch (Exception ex) {
            return null;
        }
    }
```

- [ ] **Step 2: Actualizar `chat()` para usar `BiFunction` y pasar el `id`**

En el mismo archivo, cambiar el import `java.util.function.Function` por `java.util.function.BiFunction`.

Reemplazar la firma de `chat(...)`:

```java
    public String chat(
            String ceoName,
            String teamRoster,
            List<ConversationTurn> history,
            String message,
            Function<String, String> companyMemoryQuery) {
```

por:

```java
    public String chat(
            String ceoName,
            String teamRoster,
            List<ConversationTurn> history,
            String message,
            BiFunction<String, String, String> companyMemoryQuery) {
```

Dentro del cuerpo de `chat()`, reemplazar:

```java
        var topic =
                !turn.toolCalls().isEmpty()
                        ? parseCompanyMemoryTopic(turn.toolCalls().get(0))
                        : detectInlineCompanyMemoryTopic(turn.content());

        if (topic == null) {
            return turn.content();
        }

        log.info("CEO_CHAT_TOOL_CALL topic={}", topic);

        var result = companyMemoryQuery.apply(topic);

        messages.add(Map.of(
                "role", "assistant",
                "content", "",
                "tool_calls", List.of(Map.of(
                        "function", Map.of(
                                "name", "query_company_memory",
                                "arguments", Map.of("topic", topic)
                        )
                ))
        ));
```

por:

```java
        var query =
                !turn.toolCalls().isEmpty()
                        ? parseCompanyMemoryTopic(turn.toolCalls().get(0))
                        : detectInlineCompanyMemoryTopic(turn.content());

        if (query == null) {
            return turn.content();
        }

        log.info("CEO_CHAT_TOOL_CALL topic={} id={}", query.topic(), query.id());

        var result = companyMemoryQuery.apply(query.topic(), query.id());

        var toolCallArguments = query.id() == null
                ? Map.of("topic", query.topic())
                : Map.of("topic", query.topic(), "id", query.id());

        messages.add(Map.of(
                "role", "assistant",
                "content", "",
                "tool_calls", List.of(Map.of(
                        "function", Map.of(
                                "name", "query_company_memory",
                                "arguments", toolCallArguments
                        )
                ))
        ));
```

- [ ] **Step 3: Extender el schema de la herramienta con `id` y los 2 topics nuevos**

En el mismo archivo, dentro de `COMPANY_MEMORY_TOOLS`, ubicar el bloque `"parameters", Map.of(...)` y reemplazarlo completo por:

```java
                            "parameters", Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "topic", Map.of(
                                                    "type", "string",
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
                                                    "description",
                                                    "AGENT_STATUS: qué está "
                                                            + "haciendo cada "
                                                            + "agente ahora. "
                                                            + "MISSIONS_NEEDING_ATTENTION: "
                                                            + "misiones reales "
                                                            + "(no de prueba) "
                                                            + "que esperan tu "
                                                            + "aprobación "
                                                            + "(AWAITING_INVESTOR "
                                                            + "exclusivamente, "
                                                            + "nunca fallidas). "
                                                            + "FAILED_MISSIONS: "
                                                            + "misiones reales "
                                                            + "que fallaron. "
                                                            + "TEST_MISSIONS: "
                                                            + "misiones de "
                                                            + "prueba/desarrollo "
                                                            + "(cualquier "
                                                            + "estado) -- nunca "
                                                            + "cuentan como "
                                                            + "actividad "
                                                            + "empresarial real. "
                                                            + "LAST_MENTIONED: "
                                                            + "detalle real de "
                                                            + "las últimas "
                                                            + "misiones o "
                                                            + "prospectos "
                                                            + "mencionados en "
                                                            + "esta "
                                                            + "conversación. "
                                                            + "OPPORTUNITIES: "
                                                            + "oportunidades "
                                                            + "identificadas "
                                                            + "(lista global, "
                                                            + "sin prospectos). "
                                                            + "LEADS: "
                                                            + "candidatos de "
                                                            + "cliente (LEAD) "
                                                            + "que un agente "
                                                            + "identificó y "
                                                            + "todavía no se "
                                                            + "contactaron ni "
                                                            + "convirtieron. "
                                                            + "COMPANY_PROFIT: "
                                                            + "ingresos/costos/"
                                                            + "utilidad reales "
                                                            + "acumulados. "
                                                            + "COMPANY_STATUS: "
                                                            + "resumen agregado "
                                                            + "y real de la "
                                                            + "empresa (capital, "
                                                            + "agentes, misiones, "
                                                            + "oportunidades, "
                                                            + "clientes, "
                                                            + "finanzas) -- "
                                                            + "usala siempre que "
                                                            + "te pidan un "
                                                            + "'status' o "
                                                            + "resumen general, "
                                                            + "nunca inventes "
                                                            + "ese resumen vos. "
                                                            + "MISSION_DETAILS: "
                                                            + "estado, progreso "
                                                            + "y tareas reales "
                                                            + "de UNA misión "
                                                            + "puntual -- "
                                                            + "requiere el "
                                                            + "parámetro id "
                                                            + "con el "
                                                            + "MISSION-<numero> "
                                                            + "exacto. "
                                                            + "OPPORTUNITY_DETAILS: "
                                                            + "la oportunidad "
                                                            + "real y sus "
                                                            + "prospectos "
                                                            + "reales de UNA "
                                                            + "misión puntual "
                                                            + "-- requiere el "
                                                            + "parámetro id "
                                                            + "con el "
                                                            + "MISSION-<numero> "
                                                            + "exacto (el id "
                                                            + "de la misión, "
                                                            + "nunca el id "
                                                            + "interno de la "
                                                            + "Opportunity)."
                                            ),
                                            "id", Map.of(
                                                    "type", "string",
                                                    "description",
                                                    "El MISSION-<numero> "
                                                            + "exacto -- solo "
                                                            + "necesario para "
                                                            + "MISSION_DETAILS "
                                                            + "y "
                                                            + "OPPORTUNITY_DETAILS. "
                                                            + "Omitilo para "
                                                            + "cualquier otro "
                                                            + "topic."
                                            )
                                    ),
                                    "required", List.of("topic")
                            )
```

- [ ] **Step 4: Extender `ChatIntentRouter.answerMemoryTopic` con `id` y los 2 topics nuevos**

En `app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java`, reemplazar `answerMemoryTopic` (de la Task 2) por:

```java
    String answerMemoryTopic(String topic, String id) {

        return switch (topic) {
            case "AGENT_STATUS" -> companyTools.getAgentStatus();
            case "MISSIONS_NEEDING_ATTENTION" -> companyTools.getPendingApprovals();
            case "FAILED_MISSIONS" -> companyTools.getFailedMissions();
            case "TEST_MISSIONS" -> companyTools.getTestMissions();
            case "LAST_MENTIONED" -> companyTools.getLastMentioned();
            case "OPPORTUNITIES" -> companyTools.getOpportunities();
            case "LEADS" -> companyTools.getProspects();
            case "COMPANY_PROFIT" -> companyTools.getFinancialStatus();
            case "COMPANY_STATUS" -> companyTools.getCompanyStatus();
            case "MISSION_DETAILS" -> companyTools.getMission(id);
            case "OPPORTUNITY_DETAILS" -> companyTools.getOpportunity(id);
            default -> "Dato no reconocido: " + topic + ".";
        };
    }
```

Actualizar `handleQuery` (el atajo de keywords, que nunca necesita `id`):

```java
    private String handleQuery(QueryIntent intent) {

        log.info("CHAT_INTENT_QUERY intent={}", intent);

        return answerMemoryTopic(intent.name(), null);
    }
```

Los dos usos de `this::answerMemoryTopic` como argumento de `ceoService.chat(...)` (uno en `resolve()`, otro en `handleReference`) no necesitan ningún cambio de sintaxis: al pasar de `Function<String,String>` a `BiFunction<String,String,String>` en la firma de `chat`, la misma referencia de método `this::answerMemoryTopic` se resuelve contra la nueva forma automáticamente porque ahora tiene 2 parámetros String.

- [ ] **Step 5: Actualizar `passesCompanyMemoryQueryCallbackThatResolvesAllKnownTopics` en `ChatIntentRouterTest`**

Reemplazar el test completo por:

```java
    @SuppressWarnings("unchecked")
    @Test
    void passesCompanyMemoryQueryCallbackThatResolvesAllKnownTopics() {
        when(companyMemory.agentName("ceo")).thenReturn(Optional.of("Alex"));
        when(companyMemory.teamRosterDescription()).thenReturn("- Sofia (Sales)");

        when(opportunityMemory.countOpportunities()).thenReturn(0L);
        when(customerMemory.countCustomersAndProspects()).thenReturn(new long[]{0L, 0L});

        var statuses = List.of(
                new AgentStatusResponse("sales", "Sofia", "Director of Sales AI", "persuasiva, orientada a resultados", "WORKING", "MISSION-1", "MARKET_DISCOVERY", "RUNNING", Instant.now())
        );
        when(missionMemory.latestTaskPerAgent()).thenReturn(statuses);
        when(missionMemory.findAll(50)).thenReturn(List.of());
        when(opportunityMemory.listRecent(20)).thenReturn(List.of());
        when(opportunityMemory.listLeads()).thenReturn(List.of());
        when(customerMemory.companyWideTotalRevenueAndCost()).thenReturn(new double[]{100.0, 40.0});
        when(conversationMemory.lastMentioned()).thenReturn(Optional.empty());

        var mission = new MissionResponse(
                "MISSION-1", MissionStatus.AWAITING_INVESTOR, "PRODUCTION", 95, "x", "y", Instant.now()
        );
        when(missionService.details("MISSION-1")).thenReturn(
                Optional.of(new com.aicompany.core.model.MissionStatusResponse(mission, List.of()))
        );
        when(opportunityMemory.findByMissionId("MISSION-1")).thenReturn(
                Optional.of(new OpportunitySummary("MISSION-1-OPPORTUNITY", "MISSION-1", "desc", "IDENTIFIED", Instant.now()))
        );
        when(opportunityMemory.listCandidatesForMission("MISSION-1")).thenReturn(List.of());

        router.route("Hola, ¿cómo estás?");

        var captor = org.mockito.ArgumentCaptor.forClass(java.util.function.BiFunction.class);
        verify(ceoService).chat(anyString(), anyString(), any(), anyString(), captor.capture());
        var companyMemoryQuery = (java.util.function.BiFunction<String, String, String>) captor.getValue();

        assertTrue(companyMemoryQuery.apply("AGENT_STATUS", null).contains("Sofia"));
        assertTrue(companyMemoryQuery.apply("MISSIONS_NEEDING_ATTENTION", null).contains("No hay ninguna misión"));
        assertTrue(companyMemoryQuery.apply("FAILED_MISSIONS", null).contains("No hay ninguna misión"));
        assertTrue(companyMemoryQuery.apply("TEST_MISSIONS", null).contains("No hay ninguna misión"));
        assertTrue(companyMemoryQuery.apply("LAST_MENTIONED", null).contains("No hay ninguna mención reciente"));
        assertTrue(companyMemoryQuery.apply("OPPORTUNITIES", null).contains("Todavía no hay ninguna oportunidad"));
        assertTrue(companyMemoryQuery.apply("LEADS", null).contains("No hay ningún lead"));
        assertTrue(companyMemoryQuery.apply("COMPANY_PROFIT", null).contains("60.00"));
        assertTrue(companyMemoryQuery.apply("COMPANY_STATUS", null).contains("Estado actual de Forjai"));
        assertTrue(companyMemoryQuery.apply("MISSION_DETAILS", "MISSION-1").contains("AWAITING_INVESTOR"));
        assertTrue(companyMemoryQuery.apply("OPPORTUNITY_DETAILS", "MISSION-1").contains("Todavía no hay ningún prospecto"));
        assertTrue(companyMemoryQuery.apply("ALGO_INEXISTENTE", null).contains("Dato no reconocido"));
    }
```

- [ ] **Step 6: Crear `CeoServiceCompanyMemoryTopicTest.java`**

Mismo patrón de testabilidad ya usado en el proyecto (métodos package-private probados directo, sin mockear `RestClient` — ver `CeoServiceChatHistoryTest`).

```java
package com.aicompany.core.service;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.evidence.EvidenceAcquisitionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class CeoServiceCompanyMemoryTopicTest {

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
    void parseCompanyMemoryTopicExtractsTopicAndId() {
        var rawToolCall = Map.<String, Object>of(
                "function", Map.of(
                        "name", "query_company_memory",
                        "arguments", Map.of("topic", "MISSION_DETAILS", "id", "MISSION-1")
                )
        );

        var query = ceoService.parseCompanyMemoryTopic(rawToolCall);

        assertEquals("MISSION_DETAILS", query.topic());
        assertEquals("MISSION-1", query.id());
    }

    @Test
    void parseCompanyMemoryTopicReturnsNullIdWhenAbsent() {
        var rawToolCall = Map.<String, Object>of(
                "function", Map.of(
                        "name", "query_company_memory",
                        "arguments", Map.of("topic", "COMPANY_STATUS")
                )
        );

        var query = ceoService.parseCompanyMemoryTopic(rawToolCall);

        assertEquals("COMPANY_STATUS", query.topic());
        assertNull(query.id());
    }

    @Test
    void parseCompanyMemoryTopicReturnsNullForOtherTools() {
        var rawToolCall = Map.<String, Object>of(
                "function", Map.of(
                        "name", "search_web_evidence",
                        "arguments", Map.of("query", "algo")
                )
        );

        var query = ceoService.parseCompanyMemoryTopic(rawToolCall);

        assertNull(query);
    }

    @Test
    void detectInlineCompanyMemoryTopicExtractsTopicAndId() {
        var content = "{\"name\": \"query_company_memory\", "
                + "\"arguments\": {\"topic\": \"OPPORTUNITY_DETAILS\", \"id\": \"MISSION-7\"}}";

        var query = ceoService.detectInlineCompanyMemoryTopic(content);

        assertEquals("OPPORTUNITY_DETAILS", query.topic());
        assertEquals("MISSION-7", query.id());
    }

    @Test
    void detectInlineCompanyMemoryTopicReturnsNullIdWhenAbsent() {
        var content = "{\"name\": \"query_company_memory\", "
                + "\"arguments\": {\"topic\": \"AGENT_STATUS\"}}";

        var query = ceoService.detectInlineCompanyMemoryTopic(content);

        assertEquals("AGENT_STATUS", query.topic());
        assertNull(query.id());
    }
}
```

- [ ] **Step 7: Correr toda la suite**

Run: `cd app && mvn test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
cd app && git add src/main/java/com/aicompany/core/service/CeoService.java \
  src/main/java/com/aicompany/core/service/ChatIntentRouter.java \
  src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java \
  src/test/java/com/aicompany/core/service/CeoServiceCompanyMemoryTopicTest.java
git commit -m "query_company_memory gana parametro id: MISSION_DETAILS y OPPORTUNITY_DETAILS para el chat general"
```

---

## Verificación en vivo (después de las 3 tareas, fuera del ciclo TDD)

1. Rebuild y redeploy del contenedor `company-core` con esta rama.
2. Correr una misión real (o reusar una con datos ya existentes).
3. Chat: una pregunta que NO matchee ningún keyword determinista pero mencione una misión puntual de forma indirecta (p. ej. una frase larga que no contenga "cómo va"/"avance"/"detalles" pero sí un `MISSION-<id>`), y confirmar en el log `CEO_CHAT_TOOL_CALL topic=MISSION_DETAILS id=...` con el dato real, no alucinado.
4. Repetir para `OPPORTUNITY_DETAILS`.
5. Confirmar que las consultas ya existentes (status, agentes, oportunidades sin id, leads, profit) siguen funcionando exactamente igual que antes de esta ronda.
