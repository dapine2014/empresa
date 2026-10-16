package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.ClientContract;
import com.aicompany.core.agent.model.InteractionDesign.*;
import com.aicompany.core.agent.model.RequirementsSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Juegos por niveles (spec 2026-10-16 contrato §4): qué escenas necesita cada incremento. */
class GameScenesTest {

    static final RequirementsSpec SPEC = new RequirementsSpec("Pong", List.of(), List.of(),
            List.of(new RequirementsSpec.Role("jugador", "d")),
            List.of(new RequirementsSpec.UserStory("HU-01", "jugador", "q", "p", List.of("c"), List.of(), List.of(), "MOD-01"),
                    new RequirementsSpec.UserStory("HU-02", "jugador", "q", "p", List.of("c"), List.of(), List.of(), "MOD-02")),
            List.of(new RequirementsSpec.Increment(1, "N1", List.of("HU-01")),
                    new RequirementsSpec.Increment(2, "Fin", List.of("HU-02"))), "GAME",
            List.of(new ClientContract.Item("MOD-01", "LEVEL", "Nivel 1", "x"),
                    new ClientContract.Item("MOD-02", "SCENE", "Game over", "x")), true);

    static final GameDesign GAME = new GameDesign(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(),
            List.of(new Scene("ESCENA-01", "Cancha", "Main", "x"), new Scene("ESCENA-02", "Fin", "GameOver", "x"),
                    new Scene("ESCENA-03", "Créditos", "Credits", "x")),
            List.of(new LevelDesign("MOD-01", "ESCENA-01", List.of(), "REGLA-01"),
                    new LevelDesign("MOD-02", "ESCENA-02", List.of(), "")));

    @Test void eachIncrementGetsTheMainSceneAndTheScenesOfItsLevelsAndThePreviousOnes() {
        assertEquals(List.of("Main"), GameScenes.upTo(GAME, SPEC, 1).stream().map(Scene::script).toList());
        assertEquals(List.of("Main", "GameOver"), GameScenes.upTo(GAME, SPEC, 2).stream().map(Scene::script).toList());
    }

    @Test void pathsAndLevels() {
        assertEquals("game/Main.tscn", GameScenes.scenePath("Main"));
        assertEquals("game/Main.cs", GameScenes.scriptPath("Main"));
        assertEquals("Main", GameScenes.main(GAME).orElseThrow().script());
        assertTrue(GameScenes.hasLevels(GAME, "ESCENA-01"));
        assertTrue(GameScenes.hasLevels(GAME, "ESCENA-02"));
        assertFalse(GameScenes.hasLevels(GAME, "ESCENA-03"));
    }

    @Test void withoutScenesThereIsNothing() {
        var old = new GameDesign(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of());
        assertEquals(List.of(), GameScenes.upTo(old, SPEC, 1));
        assertTrue(GameScenes.main(old).isEmpty());
    }
}
