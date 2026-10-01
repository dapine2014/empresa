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
    private final DependencyService dependencies = mock(DependencyService.class);

    private final DevelopmentTeamStrategy strategy = new DevelopmentTeamStrategy(
            memory, runtime, workspace, validator, events, JsonMapper.builder().build(), sandbox, dependencies);

    private static TeamMissionContext context() {
        var team = new TeamSnapshot("TEAM-DEVELOPMENT", "Engineering Team", "ACTIVE", "engineering", List.of(
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
        when(dependencies.resolve(anyString(), anyString(), anyList()))
                .thenReturn(new DependencyService.Outcome(List.of(), List.of(), null));
        when(workspace.commitAgentWork(eq("M-1"), eq("M-1-SCAFFOLD"), eq("forjai"), eq("Forjai"), any()))
                .thenReturn(new DevelopmentWorkspaceService.CommitRecord("5".repeat(40), List.of("game/Game.csproj")));
        when(workspace.filesAtCommit(eq("M-1"), anyString())).thenReturn(List.of("web/index.html", "web/ui/hud.js"));
        when(workspace.filesAtCommit("M-1", "5".repeat(40))).thenReturn(List.of("game/Game.csproj", "Solution.sln"));
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
    void commitsOnePerAgentInLayerOrderAndRecordsTheArtifact() throws Exception {
        stubHappyPath();
        when(runtime.review(eq("M-1-QA"), eq("M-1"), eq("qa"), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        strategy.execute(context(), progress);

        // Decisión del fundador (revisión 3): por capas. Mila tiene APPLICATION y Neo GAME: Mila va primero.
        InOrder inOrder = inOrder(workspace);
        inOrder.verify(workspace).commitAgentWork(eq("M-1"), eq("M-1-FRONTEND-UI"), eq("frontend-ui"), eq("Mila"), any());
        inOrder.verify(workspace).commitAgentWork(eq("M-1"), eq("M-1-ENGINEERING"), eq("engineering"), eq("Neo"), any());
        verify(memory).createTask("M-1-QA", "M-1", "qa", "STATIC_REVIEW", "VALIDATION");
        verify(memory).recordTaskArtifact("M-1-FRONTEND-UI", "/data/forjai-products/M-1", SHA_MILA, List.of("web/ui/hud.js"));
        verify(memory).updateTask(eq("M-1-FRONTEND-UI"), eq("COMPLETED"), anyString());
        verify(events).publish(eq("EMPRESA_TASK_COMMITTED"), eq("M-1"), eq("M-1-FRONTEND-UI"), eq("frontend-ui"), anyMap());
    }

    // Spec 2026-10-01 §1: Vera escribe los tests (WORK) y revisa con una tarea propia.
    @Test
    void qaWithATestsTaskReviewsUnderItsOwnTaskId() throws Exception {
        stubHappyPath();
        when(runtime.generate(eq("M-1-QA"), anyString(), anyString(), anyString(), anyList(), anyList()))
                .thenReturn(CompletableFuture.completedFuture(dev("tests/Combate.Tests/CombateTests.cs")));
        when(workspace.commitAgentWork(eq("M-1"), eq("M-1-QA"), eq("qa"), eq("Vera"), any()))
                .thenReturn(new DevelopmentWorkspaceService.CommitRecord("7".repeat(40),
                        List.of("tests/Combate.Tests/CombateTests.cs")));
        when(runtime.review(eq("M-1-QA-REVIEW"), eq("M-1"), eq("qa"), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        var base = context();
        var tasks = new java.util.ArrayList<>(base.plan().tasksOrEmpty());
        tasks.add(2, new PlannedTask("qa", "WORK", "ACCEPTANCE_TESTS", "Tests de aceptación",
                List.of("tests"), List.of("tests/Combate.Tests")));
        var plan = new TeamPlan(base.plan().summary(), null, null, tasks, List.of(), base.plan().stackProfile(),
                base.plan().boundedContexts(), base.plan().ubiquitousLanguage());

        strategy.execute(new TeamMissionContext("M-1", "crear un juego", base.team(), plan), progress);

        verify(memory).createTask("M-1-QA", "M-1", "qa", "ACCEPTANCE_TESTS", "WORK");
        verify(memory).createTask("M-1-QA-REVIEW", "M-1", "qa", "STATIC_REVIEW", "VALIDATION");
        verify(runtime).review(eq("M-1-QA-REVIEW"), eq("M-1"), eq("qa"), anyString(), anyMap());
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
        // Verificado en vivo (MISSION-SANDBOX-VERIFY-7): Vera citaba archivos con el sha de otro commit y agotaba
        // los reintentos. HEAD contiene todos los archivos: se le da el sha literal.
        assertTrue(reviewPrompt.getValue().contains("\"workspace:M-1@" + SHA_NEO + "/<ruta>\""), reviewPrompt.getValue());
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

    // Verificado en vivo (MISSION-SANDBOX-VERIFY-2 a -4): los .csproj los genera Forjai antes que los agentes.
    // Rondas de evidencia (revisión 2026-09-27): mismo repositorio, sin scaffold nuevo, ids -R<n>.
    @Test
    void anEvidenceRoundDoesNotRecreateTheScaffoldAndUsesSuffixedTaskIds() throws Exception {
        var base = context();
        var roundOne = new TeamMissionContext(base.missionId(), base.instruction(), base.team(), base.plan(), 1);
        when(workspace.headSha("M-1")).thenReturn("a".repeat(40));
        when(workspace.missionWorkspace("M-1")).thenReturn(Path.of("/data/forjai-products/M-1"));
        when(runtime.generate(eq("M-1-ENGINEERING-R1"), anyString(), anyString(), anyString(), anyList(), anyList(), anyList()))
                .thenReturn(CompletableFuture.completedFuture(dev("web/index.html")));
        when(runtime.generate(eq("M-1-FRONTEND-UI-R1"), anyString(), anyString(), anyString(), anyList(), anyList(), anyList()))
                .thenReturn(CompletableFuture.completedFuture(dev("web/ui/hud.js")));
        when(workspace.commitAgentWork(eq("M-1"), eq("M-1-ENGINEERING-R1"), eq("engineering"), eq("Neo"), any()))
                .thenReturn(new DevelopmentWorkspaceService.CommitRecord(SHA_NEO, List.of("web/index.html")));
        when(workspace.commitAgentWork(eq("M-1"), eq("M-1-FRONTEND-UI-R1"), eq("frontend-ui"), eq("Mila"), any()))
                .thenReturn(new DevelopmentWorkspaceService.CommitRecord(SHA_MILA, List.of("web/ui/hud.js")));
        when(dependencies.resolve(anyString(), anyString(), anyList()))
                .thenReturn(new DependencyService.Outcome(List.of(), List.of(), null));
        when(workspace.filesAtCommit(eq("M-1"), anyString())).thenReturn(List.of("web/index.html", "web/ui/hud.js"));
        when(workspace.readFileAtCommit(eq("M-1"), anyString(), anyString())).thenReturn("contenido");
        when(validator.validate(eq("M-1"), anyList(), eq(StackProfile.GODOT_DOTNET_GAME), eq(List.of("Combate")), anyList()))
                .thenReturn(List.of(StaticCheck.pass("DDD_LAYERS", "ok", null, List.of())));
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        strategy.execute(roundOne, progress);

        verify(workspace, never()).commitAgentWork(any(), endsWith("-SCAFFOLD"), any(), any(), any());
        verify(memory).createTask("M-1-ENGINEERING-R1", "M-1", "engineering", "ARCHITECTURE", "WORK");
        verify(memory).createTask("M-1-QA-R1", "M-1", "qa", "STATIC_REVIEW", "VALIDATION");
        verify(workspace).commitAgentWork(eq("M-1"), eq("M-1-FRONTEND-UI-R1"), eq("frontend-ui"), eq("Mila"), any());
        // Verificado en vivo (MISSION-E2E-ENG ronda 1): exigir un archivo en cada ruta obligaba a Iris a reenviar todo
        // su código; la API terminó fallando. En una ronda lo no devuelto queda como está en el repositorio.
        verify(runtime).generate(eq("M-1-FRONTEND-UI-R1"), eq("M-1"), eq("frontend-ui"),
                argThat(p -> p.contains("devuelve solo los archivos que cambias")), anyList(), anyList(), eq(List.of()));
    }

    @Test
    void forjaiCommitsTheProjectScaffoldBeforeTheAgentsWork() throws Exception {
        stubHappyPath();
        var milaPrompt = ArgumentCaptor.forClass(String.class);
        when(runtime.generate(eq("M-1-FRONTEND-UI"), anyString(), anyString(), milaPrompt.capture(), anyList(), anyList()))
                .thenReturn(CompletableFuture.completedFuture(dev("src/Combate.Application/X.cs")));
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        var result = (TeamExecutionResult.Development) strategy.execute(context(), progress);

        var inOrder = inOrder(workspace, runtime);
        inOrder.verify(workspace).commitAgentWork(eq("M-1"), eq("M-1-SCAFFOLD"), eq("forjai"), eq("Forjai"),
                argThat(r -> r.files().stream().anyMatch(f -> f.path().equals("game/Game.csproj"))));
        inOrder.verify(runtime).generate(eq("M-1-FRONTEND-UI"), anyString(), anyString(), anyString(), anyList(), anyList());
        assertTrue(milaPrompt.getValue().contains("los genera Forjai"), milaPrompt.getValue());
        // Verificado en vivo (MISSION-SANDBOX-VERIFY-6): la regla vieja "nadie va a ejecutar este código" era falsa
        // con el sandbox y se filtró textual dentro de Tarea.cs.
        assertFalse(milaPrompt.getValue().contains("Nadie va a ejecutar este código"), milaPrompt.getValue());
        assertTrue(milaPrompt.getValue().contains("sandbox"), milaPrompt.getValue());
        assertTrue(milaPrompt.getValue().contains("src/Combate.Application/Combate.Application.csproj"));
        assertFalse(milaPrompt.getValue().contains("ARCHIVOS OBLIGATORIOS que te corresponden (el proyecto no compila ni "
                + "arranca sin ellos): [src/Combate.Application/Combate.Application.csproj]"));
        assertTrue(result.verifiableState().contains("Forjai (scaffold)"), result.verifiableState());
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

    // Decisión del fundador (revisión 3, tras MISSION-SANDBOX-VERIFY-1..8): en paralelo cada agente escribía
    // contra clases que nunca vio y el código no compilaba. Ahora se genera por capas y cada agente ve el código
    // ya commiteado (sin .csproj/.sln, que son de Forjai).
    @Test
    void eachAgentSeesTheCodeAlreadyCommittedByThePreviousLayers() throws Exception {
        stubHappyPath();
        var milaPrompt = ArgumentCaptor.forClass(String.class);
        var neoPrompt = ArgumentCaptor.forClass(String.class);
        when(runtime.generate(eq("M-1-FRONTEND-UI"), anyString(), anyString(), milaPrompt.capture(), anyList(), anyList()))
                .thenReturn(CompletableFuture.completedFuture(dev("web/ui/hud.js")));
        when(runtime.generate(eq("M-1-ENGINEERING"), anyString(), anyString(), neoPrompt.capture(), anyList(), anyList()))
                .thenReturn(CompletableFuture.completedFuture(dev("web/index.html")));
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        strategy.execute(context(), progress);

        var inOrder = inOrder(runtime, workspace);
        inOrder.verify(runtime).generate(eq("M-1-FRONTEND-UI"), anyString(), anyString(), anyString(), anyList(), anyList());
        inOrder.verify(workspace).commitAgentWork(eq("M-1"), eq("M-1-FRONTEND-UI"), anyString(), anyString(), any());
        inOrder.verify(runtime).generate(eq("M-1-ENGINEERING"), anyString(), anyString(), anyString(), anyList(), anyList());
        assertFalse(milaPrompt.getValue().contains("CÓDIGO YA ESCRITO POR EL EQUIPO"), milaPrompt.getValue());
        assertTrue(neoPrompt.getValue().contains("CÓDIGO YA ESCRITO POR EL EQUIPO"), neoPrompt.getValue());
        assertTrue(neoPrompt.getValue().contains("### web/ui/hud.js"), neoPrompt.getValue());
        assertFalse(neoPrompt.getValue().contains("### game/Game.csproj"), neoPrompt.getValue());
    }

    // Verificado en vivo (MISSION-SANDBOX-VERIFY-9): Solution.sln (de Forjai) no estaba en ningún ownedPath y
    // PATHS_WITHIN_OWNED fallaba. Los archivos del scaffold son rutas permitidas.
    @Test
    void theScaffoldFilesAreAllowedPaths() throws Exception {
        stubHappyPath();
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        strategy.execute(context(), progress);

        verify(validator).validate(eq("M-1"), anyList(), any(), anyList(),
                argThat(allowed -> allowed.contains("game/Game.csproj")));
    }

    // Verificado en vivo (MISSION-SANDBOX-VERIFY-9): Vera marcó MAJOR un .csproj que genera Forjai; eso no es
    // trabajo de un agente y no puede impedir VERIFIED.
    @Test
    void findingsOnFilesGeneratedByForjaiDoNotCount() throws Exception {
        stubHappyPath();
        var review = new StaticReviewResult("ISSUES_FOUND",
                List.of(new StaticReviewResult.Finding("game/Game.csproj", "MAJOR", "referencia indebida")),
                List.of(), "ok", List.of("x"), cleanReview().evidence());
        var reviewPrompt = ArgumentCaptor.forClass(String.class);
        when(runtime.review(anyString(), anyString(), anyString(), reviewPrompt.capture(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(review));

        strategy.execute(context(), progress);

        verify(memory).recordStaticValidation(eq("M-1-QA"), eq("VERIFIED"), anyString());
        assertTrue(reviewPrompt.getValue().contains("los genera Forjai"), reviewPrompt.getValue());
    }

    private static SandboxResult buildFailure(String path) {
        return new SandboxResult("FAIL", List.of(
                new SandboxResult.StepResult("restore", "PASS", 0, 1000, "", 0, 0),
                new SandboxResult.StepResult("build", "FAIL", 1, 2000,
                        "/work/" + path + "(3,5): error CS1002: ; expected [/work/x.csproj]\nBuild FAILED.", 0, 0),
                new SandboxResult.StepResult("test", "SKIPPED", 0, 0, "", 0, 0)));
    }

    // Ciclo de corrección mínimo (MISSION-SANDBOX-VERIFY-10: todo pasaba salvo una línea que no compilaba).
    @Test
    void aCompileErrorGoesBackToTheFileOwnerAndTheSandboxRunsAgain() throws Exception {
        stubHappyPath();
        var pass = Optional.of(new SandboxResult("PASS", List.of(
                new SandboxResult.StepResult("build", "PASS", 0, 1000, "", 0, 0),
                new SandboxResult.StepResult("test", "PASS", 0, 1000, "", 3, 0),
                new SandboxResult.StepResult("smoke", "PASS", 0, 1000, "", 0, 0))));
        when(sandbox.verify(eq("M-1"), anyString(), eq("GODOT_DOTNET_GAME")))
                .thenReturn(Optional.of(buildFailure("src/Combate.Application/X.cs")))
                .thenReturn(pass);
        var milaPrompts = ArgumentCaptor.forClass(String.class);
        when(runtime.generate(eq("M-1-FRONTEND-UI"), anyString(), anyString(), milaPrompts.capture(), anyList(), anyList()))
                .thenReturn(CompletableFuture.completedFuture(dev("src/Combate.Application/X.cs")));
        when(runtime.generate(eq("M-1-FRONTEND-UI"), anyString(), anyString(), milaPrompts.capture(), anyList(), anyList(), anyList()))
                .thenReturn(CompletableFuture.completedFuture(corrected("src/Combate.Application/X.cs")));
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        var result = (TeamExecutionResult.Development) strategy.execute(context(), progress);

        verify(sandbox, times(2)).verify(eq("M-1"), anyString(), eq("GODOT_DOTNET_GAME"));
        assertEquals(2, milaPrompts.getAllValues().size());
        var repair = milaPrompts.getAllValues().get(1);
        assertTrue(repair.contains("CORRECCIÓN DEL SANDBOX"), repair);
        assertTrue(repair.contains("src/Combate.Application/X.cs(3,5): CS1002: ; expected"), repair);
        verify(workspace, times(2)).commitAgentWork(eq("M-1"), eq("M-1-FRONTEND-UI"), anyString(), anyString(), any());
        verify(memory).recordStaticValidation(eq("M-1-QA"), eq("VERIFIED"), anyString());
        assertTrue(result.verifiableState().contains("Rondas de corrección: 1"), result.verifiableState());
    }

    private static DevelopmentResult corrected(String path) {
        return new DevelopmentResult("corregido", List.of(new GeneratedFile(path, "corregido")));
    }

    @Test
    void theCorrectionCycleStopsAfterTheMaximumRounds() throws Exception {
        stubHappyPath();
        when(runtime.generate(eq("M-1-FRONTEND-UI"), anyString(), anyString(), anyString(), anyList(), anyList(), anyList()))
                .thenReturn(CompletableFuture.completedFuture(corrected("src/Combate.Application/X.cs")));
        when(sandbox.verify(eq("M-1"), anyString(), eq("GODOT_DOTNET_GAME")))
                .thenReturn(Optional.of(buildFailure("src/Combate.Application/X.cs")));
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        strategy.execute(context(), progress);

        verify(sandbox, times(1 + DevelopmentTeamStrategy.MAX_REPAIR_ROUNDS))
                .verify(eq("M-1"), anyString(), eq("GODOT_DOTNET_GAME"));
        verify(memory).recordStaticValidation(eq("M-1-QA"), eq("FAILED"), anyString());
    }

    @Test
    void errorsInFilesWithoutAnOwnerAreNotRepaired() throws Exception {
        stubHappyPath();
        when(sandbox.verify(eq("M-1"), anyString(), eq("GODOT_DOTNET_GAME")))
                .thenReturn(Optional.of(buildFailure("src/Otro/X.cs")));
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        strategy.execute(context(), progress);

        verify(sandbox, times(1)).verify(eq("M-1"), anyString(), eq("GODOT_DOTNET_GAME"));
    }

    // Verificado en vivo (MISSION-SANDBOX-VERIFY-11): una corrección fallida dejaba la tarea en FAILED aunque su
    // commit seguía siendo válido.
    @Test
    void aFailedRepairKeepsThePreviousCommitAndTheTaskCompleted() throws Exception {
        stubHappyPath();
        when(sandbox.verify(eq("M-1"), anyString(), eq("GODOT_DOTNET_GAME")))
                .thenReturn(Optional.of(buildFailure("src/Combate.Application/X.cs")));
        when(runtime.generate(eq("M-1-FRONTEND-UI"), anyString(), anyString(), anyString(), anyList(), anyList()))
                .thenReturn(CompletableFuture.completedFuture(dev("src/Combate.Application/X.cs")));
        when(runtime.generate(eq("M-1-FRONTEND-UI"), anyString(), anyString(), anyString(), anyList(), anyList(), anyList()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("reintentos agotados")));
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        strategy.execute(context(), progress);

        var inOrder = inOrder(memory);
        inOrder.verify(memory).updateTask(eq("M-1-FRONTEND-UI"), eq("COMPLETED"), anyString());
        inOrder.verify(memory).updateTask(eq("M-1-FRONTEND-UI"), eq("COMPLETED"), contains("se conserva el commit"));
        verify(runtime).generate(eq("M-1-FRONTEND-UI"), anyString(), anyString(),
                contains("propias rutas o usa los tipos que sí existen"), anyList(), anyList(), eq(List.of("src/Combate.Application")));
    }

    // Verificado en vivo (MISSION-SANDBOX-VERIFY-12): CS0246 por un "using" faltante de un tipo que existe en
    // otra capa. Forjai lo corrige solo (commit propio) y verifica de nuevo sin gastar una llamada al modelo.
    @Test
    void aMissingUsingIsFixedByForjaiWithoutAskingTheAgent() throws Exception {
        stubHappyPath();
        when(workspace.filesAtCommit("M-1", SHA_NEO)).thenReturn(List.of(
                "src/Combate.Domain/Unidad.cs", "src/Combate.Application/X.cs"));
        when(workspace.readFileAtCommit("M-1", SHA_NEO, "src/Combate.Domain/Unidad.cs"))
                .thenReturn("namespace Combate.Domain;\npublic class Unidad { }\n");
        when(workspace.readFileAtCommit("M-1", SHA_NEO, "src/Combate.Application/X.cs"))
                .thenReturn("using System;\nnamespace Combate.Application;\npublic class X { Unidad u; }\n");
        when(workspace.commitAgentWork(eq("M-1"), eq("M-1-AUTOFIX"), eq("forjai"), eq("Forjai"), any()))
                .thenReturn(new DevelopmentWorkspaceService.CommitRecord("6".repeat(40), List.of("src/Combate.Application/X.cs")));
        var fail = new SandboxResult("FAIL", List.of(new SandboxResult.StepResult("build", "FAIL", 1, 1000,
                "/work/src/Combate.Application/X.cs(3,35): error CS0246: The type or namespace name 'Unidad' could not "
                        + "be found (are you missing a using directive or an assembly reference?) [/work/x.csproj]", 0, 0)));
        var pass = new SandboxResult("PASS", List.of(new SandboxResult.StepResult("test", "PASS", 0, 1000, "", 2, 0)));
        when(sandbox.verify(eq("M-1"), anyString(), eq("GODOT_DOTNET_GAME")))
                .thenReturn(Optional.of(fail))
                .thenReturn(Optional.of(pass));
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        var result = (TeamExecutionResult.Development) strategy.execute(context(), progress);

        verify(workspace).commitAgentWork(eq("M-1"), eq("M-1-AUTOFIX"), eq("forjai"), eq("Forjai"),
                argThat(r -> r.files().get(0).content().contains("using System;\nusing Combate.Domain;\n")));
        verify(sandbox).verify("M-1", "6".repeat(40), "GODOT_DOTNET_GAME");
        verify(runtime, never()).generate(anyString(), anyString(), anyString(), anyString(), anyList(), anyList(), anyList());
        verify(memory).recordStaticValidation(eq("M-1-QA"), eq("VERIFIED"), anyString());
        assertTrue(result.verifiableState().contains("Correcciones automáticas de Forjai: 1"), result.verifiableState());
    }

    // Verificado en vivo (MISSION-SANDBOX-VERIFY-14): quedaba un solo error y la corrección devolvió el archivo
    // idéntico. La corrección muestra la línea exacta y, si no cambia nada, se le repite dentro de la misma ronda.
    @Test
    void aRepairShowsTheFailingLineAndInsistsIfTheAgentChangesNothing() throws Exception {
        stubHappyPath();
        when(workspace.filesAtCommit("M-1", SHA_NEO)).thenReturn(List.of("src/Combate.Application/X.cs"));
        when(workspace.readFileAtCommit("M-1", SHA_NEO, "src/Combate.Application/X.cs"))
                .thenReturn("linea1\nlinea2\n    var t = lista._tareas;\n");
        when(sandbox.verify(eq("M-1"), anyString(), eq("GODOT_DOTNET_GAME")))
                .thenReturn(Optional.of(buildFailure("src/Combate.Application/X.cs")));
        var repairPrompts = ArgumentCaptor.forClass(String.class);
        when(runtime.generate(eq("M-1-FRONTEND-UI"), anyString(), anyString(), repairPrompts.capture(), anyList(), anyList(), anyList()))
                .thenReturn(CompletableFuture.completedFuture(new DevelopmentResult("igual", List.of(
                        new DevelopmentResult.GeneratedFile("src/Combate.Application/X.cs",
                                "linea1\nlinea2\n    var t = lista._tareas;\n")))))
                .thenReturn(CompletableFuture.completedFuture(new DevelopmentResult("cambiado", List.of(
                        new DevelopmentResult.GeneratedFile("src/Combate.Application/X.cs", "arreglado\n")))));
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        strategy.execute(context(), progress);

        var first = repairPrompts.getAllValues().get(0);
        assertTrue(first.contains("línea 3: var t = lista._tareas;"), first);
        var second = repairPrompts.getAllValues().get(1);
        assertTrue(second.contains("devolviste tus archivos SIN CAMBIOS"), second);
    }

    // Verificado en vivo (MISSION-SANDBOX-VERIFY-15): CS0102 señalaba la propiedad Estado, pero el conflicto era un
    // enum Estado anidado 26 líneas más abajo que Neo nunca vio. Se muestran todas las definiciones del nombre.
    @Test
    void aDuplicateDefinitionShowsEveryDeclarationOfTheName() throws Exception {
        stubHappyPath();
        when(workspace.filesAtCommit("M-1", SHA_NEO)).thenReturn(List.of("src/Combate.Application/X.cs"));
        when(workspace.readFileAtCommit("M-1", SHA_NEO, "src/Combate.Application/X.cs"))
                .thenReturn("class Tarea {\n  public Estado Estado { get; }\n  void M() { }\n  public enum Estado { A }\n}\n");
        var fail = new SandboxResult("FAIL", List.of(new SandboxResult.StepResult("build", "FAIL", 1, 1000,
                "/work/src/Combate.Application/X.cs(2,17): error CS0102: The type 'Tarea' already contains a definition "
                        + "for 'Estado' [/work/x.csproj]", 0, 0)));
        when(sandbox.verify(eq("M-1"), anyString(), eq("GODOT_DOTNET_GAME"))).thenReturn(Optional.of(fail));
        var repairPrompts = ArgumentCaptor.forClass(String.class);
        when(runtime.generate(eq("M-1-FRONTEND-UI"), anyString(), anyString(), repairPrompts.capture(), anyList(), anyList(), anyList()))
                .thenReturn(CompletableFuture.completedFuture(corrected("src/Combate.Application/X.cs")));
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        strategy.execute(context(), progress);

        var prompt = repairPrompts.getAllValues().get(0);
        assertTrue(prompt.contains("otras definiciones de 'Estado'"), prompt);
        assertTrue(prompt.contains("línea 4: public enum Estado { A }"), prompt);
    }

    // Verificado en vivo (MISSION-SANDBOX-VERIFY-16): Neo devolvió el archivo con errores sin cambios y otro suyo
    // también sin cambios; la comparación solo miraba el primero y git fallaba con "nothing to commit".
    @Test
    void unchangedDetectionComparesEveryReturnedFileAgainstHead() throws Exception {
        stubHappyPath();
        when(workspace.filesAtCommit("M-1", SHA_NEO)).thenReturn(List.of(
                "src/Combate.Application/X.cs", "src/Combate.Application/Y.cs"));
        when(workspace.readFileAtCommit("M-1", SHA_NEO, "src/Combate.Application/X.cs")).thenReturn("x\n");
        when(workspace.readFileAtCommit("M-1", SHA_NEO, "src/Combate.Application/Y.cs")).thenReturn("y\n");
        when(sandbox.verify(eq("M-1"), anyString(), eq("GODOT_DOTNET_GAME")))
                .thenReturn(Optional.of(buildFailure("src/Combate.Application/X.cs")));
        var repairPrompts = ArgumentCaptor.forClass(String.class);
        when(runtime.generate(eq("M-1-FRONTEND-UI"), anyString(), anyString(), repairPrompts.capture(), anyList(), anyList(), anyList()))
                .thenReturn(CompletableFuture.completedFuture(new DevelopmentResult("igual", List.of(
                        new GeneratedFile("src/Combate.Application/X.cs", "x\n"),
                        new GeneratedFile("src/Combate.Application/Y.cs", "y\n")))));
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        strategy.execute(context(), progress);

        assertTrue(repairPrompts.getAllValues().get(1).contains("SIN CAMBIOS"), repairPrompts.getAllValues().get(1));
    }

    // Parte 3: los paquetes pedidos por un agente .NET entran al .csproj de su capa (commit de Forjai) antes de verificar.
    @Test
    void requestedNugetPackagesAreAddedToTheScaffoldBeforeVerifying() throws Exception {
        stubHappyPath();
        when(runtime.generate(eq("M-1-FRONTEND-UI"), anyString(), anyString(), anyString(), anyList(), anyList()))
                .thenReturn(CompletableFuture.completedFuture(new DevelopmentResult("r",
                        List.of(new GeneratedFile("src/Combate.Application/X.cs", "x")),
                        List.of(new DevelopmentResult.PackageRequest("Newtonsoft.Json", "13.0.3")))));
        when(workspace.commitAgentWork(eq("M-1"), eq("M-1-DEPENDENCIES"), eq("forjai"), eq("Forjai"), any()))
                .thenReturn(new DevelopmentWorkspaceService.CommitRecord("7".repeat(40),
                        List.of("src/Combate.Application/Combate.Application.csproj")));
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        strategy.execute(context(), progress);

        verify(workspace).commitAgentWork(eq("M-1"), eq("M-1-DEPENDENCIES"), eq("forjai"), eq("Forjai"),
                argThat(r -> r.files().size() == 1 && r.files().get(0).content()
                        .contains("<PackageReference Include=\"Newtonsoft.Json\" Version=\"13.0.3\" />")));
        verify(dependencies).resolve(eq("M-1"), eq("engineering"),
                eq(List.of(new DependencyRef("NUGET", "Newtonsoft.Json", "13.0.3"))));
        verify(sandbox).verify("M-1", "7".repeat(40), "GODOT_DOTNET_GAME");
    }

    @Test
    void pendingDependenciesSkipTheSandboxAndLeaveItUnvalidated() throws Exception {
        stubHappyPath();
        when(runtime.generate(eq("M-1-FRONTEND-UI"), anyString(), anyString(), anyString(), anyList(), anyList()))
                .thenReturn(CompletableFuture.completedFuture(new DevelopmentResult("r",
                        List.of(new GeneratedFile("src/Combate.Application/X.cs", "x")),
                        List.of(new DevelopmentResult.PackageRequest("A", "1.0.0")))));
        when(workspace.commitAgentWork(eq("M-1"), eq("M-1-DEPENDENCIES"), anyString(), anyString(), any()))
                .thenReturn(new DevelopmentWorkspaceService.CommitRecord("7".repeat(40), List.of("x.csproj")));
        when(dependencies.resolve(anyString(), anyString(), anyList()))
                .thenReturn(new DependencyService.Outcome(List.of(new DependencyRef("NUGET", "A", "1.0.0")), List.of(), null));
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        var result = (TeamExecutionResult.Development) strategy.execute(context(), progress);

        verify(sandbox, never()).verify(anyString(), anyString(), anyString());
        verify(memory).recordStaticValidation(eq("M-1-QA"), eq("UNVALIDATED"), anyString());
        assertTrue(result.verifiableState().contains("Dependencias pendientes de aprobación (🔴): [NUGET:a@1.0.0]"),
                result.verifiableState());
    }

    @Test
    void flutterDependenciesComeFromThePubspec() {
        var refs = DevelopmentTeamStrategy.dependencyRefs(StackProfile.FLUTTER_WEB_APP, List.of(),
                "dependencies:\n  flutter:\n    sdk: flutter\n  equatable: 2.0.5\n");
        assertEquals(List.of(new DependencyRef("PUB", "equatable", "2.0.5")), refs);
        assertEquals(List.of(new DependencyRef("NUGET", "A", "1.0.0")), DevelopmentTeamStrategy.dependencyRefs(
                StackProfile.DOTNET_APP, List.of(new DevelopmentResult.PackageRequest("A", "1.0.0")), null));
    }

    // Verificado en vivo (MISSION-DEPS-VERIFY-4): Iris (Application + Infrastructure) pidió Newtonsoft y Forjai lo
    // agregó solo al primer .csproj; lo usaba en Infrastructure. Va a todos los proyectos de las capas del agente.
    @Test
    void requestedPackagesGoToEveryProjectOfTheAgentsLayers() {
        var projects = DevelopmentTeamStrategy.projectsOf(StackProfile.DOTNET_APP, List.of("Tareas"),
                List.of("src/Tareas.Application", "src/Tareas.Infrastructure"));
        assertEquals(List.of("src/Tareas.Application/Tareas.Application.csproj",
                "src/Tareas.Infrastructure/Tareas.Infrastructure.csproj"), projects);
    }

    // Verificado en vivo (MISSION-DEPS-VERIFY-4): con kimi-k3, 24.000 caracteres dejaban archivos fuera y Vera marcaba
    // MAJOR "no pude verificar". El presupuesto depende del modelo del validador (remoto: contexto grande).
    @Test
    void theReviewBudgetDependsOnTheValidatorsModel() {
        assertEquals(DevelopmentTeamStrategy.REVIEW_TOTAL_BUDGET_CHARS, DevelopmentTeamStrategy.reviewBudget("qwen3:8b").total());
        assertTrue(DevelopmentTeamStrategy.reviewBudget("nvidia:moonshotai/kimi-k3").total() >= 100_000);
    }

    // Verificado en vivo (MISSION-ORQ-1790736885126): 92K de código contra un tope de 18K; con un modelo remoto el
    // código previo usa el mismo presupuesto grande que la revisión, y el contrato de API llega siempre completo.
    @Test
    void theGenerationBudgetFollowsTheAgentsModel() {
        assertEquals(DevelopmentTeamStrategy.EXISTING_CODE_TOTAL_BUDGET_CHARS,
                DevelopmentTeamStrategy.codeBudget("qwen3-coder:30b").total());
        assertTrue(DevelopmentTeamStrategy.codeBudget("nvidia:moonshotai/kimi-k3").total() >= 100_000);
    }

    @Test
    void theApiContractSurvivesWhenBodiesAreTruncated() {
        var contents = new java.util.LinkedHashMap<String, String>();
        contents.put("src/Firmas.Domain/ResultadoValidacion.cs", "namespace Firmas.Domain;\n\n"
                + "public sealed class ResultadoValidacion\n{\n" + "    // relleno\n".repeat(200)
                + "    public static ResultadoValidacion ConErrores(IEnumerable<string> errores) => new();\n}\n");

        var rendered = DevelopmentTeamStrategy.renderWithContract(contents, 300, 200);

        assertTrue(rendered.contains("API PÚBLICA"), rendered);
        assertTrue(rendered.contains("public static ResultadoValidacion ConErrores(IEnumerable<string> errores)"), rendered);
        assertTrue(rendered.contains("TRUNCADO"), rendered);
        assertTrue(rendered.indexOf("API PÚBLICA") < rendered.indexOf("TRUNCADO"), rendered);
    }
}
