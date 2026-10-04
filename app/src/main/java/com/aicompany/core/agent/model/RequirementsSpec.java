package com.aicompany.core.agent.model;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Spec 2026-10-03 Aria §1: lo que Aria (Product Owner) escribe antes de que se construya nada: roles del producto,
 * historias de usuario con criterios y escenarios, supuestos, fuera de alcance e incrementos (1 = MVP).
 *
 * Verificado en vivo (2026-10-04): el modelo puede escribir un texto suelto donde va una lista; vale como un elemento.
 */
public record RequirementsSpec(String summary, @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> assumptions, @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> outOfScope, List<Role> roles,
                               List<UserStory> userStories, List<Increment> increments) {

    public record Role(String name, String description) {
    }

    public record Scenario(String id, String title, @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> given, @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> when, @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> then) {

        public Scenario {
            given = orEmpty(given);
            when = orEmpty(when);
            then = orEmpty(then);
        }
    }

    public record UserStory(String id, String role, String iWant, String soThat, @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> acceptanceCriteria,
                            @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> dependsOn, List<Scenario> scenarios) {

        public UserStory {
            acceptanceCriteria = orEmpty(acceptanceCriteria);
            dependsOn = orEmpty(dependsOn);
            scenarios = orEmpty(scenarios);
        }

        public List<String> acceptanceCriteriaOrEmpty() {
            return acceptanceCriteria == null ? List.of() : acceptanceCriteria;
        }

        public List<String> dependsOnOrEmpty() {
            return dependsOn == null ? List.of() : dependsOn;
        }

        public List<Scenario> scenariosOrEmpty() {
            return scenarios == null ? List.of() : scenarios;
        }
    }

    public record Increment(int number, String goal, @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> userStoryIds) {

        public Increment {
            userStoryIds = orEmpty(userStoryIds);
        }

        public List<String> userStoryIdsOrEmpty() {
            return userStoryIds == null ? List.of() : userStoryIds;
        }
    }

    /** Revisión final (9): el modelo puede omitir listas (json_object sin esquema forzado); quedan vacías, nunca null. */
    public RequirementsSpec {
        assumptions = orEmpty(assumptions);
        outOfScope = orEmpty(outOfScope);
        roles = orEmpty(roles);
        userStories = orEmpty(userStories);
        increments = orEmpty(increments);
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    public List<String> assumptionsOrEmpty() {
        return assumptions == null ? List.of() : assumptions;
    }

    public List<String> outOfScopeOrEmpty() {
        return outOfScope == null ? List.of() : outOfScope;
    }

    public List<Role> rolesOrEmpty() {
        return roles == null ? List.of() : roles.stream().filter(Objects::nonNull).toList();
    }

    public List<UserStory> userStoriesOrEmpty() {
        return userStories == null ? List.of() : userStories.stream().filter(Objects::nonNull).toList();
    }

    public List<Increment> incrementsOrEmpty() {
        return increments == null ? List.of() : increments.stream().filter(Objects::nonNull).toList();
    }

    public List<Integer> incrementNumbers() {
        return incrementsOrEmpty().stream().map(Increment::number).sorted().toList();
    }

    /** Las HU del incremento, en el orden en que Aria las listó. */
    public List<UserStory> storiesOf(int increment) {
        var ids = incrementsOrEmpty().stream().filter(i -> i.number() == increment)
                .flatMap(i -> i.userStoryIdsOrEmpty().stream()).distinct().toList();
        var out = new ArrayList<UserStory>();
        for (var id : ids) {
            userStoriesOrEmpty().stream().filter(s -> id.equals(s.id())).findFirst().ifPresent(out::add);
        }
        return out;
    }

    public int scenarioCount(int increment) {
        return storiesOf(increment).stream().mapToInt(s -> s.scenariosOrEmpty().size()).sum();
    }

    private static Map<String, Object> stringArray() {
        return Map.of("type", "array", "items", Map.of("type", "string"));
    }

    private static final Map<String, Object> SCENARIO_SCHEMA = Map.of("type", "object",
            "properties", Map.of("id", Map.of("type", "string"), "title", Map.of("type", "string"),
                    "given", stringArray(), "when", stringArray(), "then", stringArray()),
            "required", List.of("id", "title", "given", "when", "then"));

    public static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "summary", Map.of("type", "string"),
                    "assumptions", stringArray(),
                    "outOfScope", stringArray(),
                    "roles", Map.of("type", "array", "items", Map.of("type", "object",
                            "properties", Map.of("name", Map.of("type", "string"), "description", Map.of("type", "string")),
                            "required", List.of("name", "description"))),
                    "userStories", Map.of("type", "array", "items", Map.of("type", "object",
                            "properties", Map.of("id", Map.of("type", "string"), "role", Map.of("type", "string"),
                                    "iWant", Map.of("type", "string"), "soThat", Map.of("type", "string"),
                                    "acceptanceCriteria", stringArray(), "dependsOn", stringArray(),
                                    "scenarios", Map.of("type", "array", "items", SCENARIO_SCHEMA)),
                            "required", List.of("id", "role", "iWant", "soThat", "acceptanceCriteria", "dependsOn",
                                    "scenarios"))),
                    "increments", Map.of("type", "array", "items", Map.of("type", "object",
                            "properties", Map.of("number", Map.of("type", "integer"), "goal", Map.of("type", "string"),
                                    "userStoryIds", stringArray()),
                            "required", List.of("number", "goal", "userStoryIds")))),
            "required", List.of("summary", "assumptions", "outOfScope", "roles", "userStories", "increments"));
}
