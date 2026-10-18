package com.aicompany.core.model;

import java.util.List;

/**
 * Resultado del sandbox-runner (mismo JSON que sandbox-runner/SandboxResult). {@code stagingDir}: en un PACKAGE, la
 * carpeta propia del job ({@code incremento-N-<jobId>}) dentro de {@code .staging/<missionId>/}; null en los demás.
 */
public record SandboxResult(String overall, List<StepResult> steps, String stagingDir) {

    public SandboxResult(String overall, List<StepResult> steps) {
        this(overall, steps, null);
    }

    /**
     * QA casos de uso (2026-10-05): un test con su nombre y resultado ({@code PASSED|FAILED|SKIPPED}). {@code detail}
     * (2026-10-15): en una partida simulada fallida, el paso y el motivo que manda el runner; si no, null.
     */
    public record TestCase(String name, String outcome, String detail) {
        public TestCase(String name, String outcome) {
            this(name, outcome, null);
        }
    }

    public record StepResult(String name, String status, int exitCode, long durationMs, String outputTail,
                             int testsPassed, int testsFailed, List<TestCase> tests) {

        /** Un runner anterior a los tests con nombre manda {@code tests} ausente: lista vacía. */
        public StepResult {
            tests = tests == null ? List.of() : List.copyOf(tests);
        }

        public StepResult(String name, String status, int exitCode, long durationMs, String outputTail,
                          int testsPassed, int testsFailed) {
            this(name, status, exitCode, durationMs, outputTail, testsPassed, testsFailed, List.of());
        }
    }

    /** Todos los tests con nombre de todos los pasos (con varios componentes, de todos). */
    public List<TestCase> allTests() {
        return stepsOrEmpty().stream().flatMap(s -> s.tests().stream()).toList();
    }

    public List<StepResult> stepsOrEmpty() {
        return steps == null ? List.of() : steps;
    }

    public int testsPassed() {
        return stepsOrEmpty().stream().mapToInt(StepResult::testsPassed).sum();
    }

    public int testsFailed() {
        return stepsOrEmpty().stream().mapToInt(StepResult::testsFailed).sum();
    }

    public boolean passed() {
        return "PASS".equals(overall) && stepsOrEmpty().stream().allMatch(s -> "PASS".equals(s.status()));
    }
}
