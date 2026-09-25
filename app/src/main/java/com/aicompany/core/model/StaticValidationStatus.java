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

        return review.hasBlocker() ? FAILED : STATICALLY_VALIDATED;
    }
}
