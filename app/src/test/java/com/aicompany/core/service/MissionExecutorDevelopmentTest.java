package com.aicompany.core.service;

import com.aicompany.core.agent.DevelopmentRuntime;
import com.aicompany.core.agent.AgentRuntime;
import com.aicompany.core.agent.model.DevelopmentResult;
import com.aicompany.core.agent.validation.ContradictionDetector;
import com.aicompany.core.config.AppProperties;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.AgentTask;
import com.aicompany.core.model.MissionStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MissionExecutorDevelopmentTest {

    private final MissionMemoryService memory = mock(MissionMemoryService.class);
    private final AgentRuntime runtime = mock(AgentRuntime.class);
    private final DevelopmentRuntime developmentRuntime = mock(DevelopmentRuntime.class);
    private final DevelopmentWorkspaceService developmentWorkspace = mock(DevelopmentWorkspaceService.class);
    private final CeoService ceoService = mock(CeoService.class);
    private final CompanyMemoryService companyMemory = mock(CompanyMemoryService.class);
    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final ContradictionDetector contradictionDetector = mock(ContradictionDetector.class);
    private final AppProperties appProperties = new AppProperties("Forjai", 50.0, 60);
    private final OpportunityMemoryService opportunityMemory = mock(OpportunityMemoryService.class);
    private final AlertMailService alertMailService = mock(AlertMailService.class);

    private final MissionExecutor executor = new MissionExecutor(
            memory, runtime, ceoService, companyMemory, "qwen2.5-coder:14b", Runnable::run, events, jsonMapper,
            contradictionDetector, appProperties, opportunityMemory, alertMailService,
            developmentRuntime, developmentWorkspace
    );

    private DevelopmentResult devResult(String summary) {
        return new DevelopmentResult(summary, List.of(new DevelopmentResult.GeneratedFile("src/A.txt", "x")));
    }

    @Test
    void reachesCompletedWhenAllThreeDevelopmentAgentsSucceed() throws Exception {

        when(memory.tasks("MISSION-1")).thenReturn(List.of());

        when(developmentRuntime.execute(anyString(), eq("MISSION-1"), eq("engineering"), eq("ARCHITECTURE_DEVELOPMENT"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(devResult("arquitectura lista")));
        when(developmentRuntime.execute(anyString(), eq("MISSION-1"), eq("backend"), eq("BACKEND_DEVELOPMENT"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(devResult("backend listo")));
        when(developmentRuntime.execute(anyString(), eq("MISSION-1"), eq("frontend-ui"), eq("FRONTEND_DEVELOPMENT"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(devResult("frontend listo")));

        executor.executeDevelopmentAsync("MISSION-1", "instrucción real").get();

        verify(memory).updateMission(eq("MISSION-1"), eq(MissionStatus.COMPLETED), anyInt(), anyString(), anyString());
        verify(developmentWorkspace, times(3)).writeFiles(eq("MISSION-1"), anyString(), any());
        verify(developmentWorkspace).commitWorkspace(eq("MISSION-1"), anyString());
    }

    @Test
    void continuesWithPartialResultsWhenOneDevelopmentAgentFails() throws Exception {

        when(memory.tasks("MISSION-1")).thenReturn(List.of());

        when(developmentRuntime.execute(anyString(), eq("MISSION-1"), eq("engineering"), eq("ARCHITECTURE_DEVELOPMENT"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(devResult("arquitectura lista")));
        when(developmentRuntime.execute(anyString(), eq("MISSION-1"), eq("backend"), eq("BACKEND_DEVELOPMENT"), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("fallo real")));
        when(developmentRuntime.execute(anyString(), eq("MISSION-1"), eq("frontend-ui"), eq("FRONTEND_DEVELOPMENT"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(devResult("frontend listo")));

        executor.executeDevelopmentAsync("MISSION-1", "instrucción real").get();

        verify(memory).updateMission(eq("MISSION-1"), eq(MissionStatus.COMPLETED), anyInt(), anyString(), anyString());
        verify(developmentWorkspace, times(2)).writeFiles(eq("MISSION-1"), anyString(), any());
    }

    @Test
    void failsTheMissionWhenAllThreeDevelopmentAgentsFail() throws Exception {

        when(memory.tasks("MISSION-1")).thenReturn(List.of());

        when(developmentRuntime.execute(anyString(), eq("MISSION-1"), anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("fallo real")));

        executor.executeDevelopmentAsync("MISSION-1", "instrucción real").get();

        verify(memory).updateMission(eq("MISSION-1"), eq(MissionStatus.FAILED), anyInt(), anyString(), anyString());
        verify(developmentWorkspace, never()).commitWorkspace(anyString(), anyString());
    }

    @Test
    void includesCompletedDiscoveryTasksAsContextInTheDevelopmentPrompt() throws Exception {

        when(memory.tasks("MISSION-1")).thenReturn(List.of(
                new AgentTask("MISSION-1-PRODUCT", "MISSION-1", "product", "OFFER_DESIGN", "COMPLETED", "{\"recommendation\":\"oferta real\"}", Instant.now())
        ));

        when(developmentRuntime.execute(anyString(), eq("MISSION-1"), anyString(), anyString(), contains("oferta real")))
                .thenReturn(CompletableFuture.completedFuture(devResult("ok")));

        executor.executeDevelopmentAsync("MISSION-1", "instrucción real").get();

        verify(developmentRuntime, times(3)).execute(anyString(), eq("MISSION-1"), anyString(), anyString(), contains("oferta real"));
    }
}
