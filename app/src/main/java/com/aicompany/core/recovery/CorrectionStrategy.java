package com.aicompany.core.recovery;

import java.util.List;

/**
 * Spec misiones durables §3 (revisión 2026-10-06): la estrategia distinta que se buscó cuando una misma falla
 * ({@code fingerprint}) se repitió en la ronda {@code round}; {@code sources}: las URL consultadas.
 */
public record CorrectionStrategy(String fingerprint, int round, String strategy, List<String> sources) {
}
