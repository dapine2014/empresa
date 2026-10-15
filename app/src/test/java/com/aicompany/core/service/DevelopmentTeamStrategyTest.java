package com.aicompany.core.service;

import com.aicompany.core.agent.DevelopmentRuntime;
import com.aicompany.core.agent.model.AgentResult;
import com.aicompany.core.agent.model.DevelopmentResult;
import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;
import com.aicompany.core.agent.model.StaticReviewResult;
import com.aicompany.core.agent.model.TeamPlan;
import com.aicompany.core.agent.model.TeamPlan.PlannedTask;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.AppComponent;
import com.aicompany.core.model.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    {
        // Sin workspace previo (misión nueva) el flujo es el de siempre.
        when(workspace.headIfAny(anyString())).thenReturn(Optional.empty());
    }

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
        when(runtime.generate(eq("M-1-ENGINEERING"), anyString(), anyString(), anyString(), anyList(), anyList(), anyList(), any()))
                .thenReturn(CompletableFuture.completedFuture(dev("web/index.html")));
        when(runtime.generate(eq("M-1-FRONTEND-UI"), anyString(), anyString(), anyString(), anyList(), anyList(), anyList(), any()))
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
        when(validator.validate(eq("M-1"), anyList(), eq(List.of(new AppComponent(StackProfile.GODOT_DOTNET_GAME, ""))), eq(List.of("Combate")), anyList(), anyList()))
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
        when(runtime.generate(eq("M-1-QA"), anyString(), anyString(), anyString(), anyList(), anyList(), anyList(), any()))
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
        when(runtime.generate(eq("M-1-FRONTEND-UI"), anyString(), anyString(), workPrompt.capture(), anyList(), anyList(), anyList(), any()))
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
        when(runtime.generate(eq("M-1-ENGINEERING"), anyString(), anyString(), neoPrompt.capture(), anyList(), anyList(), anyList(), any()))
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
        when(validator.validate(eq("M-1"), anyList(), anyList(), anyList(), anyList(), anyList()))
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
        when(workspace.headIfAny("M-1")).thenReturn(Optional.of("a".repeat(40)));
        when(workspace.headSha("M-1")).thenReturn("a".repeat(40));
        when(workspace.missionWorkspace("M-1")).thenReturn(Path.of("/data/forjai-products/M-1"));
        when(runtime.generate(eq("M-1-ENGINEERING-R1"), anyString(), anyString(), anyString(), anyList(), anyList(), anyList(), any()))
                .thenReturn(CompletableFuture.completedFuture(dev("web/index.html")));
        when(runtime.generate(eq("M-1-FRONTEND-UI-R1"), anyString(), anyString(), anyString(), anyList(), anyList(), anyList(), any()))
                .thenReturn(CompletableFuture.completedFuture(dev("web/ui/hud.js")));
        when(workspace.commitAgentWork(eq("M-1"), eq("M-1-ENGINEERING-R1"), eq("engineering"), eq("Neo"), any()))
                .thenReturn(new DevelopmentWorkspaceService.CommitRecord(SHA_NEO, List.of("web/index.html")));
        when(workspace.commitAgentWork(eq("M-1"), eq("M-1-FRONTEND-UI-R1"), eq("frontend-ui"), eq("Mila"), any()))
                .thenReturn(new DevelopmentWorkspaceService.CommitRecord(SHA_MILA, List.of("web/ui/hud.js")));
        when(dependencies.resolve(anyString(), anyString(), anyList()))
                .thenReturn(new DependencyService.Outcome(List.of(), List.of(), null));
        when(workspace.filesAtCommit(eq("M-1"), anyString())).thenReturn(List.of("web/index.html", "web/ui/hud.js"));
        when(workspace.readFileAtCommit(eq("M-1"), anyString(), anyString())).thenReturn("contenido");
        when(validator.validate(eq("M-1"), anyList(), eq(List.of(new AppComponent(StackProfile.GODOT_DOTNET_GAME, ""))), eq(List.of("Combate")), anyList(), anyList()))
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
                argThat(p -> p.contains("devuelve solo los archivos que cambias")), anyList(), anyList(), eq(List.of()), any());
    }

    // Verificado en vivo (MISSION-ARIA-CRM-1791093806, incremento 2): un contexto nuevo en una ronda no tenía sus .csproj
    // (ENTRY_FILES). Forjai commitea solo los archivos del scaffold que faltan, sin tocar los existentes.
    @Test
    void aRoundCommitsOnlyTheMissingScaffoldFiles() throws Exception {
        var base = context();
        var roundTwo = new TeamMissionContext(base.missionId(), base.instruction(), base.team(), base.plan(), 2);
        when(workspace.headIfAny("M-1")).thenReturn(Optional.of("a".repeat(40)));
        when(workspace.headSha("M-1")).thenReturn("a".repeat(40));
        when(workspace.missionWorkspace("M-1")).thenReturn(Path.of("/data/forjai-products/M-1"));
        when(workspace.readFileAtCommit(eq("M-1"), anyString(), anyString())).thenReturn("contenido");
        when(workspace.readFileAtCommit(eq("M-1"), anyString(), eq("game/Game.csproj"))).thenThrow(new java.io.IOException("no existe"));
        when(workspace.commitAgentWork(eq("M-1"), eq("M-1-SCAFFOLD-R2"), eq("forjai"), eq("Forjai"), any()))
                .thenReturn(new DevelopmentWorkspaceService.CommitRecord("b".repeat(40), List.of("game/Game.csproj")));
        when(runtime.generate(anyString(), anyString(), anyString(), anyString(), anyList(), anyList(), anyList(), any()))
                .thenReturn(CompletableFuture.completedFuture(dev("web/index.html")));
        when(workspace.commitAgentWork(eq("M-1"), endsWith("-R2"), argThat(a -> !"forjai".equals(a)), anyString(), any()))
                .thenReturn(new DevelopmentWorkspaceService.CommitRecord(SHA_NEO, List.of("web/index.html")));
        when(dependencies.resolve(anyString(), anyString(), anyList()))
                .thenReturn(new DependencyService.Outcome(List.of(), List.of(), null));
        when(workspace.filesAtCommit(eq("M-1"), anyString())).thenReturn(List.of("web/index.html"));
        when(workspace.filesAtCommit("M-1", "b".repeat(40))).thenReturn(List.of("db/postgres/migrations/V1__clientes.sql"));
        when(validator.validate(eq("M-1"), anyList(), anyList(), anyList(), anyList(), anyList()))
                .thenReturn(List.of(StaticCheck.pass("DDD_LAYERS", "ok", null, List.of())));
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        strategy.execute(roundTwo, progress);

        verify(workspace).commitAgentWork(eq("M-1"), eq("M-1-SCAFFOLD-R2"), eq("forjai"), eq("Forjai"),
                argThat(r -> r.files().stream().map(DevelopmentResult.GeneratedFile::path).toList()
                        .equals(List.of("game/Game.csproj"))));
        // Lo que ya estaba en el repositorio al empezar la ronda sigue permitido aunque su dueño no esté en este plan.
        verify(validator).validate(eq("M-1"), anyList(), anyList(), anyList(),
                argThat(allowed -> allowed.contains("db/postgres/migrations/V1__clientes.sql")), anyList());
    }

    // Revisión 2026-10-06 §6: una ronda > 0 sin repositorio (nunca existió) escribe el scaffold de nuevo.
    @Test
    void aLaterRoundWithoutRepositoryWritesTheScaffold() throws Exception {
        stubHappyPath();
        var base = context();
        var roundOne = new TeamMissionContext(base.missionId(), base.instruction(), base.team(), base.plan(), 1);
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));
        when(runtime.generate(eq("M-1-ENGINEERING-R1"), anyString(), anyString(), anyString(), anyList(), anyList(), anyList(), any()))
                .thenReturn(CompletableFuture.completedFuture(dev("web/index.html")));
        when(runtime.generate(eq("M-1-FRONTEND-UI-R1"), anyString(), anyString(), anyString(), anyList(), anyList(), anyList(), any()))
                .thenReturn(CompletableFuture.completedFuture(dev("web/ui/hud.js")));

        when(workspace.headSha("M-1")).thenReturn("a".repeat(40));
        when(workspace.commitAgentWork(eq("M-1"), eq("M-1-ENGINEERING-R1"), eq("engineering"), eq("Neo"), any()))
                .thenReturn(new DevelopmentWorkspaceService.CommitRecord(SHA_NEO, List.of("web/index.html")));
        when(workspace.commitAgentWork(eq("M-1"), eq("M-1-FRONTEND-UI-R1"), eq("frontend-ui"), eq("Mila"), any()))
                .thenReturn(new DevelopmentWorkspaceService.CommitRecord(SHA_MILA, List.of("web/ui/hud.js")));

        strategy.execute(roundOne, progress);

        verify(workspace).commitAgentWork(eq("M-1"), eq("M-1-SCAFFOLD"), eq("forjai"), eq("Forjai"), any());
        // El camino del scaffold existente (existingScaffold) no se usó: no leyó ningún archivo en un commit previo.
        verify(workspace, never()).headSha("M-1");
    }

    // Spec 2026-10-14 Kael §2.2: un plan con juego pasa los gates de juego como chequeos de la generación.
    @Test
    @SuppressWarnings("unchecked")
    void aGamePlanAppliesTheGameGatesToEveryGeneration() throws Exception {
        stubHappyPath();
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        strategy.execute(context(), progress);

        var checks = ArgumentCaptor.forClass(java.util.function.Function.class);
        verify(runtime).generate(eq("M-1-FRONTEND-UI"), anyString(), anyString(), anyString(), anyList(), anyList(),
                anyList(), checks.capture());
        var gate = (java.util.function.Function<DevelopmentResult, List<String>>) checks.getValue();
        assertEquals(List.of(), gate.apply(dev("src/Combate.Application/Ok.cs")));
        var asset = gate.apply(new DevelopmentResult("r", List.of(new GeneratedFile("game/Main.tscn",
                "[ext_resource type=\"Texture2D\" path=\"res://paleta.png\" id=\"2\"]"))));
        assertTrue(asset.stream().anyMatch(e -> e.contains("asset externo")), asset.toString());
        var random = gate.apply(new DevelopmentResult("r", List.of(new GeneratedFile("src/Combate.Domain/Dado.cs",
                "var r = new Random();"))));
        assertTrue(random.stream().anyMatch(e -> e.contains("azar")), random.toString());
        // Mila no es QA: las partidas de Vera y el runner de Forjai le están vedados.
        var reserved = gate.apply(new DevelopmentResult("r", List.of(
                new GeneratedFile("game/playthroughs/ESC_01.cs", "x"), new GeneratedFile("game/forjai/Runner.cs", "x"))));
        assertEquals(2, reserved.size(), reserved.toString());
    }

    @Test
    void forjaiCommitsTheProjectScaffoldBeforeTheAgentsWork() throws Exception {
        stubHappyPath();
        var milaPrompt = ArgumentCaptor.forClass(String.class);
        when(runtime.generate(eq("M-1-FRONTEND-UI"), anyString(), anyString(), milaPrompt.capture(), anyList(), anyList(), anyList(), any()))
                .thenReturn(CompletableFuture.completedFuture(dev("src/Combate.Application/X.cs")));
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        var result = (TeamExecutionResult.Development) strategy.execute(context(), progress);

        var inOrder = inOrder(workspace, runtime);
        inOrder.verify(workspace).commitAgentWork(eq("M-1"), eq("M-1-SCAFFOLD"), eq("forjai"), eq("Forjai"),
                argThat(r -> r.files().stream().anyMatch(f -> f.path().equals("game/Game.csproj"))));
        inOrder.verify(runtime).generate(eq("M-1-FRONTEND-UI"), anyString(), anyString(), anyString(), anyList(), anyList(), anyList(), any());
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
                        && list.contains("game/Game.csproj")), anyList(), any());
    }

    // Decisión del fundador (revisión 3, tras MISSION-SANDBOX-VERIFY-1..8): en paralelo cada agente escribía
    // contra clases que nunca vio y el código no compilaba. Ahora se genera por capas y cada agente ve el código
    // ya commiteado (sin .csproj/.sln, que son de Forjai).
    @Test
    void eachAgentSeesTheCodeAlreadyCommittedByThePreviousLayers() throws Exception {
        stubHappyPath();
        var milaPrompt = ArgumentCaptor.forClass(String.class);
        var neoPrompt = ArgumentCaptor.forClass(String.class);
        when(runtime.generate(eq("M-1-FRONTEND-UI"), anyString(), anyString(), milaPrompt.capture(), anyList(), anyList(), anyList(), any()))
                .thenReturn(CompletableFuture.completedFuture(dev("web/ui/hud.js")));
        when(runtime.generate(eq("M-1-ENGINEERING"), anyString(), anyString(), neoPrompt.capture(), anyList(), anyList(), anyList(), any()))
                .thenReturn(CompletableFuture.completedFuture(dev("web/index.html")));
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        strategy.execute(context(), progress);

        var inOrder = inOrder(runtime, workspace);
        inOrder.verify(runtime).generate(eq("M-1-FRONTEND-UI"), anyString(), anyString(), anyString(), anyList(), anyList(), anyList(), any());
        inOrder.verify(workspace).commitAgentWork(eq("M-1"), eq("M-1-FRONTEND-UI"), anyString(), anyString(), any());
        inOrder.verify(runtime).generate(eq("M-1-ENGINEERING"), anyString(), anyString(), anyString(), anyList(), anyList(), anyList(), any());
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

        verify(validator).validate(eq("M-1"), anyList(), anyList(), anyList(),
                argThat(allowed -> allowed.contains("game/Game.csproj")), anyList());
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
        when(runtime.generate(eq("M-1-FRONTEND-UI"), anyString(), anyString(), milaPrompts.capture(), anyList(), anyList(), anyList(), any()))
                .thenReturn(CompletableFuture.completedFuture(dev("src/Combate.Application/X.cs")));
        when(runtime.generate(eq("M-1-FRONTEND-UI"), anyString(), anyString(), milaPrompts.capture(), anyList(), anyList(), anyList(), any()))
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
        when(runtime.generate(eq("M-1-FRONTEND-UI"), anyString(), anyString(), anyString(), anyList(), anyList(), anyList(), any()))
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
        when(runtime.generate(eq("M-1-FRONTEND-UI"), anyString(), anyString(), anyString(), anyList(), anyList(), anyList(), any()))
                .thenReturn(CompletableFuture.completedFuture(dev("src/Combate.Application/X.cs")))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("reintentos agotados")));
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        strategy.execute(context(), progress);

        var inOrder = inOrder(memory);
        inOrder.verify(memory).updateTask(eq("M-1-FRONTEND-UI"), eq("COMPLETED"), anyString());
        inOrder.verify(memory).updateTask(eq("M-1-FRONTEND-UI"), eq("COMPLETED"), contains("se conserva el commit"));
        verify(runtime).generate(eq("M-1-FRONTEND-UI"), anyString(), anyString(),
                contains("propias rutas o usa los tipos que sí existen"), anyList(), anyList(), eq(List.of("src/Combate.Application")), any());
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
        verify(runtime, never()).generate(anyString(), anyString(), anyString(), contains("CORRECCIÓN DEL SANDBOX"),
                anyList(), anyList(), anyList(), any());
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
        when(runtime.generate(eq("M-1-FRONTEND-UI"), anyString(), anyString(), repairPrompts.capture(), anyList(), anyList(), anyList(), any()))
                .thenReturn(CompletableFuture.completedFuture(dev("web/ui/hud.js")))
                .thenReturn(CompletableFuture.completedFuture(new DevelopmentResult("igual", List.of(
                        new DevelopmentResult.GeneratedFile("src/Combate.Application/X.cs",
                                "linea1\nlinea2\n    var t = lista._tareas;\n")))))
                .thenReturn(CompletableFuture.completedFuture(new DevelopmentResult("cambiado", List.of(
                        new DevelopmentResult.GeneratedFile("src/Combate.Application/X.cs", "arreglado\n")))));
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        strategy.execute(context(), progress);

        var first = repairPrompts.getAllValues().get(1);
        assertTrue(first.contains("línea 3: var t = lista._tareas;"), first);
        var second = repairPrompts.getAllValues().get(2);
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
        when(runtime.generate(eq("M-1-FRONTEND-UI"), anyString(), anyString(), repairPrompts.capture(), anyList(), anyList(), anyList(), any()))
                .thenReturn(CompletableFuture.completedFuture(dev("web/ui/hud.js")))
                .thenReturn(CompletableFuture.completedFuture(corrected("src/Combate.Application/X.cs")));
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        strategy.execute(context(), progress);

        var prompt = repairPrompts.getAllValues().get(1);
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
        when(runtime.generate(eq("M-1-FRONTEND-UI"), anyString(), anyString(), repairPrompts.capture(), anyList(), anyList(), anyList(), any()))
                .thenReturn(CompletableFuture.completedFuture(dev("web/ui/hud.js")))
                .thenReturn(CompletableFuture.completedFuture(new DevelopmentResult("igual", List.of(
                        new GeneratedFile("src/Combate.Application/X.cs", "x\n"),
                        new GeneratedFile("src/Combate.Application/Y.cs", "y\n")))));
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        strategy.execute(context(), progress);

        assertTrue(repairPrompts.getAllValues().get(2).contains("SIN CAMBIOS"), repairPrompts.getAllValues().get(2));
    }

    // Parte 3: los paquetes pedidos por un agente .NET entran al .csproj de su capa (commit de Forjai) antes de verificar.
    @Test
    void requestedNugetPackagesAreAddedToTheScaffoldBeforeVerifying() throws Exception {
        stubHappyPath();
        when(runtime.generate(eq("M-1-FRONTEND-UI"), anyString(), anyString(), anyString(), anyList(), anyList(), anyList(), any()))
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
        when(runtime.generate(eq("M-1-FRONTEND-UI"), anyString(), anyString(), anyString(), anyList(), anyList(), anyList(), any()))
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
        var flutter = List.of(new AppComponent(StackProfile.FLUTTER_WEB_APP, ""));
        var refs = DevelopmentTeamStrategy.dependencyRefs(flutter, List.of(),
                path -> "pubspec.yaml".equals(path) ? "dependencies:\n  flutter:\n    sdk: flutter\n  equatable: 2.0.5\n" : null);
        assertEquals(List.of(new DependencyRef("PUB", "equatable", "2.0.5")), refs);
        assertEquals(List.of(new DependencyRef("NUGET", "A", "1.0.0")), DevelopmentTeamStrategy.dependencyRefs(
                List.of(new AppComponent(StackProfile.DOTNET_APP, "")),
                List.of(new DevelopmentResult.PackageRequest("A", "1.0.0")), path -> null));
    }

    // Spec 2026-10-02 B1 §3: en una app completa, NuGet del backend y pub de app/pubspec.yaml en la misma misión.
    @Test
    void fullstackDependenciesComeFromBothEcosystems() {
        var components = AppComponent.of(List.of(StackProfile.DOTNET_APP, StackProfile.FLUTTER_WEB_APP));
        var refs = DevelopmentTeamStrategy.dependencyRefs(components,
                List.of(new DevelopmentResult.PackageRequest("A", "1.0.0")),
                path -> "app/pubspec.yaml".equals(path) ? "dependencies:\n  http: 1.2.2\n" : null);
        assertEquals(List.of(new DependencyRef("NUGET", "A", "1.0.0"), new DependencyRef("PUB", "http", "1.2.2")), refs);
    }

    // Verificado en vivo (MISSION-DEPS-VERIFY-4): Iris (Application + Infrastructure) pidió Newtonsoft y Forjai lo
    // agregó solo al primer .csproj; lo usaba en Infrastructure. Va a todos los proyectos de las capas del agente.
    @Test
    void requestedPackagesGoToEveryProjectOfTheAgentsLayers() {
        var projects = DevelopmentTeamStrategy.projectsOf(List.of(new AppComponent(StackProfile.DOTNET_APP, "")), List.of("Tareas"),
                List.of("src/Tareas.Application", "src/Tareas.Infrastructure"));
        assertEquals(List.of("src/Tareas.Application/Tareas.Application.csproj",
                "src/Tareas.Infrastructure/Tareas.Infrastructure.csproj"), projects);
    }

    // Verificado en vivo (MISSION-DBA-1790955204): Diego e Iris usaron Npgsql sin pedirlo en "packages" y ningún
    // .csproj lo traía. Java lo agrega al proyecto de cada archivo que lo usa, no a todas las capas del agente.
    @Test
    void npgsqlIsAddedToTheProjectsWhoseCodeUsesIt() {
        var implied = DevelopmentTeamStrategy.impliedPackages(List.of(new AppComponent(StackProfile.DOTNET_APP, "")), List.of("Citas"), Map.of(
                "src/Citas.Infrastructure/CitaRepositorio.cs", "using System;\nusing Npgsql;\n\npublic class A {}",
                "src/Citas.Api/Program.cs", "var ds = new Npgsql.NpgsqlDataSourceBuilder(\"\").Build();",
                "src/Citas.Application/Repositorios.cs", "/// La implementación vive en Infrastructure (Npgsql/PostgreSQL).",
                "src/Citas.Domain/Cita.cs", "public class Cita {}"));
        assertEquals(Map.of(
                "src/Citas.Infrastructure/Citas.Infrastructure.csproj", List.of(DevelopmentTeamStrategy.NPGSQL),
                "src/Citas.Api/Citas.Api.csproj", List.of(DevelopmentTeamStrategy.NPGSQL)), implied);
        assertTrue(DevelopmentTeamStrategy.impliedPackages(List.of(new AppComponent(StackProfile.FLUTTER_WEB_APP, "")), List.of("Citas"),
                Map.of("lib/main.dart", "using Npgsql;")).isEmpty());
    }

    @Test
    void npgsqlGoesToTheBackendProjectOfAFullstackApp() {
        var implied = DevelopmentTeamStrategy.impliedPackages(
                AppComponent.of(List.of(StackProfile.DOTNET_APP, StackProfile.FLUTTER_WEB_APP)), List.of("Clientes"),
                Map.of("backend/src/Clientes.Infrastructure/Repo.cs", "using Npgsql;"));
        assertEquals(Map.of("backend/src/Clientes.Infrastructure/Clientes.Infrastructure.csproj",
                List.of(DevelopmentTeamStrategy.NPGSQL)), implied);
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

    // Spec 2026-10-02 §2: con base de datos, cada agente recibe el contrato y los chequeos de base se aplican.
    @Test
    void withADatabaseTheAgentsGetTheContractAndTheDatabaseChecks() throws Exception {
        stubHappyPath();
        when(runtime.generate(anyString(), anyString(), anyString(), anyString(), anyList(), anyList(), anyList(), any()))
                .thenAnswer(inv -> CompletableFuture.completedFuture(dev("web/ui/hud.js")));
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));
        var databases = mock(com.aicompany.core.database.DatabaseConnectionService.class);
        when(databases.connectionsOf("M-1")).thenReturn(List.of(new com.aicompany.core.database.DatabaseConnection(
                "C1", "citas-dev-aws", "POSTGRESQL", "db.secreto.rds.amazonaws.com", 5432, "citas", "admin",
                "REQUIRE", null, "TEST", "****9876", null, null)));
        strategy.setDatabaseConnections(databases);
        var base = context();
        var plan = new TeamPlan(base.plan().summary(), null, null, base.plan().tasksOrEmpty(), List.of(),
                base.plan().stackProfile(), base.plan().boundedContexts(), base.plan().ubiquitousLanguage(),
                new TeamPlan.DatabaseNeed("POSTGRESQL", "citas-dev-aws"));

        strategy.execute(new TeamMissionContext("M-1", "crear un juego", base.team(), plan), progress);

        var prompts = org.mockito.ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        var checks = (org.mockito.ArgumentCaptor<java.util.function.Function<DevelopmentResult, List<String>>>)
                (org.mockito.ArgumentCaptor<?>) org.mockito.ArgumentCaptor.forClass(java.util.function.Function.class);
        verify(runtime, atLeastOnce()).generate(anyString(), anyString(), anyString(), prompts.capture(), anyList(),
                anyList(), anyList(), checks.capture());
        assertTrue(prompts.getValue().contains("DB_CITAS_DEV_AWS_HOST"), prompts.getValue());
        assertFalse(prompts.getValue().contains("rds.amazonaws.com"), prompts.getValue());
        var leaked = new DevelopmentResult("r", List.of(new GeneratedFile("src/A.cs",
                "var cs = \"Host=db;Username=admin;Password=postgres\";")));
        assertFalse(checks.getValue().apply(leaked).isEmpty());
    }

    // Spec 2026-10-02 §3: una misión VERIFIED con base aplica el esquema y lo informa.
    @Test
    void aVerifiedMissionWithADatabaseAppliesTheSchemaAndReportsIt() throws Exception {
        stubHappyPath();
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));
        var apply = mock(com.aicompany.core.database.DatabaseApplyService.class);
        when(apply.apply(eq("M-1"), anyString(), any(), anyBoolean())).thenReturn(new com.aicompany.core.database.DatabaseApplyService.CascadeResult(List.of(), com.aicompany.core.database.DatabaseApplyService.Outcome.APPLIED, "Base de datos citas-dev-aws: aplicadas V1."));
        strategy.setDatabaseApply(apply);
        var base = context();
        var plan = new TeamPlan(base.plan().summary(), null, null, base.plan().tasksOrEmpty(), List.of(),
                base.plan().stackProfile(), base.plan().boundedContexts(), base.plan().ubiquitousLanguage(),
                new TeamPlan.DatabaseNeed("POSTGRESQL", "citas-dev-aws"));

        var result = (TeamExecutionResult.Development) strategy.execute(
                new TeamMissionContext("M-1", "crear un juego", base.team(), plan), progress);

        verify(apply).apply(eq("M-1"), anyString(), eq(plan), anyBoolean());
        assertTrue(result.verifiableState().contains("aplicadas V1"), result.verifiableState());
    }

    private TeamPlan databasePlan() {
        var base = context().plan();
        return new TeamPlan(base.summary(), null, null, base.tasksOrEmpty(), List.of(), base.stackProfile(),
                base.boundedContexts(), base.ubiquitousLanguage(), new TeamPlan.DatabaseNeed("POSTGRESQL", "citas-dev-aws"));
    }

    // Spec Diego ambientes §2: todos los ambientes aplicados -> DATABASE_APPLIED PASS y se registra el commit verificado.
    @Test
    void allEnvironmentsAppliedAddsAPassingCheckAndRecordsTheVerifiedCommit() throws Exception {
        stubHappyPath();
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));
        var apply = mock(com.aicompany.core.database.DatabaseApplyService.class);
        when(apply.apply(eq("M-1"), anyString(), any(), anyBoolean())).thenReturn(
                new com.aicompany.core.database.DatabaseApplyService.CascadeResult(List.of(), com.aicompany.core.database.DatabaseApplyService.Outcome.APPLIED, "todo aplicado"));
        strategy.setDatabaseApply(apply);

        strategy.execute(new TeamMissionContext("M-1", "crear un juego", context().team(), databasePlan()), progress);

        verify(memory).recordStaticValidation(eq("M-1-QA"), eq("VERIFIED"),
                argThat(json -> json.contains("DATABASE_APPLIED") && !json.contains("\"FAIL\"")));
        verify(memory).recordVerifiedCommit(eq("M-1"), anyString());
    }

    // Spec Diego ambientes §2: un ambiente fallido es un chequeo FAIL, la validación FAILED y no hay commit verificado.
    @Test
    void aFailedEnvironmentFailsTheValidationWithoutRecordingTheVerifiedCommit() throws Exception {
        stubHappyPath();
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));
        var apply = mock(com.aicompany.core.database.DatabaseApplyService.class);
        var failed = new com.aicompany.core.database.DatabaseApplyService.EnvironmentResult(
                "citas-prod", "PRODUCTION", List.of(1), 2, "columna x no existe", false);
        when(apply.apply(eq("M-1"), anyString(), any(), anyBoolean())).thenReturn(
                new com.aicompany.core.database.DatabaseApplyService.CascadeResult(List.of(failed), com.aicompany.core.database.DatabaseApplyService.Outcome.NEEDS_CORRECTION, "falló"));
        strategy.setDatabaseApply(apply);

        var result = (TeamExecutionResult.Development) strategy.execute(
                new TeamMissionContext("M-1", "crear un juego", context().team(), databasePlan()), progress);

        verify(memory).recordStaticValidation(eq("M-1-QA"), eq("FAILED"), argThat(json -> json.contains("DATABASE_APPLIED")
                && json.contains("PRODUCTION") && json.contains("citas-prod") && json.contains("V2")
                && json.contains("columna x no existe")));
        verify(memory, never()).recordVerifiedCommit(anyString(), anyString());
        assertTrue(result.verifiableState().contains("falló"), result.verifiableState());
    }

    // Sin base: ni cascada ni chequeo, como hoy.
    @Test
    void withoutADatabaseThereIsNoDatabaseCheck() throws Exception {
        stubHappyPath();
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));
        var apply = mock(com.aicompany.core.database.DatabaseApplyService.class);
        strategy.setDatabaseApply(apply);

        strategy.execute(context(), progress);

        verify(apply, never()).apply(anyString(), anyString(), any(), anyBoolean());
        verify(memory).recordStaticValidation(eq("M-1-QA"), eq("VERIFIED"), argThat(json -> !json.contains("DATABASE_APPLIED")));
    }

    // Falla cerrado: si no se puede leer qué se aplicó, editar una migración commiteada se rechaza.
    @Test
    void whenAppliedMigrationsCannotBeReadCommittedMigrationsStayImmutable() throws Exception {
        stubHappyPath();
        when(runtime.generate(anyString(), anyString(), anyString(), anyString(), anyList(), anyList(), anyList(), any()))
                .thenAnswer(inv -> CompletableFuture.completedFuture(dev("web/ui/hud.js")));
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));
        when(memory.appliedAnywhere("M-1")).thenThrow(new IllegalStateException("neo4j caído"));
        var path = "db/postgres/migrations/V1__a.sql";
        when(workspace.filesAtCommit(eq("M-1"), anyString())).thenReturn(List.of(path));
        when(workspace.readFileAtCommit(eq("M-1"), anyString(), eq(path))).thenReturn("CREATE TABLE a (id int);");

        strategy.execute(new TeamMissionContext("M-1", "crear un juego", context().team(), databasePlan()), progress);

        @SuppressWarnings("unchecked")
        var checks = (org.mockito.ArgumentCaptor<java.util.function.Function<DevelopmentResult, List<String>>>)
                (org.mockito.ArgumentCaptor<?>) org.mockito.ArgumentCaptor.forClass(java.util.function.Function.class);
        verify(runtime, atLeastOnce()).generate(anyString(), anyString(), anyString(), anyString(), anyList(),
                anyList(), anyList(), checks.capture());
        var edit = new DevelopmentResult("r", List.of(new GeneratedFile(path, "CREATE TABLE a (id int, x int);")));
        assertTrue(checks.getValue().apply(edit).stream().anyMatch(e -> e.contains("V1__a.sql")));
    }

    // Revisión final Diego ambientes (C1, I1, I3): la cascada real (DatabaseApplyService con conexiones y aplicador
    // simulados) y la clasificación de Java.
    private final com.aicompany.core.database.DatabaseConnectionService dbConnections =
            mock(com.aicompany.core.database.DatabaseConnectionService.class);
    private final com.aicompany.core.database.DatabaseSchemaApplier dbApplier =
            mock(com.aicompany.core.database.DatabaseSchemaApplier.class);
    private final AlertMailService dbMail = mock(AlertMailService.class);
    private static final com.aicompany.core.database.DatabaseConnection DB_TEST = new com.aicompany.core.database.DatabaseConnection(
            "C1", "citas-test", "POSTGRESQL", "test.secreto.example", 5432, "citas", "admin", "REQUIRE", null, "TEST",
            "****1111", null, null);
    private static final com.aicompany.core.database.DatabaseConnection DB_PROD = new com.aicompany.core.database.DatabaseConnection(
            "C2", "citas-prod", "POSTGRESQL", "prod.secreto.example", 5432, "citas", "admin", "REQUIRE", null,
            "PRODUCTION", "****2222", null, null);

    private void realDatabaseApply(com.aicompany.core.database.DatabaseConnection... connections) throws Exception {
        when(dbConnections.connectionsOf("M-1")).thenReturn(List.of(connections));
        when(dbConnections.password(any())).thenReturn("S3cret");
        var service = new com.aicompany.core.database.DatabaseApplyService(dbConnections, dbApplier, workspace, memory, events);
        service.setAlertMail(dbMail);
        strategy.setDatabaseApply(service);
    }

    private static com.aicompany.core.database.DatabaseSchemaApplier.ApplyResult applied(Integer... versions) {
        return new com.aicompany.core.database.DatabaseSchemaApplier.ApplyResult(List.of(versions), null, null, false);
    }

    private static com.aicompany.core.database.DatabaseSchemaApplier.ApplyResult failedAt(int version, String error) {
        return failedAt(version, error, "42601");
    }

    private static com.aicompany.core.database.DatabaseSchemaApplier.ApplyResult failedAt(int version, String error,
                                                                                         String sqlState) {
        return new com.aicompany.core.database.DatabaseSchemaApplier.ApplyResult(List.of(), version, error, false, sqlState);
    }

    private TeamExecutionResult.Development executeWithDatabase() throws Exception {
        stubHappyPath();
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));
        return (TeamExecutionResult.Development) strategy.execute(
                new TeamMissionContext("M-1", "crear un juego", context().team(), databasePlan()), progress);
    }

    // C1: sin conexión no hay cascada ni falla: VERIFIED, commit verificado y la nota para cargarla.
    @Test
    void withoutAConnectionTheCodeStaysVerifiedWithAPendingNote() throws Exception {
        realDatabaseApply();

        var result = executeWithDatabase();

        verify(memory).recordStaticValidation(eq("M-1-QA"), eq("VERIFIED"), argThat(json -> !json.contains("DATABASE_APPLIED")));
        verify(memory).recordVerifiedCommit(eq("M-1"), anyString());
        assertTrue(result.verifiableState().contains("aplica el esquema de M-1"), result.verifiableState());
        verifyNoInteractions(dbApplier, dbMail);
    }

    // I1 (a): el SQL de una migración no aplicada en ningún ambiente va a la corrección.
    @Test
    void aSqlErrorInAnUnappliedMigrationFailsTheValidation() throws Exception {
        realDatabaseApply(DB_TEST, DB_PROD);
        when(memory.appliedAnywhere("M-1")).thenReturn(java.util.Set.of(1, 2));
        when(dbApplier.apply(eq(DB_TEST), any(), anyList(), any(), any())).thenReturn(failedAt(3, "V3 falló: syntax error"));

        executeWithDatabase();

        verify(memory).recordStaticValidation(eq("M-1-QA"), eq("FAILED"),
                argThat(json -> json.contains("DATABASE_APPLIED") && json.contains("syntax error")));
        verify(memory, never()).recordVerifiedCommit(anyString(), anyString());
        verify(dbApplier, never()).apply(eq(DB_PROD), any(), anyList(), any(), any());
        verifyNoInteractions(dbMail);
    }

    // I1 (b): una conexión que no responde no la arregla un modelo: VERIFIED, commit verificado y aviso al fundador.
    @Test
    void aConnectionErrorKeepsTheCodeVerifiedAndNotifiesTheFounder() throws Exception {
        realDatabaseApply(DB_TEST);
        when(dbApplier.apply(eq(DB_TEST), any(), anyList(), any(), any()))
                .thenThrow(new IllegalStateException("Connection to test.secreto.example refused"));

        var result = executeWithDatabase();

        verify(memory).recordStaticValidation(eq("M-1-QA"), eq("VERIFIED"), argThat(json -> !json.contains("DATABASE_APPLIED")));
        verify(memory).recordVerifiedCommit(eq("M-1"), anyString());
        assertTrue(result.verifiableState().contains("problema del ambiente")
                && result.verifiableState().contains("aplica el esquema de M-1"), result.verifiableState());
        assertFalse(result.verifiableState().contains("test.secreto.example"), result.verifiableState());
        verify(dbMail).send(contains("M-1"), contains("problema del ambiente"), eq(true));
        verify(events).publish(eq("EMPRESA_DATABASE_SCHEMA_FAILED"), eq("M-1"), eq("M-1-DATABASE"), eq("forjai"), anyMap());
    }

    // I1 (b): V3 aplicada en TEST que falla en PRODUCTION es un problema de datos del ambiente, no una corrección.
    @Test
    void aProductionFailureOfAVersionAppliedInTestIsAnEnvironmentProblem() throws Exception {
        realDatabaseApply(DB_TEST, DB_PROD);
        when(memory.appliedAnywhere("M-1")).thenReturn(java.util.Set.of(1, 2));
        when(dbApplier.apply(eq(DB_TEST), any(), anyList(), any(), any())).thenReturn(applied(3));
        when(dbApplier.apply(eq(DB_PROD), any(), anyList(), any(), any()))
                .thenReturn(failedAt(3, "V3 falló: duplicate key value"));

        var result = executeWithDatabase();

        verify(memory).recordStaticValidation(eq("M-1-QA"), eq("VERIFIED"), argThat(json -> !json.contains("DATABASE_APPLIED")));
        verify(memory).recordVerifiedCommit(eq("M-1"), anyString());
        assertTrue(result.verifiableState().contains("problema del ambiente"), result.verifiableState());
        verify(dbMail).send(contains("M-1"), anyString(), eq(true));
    }

    // Seguimiento: un timeout (57014) en una migración sin aplicar es del ambiente: VERIFIED y aviso, sin corrección pagada.
    @Test
    void aQueryTimeoutDuringAnUnappliedMigrationIsAnEnvironmentProblem() throws Exception {
        realDatabaseApply(DB_TEST);
        when(dbApplier.apply(eq(DB_TEST), any(), anyList(), any(), any()))
                .thenReturn(failedAt(1, "V1 falló: canceling statement due to statement timeout", "57014"));

        var result = executeWithDatabase();

        verify(memory).recordStaticValidation(eq("M-1-QA"), eq("VERIFIED"), argThat(json -> !json.contains("DATABASE_APPLIED")));
        assertTrue(result.verifiableState().contains("problema del ambiente"), result.verifiableState());
        verify(dbMail).send(contains("M-1"), anyString(), eq(true));
    }

    // Menor: un error de memoria dentro de la cascada no escapa antes de registrar la validación.
    @Test
    void aMemoryErrorInsideTheCascadeIsAnEnvironmentProblem() throws Exception {
        realDatabaseApply(DB_TEST);
        doThrow(new IllegalStateException("neo4j caído")).when(memory)
                .createTask(eq("M-1-DATABASE"), anyString(), anyString(), anyString());

        var result = executeWithDatabase();

        verify(memory).recordStaticValidation(eq("M-1-QA"), eq("VERIFIED"), argThat(json -> !json.contains("DATABASE_APPLIED")));
        verify(memory).recordVerifiedCommit(eq("M-1"), anyString());
        assertTrue(result.verifiableState().contains("neo4j caído"), result.verifiableState());
    }

    // Revisión final (I-6): un usuario corto como "app" no puede marcar todo el código.
    @Test
    void ordinaryCodeIsNotTakenForTheConnectionsHostOrUser() throws Exception {
        stubHappyPath();
        when(runtime.generate(anyString(), anyString(), anyString(), anyString(), anyList(), anyList(), anyList(), any()))
                .thenAnswer(inv -> CompletableFuture.completedFuture(dev("web/ui/hud.js")));
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));
        var databases = mock(com.aicompany.core.database.DatabaseConnectionService.class);
        when(databases.connectionsOf("M-1")).thenReturn(List.of(new com.aicompany.core.database.DatabaseConnection(
                "C1", "citas-dev-aws", "POSTGRESQL", "localhost", 5432, "citas", "app",
                "REQUIRE", null, "TEST", "****9876", null, null)));
        strategy.setDatabaseConnections(databases);
        var base = context();
        var plan = new TeamPlan(base.plan().summary(), null, null, base.plan().tasksOrEmpty(), List.of(),
                base.plan().stackProfile(), base.plan().boundedContexts(), base.plan().ubiquitousLanguage(),
                new TeamPlan.DatabaseNeed("POSTGRESQL", "citas-dev-aws"));

        strategy.execute(new TeamMissionContext("M-1", "crear un juego", base.team(), plan), progress);

        @SuppressWarnings("unchecked")
        var checks = (org.mockito.ArgumentCaptor<java.util.function.Function<DevelopmentResult, List<String>>>)
                (org.mockito.ArgumentCaptor<?>) org.mockito.ArgumentCaptor.forClass(java.util.function.Function.class);
        verify(runtime, atLeastOnce()).generate(anyString(), anyString(), anyString(), anyString(), anyList(),
                anyList(), anyList(), checks.capture());
        var ordinary = new DevelopmentResult("r", List.of(new GeneratedFile("src/Citas.Api/Program.cs",
                "app.MapGet(\"/health\", () => \"ok\"); // http://localhost:5000")));
        assertEquals(List.of(), checks.getValue().apply(ordinary));
    }

    // ---- Apps completas (spec 2026-10-02 B1) ----

    private static final String SHA_BACKEND = "8".repeat(40);
    private static final String SHA_APP = "9".repeat(40);
    private static final String PROGRAM = "var app = builder.Build();\napp.MapGet(\"/clientes/{id}\", (Guid id) => Results.Ok());";

    private static TeamPlan fullstackPlan() {
        return new TeamPlan("CRM", null, null, List.of(
                new PlannedTask("backend", "WORK", "API", "API", List.of("api"), List.of("backend/src/Clientes.Domain",
                        "backend/src/Clientes.Application", "backend/src/Clientes.Api")),
                new PlannedTask("frontend-ui", "WORK", "APP", "App", List.of("flutter"), List.of("app/lib/clientes/domain",
                        "app/lib/clientes/presentation", "app/pubspec.yaml", "app/lib/main.dart", "app/web")),
                new PlannedTask("qa", "VALIDATION", "CODE_REVIEW", "Revisar", List.of("qa"), List.of())),
                List.of(), "DOTNET_APP", List.of(new TeamPlan.BoundedContext("Clientes", "Clientes del CRM")),
                List.of(new TeamPlan.GlossaryTerm("Cliente", "Quien compra"),
                        new TeamPlan.GlossaryTerm("Oportunidad", "Venta posible"),
                        new TeamPlan.GlossaryTerm("Etapa", "Paso de la venta")),
                null, List.of("DOTNET_APP", "FLUTTER_WEB_APP"));
    }

    private static PlannedTask task(TeamPlan plan, String agentId) {
        return plan.workTasks().stream().filter(t -> t.agentId().equals(agentId)).findFirst().orElseThrow();
    }

    private static TeamMissionContext fullstackContext() {
        var team = new TeamSnapshot("TEAM-DEVELOPMENT", "Development Group", "ACTIVE", "engineering", List.of(
                new TeamMemberInfo("engineering", "Neo", "Líder", "TECH_LEAD", List.of("architecture"), "m"),
                new TeamMemberInfo("backend", "Iris", "Backend", "BACKEND", List.of("api"), "m"),
                new TeamMemberInfo("frontend-ui", "Mila", "UI", "UI_UX", List.of("flutter"), "m"),
                new TeamMemberInfo("qa", "Vera", "QA", "QA", List.of("qa"), "m")));
        return new TeamMissionContext("M-1", "un CRM simple", team, fullstackPlan());
    }

    private void stubFullstack() throws Exception {
        for (var id : List.of("M-1-BACKEND", "M-1-FRONTEND-UI")) {
            var result = CompletableFuture.completedFuture(id.endsWith("BACKEND")
                    ? dev("backend/src/Clientes.Api/Program.cs") : dev("app/lib/main.dart"));
            when(runtime.generate(eq(id), anyString(), anyString(), anyString(), anyList(), anyList(), anyList(), any())).thenReturn(result);
            when(runtime.generate(eq(id), anyString(), anyString(), anyString(), anyList(), anyList(), anyList(), any()))
                    .thenReturn(result);
        }
        when(workspace.missionWorkspace("M-1")).thenReturn(Path.of("/data/forjai-products/M-1"));
        when(workspace.commitAgentWork(eq("M-1"), eq("M-1-SCAFFOLD"), eq("forjai"), eq("Forjai"), any()))
                .thenReturn(new DevelopmentWorkspaceService.CommitRecord("5".repeat(40), List.of("backend/Solution.sln")));
        when(workspace.commitAgentWork(eq("M-1"), eq("M-1-BACKEND"), eq("backend"), eq("Iris"), any()))
                .thenReturn(new DevelopmentWorkspaceService.CommitRecord(SHA_BACKEND, List.of("backend/src/Clientes.Api/Program.cs")));
        when(workspace.commitAgentWork(eq("M-1"), eq("M-1-API-ROUTES"), eq("forjai"), eq("Forjai"), any()))
                .thenReturn(new DevelopmentWorkspaceService.CommitRecord("6".repeat(40), List.of("app/lib/api/api_routes.dart")));
        when(workspace.commitAgentWork(eq("M-1"), eq("M-1-FRONTEND-UI"), eq("frontend-ui"), eq("Mila"), any()))
                .thenReturn(new DevelopmentWorkspaceService.CommitRecord(SHA_APP, List.of("app/lib/main.dart")));
        when(workspace.filesAtCommit(eq("M-1"), anyString()))
                .thenReturn(List.of("backend/src/Clientes.Api/Program.cs", "app/lib/main.dart"));
        when(workspace.readFileAtCommit(eq("M-1"), anyString(), eq("backend/src/Clientes.Api/Program.cs"))).thenReturn(PROGRAM);
        when(workspace.readFileAtCommit(eq("M-1"), anyString(), eq("app/lib/main.dart"))).thenReturn("void main() {}");
        when(dependencies.resolve(anyString(), anyString(), anyList()))
                .thenReturn(new DependencyService.Outcome(List.of(), List.of(), null));
        when(validator.validate(eq("M-1"), anyList(), anyList(), anyList(), anyList(), anyList()))
                .thenReturn(List.of(StaticCheck.pass("DDD_LAYERS", "ok", null, List.of())));
        when(runtime.review(anyString(), eq("M-1"), eq("qa"), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));
    }

    // Review Focus 5: con dos componentes, si el runner cae en app/ la misión no queda VERIFIED.
    @Test
    void fullstackWithoutTheSecondSandboxResultIsUnvalidated() throws Exception {
        stubFullstack();
        when(sandbox.verify(eq("M-1"), anyString(), eq("DOTNET_APP"), eq("backend"))).thenReturn(Optional.of(
                new SandboxResult("PASS", List.of(new SandboxResult.StepResult("test", "PASS", 0, 1, "", 3, 0)))));
        when(sandbox.verify(eq("M-1"), anyString(), eq("FLUTTER_WEB_APP"), eq("app"))).thenReturn(Optional.empty());
        when(sandbox.lastError()).thenReturn("sandbox-runner no disponible: timeout");

        var result = (TeamExecutionResult.Development) strategy.execute(fullstackContext(), progress);

        verify(memory).recordStaticValidation(anyString(), eq("UNVALIDATED"), anyString());
        assertTrue(result.verifiableState().contains("app/: sandbox-runner no disponible"), result.verifiableState());
    }

    // Spec 2026-10-02 B1 §2 + Review Focus 2: backend antes que app, tests al final, aunque Neo los liste al revés.
    @Test
    void fullstackTasksRunBackendThenAppThenTests() {
        var plan = fullstackPlan();
        var devops = new PlannedTask("devops", "WORK", "DATA", "Datos", List.of("persistence"),
                List.of("backend/src/Clientes.Infrastructure", "db/postgres/migrations"));
        var qa = new PlannedTask("qa", "WORK", "TESTS", "Tests", List.of("tests"),
                List.of("backend/tests/Clientes.Tests", "app/test/clientes"));
        var ordered = DevelopmentTeamStrategy.orderTasks(List.of(qa, task(plan, "frontend-ui"), devops,
                task(plan, "backend")), plan);
        assertEquals(List.of("backend", "devops", "frontend-ui", "qa"),
                ordered.stream().map(PlannedTask::agentId).toList());
    }

    @Test
    void apiRoutesAreCommittedByForjaiBeforeTheApp() throws Exception {
        stubFullstack();
        when(sandbox.verify(eq("M-1"), anyString(), anyString(), anyString())).thenReturn(Optional.of(
                new SandboxResult("PASS", List.of(new SandboxResult.StepResult("test", "PASS", 0, 1, "", 3, 0)))));

        strategy.execute(fullstackContext(), progress);

        InOrder inOrder = inOrder(workspace);
        inOrder.verify(workspace).commitAgentWork(eq("M-1"), eq("M-1-BACKEND"), eq("backend"), anyString(), any());
        inOrder.verify(workspace).commitAgentWork(eq("M-1"), eq("M-1-API-ROUTES"), eq("forjai"), eq("Forjai"),
                argThat(r -> r.files().get(0).path().equals("app/lib/api/api_routes.dart")
                        && r.files().get(0).content().contains("obtenerClientes(String id)")));
        inOrder.verify(workspace).commitAgentWork(eq("M-1"), eq("M-1-FRONTEND-UI"), eq("frontend-ui"), anyString(), any());
    }

    @Test
    void fullstackVerifiableStateListsComponentsRoutesAndTheJsonWarning() throws Exception {
        stubFullstack();
        when(sandbox.verify(eq("M-1"), anyString(), anyString(), anyString())).thenReturn(Optional.of(
                new SandboxResult("PASS", List.of(new SandboxResult.StepResult("test", "PASS", 0, 1, "", 3, 0)))));

        var result = (TeamExecutionResult.Development) strategy.execute(fullstackContext(), progress);

        assertTrue(result.verifiableState().contains("Componentes: backend/ (DOTNET_APP), app/ (FLUTTER_WEB_APP)"),
                result.verifiableState());
        assertTrue(result.verifiableState().contains("Contrato de rutas: 1 ruta(s)"), result.verifiableState());
        assertTrue(result.verifiableState().contains(DevelopmentTeamStrategy.FULLSTACK_JSON_WARNING),
                result.verifiableState());
        assertTrue(result.verifiableState().contains("backend Tests PASS 3/3"), result.verifiableState());
    }

    // Revisión final C1 + I3: el dueño de la API reintenta si no hay rutas del producto o si un prefijo no se resuelve.
    @Test
    void apiOwnerIsAskedForResolvableProductRoutes() {
        assertEquals(1, DevelopmentTeamStrategy.apiOwnerErrors(Map.of("backend/src/X.Api/Program.cs",
                "app.MapGet(\"/health\", () => Results.Ok());")).size());
        var unresolved = DevelopmentTeamStrategy.apiOwnerErrors(Map.of("backend/src/X.Api/E.cs",
                "public static void M(this RouteGroupBuilder grupo) { grupo.MapGet(\"/{id}\", () => 1); }\n"
                        + "app.MapGet(\"/clientes\", () => 1);"));
        assertEquals(1, unresolved.size());
        assertTrue(unresolved.get(0).contains("grupo"), unresolved.get(0));
        assertEquals(List.of(), DevelopmentTeamStrategy.apiOwnerErrors(Map.of("backend/src/X.Api/Program.cs",
                "var g = app.MapGroup(\"/clientes\").WithTags(\"C\");\ng.MapGet(\"/{id}\", () => 1);")));
    }

    // Revisión final I6: en una ronda nueva, regenerar el .csproj por Npgsql no borra Dapper, aprobado en la ronda 0.
    @Test
    void regeneratedProjectsKeepThePackagesAlreadyInHead() {
        var components = List.of(new AppComponent(StackProfile.DOTNET_APP, ""));
        var project = "src/Clientes.Infrastructure/Clientes.Infrastructure.csproj";
        var head = """
                <Project Sdk="Microsoft.NET.Sdk">
                  <ItemGroup>
                    <PackageReference Include="Npgsql" Version="8.0.5" />
                    <PackageReference Include="Dapper" Version="2.1.35" />
                  </ItemGroup>
                </Project>
                """;
        var merged = DevelopmentTeamStrategy.withPackagesInHead(
                Map.of(project, List.of(DevelopmentTeamStrategy.NPGSQL)), Map.of(project, head), components,
                List.of("Clientes"));
        assertEquals(List.of(DevelopmentTeamStrategy.NPGSQL, new DependencyRef("NUGET", "Dapper", "2.1.35")),
                merged.get(project));

        var tests = "tests/Clientes.Tests/Clientes.Tests.csproj";
        var testsHead = "<PackageReference Include=\"xunit\" Version=\"2.5.3\" />";
        assertEquals(List.of(new DependencyRef("NUGET", "A", "1.0.0")), DevelopmentTeamStrategy.withPackagesInHead(
                Map.of(tests, List.of(new DependencyRef("NUGET", "A", "1.0.0"))), Map.of(tests, testsHead), components,
                List.of("Clientes")).get(tests));
    }

    // Verificado en vivo (MISSION-B1-CRM-1790971185): el resumen decía "backend backend/checkout".
    @Test
    void sandboxSummaryNamesEachStepOnce() {
        var summary = DevelopmentTeamStrategy.sandboxSummary(new SandboxResult("PASS", List.of(
                new SandboxResult.StepResult("backend/checkout", "PASS", 0, 0, "", 0, 0),
                new SandboxResult.StepResult("app/build", "PASS", 0, 2000, "", 0, 0))), null);
        assertEquals("backend checkout PASS (0 s) · app Build PASS (2 s)", summary);
    }

    // Verificado en vivo (MISSION-B1-CRM-1790978221, MISSION-B1-AGENDA-1790981861): la violación de capas se detectaba
    // recién en la validación final, sin volver a la dueña. Ahora es un reintento al generar, con el import exacto.
    @Test
    void layerViolationsInTheReturnedFilesAreRetryable() {
        var components = AppComponent.of(List.of(StackProfile.DOTNET_APP, StackProfile.FLUTTER_WEB_APP));
        var head = Map.of("app/pubspec.yaml", "name: forjai_crm\n",
                "app/lib/crm/infrastructure/crm_api_client.dart", "class CrmApiClient {}");
        var errors = DevelopmentTeamStrategy.layerErrors(components, List.of("Crm"), head, List.of(
                new GeneratedFile("app/lib/crm/application/crm_service.dart",
                        "import '../infrastructure/crm_api_client.dart';\nclass CrmService {}"),
                new GeneratedFile("app/lib/crm/domain/cliente.dart", "class Cliente {}")));
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("app/lib/crm/application/crm_service.dart"), errors.get(0));
        assertTrue(errors.get(0).contains("../infrastructure/crm_api_client.dart"), errors.get(0));

        assertEquals(List.of(), DevelopmentTeamStrategy.layerErrors(components, List.of("Crm"), head, List.of(
                new GeneratedFile("app/lib/crm/infrastructure/crm_api_client.dart",
                        "import 'package:forjai_crm/crm/application/clientes_repository.dart';"))));
    }

    // Verificado en vivo (MISSION-ARIA-CRM-1791093806, incremento 2): el import entre contextos lo dejó una corrida
    // fallida anterior; Mila no devolvió ese archivo y el chequeo al generar no lo vio. Un archivo propio en HEAD con
    // violación también es un reintento: la dueña tiene que devolverlo corregido.
    @Test
    void anOwnedFileAlreadyInTheRepositoryWithAViolationMustBeFixed() {
        var components = AppComponent.of(List.of(StackProfile.DOTNET_APP, StackProfile.FLUTTER_WEB_APP));
        var head = Map.of("app/pubspec.yaml", "name: forjai_crm\n",
                "app/lib/clientes/presentation/clientes_pantallas.dart", "class ClientesPantalla {}",
                "app/lib/oportunidades/presentation/oportunidades_pantallas.dart",
                "import '../../clientes/presentation/clientes_pantallas.dart';\nclass OportunidadesPantalla {}");
        var contexts = List.of("Clientes", "Oportunidades");

        var errors = DevelopmentTeamStrategy.layerErrors(components, contexts, head,
                List.of(new GeneratedFile("app/lib/oportunidades/domain/oportunidad.dart", "class Oportunidad {}")),
                List.of("app/lib/oportunidades/presentation"));
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("oportunidades_pantallas.dart") && errors.get(0).contains("ya está en el repositorio"),
                errors.get(0));

        // Si no es suyo, no se le exige.
        assertEquals(List.of(), DevelopmentTeamStrategy.layerErrors(components, contexts, head,
                List.of(new GeneratedFile("app/lib/oportunidades/domain/oportunidad.dart", "class Oportunidad {}")),
                List.of("app/lib/oportunidades/domain")));
    }

    // Diagnóstico 360 (decisión del fundador 2026-10-03): un test rojo ya no deja la misión FAILED sin más. Neo arbitra
    // (Vera no juzga sus propios tests), Java valida y la corrección va al dueño del código con la regla correcta.
    @Test
    void aFailingTestIsTriagedByNeoAndTheCodeOwnerFixesIt() throws Exception {
        stubFullstack();
        var failingRun = new SandboxResult("FAIL", List.of(new SandboxResult.StepResult("test", "FAIL", 1, 1, """
                  Failed Clientes.Tests.ClienteTests.Crear [3 ms]
                  Error Message:
                   Assert.Equal() Failure: Expected 201, Actual 500
                  Stack Trace:
                     at Clientes.Api.Program.Crear() in /work/src/Clientes.Api/Program.cs:line 3
                """, 2, 1)));
        var passingRun = new SandboxResult("PASS", List.of(new SandboxResult.StepResult("test", "PASS", 0, 1, "", 3, 0)));
        when(sandbox.verify(eq("M-1"), anyString(), eq("DOTNET_APP"), eq("backend"))).thenReturn(Optional.of(failingRun),
                Optional.of(passingRun));
        when(sandbox.verify(eq("M-1"), anyString(), eq("FLUTTER_WEB_APP"), eq("app"))).thenReturn(Optional.of(passingRun));
        when(runtime.triage(anyString(), eq("M-1"), eq("engineering"), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(new com.aicompany.core.agent.model.TestTriage(List.of(
                        new com.aicompany.core.agent.model.TestTriage.Verdict("Clientes.Tests.ClienteTests.Crear",
                                "CODE_WRONG", "Crear un cliente válido responde 201.", "Program.cs devuelve 500.",
                                List.of("backend/src/Clientes.Api/Program.cs"), List.of())))));
        when(workspace.commitAgentWork(eq("M-1"), eq("M-1-BACKEND"), eq("backend"), eq("Iris"), any()))
                .thenReturn(new DevelopmentWorkspaceService.CommitRecord(SHA_BACKEND, List.of("backend/src/Clientes.Api/Program.cs")));

        var result = (TeamExecutionResult.Development) strategy.execute(fullstackContext(), progress);

        verify(runtime).triage(anyString(), eq("M-1"), eq("engineering"), contains("Expected 201, Actual 500"), any());
        verify(runtime, atLeastOnce()).generate(eq("M-1-BACKEND"), eq("M-1"), eq("backend"),
                contains("Crear un cliente válido responde 201."), anyList(), anyList(), anyList(), any());
        verify(memory).recordStaticValidation(anyString(), eq("VERIFIED"), anyString());
        assertTrue(result.verifiableState().contains("Diagnóstico de tests (Neo): Clientes.Tests.ClienteTests.Crear → CODE_WRONG"),
                result.verifiableState());
    }

    // Diagnóstico 360: si el test está mal, lo corrige QA y Java no le deja borrarlo (guarda anti-trampa).
    @Test
    void aWrongTestGoesBackToQaWithTheAntiCheatGuard() throws Exception {
        stubFullstack();
        var base = fullstackContext();
        var tasks = new java.util.ArrayList<>(base.plan().tasksOrEmpty());
        tasks.add(2, new PlannedTask("qa", "WORK", "TESTS", "Tests", List.of("qa"), List.of("backend/tests/Clientes.Tests")));
        var p = base.plan();
        var plan = new TeamPlan(p.summary(), null, null, tasks, List.of(), p.stackProfile(), p.boundedContexts(),
                p.ubiquitousLanguage(), null, p.components());
        var context = new TeamMissionContext("M-1", base.instruction(), base.team(), plan);
        when(runtime.generate(eq("M-1-QA"), anyString(), anyString(), anyString(), anyList(), anyList(), anyList(), any()))
                .thenReturn(CompletableFuture.completedFuture(dev("backend/tests/Clientes.Tests/ClienteTests.cs")));
        when(runtime.generate(eq("M-1-QA"), anyString(), anyString(), anyString(), anyList(), anyList(), anyList(), any()))
                .thenReturn(CompletableFuture.completedFuture(dev("backend/tests/Clientes.Tests/ClienteTests.cs")));
        when(workspace.commitAgentWork(eq("M-1"), eq("M-1-QA"), eq("qa"), eq("Vera"), any()))
                .thenReturn(new DevelopmentWorkspaceService.CommitRecord("7".repeat(40),
                        List.of("backend/tests/Clientes.Tests/ClienteTests.cs")));
        when(workspace.filesAtCommit(eq("M-1"), anyString())).thenReturn(List.of("backend/src/Clientes.Api/Program.cs",
                "backend/tests/Clientes.Tests/ClienteTests.cs", "app/lib/main.dart"));
        var failingRun = new SandboxResult("FAIL", List.of(new SandboxResult.StepResult("test", "FAIL", 1, 1, """
                  Failed Clientes.Tests.ClienteTests.Crear [3 ms]
                  Error Message:
                   Expected 200, Actual 201
                  Stack Trace:
                     at Clientes.Tests.ClienteTests.Crear() in /work/tests/Clientes.Tests/ClienteTests.cs:line 9
                """, 2, 1)));
        var passingRun = new SandboxResult("PASS", List.of(new SandboxResult.StepResult("test", "PASS", 0, 1, "", 3, 0)));
        when(sandbox.verify(eq("M-1"), anyString(), eq("DOTNET_APP"), eq("backend"))).thenReturn(Optional.of(failingRun),
                Optional.of(passingRun));
        when(sandbox.verify(eq("M-1"), anyString(), eq("FLUTTER_WEB_APP"), eq("app"))).thenReturn(Optional.of(passingRun));
        when(runtime.triage(anyString(), eq("M-1"), eq("engineering"), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(new com.aicompany.core.agent.model.TestTriage(List.of(
                        new com.aicompany.core.agent.model.TestTriage.Verdict("Clientes.Tests.ClienteTests.Crear",
                                "TEST_WRONG", "Crear un cliente responde 201 Created.", "El test espera 200.",
                                List.of(), List.of("backend/tests/Clientes.Tests/ClienteTests.cs"))))));

        strategy.execute(context, progress);

        verify(runtime).generate(eq("M-1-QA"), eq("M-1"), eq("qa"), contains("No puedes tocar el código"), anyList(),
                anyList(), eq(List.of("backend/tests/Clientes.Tests/ClienteTests.cs")), any());
        verify(runtime, never()).generate(eq("M-1-BACKEND"), eq("M-1"), eq("backend"), contains("DIAGNÓSTICO"), anyList(),
                anyList(), anyList(), any());
    }

    // ---- QA casos de uso (spec 2026-10-05) ----

    private static final com.aicompany.core.agent.model.RequirementsSpec QA_SPEC = new com.aicompany.core.agent.model.RequirementsSpec(
            "CRM", List.of(), List.of(), List.of(new com.aicompany.core.agent.model.RequirementsSpec.Role("vendedor", "x")),
            List.of(new com.aicompany.core.agent.model.RequirementsSpec.UserStory("HU-01", "vendedor", "crear", "seguir",
                    List.of("c"), List.of(), List.of(
                            new com.aicompany.core.agent.model.RequirementsSpec.Scenario("ESC-01", "alta válida", List.of("d"), List.of("c"), List.of("e")),
                            new com.aicompany.core.agent.model.RequirementsSpec.Scenario("ESC-02", "alta sin nombre", List.of("d"), List.of("c"), List.of("e"))))),
            List.of(new com.aicompany.core.agent.model.RequirementsSpec.Increment(1, "MVP", List.of("HU-01"))));

    private TeamMissionContext withQaWork() throws Exception {
        var requirements = mock(RequirementsMemoryService.class);
        when(requirements.approved("M-1")).thenReturn(Optional.of(new RequirementsMemoryService.Version(1,
                tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(QA_SPEC), "APPROVED", "", null)));
        when(requirements.currentIncrement("M-1")).thenReturn(1);
        strategy.setRequirements(requirements);
        var base = fullstackContext();
        var tasks = new ArrayList<>(base.plan().tasksOrEmpty());
        tasks.add(new PlannedTask("qa", "WORK", "TESTS", "Tests", List.of("qa"), List.of("backend/tests", "app/test")));
        var p = base.plan();
        var plan = new TeamPlan(p.summary(), null, null, tasks, List.of(), p.stackProfile(), p.boundedContexts(),
                p.ubiquitousLanguage(), null, p.components());
        var qa = CompletableFuture.completedFuture(dev("backend/tests/ClientesTests.cs"));
        when(runtime.generate(eq("M-1-QA"), anyString(), anyString(), anyString(), anyList(), anyList(), anyList(), any())).thenReturn(qa);
        when(workspace.commitAgentWork(eq("M-1"), eq("M-1-QA"), eq("qa"), eq("Vera"), any()))
                .thenReturn(new DevelopmentWorkspaceService.CommitRecord("4".repeat(40), List.of("backend/tests/ClientesTests.cs")));
        return new TeamMissionContext("M-1", "un CRM simple", base.team(), plan);
    }

    @SuppressWarnings("unchecked")
    private java.util.function.Function<DevelopmentResult, List<String>> checksOf(String taskId) {
        var checks = (org.mockito.ArgumentCaptor<java.util.function.Function<DevelopmentResult, List<String>>>)
                (org.mockito.ArgumentCaptor<?>) org.mockito.ArgumentCaptor.forClass(java.util.function.Function.class);
        verify(runtime, atLeastOnce()).generate(eq(taskId), anyString(), anyString(), anyString(), anyList(),
                anyList(), anyList(), checks.capture());
        return checks.getValue();
    }

    // Spec QA §1: Vera recibe los escenarios y Java exige un test por escenario al generar.
    @Test
    void veraGetsTheScenariosAndEveryScenarioNeedsATest() throws Exception {
        stubFullstack();
        var context = withQaWork();

        strategy.execute(context, progress);

        verify(runtime).generate(eq("M-1-QA"), anyString(), eq("qa"),
                argThat(p -> p.contains("ESC-01 alta válida") && p.contains("ESC-02 alta sin nombre")), anyList(), anyList(), anyList(), any());
        var errors = checksOf("M-1-QA").apply(new DevelopmentResult("r", List.of(new GeneratedFile(
                "backend/tests/ClientesTests.cs", "[Fact] public void ESC_01_alta_valida() {}"))));
        assertTrue(errors.stream().anyMatch(e -> e.contains("ESC-02")), errors.toString());
        assertEquals(List.of(), checksOf("M-1-QA").apply(new DevelopmentResult("r", List.of(new GeneratedFile(
                "backend/tests/ClientesTests.cs", "[Fact] public void ESC_01_a() {}\n[Fact] public void ESC_02_b() {}")))));
    }

    // Spec QA §4: un test del último VERIFIED no se puede borrar, en ninguna tarea.
    @Test
    void aVerifiedTestCannotDisappear() throws Exception {
        stubFullstack();
        var context = withQaWork();
        var verified = "7".repeat(40);
        when(memory.verifiedCommit("M-1")).thenReturn(Optional.of(verified));
        when(workspace.filesAtCommit("M-1", verified)).thenReturn(List.of("backend/tests/ViejosTests.cs"));
        when(workspace.readFileAtCommit("M-1", verified, "backend/tests/ViejosTests.cs"))
                .thenReturn("[Fact] public void ESC_01_viejo() {}");

        strategy.execute(context, progress);

        var errors = checksOf("M-1-BACKEND").apply(new DevelopmentResult("r", List.of(new GeneratedFile(
                "backend/src/Clientes.Api/Program.cs", PROGRAM))));
        assertTrue(errors.stream().anyMatch(e -> e.contains("ESC_01_viejo") && e.contains("borraste o renombraste")),
                errors.toString());
    }

    // Sin requisitos de Aria: ni regla ni gates nuevos.
    @Test
    void withoutRequirementsNothingChanges() {
        assertEquals(List.of(), strategy.scenarios("M-1"));
    }

    private static Optional<SandboxResult> runWith(String... passed) {
        var tests = java.util.Arrays.stream(passed).map(n -> new SandboxResult.TestCase(n, "PASSED")).toList();
        return Optional.of(new SandboxResult("PASS", List.of(new SandboxResult.StepResult("test", "PASS", 0, 1, "",
                tests.size(), 0, tests))));
    }

    // Spec QA §3 y §5: con escenarios, VERIFIED exige cobertura completa y la revisión de Vera no llama al modelo.
    @Test
    void withScenariosCoverageDecidesAndVeraIsNotAskedToReview() throws Exception {
        stubFullstack();
        var context = withQaWork();
        when(sandbox.verify(eq("M-1"), anyString(), anyString(), anyString()))
                .thenReturn(runWith("Crm.ESC_01_alta_valida", "ESC-02 alta sin nombre"));

        strategy.execute(context, progress);

        verify(runtime, never()).review(anyString(), anyString(), anyString(), anyString(), anyMap());
        verify(memory).recordStaticValidation(eq("M-1-QA-REVIEW"), eq("VERIFIED"),
                argThat(json -> json.contains("SCENARIO_COVERAGE") && json.contains("2/2")));
        verify(memory).updateTask(eq("M-1-QA-REVIEW"), eq("COMPLETED"), argThat(r -> r.contains("✅ ESC-01")));
    }

    @Test
    void aScenarioWithoutAPassingTestFailsTheMission() throws Exception {
        stubFullstack();
        var context = withQaWork();
        when(sandbox.verify(eq("M-1"), anyString(), anyString(), anyString())).thenReturn(runWith("Crm.ESC_01_alta_valida"));

        strategy.execute(context, progress);

        verify(memory).recordStaticValidation(eq("M-1-QA-REVIEW"), eq("FAILED"),
                argThat(json -> json.contains("ESC-02") && json.contains("1/2")));
    }

    // Review Focus 5: sin requisitos de Aria la revisión de Vera sigue como hoy.
    @Test
    void withoutScenariosVeraStillReviews() throws Exception {
        stubFullstack();
        when(sandbox.verify(eq("M-1"), anyString(), anyString(), anyString())).thenReturn(Optional.of(
                new SandboxResult("PASS", List.of(new SandboxResult.StepResult("test", "PASS", 0, 1, "", 3, 0)))));

        strategy.execute(fullstackContext(), progress);

        verify(runtime).review(anyString(), eq("M-1"), eq("qa"), anyString(), anyMap());
    }

    // Revisión final (I1): si el sandbox no corrió (p. ej. dependencias pendientes), UNVALIDATED, no FAILED.
    @Test
    void withScenariosAndNoSandboxTheMissionIsUnvalidated() throws Exception {
        stubFullstack();
        var context = withQaWork();
        when(sandbox.verify(eq("M-1"), anyString(), anyString(), anyString())).thenReturn(Optional.empty());

        strategy.execute(context, progress);

        verify(memory).recordStaticValidation(eq("M-1-QA-REVIEW"), eq("UNVALIDATED"),
                argThat(json -> !json.contains("SCENARIO_COVERAGE")));
    }

    // Revisión final (declinado → spec §3): sin requisitos de Aria no hay gate de crecimiento.
    @Test
    void withoutRequirementsThereIsNoGrowthGate() throws Exception {
        stubFullstack();
        when(memory.verifiedCommit("M-1")).thenReturn(Optional.of("7".repeat(40)));
        when(sandbox.verify(eq("M-1"), anyString(), anyString(), anyString())).thenReturn(Optional.of(
                new SandboxResult("PASS", List.of(new SandboxResult.StepResult("test", "PASS", 0, 1, "", 3, 0)))));

        strategy.execute(fullstackContext(), progress);

        verify(memory, never()).verifiedCommit("M-1");
    }

    // Revisión 2026-10-06 §5 (manos humanas): verifica HEAD sin llamar a ningún modelo ni tocar el repositorio.
    @Test
    void verifyHeadRunsChecksSandboxAndCoverageOnHeadWithoutAnyModel() throws Exception {
        stubFullstack();
        var context = withQaWork();
        var head = "h".repeat(40);
        when(workspace.headIfAny("M-1")).thenReturn(Optional.of(head));
        when(sandbox.verify(eq("M-1"), anyString(), anyString(), anyString()))
                .thenReturn(runWith("Crm.ESC_01_alta_valida", "ESC-02 alta sin nombre"));

        var result = (TeamExecutionResult.Development) strategy.verifyHead(context, "M-1-HUMAN-VERIFY-1");

        verifyNoInteractions(runtime);
        verify(workspace, never()).commitAgentWork(any(), any(), any(), any(), any());
        verify(sandbox).verify("M-1", head, "DOTNET_APP", "backend");
        verify(sandbox).verify("M-1", head, "FLUTTER_WEB_APP", "app");
        // Los commits del desarrollador no llevan autor de agente ni trailer: solo se valida HEAD.
        verify(validator).validate(eq("M-1"), eq(List.of()), anyList(), anyList(), anyList(), anyList());
        verify(memory).recordSandboxResult(eq("M-1-HUMAN-VERIFY-1"), anyString());
        verify(memory).updateTask(eq("M-1-HUMAN-VERIFY-1"), eq("COMPLETED"), argThat(r -> r.contains("✅ ESC-01")));
        verify(memory).recordStaticValidation(eq("M-1-HUMAN-VERIFY-1"), eq("VERIFIED"),
                argThat(json -> json.contains("SCENARIO_COVERAGE") && json.contains("TEST_SUITE_GROWTH")));
        verify(memory).recordVerifiedCommit("M-1", head);
        assertTrue(result.verifiableState().contains(head), result.verifiableState());
    }

    private TeamMissionContext withQaWorkAndDatabase() throws Exception {
        var base = withQaWork();
        var p = base.plan();
        var plan = new TeamPlan(p.summary(), null, null, p.tasksOrEmpty(), List.of(), p.stackProfile(),
                p.boundedContexts(), p.ubiquitousLanguage(), new TeamPlan.DatabaseNeed("POSTGRESQL", ""), p.components());
        return new TeamMissionContext("M-1", base.instruction(), base.team(), plan);
    }

    // Revisión final Diego ambientes (I3): "verifica" corre la misma cascada; un SQL roto sin aplicar deja FAILED.
    @Test
    void verifyHeadRunsTheCascadeAndASqlErrorFailsIt() throws Exception {
        stubFullstack();
        var context = withQaWorkAndDatabase();
        var head = "h".repeat(40);
        when(workspace.headIfAny("M-1")).thenReturn(Optional.of(head));
        when(sandbox.verify(eq("M-1"), anyString(), anyString(), anyString()))
                .thenReturn(runWith("Crm.ESC_01_alta_valida", "ESC-02 alta sin nombre"));
        realDatabaseApply(DB_TEST);
        when(dbApplier.apply(eq(DB_TEST), any(), anyList(), eq("M-1"), eq(head))).thenReturn(failedAt(1, "V1 falló: syntax error"));

        var result = (TeamExecutionResult.Development) strategy.verifyHead(context, "M-1-HUMAN-VERIFY-1");

        verifyNoInteractions(runtime);
        verify(memory).recordStaticValidation(eq("M-1-HUMAN-VERIFY-1"), eq("FAILED"),
                argThat(json -> json.contains("DATABASE_APPLIED") && json.contains("syntax error")));
        verify(memory, never()).recordVerifiedCommit(anyString(), anyString());
        assertTrue(result.verifiableState().contains("Base de datos:"), result.verifiableState());
    }

    // Seguimiento: "verifica" lo pidió el fundador y el chat ya muestra el problema: no se le manda correo.
    @Test
    void verifyHeadDoesNotEmailAnEnvironmentProblem() throws Exception {
        stubFullstack();
        var context = withQaWorkAndDatabase();
        var head = "h".repeat(40);
        when(workspace.headIfAny("M-1")).thenReturn(Optional.of(head));
        when(sandbox.verify(eq("M-1"), anyString(), anyString(), anyString()))
                .thenReturn(runWith("Crm.ESC_01_alta_valida", "ESC-02 alta sin nombre"));
        realDatabaseApply(DB_TEST);
        when(dbApplier.apply(eq(DB_TEST), any(), anyList(), eq("M-1"), eq(head)))
                .thenReturn(failedAt(1, "V1 falló: connection reset", "08006"));

        var result = (TeamExecutionResult.Development) strategy.verifyHead(context, "M-1-HUMAN-VERIFY-1");

        assertTrue(result.verifiableState().contains("problema del ambiente"), result.verifiableState());
        verifyNoInteractions(dbMail);
    }

    @Test
    void verifyHeadAppliesTheSchemaBeforeRecordingTheVerifiedCommit() throws Exception {
        stubFullstack();
        var context = withQaWorkAndDatabase();
        var head = "h".repeat(40);
        when(workspace.headIfAny("M-1")).thenReturn(Optional.of(head));
        when(sandbox.verify(eq("M-1"), anyString(), anyString(), anyString()))
                .thenReturn(runWith("Crm.ESC_01_alta_valida", "ESC-02 alta sin nombre"));
        realDatabaseApply(DB_TEST);
        when(dbApplier.apply(eq(DB_TEST), any(), anyList(), eq("M-1"), eq(head))).thenReturn(applied(1));

        strategy.verifyHead(context, "M-1-HUMAN-VERIFY-1");

        var order = inOrder(dbApplier, memory);
        order.verify(dbApplier).apply(eq(DB_TEST), any(), anyList(), eq("M-1"), eq(head));
        order.verify(memory).recordVerifiedCommit("M-1", head);
        verify(memory).recordStaticValidation(eq("M-1-HUMAN-VERIFY-1"), eq("VERIFIED"),
                argThat(json -> json.contains("DATABASE_APPLIED") && !json.contains("\"FAIL\"")));
    }

    // Review Focus 5: lo no commiteado no se borra ni se verifica, y se avisa.
    @Test
    void verifyHeadDoesNotTouchUncommittedFilesAndSaysSo() throws Exception {
        stubFullstack();
        var context = withQaWork();
        when(workspace.headIfAny("M-1")).thenReturn(Optional.of("h".repeat(40)));
        when(workspace.hasUncommitted("M-1")).thenReturn(true);
        when(sandbox.verify(eq("M-1"), anyString(), anyString(), anyString()))
                .thenReturn(runWith("Crm.ESC_01_alta_valida"));

        var result = (TeamExecutionResult.Development) strategy.verifyHead(context, "M-1-HUMAN-VERIFY-1");

        verify(workspace, never()).discardUncommitted(anyString());
        verify(workspace, never()).dropHeadCommit(anyString());
        verify(memory).updateTask(eq("M-1-HUMAN-VERIFY-1"), eq("COMPLETED"), argThat(r -> r.contains("sin commit")));
        verify(memory).recordStaticValidation(eq("M-1-HUMAN-VERIFY-1"), eq("FAILED"), anyString());
        verify(memory, never()).recordVerifiedCommit(anyString(), anyString());
        assertTrue(result.verifiableState().contains("sin commit"), result.verifiableState());
    }

    // Sin escenarios de Aria la revisión sería de Vera (modelo): la verificación humana no la pide y queda UNVALIDATED.
    @Test
    void verifyHeadWithoutScenariosNeverAsksVeraAndIsUnvalidated() throws Exception {
        stubFullstack();
        when(workspace.headIfAny("M-1")).thenReturn(Optional.of("h".repeat(40)));
        when(sandbox.verify(eq("M-1"), anyString(), anyString(), anyString())).thenReturn(Optional.of(
                new SandboxResult("PASS", List.of(new SandboxResult.StepResult("test", "PASS", 0, 1, "", 3, 0)))));

        strategy.verifyHead(fullstackContext(), "M-1-HUMAN-VERIFY-1");

        verifyNoInteractions(runtime);
        verify(memory).recordStaticValidation(eq("M-1-HUMAN-VERIFY-1"), eq("UNVALIDATED"), anyString());
    }

    // Revisión final QA (M4): un commit no cambia; los tests de un SHA se leen de git una sola vez por generación de misión.
    @Test
    void theTestsOfACommitAreReadFromGitOnce() throws Exception {
        when(workspace.filesAtCommit("M-1", "c".repeat(40))).thenReturn(List.of("tests/Crm.Tests/ClientesTests.cs", "src/X.cs"));
        when(workspace.readFileAtCommit("M-1", "c".repeat(40), "tests/Crm.Tests/ClientesTests.cs")).thenReturn("[Fact] void ESC_01_x() {}");

        var first = strategy.testFiles("M-1", "c".repeat(40));
        var second = strategy.testFiles("M-1", "c".repeat(40));

        assertEquals(first, second);
        assertEquals(java.util.Set.of("tests/Crm.Tests/ClientesTests.cs"), first.keySet());
        verify(workspace, times(1)).filesAtCommit("M-1", "c".repeat(40));
    }

    @Test
    void aFailedReadIsNotCached() throws Exception {
        when(workspace.filesAtCommit("M-1", "d".repeat(40))).thenThrow(new java.io.IOException("git show falló"))
                .thenReturn(List.of("tests/Crm.Tests/ClientesTests.cs"));
        when(workspace.readFileAtCommit("M-1", "d".repeat(40), "tests/Crm.Tests/ClientesTests.cs")).thenReturn("x");

        assertTrue(strategy.testFiles("M-1", "d".repeat(40)).isEmpty());
        assertEquals(1, strategy.testFiles("M-1", "d".repeat(40)).size());
    }

    /** Misión de 2 tareas WORK (Neo, Mila) + revisión de Vera, lista para ejecutar y retomar. */
    private TeamMissionContext resumableContext() throws Exception {
        stubHappyPath();
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));
        return context();
    }

    // Spec misiones durables §2: retomar salta las tareas ya commiteadas (con sus paquetes) y limpia el workspace.
    @Test
    void resumingSkipsCommittedTasksAndCleansTheWorkspace() throws Exception {
        var context = resumableContext();
        when(workspace.headIfAny("M-1")).thenReturn(Optional.of("a".repeat(40)));
        when(workspace.headSha("M-1")).thenReturn("a".repeat(40));
        when(workspace.headTask("M-1")).thenReturn(Optional.of("M-1-ENGINEERING"));
        when(memory.tasks("M-1")).thenReturn(List.of(new AgentTask("M-1-ENGINEERING", "M-1", "engineering", "ARCHITECTURE",
                "COMPLETED", "{\"summary\":\"api\",\"files\":[],\"packages\":[{\"name\":\"Npgsql\",\"version\":\"8.0.5\"}]}",
                java.time.Instant.now(), "WORK", "/w", "a".repeat(40), List.of("game/Game.cs"), null, null)));

        strategy.execute(context, progress);

        verify(workspace).discardUncommitted("M-1");
        verify(workspace, never()).dropHeadCommit("M-1");
        verify(runtime, never()).generate(eq("M-1-ENGINEERING"), any(), any(), any(), any(), any(), anyList(), any());
        verify(runtime, never()).generate(eq("M-1-ENGINEERING"), any(), any(), any(), any(), any(), any(), any());
        verify(runtime, never()).generate(eq("M-1-ENGINEERING"), any(), any(), any(), any(), any(), any(), any());
        // La otra tarea sí se genera; los paquetes de la ya hecha llegan al commit de dependencias.
        verify(runtime).generate(eq("M-1-FRONTEND-UI"), anyString(), anyString(), anyString(), anyList(), anyList(), anyList(), any());
        verify(workspace, never()).commitAgentWork(eq("M-1"), eq("M-1-ENGINEERING"), any(), any(), any());
    }

    // Revisión 2026-10-06 §5 (fix 1): verificación humana FAILED con cambios sin commit → la corrección que sigue los
    // guarda en git stash (nunca un reset destructivo) y el estado verificable lo dice.
    @Test
    void aCorrectionAfterAFailedHumanVerificationStashesTheUncommittedWork() throws Exception {
        var context = resumableContext();
        when(workspace.headIfAny("M-1")).thenReturn(Optional.of("a".repeat(40)));
        when(workspace.headSha("M-1")).thenReturn("a".repeat(40));
        when(workspace.headTask("M-1")).thenReturn(Optional.empty());
        when(workspace.hasUncommitted("M-1")).thenReturn(true);
        when(memory.tasks("M-1")).thenReturn(List.of());
        when(workspace.discardUncommitted("M-1")).thenReturn(Optional.of("Forjai: sin commit antes de retomar 2026-10-06T12:00:00Z"));

        var human = (TeamExecutionResult.Development) strategy.verifyHead(context, "M-1-HUMAN-VERIFY-1");
        verify(workspace, never()).discardUncommitted(anyString());
        assertTrue(human.verifiableState().contains("git stash"), human.verifiableState());

        // La limpieza con stash es la misma en toda ronda con repositorio (la corrección es una ronda más).
        var result = (TeamExecutionResult.Development) strategy.execute(context, progress);

        verify(workspace).discardUncommitted("M-1");
        verify(workspace, never()).dropHeadCommit(anyString());
        assertTrue(result.verifiableState().contains("quedó guardado en git stash (Forjai: sin commit antes de retomar"),
                result.verifiableState());
    }

    // Fix 4: sin componentes el sandbox no corre, pero no porque fallaran los chequeos.
    @Test
    void verifyHeadWithoutComponentsSaysSo() throws Exception {
        var context = resumableContext();
        when(workspace.headIfAny("M-1")).thenReturn(Optional.of("a".repeat(40)));
        var p = context.plan();
        var noComponents = new TeamPlan(p.summary(), null, null, p.tasksOrEmpty(), List.of(), null, p.boundedContexts(),
                p.ubiquitousLanguage());
        when(validator.validate(eq("M-1"), anyList(), anyList(), anyList(), anyList(), anyList()))
                .thenReturn(List.of(StaticCheck.pass("DDD_LAYERS", "ok", null, List.of())));

        var result = (TeamExecutionResult.Development) strategy.verifyHead(
                new TeamMissionContext("M-1", "x", context.team(), noComponents), "M-1-HUMAN-VERIFY-1");

        assertFalse(result.verifiableState().contains("chequeos deterministas fallaron"), result.verifiableState());
        assertTrue(result.verifiableState().contains("no declara componentes"), result.verifiableState());
    }

    // Review Focus 1: el último commit es de una tarea que no quedó COMPLETED -> se retrocede y la tarea se repite.
    @Test
    void anUnrecordedLastCommitIsDroppedAndItsTaskRepeated() throws Exception {
        var context = resumableContext();
        when(workspace.headIfAny("M-1")).thenReturn(Optional.of("a".repeat(40)));
        when(workspace.headSha("M-1")).thenReturn("a".repeat(40));
        when(workspace.headHasParent("M-1")).thenReturn(true);
        when(workspace.headTask("M-1")).thenReturn(Optional.of("M-1-ENGINEERING"));
        when(memory.tasks("M-1")).thenReturn(List.of(new AgentTask("M-1-ENGINEERING", "M-1", "engineering", "ARCHITECTURE",
                "RUNNING", null, java.time.Instant.now(), "WORK", null, null, null, null, null)));

        strategy.execute(context, progress);

        verify(workspace).dropHeadCommit("M-1");
        verify(runtime).generate(eq("M-1-ENGINEERING"), anyString(), anyString(), anyString(), anyList(), anyList(), anyList(), any());
    }

    // Verificado en vivo (MISSION-DUR-1791333307): un reinicio durante una ronda de reparación deja la tarea WORK en
    // RUNNING con su commit original; esa entrega no se regenera ni se retrocede (la verificación se repite entera).
    @Test
    void aTaskInterruptedDuringARepairKeepsItsCommittedDelivery() throws Exception {
        var context = resumableContext();
        when(workspace.headIfAny("M-1")).thenReturn(Optional.of("b".repeat(40)));
        when(workspace.headSha("M-1")).thenReturn("b".repeat(40));
        when(workspace.headHasParent("M-1")).thenReturn(true);
        when(workspace.headTask("M-1")).thenReturn(Optional.of("M-1-ENGINEERING"));
        when(memory.tasks("M-1")).thenReturn(List.of(new AgentTask("M-1-ENGINEERING", "M-1", "engineering", "ARCHITECTURE",
                "RUNNING", "{\"summary\":\"api\",\"files\":[]}", java.time.Instant.now(), "WORK", "/w", "b".repeat(40),
                List.of("src/Api/X.cs"), null, null)));

        try {
            strategy.execute(context, progress);
        } catch (IllegalStateException ignored) {
            // El resto del flujo (otras tareas, sandbox) no está armado en este test; lo que importa es lo de arriba.
        }

        verify(workspace, never()).dropHeadCommit("M-1");
        verify(runtime, never()).generate(eq("M-1-ENGINEERING"), anyString(), anyString(), anyString(), anyList(), anyList(), anyList(), any());
    }

    // El commit raíz no se retrocede (la tarea se regenera encima).
    @Test
    void anUnrecordedRootCommitIsNotDropped() throws Exception {
        var context = resumableContext();
        when(workspace.headIfAny("M-1")).thenReturn(Optional.of("a".repeat(40)));
        when(workspace.headSha("M-1")).thenReturn("a".repeat(40));
        when(workspace.headHasParent("M-1")).thenReturn(false);
        when(workspace.headTask("M-1")).thenReturn(Optional.of("M-1-ENGINEERING"));
        when(memory.tasks("M-1")).thenReturn(List.of(new AgentTask("M-1-ENGINEERING", "M-1", "engineering", "ARCHITECTURE",
                "RUNNING", null, java.time.Instant.now(), "WORK", null, null, null, null, null)));

        strategy.execute(context, progress);

        verify(workspace, never()).dropHeadCommit(anyString());
    }

    // Ronda 0 con workspace: el scaffold existente se reutiliza (no se vuelve a escribir encima del código).
    @Test
    void aRound0WithWorkspaceReusesTheScaffold() throws Exception {
        var context = resumableContext();
        when(workspace.headIfAny("M-1")).thenReturn(Optional.of("a".repeat(40)));
        when(workspace.headSha("M-1")).thenReturn("a".repeat(40));
        when(workspace.headTask("M-1")).thenReturn(Optional.empty());
        when(memory.tasks("M-1")).thenReturn(List.of());

        strategy.execute(context, progress);

        verify(workspace, never()).commitAgentWork(eq("M-1"), eq("M-1-SCAFFOLD"), any(), any(), any());
    }

    @Test
    void inAGameVeraAlsoWritesPlaythroughs() {
        var rule = DevelopmentTeamStrategy.qaScenarioRule(
                List.of(new com.aicompany.core.agent.validation.ScenarioCoverage.Scenario("ESC-01", "mueve", 1)), true);
        assertTrue(rule.contains("game/playthroughs/") && rule.contains(": Playthrough") && rule.contains("Press("), rule);
    }

    @Test
    void outsideAGameVeraGetsNoPlaythroughRule() {
        var rule = DevelopmentTeamStrategy.qaScenarioRule(
                List.of(new com.aicompany.core.agent.validation.ScenarioCoverage.Scenario("ESC-01", "mueve", 1)), false);
        assertFalse(rule.contains("Playthrough"), rule);
    }
}
