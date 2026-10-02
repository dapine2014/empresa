package com.aicompany.core.service;

import com.aicompany.core.agent.validation.CompilerErrorParser;
import com.aicompany.core.model.AppComponent;
import com.aicompany.core.model.SandboxResult;
import com.aicompany.core.model.SandboxResult.StepResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Spec 2026-10-02 B1 §3: un VERIFY por componente, combinado. Pasa solo si cada componente pasó con ≥1 test aprobado;
 * si falta el resultado de alguno (runner caído), no hay resultado: la misión queda UNVALIDATED, nunca VERIFIED.
 */
public final class ComponentSandbox {

    private ComponentSandbox() {
    }

    public static Optional<SandboxResult> combine(List<AppComponent> components, List<Optional<SandboxResult>> results) {
        if (results.stream().anyMatch(Optional::isEmpty)) {
            return Optional.empty();
        }
        if (components.size() == 1) {
            return results.get(0);
        }
        var steps = new ArrayList<StepResult>();
        var passed = true;
        for (int i = 0; i < components.size(); i++) {
            var component = components.get(i);
            var result = results.get(i).orElseThrow();
            passed &= result.passed() && result.testsPassed() > 0;
            for (var step : result.stepsOrEmpty()) {
                steps.add(new StepResult(component.prefix(step.name()), step.status(), step.exitCode(),
                        step.durationMs(), step.outputTail(), step.testsPassed(), step.testsFailed()));
            }
        }
        return Optional.of(new SandboxResult(passed ? "PASS" : "FAIL", steps));
    }

    public static String stepName(String name) {
        return name == null ? "" : name.substring(name.lastIndexOf('/') + 1);
    }

    public static String dirOf(String name) {
        return name == null || !name.contains("/") ? "" : name.substring(0, name.lastIndexOf('/'));
    }

    /** Errores del compilador del primer paso fallido (restore/build), con la ruta real en el repositorio. */
    public static List<CompilerErrorParser.CompilerError> compileErrors(SandboxResult result) {
        var failed = result.stepsOrEmpty().stream()
                .filter(step -> "FAIL".equals(step.status()) || "TIMEOUT".equals(step.status()))
                .findFirst();
        if (failed.isEmpty() || !List.of("restore", "build").contains(stepName(failed.get().name()))) {
            return List.of();
        }
        var dir = dirOf(failed.get().name());
        return CompilerErrorParser.parse(failed.get().outputTail()).stream()
                .map(e -> dir.isEmpty() ? e : new CompilerErrorParser.CompilerError(dir + "/" + e.path(), e.line(),
                        e.column(), e.code(), e.message()))
                .toList();
    }
}
