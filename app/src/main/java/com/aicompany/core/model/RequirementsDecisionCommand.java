package com.aicompany.core.model;

/** Spec 2026-10-03 Aria §2: decisión del fundador sobre los requisitos de Aria ({@code instruction} solo en ADJUST). */
public record RequirementsDecisionCommand(RequirementsDecision decision, String instruction, Target target) {

    public enum RequirementsDecision {
        APPROVE, ADJUST, REJECT
    }

    /** Spec 2026-10-14 Kael §3: a quién va un ADJUST (default Aria). VISUAL (spec 2026-11-01): al front, el diseño visual. */
    public enum Target {
        ARIA, KAEL, VISUAL
    }

    public RequirementsDecisionCommand(RequirementsDecision decision, String instruction) {
        this(decision, instruction, Target.ARIA);
    }

    public Target targetOrAria() {
        return target == null ? Target.ARIA : target;
    }
}
