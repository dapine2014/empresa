package com.aicompany.core.model;

import java.time.Instant;

/** Bitácora (spec 2026-10-17): un evento de la misión tal como lo guarda Java (resumen armado por MissionEventText). */
public record MissionEventView(String id, String type, Instant at, String agentId, String agentName, String taskId,
                               String summary, Integer round, Integer increment) {
}
