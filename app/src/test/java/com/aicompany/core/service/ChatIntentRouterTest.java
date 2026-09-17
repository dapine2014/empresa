package com.aicompany.core.service;

import com.aicompany.core.model.AgentStatusResponse;
import com.aicompany.core.model.DecisionCommand;
import com.aicompany.core.model.DecisionResponse;
import com.aicompany.core.model.InvestorDecision;
import com.aicompany.core.model.LastMentioned;
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
    private final ConversationMemoryService conversationMemory = mock(ConversationMemoryService.class);
    private final com.aicompany.core.config.AppProperties appProperties =
            new com.aicompany.core.config.AppProperties("Forjai", 50.0, 60, 2);

    private final ChatIntentRouter router = new ChatIntentRouter(
            missionService, ceoService, missionMemory, opportunityMemory, customerMemory, companyMemory, conversationMemory, appProperties
    );

    @Test
    void routesMissionStartToMissionService() {
        var mission = new MissionResponse(
                "MISSION-42", MissionStatus.CREATED, "PRODUCTION", 0, "Creada", "Misión recibida",
                Instant.parse("2026-09-12T00:00:00Z")
        );
        when(missionService.start("MISSION-42", "Inicia mission-42 para investigar.", "PRODUCTION"))
                .thenReturn(mission);

        var response = router.route("Inicia mission-42 para investigar.");

        assertTrue(response.contains("MISSION-42"));
        verify(missionService).start("MISSION-42", "Inicia mission-42 para investigar.", "PRODUCTION");
        verifyNoInteractions(ceoService);
    }

    @Test
    void routesFreeFormMissionDescriptionToMissionServiceWithAGeneratedId() {
        // Reportado por el usuario en vivo: una instrucción libre real
        // ("inicia una misión para...") contenía la frase "sin mi
        // aprobación" como restricción -- el router determinista la
        // interpretaba como una CONSULTA (MISSIONS_NEEDING_ATTENTION,
        // por el keyword "aprobacion") en vez de crear la misión. No
        // hay ningún MISSION-<número> explícito, así que el patrón
        // numerado (MISSION_START) tampoco la reconocía.
        var instruction = "Inicia una misión para encontrar una oportunidad comercial real en "
                + "videojuegos. No gastes dinero sin mi aprobación.";

        var mission = new MissionResponse(
                "MISSION-1789412392452", MissionStatus.CREATED, "PRODUCTION", 0, "Creada", "Misión recibida",
                Instant.now()
        );
        when(missionService.start(startsWith("MISSION-"), eq(instruction), eq("PRODUCTION")))
                .thenReturn(mission);

        var response = router.route(instruction);

        assertTrue(response.contains("MISSION-"));
        verify(missionService).start(startsWith("MISSION-"), eq(instruction), eq("PRODUCTION"));
        verify(conversationMemory).setLastMentioned(eq("MISSION"), argThat(ids -> ids.size() == 1));
        verifyNoInteractions(ceoService);
        verifyNoInteractions(missionMemory);
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
        when(ceoService.chat(anyString(), anyString(), any(), anyString(), any())).thenReturn("¿A qué misión te referís?");

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
                new AgentStatusResponse("sales", "Sofia", "Director of Sales AI", "persuasiva, orientada a resultados", "WORKING", "MISSION-1", "MARKET_DISCOVERY", "RUNNING", Instant.now()),
                new AgentStatusResponse("ceo", "Alex", "Chief Executive Officer AI", "estratégico, crítico", "IDLE", null, null, null, null)
        );
        when(missionMemory.latestTaskPerAgent()).thenReturn(statuses);

        var response = router.route("¿Qué agentes están trabajando ahora?");

        assertTrue(response.contains("🟢 Sofia (Director of Sales AI): WORKING (MISSION-1, MARKET_DISCOVERY)"));
        assertTrue(response.contains("⚪ Alex (Chief Executive Officer AI): IDLE"));
        verifyNoInteractions(ceoService);
    }

    @Test
    void showsLastTaskInfoWhenAgentIsIdleButHasTaskHistory() {
        // Agent.status (WORKING/IDLE, propiedad real del nodo Agent) ya
        // no se confunde con el status de su última AgentTask -- un
        // agente que terminó su última tarea vuelve a IDLE, pero seguimos
        // pudiendo mostrar qué hizo y cómo terminó (ambos datos reales,
        // no inventados). Reportado por el usuario: antes se mostraba
        // "COMPLETED" como si fuera el estado del agente.
        var statuses = List.of(
                new AgentStatusResponse("engineering", "Neo", "Chief Engineering AI", "pragmático, meticuloso",
                        "IDLE", "MISSION-1", "DELIVERY_FEASIBILITY", "COMPLETED", Instant.now())
        );
        when(missionMemory.latestTaskPerAgent()).thenReturn(statuses);

        var response = router.route("¿qué agentes están trabajando?");

        assertTrue(response.contains(
                "⚪ Neo (Chief Engineering AI): IDLE — última tarea: DELIVERY_FEASIBILITY (MISSION-1), resultado: COMPLETED"));
    }

    @Test
    void routesTeamPresentationQueryToTheSameDeterministicAgentStatus() {
        // "preséntame al equipo" no debe caer al chat general -- ahí el
        // CEO (LLM) elaboraba por encima del roster real inyectado e
        // incluso agregaba un disclaimer de privacidad contradictorio,
        // reproducido en vivo por el usuario. Debe resolverse 100% desde
        // Neo4j, igual que "¿qué agentes están trabajando?".
        var statuses = List.of(
                new AgentStatusResponse("sales", "Sofia", "Director of Sales AI", "persuasiva, orientada a resultados", "WORKING", "MISSION-1", "MARKET_DISCOVERY", "RUNNING", Instant.now())
        );
        when(missionMemory.latestTaskPerAgent()).thenReturn(statuses);

        var response = router.route("preséntame al equipo");

        assertTrue(response.contains("Sofia"));
        verifyNoInteractions(ceoService);
    }

    @Test
    void routesWhoIsWorkingNowToAgentStatusEvenWithoutTheWordAgente() {
        var statuses = List.of(
                new AgentStatusResponse("sales", "Sofia", "Director of Sales AI", "persuasiva, orientada a resultados", "WORKING", "MISSION-1", "MARKET_DISCOVERY", "RUNNING", Instant.now())
        );
        when(missionMemory.latestTaskPerAgent()).thenReturn(statuses);

        var response = router.route("¿Quién está trabajando ahora?");

        assertTrue(response.contains("Sofia"));
        verifyNoInteractions(ceoService);
    }

    @Test
    void routesWhatIsEachAgentDoingToAgentStatusEvenWithoutTrabajOrEstado() {
        var statuses = List.of(
                new AgentStatusResponse("sales", "Sofia", "Director of Sales AI", "persuasiva, orientada a resultados", "WORKING", "MISSION-1", "MARKET_DISCOVERY", "RUNNING", Instant.now())
        );
        when(missionMemory.latestTaskPerAgent()).thenReturn(statuses);

        var response = router.route("¿Qué está haciendo cada agente?");

        assertTrue(response.contains("Sofia"));
        verifyNoInteractions(ceoService);
    }

    @Test
    void routesMissionsNeedingAttentionQueryFilteredByStatusWithDeterministicCount() {
        // Deliberadamente estricto: solo AWAITING_INVESTOR -- una misión
        // FAILED no "necesita aprobación" (mezclarlas bajo esa etiqueta
        // fue un bug real reportado por el usuario). Las FAILED tienen su
        // propia consulta, ver routesFailedMissionsQuery... abajo.
        var awaiting = new MissionResponse("MISSION-1", MissionStatus.AWAITING_INVESTOR, "PRODUCTION", 95, "x", "y", Instant.now());
        var running = new MissionResponse("MISSION-2", MissionStatus.WAITING_AGENT_RESULTS, "PRODUCTION", 30, "x", "y", Instant.now());
        var failed = new MissionResponse("MISSION-3", MissionStatus.FAILED, "PRODUCTION", 100, "x", "y", Instant.now());
        when(missionMemory.findAll(50)).thenReturn(List.of(awaiting, running, failed));

        var response = router.route("¿Qué misión necesita mi aprobación?");

        assertTrue(response.contains("1 misión"));
        assertTrue(response.contains("MISSION-1"));
        assertFalse(response.contains("MISSION-2"));
        assertFalse(response.contains("MISSION-3"));
        verifyNoInteractions(ceoService);
    }

    @Test
    void excludesTestEnvironmentMissionsFromApprovalsAndFailuresByDefault() {
        // Reportado por el usuario: 25 misiones reales acumuladas de
        // sesiones de desarrollo contaminaban las respuestas de negocio.
        // Toda consulta empresarial filtra PRODUCTION por default.
        var realApproval = new MissionResponse("MISSION-001", MissionStatus.AWAITING_INVESTOR, "PRODUCTION", 95, "x", "y", Instant.now());
        var testApproval = new MissionResponse("MISSION-DEBUG-007", MissionStatus.AWAITING_INVESTOR, "TEST", 95, "x", "y", Instant.now());
        var testFailure = new MissionResponse("MISSION-STRUCTURED-001", MissionStatus.FAILED, "TEST", 100, "x", "y", Instant.now());
        when(missionMemory.findAll(50)).thenReturn(List.of(realApproval, testApproval, testFailure));

        var approvals = router.route("¿Qué necesita mi aprobación?");
        assertTrue(approvals.contains("1 misión"));
        assertTrue(approvals.contains("MISSION-001"));
        assertFalse(approvals.contains("MISSION-DEBUG-007"));

        var failures = router.route("¿Qué misiones fallaron?");
        assertTrue(failures.contains("No hay ninguna misión fallida"));
    }

    @Test
    void routesFailedMissionsQueryToItsOwnDeterministicFormatting() {
        var awaiting = new MissionResponse("MISSION-1", MissionStatus.AWAITING_INVESTOR, "PRODUCTION", 95, "x", "y", Instant.now());
        var failed = new MissionResponse("MISSION-3", MissionStatus.FAILED, "PRODUCTION", 100, "x", "z", Instant.now());
        when(missionMemory.findAll(50)).thenReturn(List.of(awaiting, failed));

        var response = router.route("¿Qué misiones fallaron?");

        assertTrue(response.contains("1 misión"));
        assertTrue(response.contains("MISSION-3"));
        assertFalse(response.contains("MISSION-1"));
        verifyNoInteractions(ceoService);
    }

    @Test
    void routesTestMissionsQueryWithDeterministicBreakdownByStatus() {
        var testAwaiting = new MissionResponse("MISSION-DEBUG-007", MissionStatus.AWAITING_INVESTOR, "TEST", 95, "x", "y", Instant.now());
        var testFailed = new MissionResponse("MISSION-STRUCTURED-001", MissionStatus.FAILED, "TEST", 100, "x", "y", Instant.now());
        var realOne = new MissionResponse("MISSION-001", MissionStatus.AWAITING_INVESTOR, "PRODUCTION", 95, "x", "y", Instant.now());
        when(missionMemory.findAll(50)).thenReturn(List.of(testAwaiting, testFailed, realOne));

        var response = router.route("¿Qué misiones están en prueba?");

        assertTrue(response.contains("2 misión"));
        assertTrue(response.contains("MISSION-DEBUG-007"));
        assertTrue(response.contains("MISSION-STRUCTURED-001"));
        assertFalse(response.contains("MISSION-001"));
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
        when(ceoService.chat(eq("Alex"), eq("- Sofia (Sales)"), any(), eq("Hola, ¿cómo estás?"), any()))
                .thenReturn("Todo bien, gracias.");

        var response = router.route("Hola, ¿cómo estás?");

        assertEquals("Todo bien, gracias.", response);
        verifyNoInteractions(missionService);
    }

    @Test
    void passesRealConversationHistoryToGeneralChatSoTheCeoRemembersPriorTurns() {
        // Reportado por el usuario: "no está recordando las charlas que
        // tengo con el CEO" -- reproducido en vivo preguntando el color
        // favorito declarado un turno antes, y el CEO respondía que no
        // tenía acceso a esa información. Causa: CeoService.chat nunca
        // recibía los turnos anteriores, solo el mensaje actual.
        var history = List.of(
                new com.aicompany.core.model.ConversationTurn("user", "Mi color favorito es el verde."),
                new com.aicompany.core.model.ConversationTurn("ceo", "Entendido.")
        );

        when(companyMemory.agentName("ceo")).thenReturn(Optional.of("Alex"));
        when(companyMemory.teamRosterDescription()).thenReturn("- Sofia (Sales)");
        when(conversationMemory.recentMessages(20)).thenReturn(history);
        when(ceoService.chat(eq("Alex"), eq("- Sofia (Sales)"), eq(history), eq("¿Cuál es mi color favorito?"), any()))
                .thenReturn("Verde.");

        var response = router.route("¿Cuál es mi color favorito?");

        assertEquals("Verde.", response);
    }

    @Test
    void routesCompanyStatusQueryToADeterministicAggregateSnapshot() {
        // Reportado por el usuario: "dame un status" caía al chat general
        // y el CEO inventaba datos -- literalmente placeholders sin
        // rellenar como "[Nombre del cliente]" -- porque no existía
        // ninguna consulta agregada real. Debe resolverse 100% en Java.
        var missions = List.of(
                new MissionResponse("MISSION-1", MissionStatus.WAITING_AGENT_RESULTS, "PRODUCTION", 30, "x", "x", Instant.now()),
                new MissionResponse("MISSION-2", MissionStatus.AWAITING_INVESTOR, "PRODUCTION", 95, "x", "x", Instant.now()),
                new MissionResponse("MISSION-3", MissionStatus.FAILED, "PRODUCTION", 100, "x", "x", Instant.now()),
                new MissionResponse("MISSION-TEST", MissionStatus.FAILED, "TEST", 100, "x", "x", Instant.now())
        );
        when(missionMemory.findAll(50)).thenReturn(missions);

        var agentStatuses = List.of(
                new AgentStatusResponse("sales", "Sofia", "Sales", "x", "WORKING", "MISSION-1", "MARKET_DISCOVERY", "RUNNING", Instant.now()),
                new AgentStatusResponse("finance", "Max", "Finance", "x", "IDLE", null, null, null, Instant.now())
        );
        when(missionMemory.latestTaskPerAgent()).thenReturn(agentStatuses);
        when(opportunityMemory.countOpportunities()).thenReturn(4L);
        when(customerMemory.countCustomersAndProspects()).thenReturn(new long[]{2L, 3L});
        when(customerMemory.companyWideTotalRevenueAndCost()).thenReturn(new double[]{150.0, 50.0});

        var response = router.route("dame un status");

        assertTrue(response.contains("US$50.00"));
        assertTrue(response.contains("1 trabajando"));
        assertTrue(response.contains("1 inactivo"));
        assertTrue(response.contains("1 activa"));
        assertTrue(response.contains("1 esperando tu aprobación"));
        assertTrue(response.contains("1 fallida"));
        assertTrue(response.contains("Oportunidades registradas: 4"));
        assertTrue(response.contains("Prospectos"));
        assertTrue(response.contains("3"));
        assertTrue(response.contains("Clientes reales: 2"));
        assertTrue(response.contains("US$150.00"));
        assertTrue(response.contains("US$100.00"));
        verifyNoInteractions(ceoService);
    }

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
        when(customerMemory.companyWideTotalRevenueAndCost()).thenReturn(new double[]{100.0, 40.0});
        when(conversationMemory.lastMentioned()).thenReturn(Optional.empty());

        router.route("Hola, ¿cómo estás?");

        var captor = org.mockito.ArgumentCaptor.forClass(java.util.function.Function.class);
        verify(ceoService).chat(anyString(), anyString(), any(), anyString(), captor.capture());
        var companyMemoryQuery = (java.util.function.Function<String, String>) captor.getValue();

        assertTrue(companyMemoryQuery.apply("AGENT_STATUS").contains("Sofia"));
        assertTrue(companyMemoryQuery.apply("MISSIONS_NEEDING_ATTENTION").contains("No hay ninguna misión"));
        assertTrue(companyMemoryQuery.apply("FAILED_MISSIONS").contains("No hay ninguna misión"));
        assertTrue(companyMemoryQuery.apply("TEST_MISSIONS").contains("No hay ninguna misión"));
        assertTrue(companyMemoryQuery.apply("LAST_MENTIONED").contains("No hay ninguna mención reciente"));
        assertTrue(companyMemoryQuery.apply("OPPORTUNITIES").contains("Todavía no hay ninguna oportunidad"));
        assertTrue(companyMemoryQuery.apply("COMPANY_PROFIT").contains("60.00"));
        assertTrue(companyMemoryQuery.apply("COMPANY_STATUS").contains("Estado actual de Forjai"));
        assertTrue(companyMemoryQuery.apply("ALGO_INEXISTENTE").contains("Dato no reconocido"));
    }

    @Test
    void resolvesApprovalCommandAgainstTheFocusAndRecordsRealDecisions() {
        // "La prueba definitiva" del usuario: preguntar qué necesita
        // aprobación, y que "las dos están aprobadas" ejecute la MISMA
        // gobernanza real que POST /missions/{id}/decision -- nunca le
        // pide al CEO (LLM) que "interprete" qué hacer.
        when(conversationMemory.lastMentioned()).thenReturn(
                Optional.of(new LastMentioned("MISSION", List.of("MISSION-1", "MISSION-2")))
        );
        when(missionService.recordDecision(eq("MISSION-1"), any(DecisionCommand.class)))
                .thenReturn(Optional.of(new DecisionResponse("MISSION-1-DECISION-1", "MISSION-1", InvestorDecision.APPROVE, Instant.now())));
        when(missionService.recordDecision(eq("MISSION-2"), any(DecisionCommand.class)))
                .thenReturn(Optional.of(new DecisionResponse("MISSION-2-DECISION-1", "MISSION-2", InvestorDecision.APPROVE, Instant.now())));

        var response = router.route("las dos misiones están aprobadas");

        assertTrue(response.contains("MISSION-1"));
        assertTrue(response.contains("MISSION-2"));
        assertTrue(response.contains("✅"));

        var captor1 = org.mockito.ArgumentCaptor.forClass(DecisionCommand.class);
        verify(missionService).recordDecision(eq("MISSION-1"), captor1.capture());
        assertEquals(InvestorDecision.APPROVE, captor1.getValue().decision());

        var captor2 = org.mockito.ArgumentCaptor.forClass(DecisionCommand.class);
        verify(missionService).recordDecision(eq("MISSION-2"), captor2.capture());
        assertEquals(InvestorDecision.APPROVE, captor2.getValue().decision());

        verifyNoInteractions(ceoService);
    }

    @Test
    void reportsPerMissionOutcomeWhenApprovingMultipleMissionsFromFocusWithAPartialFailure() {
        when(conversationMemory.lastMentioned()).thenReturn(
                Optional.of(new LastMentioned("MISSION", List.of("MISSION-1", "MISSION-2")))
        );
        when(missionService.recordDecision(eq("MISSION-1"), any(DecisionCommand.class)))
                .thenReturn(Optional.of(new DecisionResponse("MISSION-1-DECISION-1", "MISSION-1", InvestorDecision.APPROVE, Instant.now())));
        when(missionService.recordDecision(eq("MISSION-2"), any(DecisionCommand.class)))
                .thenThrow(new IllegalStateException("Solo se puede registrar una decisión sobre una misión en AWAITING_INVESTOR o FAILED"));

        var response = router.route("las dos misiones están aprobadas");

        assertTrue(response.contains("✅"));
        assertTrue(response.contains("MISSION-1"));
        assertTrue(response.contains("❌"));
        assertTrue(response.contains("MISSION-2"));
    }

    @Test
    void resolvesRejectionCommandAgainstTheFocus() {
        when(conversationMemory.lastMentioned()).thenReturn(
                Optional.of(new LastMentioned("MISSION", List.of("MISSION-1")))
        );
        when(missionService.recordDecision(eq("MISSION-1"), any(DecisionCommand.class)))
                .thenReturn(Optional.of(new DecisionResponse("MISSION-1-DECISION-1", "MISSION-1", InvestorDecision.REJECT, Instant.now())));

        // Pronombre plural ("esas") aunque el foco tenga un solo id --
        // el conjunto de pronombres soportados en v1 es deliberadamente
        // solo plural (ver REFERENCE_PRONOUN), para no arriesgar falsos
        // positivos con "esta"/"ese" que aparecen todo el tiempo en
        // oraciones normales sin relación a ninguna referencia.
        var response = router.route("esas quedan rechazadas");

        var captor = org.mockito.ArgumentCaptor.forClass(DecisionCommand.class);
        verify(missionService).recordDecision(eq("MISSION-1"), captor.capture());
        assertEquals(InvestorDecision.REJECT, captor.getValue().decision());
        assertTrue(response.contains("MISSION-1"));
    }

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
        // Verificado en vivo: sin ninguna pista de a qué tipo de entidad
        // se refiere "esas", el LLM no tiene forma de saber que debe
        // usar la herramienta LAST_MENTIONED -- en una corrida real
        // ignoró la pregunta y contestó sobre el equipo (alucinando de
        // nuevo). El mensaje que le llega al chat general debe incluir
        // una pista sobre el foco real (tipo de entidad, nunca los datos
        // en sí -- eso sigue viniendo de la herramienta).
        when(companyMemory.agentName("ceo")).thenReturn(Optional.of("Alex"));
        when(companyMemory.teamRosterDescription()).thenReturn("- Sofia (Sales)");
        when(conversationMemory.lastMentioned()).thenReturn(
                Optional.of(new LastMentioned("MISSION", List.of("MISSION-001")))
        );
        when(ceoService.chat(eq("Alex"), eq("- Sofia (Sales)"), any(), contains("contame más sobre esas"), any()))
                .thenReturn("Ahí va el detalle.");

        var response = router.route("contame más sobre esas");

        assertEquals("Ahí va el detalle.", response);
        verify(ceoService).chat(eq("Alex"), eq("- Sofia (Sales)"), any(), contains("LAST_MENTIONED"), any());
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
}
