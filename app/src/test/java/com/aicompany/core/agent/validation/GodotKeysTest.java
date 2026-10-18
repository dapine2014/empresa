package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;
import com.aicompany.core.agent.model.InteractionDesign.Control;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Ajuste de Kael (Pong de 3 niveles, 2026-10-17): teclas del diseño → códigos de Godot 4. */
class GodotKeysTest {

    private static String event(int code) {
        return "\"events\": [Object(InputEventKey,\"resource_local_to_scene\":false,\"device\":-1,\"keycode\":0,"
                + "\"physical_keycode\":" + code + ",\"key_label\":0,\"unicode\":0,\"echo\":false,\"script\":null)]";
    }

    /** El project.godot del Pong (MISSION-PONG-NIVELES-1792199290): Kael puso 40 para Enter. */
    static final String PONG = """
            config_version=5

            [application]

            run/main_scene="res://Main.tscn"

            [input]

            move_up={
                "deadzone": 0.5,
                %s
            }
            start={
                "deadzone": 0.5,
                %s
            }
            next_level={
                "deadzone": 0.5,
                %s
            }

            [rendering]

            renderer/rendering_method="mobile"
            """.formatted(event(87), event(32), event(40));

    static final List<Control> CONTROLS = List.of(new Control("move_up", "W", "sube"),
            new Control("start", "Space", "inicia"), new Control("next_level", "Enter", "siguiente nivel"));

    @Test void namesAndAliasesMapToGodotCodes() {
        assertEquals(87, GodotKeys.code("W").getAsInt());
        assertEquals(87, GodotKeys.code(" w ").getAsInt());
        assertEquals(49, GodotKeys.code("1").getAsInt());
        assertEquals(32, GodotKeys.code("Space").getAsInt());
        assertEquals(32, GodotKeys.code("barra espaciadora").getAsInt());
        assertEquals(4194309, GodotKeys.code("Enter").getAsInt());
        assertEquals(4194309, GodotKeys.code("Intro").getAsInt());
        assertEquals(4194305, GodotKeys.code("Esc").getAsInt());
        assertEquals(4194320, GodotKeys.code("Up").getAsInt());
        assertEquals(4194322, GodotKeys.code("Flecha abajo").getAsInt());
        assertEquals(4194319, GodotKeys.code("ArrowLeft").getAsInt());
        assertEquals(4194321, GodotKeys.code("right").getAsInt());
        assertTrue(GodotKeys.code("Rueda").isEmpty());
        assertTrue(GodotKeys.code(null).isEmpty());
    }

    @Test void readsTheCodesOfEachActionInTheInputSection() {
        assertEquals(Map.of("move_up", Set.of(87), "start", Set.of(32), "next_level", Set.of(40)),
                GodotKeys.declaredCodes(PONG));
    }

    @Test void flagsTheEnterOfThePong() {
        var problems = GodotKeys.mismatches(PONG, CONTROLS);
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("next_level debe usar la tecla Enter (\"physical_keycode\":4194309) y trae [40]"),
                problems.get(0));
    }

    @Test void theGeneratedBlockRoundTripsWithoutMismatches() {
        var block = GodotKeys.inputBlock(CONTROLS);
        assertTrue(block.startsWith("[input]"), block);
        assertTrue(block.contains("next_level={"), block);
        assertTrue(block.contains("\"physical_keycode\":4194309"), block);
        var fixed = "config_version=5\n\n" + block + "\n[rendering]\n";
        assertEquals(Map.of("move_up", Set.of(87), "start", Set.of(32), "next_level", Set.of(4194309)),
                GodotKeys.declaredCodes(fixed));
        assertEquals(List.of(), GodotKeys.mismatches(fixed, CONTROLS));
    }

    @Test void unknownKeysAndMissingActionsAreLeftToTheOtherChecks() {
        assertEquals(List.of(), GodotKeys.mismatches(PONG, List.of(new Control("jump", "Space", "salta"),
                new Control("start", "Rueda", "x"))));
        assertFalse(GodotKeys.inputBlock(List.of(new Control("start", "Rueda", "x"))).contains("start="));
    }

    @Test void theGateChecksTheDeliveredProjectGodotAndQuotesTheBlock() {
        var errors = GameInputMapGate.check(List.of(new GeneratedFile("game/project.godot", PONG)), CONTROLS);
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).startsWith("game/project.godot: la acción next_level"), errors.get(0));
        assertTrue(errors.get(0).contains("Copia el bloque [input] del diseño tal cual:\n[input]"), errors.get(0));
        assertEquals(List.of(), GameInputMapGate.check(List.of(new GeneratedFile("game/Main.cs", "x")), CONTROLS));
        assertEquals(List.of(), GameInputMapGate.check(List.of(new GeneratedFile("game/project.godot", PONG)), List.of()));
        assertEquals(List.of(), GameInputMapGate.check(List.of(new GeneratedFile("game/project.godot",
                "[input]\n" + GodotKeys.inputBlock(CONTROLS))), CONTROLS));
    }
}
