package com.aicompany.core.model;

import java.time.Instant;

/**
 * Estado real (no aspiracional) de un agente: la última {@code AgentTask}
 * que se le asignó, en cualquier misión. {@code status="IDLE"} y el resto
 * de campos en {@code null} si el agente nunca tuvo una tarea todavía.
 */
public record AgentStatusResponse(
        String agentId,
        String status,
        String missionId,
        String action,
        Instant updatedAt
) {
}
