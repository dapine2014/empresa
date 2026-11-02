package com.aicompany.core.model;

/**
 * Spec 2026-11-01 front con diseño visual §14: la revisión de diseño de una pantalla de un incremento
 * ({@code (:Mission)-[:HAS_DESIGN_REVIEW]->(:DesignScreenReview)}). {@code size} y {@code ssim} son los del peor tamaño
 * comparado; {@code rounds} = llamadas de corrección ya gastadas; {@code extraRounds} = las que sumó el decisor
 * («pedir otra ronda»). El límite de rondas es {@code 2 + extraRounds}.
 */
public record DesignScreenReview(int increment, String screen, String size, double ssim, String status, int rounds,
                                 int extraRounds, String decidedBy, String decisionNote, String updatedAt) {

    public static final String PENDING = "PENDING";
    public static final String PASSED = "PASSED";
    public static final String AWAITING_DECISION = "AWAITING_DECISION";
    public static final String ACCEPTED = "ACCEPTED";

    /** Rondas de corrección del front por pantalla antes de esperar al decisor. */
    public static final int MAX_ROUNDS = 2;

    public int roundLimit() {
        return MAX_ROUNDS + Math.max(0, extraRounds);
    }
}
