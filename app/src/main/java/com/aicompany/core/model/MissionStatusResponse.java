package com.aicompany.core.model;

import java.time.Instant;
import java.util.List;

public record MissionStatusResponse(
        MissionResponse mission,
        List<AgentTask> tasks
) {}
