package com.aicompany.core.service;

import com.aicompany.core.agent.AgentRuntime;
import com.aicompany.core.agent.model.AgentResult;
import com.aicompany.core.agent.validation.ContradictionDetector;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.FinancialCriteriaResponse;
import com.aicompany.core.model.FinancialMetric;
import com.aicompany.core.model.MissionStatus;
import com.aicompany.core.model.PolicyKey;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MissionExecutorTest {

    private final MissionMemoryService memory = mock(MissionMemoryService.class);
    private final AgentRuntime runtime = mock(AgentRuntime.class);
    private final CeoService ceoService = mock(CeoService.class);
    // Sin stub explícito, un mock de CompanyMemoryService devuelve null en
    // agentModel(...) -- y anyString() (usado en la mayoría de los
    // call-sites de executeMission de este archivo) no matchea null, lo
    // que rompería todos los stubs existentes que no les importa qué
    // modelo se resuelva. Default: devuelve el fallback tal cual, como
    // haría la implementación real cuando el CEO no tiene un modelo
    // propio configurado en Neo4j.
    private final CompanyMemoryService companyMemory = defaultCompanyMemory();

    private static CompanyMemoryService defaultCompanyMemory() {
        var mock = mock(CompanyMemoryService.class);
        when(mock.agentModel(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        return mock;
    }

    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final ContradictionDetector contradictionDetector = mock(ContradictionDetector.class);
    private final CompanyPolicyService companyPolicyService = defaultCompanyPolicyService();

    private static CompanyPolicyService defaultCompanyPolicyService() {
        var mock = mock(CompanyPolicyService.class);
        when(mock.activeValue(PolicyKey.SEED_CAPITAL_USD)).thenReturn(50.0);
        when(mock.activeValue(PolicyKey.CONTRADICTION_SEED_CAPITAL_MULTIPLE)).thenReturn(100.0);
        return mock;
    }

    private final OpportunityMemoryService opportunityMemory = mock(OpportunityMemoryService.class);
    private final AlertMailService alertMailService = mock(AlertMailService.class);
    private final PromptMemoryService promptMemory = defaultPromptMemory();

    private static PromptMemoryService defaultPromptMemory() {
        var mock = mock(PromptMemoryService.class);
        when(mock.activePrompt(anyString())).thenReturn("");
        return mock;
    }

    private final MissionExecutor executor = new MissionExecutor(
            memory, new AgentTaskBatchRunner(memory, runtime, events), ceoService, companyMemory, promptMemory, "qwen2.5-coder:14b", Runnable::run, events, jsonMapper,
            contradictionDetector, companyPolicyService, opportunityMemory, alertMailService
    );

    @Test
    void reachesAwaitingInvestorWhenAllAgentsSucceed() throws Exception {
        stubAgent("sales");
        stubAgent("product");
        stubAgent("finance");
        stubAgent("engineering");
        stubAgent("qa");

        when(contradictionDetector.detect(any(), anyDouble(), anyDouble())).thenReturn(List.of());

        var resultsCaptor = ArgumentCaptor.forClass(String.class);
        when(ceoService.executeMission(anyString(), resultsCaptor.capture(), anyString(), anyString()))
                .thenReturn("consolidado");

        executor.executeAsync("MISSION-1", "instrucción").get();

        verify(memory).updateMission(
                eq("MISSION-1"), eq(MissionStatus.AWAITING_INVESTOR), anyInt(), anyString(), anyString());
        verify(ceoService, times(1)).executeMission(anyString(), anyString(), anyString(), anyString());

        assertFalse(resultsCaptor.getValue().contains("AGENTES_FALLIDOS"));

        verify(opportunityMemory).recordOpportunity("MISSION-1", "instrucción");

        verify(alertMailService).send(contains("MISSION-1"), anyString(), anyBoolean());
    }

    @Test
    void recordsCustomerCandidatesFromAgentResults() throws Exception {
        stubAgent("product");
        stubAgent("finance");
        stubAgent("engineering");
        stubAgent("qa");

        var candidate = new AgentResult.CustomerCandidate(
                "Microempresas de logística en Medellín",
                "Identificadas en estudios de mercado citados por el agente",
                "https://example.com/estudio-logistica",
                "WEB"
        );

        var salesResult = new AgentResult(
                "sales", "MARKET_DISCOVERY", "NOT_VALIDATED",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                "recomendación de sales", 0.5, List.of(candidate)
        );

        when(runtime.execute(anyString(), eq("MISSION-1"), eq("sales"), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(salesResult));

        when(contradictionDetector.detect(any(), anyDouble(), anyDouble())).thenReturn(List.of());
        when(ceoService.executeMission(anyString(), anyString(), anyString(), anyString())).thenReturn("consolidado");

        executor.executeAsync("MISSION-1", "instrucción").get();

        verify(opportunityMemory).recordOpportunity("MISSION-1", "instrucción");
        verify(opportunityMemory).recordCandidates("MISSION-1", "sales", List.of(candidate));
        // Los demás agentes no reportaron candidatos -- se llama igual,
        // pero con lista vacía (recordCandidates no hace nada con eso).
        verify(opportunityMemory).recordCandidates("MISSION-1", "product", List.of());
    }

    @Test
    void continuesWithPartialResultsWhenSomeAgentsFail() throws Exception {
        stubAgent("sales");
        stubAgent("product");
        stubFailingAgent("finance", "El agente finance no produjo un resultado procesable después de 3 intentos.");
        stubAgent("engineering");
        stubAgent("qa");

        when(contradictionDetector.detect(any(), anyDouble(), anyDouble())).thenReturn(List.of());

        var resultsCaptor = ArgumentCaptor.forClass(String.class);
        when(ceoService.executeMission(anyString(), resultsCaptor.capture(), anyString(), anyString()))
                .thenReturn("consolidado con hueco");

        executor.executeAsync("MISSION-1", "instrucción").get();

        // La misión sigue adelante hasta AWAITING_INVESTOR pese al agente
        // fallido -- no cae a FAILED por un solo agente no recuperable.
        verify(memory).updateMission(
                eq("MISSION-1"), eq(MissionStatus.AWAITING_INVESTOR), anyInt(), anyString(), anyString());
        verify(memory, never()).updateMission(
                eq("MISSION-1"), eq(MissionStatus.FAILED), anyInt(), anyString(), anyString());

        verify(ceoService, times(1)).executeMission(anyString(), anyString(), anyString(), anyString());

        var resultsForCeo = resultsCaptor.getValue();
        assertTrue(resultsForCeo.contains("AGENTES_FALLIDOS"));
        assertTrue(resultsForCeo.contains("finance"));
        assertTrue(resultsForCeo.contains("no produjo un resultado procesable"));

        // Los 4 agentes que sí completaron deben seguir yendo a la
        // consolidación -- solo se descarta el que falló.
        assertTrue(resultsForCeo.contains("\"agent\":\"sales\""));
        assertTrue(resultsForCeo.contains("\"agent\":\"qa\""));
        assertFalse(resultsForCeo.contains("\"agent\":\"finance\""));
    }

    @Test
    void recoversAgentThroughMissionLevelReplanAfterInitialFailure() throws Exception {
        stubAgent("product");
        stubAgent("finance");
        stubAgent("engineering");
        stubAgent("qa");

        var recovered = new AgentResult(
                "sales", "ACTION", "NOT_VALIDATED",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                "recomendación de sales tras replan", 0.5
        );

        var failedFuture = new CompletableFuture<AgentResult>();
        failedFuture.completeExceptionally(new IllegalStateException("fallo transitorio"));

        // Primera llamada (intento normal) falla; la segunda (replan a
        // nivel de misión) sí completa -- el agente se recupera sin que
        // la misión pierda su resultado.
        when(runtime.execute(anyString(), eq("MISSION-1"), eq("sales"), anyString(), anyString()))
                .thenReturn(failedFuture)
                .thenReturn(CompletableFuture.completedFuture(recovered));

        when(contradictionDetector.detect(any(), anyDouble(), anyDouble())).thenReturn(List.of());

        var resultsCaptor = ArgumentCaptor.forClass(String.class);
        when(ceoService.executeMission(anyString(), resultsCaptor.capture(), anyString(), anyString()))
                .thenReturn("consolidado");

        executor.executeAsync("MISSION-1", "instrucción").get();

        verify(memory).updateMission(
                eq("MISSION-1"), eq(MissionStatus.AWAITING_INVESTOR), anyInt(), anyString(), anyString());
        verify(runtime, times(2)).execute(
                anyString(), eq("MISSION-1"), eq("sales"), anyString(), anyString());
        verify(events).publish(
                eq("EMPRESA_MISSION_REPLANNED"), eq("MISSION-1"), anyString(), eq("sales"), any());

        var resultsForCeo = resultsCaptor.getValue();
        assertFalse(resultsForCeo.contains("AGENTES_FALLIDOS"));
        assertTrue(resultsForCeo.contains("recomendación de sales tras replan"));
    }

    @Test
    void stopsReplanningAfterExhaustingMissionLevelRetries() throws Exception {
        stubAgent("sales");
        stubAgent("product");
        stubFailingAgent("finance", "sigue sin resultado");
        stubAgent("engineering");
        stubAgent("qa");

        when(contradictionDetector.detect(any(), anyDouble(), anyDouble())).thenReturn(List.of());
        when(ceoService.executeMission(anyString(), anyString(), anyString(), anyString())).thenReturn("consolidado");

        executor.executeAsync("MISSION-1", "instrucción").get();

        // MAX_AGENT_REPLANS=1 -> intento normal + 1 replan, nunca más.
        verify(runtime, times(2)).execute(
                anyString(), eq("MISSION-1"), eq("finance"), anyString(), anyString());
        verify(events, times(1)).publish(
                eq("EMPRESA_MISSION_REPLANNED"), eq("MISSION-1"), anyString(), eq("finance"), any());
        verify(memory).updateMission(
                eq("MISSION-1"), eq(MissionStatus.AWAITING_INVESTOR), anyInt(), anyString(), anyString());
    }

    @Test
    void failsMissionWhenAllAgentsFail() throws Exception {
        stubFailingAgent("sales", "sin resultado");
        stubFailingAgent("product", "sin resultado");
        stubFailingAgent("finance", "sin resultado");
        stubFailingAgent("engineering", "sin resultado");
        stubFailingAgent("qa", "sin resultado");

        executor.executeAsync("MISSION-1", "instrucción").get();

        verify(memory).updateMission(
                eq("MISSION-1"), eq(MissionStatus.FAILED), anyInt(), anyString(), anyString());
        verify(ceoService, never()).executeMission(anyString(), anyString(), anyString(), anyString());
        verify(opportunityMemory, never()).recordOpportunity(anyString(), anyString());

        verify(alertMailService).send(contains("MISSION-1"), anyString(), anyBoolean());
    }

    @Test
    void financeObjectiveReferencesTheLiveSeedCapitalPolicyInsteadOfAHardcodedAmount() throws Exception {
        // Ya no lee AppProperties -- lee la Company Policy vigente. Valor
        // DISTINTO de 50 para probar que sale de la política real, no que
        // "coincide" con un default.
        var customPolicies = mock(CompanyPolicyService.class);
        when(customPolicies.activeValue(PolicyKey.SEED_CAPITAL_USD)).thenReturn(75.0);
        when(customPolicies.activeValue(PolicyKey.CONTRADICTION_SEED_CAPITAL_MULTIPLE)).thenReturn(100.0);

        var executorWithCustomCapital = new MissionExecutor(
                memory, new AgentTaskBatchRunner(memory, runtime, events), ceoService, companyMemory, promptMemory, "qwen2.5-coder:14b", Runnable::run, events,
                jsonMapper, contradictionDetector, customPolicies, opportunityMemory, alertMailService
        );

        stubAgent("sales");
        stubAgent("product");
        stubAgent("finance");
        stubAgent("engineering");
        stubAgent("qa");

        when(contradictionDetector.detect(any(), anyDouble(), anyDouble())).thenReturn(List.of());
        when(ceoService.executeMission(anyString(), anyString(), anyString(), anyString())).thenReturn("consolidado");

        executorWithCustomCapital.executeAsync("MISSION-1", "instrucción").get();

        var instructionCaptor = ArgumentCaptor.forClass(String.class);
        verify(runtime).execute(anyString(), eq("MISSION-1"), eq("finance"), anyString(), instructionCaptor.capture());

        var expectedAmount = "US$%.2f".formatted(75.0);
        assertTrue(instructionCaptor.getValue().contains(expectedAmount));
        assertFalse(instructionCaptor.getValue().contains("US$50"));
    }

    @Test
    void financeObjectiveIncludesMissionsStructuredFinancialCriteriaWhenDeclared() throws Exception {
        when(memory.financialCriteria("MISSION-1")).thenReturn(Optional.of(
                new FinancialCriteriaResponse(FinancialMetric.NET_PROFIT, 1000.0, "USD", java.time.LocalDate.of(2026, 11, 20))
        ));

        stubAgent("sales");
        stubAgent("product");
        stubAgent("finance");
        stubAgent("engineering");
        stubAgent("qa");

        when(contradictionDetector.detect(any(), anyDouble(), anyDouble())).thenReturn(List.of());
        when(ceoService.executeMission(anyString(), anyString(), anyString(), anyString())).thenReturn("consolidado");

        executor.executeAsync("MISSION-1", "instrucción").get();

        var instructionCaptor = ArgumentCaptor.forClass(String.class);
        verify(runtime).execute(anyString(), eq("MISSION-1"), eq("finance"), anyString(), instructionCaptor.capture());

        var text = instructionCaptor.getValue();
        assertTrue(text.contains("objetivo financiero explícito"));
        assertTrue(text.contains("NET_PROFIT"));
        assertTrue(text.contains("1000"));
        assertTrue(text.contains("nunca alteres los valores"));
    }

    @Test
    void financeObjectiveDoesNotAssertAnyTargetWhenMissionHasNoFinancialCriteria() throws Exception {
        stubAgent("sales");
        stubAgent("product");
        stubAgent("finance");
        stubAgent("engineering");
        stubAgent("qa");

        when(contradictionDetector.detect(any(), anyDouble(), anyDouble())).thenReturn(List.of());
        when(ceoService.executeMission(anyString(), anyString(), anyString(), anyString())).thenReturn("consolidado");

        executor.executeAsync("MISSION-1", "instrucción").get();

        var instructionCaptor = ArgumentCaptor.forClass(String.class);
        verify(runtime).execute(anyString(), eq("MISSION-1"), eq("finance"), anyString(), instructionCaptor.capture());

        assertFalse(instructionCaptor.getValue().contains("objetivo financiero explícito"));
    }

    private void stubAgent(String agentId) {
        var result = new AgentResult(
                agentId, "ACTION", "NOT_VALIDATED",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                "recomendación de " + agentId, 0.5
        );

        when(runtime.execute(anyString(), eq("MISSION-1"), eq(agentId), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(result));
    }

    private void stubFailingAgent(String agentId, String message) {
        var future = new CompletableFuture<AgentResult>();
        future.completeExceptionally(new IllegalStateException(message));

        when(runtime.execute(anyString(), eq("MISSION-1"), eq(agentId), anyString(), anyString()))
                .thenReturn(future);
    }
}
