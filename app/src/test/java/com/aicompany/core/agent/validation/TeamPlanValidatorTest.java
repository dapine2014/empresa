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

    private static TeamMemberInfo member(String id, String roleCode, List<String> capabilities) {
        return new TeamMemberInfo(id, id, "rol", roleCode, capabilities, "m");
    }

    private static TeamSnapshot engineeringTeam() {
        return new TeamSnapshot("TEAM-DEVELOPMENT", "Development Group", "ACTIVE", "engineering", List.of(
                member("product-owner", "PRODUCT_OWNER", List.of("requirements")),
                member("engineering", "TECH_LEAD", List.of("architecture", "planning")),
                member("backend", "BACKEND", List.of("backend", "api")),
                member("devops", "DATA_ARCHITECT", List.of("persistence")),
                member("frontend-ui", "UI_UX", List.of("flutter", "ui")),
                member("interaction-design", "GAME_DEV", List.of("godot")),
                member("qa", "QA", List.of("qa", "tests"))
        ));
    }

    private static List<PlannedTask> validTasks() {
        return new ArrayList<>(List.of(
                new PlannedTask("frontend-ui", "WORK", "UI", "Pantalla de saludo",
                        List.of("flutter"), List.of("lib/saludo/presentation", "pubspec.yaml", "lib/main.dart", "web")),
                new PlannedTask("backend", "WORK", "DOMAIN_MODEL", "Modelo de saludo",
                        List.of("backend"), List.of("lib/saludo/domain", "lib/saludo/application")),
                new PlannedTask("qa", "WORK", "ACCEPTANCE_TESTS", "Tests de aceptación",
                        List.of("tests"), List.of("test/saludo")),
                new PlannedTask("qa", "VALIDATION", "CODE_REVIEW", "Revisar el código",
                        List.of("qa"), List.of())
        ));
    }

    private static final List<TeamPlan.BoundedContext> CONTEXTS =
            List.of(new TeamPlan.BoundedContext("saludo", "Saludo al usuario"));

    private static final List<TeamPlan.GlossaryTerm> GLOSSARY = List.of(
            new TeamPlan.GlossaryTerm("Saludo", "Mensaje de bienvenida"),
            new TeamPlan.GlossaryTerm("Usuario", "Persona que abre la app"),
            new TeamPlan.GlossaryTerm("Pantalla", "Vista que muestra el saludo"));

    private static TeamPlan plan(List<PlannedTask> tasks) {
        return plan("FLUTTER_WEB_APP", CONTEXTS, GLOSSARY, tasks);
    }

    private static TeamPlan plan(String profile, List<TeamPlan.BoundedContext> contexts,
                                 List<TeamPlan.GlossaryTerm> glossary, List<PlannedTask> tasks) {
        return new TeamPlan("Hola mundo", null, null, tasks, List.of(), profile, contexts, glossary);
    }

    private List<String> validateDev(TeamPlan plan) {
        return validator.validate(plan, engineeringTeam(), TeamExecutionMode.DEVELOPMENT);
    }

    private static TeamSnapshot marketing() {
        return new TeamSnapshot("TEAM-MARKETING-GROWTH", "Marketing & Growth", "ACTIVE", "growth-content", List.of(
                member("growth-content", "GROWTH_CONTENT_COMMUNITY", List.of("growth", "SEO")),
                member("community", "COMMUNITY_MANAGER", List.of("Discord", "moderación"))
        ));
    }

    @Test
    void acceptsAValidDevelopmentPlan() {
        assertEquals(List.of(), validateDev(plan(validTasks())));
    }

    @Test
    void partialTeamsAreAllowedInDevelopment() {
        var tasks = validTasks();
        tasks.remove(1);
        tasks.set(0, new PlannedTask("frontend-ui", "WORK", "UI", "Toda la app", List.of("flutter"),
                List.of("lib/saludo/domain", "lib/saludo/presentation", "pubspec.yaml", "lib/main.dart", "web")));
        assertEquals(List.of(), validateDev(plan(tasks)));
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
        tasks.set(0, new PlannedTask("frontend-ui", "WORK", "UI", "Pantalla", List.of("unity"),
                List.of("lib/saludo/presentation", "pubspec.yaml", "lib/main.dart", "web")));
        var errors = validateDev(plan(tasks));
        assertTrue(errors.stream().anyMatch(e -> e.contains("unity")), errors.toString());
    }

    @Test
    void rejectsOverlappingOwnedPaths() {
        var tasks = validTasks();
        tasks.set(1, new PlannedTask("backend", "WORK", "DOMAIN_MODEL", "Dominio", List.of("backend"),
                List.of("lib/saludo/presentation")));
        var errors = validateDev(plan(tasks));
        assertTrue(errors.stream().anyMatch(e -> e.contains("se solapan")), errors.toString());
    }

    @Test
    void requiresExactlyOneValidationTask() {
        var tasks = validTasks();
        tasks.remove(3);
        var errors = validateDev(plan(tasks));
        assertTrue(errors.stream().anyMatch(e -> e.contains("exactamente una tarea VALIDATION")), errors.toString());
    }

    @Test
    void validationMustGoToTheQaMember() {
        var tasks = validTasks();
        tasks.set(3, new PlannedTask("backend", "VALIDATION", "CODE_REVIEW", "Revisar", List.of("backend"), List.of()));
        var errors = validateDev(plan(tasks));
        assertTrue(errors.stream().anyMatch(e -> e.contains("miembro QA")), errors.toString());
    }

    @Test
    void rejectsTasksForRolesThatDoNotWriteCode() {
        var tasks = validTasks();
        tasks.add(new PlannedTask("engineering", "WORK", "ARCHITECTURE", "Arquitectura",
                List.of("architecture"), List.of("lib/saludo/infrastructure")));
        var errors = validateDev(plan(tasks));
        assertTrue(errors.stream().anyMatch(e -> e.contains("engineering") && e.contains("no escribe código")), errors.toString());
    }

    @Test
    void rejectsARoleOfALaterPhase() {
        var tasks = validTasks();
        tasks.add(new PlannedTask("interaction-design", "WORK", "GAMEPLAY", "Juego",
                List.of("godot"), List.of("lib/saludo/infrastructure")));
        var errors = validateDev(plan(tasks));
        assertTrue(errors.stream().anyMatch(e -> e.contains("interaction-design") && e.contains("habilitado")), errors.toString());
    }

    @Test
    void rejectsAProfileOfALaterPhase() {
        var errors = validateDev(plan("GODOT_DOTNET_GAME",
                List.of(new TeamPlan.BoundedContext("Combate", "Combate")), GLOSSARY, validTasks()));
        assertTrue(errors.stream().anyMatch(e -> e.contains("GODOT_DOTNET_GAME") && e.contains("habilitado")), errors.toString());
    }

    @Test
    void qaNeedsATestsTask() {
        var tasks = validTasks();
        tasks.remove(2);
        assertTrue(validateDev(plan(tasks)).stream().anyMatch(e -> e.contains("tests de qa")));
    }

    @Test
    void qaCannotHaveTwoReviews() {
        var tasks = validTasks();
        tasks.add(new PlannedTask("qa", "VALIDATION", "CODE_REVIEW", "Otra", List.of("qa"), List.of()));
        assertTrue(validateDev(plan(tasks)).stream().anyMatch(e -> e.contains("VALIDATION")));
    }

    @Test
    void rejectsUnsafeOwnedPaths() {
        var tasks = validTasks();
        tasks.set(1, new PlannedTask("backend", "WORK", "DOMAIN_MODEL", "Dominio", List.of("backend"), List.of("../infra")));
        var errors = validateDev(plan(tasks));
        assertTrue(errors.stream().anyMatch(e -> e.contains("../infra")), errors.toString());
    }

    @Test
    void otherAgentsStillGetAtMostOneTask() {
        var tasks = validTasks();
        tasks.add(new PlannedTask("backend", "WORK", "MORE", "Más", List.of("api"), List.of("lib/saludo/infrastructure")));
        var errors = validateDev(plan(tasks));
        assertTrue(errors.stream().anyMatch(e -> e.contains("backend tiene más de una tarea")), errors.toString());
    }

    @Test
    void rejectsActionsThatAreNotUpperSnakeCase() {
        var tasks = validTasks();
        tasks.set(1, new PlannedTask("backend", "WORK", "domain model", "Dominio", List.of("backend"),
                List.of("lib/saludo/domain", "lib/saludo/application")));
        var errors = validateDev(plan(tasks));
        assertTrue(errors.stream().anyMatch(e -> e.contains("action")), errors.toString());
    }

    @Test
    void analysisModeRejectsValidationTasksButAllowsPartialTeams() {
        var onlyKira = new TeamPlan("Plan", "", "", List.of(
                new PlannedTask("growth-content", "WORK", "SEO_PLAN", "Plan SEO", List.of("SEO"), List.of())));
        assertEquals(List.of(), validator.validate(onlyKira, marketing(), TeamExecutionMode.ANALYSIS));

        var withValidation = new TeamPlan("Plan", "", "", List.of(
                new PlannedTask("growth-content", "WORK", "SEO_PLAN", "Plan SEO", List.of("SEO"), List.of()),
                new PlannedTask("community", "VALIDATION", "REVIEW", "Revisar", List.of("moderación"), List.of())));
        var errors = validator.validate(withValidation, marketing(), TeamExecutionMode.ANALYSIS);
        assertTrue(errors.stream().anyMatch(e -> e.contains("VALIDATION")), errors.toString());
    }

    // Verificado en vivo (MISSION-1790325370585): Neo mandó el listado completo como una sola capability.
    @Test
    void nonAtomicCapabilitiesAreRejectedWithTheRealOnesAsExample() {
        var tasks = validTasks();
        tasks.set(0, new PlannedTask("frontend-ui", "WORK", "UI", "Pantalla",
                List.of("flutter, ui y diseño responsive"),
                List.of("lib/saludo/presentation", "pubspec.yaml", "lib/main.dart", "web")));
        var errors = validateDev(plan(tasks));
        assertTrue(errors.stream().anyMatch(e -> e.contains("atómica") && e.contains("\"flutter\"")), errors.toString());
    }

    @Test
    void individualRealCapabilitiesPass() {
        var tasks = validTasks();
        tasks.set(0, new PlannedTask("frontend-ui", "WORK", "UI", "Pantalla", List.of("flutter", "ui"),
                List.of("lib/saludo/presentation", "pubspec.yaml", "lib/main.dart", "web")));
        assertEquals(List.of(), validateDev(plan(tasks)));
    }

    @Test
    void theSamePathTwiceInOneTaskIsRejected() {
        var tasks = validTasks();
        tasks.set(1, new PlannedTask("backend", "WORK", "DOMAIN_MODEL", "Dominio", List.of("backend"),
                List.of("lib/saludo/domain", "lib/saludo/domain")));
        var errors = validateDev(plan(tasks));
        assertTrue(errors.stream().anyMatch(e -> e.contains("lib/saludo/domain") && e.contains("backend")), errors.toString());
    }

    @Test
    void theSamePathInTwoTasksIsRejected() {
        var tasks = validTasks();
        tasks.set(1, new PlannedTask("backend", "WORK", "DOMAIN_MODEL", "Dominio", List.of("backend"),
                List.of("lib/saludo/domain", "README.md")));
        tasks.set(2, new PlannedTask("qa", "WORK", "ACCEPTANCE_TESTS", "Tests", List.of("tests"),
                List.of("test/saludo", "README.md")));
        var errors = validateDev(plan(tasks));
        assertTrue(errors.stream().anyMatch(e -> e.contains("README.md") && e.contains("se solapan")), errors.toString());
    }

    @Test
    void globsInOwnedPathsAreRejected() {
        var tasks = validTasks();
        tasks.set(2, new PlannedTask("qa", "WORK", "ACCEPTANCE_TESTS", "Tests", List.of("tests"), List.of("test/*.dart")));
        var errors = validateDev(plan(tasks));
        assertTrue(errors.stream().anyMatch(e -> e.contains("test/*.dart")), errors.toString());
    }

    @Test
    void theLeaderMustHaveATaskInAnalysisTeams() {
        var onlyNora = new TeamPlan("Plan", "", "", List.of(
                new PlannedTask("community", "WORK", "DISCORD", "Discord", List.of("Discord"), List.of())));
        var errors = validator.validate(onlyNora, marketing(), TeamExecutionMode.ANALYSIS);
        assertTrue(errors.stream().anyMatch(e -> e.contains("líder") && e.contains("growth-content")), errors.toString());
    }

    @Test
    void theDevelopmentLeaderDoesNotNeedATask() {
        assertTrue(validateDev(plan(validTasks())).stream().noneMatch(e -> e.contains("líder")));
    }

    @Test
    void rejectsAProfileOutsideTheCatalog() {
        var errors = validateDev(plan("UNITY_GAME", CONTEXTS, GLOSSARY, validTasks()));
        assertTrue(errors.stream().anyMatch(e -> e.contains("stackProfile") && e.contains("FLUTTER_WEB_APP")), errors.toString());
    }

    @Test
    void requiresAtLeastOneBoundedContext() {
        var errors = validateDev(plan("FLUTTER_WEB_APP", List.of(), GLOSSARY, validTasks()));
        assertTrue(errors.stream().anyMatch(e -> e.contains("boundedContexts")), errors.toString());
    }

    // Review Focus: en Flutter el nombre define las rutas lib/<ctx>/...; "Saludo" no es snake_case.
    @Test
    void rejectsAContextNameThatDoesNotFollowTheProfileRule() {
        var errors = validateDev(plan("FLUTTER_WEB_APP",
                List.of(new TeamPlan.BoundedContext("Saludo", "x")), GLOSSARY, validTasks()));
        assertTrue(errors.stream().anyMatch(e -> e.contains("Saludo") && e.contains("snake_case")), errors.toString());
    }

    @Test
    void requiresAGlossaryOfAtLeastThreeTerms() {
        var errors = validateDev(plan("FLUTTER_WEB_APP", CONTEXTS, GLOSSARY.subList(0, 2), validTasks()));
        assertTrue(errors.stream().anyMatch(e -> e.contains("ubiquitousLanguage")), errors.toString());
    }

    // Review Focus: "lib" es padre de toda la estructura; tiene que estar DENTRO de ella.
    @Test
    void rejectsOwnedPathsOutsideTheProfileStructure() {
        var tasks = validTasks();
        tasks.set(1, new PlannedTask("backend", "WORK", "DOMAIN_MODEL", "Dominio", List.of("backend"), List.of("lib")));
        var errors = validateDev(plan(tasks));
        assertTrue(errors.stream().anyMatch(e -> e.contains("\"lib\"") && e.contains("estructura")), errors.toString());
    }

    @Test
    void analysisTeamsStillNeedNoProfile() {
        var onlyKira = new TeamPlan("Plan", "", "", List.of(
                new PlannedTask("growth-content", "WORK", "SEO_PLAN", "Plan SEO", List.of("SEO"), List.of())));
        assertEquals(List.of(), validator.validate(onlyKira, marketing(), TeamExecutionMode.ANALYSIS));
    }
}
