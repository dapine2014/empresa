package com.aicompany.core.model;

import java.time.Instant;

/**
 * Entrada normalizada de la línea de tiempo de "Activity" — derivada de
 * timestamps que Neo4j ya guarda por otras razones (Mission/AgentTask/
 * Evidence/Decision), no de un consumer de Kafka nuevo. Ver
 * {@code ActivityMemoryService}.
 */
public record ActivityItem(
        String type,
        String missionId,
        String agentId,
        String description,
        Instant timestamp
) {
}
