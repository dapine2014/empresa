package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.InteractionDesign;
import com.aicompany.core.agent.model.InteractionDesign.*;
import com.aicompany.core.agent.model.RequirementsSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InteractionDesignTextTest {

    static final RequirementsSpec SPEC = new RequirementsSpec("x", List.of(), List.of(),
            List.of(new RequirementsSpec.Role("vendedor", "d")),
            List.of(new RequirementsSpec.UserStory("HU-01", "vendedor", "q", "p", List.of("c"), List.of(),
                            List.of(new RequirementsSpec.Scenario("ESC-01", "a", List.of("d"), List.of("c"), List.of("e")))),
                    new RequirementsSpec.UserStory("HU-02", "vendedor", "q2", "p", List.of("c"), List.of(),
                            List.of(new RequirementsSpec.Scenario("ESC-02", "b", List.of("d"), List.of("c"), List.of("e"))))),
            List.of(new RequirementsSpec.Increment(1, "MVP", List.of("HU-01")),
                    new RequirementsSpec.Increment(2, "más", List.of("HU-02"))), "APP");

    @Test void theAppBlockOnlyCarriesTheScreensOfTheIncrement() {
        var design = new InteractionDesign("APP", new AppDesign(
                List.of(new Screen("PANT-01", "Lista", "ver", "vendedor", List.of()),
                        new Screen("PANT-02", "Detalle", "ver", "vendedor", List.of())),
                List.of(new Flow("HU-01", List.of("PANT-01"), List.of("abrir")),
                        new Flow("HU-02", List.of("PANT-02"), List.of("tocar"))),
                List.of(new ScenarioScreen("ESC-01", "PANT-01"), new ScenarioScreen("ESC-02", "PANT-02")),
                List.of("r")), null);
        var block = InteractionDesignText.incrementBlock(design, SPEC, 1);
        assertTrue(block.contains("PANT-01") && block.contains("Key('PANT-01')"), block);
        assertFalse(block.contains("PANT-02"), block);
    }

    @Test void theGameBlockCarriesControlsEntitiesAndTheRulesOfTheIncrement() {
        var design = new InteractionDesign("GAME", null, new GameDesign(List.of("PLAYING", "GAME_OVER"),
                List.of(new Transition("PLAYING", "GAME_OVER", "REGLA-02")),
                List.of(new Entity("pelota", "Pelota", List.of(new Property("velocidad", 300.0)))),
                List.of(new Control("move_right", "Right", "mueve")),
                List.of(new Rule("REGLA-01", "move_right", "avanza"), new Rule("REGLA-02", "pasa", "pierde"),
                        new Rule("REGLA-03", "otra", "x"), new Rule("REGLA-04", "pelota toca la pared", "rebota"),
                        new Rule("REGLA-05", "se presiona move_right en el borde", "se detiene")),
                List.of(), List.of("REGLA-02"), List.of(), List.of(new ScenarioRule("ESC-01", "REGLA-01"),
                new ScenarioRule("ESC-02", "REGLA-03")), List.of("h")));
        var block = InteractionDesignText.incrementBlock(design, SPEC, 1);
        assertTrue(block.contains("move_right") && block.contains("300") && block.contains("REGLA-01")
                && block.contains("REGLA-02"), block);   // REGLA-02 por ser de lose
        assertFalse(block.contains("REGLA-03"), block);  // solo la apunta un escenario del incremento 2
        assertTrue(block.contains("REGLA-04") && block.contains("rebota"), block);      // sin escenario: va
        assertTrue(block.contains("REGLA-05") && block.contains("se detiene"), block);  // condición de control: va
    }

    @Test void theGameBlockCarriesTheGodotRulesForKaelsCode() {
        var design = new InteractionDesign("GAME", null, new GameDesign(List.of("PLAYING"), List.of(), List.of(),
                List.of(new Control("jump", "Space", "salta")), List.of(new Rule("REGLA-01", "jump", "salta")),
                List.of(), List.of(), List.of(), List.of(new ScenarioRule("ESC-01", "REGLA-01")), List.of()));
        var block = InteractionDesignText.incrementBlock(design, SPEC, 1);
        assertTrue(block.contains("Lee el input SOLO con las acciones de [input]"), block);
        assertTrue(block.contains("No uses GetTree().Quit(), ChangeSceneToFile ni ReloadCurrentScene"), block);
        assertTrue(block.contains("como propiedades públicas, el estado que se verifica"), block);
    }
}
