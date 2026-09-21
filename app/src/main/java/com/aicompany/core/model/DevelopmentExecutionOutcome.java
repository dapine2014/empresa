package com.aicompany.core.model;

import com.aicompany.core.agent.model.DevelopmentResult;

/**
 * Espejo de {@link AgentExecutionOutcome} para tareas de desarrollo —
 * mismo criterio "agent failure ≠ mission failure" aplicado a la fase de
 * ejecución, no solo a discovery.
 */
public record DevelopmentExecutionOutcome(
        String agentId,
        boolean completed,
        DevelopmentResult result,
        String error) {

    public static DevelopmentExecutionOutcome success(String agentId, DevelopmentResult result) {
        return new DevelopmentExecutionOutcome(agentId, true, result, null);
    }

    public static DevelopmentExecutionOutcome failure(String agentId, String error) {
        return new DevelopmentExecutionOutcome(agentId, false, null, error);
    }
}
