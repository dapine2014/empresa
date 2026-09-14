package com.aicompany.core.model;

/**
 * Decisión real del inversionista humano (Alexander) sobre una misión que
 * llegó a {@code AWAITING_INVESTOR} — nunca generada por un agente ni por
 * el CEO, es exactamente el punto donde `empresa.md` §5 exige intervención
 * humana ("decisiones estratégicas importantes").
 */
public enum InvestorDecision {
    APPROVE,
    REJECT,
    REQUEST_MORE_EVIDENCE
}
