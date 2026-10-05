package com.aicompany.core.agent.validation;

import com.aicompany.core.model.SandboxResult;
import com.aicompany.core.model.StaticCheck;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * QA casos de uso (2026-10-05, decisión del fundador): la suite solo crece. Un test del último commit VERIFIED no se
 * borra, no se renombra y no se salta; agregar tests siempre se permite.
 */
public final class TestSuiteGrowthGate {

    public static final String CHECK = "TEST_SUITE_GROWTH";

    private TestSuiteGrowthGate() {
    }

    /** Al generar: inventario del último VERIFIED contra el código actual. */
    public static List<String> errors(Map<String, Boolean> before, Map<String, Boolean> now) {
        var errors = new ArrayList<String>();
        before.forEach((name, skipped) -> {
            if (Boolean.TRUE.equals(skipped)) {
                return;
            }
            if (!now.containsKey(name)) {
                errors.add("El test \"" + name + "\" ya estaba verificado y lo borraste o renombraste: la suite solo crece; "
                        + "devuélvelo con el mismo nombre (si el test está mal, lo decide el diagnóstico de Neo).");
            } else if (Boolean.TRUE.equals(now.get(name))) {
                errors.add("El test \"" + name + "\" quedó saltado (Skip): un test verificado no se salta.");
            }
        });
        return errors;
    }

    /**
     * En el sandbox: los tests que pasaron en el último VERIFIED tienen que seguir corriendo. Revisión final (I3): el
     * nombre se compara sin argumentos de Theory ni namespace/clase, y un test que nombra escenarios no se da por perdido
     * mientras todos sus escenarios sigan con un test aprobado (corregir un InlineData, mover de clase o renombrar un
     * grupo de Flutter no rompen la suite).
     */
    public static StaticCheck check(List<SandboxResult.TestCase> before, List<SandboxResult.TestCase> now) {
        Set<String> running = now.stream().filter(t -> !"SKIPPED".equals(t.outcome()))
                .map(t -> normalize(t.name())).collect(Collectors.toSet());
        Set<String> coveredNow = now.stream().filter(t -> "PASSED".equals(t.outcome()))
                .flatMap(t -> ScenarioIds.inTestName(t.name()).stream()).collect(Collectors.toSet());
        var lost = before.stream().filter(t -> "PASSED".equals(t.outcome()))
                .filter(t -> !running.contains(normalize(t.name())))
                .filter(t -> {
                    var ids = ScenarioIds.inTestName(t.name());
                    return ids.isEmpty() || !coveredNow.containsAll(ids);
                })
                .map(SandboxResult.TestCase::name).distinct().toList();
        if (lost.isEmpty()) {
            return StaticCheck.pass(CHECK, before.isEmpty() ? "Sin suite verificada anterior."
                    : "Los " + before.size() + " tests de la suite verificada siguen corriendo.", null, List.of());
        }
        return StaticCheck.fail(CHECK, "Tests verificados que ya no corren: " + String.join(", ", lost) + ".", null, lost);
    }

    /** Sin argumentos de Theory; en un nombre .NET calificado (sin espacios), solo el método. */
    static String normalize(String name) {
        if (name == null) {
            return "";
        }
        var paren = name.indexOf('(');
        var base = (paren < 0 ? name : name.substring(0, paren)).strip();
        return base.contains(" ") ? base : base.substring(base.lastIndexOf('.') + 1);
    }
}
