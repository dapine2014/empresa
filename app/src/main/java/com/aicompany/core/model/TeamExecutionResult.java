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

    record Development(String resultsForCeo, String verifiableState) implements TeamExecutionResult {
    }
}
