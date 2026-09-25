package com.aicompany.core.service;

import com.aicompany.core.agent.model.TeamPlan;
import com.aicompany.core.agent.model.TeamPlan.PlannedTask;
import com.aicompany.core.agent.validation.TeamPlanValidator;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.TeamExecutionMode;
import com.aicompany.core.model.TeamMemberInfo;
import com.aicompany.core.model.TeamSnapshot;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TeamWorkPlannerTest {

    private final TeamMemoryService teamMemory = mock(TeamMemoryService.class);
    private final CeoService ceoService = mock(CeoService.class);
    private final CompanyMemoryService companyMemory = mock(CompanyMemoryService.class);
    private final PromptMemoryService promptMemory = mock(PromptMemoryService.class);
    private final MissionMemoryService memory = mock(MissionMemoryService.class);
    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);

    private final TeamWorkPlanner planner = new TeamWorkPlanner(
            teamMemory, ceoService, companyMemory, promptMemory, memory, events,
            new TeamPlanValidator(), JsonMapper.builder().build(), "qwen3:8b");

    {
        when(companyMemory.agentModel(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(promptMemory.activePrompt(anyString())).thenReturn("");
    }

    private static TeamSnapshot marketing(String status) {
        return new TeamSnapshot("TEAM-MARKETING-GROWTH", "Marketing & Growth", status, "growth-content", List.of(
                new TeamMemberInfo("growth-content", "Kira", "Growth", "GROWTH_CONTENT_COMMUNITY", List.of("SEO", "growth"), "qwen3:8b"),
                new TeamMemberInfo("community", "Nora", "Community", "COMMUNITY_MANAGER", List.of("Discord"), "qwen3:8b")));
    }

    private static TeamPlan validPlan() {
        return new TeamPlan("Plan de lanzamiento", "", "", List.of(
                new PlannedTask("growth-content", "WORK", "SEO_PLAN", "Plan SEO", List.of("SEO"), List.of())));
    }

    private static TeamPlan planWithOutsider() {
        return new TeamPlan("Plan", "", "", List.of(
                new PlannedTask("finance", "WORK", "COSTS", "Costos", List.of("finanzas"), List.of())));
    }

    @Test
    void returnsAValidPlanAndPersistsItAsTheLeadersPlanningTask() {
        when(teamMemory.snapshot("TEAM-MARKETING-GROWTH")).thenReturn(marketing("ACTIVE"));
        when(ceoService.planTeamWork(eq("growth-content"), anyString(), anyString(), eq("qwen3:8b"))).thenReturn(validPlan());

        var result = planner.plan("MISSION-5", "TEAM-MARKETING-GROWTH", "Lanzar el juego", TeamExecutionMode.ANALYSIS);

        assertEquals("growth-content", result.team().leaderAgentId());
        assertEquals(1, result.plan().tasksOrEmpty().size());
        verify(memory).createTask("MISSION-5-GROWTH-CONTENT-PLAN", "MISSION-5", "growth-content", "TEAM_PLANNING", "PLANNING");
        verify(memory).updateTask(eq("MISSION-5-GROWTH-CONTENT-PLAN"), eq("COMPLETED"), anyString());
        verify(events).publish(eq("EMPRESA_TEAM_PLAN_CREATED"), eq("MISSION-5"), anyString(), eq("growth-content"), anyMap());
    }

    @Test
    void theRosterWithRealCapabilitiesGoesIntoThePrompt() {
        when(teamMemory.snapshot("TEAM-MARKETING-GROWTH")).thenReturn(marketing("ACTIVE"));
        var prompt = ArgumentCaptor.forClass(String.class);
        when(ceoService.planTeamWork(eq("growth-content"), prompt.capture(), anyString(), anyString())).thenReturn(validPlan());

        planner.plan("MISSION-5", "TEAM-MARKETING-GROWTH", "Lanzar el juego", TeamExecutionMode.ANALYSIS);

        assertTrue(prompt.getValue().contains("agentId=community"));
        assertTrue(prompt.getValue().contains("Discord"));
        assertTrue(prompt.getValue().contains("Lanzar el juego"));
    }

    @Test
    void retriesWithTheExactValidationErrorsAsCorrection() {
        when(teamMemory.snapshot("TEAM-MARKETING-GROWTH")).thenReturn(marketing("ACTIVE"));
        var prompt = ArgumentCaptor.forClass(String.class);
        when(ceoService.planTeamWork(eq("growth-content"), prompt.capture(), anyString(), anyString()))
                .thenReturn(planWithOutsider())
                .thenReturn(validPlan());

        planner.plan("MISSION-5", "TEAM-MARKETING-GROWTH", "Lanzar el juego", TeamExecutionMode.ANALYSIS);

        var second = prompt.getAllValues().get(1);
        assertTrue(second.contains("CORRECCIÓN DEL INTENTO ANTERIOR"));
        assertTrue(second.contains("finance"));
        verify(events).publish(eq("EMPRESA_TEAM_PLAN_REJECTED"), eq("MISSION-5"), anyString(), eq("growth-content"), anyMap());
    }

    @Test
    void failsAfterThreeInvalidPlansAndReturnsTheLeaderToIdle() {
        when(teamMemory.snapshot("TEAM-MARKETING-GROWTH")).thenReturn(marketing("ACTIVE"));
        when(ceoService.planTeamWork(anyString(), anyString(), anyString(), anyString())).thenReturn(planWithOutsider());

        var ex = assertThrows(IllegalStateException.class,
                () -> planner.plan("MISSION-5", "TEAM-MARKETING-GROWTH", "x", TeamExecutionMode.ANALYSIS));

        assertTrue(ex.getMessage().contains("no produjo un plan válido"));
        verify(ceoService, times(3)).planTeamWork(anyString(), anyString(), anyString(), anyString());
        verify(memory).updateTask(eq("MISSION-5-GROWTH-CONTENT-PLAN"), eq("FAILED"), anyString());
        var inOrder = inOrder(memory);
        inOrder.verify(memory).setAgentStatus("growth-content", "WORKING");
        inOrder.verify(memory).setAgentStatus("growth-content", "IDLE");
    }

    @Test
    void anUnparseableResponseCountsAsARejectedAttempt() {
        when(teamMemory.snapshot("TEAM-MARKETING-GROWTH")).thenReturn(marketing("ACTIVE"));
        when(ceoService.planTeamWork(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("JSON inválido"))
                .thenReturn(validPlan());

        var result = planner.plan("MISSION-5", "TEAM-MARKETING-GROWTH", "x", TeamExecutionMode.ANALYSIS);

        assertNotNull(result.plan());
        verify(ceoService, times(2)).planTeamWork(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void refusesAnInactiveTeamWithoutCallingTheModel() {
        when(teamMemory.snapshot("TEAM-MARKETING-GROWTH")).thenReturn(marketing("INACTIVE"));

        assertThrows(IllegalStateException.class,
                () -> planner.plan("MISSION-5", "TEAM-MARKETING-GROWTH", "x", TeamExecutionMode.ANALYSIS));
        verifyNoInteractions(ceoService);
    }
}
