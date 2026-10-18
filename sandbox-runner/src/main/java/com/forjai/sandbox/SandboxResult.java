package com.forjai.sandbox;

import java.util.List;

/**
 * Contrato de respuesta del runner (spec 2026-09-26 §2). company-core tiene su copia en model/SandboxResult.
 * {@code stagingDir} (entrega del producto): en un PACKAGE, la carpeta propia del job dentro de
 * {@code <staging-root>/<missionId>/} ({@code incremento-N-<jobId>}); null en los demás jobs.
 */
public record SandboxResult(String overall, List<StepResult> steps, String stagingDir) {

    public SandboxResult(String overall, List<StepResult> steps) {
        this(overall, steps, null);
    }

    public static final String PASS = "PASS";
    public static final String FAIL = "FAIL";
    public static final String TIMEOUT = "TIMEOUT";
    public static final String SKIPPED = "SKIPPED";

    /**
     * QA casos de uso (2026-10-05): un test con su nombre y resultado ({@code PASSED|FAILED|SKIPPED}). {@code detail}
     * (2026-10-15): en una partida simulada fallida, el paso y el motivo; si no, null.
     */
    public record TestCase(String name, String outcome, String detail) {
        public TestCase(String name, String outcome) {
            this(name, outcome, null);
        }
    }

    public record StepResult(String name, String status, int exitCode, long durationMs, String outputTail,
                             int testsPassed, int testsFailed, List<TestCase> tests) {

        public StepResult {
            tests = tests == null ? List.of() : List.copyOf(tests);
        }

        public StepResult(String name, String status, int exitCode, long durationMs, String outputTail,
                          int testsPassed, int testsFailed) {
            this(name, status, exitCode, durationMs, outputTail, testsPassed, testsFailed, List.of());
        }
    }
}
