package com.aicompany.core.model;

import java.time.Instant;

public record DecisionResponse(
        String decisionId,
        String missionId,
        InvestorDecision decision,
        Instant recordedAt,
        Integer evidenceRound
) {
    /** Decisiones sin ronda de evidencia (APPROVE, REJECT). */
    public DecisionResponse(String decisionId, String missionId, InvestorDecision decision, Instant recordedAt) {
        this(decisionId, missionId, decision, recordedAt, null);
    }
}
