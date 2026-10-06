package com.aicompany.core.recovery;

/**
 * Spec misiones durables §2: la etapa desde la que se retoma una huérfana (Java, sin modelo).
 */
public record ResumePoint(Stage stage, int round, RoundKind kind, Integer increment) {

    public enum Stage { REQUIREMENTS, ADJUSTMENT, AWAIT_APPROVAL, PLAN, GENERATE, VERIFY, INTERRUPT }

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
        if (!s.requirementsApproved()) {
            if (s.pendingAdjustment() != null) {
                return new ResumePoint(Stage.ADJUSTMENT, s.round(), kind, increment);
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
                .allMatch(t -> "COMPLETED".equals(t.status()) && t.commitSha() != null);
        return new ResumePoint(allCommitted ? Stage.VERIFY : Stage.GENERATE, s.round(), kind, increment);
    }
}
