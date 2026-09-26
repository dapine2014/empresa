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
                new PlannedTask("engineering", "WORK", "ARCHITECTURE", "Solución y capa application",
                        List.of("arquitectura backend"), List.of("Juego.sln", "src/Combate.Application")),
                new PlannedTask("frontend-ui", "WORK", "GAME_UI", "Escenas y HUD en Godot",
                        List.of("Game UI"), List.of("game")),
                new PlannedTask("backend", "WORK", "DOMAIN_MODEL", "Modelo de dominio del combate",
                        List.of("lógica de negocio"), List.of("src/Combate.Domain")),
                new PlannedTask("devops", "WORK", "TESTS_INFRA", "Proyecto de tests",
                        List.of("infraestructura"), List.of("tests/Combate.Tests")),
                new PlannedTask("qa", "VALIDATION", "STATIC_REVIEW", "Revisar el código",
                        List.of("QA"), List.of())
        ));
    }

    private static final List<TeamPlan.BoundedContext> CONTEXTS =
            List.of(new TeamPlan.BoundedContext("Combate", "Reglas del combate por turnos"));

    private static final List<TeamPlan.GlossaryTerm> GLOSSARY = List.of(
            new TeamPlan.GlossaryTerm("Unidad", "Personaje que participa en un combate"),
            new TeamPlan.GlossaryTerm("Turno", "Momento en que una unidad actúa"),
            new TeamPlan.GlossaryTerm("Daño", "Puntos que resta un ataque a la vida de una unidad"));

    private static TeamPlan plan(List<PlannedTask> tasks) {
        return plan("GODOT_DOTNET_GAME", CONTEXTS, GLOSSARY, tasks);
    }

    private static TeamPlan plan(String profile, List<TeamPlan.BoundedContext> contexts,
                                 List<TeamPlan.GlossaryTerm> glossary, List<PlannedTask> tasks) {
        return new TeamPlan("Juego de combate por turnos", null, null, tasks, List.of(), profile, contexts, glossary);
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
        tasks.add(new PlannedTask("finance", "WORK", "COSTS", "Costos", List.of("finanzas"), List.of("docs")));
        var errors = validateDev(plan(tasks));
        assertTrue(errors.stream().anyMatch(e -> e.contains("finance") && e.contains("no es miembro")), errors.toString());
    }

    @Test
    void rejectsAnInventedCapability() {
        var tasks = validTasks();
        tasks.set(1, new PlannedTask("frontend-ui", "WORK", "GAME_UI", "HUD", List.of("Unity experto"), List.of("game")));
        var errors = validateDev(plan(tasks));
        assertTrue(errors.stream().anyMatch(e -> e.contains("Unity experto")), errors.toString());
    }

    @Test
    void rejectsOverlappingOwnedPaths() {
        var tasks = validTasks();
        tasks.set(2, new PlannedTask("backend", "WORK", "DOMAIN_MODEL", "Dominio", List.of("lógica de negocio"),
                List.of("src/Combate.Application")));
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
        tasks.removeIf(t -> t.kind().equals("VALIDATION") || t.agentId().equals("devops"));
        tasks.add(new PlannedTask("qa", "WORK", "TESTS", "Tests", List.of("QA"), List.of("tests/Combate.Tests")));
        tasks.add(new PlannedTask("devops", "WORK", "DOCS", "Docs", List.of("infraestructura"), List.of("README.md")));
        var errors = validateDev(plan(tasks));
        assertTrue(errors.stream().anyMatch(e -> e.contains("exactamente una tarea VALIDATION")), errors.toString());
    }

    @Test
    void validationMustGoToAMemberWithQaCapability() {
        var tasks = validTasks();
        tasks.removeIf(t -> t.agentId().equals("qa") || t.agentId().equals("backend"));
        tasks.add(new PlannedTask("backend", "VALIDATION", "STATIC_REVIEW", "Revisar", List.of("backend"), List.of()));
        tasks.add(new PlannedTask("qa", "WORK", "DOMAIN", "Dominio", List.of("QA"), List.of("src/Combate.Domain")));
        var errors = validateDev(plan(tasks));
        assertTrue(errors.stream().anyMatch(e -> e.contains("capability QA")), errors.toString());
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
        tasks.add(new PlannedTask("backend", "WORK", "MORE", "Más", List.of("backend"), List.of("docs")));
        var errors = validateDev(plan(tasks));
        assertTrue(errors.stream().anyMatch(e -> e.contains("más de una tarea")), errors.toString());
    }

    @Test
    void rejectsActionsThatAreNotUpperSnakeCase() {
        var tasks = validTasks();
        tasks.set(0, new PlannedTask("engineering", "WORK", "architecture", "Base",
                List.of("arquitectura backend"), List.of("Juego.sln")));
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

    // Verificado en vivo (MISSION-1790325370585): Neo mandó el listado completo como una sola capability.
    @Test
    void aConcatenatedCapabilityListIsRejectedWithASpecificCorrection() {
        var tasks = validTasks();
        tasks.set(1, new PlannedTask("frontend-ui", "WORK", "GAME_UI", "HUD",
                List.of("frontend, Game UI"), List.of("game")));
        var errors = validateDev(plan(tasks));
        assertTrue(errors.stream().anyMatch(e -> e.contains("concaten") && e.contains("\"frontend\"")), errors.toString());
    }

    @Test
    void individualRealCapabilitiesPass() {
        var tasks = validTasks();
        tasks.set(1, new PlannedTask("frontend-ui", "WORK", "GAME_UI", "HUD",
                List.of("frontend", "Game UI"), List.of("game")));
        assertEquals(List.of(), validateDev(plan(tasks)));
    }

    @Test
    void theSamePathTwiceInOneTaskIsRejected() {
        var tasks = validTasks();
        tasks.set(1, new PlannedTask("frontend-ui", "WORK", "GAME_UI", "HUD",
                List.of("Game UI"), List.of("game/hud.cs", "game/hud.cs")));
        var errors = validateDev(plan(tasks));
        assertTrue(errors.stream().anyMatch(e -> e.contains("game/hud.cs") && e.contains("frontend-ui")), errors.toString());
    }

    @Test
    void theSamePathInTwoTasksIsRejected() {
        var tasks = validTasks();
        tasks.set(0, new PlannedTask("engineering", "WORK", "ARCHITECTURE", "Base",
                List.of("arquitectura backend"), List.of("Juego.sln", "README.md")));
        tasks.set(3, new PlannedTask("devops", "WORK", "DEV_INFRA", "Infra",
                List.of("infraestructura"), List.of("tests/Combate.Tests", "README.md")));
        var errors = validateDev(plan(tasks));
        assertTrue(errors.stream().anyMatch(e -> e.contains("README.md") && e.contains("se solapan")), errors.toString());
    }

    @Test
    void globsInOwnedPathsAreRejected() {
        var tasks = validTasks();
        tasks.set(3, new PlannedTask("devops", "WORK", "DEV_INFRA", "Infra",
                List.of("infraestructura"), List.of("tests/*.cs")));
        var errors = validateDev(plan(tasks));
        assertTrue(errors.stream().anyMatch(e -> e.contains("tests/*.cs")), errors.toString());
    }

    @Test
    void theLeaderMustHaveATaskEvenWhenPartialTeamsAreAllowed() {
        var marketing = new TeamSnapshot("TEAM-MARKETING-GROWTH", "Marketing & Growth", "ACTIVE", "growth-content", List.of(
                member("growth-content", "Kira", List.of("growth", "SEO")),
                member("community", "Nora", List.of("Discord", "moderación"))
        ));
        var onlyNora = new TeamPlan("Plan", "", "", List.of(
                new PlannedTask("community", "WORK", "DISCORD", "Discord", List.of("Discord"), List.of())));
        var errors = validator.validate(onlyNora, marketing, TeamExecutionMode.ANALYSIS);
        assertTrue(errors.stream().anyMatch(e -> e.contains("líder") && e.contains("growth-content")), errors.toString());
    }

    @Test
    void rejectsAProfileOutsideTheCatalog() {
        var errors = validateDev(plan("UNITY_GAME", CONTEXTS, GLOSSARY, validTasks()));
        assertTrue(errors.stream().anyMatch(e -> e.contains("stackProfile") && e.contains("GODOT_DOTNET_GAME")), errors.toString());
    }

    @Test
    void requiresAtLeastOneBoundedContext() {
        var errors = validateDev(plan("GODOT_DOTNET_GAME", List.of(), GLOSSARY, validTasks()));
        assertTrue(errors.stream().anyMatch(e -> e.contains("boundedContexts")), errors.toString());
    }

    // Review Focus: en .NET el nombre define las rutas src/<Ctx>.Domain; "combate" no coincide.
    @Test
    void rejectsAContextNameThatDoesNotFollowTheProfileRule() {
        var errors = validateDev(plan("GODOT_DOTNET_GAME",
                List.of(new TeamPlan.BoundedContext("combate", "x")), GLOSSARY, validTasks()));
        assertTrue(errors.stream().anyMatch(e -> e.contains("combate") && e.contains("PascalCase")), errors.toString());
    }

    @Test
    void requiresAGlossaryOfAtLeastThreeTerms() {
        var errors = validateDev(plan("GODOT_DOTNET_GAME", CONTEXTS, GLOSSARY.subList(0, 2), validTasks()));
        assertTrue(errors.stream().anyMatch(e -> e.contains("ubiquitousLanguage")), errors.toString());
    }

    // Review Focus: "src" es padre de toda la estructura; tiene que estar DENTRO de ella.
    @Test
    void rejectsOwnedPathsOutsideTheProfileStructure() {
        var tasks = validTasks();
        tasks.set(3, new PlannedTask("devops", "WORK", "TESTS_INFRA", "Infra", List.of("infraestructura"), List.of("src")));
        var errors = validateDev(plan(tasks));
        assertTrue(errors.stream().anyMatch(e -> e.contains("\"src\"") && e.contains("estructura")), errors.toString());
    }

    @Test
    void analysisTeamsStillNeedNoProfile() {
        var marketing = new TeamSnapshot("TEAM-MARKETING-GROWTH", "Marketing & Growth", "ACTIVE", "growth-content", List.of(
                member("growth-content", "Kira", List.of("SEO"))));
        var onlyKira = new TeamPlan("Plan", "", "", List.of(
                new PlannedTask("growth-content", "WORK", "SEO_PLAN", "Plan SEO", List.of("SEO"), List.of())));
        assertEquals(List.of(), validator.validate(onlyKira, marketing, TeamExecutionMode.ANALYSIS));
    }
}
