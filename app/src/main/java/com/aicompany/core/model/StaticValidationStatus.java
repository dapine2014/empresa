package com.aicompany.core.model;

import com.aicompany.core.agent.model.StaticReviewResult;

import java.util.List;

/**
 * Estado de la validación estática, calculado por Java (spec §7) — nunca
 * autodeclarado por el agente validador.
 */
public enum StaticValidationStatus {
    VERIFIED,
    STATICALLY_VALIDATED,
    UNVALIDATED,
    FAILED;

    public static StaticValidationStatus compute(List<StaticCheck> checks, StaticReviewResult review) {

        if (checks == null || checks.isEmpty() || checks.stream().anyMatch(c -> !c.passed())) {
            return FAILED;
        }

        if (review == null) {
            return UNVALIDATED;
        }

        // Decisión del fundador: cualquier BLOCKER o MAJOR hace fallar la validación, sin importar el verdict.
        var seriousFinding = review.findingsOrEmpty().stream()
                .anyMatch(f -> f != null && ("BLOCKER".equals(f.severity()) || "MAJOR".equals(f.severity())));

        return seriousFinding ? FAILED : STATICALLY_VALIDATED;
    }

    /**
     * Con sandbox (spec 2026-09-26 §4): FAILED si falla un chequeo, el sandbox,
     * hay BLOCKER/MAJOR o 0 tests pasados; UNVALIDATED si el sandbox no corrió o
     * no hubo revisión; VERIFIED si todo pasa con ≥1 test.
     */
    public static StaticValidationStatus compute(List<StaticCheck> checks, StaticReviewResult review, SandboxResult sandbox) {

        if (checks == null || checks.isEmpty() || checks.stream().anyMatch(c -> !c.passed())) {
            return FAILED;
        }

        if (sandbox != null && (!sandbox.passed() || sandbox.testsPassed() == 0)) {
            return FAILED;
        }

        var reviewOnly = compute(checks, review);
        if (reviewOnly == FAILED) {
            return FAILED;
        }

        if (sandbox == null || review == null) {
            return UNVALIDATED;
        }

        return VERIFIED;
    }
}
