package com.aicompany.core.model;

import java.time.Instant;
import java.util.List;

public record AgentTask(
        String taskId,
        String missionId,
        String agentId,
        String action,
        String status,
        String result,
        Instant updatedAt,
        String kind,
        String workspacePath,
        String commitSha,
        List<String> files,
        String validationStatus,
        String staticChecks,
        String sandboxResult
) {
    /** Tareas de desarrollo sin resultado de sandbox. */
    public AgentTask(String taskId, String missionId, String agentId, String action, String status, String result,
                     Instant updatedAt, String kind, String workspacePath, String commitSha, List<String> files,
                     String validationStatus, String staticChecks) {
        this(taskId, missionId, agentId, action, status, result, updatedAt, kind, workspacePath, commitSha, files,
                validationStatus, staticChecks, null);
    }

    /** Tareas de discovery: sin tipo ni artefactos. */
    public AgentTask(String taskId, String missionId, String agentId, String action,
                     String status, String result, Instant updatedAt) {
        this(taskId, missionId, agentId, action, status, result, updatedAt, null, null, null, null, null, null, null);
    }
}
