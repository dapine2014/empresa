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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
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
    private final SandboxRunnerClient sandbox = mock(SandboxRunnerClient.class);

    private final DevelopmentTeamStrategy strategy = new DevelopmentTeamStrategy(
            memory, runtime, workspace, validator, events, JsonMapper.builder().build(), sandbox);

    private static TeamMissionContext context() {
        var team = new TeamSnapshot("TEAM-ENGINEERING", "Engineering Team", "ACTIVE", "engineering", List.of(
                new TeamMemberInfo("engineering", "Neo", "Arquitecto", "R", List.of("arquitectura backend"), "m"),
                new TeamMemberInfo("frontend-ui", "Mila", "UI", "R", List.of("Game UI"), "m"),
                new TeamMemberInfo("qa", "Vera", "QA", "R", List.of("QA"), "m")));
        var plan = new TeamPlan("Juego de combate", null, null, List.of(
                new PlannedTask("engineering", "WORK", "ARCHITECTURE", "Base", List.of("arquitectura backend"), List.of("Juego.sln", "game")),
                new PlannedTask("frontend-ui", "WORK", "GAME_UI", "HUD", List.of("Game UI"), List.of("src/Combate.Application")),
                new PlannedTask("qa", "VALIDATION", "STATIC_REVIEW", "Revisar", List.of("QA"), List.of())),
                List.of(), "GODOT_DOTNET_GAME",
                List.of(new TeamPlan.BoundedContext("Combate", "Combate por turnos")),
                List.of(new TeamPlan.GlossaryTerm("Unidad", "Personaje"),
                        new TeamPlan.GlossaryTerm("Turno", "Momento de acción"),
                        new TeamPlan.GlossaryTerm("Daño", "Vida que resta un ataque")));
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
        when(runtime.generate(eq("M-1-ENGINEERING"), anyString(), anyString(), anyString(), anyList(), anyList()))
                .thenReturn(CompletableFuture.completedFuture(dev("web/index.html")));
        when(runtime.generate(eq("M-1-FRONTEND-UI"), anyString(), anyString(), anyString(), anyList(), anyList()))
                .thenReturn(CompletableFuture.completedFuture(dev("web/ui/hud.js")));
        when(workspace.missionWorkspace("M-1")).thenReturn(Path.of("/data/forjai-products/M-1"));
        when(workspace.commitAgentWork(eq("M-1"), eq("M-1-ENGINEERING"), eq("engineering"), eq("Neo"), any()))
                .thenReturn(new DevelopmentWorkspaceService.CommitRecord(SHA_NEO, List.of("web/index.html")));
        when(workspace.commitAgentWork(eq("M-1"), eq("M-1-FRONTEND-UI"), eq("frontend-ui"), eq("Mila"), any()))
                .thenReturn(new DevelopmentWorkspaceService.CommitRecord(SHA_MILA, List.of("web/ui/hud.js")));
        when(workspace.filesAtCommit(eq("M-1"), anyString())).thenReturn(List.of("web/index.html", "web/ui/hud.js"));
        when(workspace.readFileAtCommit(eq("M-1"), anyString(), anyString())).thenReturn("contenido");
        when(validator.validate(eq("M-1"), anyList(), eq(StackProfile.GODOT_DOTNET_GAME), eq(List.of("Combate")), anyList()))
                .thenReturn(List.of(StaticCheck.pass("DDD_LAYERS", "ok", null, List.of())));
        when(sandbox.verify(eq("M-1"), anyString(), eq("GODOT_DOTNET_GAME"))).thenReturn(Optional.of(
                new SandboxResult("PASS", List.of(
                        new SandboxResult.StepResult("build", "PASS", 0, 48000, "", 0, 0),
                        new SandboxResult.StepResult("test", "PASS", 0, 12000, "", 12, 0),
                        new SandboxResult.StepResult("smoke", "PASS", 0, 9000, "", 0, 0)))));
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
    void aCleanReviewWithAPassingSandboxCitesRealShas() throws Exception {
        stubHappyPath();
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        var result = (TeamExecutionResult.Development) strategy.execute(context(), progress);

        verify(memory).recordEvidence(eq("M-1-QA"), eq("M-1"), eq("qa"), anyList());
        assertTrue(result.verifiableState().contains(SHA_NEO));
        assertTrue(result.verifiableState().contains(SHA_MILA));
        assertTrue(result.resultsForCeo().contains("VERIFIED"));
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

    @Test
    void workAndReviewPromptsCarryTheProfileContextsAndGlossary() throws Exception {
        stubHappyPath();
        var workPrompt = ArgumentCaptor.forClass(String.class);
        when(runtime.generate(eq("M-1-FRONTEND-UI"), anyString(), anyString(), workPrompt.capture(), anyList(), anyList()))
                .thenReturn(CompletableFuture.completedFuture(dev("src/Combate.Application/Atacar.cs")));
        var reviewPrompt = ArgumentCaptor.forClass(String.class);
        when(runtime.review(anyString(), anyString(), anyString(), reviewPrompt.capture(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        var result = (TeamExecutionResult.Development) strategy.execute(context(), progress);

        assertTrue(workPrompt.getValue().contains("GODOT_DOTNET_GAME"));
        assertTrue(workPrompt.getValue().contains("Combate"));
        assertTrue(workPrompt.getValue().contains("Unidad: Personaje"));
        assertTrue(reviewPrompt.getValue().contains("lenguaje ubicuo"));
        assertTrue(reviewPrompt.getValue().contains("anémico"));
        assertTrue(result.resultsForCeo().contains("GODOT_DOTNET_GAME"));
    }

    // Verificado en vivo (MISSION-DDD-VERIFY-6): la dueña de "game" no creó game/project.godot porque nadie le
    // dijo que era obligatorio. El prompt de cada tarea lista los archivos de entrada que caen en sus rutas.
    @Test
    void theOwnerOfAnEntryFilesFolderIsToldToCreateIt() throws Exception {
        stubHappyPath();
        var neoPrompt = ArgumentCaptor.forClass(String.class);
        when(runtime.generate(eq("M-1-ENGINEERING"), anyString(), anyString(), neoPrompt.capture(), anyList(), anyList()))
                .thenReturn(CompletableFuture.completedFuture(dev("game/project.godot")));
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        strategy.execute(context(), progress);

        assertTrue(neoPrompt.getValue().contains("ARCHIVOS OBLIGATORIOS"), neoPrompt.getValue());
        assertTrue(neoPrompt.getValue().contains("game/project.godot"), neoPrompt.getValue());
    }

    // Verificado en vivo (MISSION-DDD-VERIFY-6): la lectura del repo falló antes de la revisión y la tarea de Vera
    // quedó en PENDING para siempre.
    @Test
    void aFailureBeforeTheReviewMarksTheValidationTaskFailed() throws Exception {
        stubHappyPath();
        when(workspace.filesAtCommit(eq("M-1"), anyString())).thenThrow(new java.io.IOException("git show falló"));

        strategy.execute(context(), progress);

        verify(runtime, never()).review(anyString(), anyString(), anyString(), anyString(), anyMap());
        verify(memory).updateTask(eq("M-1-QA"), eq("FAILED"), contains("git show falló"));
    }

    @Test
    void aPassingSandboxAndCleanReviewIsVerified() throws Exception {
        stubHappyPath();
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        var result = (TeamExecutionResult.Development) strategy.execute(context(), progress);

        verify(memory).recordStaticValidation(eq("M-1-QA"), eq("VERIFIED"), anyString());
        verify(memory).recordSandboxResult(eq("M-1-QA"), anyString());
        assertTrue(result.verifiableState().contains("Tests PASS 12/12"));
        assertTrue(result.verifiableState().contains("Compiló, pasaron 12 tests y arrancó en el sandbox."));
    }

    @Test
    void theReviewReceivesTheRealSandboxResults() throws Exception {
        stubHappyPath();
        var reviewPrompt = ArgumentCaptor.forClass(String.class);
        when(runtime.review(anyString(), anyString(), anyString(), reviewPrompt.capture(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        strategy.execute(context(), progress);

        assertTrue(reviewPrompt.getValue().contains("RESULTADOS REALES DEL SANDBOX"));
    }

    // Review Focus: runner no disponible → UNVALIDATED con el motivo.
    @Test
    void anUnavailableRunnerLeavesTheWorkUnvalidated() throws Exception {
        stubHappyPath();
        when(sandbox.verify(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(sandbox.lastError()).thenReturn("sandbox-runner no disponible: Connection refused");
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        var result = (TeamExecutionResult.Development) strategy.execute(context(), progress);

        verify(memory).recordStaticValidation(eq("M-1-QA"), eq("UNVALIDATED"), anyString());
        assertTrue(result.verifiableState().contains("Connection refused"));
    }

    // Review Focus: si los chequeos deterministas fallaron, no se gasta tiempo compilando.
    @Test
    void failedDeterministicChecksSkipTheSandbox() throws Exception {
        stubHappyPath();
        when(validator.validate(eq("M-1"), anyList(), any(), anyList(), anyList()))
                .thenReturn(List.of(StaticCheck.fail("DDD_LAYERS", "violación", null, List.of())));
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        strategy.execute(context(), progress);

        verifyNoInteractions(sandbox);
    }

    // Verificado en vivo (MISSION-SANDBOX-VERIFY-2): ningún agente escribió su .csproj.
    @Test
    void eachLayerOwnerIsToldToWriteItsProjectFile() throws Exception {
        stubHappyPath();
        var milaPrompt = ArgumentCaptor.forClass(String.class);
        when(runtime.generate(eq("M-1-FRONTEND-UI"), anyString(), anyString(), milaPrompt.capture(), anyList(), anyList()))
                .thenReturn(CompletableFuture.completedFuture(dev("src/Combate.Application/X.cs")));
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        strategy.execute(context(), progress);

        assertTrue(milaPrompt.getValue().contains("src/Combate.Application/Combate.Application.csproj"),
                milaPrompt.getValue());
    }

    @Test
    void theExpectedProjectFilesReachTheGenerationGate() throws Exception {
        stubHappyPath();
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        strategy.execute(context(), progress);

        verify(runtime).generate(eq("M-1-FRONTEND-UI"), anyString(), anyString(), anyString(), anyList(),
                argThat(list -> list.contains("src/Combate.Domain/Combate.Domain.csproj")
                        && list.contains("game/Game.csproj")));
    }
}
