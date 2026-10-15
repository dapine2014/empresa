package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.InteractionDesign.*;
import com.aicompany.core.agent.model.RequirementsSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InteractionDesignValidatorTest {

    static final RequirementsSpec SPEC = new RequirementsSpec("x", List.of(), List.of(),
            List.of(new RequirementsSpec.Role("vendedor", "d")),
            List.of(new RequirementsSpec.UserStory("HU-01", "vendedor", "q", "p", List.of("c"), List.of(),
                    List.of(new RequirementsSpec.Scenario("ESC-01", "a", List.of("d"), List.of("c"), List.of("e")),
                            new RequirementsSpec.Scenario("ESC-02", "b", List.of("d"), List.of("c"), List.of("e"))))),
            List.of(new RequirementsSpec.Increment(1, "MVP", List.of("HU-01"))), "APP");

    static AppDesign app(List<Screen> screens, List<Flow> flows, List<ScenarioScreen> map) {
        return new AppDesign(screens, flows, map, List.of("hipótesis"));
    }

    static final Screen P1 = new Screen("PANT-01", "Clientes", "listar", "vendedor", List.of("empty"));

    @Test void aValidAppDesignPasses() {
        assertEquals(List.of(), InteractionDesignValidator.validateApp(app(List.of(P1),
                List.of(new Flow("HU-01", List.of("PANT-01"), List.of("abrir"))),
                List.of(new ScenarioScreen("ESC-01", "PANT-01"), new ScenarioScreen("ESC-02", "PANT-01"))), SPEC));
    }

    @Test void everyScenarioMustBeAssigned() {
        var errors = InteractionDesignValidator.validateApp(app(List.of(P1),
                List.of(new Flow("HU-01", List.of("PANT-01"), List.of("abrir"))),
                List.of(new ScenarioScreen("ESC-01", "PANT-01"))), SPEC);
        assertTrue(errors.stream().anyMatch(e -> e.contains("ESC-02")), errors.toString());
    }

    @Test void unknownIdsScreensAndRolesAreRejected() {
        var errors = InteractionDesignValidator.validateApp(app(
                List.of(new Screen("P-1", "x", "y", "admin", List.of("raro"))),
                List.of(new Flow("HU-09", List.of("PANT-07"), List.of())),
                List.of(new ScenarioScreen("ESC-01", "PANT-07"), new ScenarioScreen("ESC-02", "PANT-07"),
                        new ScenarioScreen("ESC-99", "PANT-07"))), SPEC);
        var all = String.join("\n", errors);
        assertTrue(all.contains("P-1"), all);       // formato PANT-NN
        assertTrue(all.contains("admin"), all);     // rol inexistente
        assertTrue(all.contains("HU-01 no tiene flujo"), all);
        assertTrue(all.contains("HU-09"), all);     // flujo de HU inexistente
        assertTrue(all.contains("PANT-07"), all);   // pantalla inexistente
        assertTrue(all.contains("ESC-99"), all);    // escenario inexistente
        assertTrue(all.contains("raro"), all);      // estado inválido
    }

    static GameDesign game(List<Control> controls, List<Rule> rules, List<Transition> transitions,
                           List<String> lose, List<ScenarioRule> map, Double speed) {
        return new GameDesign(List.of("START", "PLAYING", "GAME_OVER"), transitions,
                List.of(new Entity("pelota", "Pelota", List.of(new Property("velocidad", speed)))),
                controls, rules, List.of(), lose, List.of(new Property("velocidad_por_5_puntos", 1.1)), map,
                List.of("hipótesis"));
    }

    @Test void aValidGameDesignPasses() {
        assertEquals(List.of(), InteractionDesignValidator.validateGame(game(
                List.of(new Control("move_right", "Right", "mueve la paleta")),
                List.of(new Rule("REGLA-01", "move_right", "la paleta avanza"),
                        new Rule("REGLA-02", "la pelota pasa la paleta", "GAME_OVER")),
                List.of(new Transition("START", "PLAYING", "ui_accept"), new Transition("PLAYING", "GAME_OVER", "REGLA-02")),
                List.of("REGLA-02"),
                List.of(new ScenarioRule("ESC-01", "move_right"), new ScenarioRule("ESC-02", "REGLA-02")), 300.0), SPEC));
    }

    @Test void gameRulesAreCheckedInJava() {
        var errors = InteractionDesignValidator.validateGame(game(
                List.of(new Control("Move Right", "Right", "x"), new Control("jump", "Space", "salta")),
                List.of(new Rule("REGLA-01", "algo", "efecto")),
                List.of(new Transition("PLAYING", "PAUSA", "p")),
                List.of(), List.of(new ScenarioRule("ESC-01", "REGLA-05")), null), SPEC);
        var all = String.join("\n", errors);
        assertTrue(all.contains("Move Right"), all);  // formato de acción
        assertTrue(all.contains("jump"), all);        // control sin uso en reglas
        assertTrue(all.contains("PAUSA"), all);       // transición a estado inexistente
        assertTrue(all.contains("velocidad"), all);   // propiedad sin número
        assertTrue(all.contains("win"), all);         // sin win ni lose
        assertTrue(all.contains("REGLA-05"), all);    // destino inexistente
        assertTrue(all.contains("ESC-02"), all);      // escenario sin asignar
    }

    @Test void capsKeepTheDesignAtMvpSize() {
        var many = java.util.stream.IntStream.rangeClosed(1, 16)
                .mapToObj(i -> new Screen("PANT-%02d".formatted(i), "s", "p", "vendedor", List.of())).toList();
        var errors = InteractionDesignValidator.validateApp(app(many,
                List.of(new Flow("HU-01", List.of("PANT-01"), List.of())),
                List.of(new ScenarioScreen("ESC-01", "PANT-01"), new ScenarioScreen("ESC-02", "PANT-01"))), SPEC);
        assertTrue(errors.stream().anyMatch(e -> e.contains("máximo 15")), errors.toString());
    }


    static final List<Flow> FLOW = List.of(new Flow("HU-01", List.of("PANT-01"), List.of("abrir")));
    static final List<ScenarioScreen> MAP = List.of(new ScenarioScreen("ESC-01", "PANT-01"), new ScenarioScreen("ESC-02", "PANT-01"));

    static String joined(List<String> errors) { return String.join("\n", errors); }

    static GameDesign okGame(List<Control> controls, List<Rule> rules, List<String> win, List<String> lose,
                             List<String> hyp) {
        return new GameDesign(List.of("START", "PLAYING"), List.of(), List.of(), controls, rules, win, lose, List.of(),
                List.of(new ScenarioRule("ESC-01", "REGLA-01"), new ScenarioRule("ESC-02", "REGLA-01")), hyp);
    }

    @Test void ruleAndControlCaps() {
        var rules = java.util.stream.IntStream.rangeClosed(1, 21)
                .mapToObj(i -> new Rule("REGLA-%02d".formatted(i), "c", "e")).toList();
        var controls = java.util.stream.IntStream.rangeClosed(1, 9)
                .mapToObj(i -> new Control("act_" + i, "K", "e")).toList();
        var all = joined(InteractionDesignValidator.validateGame(
                okGame(controls, rules, List.of("REGLA-01"), List.of(), List.of("h")), SPEC));
        assertTrue(all.contains("máximo 20"), all);
        assertTrue(all.contains("máximo 8"), all);
    }

    @Test void duplicateAndMalformedIds() {
        var app = joined(InteractionDesignValidator.validateApp(app(List.of(P1, P1), FLOW, MAP), SPEC));
        assertTrue(app.contains("PANT-01 está repetida"), app);
        var game = joined(InteractionDesignValidator.validateGame(okGame(List.of(),
                List.of(new Rule("REGLA-01", "c", "e"), new Rule("REGLA-01", "c", "e"), new Rule("R1", "c", "e")),
                List.of("REGLA-09"), List.of(), List.of("h")), SPEC));
        assertTrue(game.contains("REGLA-01 está repetida"), game);
        assertTrue(game.contains("\"R1\" no tiene el formato"), game);
        assertTrue(game.contains("REGLA-09, que no está en rules"), game);
    }

    @Test void emptyFrictionAndBalanceHypothesesAreRejected() {
        var app = joined(InteractionDesignValidator.validateApp(
                new AppDesign(List.of(P1), FLOW, MAP, List.of(" ")), SPEC));
        assertTrue(app.contains("frictionRisks necesita"), app);
        var game = joined(InteractionDesignValidator.validateGame(okGame(List.of(),
                List.of(new Rule("REGLA-01", "c", "e")), List.of("REGLA-01"), List.of(), List.of()), SPEC));
        assertTrue(game.contains("balanceHypotheses necesita"), game);
    }

    @Test void duplicateScenarioAssignmentIsRejected() {
        var errors = joined(InteractionDesignValidator.validateApp(app(List.of(P1), FLOW,
                List.of(new ScenarioScreen("ESC-01", "PANT-01"), new ScenarioScreen("ESC-01", "PANT-09"),
                        new ScenarioScreen("ESC-02", "PANT-01"))), SPEC));
        assertTrue(errors.contains("ESC-01 está asignado más de una vez"), errors);
        assertTrue(errors.contains("PANT-09"), errors);
    }

    @Test void controlNameMustAppearAsWholeWord() {
        var errors = joined(InteractionDesignValidator.validateGame(okGame(
                List.of(new Control("left", "A", "x")),
                List.of(new Rule("REGLA-01", "move_left", "avanza")), List.of("REGLA-01"), List.of(), List.of("h")), SPEC));
        assertTrue(errors.contains("El control left no se usa"), errors);
    }

    @Test void nullElementsDoNotThrow() {
        var nulls = java.util.Arrays.asList((Screen) null);
        assertDoesNotThrow(() -> InteractionDesignValidator.validateApp(new AppDesign(
                nulls, java.util.Arrays.asList((Flow) null), java.util.Arrays.asList((ScenarioScreen) null),
                java.util.Arrays.asList((String) null)), SPEC));
        assertDoesNotThrow(() -> InteractionDesignValidator.validateGame(new GameDesign(
                java.util.Arrays.asList((String) null), java.util.Arrays.asList((Transition) null),
                java.util.Arrays.asList((Entity) null), java.util.Arrays.asList((Control) null),
                java.util.Arrays.asList((Rule) null), java.util.Arrays.asList((String) null),
                java.util.Arrays.asList((String) null), java.util.Arrays.asList((Property) null),
                java.util.Arrays.asList((ScenarioRule) null), java.util.Arrays.asList((String) null)), SPEC));
        var nullRole = new RequirementsSpec("x", List.of(), List.of(),
                java.util.Arrays.asList(new RequirementsSpec.Role(null, "d"), null), SPEC.userStories(), SPEC.increments(), "APP");
        assertDoesNotThrow(() -> InteractionDesignValidator.validateApp(app(List.of(P1), FLOW, MAP), nullRole));
    }
}
