package com.aicompany.core.recovery;

/**
 * Spec misiones durables §2: la etapa desde la que se retoma una huérfana (Java, sin modelo).
 */
public record ResumePoint(Stage stage, int round, RoundKind kind, Integer increment) {

    /**
     * HUMAN_VERIFY (revisión 2026-10-06 §5): una verificación humana cortada se repite como verificación, sin modelos.
     * DESIGN / DESIGN_ADJUSTMENT (spec 2026-10-14 Kael §1.2): Kael diseña (o ajusta) sobre el borrador de Aria, sin
     * repetir a Aria. JOINT_REVIEW (spec 2026-10-15): Neo y Aria revisan el par DRAFT (HU + diseño) sin repetir a Aria ni
     * a Kael. CONTRACT_ADJUSTMENT (spec 2026-10-16): Aria rehace el contrato con el ajuste reclamado; un contrato DRAFT se
     * retoma en AWAIT_APPROVAL.
     */
    public enum Stage { REQUIREMENTS, ADJUSTMENT, AWAIT_APPROVAL, PLAN, GENERATE, VERIFY, INTERRUPT, HUMAN_VERIFY, DESIGN,
        DESIGN_ADJUSTMENT, JOINT_REVIEW, CONTRACT_ADJUSTMENT }

    public enum RoundKind { ORIGINAL, INCREMENT, EVIDENCE, CORRECTION }

    static final String DEVELOPMENT_TEAM = "TEAM-DEVELOPMENT";

    public static ResumePoint of(MissionSnapshot s) {
        var kind = s.round() == 0 ? RoundKind.ORIGINAL
                : s.correctionRound() ? RoundKind.CORRECTION
                : s.incrementOfRound() != null ? RoundKind.INCREMENT
                : RoundKind.EVIDENCE;
        var increment = s.incrementOfRound();
        if (!DEVELOPMENT_TEAM.equals(s.teamId())) {
            return new ResumePoint(Stage.INTERRUPT, s.round(), kind, increment);
        }
        if (s.humanVerifyTask() != null) {
            return new ResumePoint(Stage.HUMAN_VERIFY, s.round(), kind, increment);
        }
        if (!s.requirementsApproved()) {
            // Contrato (spec 2026-10-16): mientras el contrato espera, todavía no hay HU.
            if (s.pendingContractAdjustment() != null) {
                return new ResumePoint(Stage.CONTRACT_ADJUSTMENT, s.round(), kind, increment);
            }
            if ("DRAFT".equals(s.latestContractStatus())) {
                return new ResumePoint(Stage.AWAIT_APPROVAL, s.round(), kind, increment);
            }
            if (s.pendingAdjustment() != null) {
                return new ResumePoint(Stage.ADJUSTMENT, s.round(), kind, increment);
            }
            if ("DRAFT".equals(s.latestRequirementsStatus()) && s.designNeeded()) {
                if (s.pendingDesignAdjustment() != null) {
                    return new ResumePoint(Stage.DESIGN_ADJUSTMENT, s.round(), kind, increment);
                }
                if (!s.designForLatestSpec()) {
                    return new ResumePoint(Stage.DESIGN, s.round(), kind, increment);
                }
                if (s.jointReviewPending()) {
                    return new ResumePoint(Stage.JOINT_REVIEW, s.round(), kind, increment);
                }
            }
            if ("DRAFT".equals(s.latestRequirementsStatus())) {
                return new ResumePoint(Stage.AWAIT_APPROVAL, s.round(), kind, increment);
            }
            return new ResumePoint(Stage.REQUIREMENTS, s.round(), kind, increment);
        }
        if (!s.planForRound()) {
            return new ResumePoint(Stage.PLAN, s.round(), kind, increment);
        }
        var allCommitted = !s.workTasks().isEmpty() && s.workTasks().stream()
                .allMatch(t -> t.commitSha() != null); // con commit ya entregó (una reparación cortada la deja RUNNING)
        return new ResumePoint(allCommitted ? Stage.VERIFY : Stage.GENERATE, s.round(), kind, increment);
    }
}
