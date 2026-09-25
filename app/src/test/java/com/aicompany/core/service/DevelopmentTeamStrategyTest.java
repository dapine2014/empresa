package com.aicompany.core.service;

import com.aicompany.core.agent.DevelopmentRuntime;
import com.aicompany.core.agent.model.AgentResult;
import com.aicompany.core.agent.model.DevelopmentResult;
import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;
import com.aicompany.core.agent.model.StaticReviewResult;
import com.aicompany.core.agent.model.TeamPlan;
import com.aicompany.core.agent.model.TeamPlan.PlannedTask;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.*;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DevelopmentTeamStrategyTest {

    private static final String SHA_NEO = "1".repeat(40);
    private static final String SHA_MILA = "2".repeat(40);

    private final MissionMemoryService memory = mock(MissionMemoryService.class);
    private final DevelopmentRuntime runtime = mock(DevelopmentRuntime.class);
    private final DevelopmentWorkspaceService workspace = mock(DevelopmentWorkspaceService.class);
    private final StaticWorkspaceValidator validator = mock(StaticWorkspaceValidator.class);
    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    private final MissionProgress progress = mock(MissionProgress.class);

    private final DevelopmentTeamStrategy strategy = new DevelopmentTeamStrategy(
            memory, runtime, workspace, validator, events, JsonMapper.builder().build());

    private static TeamMissionContext context() {
        var team = new TeamSnapshot("TEAM-ENGINEERING", "Engineering Team", "ACTIVE", "engineering", List.of(
                new TeamMemberInfo("engineering", "Neo", "Arquitecto", "R", List.of("arquitectura backend"), "m"),
                new TeamMemberInfo("frontend-ui", "Mila", "UI", "R", List.of("Game UI"), "m"),
                new TeamMemberInfo("qa", "Vera", "QA", "R", List.of("QA"), "m")));
        var plan = new TeamPlan("Juego de navegador", "HTML5 + JS", "web/index.html", List.of(
                new PlannedTask("engineering", "WORK", "ARCHITECTURE", "Base", List.of("arquitectura backend"), List.of("web/index.html")),
                new PlannedTask("frontend-ui", "WORK", "GAME_UI", "HUD", List.of("Game UI"), List.of("web/ui")),
                new PlannedTask("qa", "VALIDATION", "STATIC_REVIEW", "Revisar", List.of("QA"), List.of())));
        return new TeamMissionContext("M-1", "crear un juego", team, plan);
    }

    private static DevelopmentResult dev(String path) {
        return new DevelopmentResult("resumen de " + path, List.of(new GeneratedFile(path, "contenido")));
    }

    private static StaticReviewResult cleanReview() {
        return new StaticReviewResult("NO_EVIDENT_ISSUES", List.of(), List.of(), "Coherente con el plan.",
                List.of("No se verificó la ejecución."),
                List.of(new AgentResult.Evidence("main", "workspace:M-1@" + SHA_MILA + "/web/ui/hud.js", "INTERNAL", true)));
    }

    private void stubHappyPath() throws Exception {
        when(runtime.generate(eq("M-1-ENGINEERING"), anyString(), anyString(), anyString(), anyList()))
                .thenReturn(CompletableFuture.completedFuture(dev("web/index.html")));
        when(runtime.generate(eq("M-1-FRONTEND-UI"), anyString(), anyString(), anyString(), anyList()))
                .thenReturn(CompletableFuture.completedFuture(dev("web/ui/hud.js")));
        when(workspace.missionWorkspace("M-1")).thenReturn(Path.of("/data/forjai-products/M-1"));
        when(workspace.commitAgentWork(eq("M-1"), eq("M-1-ENGINEERING"), eq("engineering"), eq("Neo"), any()))
                .thenReturn(new DevelopmentWorkspaceService.CommitRecord(SHA_NEO, List.of("web/index.html")));
        when(workspace.commitAgentWork(eq("M-1"), eq("M-1-FRONTEND-UI"), eq("frontend-ui"), eq("Mila"), any()))
                .thenReturn(new DevelopmentWorkspaceService.CommitRecord(SHA_MILA, List.of("web/ui/hud.js")));
        when(workspace.filesAtCommit(eq("M-1"), anyString())).thenReturn(List.of("web/index.html", "web/ui/hud.js"));
        when(workspace.readFileAtCommit(eq("M-1"), anyString(), anyString())).thenReturn("contenido");
        when(validator.validate(eq("M-1"), anyList(), eq("web/index.html"), anyList()))
                .thenReturn(List.of(StaticCheck.pass("ENTRY_POINT", "ok", null, List.of("web/index.html"))));
    }

    @Test
    void commitsOnePerAgentInPlanOrderAndRecordsTheArtifact() throws Exception {
        stubHappyPath();
        when(runtime.review(eq("M-1-QA"), eq("M-1"), eq("qa"), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        strategy.execute(context(), progress);

        InOrder inOrder = inOrder(workspace);
        inOrder.verify(workspace).commitAgentWork(eq("M-1"), eq("M-1-ENGINEERING"), eq("engineering"), eq("Neo"), any());
        inOrder.verify(workspace).commitAgentWork(eq("M-1"), eq("M-1-FRONTEND-UI"), eq("frontend-ui"), eq("Mila"), any());
        verify(memory).createTask("M-1-QA", "M-1", "qa", "STATIC_REVIEW", "VALIDATION");
        verify(memory).recordTaskArtifact("M-1-FRONTEND-UI", "/data/forjai-products/M-1", SHA_MILA, List.of("web/ui/hud.js"));
        verify(memory).updateTask(eq("M-1-FRONTEND-UI"), eq("COMPLETED"), anyString());
        verify(events).publish(eq("EMPRESA_TASK_COMMITTED"), eq("M-1"), eq("M-1-FRONTEND-UI"), eq("frontend-ui"), anyMap());
    }

    @Test
    void aCleanReviewIsStaticallyValidatedAndTheStateCitesRealShas() throws Exception {
        stubHappyPath();
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        var result = (TeamExecutionResult.Development) strategy.execute(context(), progress);

        verify(memory).recordStaticValidation(eq("M-1-QA"), eq("STATICALLY_VALIDATED"), anyString());
        verify(memory).recordEvidence(eq("M-1-QA"), eq("M-1"), eq("qa"), anyList());
        assertTrue(result.verifiableState().contains(SHA_NEO));
        assertTrue(result.verifiableState().contains(SHA_MILA));
        assertTrue(result.verifiableState().contains(
                "Esta fase no ejecuta código: no se puede afirmar que el juego compile, se ejecute o pase tests."));
        assertTrue(result.resultsForCeo().contains("STATICALLY_VALIDATED"));
    }

    @Test
    void aFailedCommitFailsOnlyThatTaskAndIsReported() throws Exception {
        stubHappyPath();
        when(workspace.commitAgentWork(eq("M-1"), eq("M-1-FRONTEND-UI"), anyString(), anyString(), any()))
                .thenThrow(new java.io.IOException("git commit falló"));
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        var result = (TeamExecutionResult.Development) strategy.execute(context(), progress);

        verify(memory).updateTask(eq("M-1-FRONTEND-UI"), eq("FAILED"), contains("sin commit"));
        verify(memory, never()).recordTaskArtifact(eq("M-1-FRONTEND-UI"), anyString(), anyString(), anyList());
        assertTrue(result.resultsForCeo().contains("AGENTES_FALLIDOS"));
    }

    @Test
    void noCommitAtAllFailsTheMissionAndTheValidationTask() throws Exception {
        stubHappyPath();
        when(workspace.commitAgentWork(anyString(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new java.io.IOException("sin git"));

        assertThrows(IllegalStateException.class, () -> strategy.execute(context(), progress));
        verify(memory).updateTask(eq("M-1-QA"), eq("FAILED"), anyString());
        verify(runtime, never()).review(anyString(), anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void aReviewThatNeverCompletesLeavesTheWorkUnvalidated() throws Exception {
        stubHappyPath();
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("reintentos agotados")));

        var result = (TeamExecutionResult.Development) strategy.execute(context(), progress);

        verify(memory).recordStaticValidation(eq("M-1-QA"), eq("UNVALIDATED"), anyString());
        assertTrue(result.verifiableState().contains("UNVALIDATED"));
    }

    // Review Focus: si el repo supera el tope, Vera recibe marcas explícitas, nunca un corte silencioso.
    @Test
    void theReviewContextMarksTruncatedAndOmittedFiles() {
        var contents = new LinkedHashMap<String, String>();
        contents.put("web/a.js", "a".repeat(50));
        contents.put("web/b.js", "b".repeat(50));
        contents.put("web/c.js", "c".repeat(50));

        var rendered = DevelopmentTeamStrategy.renderRepositoryContext(contents, 80, 40);

        assertTrue(rendered.contains("### web/a.js"));
        assertTrue(rendered.contains("[TRUNCADO"));
        assertTrue(rendered.contains("web/c.js (NO INCLUIDO"));
        assertFalse(rendered.contains("c".repeat(50)));
    }
}
