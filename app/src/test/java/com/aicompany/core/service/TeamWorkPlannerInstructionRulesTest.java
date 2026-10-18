package com.aicompany.core.service;

import com.aicompany.core.agent.validation.ExperienceRuleTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TeamWorkPlannerInstructionRulesTest {

    // Review Focus 1: "pantalla" en un juego no exige Flutter cuando Aria declaró GAME.
    @Test void aDeclaredGameSkipsTheRequestedComponentsHeuristic() {
        var ins = "TIPO DE EXPERIENCIA: GAME\nPEDIDO ORIGINAL DEL FUNDADOR: un pong con pantalla de game over";
        assertEquals(List.of(), TeamWorkPlanner.instructionRules(ins,
                ExperienceRuleTest.plan("GODOT_DOTNET_GAME", "interaction-design", "qa"), ExperienceRuleTest.TEAM));
    }

    @Test void withoutTheLineTheHeuristicStillRuns() {
        assertFalse(TeamWorkPlanner.instructionRules("una app para inventario",
                ExperienceRuleTest.plan("DOTNET_APP", "backend"), ExperienceRuleTest.TEAM).isEmpty());
    }
}
