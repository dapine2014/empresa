package com.aicompany.core.agent;

import com.aicompany.core.agent.model.AgentResult;
import com.aicompany.core.agent.validation.AgentResultValidator;
import com.aicompany.core.agent.validation.EvidenceValidationGate;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.service.CeoService;
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
    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    private final CeoService ceoService = mock(CeoService.class);
    private final AgentResultValidator validator = mock(AgentResultValidator.class);
    private final EvidenceValidationGate evidenceGate = mock(EvidenceValidationGate.class);
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private final AgentRuntime runtime = new AgentRuntime(
            ceoService, memory, Runnable::run, events, validator, evidenceGate, jsonMapper
    );

    @Test
    void succeedsOnFirstAttemptWithoutRetrying() throws Exception {
        var result = agentResult("finance", "recomendación ok");

        when(ceoService.executeAgentTask(eq("finance"), anyString())).thenReturn(result);
        when(validator.validate(result)).thenReturn(new AgentResultValidator.ValidationResult(true, List.of()));
        when(evidenceGate.validate(result)).thenReturn(new EvidenceValidationGate.ValidationResult(true, List.of()));

        var future = runtime.execute("TASK-1", "MISSION-1", "finance", "UNIT_ECONOMICS", "instrucción");

        assertEquals(result, future.get());
        verify(ceoService, times(1)).executeAgentTask(eq("finance"), anyString());
        verify(events, never()).publishTask(eq("EMPRESA_TASK_RETRY"), any(), any(), any(), any(), any());
        verify(memory).updateTask(eq("TASK-1"), eq("COMPLETED"), anyString());
    }

    @Test
    void retriesAfterValidatorRejectionAndIncludesCorrectionInNextPrompt() throws Exception {
        var badResult = agentResult("finance", "");
        var goodResult = agentResult("finance", "recomendación corregida");

        when(ceoService.executeAgentTask(eq("finance"), anyString()))
                .thenReturn(badResult)
                .thenReturn(goodResult);

        when(validator.validate(badResult))
                .thenReturn(new AgentResultValidator.ValidationResult(false, List.of("recommendation es obligatorio")));
        when(validator.validate(goodResult))
                .thenReturn(new AgentResultValidator.ValidationResult(true, List.of()));

        when(evidenceGate.validate(goodResult))
                .thenReturn(new EvidenceValidationGate.ValidationResult(true, List.of()));

        var future = runtime.execute("TASK-1", "MISSION-1", "finance", "UNIT_ECONOMICS", "instrucción");

        assertEquals(goodResult, future.get());

        var promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(ceoService, times(2)).executeAgentTask(eq("finance"), promptCaptor.capture());

        var prompts = promptCaptor.getAllValues();
        assertFalse(prompts.get(0).contains("CORRECCIÓN DEL INTENTO ANTERIOR"));
        assertTrue(prompts.get(1).contains("CORRECCIÓN DEL INTENTO ANTERIOR"));
        assertTrue(prompts.get(1).contains("recommendation es obligatorio"));

        verify(events, times(1)).publishTask(eq("EMPRESA_TASK_RETRY"), any(), any(), any(), any(), any());
    }

    @Test
    void retriesAfterUnparsableModelResponse() throws Exception {
        var goodResult = agentResult("engineering", "recomendación ok");

        when(ceoService.executeAgentTask(eq("engineering"), anyString()))
                .thenThrow(new IllegalStateException("El agente engineering no devolvió un AgentResult JSON válido."))
                .thenReturn(goodResult);

        when(validator.validate(goodResult))
                .thenReturn(new AgentResultValidator.ValidationResult(true, List.of()));
        when(evidenceGate.validate(goodResult))
                .thenReturn(new EvidenceValidationGate.ValidationResult(true, List.of()));

        var future = runtime.execute("TASK-1", "MISSION-1", "engineering", "DELIVERY_FEASIBILITY", "instrucción");

        assertEquals(goodResult, future.get());
        verify(ceoService, times(2)).executeAgentTask(eq("engineering"), anyString());
        verify(validator, times(1)).validate(any());
    }

    @Test
    void failsTaskAfterExhaustingAllRetriesOnValidatorRejection() {
        var badResult = agentResult("finance", "");

        when(ceoService.executeAgentTask(eq("finance"), anyString())).thenReturn(badResult);
        when(validator.validate(badResult))
                .thenReturn(new AgentResultValidator.ValidationResult(false, List.of("recommendation es obligatorio")));

        var future = runtime.execute("TASK-1", "MISSION-1", "finance", "UNIT_ECONOMICS", "instrucción");

        var ex = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(IllegalStateException.class, ex.getCause());

        // MAX_RESULT_RETRIES=2 -> 3 intentos en total (0,1,2).
        verify(ceoService, times(3)).executeAgentTask(eq("finance"), anyString());
        verify(memory).updateTask(eq("TASK-1"), eq("FAILED"), anyString());
        verify(events).publishTask(eq("EMPRESA_TASK_FAILED"), any(), any(), any(), any(), any());
    }

    @Test
    void failsTaskAfterExhaustingAllRetriesOnUnparsableResponse() {
        when(ceoService.executeAgentTask(eq("finance"), anyString()))
                .thenThrow(new IllegalStateException("respuesta no parseable"));

        var future = runtime.execute("TASK-1", "MISSION-1", "finance", "UNIT_ECONOMICS", "instrucción");

        var ex = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(IllegalStateException.class, ex.getCause());

        verify(ceoService, times(3)).executeAgentTask(eq("finance"), anyString());
        verify(validator, never()).validate(any());
        verify(memory).updateTask(eq("TASK-1"), eq("FAILED"), anyString());
    }

    private AgentResult agentResult(String agent, String recommendation) {
        return new AgentResult(
                agent, "ACTION", "NOT_VALIDATED",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                recommendation, 0.5
        );
    }
}
