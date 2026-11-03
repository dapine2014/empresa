package com.aicompany.core.model;

import java.util.List;

/**
 * Spec 2026-11-01 front con diseño visual §14: la revisión de diseño de una pantalla de un incremento
 * ({@code (:Mission)-[:HAS_DESIGN_REVIEW]->(:DesignScreenReview)}). {@code size} y {@code ssim} son los del peor tamaño
 * comparado; {@code rounds} = llamadas de corrección ya gastadas; {@code extraRounds} = las que sumó el decisor
 * («pedir otra ronda»). El límite de rondas es {@code 2 + extraRounds}. Revisión R15: {@code differences} y
 * {@code missing} son los de la última comparación de ese tamaño (para verla lado a lado en el Command Center).
 */
public record DesignScreenReview(int increment, String screen, String size, double ssim, String status, int rounds,
                                 int extraRounds, String decidedBy, String decisionNote, String updatedAt,
                                 List<Difference> differences, List<String> missing) {

    /** Una diferencia de un elemento entre la maqueta y la app ({@code reason}: el texto de Java, en español). */
    public record Difference(String id, String reason) {
    }

    public DesignScreenReview {
        differences = differences == null ? List.of() : List.copyOf(differences);
        missing = missing == null ? List.of() : List.copyOf(missing);
    }

    /** Sin diferencias ni elementos faltantes guardados. */
    public DesignScreenReview(int increment, String screen, String size, double ssim, String status, int rounds,
                              int extraRounds, String decidedBy, String decisionNote, String updatedAt) {
        this(increment, screen, size, ssim, status, rounds, extraRounds, decidedBy, decisionNote, updatedAt, List.of(),
                List.of());
    }

    public static final String PENDING = "PENDING";
    public static final String PASSED = "PASSED";
    public static final String AWAITING_DECISION = "AWAITING_DECISION";
    public static final String ACCEPTED = "ACCEPTED";
    /** Transitorio: el claim atómico del punto de decisión (dentro de su transacción). */
    public static final String DECIDING = "DECIDING";

    /** Rondas de corrección del front por pantalla antes de esperar al decisor. */
    public static final int MAX_ROUNDS = 2;

    public int roundLimit() {
        return MAX_ROUNDS + Math.max(0, extraRounds);
    }
}
