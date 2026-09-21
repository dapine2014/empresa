package com.aicompany.core.agent;

import com.aicompany.core.agent.model.AgentResult;
import com.aicompany.core.agent.model.AgentTaskOutcome;
import com.aicompany.core.agent.validation.AgentResultValidator;
import com.aicompany.core.agent.validation.EvidenceBindingGate;
import com.aicompany.core.agent.validation.EvidenceValidationGate;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.service.CeoService;
import com.aicompany.core.service.CompanyMemoryService;
import com.aicompany.core.service.MissionMemoryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentRuntimeTest {

    private final MissionMemoryService memory = mock(MissionMemoryService.class);
    // Sin stub explícito, un mock de CompanyMemoryService devuelve null en
    // agentModel(...) -- y anyString() (usado en la mayoría de los
    // call-sites de executeAgentTask de este archivo) no matchea null, lo
    // que rompería todos los stubs existentes que no les importa qué
    // modelo se resuelva. Default: devuelve el fallback tal cual, como
    // haría la implementación real cuando el agente no tiene un modelo
    // propio configurado en Neo4j -- los tests que sí necesitan un modelo
    // real distinto por agente (ver resolvesADifferentRealModelPerAgent)
    // sobreescriben este stub genérico con uno más específico.
    private final CompanyMemoryService companyMemory = defaultCompanyMemory();

    private static CompanyMemoryService defaultCompanyMemory() {
        var mock = mock(CompanyMemoryService.class);
        when(mock.agentModel(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        return mock;
    }

    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    private final CeoService ceoService = mock(CeoService.class);
    private final AgentResultValidator validator = mock(AgentResultValidator.class);
    private final EvidenceValidationGate evidenceGate = mock(EvidenceValidationGate.class);
    private final EvidenceBindingGate evidenceBindingGate = new EvidenceBindingGate();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private final AgentRuntime runtime = new AgentRuntime(
            ceoService, memory, companyMemory, "qwen3:8b", Runnable::run, events,
            validator, evidenceGate, evidenceBindingGate, jsonMapper
    );

    @Test
    void succeedsOnFirstAttemptWithoutRetrying() throws Exception {
        var result = agentResult("finance", "recomendación ok");

        when(ceoService.executeAgentTask(eq("finance"), anyString(), anyString(), eq("MISSION-1"), eq("TASK-1"), anyString()))
                .thenReturn(outcome(result));
        when(validator.validate(result)).thenReturn(new AgentResultValidator.ValidationResult(true, List.of()));
        when(evidenceGate.validate(result)).thenReturn(new EvidenceValidationGate.ValidationResult(true, List.of()));

        var future = runtime.execute("TASK-1", "MISSION-1", "finance", "UNIT_ECONOMICS", "instrucción");

        assertEquals(result, future.get());
        verify(ceoService, times(1)).executeAgentTask(eq("finance"), anyString(), anyString(), eq("MISSION-1"), eq("TASK-1"), anyString());
        verify(events, never()).publishTask(eq("EMPRESA_TASK_RETRY"), any(), any(), any(), any(), any());
        verify(memory).updateTask(eq("TASK-1"), eq("COMPLETED"), anyString());
    }

    @Test
    void setsAgentStatusToWorkingWhenTaskStartsAndBackToIdleOnSuccess() throws Exception {
        // Agent.status es una propiedad real en Neo4j, distinta del
        // status de la AgentTask -- antes no existía, y el frontend/chat
        // usaban el status de la última tarea como si fuera el del
        // agente (un agente con la última tarea COMPLETED se mostraba
        // "trabajando en X" para siempre). Verificado en vivo, reportado
        // por el usuario.
        var result = agentResult("finance", "recomendación ok");

        when(ceoService.executeAgentTask(eq("finance"), anyString(), anyString(), eq("MISSION-1"), eq("TASK-1"), anyString()))
                .thenReturn(outcome(result));
        when(validator.validate(result)).thenReturn(new AgentResultValidator.ValidationResult(true, List.of()));
        when(evidenceGate.validate(result)).thenReturn(new EvidenceValidationGate.ValidationResult(true, List.of()));

        runtime.execute("TASK-1", "MISSION-1", "finance", "UNIT_ECONOMICS", "instrucción").get();

        var inOrder = inOrder(memory);
        inOrder.verify(memory).setAgentStatus("finance", "WORKING");
        inOrder.verify(memory).updateTask(eq("TASK-1"), eq("COMPLETED"), anyString());
        inOrder.verify(memory).setAgentStatus("finance", "IDLE");
    }

    @Test
    void setsAgentStatusBackToIdleEvenWhenTheTaskFailsAfterExhaustingRetries() {
        var badResult = agentResult("finance", "");

        when(ceoService.executeAgentTask(eq("finance"), anyString(), anyString(), eq("MISSION-1"), eq("TASK-1"), anyString()))
                .thenReturn(outcome(badResult));
        when(validator.validate(badResult))
                .thenReturn(new AgentResultValidator.ValidationResult(false, List.of("recommendation es obligatorio")));

        var future = runtime.execute("TASK-1", "MISSION-1", "finance", "UNIT_ECONOMICS", "instrucción");

        assertThrows(ExecutionException.class, future::get);

        verify(memory).setAgentStatus("finance", "WORKING");
        verify(memory).setAgentStatus("finance", "IDLE");
    }

    @Test
    void retriesAfterValidatorRejectionAndIncludesCorrectionInNextPrompt() throws Exception {
        var badResult = agentResult("finance", "");
        var goodResult = agentResult("finance", "recomendación corregida");

        when(ceoService.executeAgentTask(eq("finance"), anyString(), anyString(), eq("MISSION-1"), eq("TASK-1"), anyString()))
                .thenReturn(outcome(badResult))
                .thenReturn(outcome(goodResult));

        when(validator.validate(badResult))
                .thenReturn(new AgentResultValidator.ValidationResult(false, List.of("recommendation es obligatorio")));
        when(validator.validate(goodResult))
                .thenReturn(new AgentResultValidator.ValidationResult(true, List.of()));

        when(evidenceGate.validate(goodResult))
                .thenReturn(new EvidenceValidationGate.ValidationResult(true, List.of()));

        var future = runtime.execute("TASK-1", "MISSION-1", "finance", "UNIT_ECONOMICS", "instrucción");

        assertEquals(goodResult, future.get());

        var promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(ceoService, times(2)).executeAgentTask(eq("finance"), promptCaptor.capture(), anyString(), eq("MISSION-1"), eq("TASK-1"), anyString());

        var prompts = promptCaptor.getAllValues();
        assertFalse(prompts.get(0).contains("CORRECCIÓN DEL INTENTO ANTERIOR"));
        assertTrue(prompts.get(1).contains("CORRECCIÓN DEL INTENTO ANTERIOR"));
        assertTrue(prompts.get(1).contains("recommendation es obligatorio"));

        verify(events, times(1)).publishTask(eq("EMPRESA_TASK_RETRY"), any(), any(), any(), any(), any());
    }

    @Test
    void retriesAfterUnparsableModelResponse() throws Exception {
        var goodResult = agentResult("engineering", "recomendación ok");

        when(ceoService.executeAgentTask(eq("engineering"), anyString(), anyString(), eq("MISSION-1"), eq("TASK-1"), anyString()))
                .thenThrow(new IllegalStateException("El agente engineering no devolvió un AgentResult JSON válido."))
                .thenReturn(outcome(goodResult));

        when(validator.validate(goodResult))
                .thenReturn(new AgentResultValidator.ValidationResult(true, List.of()));
        when(evidenceGate.validate(goodResult))
                .thenReturn(new EvidenceValidationGate.ValidationResult(true, List.of()));

        var future = runtime.execute("TASK-1", "MISSION-1", "engineering", "DELIVERY_FEASIBILITY", "instrucción");

        assertEquals(goodResult, future.get());
        verify(ceoService, times(2)).executeAgentTask(eq("engineering"), anyString(), anyString(), eq("MISSION-1"), eq("TASK-1"), anyString());
        verify(validator, times(1)).validate(any());
    }

    @Test
    void failsTaskAfterExhaustingAllRetriesOnValidatorRejection() {
        var badResult = agentResult("finance", "");

        when(ceoService.executeAgentTask(eq("finance"), anyString(), anyString(), eq("MISSION-1"), eq("TASK-1"), anyString()))
                .thenReturn(outcome(badResult));
        when(validator.validate(badResult))
                .thenReturn(new AgentResultValidator.ValidationResult(false, List.of("recommendation es obligatorio")));

        var future = runtime.execute("TASK-1", "MISSION-1", "finance", "UNIT_ECONOMICS", "instrucción");

        var ex = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(IllegalStateException.class, ex.getCause());

        // MAX_RESULT_RETRIES=2 -> 3 intentos en total (0,1,2).
        verify(ceoService, times(3)).executeAgentTask(eq("finance"), anyString(), anyString(), eq("MISSION-1"), eq("TASK-1"), anyString());
        verify(memory).updateTask(eq("TASK-1"), eq("FAILED"), anyString());
        verify(events).publishTask(eq("EMPRESA_TASK_FAILED"), any(), any(), any(), any(), any());
    }

    @Test
    void failsTaskAfterExhaustingAllRetriesOnUnparsableResponse() {
        when(ceoService.executeAgentTask(eq("finance"), anyString(), anyString(), eq("MISSION-1"), eq("TASK-1"), anyString()))
                .thenThrow(new IllegalStateException("respuesta no parseable"));

        var future = runtime.execute("TASK-1", "MISSION-1", "finance", "UNIT_ECONOMICS", "instrucción");

        var ex = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(IllegalStateException.class, ex.getCause());

        verify(ceoService, times(3)).executeAgentTask(eq("finance"), anyString(), anyString(), eq("MISSION-1"), eq("TASK-1"), anyString());
        verify(validator, never()).validate(any());
        verify(memory).updateTask(eq("TASK-1"), eq("FAILED"), anyString());
    }

    @Test
    void retriesWhenAgentSearchedRealEvidenceButDidNotCiteItInResult() throws Exception {
        // El agente pidió y recibió una URL real confirmada, pero su
        // evidence[] queda vacío — el gate de binding debe rechazarlo y
        // dar oportunidad de corregir, igual que el validator sintáctico.
        var unboundResult = agentResult("sales", "sin evidencia citada");
        var boundResult = agentResultWithEvidence(
                "sales", "con evidencia citada",
                List.of(new AgentResult.Evidence(
                        "fuente real", "https://example.com/real", "WEB", false))
        );

        when(ceoService.executeAgentTask(eq("sales"), anyString(), anyString(), eq("MISSION-1"), eq("TASK-1"), anyString()))
                .thenReturn(outcome(unboundResult, List.of("https://example.com/real")))
                .thenReturn(outcome(boundResult, List.of("https://example.com/real")));

        when(validator.validate(unboundResult))
                .thenReturn(new AgentResultValidator.ValidationResult(true, List.of()));
        when(validator.validate(boundResult))
                .thenReturn(new AgentResultValidator.ValidationResult(true, List.of()));
        when(evidenceGate.validate(boundResult))
                .thenReturn(new EvidenceValidationGate.ValidationResult(true, List.of()));

        var future = runtime.execute("TASK-1", "MISSION-1", "sales", "MARKET_DISCOVERY", "instrucción");

        assertEquals(boundResult, future.get());
        verify(ceoService, times(2)).executeAgentTask(eq("sales"), anyString(), anyString(), eq("MISSION-1"), eq("TASK-1"), anyString());
        verify(events, times(1)).publishTask(eq("EMPRESA_TASK_RETRY"), any(), any(), any(), any(), any());
    }

    @Test
    void failsTaskAfterExhaustingRetriesWhenEvidenceNeverGetsBound() {
        var unboundResult = agentResult("sales", "sin evidencia citada");

        when(ceoService.executeAgentTask(eq("sales"), anyString(), anyString(), eq("MISSION-1"), eq("TASK-1"), anyString()))
                .thenReturn(outcome(unboundResult, List.of("https://example.com/real")));

        when(validator.validate(unboundResult))
                .thenReturn(new AgentResultValidator.ValidationResult(true, List.of()));

        var future = runtime.execute("TASK-1", "MISSION-1", "sales", "MARKET_DISCOVERY", "instrucción");

        var ex = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(IllegalStateException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("buscó evidencia real pero no la citó"));

        verify(ceoService, times(3)).executeAgentTask(eq("sales"), anyString(), anyString(), eq("MISSION-1"), eq("TASK-1"), anyString());
        verify(evidenceGate, never()).validate(any(AgentResult.class));
        verify(memory).updateTask(eq("TASK-1"), eq("FAILED"), anyString());
    }

    @Test
    void keepsRequiringCitationAcrossRetriesEvenIfALaterAttemptDoesNotSearchAgain() throws Exception {
        // El turno de decisión de herramienta es independiente en cada
        // intento (no ve el feedback de corrección) — si el intento 2 no
        // vuelve a pedir la herramienta, confirmedEvidenceUrls para ESE
        // intento viene vacío. El gate no debe "olvidar" la URL real que
        // sí se confirmó en el intento 1: seguir sin citarla debe seguir
        // rechazando, no aprobar trivialmente solo porque este intento en
        // particular no buscó nada nuevo.
        var unboundResult = agentResult("sales", "sin evidencia citada");
        var boundResult = agentResultWithEvidence(
                "sales", "con evidencia citada",
                List.of(new AgentResult.Evidence(
                        "fuente real", "https://example.com/real", "WEB", false))
        );

        when(ceoService.executeAgentTask(eq("sales"), anyString(), anyString(), eq("MISSION-1"), eq("TASK-1"), anyString()))
                .thenReturn(outcome(unboundResult, List.of("https://example.com/real")))
                .thenReturn(outcome(unboundResult, List.of()))
                .thenReturn(outcome(boundResult, List.of()));

        when(validator.validate(unboundResult))
                .thenReturn(new AgentResultValidator.ValidationResult(true, List.of()));
        when(validator.validate(boundResult))
                .thenReturn(new AgentResultValidator.ValidationResult(true, List.of()));
        when(evidenceGate.validate(boundResult))
                .thenReturn(new EvidenceValidationGate.ValidationResult(true, List.of()));

        var future = runtime.execute("TASK-1", "MISSION-1", "sales", "MARKET_DISCOVERY", "instrucción");

        assertEquals(boundResult, future.get());
        verify(ceoService, times(3)).executeAgentTask(eq("sales"), anyString(), anyString(), eq("MISSION-1"), eq("TASK-1"), anyString());
        verify(events, times(2)).publishTask(eq("EMPRESA_TASK_RETRY"), any(), any(), any(), any(), any());
    }

    @Test
    void resolvesADifferentRealModelPerAgent() throws Exception {

        var financeResult = agentResult("finance", "ok finance");
        var salesResult = agentResult("sales", "ok sales");

        when(companyMemory.agentModel("finance", "qwen3:8b")).thenReturn("llama3:8b");
        when(companyMemory.agentModel("sales", "qwen3:8b")).thenReturn("mistral:7b");

        when(ceoService.executeAgentTask(eq("finance"), anyString(), anyString(), eq("MISSION-1"), eq("TASK-1"), eq("llama3:8b")))
                .thenReturn(new AgentTaskOutcome(financeResult, List.of()));
        when(ceoService.executeAgentTask(eq("sales"), anyString(), anyString(), eq("MISSION-1"), eq("TASK-1"), eq("mistral:7b")))
                .thenReturn(new AgentTaskOutcome(salesResult, List.of()));

        when(validator.validate(financeResult)).thenReturn(new AgentResultValidator.ValidationResult(true, List.of()));
        when(validator.validate(salesResult)).thenReturn(new AgentResultValidator.ValidationResult(true, List.of()));
        when(evidenceGate.validate(financeResult)).thenReturn(new EvidenceValidationGate.ValidationResult(true, List.of()));
        when(evidenceGate.validate(salesResult)).thenReturn(new EvidenceValidationGate.ValidationResult(true, List.of()));

        runtime.execute("TASK-1", "MISSION-1", "finance", "UNIT_ECONOMICS", "instrucción").get();
        runtime.execute("TASK-1", "MISSION-1", "sales", "MARKET_DISCOVERY", "instrucción").get();

        verify(ceoService).executeAgentTask(eq("finance"), anyString(), anyString(), eq("MISSION-1"), eq("TASK-1"), eq("llama3:8b"));
        verify(ceoService).executeAgentTask(eq("sales"), anyString(), anyString(), eq("MISSION-1"), eq("TASK-1"), eq("mistral:7b"));
    }

    private AgentTaskOutcome outcome(AgentResult result) {
        return new AgentTaskOutcome(result, List.of());
    }

    private AgentTaskOutcome outcome(AgentResult result, List<String> confirmedEvidenceUrls) {
        return new AgentTaskOutcome(result, confirmedEvidenceUrls);
    }

    private AgentResult agentResult(String agent, String recommendation) {
        return new AgentResult(
                agent, "ACTION", "NOT_VALIDATED",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                recommendation, 0.5
        );
    }

    private AgentResult agentResultWithEvidence(
            String agent, String recommendation, List<AgentResult.Evidence> evidence) {
        return new AgentResult(
                agent, "ACTION", "NOT_VALIDATED",
                List.of(), List.of(), List.of(), evidence, List.of(), List.of(), List.of(),
                recommendation, 0.5
        );
    }
}
