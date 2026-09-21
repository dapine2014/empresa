package com.aicompany.core.agent;

import com.aicompany.core.agent.model.DevelopmentResult;
import com.aicompany.core.agent.validation.DevelopmentPathValidationGate;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.service.CeoService;
import com.aicompany.core.service.CompanyMemoryService;
import com.aicompany.core.service.MissionMemoryService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DevelopmentRuntimeTest {

    private final MissionMemoryService memory = mock(MissionMemoryService.class);
    private final CompanyMemoryService companyMemory = mock(CompanyMemoryService.class);
    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    private final CeoService ceoService = mock(CeoService.class);
    private final DevelopmentPathValidationGate pathGate = new DevelopmentPathValidationGate();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private final DevelopmentRuntime runtime = new DevelopmentRuntime(
            ceoService, memory, companyMemory, "qwen3:8b", Runnable::run, events, pathGate, jsonMapper
    );

    private DevelopmentResult devResult(String path) {
        return new DevelopmentResult(
                "resumen",
                List.of(new DevelopmentResult.GeneratedFile(path, "contenido"))
        );
    }

    @Test
    void succeedsOnFirstAttemptWithoutRetrying() throws Exception {

        when(companyMemory.agentModel("engineering", "qwen3:8b")).thenReturn("qwen3:8b");
        when(ceoService.generateDevelopmentArtifact(eq("engineering"), anyString(), eq("qwen3:8b")))
                .thenReturn(devResult("src/Program.cs"));

        var result = runtime.execute("TASK-1", "MISSION-1", "engineering", "ARCHITECTURE_DEVELOPMENT", "prompt").get();

        assertEquals("resumen", result.summary());
        verify(ceoService, times(1)).generateDevelopmentArtifact(eq("engineering"), anyString(), eq("qwen3:8b"));
        verify(memory).updateTask(eq("TASK-1"), eq("COMPLETED"), anyString());
    }

    @Test
    void retriesWhenThePathGateRejectsTheFirstAttempt() throws Exception {

        when(companyMemory.agentModel("engineering", "qwen3:8b")).thenReturn("qwen3:8b");
        when(ceoService.generateDevelopmentArtifact(eq("engineering"), anyString(), eq("qwen3:8b")))
                .thenReturn(devResult("../etc/passwd"))
                .thenReturn(devResult("src/Program.cs"));

        var result = runtime.execute("TASK-1", "MISSION-1", "engineering", "ARCHITECTURE_DEVELOPMENT", "prompt").get();

        assertEquals("src/Program.cs", result.files().get(0).path());
        verify(ceoService, times(2)).generateDevelopmentArtifact(eq("engineering"), anyString(), eq("qwen3:8b"));
    }

    @Test
    void failsTheTaskAfterExhaustingRetriesOnPersistentInvalidPaths() {

        when(companyMemory.agentModel("engineering", "qwen3:8b")).thenReturn("qwen3:8b");
        when(ceoService.generateDevelopmentArtifact(eq("engineering"), anyString(), eq("qwen3:8b")))
                .thenReturn(devResult("../etc/passwd"));

        var future = runtime.execute("TASK-1", "MISSION-1", "engineering", "ARCHITECTURE_DEVELOPMENT", "prompt");

        assertThrows(ExecutionException.class, future::get);
        verify(ceoService, times(3)).generateDevelopmentArtifact(eq("engineering"), anyString(), eq("qwen3:8b"));
        verify(memory).updateTask(eq("TASK-1"), eq("FAILED"), anyString());
    }

    @Test
    void alwaysReturnsAgentStatusToIdleEvenAfterExhaustingRetries() {

        when(companyMemory.agentModel("engineering", "qwen3:8b")).thenReturn("qwen3:8b");
        when(ceoService.generateDevelopmentArtifact(eq("engineering"), anyString(), eq("qwen3:8b")))
                .thenReturn(devResult("../etc/passwd"));

        var future = runtime.execute("TASK-1", "MISSION-1", "engineering", "ARCHITECTURE_DEVELOPMENT", "prompt");

        assertThrows(ExecutionException.class, future::get);

        var inOrder = inOrder(memory);
        inOrder.verify(memory).setAgentStatus("engineering", "WORKING");
        inOrder.verify(memory).setAgentStatus("engineering", "IDLE");
    }

    @Test
    void resolvesTheRealPerAgentModelBeforeCallingCeoService() throws Exception {

        when(companyMemory.agentModel("backend", "qwen3:8b")).thenReturn("llama3:8b");
        when(ceoService.generateDevelopmentArtifact(eq("backend"), anyString(), eq("llama3:8b")))
                .thenReturn(devResult("src/api.py"));

        runtime.execute("TASK-2", "MISSION-1", "backend", "BACKEND_DEVELOPMENT", "prompt").get();

        verify(ceoService).generateDevelopmentArtifact(eq("backend"), anyString(), eq("llama3:8b"));
    }
}
