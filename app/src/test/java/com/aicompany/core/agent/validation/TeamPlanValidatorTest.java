package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.TeamPlan;
import com.aicompany.core.agent.model.TeamPlan.PlannedTask;
import com.aicompany.core.model.TeamExecutionMode;
import com.aicompany.core.model.TeamMemberInfo;
import com.aicompany.core.model.TeamSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TeamPlanValidatorTest {

    private final TeamPlanValidator validator = new TeamPlanValidator();

    private static TeamMemberInfo member(String id, String name, List<String> capabilities) {
        return new TeamMemberInfo(id, name, "rol", "ROLE", capabilities, "qwen3:8b");
    }

    private static TeamSnapshot engineeringTeam() {
        return new TeamSnapshot("TEAM-ENGINEERING", "Engineering Team", "ACTIVE", "engineering", List.of(
                member("engineering", "Neo", List.of("arquitectura backend", "descomposición técnica del trabajo")),
                member("qa", "Vera", List.of("QA", "análisis de errores")),
                member("devops", "Diego", List.of("infraestructura", "SRE")),
                member("backend", "Iris", List.of("backend", "lógica de negocio")),
                member("frontend-ui", "Mila", List.of("frontend", "Game UI"))
        ));
    }

    private static List<PlannedTask> validTasks() {
        return new ArrayList<>(List.of(
                new PlannedTask("engineering", "WORK", "ARCHITECTURE", "Estructura base",
                        List.of("arquitectura backend"), List.of("web/index.html", "docs")),
                new PlannedTask("frontend-ui", "WORK", "GAME_UI", "HUD y menús",
                        List.of("Game UI"), List.of("web/ui")),
                new PlannedTask("backend", "WORK", "GAME_LOGIC", "Lógica del juego",
                        List.of("lógica de negocio"), List.of("web/game")),
                new PlannedTask("devops", "WORK", "DEV_INFRA", "Scripts de servidor local",
                        List.of("infraestructura"), List.of("infra")),
                new PlannedTask("qa", "VALIDATION", "STATIC_REVIEW", "Revisar el código",
                        List.of("QA"), List.of())
        ));
    }

    private static TeamPlan plan(List<PlannedTask> tasks) {
        return new TeamPlan("Juego de navegador", "HTML5 + JavaScript", "web/index.html", tasks);
    }

    private List<String> validateDev(TeamPlan plan) {
        return validator.validate(plan, engineeringTeam(), TeamExecutionMode.DEVELOPMENT);
    }

    @Test
    void acceptsAValidDevelopmentPlan() {
        assertEquals(List.of(), validateDev(plan(validTasks())));
    }

    @Test
    void rejectsAnAgentOutsideTheTeam() {
        var tasks = validTasks();
        tasks.add(new PlannedTask("finance", "WORK", "COSTS", "Costos", List.of("finanzas"), List.of("costs")));
        var errors = validateDev(plan(tasks));
        assertTrue(errors.stream().anyMatch(e -> e.contains("finance") && e.contains("no es miembro")), errors.toString());
    }

    @Test
    void rejectsAnInventedCapability() {
        var tasks = validTasks();
        tasks.set(1, new PlannedTask("frontend-ui", "WORK", "GAME_UI", "HUD", List.of("Unity experto"), List.of("web/ui")));
        var errors = validateDev(plan(tasks));
        assertTrue(errors.stream().anyMatch(e -> e.contains("Unity experto")), errors.toString());
    }

    @Test
    void rejectsOverlappingOwnedPaths() {
        var tasks = validTasks();
        tasks.set(2, new PlannedTask("backend", "WORK", "GAME_LOGIC", "Lógica", List.of("lógica de negocio"), List.of("web")));
        var errors = validateDev(plan(tasks));
        assertTrue(errors.stream().anyMatch(e -> e.contains("se solapan")), errors.toString());
    }

    @Test
    void requiresEveryMemberInDevelopmentMode() {
        var tasks = validTasks();
        tasks.removeIf(t -> t.agentId().equals("devops"));
        var errors = validateDev(plan(tasks));
        assertTrue(errors.stream().anyMatch(e -> e.contains("devops")), errors.toString());
    }

    @Test
    void requiresExactlyOneValidationTask() {
        var tasks = validTasks();
        tasks.removeIf(t -> t.kind().equals("VALIDATION"));
        tasks.add(new PlannedTask("qa", "WORK", "TESTS", "Tests", List.of("QA"), List.of("tests")));
        var errors = validateDev(plan(tasks));
        assertTrue(errors.stream().anyMatch(e -> e.contains("exactamente una tarea VALIDATION")), errors.toString());
    }

    @Test
    void validationMustGoToAMemberWithQaCapability() {
        var tasks = validTasks();
        tasks.removeIf(t -> t.agentId().equals("qa") || t.agentId().equals("backend"));
        tasks.add(new PlannedTask("backend", "VALIDATION", "STATIC_REVIEW", "Revisar", List.of("backend"), List.of()));
        tasks.add(new PlannedTask("qa", "WORK", "TESTS", "Tests", List.of("QA"), List.of("tests")));
        var errors = validateDev(plan(tasks));
        assertTrue(errors.stream().anyMatch(e -> e.contains("capability QA")), errors.toString());
    }

    @Test
    void entryPointMustBeInsideSomeWorkOwnedPaths() {
        var errors = validateDev(new TeamPlan("x", "HTML5", "main.js", validTasks()));
        assertTrue(errors.stream().anyMatch(e -> e.contains("entryPoint")), errors.toString());
    }

    @Test
    void rejectsUnsafeOwnedPaths() {
        var tasks = validTasks();
        tasks.set(3, new PlannedTask("devops", "WORK", "DEV_INFRA", "Infra", List.of("infraestructura"), List.of("../infra")));
        var errors = validateDev(plan(tasks));
        assertTrue(errors.stream().anyMatch(e -> e.contains("../infra")), errors.toString());
    }

    @Test
    void rejectsTwoTasksForTheSameAgent() {
        var tasks = validTasks();
        tasks.add(new PlannedTask("backend", "WORK", "MORE", "Más", List.of("backend"), List.of("web/extra")));
        var errors = validateDev(plan(tasks));
        assertTrue(errors.stream().anyMatch(e -> e.contains("más de una tarea")), errors.toString());
    }

    @Test
    void rejectsActionsThatAreNotUpperSnakeCase() {
        var tasks = validTasks();
        tasks.set(0, new PlannedTask("engineering", "WORK", "architecture", "Base",
                List.of("arquitectura backend"), List.of("web/index.html")));
        var errors = validateDev(plan(tasks));
        assertTrue(errors.stream().anyMatch(e -> e.contains("action")), errors.toString());
    }

    @Test
    void analysisModeRejectsValidationTasksButAllowsPartialTeams() {
        var marketing = new TeamSnapshot("TEAM-MARKETING-GROWTH", "Marketing & Growth", "ACTIVE", "growth-content", List.of(
                member("growth-content", "Kira", List.of("growth", "SEO")),
                member("community", "Nora", List.of("Discord", "moderación"))
        ));
        var onlyKira = new TeamPlan("Plan", "", "", List.of(
                new PlannedTask("growth-content", "WORK", "SEO_PLAN", "Plan SEO", List.of("SEO"), List.of())));
        assertEquals(List.of(), validator.validate(onlyKira, marketing, TeamExecutionMode.ANALYSIS));

        var withValidation = new TeamPlan("Plan", "", "", List.of(
                new PlannedTask("growth-content", "WORK", "SEO_PLAN", "Plan SEO", List.of("SEO"), List.of()),
                new PlannedTask("community", "VALIDATION", "REVIEW", "Revisar", List.of("moderación"), List.of())));
        var errors = validator.validate(withValidation, marketing, TeamExecutionMode.ANALYSIS);
        assertTrue(errors.stream().anyMatch(e -> e.contains("VALIDATION")), errors.toString());
    }

    // Verificado en vivo: Neo falló 3 veces seguidas con el entryPoint fuera de sus ownedPaths
    // porque el error no le decía cuáles eran; la corrección tiene que ser accionable.
    @Test
    void theEntryPointErrorListsTheWorkOwnedPathsAndHowToFixIt() {
        var errors = validateDev(new TeamPlan("x", "HTML5", "src/index.html", validTasks()));
        var error = errors.stream().filter(e -> e.contains("entryPoint")).findFirst().orElseThrow();
        assertTrue(error.contains("web/ui"), error);
        assertTrue(error.contains("engineering=[web/index.html, docs]"), error);
    }
}
