package com.aicompany.core.model;

import java.time.Instant;

public record DecisionActivity(
        String decisionId,
        String missionId,
        String decision,
        String reasoning,
        Instant decidedAt
) {
}
