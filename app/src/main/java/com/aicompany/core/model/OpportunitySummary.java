package com.aicompany.core.model;

import java.time.Instant;

public record OpportunitySummary(
        String id,
        String missionId,
        String description,
        String status,
        Instant createdAt
) {
}
