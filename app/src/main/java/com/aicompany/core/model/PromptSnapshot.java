package com.aicompany.core.model;

import java.time.Instant;
import java.util.List;

public record PromptSnapshot(
        String agentId,
        int activeVersion,
        String activeContent,
        String activeCreatedBy,
        String activeChangeReason,
        Instant activeCreatedAt,
        List<PromptVersionSummary> versions
) {
}
