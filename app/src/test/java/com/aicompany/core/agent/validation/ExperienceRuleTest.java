package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.TeamPlan;
import com.aicompany.core.agent.model.TeamPlan.PlannedTask;
import com.aicompany.core.model.TeamMemberInfo;
import com.aicompany.core.model.TeamSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ExperienceRuleTest {

    public static final TeamSnapshot TEAM = new TeamSnapshot("TEAM-DEVELOPMENT", "DG", "ACTIVE", "engineering", List.of(
            member("interaction-design", "GAME_DEV"), member("qa", "QA"), member("frontend-ui", "UI_UX"),
            member("backend", "BACKEND")));

    static TeamMemberInfo member(String id, String role) {
        return new TeamMemberInfo(id, id, role, role, List.of(), "m");
    }

    public static TeamPlan plan(String profile, String... agents) {
        var tasks = java.util.Arrays.stream(agents).map(a -> new PlannedTask(a, "WORK", "HACER", "o",
                List.of("x"), List.of(), List.of())).toList();
        return new TeamPlan("s", "t", "e", tasks, List.of(), profile, List.of(), List.of(), null, List.of(profile));
    }

    @Test void aGameNeedsGodotKaelAndVera() {
        var ins = "TIPO DE EXPERIENCIA: GAME\nPEDIDO: un pong";
        assertEquals(List.of(), ExperienceRule.errors(ins, plan("GODOT_DOTNET_GAME", "interaction-design", "qa"), TEAM));
        var errors = String.join("\n", ExperienceRule.errors(ins, plan("FLUTTER_WEB_APP", "frontend-ui"), TEAM));
        assertTrue(errors.contains("incluye GODOT_DOTNET_GAME en components") && errors.contains("Falta interaction-design (GAME_DEV)")
                && errors.contains("Falta qa (QA)"), errors);
    }

    @Test void anAppNeedsFlutterAndMila() {
        var ins = "TIPO DE EXPERIENCIA: APP\n";
        assertFalse(ExperienceRule.errors(ins, plan("DOTNET_APP", "backend"), TEAM).isEmpty());
        assertEquals(List.of(), ExperienceRule.errors(ins, plan("FLUTTER_WEB_APP", "frontend-ui"), TEAM));
    }

    @Test void noneForbidsInterfaces() {
        assertFalse(ExperienceRule.errors("TIPO DE EXPERIENCIA: NONE", plan("FLUTTER_WEB_APP", "frontend-ui"), TEAM).isEmpty());
    }

    @Test void withoutTheLineNothingIsRequired() {
        assertEquals(List.of(), ExperienceRule.errors("PEDIDO: un CRM", plan("DOTNET_APP", "backend"), TEAM));
        assertTrue(ExperienceRule.declared("PEDIDO: un CRM").isEmpty());
    }

    private static TeamSnapshot teamWithout(String role) {
        return new TeamSnapshot("TEAM-DEVELOPMENT", "DG", "ACTIVE", "engineering",
                TEAM.members().stream().filter(m -> !role.equals(m.roleCode())).toList());
    }

    @Test void aGameWithoutAnActiveGameDevIsAnError() {
        var errors = String.join("\n", ExperienceRule.errors("TIPO DE EXPERIENCIA: GAME",
                plan("GODOT_DOTNET_GAME", "qa"), teamWithout("GAME_DEV")));
        assertTrue(errors.contains("GAME_DEV") && errors.contains("activo"), errors);
    }

    @Test void anAppWithFlutterButWithoutMilaInThePlanIsAnError() {
        var errors = String.join("\n", ExperienceRule.errors("TIPO DE EXPERIENCIA: APP",
                plan("FLUTTER_WEB_APP", "backend"), TEAM));
        assertTrue(errors.contains("Falta frontend-ui (UI_UX)"), errors);
    }

    @Test void anAppWithoutAnActiveUiUxIsAnError() {
        var errors = String.join("\n", ExperienceRule.errors("TIPO DE EXPERIENCIA: APP",
                plan("FLUTTER_WEB_APP", "backend"), teamWithout("UI_UX")));
        assertTrue(errors.contains("UI_UX") && errors.contains("activo"), errors);
    }

    @Test void noneWithoutInterfacesPasses() {
        assertEquals(List.of(), ExperienceRule.errors("TIPO DE EXPERIENCIA: NONE", plan("DOTNET_APP", "backend"), TEAM));
    }

    @Test void declaredToleratesCrlfAndLongerText() {
        assertEquals(java.util.Optional.of(com.aicompany.core.agent.model.RequirementsSpec.Experience.GAME),
                ExperienceRule.declared("TIPO DE EXPERIENCIA: GAME\r\nPEDIDO: x"));
        assertEquals(java.util.Optional.of(com.aicompany.core.agent.model.RequirementsSpec.Experience.APP),
                ExperienceRule.declared("intro\nmás texto\nTIPO DE EXPERIENCIA: APP\nPEDIDO: y\notro"));
    }
}
