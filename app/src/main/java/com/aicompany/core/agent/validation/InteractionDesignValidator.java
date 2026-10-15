package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.InteractionDesign.*;
import com.aicompany.core.agent.model.RequirementsSpec;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Spec 2026-10-14 Kael §1.3: reglas de Java sobre el diseño de Kael (lista vacía = válido). */
public final class InteractionDesignValidator {

    public static final int MAX_SCREENS = 15;
    public static final int MAX_RULES = 20;
    public static final int MAX_CONTROLS = 8;
    private static final Pattern PANT = Pattern.compile("PANT-\\d{2}");
    private static final Pattern REGLA = Pattern.compile("REGLA-\\d{2}");
    private static final Pattern ACTION = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Set<String> STATES = Set.of("empty", "loading", "error");

    private InteractionDesignValidator() {
    }

    public static List<String> validateApp(AppDesign design, RequirementsSpec spec) {
        if (design == null) {
            return List.of("El diseño de la app está vacío.");
        }
        var errors = new ArrayList<String>();
        var roles = nn(spec.rolesOrEmpty()).stream().filter(r -> r.name() != null).map(r -> r.name().strip()).collect(Collectors.toSet());
        var screens = new LinkedHashSet<String>();
        if (design.screens().isEmpty()) {
            errors.add("screens debe tener al menos una pantalla PANT-01.");
        }
        if (design.screens().size() > MAX_SCREENS) {
            errors.add("Hay " + design.screens().size() + " pantallas: máximo " + MAX_SCREENS + " (diseña el MVP).");
        }
        for (var s : nn(design.screens())) {
            if (s.id() == null || !PANT.matcher(s.id()).matches()) {
                errors.add("El id de pantalla \"" + s.id() + "\" no tiene el formato PANT-01.");
            } else if (!screens.add(s.id())) {
                errors.add("La pantalla " + s.id() + " está repetida.");
            }
            if (s.role() == null || !roles.contains(s.role().strip())) {
                errors.add(s.id() + ": el rol \"" + s.role() + "\" no está en los roles de Aria " + roles + ".");
            }
            nn(s.states()).stream().filter(st -> !STATES.contains(st)).forEach(st ->
                    errors.add(s.id() + ": el estado \"" + st + "\" no es empty, loading ni error."));
        }
        var stories = nn(spec.userStoriesOrEmpty()).stream().map(RequirementsSpec.UserStory::id).toList();
        var withFlow = new HashSet<String>();
        for (var f : nn(design.flows())) {
            if (!stories.contains(f.story())) {
                errors.add("Hay un flujo para " + f.story() + ", que no existe en las HU " + stories + ".");
            }
            withFlow.add(f.story());
            nn(f.screens()).stream().filter(p -> !screens.contains(p)).forEach(p ->
                    errors.add("El flujo de " + f.story() + " usa " + p + ", que no está en screens."));
        }
        stories.stream().filter(id -> !withFlow.contains(id)).forEach(id ->
                errors.add(id + " no tiene flujo: agrega uno en flows con story \"" + id + "\"."));
        var targets = new ArrayList<Map.Entry<String, String>>();
        nn(design.scenarioScreens()).forEach(m -> targets.add(new AbstractMap.SimpleEntry<>(m.scenario(), m.screen())));
        checkScenarios(spec, targets, screens::contains, "screen", errors);
        if (nn(design.frictionRisks()).stream().allMatch(r -> r == null || r.isBlank())) {
            errors.add("frictionRisks necesita al menos un riesgo de fricción con su hipótesis y mitigación.");
        }
        return errors;
    }

    public static List<String> validateGame(GameDesign design, RequirementsSpec spec) {
        if (design == null) {
            return List.of("El diseño del juego está vacío.");
        }
        var errors = new ArrayList<String>();
        var states = new HashSet<>(nn(design.states()));
        for (var t : nn(design.transitions())) {
            for (var end : new String[]{t.from(), t.to()}) {
                if (!states.contains(end)) {
                    errors.add("La transición " + t.from() + " → " + t.to() + " usa el estado \"" + end
                            + "\", que no está en states " + design.states() + ".");
                }
            }
        }
        for (var e : nn(design.entities())) {
            nn(e.properties()).stream().filter(p -> p.value() == null).forEach(p -> errors.add(e.id() + ": la propiedad \""
                    + p.name() + "\" necesita un número en value (p. ej. 300 para una velocidad en píxeles/segundo)."));
        }
        nn(design.difficulty()).stream().filter(p -> p.value() == null).forEach(p ->
                errors.add("difficulty: \"" + p.name() + "\" necesita un número en value."));
        if (nn(design.controls()).size() > MAX_CONTROLS) {
            errors.add("Hay " + nn(design.controls()).size() + " controles: máximo " + MAX_CONTROLS + ".");
        }
        if (nn(design.rules()).size() > MAX_RULES) {
            errors.add("Hay " + nn(design.rules()).size() + " reglas: máximo " + MAX_RULES + ".");
        }
        var rules = new LinkedHashSet<String>();
        for (var r : nn(design.rules())) {
            if (r.id() == null || !REGLA.matcher(r.id()).matches()) {
                errors.add("El id de regla \"" + r.id() + "\" no tiene el formato REGLA-01.");
            } else if (!rules.add(r.id())) {
                errors.add("La regla " + r.id() + " está repetida.");
            }
        }
        var ruleText = nn(design.rules()).stream().map(r -> r.condition() + " " + r.effect()).collect(Collectors.joining(" "));
        var actions = new LinkedHashSet<String>();
        for (var c : nn(design.controls())) {
            if (c.action() == null || !ACTION.matcher(c.action()).matches()) {
                errors.add("La acción \"" + c.action() + "\" debe ser un nombre de acción de Godot en minúsculas con "
                        + "guiones bajos (p. ej. move_left).");
                continue;
            }
            actions.add(c.action());
            if (!usedIn(ruleText, c.action())) {
                errors.add("El control " + c.action() + " no se usa en ninguna regla: nómbralo en la condition de la "
                        + "regla que dispara, o quítalo.");
            }
        }
        if (nn(design.win()).isEmpty() && nn(design.lose()).isEmpty()) {
            errors.add("Define al menos una condición de win o de lose (REGLA-NN).");
        }
        for (var ref : concat(nn(design.win()), nn(design.lose()))) {
            if (!rules.contains(ref)) {
                errors.add("win/lose usa " + ref + ", que no está en rules.");
            }
        }
        var targets = new ArrayList<Map.Entry<String, String>>();
        nn(design.scenarioRules()).forEach(m -> targets.add(new AbstractMap.SimpleEntry<>(m.scenario(), m.target())));
        checkScenarios(spec, targets, t -> rules.contains(t) || actions.contains(t), "target (REGLA-NN o una action)",
                errors);
        if (nn(design.balanceHypotheses()).stream().allMatch(h -> h == null || h.isBlank())) {
            errors.add("balanceHypotheses necesita al menos una hipótesis de balance.");
        }
        return errors;
    }

    /** Todo escenario del spec asignado una vez, a un destino existente; ningún escenario inventado. */
    private static void checkScenarios(RequirementsSpec spec, List<Map.Entry<String, String>> targets,
                                       java.util.function.Predicate<String> exists, String what, List<String> errors) {
        var known = nn(spec.userStoriesOrEmpty()).stream().flatMap(s -> nn(s.scenariosOrEmpty()).stream())
                .map(RequirementsSpec.Scenario::id).toList();
        var seen = new HashSet<String>();
        targets.forEach(entry -> {
            var scenario = entry.getKey();
            var target = entry.getValue();
            if (!seen.add(scenario)) {
                errors.add(scenario + " está asignado más de una vez: asígnalo a un solo destino.");
            }
            if (!known.contains(scenario)) {
                errors.add("El escenario " + scenario + " no existe en las HU de Aria " + known + ".");
            } else if (target == null || !exists.test(target)) {
                errors.add(scenario + ": el " + what + " \"" + target + "\" no existe en el diseño.");
            }
        });
        known.stream().filter(id -> !seen.contains(id)).forEach(id ->
                errors.add(id + " no está asignado: cada escenario de Aria va a un " + what + "."));
    }

    /** El nombre de la acción aparece como palabra completa (no como parte de move_left). */
    private static boolean usedIn(String text, String action) {
        return Pattern.compile("(?<![a-z0-9_])" + Pattern.quote(action) + "(?![a-z0-9_])").matcher(text).find();
    }

    /** Los elementos nulos que emite el modelo se ignoran: nunca provocan un NPE. */
    private static <T> List<T> nn(List<T> list) {
        return list == null ? List.of() : list.stream().filter(Objects::nonNull).toList();
    }

    private static List<String> concat(List<String> a, List<String> b) {
        var out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
