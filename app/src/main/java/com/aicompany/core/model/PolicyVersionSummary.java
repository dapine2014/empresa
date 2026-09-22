package com.aicompany.core.model;

import java.time.Instant;

public record PolicyVersionSummary(
        int version,
        double value,
        String createdBy,
        String changeReason,
        Instant createdAt) {
}
