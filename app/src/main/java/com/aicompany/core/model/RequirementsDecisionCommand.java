package com.aicompany.core.model;

/** Spec 2026-10-03 Aria §2: decisión del fundador sobre los requisitos de Aria ({@code instruction} solo en ADJUST). */
public record RequirementsDecisionCommand(RequirementsDecision decision, String instruction) {

    public enum RequirementsDecision {
        APPROVE, ADJUST, REJECT
    }
}
