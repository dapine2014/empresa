package com.aicompany.core.model;

import java.time.Instant;

public record AgentTask(
        String taskId,
        String missionId,
        String agentId,
        String action,
        String status,
        String result,
        Instant updatedAt
) {}
