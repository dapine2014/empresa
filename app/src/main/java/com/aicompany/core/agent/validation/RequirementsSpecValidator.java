package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.RequirementsSpec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/** Spec 2026-10-03 Aria §2: reglas de Java sobre el spec de Aria (lista vacía = válido). */
public final class RequirementsSpecValidator {

    public static final int MAX_SCENARIOS_PER_INCREMENT = 12;
    private static final Pattern HU = Pattern.compile("HU-\\d{2}");
    private static final Pattern ESC = Pattern.compile("ESC-\\d{2}");

    private RequirementsSpecValidator() {
    }

    public static List<String> validate(RequirementsSpec spec) {
        var errors = new ArrayList<String>();
        if (spec == null) {
            return List.of("El spec está vacío.");
        }

        var roleNames = new HashSet<String>();
        if (spec.rolesOrEmpty().isEmpty()) {
            errors.add("roles debe listar al menos un rol: quién usa el producto (p. ej. comprador, administrador).");
        }
        for (var role : spec.rolesOrEmpty()) {
            if (role.name() == null || role.name().isBlank()) {
                errors.add("Hay un rol sin nombre.");
            } else if (!roleNames.add(role.name().strip())) {
                errors.add("El rol \"" + role.name() + "\" está repetido.");
            }
        }

        var stories = spec.userStoriesOrEmpty();
        if (stories.isEmpty()) {
            errors.add("userStories debe tener al menos una historia de usuario.");
        }
        var storyIds = new HashSet<String>();
        var scenarioIds = new HashSet<String>();
        for (var story : stories) {
            var id = story.id();
            if (id == null || !HU.matcher(id).matches()) {
                errors.add("El id de historia \"" + id + "\" no tiene el formato HU-01.");
            } else if (!storyIds.add(id)) {
                errors.add("La historia " + id + " está repetida.");
            }
            if (story.role() == null || !roleNames.contains(story.role().strip())) {
                errors.add(id + ": el rol \"" + story.role() + "\" no está en roles " + roleNames + ".");
            }
            if (story.acceptanceCriteriaOrEmpty().stream().allMatch(c -> c == null || c.isBlank())) {
                errors.add(id + " necesita al menos un criterio de aceptación.");
            }
            if (story.scenariosOrEmpty().isEmpty()) {
                errors.add(id + " necesita al menos un escenario (dado / cuando / entonces).");
            }
            for (var scenario : story.scenariosOrEmpty()) {
                var sid = scenario.id();
                if (sid == null || !ESC.matcher(sid).matches()) {
                    errors.add("El id de escenario \"" + sid + "\" de " + id + " no tiene el formato ESC-01.");
                } else if (!scenarioIds.add(sid)) {
                    errors.add("El escenario " + sid + " está repetido: los ids no se reutilizan.");
                }
                if (empty(scenario.given()) || empty(scenario.when()) || empty(scenario.then())) {
                    errors.add(sid + " de " + id + ": given, when y then no pueden estar vacíos.");
                }
            }
        }

        var numbers = spec.incrementNumbers();
        var expected = IntStream.rangeClosed(1, Math.max(1, numbers.size())).boxed().toList();
        if (numbers.isEmpty() || !numbers.equals(expected)) {
            errors.add("Los incrementos van numerados desde 1 sin huecos (" + expected.toString()
                    .replaceAll("[\\[\\]]", "") + "); recibidos " + numbers + ".");
        }
        var incrementOf = new HashMap<String, Integer>();
        var count = new HashMap<String, Integer>();
        for (var increment : spec.incrementsOrEmpty()) {
            for (var id : increment.userStoryIdsOrEmpty()) {
                count.merge(id, 1, Integer::sum);
                incrementOf.put(id, increment.number());
            }
            var scenarios = spec.scenarioCount(increment.number());
            if (scenarios > MAX_SCENARIOS_PER_INCREMENT) {
                errors.add("El incremento " + increment.number() + " tiene " + scenarios + " escenarios: máximo "
                        + MAX_SCENARIOS_PER_INCREMENT + ". Pasa historias a un incremento posterior.");
            }
        }
        for (var id : storyIds) {
            if (count.getOrDefault(id, 0) != 1) {
                errors.add(id + " debe estar en exactamente un incremento (está en " + count.getOrDefault(id, 0) + ").");
            }
        }

        var depsOf = new HashMap<String, List<String>>();
        for (var story : stories) {
            depsOf.put(story.id(), story.dependsOnOrEmpty());
            for (var dep : story.dependsOnOrEmpty()) {
                if (!storyIds.contains(dep)) {
                    errors.add(story.id() + " depende de " + dep + ", que no existe.");
                } else if (incrementOf.containsKey(dep) && incrementOf.containsKey(story.id())
                        && incrementOf.get(dep) > incrementOf.get(story.id())) {
                    errors.add(story.id() + " depende de " + dep + ", que está en un incremento posterior.");
                }
            }
        }
        if (hasCycle(depsOf)) {
            errors.add("Las dependencias entre historias forman un ciclo.");
        }
        return errors;
    }

    private static boolean empty(List<String> steps) {
        return steps == null || steps.stream().allMatch(s -> s == null || s.isBlank());
    }

    private static boolean hasCycle(Map<String, List<String>> deps) {
        var state = new HashMap<String, Integer>();
        for (var node : deps.keySet()) {
            if (visit(node, deps, state)) {
                return true;
            }
        }
        return false;
    }

    /** 1 = en curso, 2 = terminado. */
    private static boolean visit(String node, Map<String, List<String>> deps, Map<String, Integer> state) {
        var s = state.getOrDefault(node, 0);
        if (s == 1) {
            return true;
        }
        if (s == 2) {
            return false;
        }
        state.put(node, 1);
        for (var next : deps.getOrDefault(node, List.of())) {
            if (deps.containsKey(next) && visit(next, deps, state)) {
                return true;
            }
        }
        state.put(node, 2);
        return false;
    }
}
