package com.aicompany.core.recovery;

import java.util.List;

/**
 * Spec misiones durables §3: una ronda de corrección (automática o con la indicación del fundador sobre una BLOCKED).
 * {@code failing}: cuántas cosas fallaban al lanzarla (freno "sin avance"); {@code request}: el pedido completo, para
 * retomarla tras un reinicio; {@code failures}: las fallas individuales del reporte (la estrategia compara las de las rondas
 * previas; revisión 2026-10-06, las rondas viejas sin el campo leen vacío).
 */
public record CorrectionRound(int round, int increment, String request, int failing, boolean byFounder,
                              List<String> failures) {

    public CorrectionRound {
        failures = failures == null ? List.of() : List.copyOf(failures);
    }

    public CorrectionRound(int round, int increment, String request, int failing, boolean byFounder) {
        this(round, increment, request, failing, byFounder, List.of());
    }

    /** Encabezado del pedido de una ronda con la indicación del fundador (antes del CorrectionReport). */
    public static final String FOUNDER_PREFIX = "INDICACIÓN DEL FUNDADOR: ";

    /**
     * Spec misiones durables §3: las rondas automáticas del incremento desde la última ronda del fundador (la indicación del
     * fundador pone el contador en cero). Misma regla para el freno y para el chat.
     */
    public static List<CorrectionRound> automaticSinceFounder(List<CorrectionRound> rounds, int increment) {
        return automaticSinceFounder(rounds, increment, 0);
    }

    /**
     * Revisión 2026-10-06 §5: además, una verificación humana deja una marca de reinicio ({@code resetAfterRound}, ver
     * {@link RecoveryMemoryService#resetCounter}); solo cuentan las rondas posteriores a la marca y a la última del fundador.
     */
    public static List<CorrectionRound> automaticSinceFounder(List<CorrectionRound> rounds, int increment, int resetAfterRound) {
        var ofIncrement = rounds.stream().filter(r -> r.increment() == increment && r.round() > resetAfterRound).toList();
        var lastFounder = -1;
        for (int i = 0; i < ofIncrement.size(); i++) {
            if (ofIncrement.get(i).byFounder()) {
                lastFounder = i;
            }
        }
        return ofIncrement.subList(lastFounder + 1, ofIncrement.size()).stream().filter(r -> !r.byFounder()).toList();
    }

    /** La indicación del fundador (primera parte del pedido, sin el encabezado ni el reporte de Java); vacío si es automática. */
    public String founderIndication() {
        if (!byFounder() || request() == null) {
            return "";
        }
        var text = request().startsWith(FOUNDER_PREFIX) ? request().substring(FOUNDER_PREFIX.length()) : request();
        var end = text.indexOf("\n\n" + CorrectionReport.HEADER);
        return (end >= 0 ? text.substring(0, end) : text).strip();
    }
}
