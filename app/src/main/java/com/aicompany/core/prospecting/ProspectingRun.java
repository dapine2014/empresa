package com.aicompany.core.prospecting;

import java.time.Instant;
import java.util.List;

/** Una corrida de búsqueda: status COMPLETED | FAILED. */
public record ProspectingRun(String id, String productId, String strategyId, String status, int found, int valid,
                             List<String> rejections, String error, Instant startedAt, Instant endedAt) {
}
