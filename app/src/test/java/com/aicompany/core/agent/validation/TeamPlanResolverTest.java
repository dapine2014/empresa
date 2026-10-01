package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.TeamPlan;
import com.aicompany.core.agent.model.TeamPlan.PlannedTask;
import com.aicompany.core.model.TeamMemberInfo;
import com.aicompany.core.model.TeamSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Revisión 4 (spec 2026-10-01): Neo elige quién participa; Java reparte las capas por rol (primarias y de
 * respaldo), desdobla a QA en tests + revisión y da los archivos de entrada al dueño de la capa más externa.
 */
class TeamPlanResolverTest {

    private final TeamPlanResolver resolver = new TeamPlanResolver();

    private static TeamMemberInfo m(String id, String roleCode, String... capabilities) {
        return new TeamMemberInfo(id, id, "rol", roleCode, List.of(capabilities), "m");
    }

    static final TeamSnapshot DEVELOPMENT = new TeamSnapshot("TEAM-DEVELOPMENT", "Development Group", "ACTIVE",
            "engineering", List.of(
            m("product-owner", "PRODUCT_OWNER", "requirements"),
            m("engineering", "TECH_LEAD", "architecture"),
            m("backend", "BACKEND", "backend", "api"),
            m("devops", "DATA_ARCHITECT", "persistence"),
            m("frontend-ui", "UI_UX", "flutter", "ui"),
            m("interaction-design", "GAME_DEV", "godot"),
            m("qa", "QA", "qa", "tests")));

    private static PlannedTask t(String agent, String kind, String capability) {
        return new PlannedTask(agent, kind, "WORK_ITEM", "objetivo de " + agent, List.of(capability), List.of());
    }

    private static TeamPlan plan(String profile, String context, List<PlannedTask> tasks) {
        return new TeamPlan("App", null, null, tasks, List.of(), profile,
                List.of(new TeamPlan.BoundedContext(context, "desc")),
                List.of(new TeamPlan.GlossaryTerm("a", "b"), new TeamPlan.GlossaryTerm("c", "d"),
                        new TeamPlan.GlossaryTerm("e", "f")));
    }

    private static List<PlannedTask> of(TeamPlan plan, String agent) {
        return plan.tasksOrEmpty().stream().filter(t -> t.agentId().equals(agent)).toList();
    }

    @Test
    void flutterWithOnlyUiAndQaGivesUiTheFallbackLayers() {
        var result = resolver.resolve(plan("FLUTTER_WEB_APP", "saludo",
                List.of(t("frontend-ui", "WORK", "flutter"), t("qa", "WORK", "tests"))), DEVELOPMENT);

        assertEquals(List.of(), result.errors());
        assertEquals(List.of("lib/saludo/domain", "lib/saludo/application", "lib/saludo/infrastructure",
                        "lib/saludo/presentation", "pubspec.yaml", "lib/main.dart", "web"),
                of(result.plan(), "frontend-ui").get(0).ownedPaths());
    }

    @Test
    void qaAlwaysEndsWithOneTestsTaskAndOneReview() {
        var result = resolver.resolve(plan("FLUTTER_WEB_APP", "saludo", List.of(
                t("frontend-ui", "WORK", "flutter"),
                t("qa", "VALIDATION", "qa"),
                t("qa", "WORK", "tests"))), DEVELOPMENT);

        var qa = of(result.plan(), "qa");
        assertEquals(2, qa.size());
        assertEquals("WORK", qa.get(0).kind());
        assertEquals(List.of("test/saludo"), qa.get(0).ownedPaths());
        assertEquals("VALIDATION", qa.get(1).kind());
        assertEquals("CODE_REVIEW", qa.get(1).action());
        assertEquals(List.of(), qa.get(1).ownedPaths());
    }

    @Test
    void dotnetSplitsLayersAmongBackendAndDataArchitect() {
        var result = resolver.resolve(plan("DOTNET_APP", "Citas", List.of(
                t("backend", "WORK", "backend"), t("devops", "WORK", "persistence"), t("qa", "WORK", "tests"))),
                DEVELOPMENT);

        assertEquals(List.of(), result.errors());
        assertEquals(List.of("src/Citas.Domain", "src/Citas.Application", "src/Citas.Api"),
                of(result.plan(), "backend").get(0).ownedPaths());
        assertEquals(List.of("src/Citas.Infrastructure"), of(result.plan(), "devops").get(0).ownedPaths());
    }

    @Test
    void requiredCapabilitiesAreNeverRewritten() {
        var result = resolver.resolve(plan("FLUTTER_WEB_APP", "saludo", List.of(
                t("frontend-ui", "WORK", "inventada"), t("qa", "WORK", "tests"))), DEVELOPMENT);

        assertEquals(List.of("inventada"), of(result.plan(), "frontend-ui").get(0).requiredCapabilities());
    }

    @Test
    void tasksOfRolesWithoutCodeOrNotEnabledAreLeftForTheValidator() {
        var result = resolver.resolve(plan("FLUTTER_WEB_APP", "saludo", List.of(
                t("engineering", "WORK", "architecture"),
                t("interaction-design", "WORK", "godot"),
                t("frontend-ui", "WORK", "flutter"), t("qa", "WORK", "tests"))), DEVELOPMENT);

        assertEquals(List.of(), of(result.plan(), "engineering").get(0).ownedPaths());
        assertEquals(List.of(), of(result.plan(), "interaction-design").get(0).ownedPaths());
    }

    @Test
    void flutterEntryFilesWithoutAPresentationOwnerAreAnError() {
        var result = resolver.resolve(plan("FLUTTER_WEB_APP", "saludo", List.of(
                t("backend", "WORK", "backend"), t("qa", "WORK", "tests"))), DEVELOPMENT);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("pubspec.yaml")), result.errors().toString());
    }

    @Test
    void aDomainWithoutOwnerIsAnError() {
        var result = resolver.resolve(plan("DOTNET_APP", "Citas", List.of(
                t("devops", "WORK", "persistence"), t("qa", "WORK", "tests"))), DEVELOPMENT);

        assertTrue(result.errors().stream().anyMatch(e -> e.contains("DOMAIN")), result.errors().toString());
    }

    @Test
    void aPlanWithoutProfileIsLeftForTheValidator() {
        var original = plan(null, "saludo", List.of(t("frontend-ui", "WORK", "flutter")));
        var result = resolver.resolve(original, DEVELOPMENT);

        assertSame(original, result.plan());
        assertEquals(List.of(), result.errors());
    }
}
