package com.aicompany.core.agent.model;

import java.util.List;

/**
 * Resultado de {@code CeoService.executeAgentTask}: el {@link AgentResult}
 * más las URLs que de verdad se confirmaron (fetch real + relevancia,
 * {@code EvidenceAcquisitionService.confirmReachable}) en el turno de
 * herramienta de esa misma llamada, si hubo una. Existe para que
 * {@code EvidenceBindingGate} pueda comparar "qué evidencia real recibió
 * el agente" contra "qué evidencia citó en su resultado final" sin que
 * {@code AgentRuntime} tenga que adivinarlo a partir del JSON de
 * {@code AgentResult} — esa comparación es justamente lo que el gate
 * existe para no depender de que el modelo "se acuerde" de citar lo que
 * buscó.
 */
public record AgentTaskOutcome(
        AgentResult result,
        List<String> confirmedEvidenceUrls) {

    public AgentTaskOutcome {
        confirmedEvidenceUrls =
                confirmedEvidenceUrls == null
                        ? List.of()
                        : List.copyOf(confirmedEvidenceUrls);
    }
}
