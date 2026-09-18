package com.aicompany.core.model;

import java.time.Instant;

public record LeadResponse(
        String id,
        String name,
        String description,
        String source,
        String sourceType,
        String missionId,
        String opportunityId,
        Instant createdAt,
        String status,
        String discardReason,
        Instant discardedAt,
        double confidence
) {
}
