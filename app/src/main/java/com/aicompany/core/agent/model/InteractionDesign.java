package com.aicompany.core.agent.model;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.List;
import java.util.Map;

/** Spec 2026-10-14 Kael §1.3: el diseño de interacción (apps) o de juego que Kael escribe antes del plan de Neo. */
public record InteractionDesign(String experience, AppDesign app, GameDesign game) {

    public record Screen(String id, String name, String purpose, String role,
                         @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> states) {
        public Screen { states = states == null ? List.of() : states; }
    }

    public record Flow(String story, @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> screens,
                       @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> actions) {
        public Flow { screens = screens == null ? List.of() : screens; actions = actions == null ? List.of() : actions; }
    }

    public record ScenarioScreen(String scenario, String screen) { }

    public record AppDesign(List<Screen> screens, List<Flow> flows, List<ScenarioScreen> scenarioScreens,
                            @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> frictionRisks) {
        public AppDesign {
            screens = screens == null ? List.of() : screens;
            flows = flows == null ? List.of() : flows;
            scenarioScreens = scenarioScreens == null ? List.of() : scenarioScreens;
            frictionRisks = frictionRisks == null ? List.of() : frictionRisks;
        }

        public static final Map<String, Object> SCHEMA = Map.of("type", "object",
                "properties", Map.of(
                        "screens", array(Map.of("type", "object", "properties", Map.of("id", str(), "name", str(),
                                "purpose", str(), "role", str(), "states", strings()),
                                "required", List.of("id", "name", "purpose", "role", "states"))),
                        "flows", array(Map.of("type", "object", "properties", Map.of("story", str(),
                                "screens", strings(), "actions", strings()), "required", List.of("story", "screens", "actions"))),
                        "scenarioScreens", array(Map.of("type", "object", "properties", Map.of("scenario", str(),
                                "screen", str()), "required", List.of("scenario", "screen"))),
                        "frictionRisks", strings()),
                "required", List.of("screens", "flows", "scenarioScreens", "frictionRisks"));
    }

    public record Transition(String from, String to, String trigger) { }
    public record Property(String name, Double value) { }
    public record Entity(String id, String name, List<Property> properties) {
        public Entity { properties = properties == null ? List.of() : properties; }
    }
    public record Control(String action, String key, String effect) { }
    public record Rule(String id, String condition, String effect) { }
    public record ScenarioRule(String scenario, String target) { }

    /** Juegos por niveles (spec 2026-10-16 contrato §3): una escena Godot; script es la clase C# y el nombre de sus archivos. */
    public record Scene(String id, String name, String script, String purpose) { }

    /** Por cada nivel o escena del contrato (item MOD-NN): dónde ocurre, sus parámetros y la regla que pasa al siguiente (vacía en el último). */
    public record LevelDesign(String item, String scene, List<Property> parameters, String advanceRule) {
        public LevelDesign { parameters = parameters == null ? List.of() : parameters; }
    }

    public record GameDesign(@JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> states,
                             List<Transition> transitions, List<Entity> entities, List<Control> controls, List<Rule> rules,
                             @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> win,
                             @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> lose,
                             List<Property> difficulty, List<ScenarioRule> scenarioRules,
                             @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> balanceHypotheses,
                             List<Scene> scenes, List<LevelDesign> levels) {
        /** Diseños anteriores a juegos por niveles (spec 2026-10-16): sin escenas ni niveles. */
        public GameDesign(List<String> states, List<Transition> transitions, List<Entity> entities, List<Control> controls,
                          List<Rule> rules, List<String> win, List<String> lose, List<Property> difficulty,
                          List<ScenarioRule> scenarioRules, List<String> balanceHypotheses) {
            this(states, transitions, entities, controls, rules, win, lose, difficulty, scenarioRules, balanceHypotheses,
                    null, null);
        }

        public GameDesign {
            scenes = scenes == null ? List.of() : scenes;
            levels = levels == null ? List.of() : levels;
            states = states == null ? List.of() : states;
            transitions = transitions == null ? List.of() : transitions;
            entities = entities == null ? List.of() : entities;
            controls = controls == null ? List.of() : controls;
            rules = rules == null ? List.of() : rules;
            win = win == null ? List.of() : win;
            lose = lose == null ? List.of() : lose;
            difficulty = difficulty == null ? List.of() : difficulty;
            scenarioRules = scenarioRules == null ? List.of() : scenarioRules;
            balanceHypotheses = balanceHypotheses == null ? List.of() : balanceHypotheses;
        }

        private static Map<String, Object> property() {
            return Map.of("type", "object", "properties", Map.of("name", str(), "value", Map.of("type", "number")),
                    "required", List.of("name", "value"));
        }

        public static final Map<String, Object> SCHEMA = Map.of("type", "object",
                "properties", Map.ofEntries(
                        Map.entry("states", strings()),
                        Map.entry("transitions", array(Map.of("type", "object", "properties", Map.of("from", str(), "to", str(),
                                "trigger", str()), "required", List.of("from", "to", "trigger")))),
                        Map.entry("entities", array(Map.of("type", "object", "properties", Map.of("id", str(), "name", str(),
                                "properties", array(property())), "required", List.of("id", "name", "properties")))),
                        Map.entry("controls", array(Map.of("type", "object", "properties", Map.of("action", str(), "key", str(),
                                "effect", str()), "required", List.of("action", "key", "effect")))),
                        Map.entry("rules", array(Map.of("type", "object", "properties", Map.of("id", str(), "condition", str(),
                                "effect", str()), "required", List.of("id", "condition", "effect")))),
                        Map.entry("win", strings()), Map.entry("lose", strings()),
                        Map.entry("difficulty", array(property())),
                        Map.entry("scenarioRules", array(Map.of("type", "object", "properties", Map.of("scenario", str(),
                                "target", str()), "required", List.of("scenario", "target")))),
                        Map.entry("balanceHypotheses", strings()),
                        Map.entry("scenes", array(Map.of("type", "object", "properties", Map.of("id", str(), "name", str(),
                                "script", str(), "purpose", str()), "required", List.of("id", "name", "script", "purpose")))),
                        Map.entry("levels", array(Map.of("type", "object", "properties", Map.of("item", str(),
                                "scene", str(), "parameters", array(property()), "advanceRule", str()),
                                "required", List.of("item", "scene", "parameters", "advanceRule"))))),
                
                "required", List.of("states", "transitions", "entities", "controls", "rules", "win", "lose",
                        "difficulty", "scenarioRules", "balanceHypotheses"));
    }

    static Map<String, Object> str() { return Map.of("type", "string"); }
    static Map<String, Object> strings() { return Map.of("type", "array", "items", str()); }
    static Map<String, Object> array(Map<String, Object> items) { return Map.of("type", "array", "items", items); }
}
