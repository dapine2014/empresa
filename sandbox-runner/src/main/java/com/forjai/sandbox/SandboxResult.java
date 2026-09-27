package com.forjai.sandbox;

import java.util.List;

/** Contrato de respuesta del runner (spec 2026-09-26 §2). company-core tiene su copia en model/SandboxResult. */
public record SandboxResult(String overall, List<StepResult> steps) {

    public static final String PASS = "PASS";
    public static final String FAIL = "FAIL";
    public static final String TIMEOUT = "TIMEOUT";
    public static final String SKIPPED = "SKIPPED";

    public record StepResult(String name, String status, int exitCode, long durationMs, String outputTail,
                             int testsPassed, int testsFailed) {
    }
}
