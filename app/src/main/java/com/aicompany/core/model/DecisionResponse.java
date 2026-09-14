package com.aicompany.core.model;

import java.time.Instant;

public record DecisionResponse(
        String decisionId,
        String missionId,
        InvestorDecision decision,
        Instant recordedAt
) {
}
