package com.aicompany.core.model;

import com.aicompany.core.agent.model.AgentResult;

/**
 * Resultado de esperar a un agente dentro de una misión: o completó
 * ({@code completed=true}, {@code result} presente) o agotó sus
 * reintentos ({@code completed=false}, {@code error} con el motivo). Con
 * esto {@code MissionExecutor} deja de tratar "un agente falló" como
 * sinónimo de "la misión falló" — antes, {@code allOf(futures).join()}
 * propagaba la primera excepción y tumbaba toda la misión sin importar
 * cuántos otros agentes sí hubieran completado.
 *
 * <p>Deliberadamente más simple que el {@code AgentExecutionOutcome} de
 * `EMPRESA_AI_NUEVO_TODO_EVIDENCE.md` §21 (que agrega {@code recoverable}
 * y {@code attempts}): esos dos campos no tienen una fuente real hoy —
 * {@code AgentRuntime} no expone cuántos intentos usó ni una noción de
 * "recuperable" distinta de "completó o no", así que agregarlos sería
 * inventar datos. El detalle de reintentos ya es público vía el evento
 * Kafka {@code EMPRESA_TASK_RETRY}; este record solo necesita lo mínimo
 * para que {@code MissionExecutor} decida si sigue con resultado parcial.
 */
public record AgentExecutionOutcome(
        String agentId,
        boolean completed,
        AgentResult result,
        String error) {

    public static AgentExecutionOutcome success(String agentId, AgentResult result) {
        return new AgentExecutionOutcome(agentId, true, result, null);
    }

    public static AgentExecutionOutcome failure(String agentId, String error) {
        return new AgentExecutionOutcome(agentId, false, null, error);
    }
}
