package com.aicompany.core.recovery;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Spec misiones durables §2: desde dónde se retoma una huérfana, decidido en Java. */
class ResumePointTest {

    private static MissionSnapshot dev(int round, Integer increment, boolean correction, String reqStatus, boolean approved,
                                       String adjustment, boolean plan, List<MissionSnapshot.RoundTask> tasks) {
        return new MissionSnapshot("M-1", "TEAM-DEVELOPMENT", round, increment, correction, reqStatus, approved, adjustment,
                plan, tasks);
    }

    @Test void withoutTeamOrOutsideTheDevelopmentGroupItIsInterrupted() {
        assertEquals(ResumePoint.Stage.INTERRUPT, ResumePoint.of(new MissionSnapshot("M-1", null, 0, null, false, null, false,
                null, false, List.of())).stage());
        assertEquals(ResumePoint.Stage.INTERRUPT, ResumePoint.of(new MissionSnapshot("M-1", "TEAM-MARKETING-GROWTH", 0, null,
                false, null, false, null, false, List.of())).stage());
    }
    // Revisión 2026-10-06 §5 (fix 2): una verificación humana cortada se repite como verificación (nunca una ronda).
    @Test void anInterruptedHumanVerificationIsVerifiedAgain() {
        var s = new MissionSnapshot("M-1", "TEAM-DEVELOPMENT", 3, 1, true, "APPROVED", true, null, true, List.of(
                new MissionSnapshot.RoundTask("M-1-BACKEND-R3", "RUNNING", null)), "M-1-HUMAN-VERIFY-2");
        var p = ResumePoint.of(s);
        assertEquals(ResumePoint.Stage.HUMAN_VERIFY, p.stage());
        assertEquals(3, p.round());
        assertEquals(ResumePoint.Stage.GENERATE, ResumePoint.of(new MissionSnapshot("M-1", "TEAM-DEVELOPMENT", 3, 1, true,
                "APPROVED", true, null, true, List.of(new MissionSnapshot.RoundTask("M-1-BACKEND-R3", "RUNNING", null)), null))
                .stage());
    }
    @Test void withoutRequirementsAriaWritesAgain() {
        assertEquals(ResumePoint.Stage.REQUIREMENTS, ResumePoint.of(dev(0, null, false, null, false, null, false, List.of())).stage());
    }
    @Test void aClaimedAdjustmentIsRepeated() {
        assertEquals(ResumePoint.Stage.ADJUSTMENT, ResumePoint.of(dev(0, null, false, "DRAFT", false, "solo clientes", false,
                List.of())).stage());
    }
    @Test void aDraftNotYetAwaitingGoesToApproval() {
        assertEquals(ResumePoint.Stage.AWAIT_APPROVAL, ResumePoint.of(dev(0, null, false, "DRAFT", false, null, false,
                List.of())).stage());
    }
    @Test void approvedWithoutPlanPlans() {
        var p = ResumePoint.of(dev(0, null, false, "APPROVED", true, null, false, List.of()));
        assertEquals(ResumePoint.Stage.PLAN, p.stage());
        assertEquals(ResumePoint.RoundKind.ORIGINAL, p.kind());
    }
    @Test void aPlanWithATaskWithoutCommitGenerates() {
        var p = ResumePoint.of(dev(2, 2, false, "APPROVED", true, null, true, List.of(
                new MissionSnapshot.RoundTask("M-1-BACKEND-R2", "COMPLETED", "abc"),
                new MissionSnapshot.RoundTask("M-1-FRONTEND-UI-R2", "RUNNING", null))));
        assertEquals(ResumePoint.Stage.GENERATE, p.stage());
        assertEquals(ResumePoint.RoundKind.INCREMENT, p.kind());
        assertEquals(2, p.increment());
        assertEquals(2, p.round());
    }
    @Test void allTasksCommittedVerifies() {
        assertEquals(ResumePoint.Stage.VERIFY, ResumePoint.of(dev(0, null, false, "APPROVED", true, null, true, List.of(
                new MissionSnapshot.RoundTask("M-1-BACKEND", "COMPLETED", "abc")))).stage());
    }
    // Verificado en vivo: una tarea RUNNING con su commit (reinicio durante una reparación) ya entregó; se verifica.
    @Test void aRunningTaskWithItsCommitCountsAsDelivered() {
        assertEquals(ResumePoint.Stage.VERIFY, ResumePoint.of(dev(0, null, false, "APPROVED", true, null, true, List.of(
                new MissionSnapshot.RoundTask("M-1-BACKEND", "RUNNING", "abc"),
                new MissionSnapshot.RoundTask("M-1-QA", "COMPLETED", "def")))).stage());
    }
    // Review Focus 4: una ronda de corrección cortada se retoma como corrección (misma ronda, sin contar otra).
    @Test void aCorrectionRoundIsResumedAsCorrection() {
        var p = ResumePoint.of(dev(3, null, true, "APPROVED", true, null, true, List.of()));
        assertEquals(ResumePoint.RoundKind.CORRECTION, p.kind());
        assertEquals(3, p.round());
    }
    @Test void anEvidenceRoundIsResumedAsEvidence() {
        assertEquals(ResumePoint.RoundKind.EVIDENCE, ResumePoint.of(dev(1, null, false, "APPROVED", true, null, true,
                List.of())).kind());
    }
    // Spec 2026-10-14 Kael §1.2: un reinicio con el spec en DRAFT y sin diseño para esa versión retoma en Kael.
    @Test void aDraftThatNeedsADesignWithoutOneGoesToKael() {
        var s = new MissionSnapshot("M-1", "TEAM-DEVELOPMENT", 0, null, false, "DRAFT", false, null, false, List.of(), null,
                true, false, null);
        assertEquals(ResumePoint.Stage.DESIGN, ResumePoint.of(s).stage());
    }
    @Test void aClaimedDesignAdjustmentIsRepeated() {
        var s = new MissionSnapshot("M-1", "TEAM-DEVELOPMENT", 0, null, false, "DRAFT", false, null, false, List.of(), null,
                true, true, "más grande la paleta");
        assertEquals(ResumePoint.Stage.DESIGN_ADJUSTMENT, ResumePoint.of(s).stage());
    }
    @Test void aDraftWithItsDesignAwaitsApproval() {
        var s = new MissionSnapshot("M-1", "TEAM-DEVELOPMENT", 0, null, false, "DRAFT", false, null, false, List.of(), null,
                true, true, null);
        assertEquals(ResumePoint.Stage.AWAIT_APPROVAL, ResumePoint.of(s).stage());
    }
    // Review Focus 3: un spec sin experience (o NONE) no necesita a Kael.
    @Test void aLegacyDraftAwaitsApprovalWithoutKael() {
        assertEquals(ResumePoint.Stage.AWAIT_APPROVAL, ResumePoint.of(dev(0, null, false, "DRAFT", false, null, false,
                List.of())).stage());
    }
    // Revisión conjunta (spec 2026-10-15): diseño DRAFT del spec DRAFT sin revisión cerrada → se retoma la revisión.
    @Test
    void aDraftPairWithoutAClosedReviewResumesInTheJointReview() {
        var s = new MissionSnapshot("M-1", "TEAM-DEVELOPMENT", 0, 1, false, "DRAFT", false, null, false, List.of(), null,
                true, true, null, true);
        assertEquals(ResumePoint.Stage.JOINT_REVIEW, ResumePoint.of(s).stage());
    }

    @Test
    void aDraftPairAlreadyReviewedWaitsForTheFounder() {
        var s = new MissionSnapshot("M-1", "TEAM-DEVELOPMENT", 0, 1, false, "DRAFT", false, null, false, List.of(), null,
                true, true, null, false);
        assertEquals(ResumePoint.Stage.AWAIT_APPROVAL, ResumePoint.of(s).stage());
    }
}
