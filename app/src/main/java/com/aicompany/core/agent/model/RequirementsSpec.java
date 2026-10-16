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
                               List<UserStory> userStories, List<Increment> increments, String experience,
                               List<ClientContract.Item> contractItems, Boolean useEpics) {

    /** Specs guardados antes de Spec 2026-10-14 Kael §1: sin tipo de experiencia (null). */
    public RequirementsSpec(String summary, List<String> assumptions, List<String> outOfScope, List<Role> roles,
                            List<UserStory> userStories, List<Increment> increments) {
        this(summary, assumptions, outOfScope, roles, userStories, increments, null, null, null);
    }

    /** Specs anteriores al contrato (spec 2026-10-16): sin ítems del contrato ni épicas. */
    public RequirementsSpec(String summary, List<String> assumptions, List<String> outOfScope, List<Role> roles,
                            List<UserStory> userStories, List<Increment> increments, String experience) {
        this(summary, assumptions, outOfScope, roles, userStories, increments, experience, null, null);
    }

    /** Spec 2026-10-14 Kael §1: APP (pantallas), GAME (juego) o NONE (solo API o servicio, sin interfaz). */
    public enum Experience {
        APP, GAME, NONE;

        public static java.util.Optional<Experience> of(String value) {
            return value == null ? java.util.Optional.empty()
                    : java.util.Arrays.stream(values()).filter(e -> e.name().equals(value.strip())).findFirst();
        }
    }

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
                            @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> dependsOn, List<Scenario> scenarios,
                            String contractItem) {

        /** HU anteriores al contrato (spec 2026-10-16): sin ítem. */
        public UserStory(String id, String role, String iWant, String soThat, List<String> acceptanceCriteria,
                         List<String> dependsOn, List<Scenario> scenarios) {
            this(id, role, iWant, soThat, acceptanceCriteria, dependsOn, scenarios, null);
        }

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

    public Experience experienceOrNull() {
        return Experience.of(experience).orElse(null);
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

    private static final java.util.Set<String> PLAYABLE = java.util.Set.of("LEVEL", "SCENE");

    public List<ClientContract.Item> contractItemsOrEmpty() {
        return contractItems == null ? List.of() : contractItems.stream().filter(Objects::nonNull).toList();
    }

    public boolean epics() {
        return Boolean.TRUE.equals(useEpics);
    }

    /** Las HU de un ítem del contrato (su épica), en el orden de userStories. */
    public List<UserStory> storiesOfItem(String itemId) {
        return userStoriesOrEmpty().stream().filter(s -> itemId != null && itemId.equals(s.contractItem())).toList();
    }

    /** Los ítems del contrato con al menos una HU en el incremento, en el orden del contrato. */
    public List<ClientContract.Item> itemsOf(int increment) {
        var items = storiesOf(increment).stream().map(UserStory::contractItem).filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        return contractItemsOrEmpty().stream().filter(i -> items.contains(i.id())).toList();
    }

    /** Los niveles y escenas del contrato (en un juego), en el orden en que se juegan. */
    public List<ClientContract.Item> gameLevels() {
        return contractItemsOrEmpty().stream().filter(i -> PLAYABLE.contains(i.kind())).toList();
    }

    public List<ClientContract.Item> gameLevelsOf(int increment) {
        return itemsOf(increment).stream().filter(i -> PLAYABLE.contains(i.kind())).toList();
    }

    /**
     * Contrato (spec 2026-10-16 contrato §2): Java copia al spec los ítems del contrato aprobado, si lleva épicas y su
     * experiencia (manda el contrato). Sin contrato (misión anterior) los deja vacíos aunque el modelo los haya inventado.
     */
    public RequirementsSpec withContract(ClientContract contract) {
        if (contract == null) {
            return new RequirementsSpec(summary, assumptions, outOfScope, roles, userStories, increments, experience,
                    null, null);
        }
        return new RequirementsSpec(summary, assumptions, outOfScope, roles, userStories, increments,
                contract.experience(), contract.itemsOrEmpty(), contract.epics());
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
                    "experience", Map.of("type", "string", "enum", List.of("APP", "GAME", "NONE")),
                    "assumptions", stringArray(),
                    "outOfScope", stringArray(),
                    "roles", Map.of("type", "array", "items", Map.of("type", "object",
                            "properties", Map.of("name", Map.of("type", "string"), "description", Map.of("type", "string")),
                            "required", List.of("name", "description"))),
                    "userStories", Map.of("type", "array", "items", Map.of("type", "object",
                            "properties", Map.of("id", Map.of("type", "string"), "role", Map.of("type", "string"),
                                    "iWant", Map.of("type", "string"), "soThat", Map.of("type", "string"),
                                    "acceptanceCriteria", stringArray(), "dependsOn", stringArray(),
                                    "scenarios", Map.of("type", "array", "items", SCENARIO_SCHEMA),
                                    "contractItem", Map.of("type", "string")),
                            "required", List.of("id", "role", "iWant", "soThat", "acceptanceCriteria", "dependsOn",
                                    "scenarios"))),
                    "increments", Map.of("type", "array", "items", Map.of("type", "object",
                            "properties", Map.of("number", Map.of("type", "integer"), "goal", Map.of("type", "string"),
                                    "userStoryIds", stringArray()),
                            "required", List.of("number", "goal", "userStoryIds")))),
            "required", List.of("summary", "experience", "assumptions", "outOfScope", "roles", "userStories", "increments"));
}
