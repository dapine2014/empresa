package com.aicompany.core.model;

import java.time.Instant;
import java.util.List;

public record PolicySnapshot(
        String key,
        int activeVersion,
        double activeValue,
        String createdBy,
        String changeReason,
        Instant updatedAt,
        List<PolicyVersionSummary> history) {
}
