package com.aicompany.core.model;

import java.time.Instant;

/** Un ciclo del orquestador: de "no hay nada que vender" a un producto listo (o fallido/detenido, con el motivo). */
public record OrchestratorRun(String id, OrchestratorStatus status, String productId, String discoveryMissionId,
                              String buildMissionId, String choiceReason, Instant startedAt, Instant updatedAt,
                              String failureReason) {

    public OrchestratorRun with(OrchestratorStatus newStatus) {
        return new OrchestratorRun(id, newStatus, productId, discoveryMissionId, buildMissionId, choiceReason, startedAt,
                Instant.now(), failureReason);
    }
}
