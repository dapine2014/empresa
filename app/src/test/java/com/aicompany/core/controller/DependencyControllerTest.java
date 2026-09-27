package com.aicompany.core.controller;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.DependencyRef;
import com.aicompany.core.service.DependencyMemoryService;
import com.aicompany.core.service.SandboxRunnerClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DependencyControllerTest {

    private final DependencyMemoryService memory = mock(DependencyMemoryService.class);
    private final SandboxRunnerClient runner = mock(SandboxRunnerClient.class);
    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    private final DependencyController controller = new DependencyController(memory, runner, events);

    private static final String ID = "NUGET:newtonsoft.json@12.0.1";
    private static final Map<String, Object> PENDING = Map.of("id", ID, "ecosystem", "NUGET", "name", "Newtonsoft.Json",
            "version", "12.0.1", "status", "PENDING_APPROVAL", "jobId", "fetch-7");

    @Test
    void approvingPromotesFromTheSavedJobAndThenRecordsTheFoundersDecision() {
        when(memory.find(ID)).thenReturn(Optional.of(PENDING));
        when(runner.promoteDependencies("NUGET", "fetch-7", List.of(new DependencyRef("NUGET", "Newtonsoft.Json", "12.0.1"))))
                .thenReturn(true);

        assertEquals(200, controller.approve(ID).getStatusCode().value());

        var inOrder = inOrder(runner, memory);
        inOrder.verify(runner).promoteDependencies(anyString(), anyString(), anyList());
        inOrder.verify(memory).decide(ID, "APPROVED", "founder");
        verify(events).publish(eq("EMPRESA_DEPENDENCY_APPROVED"), any(), any(), eq("human"), anyMap());
    }

    // Review Focus: si el staging ya no existe, la promoción falla y el estado no cambia.
    @Test
    void aFailedPromotionLeavesTheStatusUnchanged() {
        when(memory.find(ID)).thenReturn(Optional.of(PENDING));
        when(runner.promoteDependencies(anyString(), anyString(), anyList())).thenReturn(false);
        when(runner.lastError()).thenReturn("No existe el staging del job fetch-7.");

        var ex = assertThrows(IllegalStateException.class, () -> controller.approve(ID));

        assertTrue(ex.getMessage().contains("staging"), ex.getMessage());
        verify(memory, never()).decide(anyString(), anyString(), anyString());
    }

    @Test
    void rejectingNeverTouchesTheRunner() {
        when(memory.find(ID)).thenReturn(Optional.of(PENDING));

        assertEquals(200, controller.reject(ID).getStatusCode().value());

        verify(memory).decide(ID, "REJECTED", "founder");
        verifyNoInteractions(runner);
        verify(events).publish(eq("EMPRESA_DEPENDENCY_REJECTED"), any(), any(), eq("human"), anyMap());
    }

    @Test
    void anUnknownDependencyIs404() {
        when(memory.find("x")).thenReturn(Optional.empty());
        assertEquals(404, controller.approve("x").getStatusCode().value());
        assertEquals(404, controller.reject("x").getStatusCode().value());
    }
}
