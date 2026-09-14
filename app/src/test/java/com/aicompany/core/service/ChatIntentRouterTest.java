package com.aicompany.core.service;

import com.aicompany.core.model.AgentStatusResponse;
import com.aicompany.core.model.DecisionCommand;
import com.aicompany.core.model.DecisionResponse;
import com.aicompany.core.model.InvestorDecision;
import com.aicompany.core.model.MissionResponse;
import com.aicompany.core.model.MissionStatus;
import com.aicompany.core.model.OpportunitySummary;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatIntentRouterTest {

    private final MissionService missionService = mock(MissionService.class);
    private final CeoService ceoService = mock(CeoService.class);
    private final MissionMemoryService missionMemory = mock(MissionMemoryService.class);
    private final OpportunityMemoryService opportunityMemory = mock(OpportunityMemoryService.class);
    private final CustomerMemoryService customerMemory = mock(CustomerMemoryService.class);
    private final CompanyMemoryService companyMemory = mock(CompanyMemoryService.class);

    private final ChatIntentRouter router = new ChatIntentRouter(
            missionService, ceoService, missionMemory, opportunityMemory, customerMemory, companyMemory
    );

    @Test
    void routesMissionStartToMissionService() {
        var mission = new MissionResponse(
                "MISSION-42", MissionStatus.CREATED, 0, "Creada", "Misión recibida",
                Instant.parse("2026-09-12T00:00:00Z")
        );
        when(missionService.start("MISSION-42", "Inicia mission-42 para investigar."))
                .thenReturn(mission);

        var response = router.route("Inicia mission-42 para investigar.");

        assertTrue(response.contains("MISSION-42"));
        verify(missionService).start("MISSION-42", "Inicia mission-42 para investigar.");
        verifyNoInteractions(ceoService);
    }

    @Test
    void routesApproveWithExplicitMissionIdToRecordDecision() {
        var decisionResponse = new DecisionResponse(
                "MISSION-7-DECISION-1", "MISSION-7", InvestorDecision.APPROVE, Instant.now()
        );
        when(missionService.recordDecision(eq("MISSION-7"), any(DecisionCommand.class)))
                .thenReturn(Optional.of(decisionResponse));

        var response = router.route("Aprueba la misión MISSION-7, se ve bien.");

        assertTrue(response.contains("APPROVE"));
        assertTrue(response.contains("MISSION-7"));

        var captor = org.mockito.ArgumentCaptor.forClass(DecisionCommand.class);
        verify(missionService).recordDecision(eq("MISSION-7"), captor.capture());
        assertEquals(InvestorDecision.APPROVE, captor.getValue().decision());
        assertEquals("Aprueba la misión MISSION-7, se ve bien.", captor.getValue().reasoning());

        verifyNoInteractions(ceoService);
    }

    @Test
    void routesRejectWithExplicitMissionIdToRecordDecision() {
        when(missionService.recordDecision(eq("MISSION-9"), any(DecisionCommand.class)))
                .thenReturn(Optional.of(new DecisionResponse(
                        "MISSION-9-DECISION-1", "MISSION-9", InvestorDecision.REJECT, Instant.now()
                )));

        router.route("Rechaza la misión MISSION-9 por favor.");

        var captor = org.mockito.ArgumentCaptor.forClass(DecisionCommand.class);
        verify(missionService).recordDecision(eq("MISSION-9"), captor.capture());
        assertEquals(InvestorDecision.REJECT, captor.getValue().decision());
    }

    @Test
    void routesRequestMoreEvidenceWithExplicitMissionIdToRecordDecision() {
        when(missionService.recordDecision(eq("MISSION-3"), any(DecisionCommand.class)))
                .thenReturn(Optional.of(new DecisionResponse(
                        "MISSION-3-DECISION-1", "MISSION-3", InvestorDecision.REQUEST_MORE_EVIDENCE, Instant.now()
                )));

        router.route("Para MISSION-3 pide más evidencia antes de decidir.");

        var captor = org.mockito.ArgumentCaptor.forClass(DecisionCommand.class);
        verify(missionService).recordDecision(eq("MISSION-3"), captor.capture());
        assertEquals(InvestorDecision.REQUEST_MORE_EVIDENCE, captor.getValue().decision());
    }

    @Test
    void doesNotRouteToDecisionWhenMessageHasNoExplicitMissionId() {
        when(companyMemory.agentName("ceo")).thenReturn(Optional.of("Alex"));
        when(companyMemory.teamRosterDescription()).thenReturn("- Sofia (Sales)");
        when(ceoService.chat(anyString(), anyString(), anyString(), any())).thenReturn("¿A qué misión te referís?");

        var response = router.route("Aprueba la misión de la que hablamos ayer.");

        assertEquals("¿A qué misión te referís?", response);
        verify(missionService, never()).recordDecision(anyString(), any());
    }

    @Test
    void returnsNotFoundMessageWhenDecisionMissionDoesNotExist() {
        when(missionService.recordDecision(eq("MISSION-404"), any(DecisionCommand.class)))
                .thenReturn(Optional.empty());

        var response = router.route("Aprueba la misión MISSION-404.");

        assertTrue(response.contains("No encontré"));
    }

    @Test
    void routesAgentStatusQueryWithDeterministicFormatting() {
        // El router NUNCA le pide al modelo que enumere/cuente listas --
        // se reprodujo en vivo que con listas largas el modelo subcuenta
        // (dijo "9" en vez de 25 misiones reales). La respuesta se arma
        // 100% en Java a partir de los datos reales.
        var statuses = List.of(
                new AgentStatusResponse("sales", "Sofia", "Director of Sales AI", "persuasiva, orientada a resultados", "WORKING", "MISSION-1", "MARKET_DISCOVERY", Instant.now()),
                new AgentStatusResponse("ceo", "Alex", "Chief Executive Officer AI", "estratégico, crítico", "IDLE", null, null, null)
        );
        when(missionMemory.latestTaskPerAgent()).thenReturn(statuses);

        var response = router.route("¿Qué agentes están trabajando ahora?");

        assertTrue(response.contains("🟢 Sofia (Director of Sales AI): WORKING (MISSION-1, MARKET_DISCOVERY)"));
        assertTrue(response.contains("⚪ Alex (Chief Executive Officer AI): IDLE"));
        verifyNoInteractions(ceoService);
    }

    @Test
    void routesTeamPresentationQueryToTheSameDeterministicAgentStatus() {
        // "preséntame al equipo" no debe caer al chat general -- ahí el
        // CEO (LLM) elaboraba por encima del roster real inyectado e
        // incluso agregaba un disclaimer de privacidad contradictorio,
        // reproducido en vivo por el usuario. Debe resolverse 100% desde
        // Neo4j, igual que "¿qué agentes están trabajando?".
        var statuses = List.of(
                new AgentStatusResponse("sales", "Sofia", "Director of Sales AI", "persuasiva, orientada a resultados", "WORKING", "MISSION-1", "MARKET_DISCOVERY", Instant.now())
        );
        when(missionMemory.latestTaskPerAgent()).thenReturn(statuses);

        var response = router.route("preséntame al equipo");

        assertTrue(response.contains("Sofia"));
        verifyNoInteractions(ceoService);
    }

    @Test
    void routesWhoIsWorkingNowToAgentStatusEvenWithoutTheWordAgente() {
        var statuses = List.of(
                new AgentStatusResponse("sales", "Sofia", "Director of Sales AI", "persuasiva, orientada a resultados", "WORKING", "MISSION-1", "MARKET_DISCOVERY", Instant.now())
        );
        when(missionMemory.latestTaskPerAgent()).thenReturn(statuses);

        var response = router.route("¿Quién está trabajando ahora?");

        assertTrue(response.contains("Sofia"));
        verifyNoInteractions(ceoService);
    }

    @Test
    void routesWhatIsEachAgentDoingToAgentStatusEvenWithoutTrabajOrEstado() {
        var statuses = List.of(
                new AgentStatusResponse("sales", "Sofia", "Director of Sales AI", "persuasiva, orientada a resultados", "WORKING", "MISSION-1", "MARKET_DISCOVERY", Instant.now())
        );
        when(missionMemory.latestTaskPerAgent()).thenReturn(statuses);

        var response = router.route("¿Qué está haciendo cada agente?");

        assertTrue(response.contains("Sofia"));
        verifyNoInteractions(ceoService);
    }

    @Test
    void routesMissionsNeedingAttentionQueryFilteredByStatusWithDeterministicCount() {
        var awaiting = new MissionResponse("MISSION-1", MissionStatus.AWAITING_INVESTOR, 95, "x", "y", Instant.now());
        var running = new MissionResponse("MISSION-2", MissionStatus.WAITING_AGENT_RESULTS, 30, "x", "y", Instant.now());
        var failed = new MissionResponse("MISSION-3", MissionStatus.FAILED, 100, "x", "y", Instant.now());
        when(missionMemory.findAll(50)).thenReturn(List.of(awaiting, running, failed));

        var response = router.route("¿Qué misión necesita mi aprobación?");

        assertTrue(response.contains("2 misión"));
        assertTrue(response.contains("MISSION-1"));
        assertTrue(response.contains("MISSION-3"));
        assertFalse(response.contains("MISSION-2"));
        verifyNoInteractions(ceoService);
    }

    @Test
    void routesOpportunitiesQueryWithDeterministicFormatting() {
        when(opportunityMemory.listRecent(20)).thenReturn(List.of(
                new OpportunitySummary("MISSION-1-OPPORTUNITY", "MISSION-1", "asesoría a microempresas", "IDENTIFIED", Instant.now())
        ));

        var response = router.route("¿Qué oportunidades tenemos?");

        assertTrue(response.contains("1 oportunidad"));
        assertTrue(response.contains("asesoría a microempresas"));
        verifyNoInteractions(ceoService);
    }

    @Test
    void routesCompanyProfitQueryWithDeterministicAggregation() {
        when(customerMemory.companyWideTotalRevenueAndCost()).thenReturn(new double[]{120.0, 40.0});

        var response = router.route("¿Cuánto dinero hemos ganado hasta ahora?");

        assertTrue(response.contains("120.00"));
        assertTrue(response.contains("40.00"));
        assertTrue(response.contains("80.00"));
        verifyNoInteractions(ceoService);
    }

    @Test
    void fallsBackToGeneralChatWhenNoIntentMatches() {
        when(companyMemory.agentName("ceo")).thenReturn(Optional.of("Alex"));
        when(companyMemory.teamRosterDescription()).thenReturn("- Sofia (Sales)");
        when(ceoService.chat(eq("Alex"), eq("- Sofia (Sales)"), eq("Hola, ¿cómo estás?"), any()))
                .thenReturn("Todo bien, gracias.");

        var response = router.route("Hola, ¿cómo estás?");

        assertEquals("Todo bien, gracias.", response);
        verifyNoInteractions(missionService);
    }

    @SuppressWarnings("unchecked")
    @Test
    void passesCompanyMemoryQueryCallbackThatResolvesAllKnownTopics() {
        when(companyMemory.agentName("ceo")).thenReturn(Optional.of("Alex"));
        when(companyMemory.teamRosterDescription()).thenReturn("- Sofia (Sales)");

        var statuses = List.of(
                new AgentStatusResponse("sales", "Sofia", "Director of Sales AI", "persuasiva, orientada a resultados", "WORKING", "MISSION-1", "MARKET_DISCOVERY", Instant.now())
        );
        when(missionMemory.latestTaskPerAgent()).thenReturn(statuses);
        when(missionMemory.findAll(50)).thenReturn(List.of());
        when(opportunityMemory.listRecent(20)).thenReturn(List.of());
        when(customerMemory.companyWideTotalRevenueAndCost()).thenReturn(new double[]{100.0, 40.0});

        router.route("Hola, ¿cómo estás?");

        var captor = org.mockito.ArgumentCaptor.forClass(java.util.function.Function.class);
        verify(ceoService).chat(anyString(), anyString(), anyString(), captor.capture());
        var companyMemoryQuery = (java.util.function.Function<String, String>) captor.getValue();

        assertTrue(companyMemoryQuery.apply("AGENT_STATUS").contains("Sofia"));
        assertTrue(companyMemoryQuery.apply("MISSIONS_NEEDING_ATTENTION").contains("No hay ninguna misión"));
        assertTrue(companyMemoryQuery.apply("OPPORTUNITIES").contains("Todavía no hay ninguna oportunidad"));
        assertTrue(companyMemoryQuery.apply("COMPANY_PROFIT").contains("60.00"));
        assertTrue(companyMemoryQuery.apply("ALGO_INEXISTENTE").contains("Dato no reconocido"));
    }
}
