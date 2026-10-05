package com.aicompany.core.agent.validation;

import com.aicompany.core.model.SandboxResult;
import com.aicompany.core.model.StaticCheck;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * QA casos de uso (2026-10-05, decisión del fundador): cada escenario aprobado de los incrementos construidos debe tener
 * al menos un test que corrió y pasó en el sandbox. Puro: escenarios × tests con nombre → cobertura.
 */
public final class ScenarioCoverage {

    public static final String CHECK = "SCENARIO_COVERAGE";

    public record Scenario(String id, String title, int increment) {
    }

    /** {@code passing}: tests aprobados que lo nombran; {@code failing}: los que lo nombran y fallaron o se saltaron. */
    public record Row(Scenario scenario, List<String> passing, List<String> failing) {
        public boolean covered() {
            return !passing.isEmpty();
        }
    }

    private ScenarioCoverage() {
    }

    public static List<Row> compute(List<Scenario> scenarios, List<SandboxResult.TestCase> tests) {
        var rows = new ArrayList<Row>();
        for (var scenario : scenarios) {
            var passing = new ArrayList<String>();
            var failing = new ArrayList<String>();
            for (var test : tests) {
                if (ScenarioIds.inTestName(test.name()).contains(scenario.id())) {
                    ("PASSED".equals(test.outcome()) ? passing : failing).add(test.name());
                }
            }
            rows.add(new Row(scenario, passing, failing));
        }
        return rows;
    }

    public static StaticCheck check(List<Row> rows) {
        var covered = rows.stream().filter(Row::covered).count();
        var missing = rows.stream().filter(r -> !r.covered()).toList();
        var paths = rows.stream().flatMap(r -> r.passing().stream()).toList();
        if (missing.isEmpty()) {
            return StaticCheck.pass(CHECK, covered + "/" + rows.size() + " escenarios con test aprobado.", null, paths);
        }
        return StaticCheck.fail(CHECK, covered + "/" + rows.size() + ": sin test aprobado "
                + missing.stream().map(r -> r.scenario().id() + " (" + r.scenario().title() + ")"
                        + (r.failing().isEmpty() ? "" : " — falló o se saltó: " + String.join(", ", r.failing())))
                .collect(Collectors.joining("; ")) + ".", null, paths);
    }

    /** Reporte de Vera armado por Java: una línea por escenario. */
    public static String report(List<Row> rows) {
        return rows.stream().map(r -> (r.covered() ? "✅ " : "❌ ") + r.scenario().id() + " (incremento "
                        + r.scenario().increment() + ") " + r.scenario().title() + ": "
                        + (r.covered() ? String.join(", ", r.passing()) : "sin test aprobado"))
                .collect(Collectors.joining("\n"));
    }

    /**
     * Al generar: escenarios sin un test que los nombre (solo nombres de test: revisión final C2, el TRX no trae Trait y
     * un comentario no es un test) e ids inexistentes en lo devuelto en este intento (revisión final M3).
     */
    public static List<String> sourceErrors(List<Scenario> scenarios, Map<String, String> testFiles,
                                            Map<String, String> returnedTestFiles) {
        var known = scenarios.stream().map(Scenario::id).collect(Collectors.toCollection(LinkedHashSet::new));
        var named = new LinkedHashSet<String>();
        TestInventory.of(testFiles).keySet().forEach(name -> named.addAll(ScenarioIds.inTestName(name)));
        var returned = new LinkedHashSet<String>();
        TestInventory.of(returnedTestFiles).keySet().forEach(name -> returned.addAll(ScenarioIds.inTestName(name)));
        var errors = new ArrayList<String>();
        var missing = known.stream().filter(id -> !named.contains(id)).toList();
        if (!missing.isEmpty()) {
            errors.add("Faltan tests para " + String.join(", ", missing) + ": escribe al menos un test por escenario con "
                    + "el id en el nombre del test (.NET: método ESC_03_descripcion; Flutter: test('ESC-03 …')).");
        }
        var unknown = returned.stream().filter(id -> !known.contains(id)).sorted().toList();
        if (!unknown.isEmpty()) {
            errors.add("Los escenarios " + String.join(", ", unknown) + " no existen en los requisitos aprobados: usa solo "
                    + String.join(", ", known) + ".");
        }
        return errors;
    }
}
