package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.InteractionDesign.*;
import com.aicompany.core.agent.model.ClientContract;
import com.aicompany.core.agent.model.RequirementsSpec;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Spec 2026-10-14 Kael §1.3: reglas de Java sobre el diseño de Kael (lista vacía = válido). */
public final class InteractionDesignValidator {

    public static final int MAX_SCREENS = 15;
    public static final int MAX_RULES = 20;
    public static final int MAX_CONTROLS = 8;
    public static final int MAX_SCENES = 8;
    public static final Pattern SCRIPT = Pattern.compile("[A-Z][A-Za-z0-9]*");
    /** Clases del runner de Forjai (namespace Forjai.Playthroughs): una escena con ese nombre choca en el build. */
    public static final Set<String> RESERVED_SCRIPTS = Set.of("Playthrough", "PlaythroughRunner", "Step", "StepKind");
    private static final Pattern ESCENA = Pattern.compile("ESCENA-\\d{2}");
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
            if (GodotKeys.code(c.key()).isEmpty()) {
                errors.add("El control " + c.action() + " usa la tecla \"" + c.key() + "\", que no reconozco: usa una "
                        + "de " + GodotKeys.NAMES + ".");
            }
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
        validateLevels(design, spec, rules, ruleText, errors);
        return errors;
    }

    /** Juegos por niveles (spec 2026-10-16 contrato §3): solo si el contrato tiene niveles o escenas. */
    private static void validateLevels(GameDesign design, RequirementsSpec spec, Set<String> rules, String ruleText,
                                       List<String> errors) {
        var levelItems = spec.gameLevels().stream().map(ClientContract.Item::id).filter(Objects::nonNull).toList();
        if (levelItems.isEmpty()) {
            return;
        }
        var scenes = nn(design.scenes());
        if (scenes.size() > MAX_SCENES) {
            errors.add("Hay " + scenes.size() + " escenas: máximo " + MAX_SCENES + ".");
        }
        var sceneIds = new LinkedHashSet<String>();
        var scripts = new HashSet<String>();
        for (var s : scenes) {
            if (s.id() == null || !ESCENA.matcher(s.id()).matches()) {
                errors.add("El id de escena \"" + s.id() + "\" no tiene el formato ESCENA-01.");
            } else if (!sceneIds.add(s.id())) {
                errors.add("La escena " + s.id() + " está repetida.");
            }
            if (s.script() == null || !SCRIPT.matcher(s.script()).matches()) {
                errors.add(s.id() + ": script \"" + s.script() + "\" debe ser un nombre de clase C# que empieza en "
                        + "mayúscula, sin espacios (p. ej. Main, Menu).");
            } else if (RESERVED_SCRIPTS.contains(s.script())) {
                errors.add(s.id() + ": script " + s.script() + " es una clase del runner de Forjai: usa otro nombre.");
            } else if (!scripts.add(s.script())) {
                errors.add("El script " + s.script() + " está repetido: cada escena tiene el suyo.");
            }
        }
        if (!sceneIds.contains("ESCENA-01")) {
            errors.add("scenes necesita ESCENA-01: la escena principal (la que abre el juego).");
        }
        var lastLevel = spec.gameLevels().stream().filter(i -> "LEVEL".equals(i.kind())).reduce((a, b) -> b)
                .map(ClientContract.Item::id).orElse(null);
        var designed = new HashMap<String, Integer>();
        for (var l : nn(design.levels())) {
            designed.merge(String.valueOf(l.item()), 1, Integer::sum);
            if (!levelItems.contains(l.item())) {
                errors.add("Hay un diseño para " + l.item() + ", que no es un nivel o escena del contrato " + levelItems + ".");
                continue;
            }
            if (l.scene() == null || !sceneIds.contains(l.scene())) {
                errors.add(l.item() + " usa la escena " + l.scene() + ", que no está en scenes.");
            }
            nn(l.parameters()).stream().filter(p -> p.value() == null).forEach(p -> errors.add(l.item()
                    + ": el parámetro \"" + p.name() + "\" necesita un número en value (p. ej. velocidad_pelota=200)."));
            var isLevel = spec.gameLevels().stream().anyMatch(i -> l.item().equals(i.id()) && "LEVEL".equals(i.kind()));
            var rule = l.advanceRule() == null ? "" : l.advanceRule().strip();
            if (rule.isEmpty() && isLevel && !l.item().equals(lastLevel)) {
                errors.add(l.item() + " necesita advanceRule: la REGLA-NN que pasa al nivel siguiente.");
            } else if (!rule.isEmpty() && !rules.contains(rule)) {
                errors.add(l.item() + ": advanceRule " + rule + " no está en rules.");
            }
        }
        validateLevelDifferences(design, spec, ruleText, errors);
        for (var id : levelItems) {
            var n = designed.getOrDefault(id, 0);
            if (n == 0) {
                errors.add(id + " no tiene diseño: agrega uno en levels con item \"" + id + "\".");
            } else if (n > 1) {
                errors.add(id + " tiene " + n + " diseños: deja uno.");
            }
        }
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
    /**
     * Ajuste de Kael (Pong de 3 niveles, 2026-10-17): el nivel 2 («la pelota aumenta de velocidad») salió con los mismos
     * parámetros que el 1 y ninguna regla lo usaba. Dos LEVEL del contrato son niveles distintos: algo en parameters los
     * diferencia, y un parámetro que cambia entre niveles lo lee alguna regla.
     */
    private static void validateLevelDifferences(GameDesign design, RequirementsSpec spec, String ruleText,
                                                 List<String> errors) {
        var levels = new LinkedHashMap<String, Map<String, Double>>();
        for (var item : spec.gameLevels().stream().filter(i -> "LEVEL".equals(i.kind())).toList()) {
            nn(design.levels()).stream().filter(l -> item.id() != null && item.id().equals(l.item())).findFirst()
                    .ifPresent(l -> levels.put(item.id(), new TreeMap<>(nn(l.parameters()).stream()
                            .filter(p -> p.name() != null && p.value() != null)
                            .collect(Collectors.toMap(p -> p.name().strip(), Property::value, (a, b) -> b)))));
        }
        var ids = new ArrayList<>(levels.keySet());
        for (int i = 0; i < ids.size(); i++) {
            for (int j = 0; j < i; j++) {
                if (levels.get(ids.get(i)).equals(levels.get(ids.get(j)))) {
                    errors.add(ids.get(i) + " tiene los mismos parameters que " + ids.get(j) + " " + levels.get(ids.get(j))
                            + ", pero el contrato los describe distintos (" + description(spec, ids.get(j)) + " / "
                            + description(spec, ids.get(i)) + "): agrega en parameters lo que cambia en " + ids.get(i)
                            + " (p. ej. aceleracion_por_punto=0.01) y úsalo en una regla.");
                }
            }
        }
        var names = levels.values().stream().flatMap(m -> m.keySet().stream()).collect(Collectors.toCollection(TreeSet::new));
        for (var name : names) {
            var values = levels.values().stream().map(m -> m.getOrDefault(name, 0.0)).collect(Collectors.toSet());
            if (values.size() > 1 && !usedIn(ruleText, name)) {
                errors.add("El parámetro " + name + " cambia entre niveles y ninguna regla lo usa: nómbralo en la "
                        + "condition o el effect de la regla que lo aplica (p. ej. pelota.velocidad *= 1 + " + name + ").");
            }
        }
    }

    private static String description(RequirementsSpec spec, String itemId) {
        return spec.gameLevels().stream().filter(i -> itemId.equals(i.id())).findFirst()
                .map(i -> i.name() + ": " + i.description()).orElse(itemId);
    }

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
