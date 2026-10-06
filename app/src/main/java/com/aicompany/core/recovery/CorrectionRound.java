package com.aicompany.core.recovery;

/**
 * Spec misiones durables §3: una ronda de corrección (automática o con la indicación del fundador sobre una BLOCKED).
 * {@code failing}: cuántas cosas fallaban al lanzarla (freno "sin avance"); {@code request}: el pedido completo, para
 * retomarla tras un reinicio.
 */
public record CorrectionRound(int round, int increment, String request, int failing, boolean byFounder) {
}
