package com.aicompany.core.model;

import java.util.List;

/**
 * Lo que devuelve una estrategia de equipo a MissionExecutor, que sigue
 * siendo el dueño de la consolidación: resultados de AgentRuntime (se
 * consolidan como discovery) o el reporte de desarrollo + el bloque
 * "Estado verificable" generado por Java.
 */
public sealed interface TeamExecutionResult {

    record AgentOutcomes(List<AgentExecutionOutcome> outcomes) implements TeamExecutionResult {
    }

    /**
     * {@code blockReason} no nulo (spec 2026-11-01 diseño visual §14, {@code DESIGN_MISMATCH: …}): la misión se frena en
     * BLOCKED para el decisor en vez de consolidarse.
     */
    record Development(String resultsForCeo, String verifiableState, String blockReason) implements TeamExecutionResult {
        public Development(String resultsForCeo, String verifiableState) {
            this(resultsForCeo, verifiableState, null);
        }
    }
}
