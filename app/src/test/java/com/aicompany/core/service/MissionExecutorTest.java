package com.aicompany.core.service;

import com.aicompany.core.agent.AgentRuntime;
import com.aicompany.core.agent.model.AgentResult;
import com.aicompany.core.agent.validation.ContradictionDetector;
import com.aicompany.core.config.AppProperties;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.MissionStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MissionExecutorTest {

    private final MissionMemoryService memory = mock(MissionMemoryService.class);
    private final AgentRuntime runtime = mock(AgentRuntime.class);
    private final CeoService ceoService = mock(CeoService.class);
    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final ContradictionDetector contradictionDetector = mock(ContradictionDetector.class);
    private final AppProperties appProperties = new AppProperties("Forjai", 50.0, 60, 2);
    private final OpportunityMemoryService opportunityMemory = mock(OpportunityMemoryService.class);
    private final AlertMailService alertMailService = mock(AlertMailService.class);

    private final MissionExecutor executor = new MissionExecutor(
            memory, runtime, ceoService, Runnable::run, events, jsonMapper,
            contradictionDetector, appProperties, opportunityMemory, alertMailService
    );

    @Test
    void reachesAwaitingInvestorWhenAllAgentsSucceed() throws Exception {
        stubAgent("sales");
        stubAgent("product");
        stubAgent("finance");
        stubAgent("engineering");
        stubAgent("qa");

        when(contradictionDetector.detect(any(), anyDouble())).thenReturn(List.of());

        var resultsCaptor = ArgumentCaptor.forClass(String.class);
        when(ceoService.executeMission(anyString(), resultsCaptor.capture()))
                .thenReturn("consolidado");

        executor.executeAsync("MISSION-1", "instrucción").get();

        verify(memory).updateMission(
                eq("MISSION-1"), eq(MissionStatus.AWAITING_INVESTOR), anyInt(), anyString(), anyString());
        verify(ceoService, times(1)).executeMission(anyString(), anyString());

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

        when(contradictionDetector.detect(any(), anyDouble())).thenReturn(List.of());
        when(ceoService.executeMission(anyString(), anyString())).thenReturn("consolidado");

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

        when(contradictionDetector.detect(any(), anyDouble())).thenReturn(List.of());

        var resultsCaptor = ArgumentCaptor.forClass(String.class);
        when(ceoService.executeMission(anyString(), resultsCaptor.capture()))
                .thenReturn("consolidado con hueco");

        executor.executeAsync("MISSION-1", "instrucción").get();

        // La misión sigue adelante hasta AWAITING_INVESTOR pese al agente
        // fallido -- no cae a FAILED por un solo agente no recuperable.
        verify(memory).updateMission(
                eq("MISSION-1"), eq(MissionStatus.AWAITING_INVESTOR), anyInt(), anyString(), anyString());
        verify(memory, never()).updateMission(
                eq("MISSION-1"), eq(MissionStatus.FAILED), anyInt(), anyString(), anyString());

        verify(ceoService, times(1)).executeMission(anyString(), anyString());

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

        when(contradictionDetector.detect(any(), anyDouble())).thenReturn(List.of());

        var resultsCaptor = ArgumentCaptor.forClass(String.class);
        when(ceoService.executeMission(anyString(), resultsCaptor.capture()))
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

        when(contradictionDetector.detect(any(), anyDouble())).thenReturn(List.of());
        when(ceoService.executeMission(anyString(), anyString())).thenReturn("consolidado");

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
        verify(ceoService, never()).executeMission(anyString(), anyString());
        verify(opportunityMemory, never()).recordOpportunity(anyString(), anyString());

        verify(alertMailService).send(contains("MISSION-1"), anyString(), anyBoolean());
    }

    @Test
    void reexecuteAsyncCreatesTasksWithRoundSuffixAndReachesAwaitingInvestorAgain() throws Exception {
        stubAgent("sales");
        stubAgent("product");
        stubAgent("finance");
        stubAgent("engineering");
        stubAgent("qa");

        when(memory.tasksForRound(eq("MISSION-1"), eq(0))).thenReturn(List.of());
        when(ceoService.routeInvestorFeedback(anyString(), anyString(), anyString()))
                .thenReturn(Map.of(
                        "sales", "", "product", "", "finance", "", "engineering", "", "qa", ""));
        when(contradictionDetector.detect(any(), anyDouble())).thenReturn(List.of());
        when(ceoService.executeMission(anyString(), anyString())).thenReturn("consolidado ronda 1");

        var taskIdCaptor = ArgumentCaptor.forClass(String.class);

        executor.reexecuteAsync("MISSION-1", "instrucción", 1, "falta validar precios reales").get();

        verify(runtime, times(5)).execute(
                taskIdCaptor.capture(), eq("MISSION-1"), anyString(), anyString(), anyString());

        assertTrue(taskIdCaptor.getAllValues().stream()
                .allMatch(taskId -> taskId.endsWith("-R1")));

        verify(memory).updateMission(
                eq("MISSION-1"), eq(MissionStatus.AWAITING_INVESTOR), anyInt(), anyString(), anyString());

        verify(events).publish(
                eq("EMPRESA_MISSION_EVIDENCE_ROUND_STARTED"), eq("MISSION-1"), any(), eq("human"), any());
    }

    @Test
    void reexecuteAsyncAppendsInvestorFeedbackOnlyForAgentsThatReceivedIt() throws Exception {
        stubAgent("sales");
        stubAgent("product");
        stubAgent("finance");
        stubAgent("engineering");
        stubAgent("qa");

        when(memory.tasksForRound(eq("MISSION-1"), eq(1))).thenReturn(List.of());
        when(ceoService.routeInvestorFeedback(anyString(), anyString(), eq("falta validar precios reales")))
                .thenReturn(Map.of(
                        "sales", "Confirmá precios reales de al menos 3 competidores.",
                        "product", "", "finance", "", "engineering", "", "qa", ""));
        when(contradictionDetector.detect(any(), anyDouble())).thenReturn(List.of());
        when(ceoService.executeMission(anyString(), anyString())).thenReturn("consolidado ronda 2");

        var salesInstructionCaptor = ArgumentCaptor.forClass(String.class);
        var productInstructionCaptor = ArgumentCaptor.forClass(String.class);

        executor.reexecuteAsync("MISSION-1", "instrucción", 2, "falta validar precios reales").get();

        verify(runtime).execute(
                anyString(), eq("MISSION-1"), eq("sales"), anyString(), salesInstructionCaptor.capture());
        verify(runtime).execute(
                anyString(), eq("MISSION-1"), eq("product"), anyString(), productInstructionCaptor.capture());

        assertTrue(salesInstructionCaptor.getValue().contains("SOLICITUD DEL INVERSIONISTA"));
        assertTrue(salesInstructionCaptor.getValue().contains("Confirmá precios reales"));
        assertFalse(productInstructionCaptor.getValue().contains("SOLICITUD DEL INVERSIONISTA"));
    }

    @Test
    void initialExecutionUsesRoundZeroTaskIdsAndEmptyFeedback() throws Exception {
        stubAgent("sales");
        stubAgent("product");
        stubAgent("finance");
        stubAgent("engineering");
        stubAgent("qa");

        when(contradictionDetector.detect(any(), anyDouble())).thenReturn(List.of());
        when(ceoService.executeMission(anyString(), anyString())).thenReturn("consolidado");

        var taskIdCaptor = ArgumentCaptor.forClass(String.class);

        executor.executeAsync("MISSION-1", "instrucción").get();

        verify(runtime, times(5)).execute(
                taskIdCaptor.capture(), eq("MISSION-1"), anyString(), anyString(), anyString());

        assertTrue(taskIdCaptor.getAllValues().stream()
                .allMatch(taskId -> taskId.endsWith("-R0")));

        verify(ceoService, never()).routeInvestorFeedback(anyString(), anyString(), anyString());
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
