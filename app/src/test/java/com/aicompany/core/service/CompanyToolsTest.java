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
    private final ActivityMemoryService activityMemory = mock(ActivityMemoryService.class);
    private final AppProperties appProperties = new AppProperties("Forjai", 50.0, 60);

    private final CompanyTools tools = new CompanyTools(
            missionService, missionMemory, opportunityMemory, customerMemory, conversationMemory, activityMemory, appProperties
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
    void getMissionNormalizesCasingAndWhitespaceBeforeQuerying() {
        // El camino de la herramienta query_company_memory pasa el id del
        // modelo sin normalizar (a diferencia del atajo de keywords, que
        // ya uppercasea antes de llamar acá) -- sin esto, "mission-x"
        // (minúsculas) o " MISSION-X" (espacio) reportarían "no encontré"
        // aunque la misión real exista con el id en mayúsculas.
        var mission = new MissionResponse(
                "MISSION-1789701859658", MissionStatus.WAITING_AGENT_RESULTS, "PRODUCTION",
                35, "Esperando resultados", "Los agentes están trabajando en paralelo.", Instant.now()
        );
        when(missionService.details("MISSION-1789701859658"))
                .thenReturn(Optional.of(new MissionStatusResponse(mission, List.of())));

        var response = tools.getMission(" mission-1789701859658 ".trim().toLowerCase());

        assertFalse(response.contains("No encontré"));
        assertTrue(response.contains("MISSION-1789701859658"));
    }

    @Test
    void getOpportunityNormalizesCasingAndWhitespaceBeforeQuerying() {
        when(opportunityMemory.findByMissionId("MISSION-1")).thenReturn(Optional.of(
                new OpportunitySummary("MISSION-1-OPPORTUNITY", "MISSION-1", "asesoría a microempresas", "IDENTIFIED", Instant.now())
        ));
        when(opportunityMemory.listCandidatesForMission("MISSION-1")).thenReturn(List.of());

        var response = tools.getOpportunity("mission-1");

        assertFalse(response.contains("No encontré"));
        assertTrue(response.contains("asesoría a microempresas"));
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
        var decidedAt = Instant.parse("2026-09-18T12:00:00Z");
        when(missionMemory.recentDecisions(10)).thenReturn(List.of(
                new com.aicompany.core.model.DecisionActivity(
                        "MISSION-1-DECISION-1", "MISSION-1", "APPROVE", "Se ve bien", decidedAt
                )
        ));

        var response = tools.getRecentDecisions();

        assertTrue(response.contains("MISSION-1"));
        assertTrue(response.contains("APPROVE"));
        assertTrue(response.contains("Se ve bien"));
        assertTrue(response.contains(decidedAt.toString()));
    }

    @Test
    void getRecentDecisionsTruncatesLongReasoning() {
        // El reasoning de una Decision puede ser una instrucción de
        // misión completa en texto libre (~500 caracteres) -- diez de
        // esas juntas producirían una sola línea de chat gigantesca, y
        // por el camino del tool-call de la herramienta, todo eso
        // entraría al prompt del CEO.
        var longReasoning = "x".repeat(150);
        when(missionMemory.recentDecisions(10)).thenReturn(List.of(
                new com.aicompany.core.model.DecisionActivity(
                        "MISSION-1-DECISION-1", "MISSION-1", "APPROVE", longReasoning, Instant.now()
                )
        ));

        var response = tools.getRecentDecisions();

        assertFalse(response.contains(longReasoning));
        assertTrue(response.contains("x".repeat(100) + "..."));
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
}
