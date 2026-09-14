package com.aicompany.core.model;

import java.time.Instant;

/**
 * Estado real (no aspiracional) de un agente. {@code status} es una
 * propiedad propia del nodo {@code Agent} en Neo4j ({@code WORKING}/
 * {@code IDLE}, escrita por {@code AgentRuntime} en cada transición) —
 * deliberadamente distinta de {@code taskStatus}, que es el status de la
 * última {@code AgentTask} asignada (p. ej. {@code COMPLETED}/
 * {@code FAILED}). Antes ambas cosas se conflaban en un solo campo: un
 * agente con su última tarea en {@code COMPLETED} se mostraba
 * "trabajando" para siempre, tanto en el chat como en la pantalla Agents.
 *
 * <p>{@code missionId}/{@code action}/{@code taskStatus}/{@code updatedAt}
 * quedan en {@code null} si el agente nunca tuvo una tarea todavía.
 */
public record AgentStatusResponse(
        String agentId,
        String name,
        String role,
        String personality,
        String status,
        String missionId,
        String action,
        String taskStatus,
        Instant updatedAt
) {
}
