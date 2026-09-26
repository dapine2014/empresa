package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.TeamPlan;
import com.aicompany.core.agent.model.TeamPlan.LayerAssignment;
import com.aicompany.core.agent.model.TeamPlan.PlannedTask;
import com.aicompany.core.model.TeamMemberInfo;
import com.aicompany.core.model.TeamSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verificado en vivo (MISSION-DDD-VERIFY-1 a -3): qwen3:8b no convergía calculando carpetas exclusivas, la
 * única tarea VALIDATION y los archivos de entrada. Java decide lo mecánico a partir de las capas asignadas.
 */
class TeamPlanResolverTest {

    private final TeamPlanResolver resolver = new TeamPlanResolver();

    private static TeamMemberInfo member(String id, List<String> capabilities) {
        return new TeamMemberInfo(id, id, "rol", "ROLE", capabilities, "qwen3:8b");
    }

    private static final TeamSnapshot ENGINEERING = new TeamSnapshot("TEAM-ENGINEERING", "Engineering Team", "ACTIVE",
            "engineering", List.of(
            member("engineering", List.of("arquitectura backend")),
            member("qa", List.of("QA")),
            member("devops", List.of("infraestructura")),
            member("backend", List.of("lógica de negocio")),
            member("frontend-ui", List.of("Game UI"))));

    private static PlannedTask task(String agent, String kind, LayerAssignment... assignments) {
        return new PlannedTask(agent, kind, "WORK_ITEM", "objetivo", List.of("x"), List.of(), List.of(assignments));
    }

    private static LayerAssignment a(String context, String layer) {
        return new LayerAssignment(context, layer);
    }

    private static TeamPlan plan(String profile, List<String> contexts, List<PlannedTask> tasks) {
        return new TeamPlan("Juego", null, null, tasks, List.of(), profile,
                contexts.stream().map(c -> new TeamPlan.BoundedContext(c, "desc")).toList(),
                List.of(new TeamPlan.GlossaryTerm("a", "b"), new TeamPlan.GlossaryTerm("c", "d"),
                        new TeamPlan.GlossaryTerm("e", "f")));
    }

    private static List<PlannedTask> godotTasks() {
        return new ArrayList<>(List.of(
                task("engineering", "WORK", a("Combate", "APPLICATION")),
                task("backend", "WORK", a("Combate", "DOMAIN")),
                task("frontend-ui", "WORK", a(null, "GAME")),
                task("devops", "WORK", a("Combate", "TESTS")),
                task("qa", "VALIDATION")));
    }

    private static PlannedTask of(TeamPlan plan, String agent) {
        return plan.tasksOrEmpty().stream().filter(t -> t.agentId().equals(agent)).findFirst().orElseThrow();
    }

    @Test
    void computesOwnedPathsKindsAndEntryFiles() {
        var result = resolver.resolve(plan("GODOT_DOTNET_GAME", List.of("Combate"), godotTasks()), ENGINEERING);

        assertEquals(List.of(), result.errors());
        assertEquals(List.of("src/Combate.Application", "Solution.sln"), of(result.plan(), "engineering").ownedPaths());
        assertEquals(List.of("src/Combate.Domain"), of(result.plan(), "backend").ownedPaths());
        assertEquals(List.of("game"), of(result.plan(), "frontend-ui").ownedPaths());
        assertEquals("VALIDATION", of(result.plan(), "qa").kind());
        assertEquals(List.of(), of(result.plan(), "qa").ownedPaths());
    }

    // El bug observado en vivo: dos tareas VALIDATION. El kind lo decide Java, no el modelo.
    @Test
    void onlyTheQaMemberValidatesWhateverTheModelWrote() {
        var tasks = godotTasks();
        tasks.set(2, task("frontend-ui", "VALIDATION", a(null, "GAME")));
        tasks.set(4, task("qa", "WORK"));

        var result = resolver.resolve(plan("GODOT_DOTNET_GAME", List.of("Combate"), tasks), ENGINEERING);

        assertEquals("WORK", of(result.plan(), "frontend-ui").kind());
        assertEquals("VALIDATION", of(result.plan(), "qa").kind());
    }

    @Test
    void theSameLayerForTwoMembersIsAnActionableError() {
        var tasks = godotTasks();
        tasks.set(0, task("engineering", "WORK", a("Combate", "DOMAIN")));

        var errors = resolver.resolve(plan("GODOT_DOTNET_GAME", List.of("Combate"), tasks), ENGINEERING).errors();

        assertTrue(errors.stream().anyMatch(e -> e.contains("DOMAIN") && e.contains("backend") && e.contains("engineering")
                && e.contains("uno solo")), errors.toString());
    }

    @Test
    void unknownLayersAndContextsAreErrors() {
        var tasks = godotTasks();
        tasks.set(0, task("engineering", "WORK", a("Combate", "API")));
        tasks.set(3, task("devops", "WORK", a("Inventario", "TESTS")));

        var errors = resolver.resolve(plan("GODOT_DOTNET_GAME", List.of("Combate"), tasks), ENGINEERING).errors();

        assertTrue(errors.stream().anyMatch(e -> e.contains("API") && e.contains("GODOT_DOTNET_GAME")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("Inventario") && e.contains("boundedContexts")), errors.toString());
    }

    @Test
    void aWorkerWithoutAssignmentsIsAnError() {
        var tasks = godotTasks();
        tasks.set(3, task("devops", "WORK"));

        var errors = resolver.resolve(plan("GODOT_DOTNET_GAME", List.of("Combate"), tasks), ENGINEERING).errors();

        assertTrue(errors.stream().anyMatch(e -> e.contains("devops") && e.contains("assignments")), errors.toString());
    }

    @Test
    void everyContextNeedsADomainOwner() {
        var tasks = godotTasks();
        tasks.set(1, task("backend", "WORK", a("Combate", "APPLICATION")));
        tasks.set(0, task("engineering", "WORK", a("Combate", "TESTS")));
        tasks.set(3, task("devops", "WORK", a(null, "GAME")));
        tasks.set(2, task("frontend-ui", "WORK", a(null, "GAME")));

        var errors = resolver.resolve(plan("GODOT_DOTNET_GAME", List.of("Combate"), tasks), ENGINEERING).errors();

        assertTrue(errors.stream().anyMatch(e -> e.contains("DOMAIN") && e.contains("Combate") && e.contains("dueño")), errors.toString());
    }

    @Test
    void godotProjectFileGoesToTheLeaderWhenNobodyOwnsGame() {
        var tasks = godotTasks();
        tasks.set(2, task("frontend-ui", "WORK", a("Combate", "APPLICATION")));
        tasks.set(0, task("engineering", "WORK", a("Combate", "TESTS")));
        tasks.set(3, task("devops", "WORK", a("Combate", "DOMAIN")));
        tasks.set(1, task("backend", "WORK", a("Combate", "DOMAIN")));

        var result = resolver.resolve(plan("GODOT_DOTNET_GAME", List.of("Combate"), tasks), ENGINEERING);

        assertTrue(of(result.plan(), "engineering").ownedPaths().contains("game/project.godot"));
    }

    @Test
    void flutterLeaderGetsTheBootstrapFiles() {
        var tasks = new ArrayList<>(List.of(
                task("engineering", "WORK", a("pedidos", "APPLICATION")),
                task("backend", "WORK", a("pedidos", "DOMAIN")),
                task("frontend-ui", "WORK", a("pedidos", "PRESENTATION")),
                task("devops", "WORK", a("pedidos", "INFRASTRUCTURE")),
                task("qa", "VALIDATION")));

        var result = resolver.resolve(plan("FLUTTER_WEB_APP", List.of("pedidos"), tasks), ENGINEERING);

        assertEquals(List.of(), result.errors());
        assertTrue(of(result.plan(), "engineering").ownedPaths().containsAll(List.of("pubspec.yaml", "lib/main.dart", "web")));
        assertEquals(List.of("lib/pedidos/presentation"), of(result.plan(), "frontend-ui").ownedPaths());
    }

    @Test
    void aPlanWithoutProfileIsLeftForTheValidator() {
        var original = plan("UNITY_GAME", List.of("Combate"), godotTasks());
        var result = resolver.resolve(original, ENGINEERING);
        assertEquals(List.of(), result.errors());
        assertSame(original, result.plan());
    }

    // Verificado en vivo (MISSION-DDD-VERIFY-4): Neo oscilaba entre "DOMAIN duplicada" y "backend sin capas".
    // Ambos errores listan las capas libres para que haya una salida concreta.
    @Test
    void duplicateAndEmptyAssignmentErrorsListTheFreeLayers() {
        var duplicate = godotTasks();
        duplicate.set(0, task("engineering", "WORK", a("Combate", "DOMAIN")));
        var dupErrors = resolver.resolve(plan("GODOT_DOTNET_GAME", List.of("Combate"), duplicate), ENGINEERING).errors();
        assertTrue(dupErrors.stream().anyMatch(e -> e.contains("Capas libres") && e.contains("APPLICATION de Combate")),
                dupErrors.toString());

        var empty = godotTasks();
        empty.set(1, task("backend", "WORK"));
        empty.set(0, task("engineering", "WORK", a("Combate", "DOMAIN")));
        var emptyErrors = resolver.resolve(plan("GODOT_DOTNET_GAME", List.of("Combate"), empty), ENGINEERING).errors();
        assertTrue(emptyErrors.stream().anyMatch(e -> e.contains("backend") && e.contains("Capas libres")
                && e.contains("APPLICATION de Combate")), emptyErrors.toString());
    }

    // Revisión 2 (opción 1, tras MISSION-DDD-VERIFY-4/-5): las capas salen del roleCode, no del modelo.
    private static final TeamSnapshot REAL_ENGINEERING = new TeamSnapshot("TEAM-ENGINEERING", "Engineering Team", "ACTIVE",
            "engineering", List.of(
            new TeamMemberInfo("engineering", "Neo", "rol", "CLOUD_ARCHITECT_LEAD_BACKEND", List.of("arquitectura backend"), "m"),
            new TeamMemberInfo("qa", "Vera", "rol", "QA_CLOUD_PERFORMANCE_ENGINEER", List.of("QA"), "m"),
            new TeamMemberInfo("devops", "Diego", "rol", "CLOUD_DB_SRE_DEVOPS", List.of("infraestructura"), "m"),
            new TeamMemberInfo("backend", "Iris", "rol", "DEV_BACKEND_INTEGRATIONS", List.of("backend"), "m"),
            new TeamMemberInfo("frontend-ui", "Mila", "rol", "FRONTEND_GAME_UI_SPECIALIST", List.of("Game UI"), "m")));

    private static List<PlannedTask> tasksWithoutAssignments() {
        return new ArrayList<>(List.of(
                task("engineering", "WORK"), task("backend", "WORK"), task("frontend-ui", "VALIDATION"),
                task("devops", "WORK"), task("qa", "WORK")));
    }

    @Test
    void layersComeFromTheRoleCodeIgnoringTheModelsAssignments() {
        var tasks = tasksWithoutAssignments();
        tasks.set(1, task("backend", "WORK", a("Combate", "GAME")));

        var result = resolver.resolve(plan("GODOT_DOTNET_GAME", List.of("Combate", "Inventario"), tasks), REAL_ENGINEERING);

        assertEquals(List.of(), result.errors());
        assertEquals(List.of("src/Combate.Domain", "src/Inventario.Domain", "src/Combate.Application", "src/Inventario.Application"),
                of(result.plan(), "backend").ownedPaths());
        assertEquals(List.of("game"), of(result.plan(), "frontend-ui").ownedPaths());
        assertEquals(List.of("tests/Combate.Tests", "tests/Inventario.Tests"), of(result.plan(), "devops").ownedPaths());
        assertEquals(List.of("Solution.sln"), of(result.plan(), "engineering").ownedPaths());
        assertEquals("WORK", of(result.plan(), "frontend-ui").kind());
        assertEquals("VALIDATION", of(result.plan(), "qa").kind());
    }

    @Test
    void flutterRolesMapToTheirLayers() {
        var result = resolver.resolve(plan("FLUTTER_WEB_APP", List.of("pedidos"), tasksWithoutAssignments()), REAL_ENGINEERING);

        assertEquals(List.of(), result.errors());
        assertEquals(List.of("lib/pedidos/presentation"), of(result.plan(), "frontend-ui").ownedPaths());
        assertEquals(List.of("lib/pedidos/infrastructure", "test/pedidos"), of(result.plan(), "devops").ownedPaths());
        assertEquals(List.of("pubspec.yaml", "lib/main.dart", "web"), of(result.plan(), "engineering").ownedPaths());
    }

    @Test
    void repeatedTasksOfTheSameAgentAreMergedIntoOne() {
        var tasks = tasksWithoutAssignments();
        tasks.add(new PlannedTask("engineering", "WORK", "EXTRA", "otra cosa", List.of("y"), List.of()));

        var result = resolver.resolve(plan("GODOT_DOTNET_GAME", List.of("Combate"), tasks), REAL_ENGINEERING);

        var neo = result.plan().tasksOrEmpty().stream().filter(t -> t.agentId().equals("engineering")).toList();
        assertEquals(1, neo.size());
        assertTrue(neo.get(0).objective().contains("objetivo") && neo.get(0).objective().contains("otra cosa"));
        assertTrue(neo.get(0).requiredCapabilitiesOrEmpty().containsAll(List.of("x", "y")));
    }
}
