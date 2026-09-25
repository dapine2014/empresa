package com.aicompany.core.model;

import com.aicompany.core.agent.model.StaticReviewResult;

import java.util.List;

/**
 * Estado de la validación estática, calculado por Java (spec §7) — nunca
 * autodeclarado por el agente validador.
 */
public enum StaticValidationStatus {
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

        if (review.hasBlocker()) {
            return FAILED;
        }

        // Decisión del fundador: ISSUES_FOUND con algún hallazgo MAJOR no es "sin inconsistencias evidentes".
        var majorIssues = "ISSUES_FOUND".equals(review.verdict()) && review.findingsOrEmpty().stream()
                .anyMatch(f -> f != null && "MAJOR".equals(f.severity()));

        return majorIssues ? FAILED : STATICALLY_VALIDATED;
    }
}
