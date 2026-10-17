package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.InteractionDesign;
import com.aicompany.core.agent.model.RequirementsSpec;
import com.aicompany.core.model.SandboxResult;
import com.aicompany.core.model.StaticCheck;

import java.util.*;
import java.util.regex.Pattern;

/** Spec 2026-10-14 Kael §1.4: el código sigue el diseño aprobado (Java, sin modelo). */
public final class DesignConformance {

    public static final String CHECK = "DESIGN_CONFORMANCE";
    /** Prefijo de las partidas simuladas en los tests del sandbox (sandbox-runner TestReportParser). */
    static final String PLAYTHROUGH_PREFIX = "[juego] ";
    private static final Pattern PRESS = Pattern.compile("Press\\(\\s*\"([^\"]+)\"");
    private static final Pattern INPUT_ACTION = Pattern.compile("^([A-Za-z0-9_]+)=");

    private DesignConformance() {
    }

    public static StaticCheck check(InteractionDesign design, RequirementsSpec spec, int upTo,
                                    Map<String, String> headFiles, List<SandboxResult.TestCase> tests) {
        var problems = new ArrayList<String>();
        var files = new TreeMap<>(headFiles);
        if (design.app() != null) {
            var dart = files.entrySet().stream().filter(e -> e.getKey().endsWith(".dart")
                    && (e.getKey().startsWith("lib/") || e.getKey().contains("/lib/"))).map(Map.Entry::getValue).toList();
            var screens = new LinkedHashSet<String>();
            for (int n = 1; n <= upTo; n++) {
                screens.addAll(InteractionDesignText.screensOf(design, spec, n));
            }
            for (var screen : screens) {
                var key = Pattern.compile("Key\\(\\s*['\"]" + Pattern.quote(screen) + "['\"]\\s*\\)");
                if (dart.stream().noneMatch(c -> c != null && key.matcher(c).find())) {
                    problems.add(screen + " sin Key('" + screen + "') en la app");
                }
            }
        }
        if (design.game() != null) {
            var projectGodot = files.entrySet().stream()
                    .filter(e -> e.getKey().endsWith("game/project.godot") && e.getValue() != null)
                    .map(Map.Entry::getValue).findFirst().orElse("");
            var declared = inputActions(projectGodot);
            var designed = design.game().controls().stream().map(InteractionDesign.Control::action).toList();
            designed.stream().filter(a -> !declared.contains(a)).forEach(a ->
                    problems.add("la acción " + a + " no está en [input] de game/project.godot"));
            GodotKeys.mismatches(projectGodot, design.game().controls()).forEach(p ->
                    problems.add("game/project.godot: " + p));
            files.forEach((path, content) -> {
                if (path.contains("game/playthroughs/") && content != null) {
                    var m = PRESS.matcher(content);
                    while (m.find()) {
                        if (!designed.contains(m.group(1))) {
                            problems.add(path + " usa la acción " + m.group(1) + ", que no está en el diseño " + designed);
                        }
                    }
                }
            });
            // Con varios componentes el nombre llega como "[componente] [juego] ..." (ScenarioCoverage.componentTests).
            var passingGames = tests.stream().filter(t -> t.name() != null && t.name().contains(PLAYTHROUGH_PREFIX)
                            && "PASSED".equals(t.outcome()))
                    .flatMap(t -> ScenarioIds.inTestName(t.name()).stream()).collect(java.util.stream.Collectors.toSet());
            var scenarios = new LinkedHashSet<String>();
            for (int n = 1; n <= upTo; n++) {
                scenarios.addAll(InteractionDesignText.scenariosOf(spec, n));
            }
            var targets = InteractionDesignText.scenarioTargets(design);
            scenarios.stream().filter(id -> designed.contains(targets.get(id)) && !passingGames.contains(id))
                    .forEach(id -> problems.add(id + " es de interacción (" + targets.get(id)
                            + ") y no tiene una partida simulada aprobada"));
        }
        return problems.isEmpty()
                ? StaticCheck.pass(CHECK, "El código sigue el diseño aprobado de Kael.", null, List.of())
                : StaticCheck.fail(CHECK, String.join("; ", problems) + ".", null, List.of());
    }

    static Set<String> inputActions(String projectGodot) {
        var actions = new LinkedHashSet<String>();
        var inInput = false;
        for (var line : projectGodot.split("\\R")) {
            var t = line.strip();
            if (t.startsWith("[")) {
                inInput = t.equals("[input]");
                continue;
            }
            var m = INPUT_ACTION.matcher(t);
            if (inInput && m.find()) {
                actions.add(m.group(1));
            }
        }
        return actions;
    }
}
