package com.aicompany.core.prospecting;

import java.time.Instant;

/** Resultado de una corrida completada: base del rendimiento por estrategia. */
public record RunStat(String productId, String strategyId, int valid, Instant startedAt) {
}
