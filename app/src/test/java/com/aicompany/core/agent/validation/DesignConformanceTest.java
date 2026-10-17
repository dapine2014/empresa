package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.InteractionDesign;
import com.aicompany.core.agent.model.InteractionDesign.*;
import com.aicompany.core.model.SandboxResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DesignConformanceTest {

    static final InteractionDesign APP = new InteractionDesign("APP", new AppDesign(
            List.of(new Screen("PANT-01", "Lista", "ver", "vendedor", List.of())),
            List.of(new Flow("HU-01", List.of("PANT-01"), List.of())),
            List.of(new ScenarioScreen("ESC-01", "PANT-01")), List.of("r")), null);

    @Test void everyScreenNeedsItsKeyInTheApp() {
        var spec = InteractionDesignTextTest.SPEC;
        var fail = DesignConformance.check(APP, spec, 1, Map.of("lib/main.dart", "void main() {}"), List.of());
        assertFalse(fail.passed(), fail.detail());
        assertTrue(fail.detail().contains("PANT-01"));
        assertEquals(DesignConformance.CHECK, fail.check());
        var pass = DesignConformance.check(APP, spec, 1,
                Map.of("app/lib/clientes/presentation/lista.dart", "return Scaffold(key: const Key('PANT-01'));"), List.of());
        assertTrue(pass.passed(), pass.detail());
    }

    // Revisión Task 12: ValueKey y comillas dobles valen; PANT-010 no satisface PANT-01.
    @Test void keyVariantsAndExactIds() {
        var spec = InteractionDesignTextTest.SPEC;
        assertTrue(DesignConformance.check(APP, spec, 1,
                Map.of("lib/a.dart", "Scaffold(key: const ValueKey('PANT-01'))"), List.of()).passed());
        assertTrue(DesignConformance.check(APP, spec, 1,
                Map.of("lib/a.dart", "Scaffold(key: const Key(\"PANT-01\"))"), List.of()).passed());
        assertFalse(DesignConformance.check(APP, spec, 1,
                Map.of("lib/a.dart", "Scaffold(key: const Key('PANT-010'))"), List.of()).passed());
    }

    // Una Key fuera de lib/ (p. ej. en un test) no cuenta.
    @Test void aKeyOutsideLibDoesNotCount() {
        var check = DesignConformance.check(APP, InteractionDesignTextTest.SPEC, 1,
                Map.of("app/test/lista_test.dart", "find.byKey(const Key('PANT-01'))"), List.of());
        assertFalse(check.passed(), check.detail());
    }

    static final InteractionDesign GAME = new InteractionDesign("GAME", null, new GameDesign(List.of("PLAYING"),
            List.of(), List.of(), List.of(new Control("move_right", "Right", "x")),
            List.of(new Rule("REGLA-01", "move_right", "avanza")), List.of(), List.of("REGLA-01"), List.of(),
            List.of(new ScenarioRule("ESC-01", "move_right")), List.of("h")));

    static final String GODOT = "[application]\nrun/main_scene=\"res://Main.tscn\"\n[input]\nmove_right={\n\"deadzone\": 0.5,\n"
            + "\"events\": [Object(InputEventKey,\"keycode\":0,\"physical_keycode\":4194321,\"script\":null)]\n}\n";

    @Test void gameActionsMustBeDeclaredAndInteractionScenariosNeedAPassingPlaythrough() {
        var spec = InteractionDesignTextTest.SPEC;
        var playthrough = "yield return Press(\"move_right\", frames: 10);";
        var pass = DesignConformance.check(GAME, spec, 1, Map.of("game/project.godot", GODOT,
                "game/playthroughs/ESC_01_mueve.cs", playthrough),
                List.of(new SandboxResult.TestCase("[juego] ESC_01_mueve", "PASSED")));
        assertTrue(pass.passed(), pass.detail());

        var undeclared = DesignConformance.check(GAME, spec, 1, Map.of("game/project.godot", "[input]\n",
                "game/playthroughs/ESC_01_mueve.cs", playthrough),
                List.of(new SandboxResult.TestCase("[juego] ESC_01_mueve", "PASSED")));
        assertTrue(undeclared.detail().contains("move_right") && !undeclared.passed());

        var foreignAction = DesignConformance.check(GAME, spec, 1, Map.of("game/project.godot", GODOT,
                "game/playthroughs/ESC_01_mueve.cs", "Press(\"jump\", frames: 1)"),
                List.of(new SandboxResult.TestCase("[juego] ESC_01_mueve", "PASSED")));
        assertTrue(foreignAction.detail().contains("jump") && !foreignAction.passed());

        var noPlaythrough = DesignConformance.check(GAME, spec, 1, Map.of("game/project.godot", GODOT),
                List.of(new SandboxResult.TestCase("Pong.Tests.ESC_01_mueve", "PASSED")));
        assertTrue(noPlaythrough.detail().contains("ESC-01") && !noPlaythrough.passed());

        var failedPlaythrough = DesignConformance.check(GAME, spec, 1, Map.of("game/project.godot", GODOT),
                List.of(new SandboxResult.TestCase("[juego] ESC_01_mueve", "FAILED")));
        assertFalse(failedPlaythrough.passed(), failedPlaythrough.detail());
    }

    // Misión con varios componentes: rutas con prefijo y tests con "[componente] " delante (ScenarioCoverage.componentTests).
    @Test void multiComponentPathsAndTestNamesStillCount() {
        var check = DesignConformance.check(GAME, InteractionDesignTextTest.SPEC, 1,
                Map.of("game-client/game/project.godot", GODOT,
                        "game-client/game/playthroughs/ESC_01_mueve.cs", "Press(\"move_right\", frames: 2)"),
                List.of(new SandboxResult.TestCase("[game-client] [juego] ESC_01_mueve", "PASSED")));
        assertTrue(check.passed(), check.detail());
    }

    // Ajuste de Kael (Pong de 3 niveles, 2026-10-17): Kael puso 40 para Enter en project.godot.
    @Test void anActionWithTheWrongKeyFails() {
        var check = DesignConformance.check(GAME, InteractionDesignTextTest.SPEC, 1,
                Map.of("game/project.godot", GODOT.replace("4194321", "39"),
                        "game/playthroughs/ESC_01_mueve.cs", "Press(\"move_right\", frames: 2)"),
                List.of(new SandboxResult.TestCase("[juego] ESC_01_mueve", "PASSED")));
        assertFalse(check.passed());
        assertTrue(check.detail().contains("move_right debe usar la tecla Right (\"physical_keycode\":4194321) y trae [39]"),
                check.detail());
    }

    @Test void inputActionsOnlyComeFromTheInputSection() {
        assertEquals(java.util.Set.of("move_right"), DesignConformance.inputActions(GODOT));
    }
}
