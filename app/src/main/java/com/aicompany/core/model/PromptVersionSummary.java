package com.aicompany.core.model;

import java.time.Instant;

public record PromptVersionSummary(
        int version,
        String createdBy,
        String changeReason,
        Instant createdAt
) {
}
