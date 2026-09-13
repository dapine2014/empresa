package com.aicompany.core.model;

import java.time.Instant;

public record CustomerResponse(
        String customerId,
        String missionId,
        String name,
        Instant recordedAt
) {
}
