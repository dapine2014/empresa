package com.aicompany.core.service;

import com.aicompany.core.agent.model.TeamPlan;
import com.aicompany.core.agent.model.TeamPlan.PlannedTask;
import com.aicompany.core.agent.validation.TeamPlanResolver;
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
            new TeamPlanValidator(), new TeamPlanResolver(), JsonMapper.builder().build(), "qwen3:8b");

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

    // Verificado en vivo (2026-09-29, glm-5.3): el líder omitía summary y se rechazaban planes válidos. Es un campo
    // descriptivo: Java lo arma desde las tareas en vez de gastar intentos.
    @Test
    void aMissingSummaryIsFilledByJavaFromTheTasks() {
        when(teamMemory.snapshot("TEAM-MARKETING-GROWTH")).thenReturn(marketing("ACTIVE"));
        when(ceoService.planTeamWork(eq("growth-content"), anyString(), anyString(), eq("qwen3:8b"))).thenReturn(
                new TeamPlan("", "", "", List.of(
                        new PlannedTask("growth-content", "WORK", "SEO_PLAN", "Plan SEO", List.of("SEO"), List.of()))));

        var result = planner.plan("MISSION-5", "TEAM-MARKETING-GROWTH", "Lanzar el juego", TeamExecutionMode.ANALYSIS);

        assertTrue(result.plan().summary().contains("growth-content: SEO_PLAN"), result.plan().summary());
        verify(ceoService, times(1)).planTeamWork(anyString(), anyString(), anyString(), anyString());
        verify(events, never()).publish(eq("EMPRESA_TEAM_PLAN_REJECTED"), any(), any(), any(), anyMap());
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

    // Verificado en vivo: con capabilities=[a, b, c] (List.toString) Neo copiaba todo como un solo texto.
    @Test
    void theRosterListsEachCapabilityAsASeparateQuotedItem() {
        when(teamMemory.snapshot("TEAM-MARKETING-GROWTH")).thenReturn(marketing("ACTIVE"));
        var prompt = ArgumentCaptor.forClass(String.class);
        when(ceoService.planTeamWork(eq("growth-content"), prompt.capture(), anyString(), anyString())).thenReturn(validPlan());

        planner.plan("MISSION-5", "TEAM-MARKETING-GROWTH", "Lanzar el juego", TeamExecutionMode.ANALYSIS);

        assertTrue(prompt.getValue().contains("capabilities=[\"SEO\", \"growth\"]"), prompt.getValue());
        assertTrue(prompt.getValue().contains("Selecciona capabilities individuales del roster."));
        assertTrue(prompt.getValue().contains("No copies ni concatenes el listado completo de capabilities."));
    }

    // Verificado en vivo (MISSION-TEAM-VERIFY-5): un plan válido se perdía por "DESIGN-ARCHITECTURE".
    // El formato del action es cosmético: se normaliza en Java antes de validar, nunca se inventa.
    @Test
    void actionFormatIsNormalizedBeforeValidation() {
        when(teamMemory.snapshot("TEAM-MARKETING-GROWTH")).thenReturn(marketing("ACTIVE"));
        var hyphenated = new TeamPlan("Plan", "", "", List.of(
                new PlannedTask("growth-content", "WORK", "seo-plan inicial", "Plan SEO", List.of("SEO"), List.of())));
        when(ceoService.planTeamWork(anyString(), anyString(), anyString(), anyString())).thenReturn(hyphenated);

        var result = planner.plan("MISSION-5", "TEAM-MARKETING-GROWTH", "x", TeamExecutionMode.ANALYSIS);

        assertEquals("SEO_PLAN_INICIAL", result.plan().tasks().get(0).action());
        verify(ceoService, times(1)).planTeamWork(anyString(), anyString(), anyString(), anyString());
    }

    private static TeamSnapshot engineering() {
        return new TeamSnapshot("TEAM-DEVELOPMENT", "Development Group", "ACTIVE", "engineering", List.of(
                new TeamMemberInfo("engineering", "Neo", "Tech Lead", "TECH_LEAD", List.of("architecture"), "qwen3:8b"),
                new TeamMemberInfo("frontend-ui", "Mila", "UI", "UI_UX", List.of("flutter", "ui"), "qwen3:8b"),
                new TeamMemberInfo("qa", "Vera", "QA", "QA", List.of("qa", "tests"), "qwen3:8b"),
                new TeamMemberInfo("backend", "Iris", "Backend", "BACKEND", List.of("backend"), "qwen3:8b")));
    }

    private static TeamPlan dddPlan() {
        return new TeamPlan("Hola mundo", null, null, List.of(
                new PlannedTask("frontend-ui", "WORK", "HELLO_UI", "Pantalla de saludo", List.of("flutter"),
                        List.of(), List.of()),
                new PlannedTask("qa", "WORK", "ACCEPTANCE_TESTS", "Tests de aceptación", List.of("tests"), List.of())),
                List.of(), "FLUTTER_WEB_APP",
                List.of(new TeamPlan.BoundedContext("saludo", "Saludo al usuario")),
                List.of(new TeamPlan.GlossaryTerm("Saludo", "Mensaje de bienvenida"),
                        new TeamPlan.GlossaryTerm("Usuario", "Persona que abre la app"),
                        new TeamPlan.GlossaryTerm("Pantalla", "Vista del saludo")));
    }

    @Test
    void theDevelopmentPromptPresentsTheStackCatalogAndDddRules() {
        when(teamMemory.snapshot("TEAM-DEVELOPMENT")).thenReturn(engineering());
        var prompt = ArgumentCaptor.forClass(String.class);
        when(ceoService.planTeamWork(eq("engineering"), prompt.capture(), anyString(), anyString())).thenReturn(dddPlan());

        var result = planner.plan("M-1", "TEAM-DEVELOPMENT", "Crear un juego", TeamExecutionMode.DEVELOPMENT);

        assertTrue(prompt.getValue().contains("FLUTTER_WEB_APP"));
        assertTrue(prompt.getValue().contains("src/<Ctx>.Domain"));
        assertFalse(prompt.getValue().contains("GODOT_DOTNET_GAME"), "Godot no está habilitado en fase 1");
        assertTrue(prompt.getValue().contains("boundedContexts"));
        assertTrue(prompt.getValue().contains("ubiquitousLanguage"));
        assertEquals("FLUTTER_WEB_APP", result.plan().stackProfile());
    }

    @Test
    void normalizingActionsKeepsTheDddFields() {
        var normalized = TeamWorkPlanner.normalizeActions(dddPlan());
        assertEquals("FLUTTER_WEB_APP", normalized.stackProfile());
        assertEquals(List.of("saludo"), normalized.contextNames());
        assertEquals(3, normalized.ubiquitousLanguageOrEmpty().size());
    }

    // Spec 2026-10-01 §5: Neo elige solo a los agentes necesarios; nadie recibe trabajo de relleno.
    @Test
    void theDevelopmentPromptAsksForOnlyTheNecessaryAgents() {
        when(teamMemory.snapshot("TEAM-DEVELOPMENT")).thenReturn(engineering());
        var prompt = ArgumentCaptor.forClass(String.class);
        when(ceoService.planTeamWork(eq("engineering"), prompt.capture(), anyString(), anyString())).thenReturn(dddPlan());

        planner.plan("M-1", "TEAM-DEVELOPMENT", "Hola mundo", TeamExecutionMode.DEVELOPMENT);

        var text = prompt.getValue();
        assertTrue(text.contains("SOLO los miembros necesarios"), text);
        assertTrue(text.contains("no escriben código"), text);
        assertFalse(text.contains("participationConflicts"), text);
    }

    // Verificado en vivo (MISSION-DDD-VERIFY-2): sin el plan anterior, cada reintento regeneraba desde cero y
    // traía errores nuevos. La corrección incluye el plan rechazado para que se corrija de forma incremental.
    @Test
    void theRetryIncludesThePreviousPlanToCorrectIncrementally() {
        when(teamMemory.snapshot("TEAM-MARKETING-GROWTH")).thenReturn(marketing("ACTIVE"));
        var prompt = ArgumentCaptor.forClass(String.class);
        when(ceoService.planTeamWork(eq("growth-content"), prompt.capture(), anyString(), anyString()))
                .thenReturn(planWithOutsider())
                .thenReturn(validPlan());

        planner.plan("MISSION-5", "TEAM-MARKETING-GROWTH", "x", TeamExecutionMode.ANALYSIS);

        var second = prompt.getAllValues().get(1);
        assertTrue(second.contains("PLAN ANTERIOR"), second);
        assertTrue(second.contains("\"agentId\":\"finance\""), second);
        assertTrue(second.contains("corrige SOLO"), second);
    }

    @Test
    void developmentPlansGetFiveAttempts() {
        when(teamMemory.snapshot("TEAM-DEVELOPMENT")).thenReturn(engineering());
        when(ceoService.planTeamWork(anyString(), anyString(), anyString(), anyString())).thenReturn(planWithOutsider());

        assertThrows(IllegalStateException.class,
                () -> planner.plan("M-1", "TEAM-DEVELOPMENT", "x", TeamExecutionMode.DEVELOPMENT));
        verify(ceoService, times(5)).planTeamWork(anyString(), anyString(), anyString(), anyString());
    }

    // Revisión 4 (spec 2026-10-01): Java reparte capas por rol y desdobla QA antes de validar.
    @Test
    void developmentPlansAreResolvedBeforeValidation() {
        when(teamMemory.snapshot("TEAM-DEVELOPMENT")).thenReturn(engineering());
        when(ceoService.planTeamWork(anyString(), anyString(), anyString(), anyString())).thenReturn(dddPlan());

        var result = planner.plan("M-1", "TEAM-DEVELOPMENT", "Hola mundo", TeamExecutionMode.DEVELOPMENT);

        var mila = result.plan().tasksOrEmpty().stream().filter(t -> t.agentId().equals("frontend-ui")).findFirst().orElseThrow();
        assertEquals(List.of("lib/saludo/domain", "lib/saludo/application", "lib/saludo/infrastructure",
                "lib/saludo/presentation", "pubspec.yaml", "lib/main.dart", "web"), mila.ownedPaths());
        var vera = result.plan().tasksOrEmpty().stream().filter(t -> t.agentId().equals("qa")).toList();
        assertEquals(List.of("WORK", "VALIDATION"), vera.stream().map(TeamPlan.PlannedTask::kind).toList());
    }

    @Test
    void invalidDevelopmentPlansAreRetriedWithTheirCorrection() {
        when(teamMemory.snapshot("TEAM-DEVELOPMENT")).thenReturn(engineering());
        var withLeader = new TeamPlan("Hola mundo", null, null, List.of(
                new PlannedTask("engineering", "WORK", "ARCHITECTURE", "Arquitectura", List.of("architecture"), List.of()),
                new PlannedTask("frontend-ui", "WORK", "HELLO_UI", "Pantalla", List.of("flutter"), List.of()),
                new PlannedTask("qa", "WORK", "ACCEPTANCE_TESTS", "Tests", List.of("tests"), List.of())),
                List.of(), "FLUTTER_WEB_APP", dddPlan().boundedContexts(), dddPlan().ubiquitousLanguage());
        var prompt = ArgumentCaptor.forClass(String.class);
        when(ceoService.planTeamWork(anyString(), prompt.capture(), anyString(), anyString()))
                .thenReturn(withLeader)
                .thenReturn(dddPlan());

        planner.plan("M-1", "TEAM-DEVELOPMENT", "Hola mundo", TeamExecutionMode.DEVELOPMENT);

        var second = prompt.getAllValues().get(1);
        assertTrue(second.contains("CORRECCIÓN"), second);
        assertTrue(second.contains("engineering (TECH_LEAD) no escribe código"), second);
    }

    // Revisión final (I-1): un error del resolutor no puede ocultar la causa real (perfil o rol no habilitado).
    @Test
    void validatorErrorsAreReportedEvenWhenTheResolverAlsoFails() {
        var team = new TeamSnapshot("TEAM-DEVELOPMENT", "Development Group", "ACTIVE", "engineering", List.of(
                new TeamMemberInfo("engineering", "Neo", "Tech Lead", "TECH_LEAD", List.of("architecture"), "qwen3:8b"),
                new TeamMemberInfo("backend", "Iris", "Backend", "BACKEND", List.of("backend"), "qwen3:8b"),
                new TeamMemberInfo("interaction-design", "Kael", "Game", "GAME_DEV", List.of("godot"), "qwen3:8b"),
                new TeamMemberInfo("frontend-ui", "Mila", "UI", "UI_UX", List.of("flutter", "ui"), "qwen3:8b"),
                new TeamMemberInfo("qa", "Vera", "QA", "QA", List.of("qa", "tests"), "qwen3:8b")));
        when(teamMemory.snapshot("TEAM-DEVELOPMENT")).thenReturn(team);
        var godot = new TeamPlan("Juego", null, null, List.of(
                new PlannedTask("backend", "WORK", "DOMAIN_MODEL", "Dominio", List.of("backend"), List.of()),
                new PlannedTask("interaction-design", "WORK", "GAMEPLAY", "Juego", List.of("godot"), List.of()),
                new PlannedTask("qa", "WORK", "ACCEPTANCE_TESTS", "Tests", List.of("tests"), List.of())),
                List.of(), "GODOT_DOTNET_GAME", List.of(new TeamPlan.BoundedContext("Combate", "Combate")),
                dddPlan().ubiquitousLanguage());
        var prompt = ArgumentCaptor.forClass(String.class);
        when(ceoService.planTeamWork(anyString(), prompt.capture(), anyString(), anyString()))
                .thenReturn(godot)
                .thenReturn(dddPlan());

        planner.plan("M-1", "TEAM-DEVELOPMENT", "Un juego", TeamExecutionMode.DEVELOPMENT);

        var second = prompt.getAllValues().get(1);
        assertTrue(second.contains("GODOT_DOTNET_GAME todavía no está habilitado"), second);
        assertTrue(second.contains("interaction-design (GAME_DEV) todavía no está habilitado"), second);
    }
}
