package com.aicompany.core.service;

import com.aicompany.core.agent.DevelopmentRuntime;
import com.aicompany.core.agent.model.AgentResult;
import com.aicompany.core.agent.model.DevelopmentResult;
import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;
import com.aicompany.core.agent.model.Mockups;
import com.aicompany.core.agent.model.StaticReviewResult;
import com.aicompany.core.agent.model.TeamPlan;
import com.aicompany.core.agent.model.TeamPlan.PlannedTask;
import com.aicompany.core.agent.model.VisualDesign;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.SandboxResult;
import com.aicompany.core.model.StaticCheck;
import com.aicompany.core.model.TeamMemberInfo;
import com.aicompany.core.model.TeamMissionContext;
import com.aicompany.core.model.TeamSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Revisión final del diseño visual (C1): con un repositorio Git REAL y el StaticWorkspaceValidator REAL, una misión con
 * maquetas commiteadas por Forjai (.forjai/mockups) y diseño visual aprobado (lib/theme, lib/forjai, assets/svg) pasa
 * los chequeos de estructura en la ronda 0; y una misión que Neo planificó solo como DOTNET_APP también.
 */
class DevelopmentTeamStrategyVisualRepoTest {

    @TempDir
    Path root;

    private final JsonMapper json = JsonMapper.builder().build();
    private final MissionMemoryService memory = mock(MissionMemoryService.class);
    private final DevelopmentRuntime runtime = mock(DevelopmentRuntime.class);
    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    private final MissionProgress progress = mock(MissionProgress.class);
    private final SandboxRunnerClient sandbox = mock(SandboxRunnerClient.class);
    private final DependencyService dependencies = mock(DependencyService.class);
    private final VisualDesignMemoryService visuals = mock(VisualDesignMemoryService.class);
    private final GitCommandRunner git = new GitCommandRunner();
    private DevelopmentWorkspaceService workspace;
    private DevelopmentTeamStrategy strategy;

    static VisualDesign design() {
        return new VisualDesign(VisualDesignWriterTest.system("#FFFFFF"), new Mockups(List.of(
                new Mockups.Mockup("PANT-01", "<div data-id=\"PANT-01\"><span data-id=\"titulo\">x</span></div>")),
                List.of(new Mockups.SvgAsset("logo", "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>"))));
    }

    @BeforeEach
    void setUp() throws Exception {
        workspace = new DevelopmentWorkspaceService(root.toString(), git);
        strategy = new DevelopmentTeamStrategy(memory, runtime, workspace, new StaticWorkspaceValidator(workspace, git),
                events, json, sandbox, dependencies);
        strategy.setVisualDesign(visuals);
        when(visuals.approved("M-1")).thenReturn(Optional.of(new VisualDesignMemoryService.Version(1,
                json.writeValueAsString(design()), "APPROVED", 1, 1, "")));
        when(dependencies.resolve(anyString(), anyString(), anyList()))
                .thenReturn(new DependencyService.Outcome(List.of(), List.of(), null));
        when(sandbox.verify(eq("M-1"), anyString(), anyString())).thenReturn(Optional.of(new SandboxResult("PASS", List.of(
                new SandboxResult.StepResult("build", "PASS", 0, 1000, "", 0, 0),
                new SandboxResult.StepResult("test", "PASS", 0, 1000, "", 3, 0),
                new SandboxResult.StepResult("smoke", "PASS", 0, 1000, "", 0, 0)))));
        when(runtime.review(anyString(), eq("M-1"), eq("qa"), anyString(), anyMap())).thenReturn(CompletableFuture.completedFuture(
                new StaticReviewResult("NO_EVIDENT_ISSUES", List.of(), List.of(), "Coherente.", List.of("x"),
                        List.of(new AgentResult.Evidence("main", "workspace:M-1@x/lib/main.dart", "INTERNAL", true)))));
        // Lo que Forjai commitea antes de la aprobación (DesignCaptureService.captureMockups): HEAD existe en la ronda 0.
        workspace.commitAgentWork("M-1", "M-1-MOCKUPS", "forjai", "Forjai", new DevelopmentResult("Maquetas", List.of(
                new GeneratedFile(".forjai/mockups/PANT-01.html", "<html><div data-id=\"PANT-01\"></div></html>"),
                new GeneratedFile(".forjai/mockups/svg/logo.svg", "<svg></svg>"))));
    }

    private static TeamMissionContext context(String profile, List<String> contexts, PlannedTask front) {
        var team = new TeamSnapshot("TEAM-DEVELOPMENT", "Engineering Team", "ACTIVE", "engineering", List.of(
                new TeamMemberInfo("engineering", "Neo", "Arquitecto", "R", List.of("arquitectura"), "m"),
                new TeamMemberInfo(front.agentId(), "Mila", "UI", "R", List.of("UI"), "m"),
                new TeamMemberInfo("qa", "Vera", "QA", "R", List.of("QA"), "m")));
        var plan = new TeamPlan("App", null, null, List.of(front,
                new PlannedTask("qa", "VALIDATION", "STATIC_REVIEW", "Revisar", List.of("QA"), List.of())),
                List.of(), profile, contexts.stream().map(c -> new TeamPlan.BoundedContext(c, "d")).toList(),
                List.of(new TeamPlan.GlossaryTerm("Pedido", "x"), new TeamPlan.GlossaryTerm("Cliente", "y"),
                        new TeamPlan.GlossaryTerm("Total", "z")));
        return new TeamMissionContext("M-1", "crear una app", team, plan);
    }

    private List<StaticCheck> recordedChecks() {
        var captor = ArgumentCaptor.forClass(String.class);
        verify(memory).recordStaticValidation(anyString(), anyString(), captor.capture());
        return Arrays.asList(json.readValue(captor.getValue(), StaticCheck[].class));
    }

    private void frontReturns(String agentId, List<GeneratedFile> files) {
        var result = CompletableFuture.completedFuture(new DevelopmentResult("app", files));
        when(runtime.generate(anyString(), eq("M-1"), eq(agentId), anyString(), anyList(), anyList())).thenReturn(result);
        when(runtime.generate(anyString(), eq("M-1"), eq(agentId), anyString(), anyList(), anyList(), anyList()))
                .thenReturn(result);
        when(runtime.generate(anyString(), eq("M-1"), eq(agentId), anyString(), anyList(), anyList(), anyList(), any()))
                .thenReturn(result);
    }

    @Test
    void aFlutterMissionWithMockupsAndAnApprovedVisualDesignPassesTheRealStructureChecks() throws Exception {
        frontReturns("frontend-ui", List.of(
                new GeneratedFile("pubspec.yaml", "name: pedidos\nenvironment:\n  sdk: '>=3.0.0 <4.0.0'\n"
                        + "dependencies:\n  flutter:\n    sdk: flutter\n"),
                new GeneratedFile("lib/main.dart", "import 'package:flutter/material.dart';\n"
                        + "import 'forjai/preview.dart';\nimport 'pedidos/presentation/pedidos_page.dart';\n"
                        + "void main() { runApp(forjaiPreviewOr(const PedidosPage())); }\n"),
                new GeneratedFile("lib/pedidos/domain/pedido.dart", "class Pedido { const Pedido(); }\n"),
                new GeneratedFile("lib/pedidos/presentation/pedidos_page.dart", "import 'package:flutter/material.dart';\n"
                        + "class PedidosPage extends StatelessWidget {\n  const PedidosPage({super.key});\n"
                        + "  @override\n  Widget build(BuildContext context) => const SizedBox();\n}\n")));
        var front = new PlannedTask("frontend-ui", "WORK", "UI", "App", List.of("UI"),
                List.of("pubspec.yaml", "lib/main.dart", "lib/pedidos"));

        strategy.execute(context("FLUTTER_WEB_APP", List.of("pedidos"), front), progress);

        var head = workspace.filesAtCommit("M-1", workspace.headSha("M-1"));
        assertTrue(head.containsAll(List.of(".forjai/mockups/PANT-01.html", "lib/theme/app_theme.dart",
                "lib/forjai/preview.dart", "lib/forjai/previews.dart", "assets/svg/logo.svg")), head.toString());
        var checks = recordedChecks();
        assertTrue(checks.stream().allMatch(StaticCheck::passed),
                checks.stream().filter(c -> !c.passed()).toList().toString());
        // Las maquetas no entran al contexto de la revisión de Vera.
        var reviewPrompt = ArgumentCaptor.forClass(String.class);
        verify(runtime).review(anyString(), eq("M-1"), eq("qa"), reviewPrompt.capture(), anyMap());
        assertFalse(reviewPrompt.getValue().contains(".forjai/mockups"), reviewPrompt.getValue());
        assertTrue(reviewPrompt.getValue().contains("lib/pedidos/domain/pedido.dart"));
    }

    @Test
    void aMissionPlannedOnlyAsDotnetWithMockupsPassesTheRealStructureChecks() throws Exception {
        frontReturns("backend", List.of(
                new GeneratedFile("Pedidos.sln", "Microsoft Visual Studio Solution File"),
                new GeneratedFile("src/Pedidos.Domain/Pedido.cs", "namespace Pedidos.Domain;\npublic class Pedido {}\n")));
        var front = new PlannedTask("backend", "WORK", "API", "Backend", List.of("API"),
                List.of("Pedidos.sln", "src/Pedidos.Domain", "src/Pedidos.Application", "src/Pedidos.Infrastructure",
                        "src/Pedidos.Api", "tests/Pedidos.Tests"));

        strategy.execute(context("DOTNET_APP", List.of("Pedidos"), front), progress);

        var checks = recordedChecks();
        assertTrue(checks.stream().allMatch(StaticCheck::passed),
                checks.stream().filter(c -> !c.passed()).toList().toString());
    }
}
