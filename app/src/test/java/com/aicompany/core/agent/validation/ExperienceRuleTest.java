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
        assertTrue(errors.contains("GODOT_DOTNET_GAME") && errors.contains("interaction-design") && errors.contains("qa"), errors);
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
}
