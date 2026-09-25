package com.aicompany.core.service;

import com.aicompany.core.agent.AgentRuntime;
import com.aicompany.core.agent.model.AgentResult;
import com.aicompany.core.agent.model.TeamPlan;
import com.aicompany.core.agent.model.TeamPlan.PlannedTask;
import com.aicompany.core.agent.validation.ContradictionDetector;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MissionExecutorTeamTest {

    private final MissionMemoryService memory = mock(MissionMemoryService.class);
    private final AgentRuntime runtime = mock(AgentRuntime.class);
    private final CeoService ceoService = mock(CeoService.class);
    private final CompanyMemoryService companyMemory = mock(CompanyMemoryService.class);
    private final PromptMemoryService promptMemory = mock(PromptMemoryService.class);
    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    private final ContradictionDetector contradictionDetector = mock(ContradictionDetector.class);
    private final CompanyPolicyService companyPolicyService = mock(CompanyPolicyService.class);
    private final OpportunityMemoryService opportunityMemory = mock(OpportunityMemoryService.class);
    private final AlertMailService alertMailService = mock(AlertMailService.class);
    private final TeamWorkPlanner planner = mock(TeamWorkPlanner.class);
    private final TeamExecutionStrategy analysis = mock(TeamExecutionStrategy.class);
    private final TeamExecutionStrategy development = mock(TeamExecutionStrategy.class);

    private final MissionExecutor executor = new MissionExecutor(
            memory, new AgentTaskBatchRunner(memory, runtime, events), ceoService, companyMemory, promptMemory,
            "qwen2.5-coder:14b", Runnable::run, events, JsonMapper.builder().build(), contradictionDetector,
            companyPolicyService, opportunityMemory, alertMailService, planner, List.of(analysis, development));

    {
        when(companyMemory.agentModel(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(promptMemory.activePrompt(anyString())).thenReturn("");
        when(companyPolicyService.activeValue(PolicyKey.SEED_CAPITAL_USD)).thenReturn(50.0);
        when(companyPolicyService.activeValue(PolicyKey.CONTRADICTION_SEED_CAPITAL_MULTIPLE)).thenReturn(100.0);
        when(analysis.mode()).thenReturn(TeamExecutionMode.ANALYSIS);
        when(development.mode()).thenReturn(TeamExecutionMode.DEVELOPMENT);
        when(ceoService.executeMission(anyString(), anyString(), anyString(), anyString())).thenReturn("consolidado");
    }

    private static TeamPlanResult planned(String teamId) {
        var team = new TeamSnapshot(teamId, "Equipo", "ACTIVE", "engineering", List.of(
                new TeamMemberInfo("engineering", "Neo", "rol", "ROLE", List.of("x"), "qwen3:8b")));
        var plan = new TeamPlan("plan", "HTML5", "web/index.html", List.of(
                new PlannedTask("engineering", "WORK", "BUILD", "Construir", List.of("x"), List.of("web"))));
        return new TeamPlanResult(team, plan);
    }

    @Test
    void anEngineeringMissionNeverCreatesDiscoveryTasks() throws Exception {
        when(memory.teamId("M-1")).thenReturn(Optional.of("TEAM-ENGINEERING"));
        when(planner.plan("M-1", "TEAM-ENGINEERING", "crear un juego", TeamExecutionMode.DEVELOPMENT))
                .thenReturn(planned("TEAM-ENGINEERING"));
        when(development.execute(any(), any()))
                .thenReturn(new TeamExecutionResult.Development("reporte", "ESTADO VERIFICABLE ..."));

        executor.executeAsync("M-1", "crear un juego").get();

        verifyNoInteractions(runtime);
        verify(memory, never()).createTask(anyString(), anyString(), eq("finance"), anyString());
        verify(memory, never()).createTask(anyString(), anyString(), eq("sales"), anyString());
        verify(memory, never()).createTask(anyString(), anyString(), eq("product"), anyString());
        verify(analysis, never()).execute(any(), any());
    }

    @Test
    void developmentResultsEndWithTheJavaGeneratedVerifiableState() throws Exception {
        when(memory.teamId("M-1")).thenReturn(Optional.of("TEAM-ENGINEERING"));
        when(planner.plan(anyString(), anyString(), anyString(), any())).thenReturn(planned("TEAM-ENGINEERING"));
        when(development.execute(any(), any()))
                .thenReturn(new TeamExecutionResult.Development("reporte", "ESTADO VERIFICABLE X"));

        executor.executeAsync("M-1", "crear un juego").get();

        verify(ceoService).executeMission(eq("crear un juego"), eq("reporte"), anyString(), anyString());
        verify(memory).updateMission(eq("M-1"), eq(MissionStatus.AWAITING_INVESTOR), anyInt(), anyString(),
                eq("consolidado\n\nESTADO VERIFICABLE X"));
    }

    @Test
    void anAnalysisTeamIsConsolidatedLikeDiscovery() throws Exception {
        when(memory.teamId("M-2")).thenReturn(Optional.of("TEAM-MARKETING-GROWTH"));
        when(planner.plan("M-2", "TEAM-MARKETING-GROWTH", "lanzar", TeamExecutionMode.ANALYSIS))
                .thenReturn(planned("TEAM-MARKETING-GROWTH"));
        var result = new AgentResult(
                "growth-content", "SEO_PLAN", "NOT_VALIDATED",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                "recomendación", 0.5);
        when(analysis.execute(any(), any())).thenReturn(new TeamExecutionResult.AgentOutcomes(
                List.of(AgentExecutionOutcome.success("growth-content", result))));
        when(contradictionDetector.detect(any(), anyDouble(), anyDouble())).thenReturn(List.of());

        executor.executeAsync("M-2", "lanzar").get();

        verify(development, never()).execute(any(), any());
        verify(memory).updateMission(eq("M-2"), eq(MissionStatus.AWAITING_INVESTOR), anyInt(), anyString(), eq("consolidado"));
    }

    @Test
    void anInvalidPlanFailsTheMission() throws Exception {
        when(memory.teamId("M-3")).thenReturn(Optional.of("TEAM-ENGINEERING"));
        when(planner.plan(anyString(), anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("El líder engineering no produjo un plan válido"));

        executor.executeAsync("M-3", "x").get();

        verify(memory).updateMission(eq("M-3"), eq(MissionStatus.FAILED), eq(100), anyString(),
                contains("no produjo un plan válido"));
    }

    @Test
    void aMissionWithoutTeamNeverCallsThePlanner() throws Exception {
        when(memory.teamId("M-4")).thenReturn(Optional.empty());

        executor.executeAsync("M-4", "x").get();

        verifyNoInteractions(planner);
    }
}
