package com.aicompany.core.recovery;

import com.aicompany.core.model.MissionResponse;
import com.aicompany.core.model.MissionStatus;
import com.aicompany.core.model.PolicyKey;
import com.aicompany.core.service.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Spec misiones durables §1 y §3: qué se recupera, en qué orden, una de desarrollo a la vez, y los frenos. */
class MissionRecoveryServiceTest {

    private final MissionMemoryService missions = mock(MissionMemoryService.class);
    private final RequirementsMemoryService requirements = mock(RequirementsMemoryService.class);
    private final RecoveryMemoryService recovery = mock(RecoveryMemoryService.class);
    private final MissionExecutor executor = mock(MissionExecutor.class);
    private final CompanyPolicyService policies = mock(CompanyPolicyService.class);
    private final ModelCostService cost = mock(ModelCostService.class);
    private final MissionRecoveryService service = new MissionRecoveryService(missions, requirements, recovery, executor,
            policies, cost, Runnable::run);

    {
        when(policies.activeValue(PolicyKey.MAX_AUTO_CORRECTIONS)).thenReturn(3.0);
        when(policies.activeValue(PolicyKey.MAX_MISSION_COST_USD)).thenReturn(5.0);
        when(cost.missionCost(anyString())).thenReturn(new ModelCostService.Summary(List.of()));
        when(executor.resumeAsync(anyString(), any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        when(executor.correctAsync(anyString(), anyInt(), anyString(), anyBoolean())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        when(missions.claimNextRound(anyString(), anyInt(), anySet())).thenReturn(true);
    }

    private static MissionResponse mission(String id, MissionStatus status, String team) {
        return new MissionResponse(id, status, "TEST", 0, "", "", Instant.now(), null, team);
    }

    /** Lo que devuelven el listado y la relectura de cada misión. */
    private void listed(MissionResponse... all) {
        when(missions.findAll(anyInt())).thenReturn(List.of(all));
        for (var m : all) {
            when(missions.find(m.missionId())).thenReturn(Optional.of(m));
        }
    }

    private void approved(String missionId) {
        when(requirements.approved(missionId)).thenReturn(Optional.of(new RequirementsMemoryService.Version(1, "{}", "APPROVED", "", null)));
        when(requirements.currentIncrement(missionId)).thenReturn(1);
    }

    @Test void atStartupWorkingAgentsGoIdleAndADiscoveryOrphanIsInterrupted() {
        when(missions.findAll(anyInt())).thenReturn(List.of(mission("M-D", MissionStatus.WAITING_AGENT_RESULTS, null)));
        service.onStartup();
        verify(missions).resetWorkingAgents();
        verify(executor).resumeAsync(eq("M-D"), argThat(p -> p.stage() == ResumePoint.Stage.INTERRUPT));
    }

    // Review Focus 5: una misión viva (lanzada por el fundador) no es huérfana.
    @Test void aLiveMissionIsNotAnOrphan() {
        when(missions.findAll(anyInt())).thenReturn(List.of(mission("M-1", MissionStatus.PLANNING, "TEAM-DEVELOPMENT")));
        when(executor.isLive("M-1")).thenReturn(true);
        service.onStartup();
        service.recoverAll();
        verify(executor, never()).resumeAsync(anyString(), any());
    }

    // Review Task 4: las huérfanas solo se detectan al arrancar; una misión recién persistida en estado de trabajo
    // (antes de registrarse como viva) no se confunde con una huérfana.
    @Test void aWorkingMissionNotSeenAtStartupIsNeverResumed() {
        when(missions.findAll(anyInt())).thenReturn(List.of());
        service.onStartup();
        when(missions.findAll(anyInt())).thenReturn(List.of(mission("M-1", MissionStatus.PLANNING, "TEAM-DEVELOPMENT")));
        service.recoverAll();
        verify(executor, never()).resumeAsync(anyString(), any());
    }

    @Test void onlyOneDevelopmentMissionIsDispatchedAtATime() {
        when(missions.findAll(anyInt())).thenReturn(List.of(
                mission("M-1", MissionStatus.PLANNING, "TEAM-DEVELOPMENT"),
                mission("M-2", MissionStatus.EVALUATING, "TEAM-DEVELOPMENT")));
        when(missions.createdAt("M-1")).thenReturn(Optional.of(Instant.parse("2026-10-05T10:00:00Z")));
        when(missions.createdAt("M-2")).thenReturn(Optional.of(Instant.parse("2026-10-05T09:00:00Z")));
        service.onStartup();
        verify(executor).resumeAsync(eq("M-2"), any());          // la más antigua
        verify(executor, never()).resumeAsync(eq("M-1"), any());
    }

    @Test void aDispatchedOrphanIsNotDispatchedAgain() {
        when(missions.findAll(anyInt())).thenReturn(List.of(mission("M-1", MissionStatus.PLANNING, "TEAM-DEVELOPMENT")));
        service.onStartup();
        service.recoverAll();
        verify(executor, times(1)).resumeAsync(eq("M-1"), any());
    }

    @Test void aDevelopmentInterruptedMissionIsResumedByRecoverAll() {
        when(missions.findAll(anyInt())).thenReturn(List.of(mission("M-1", MissionStatus.INTERRUPTED, "TEAM-DEVELOPMENT")));
        service.recoverAll();
        verify(executor).resumeAsync(eq("M-1"), any());
    }

    @Test void aDiscoveryInterruptedMissionIsNotResumedByRecoverAll() {
        when(missions.findAll(anyInt())).thenReturn(List.of(mission("M-D", MissionStatus.INTERRUPTED, null)));
        service.recoverAll();
        verify(executor, never()).resumeAsync(anyString(), any());
    }

    @Test void aPendingOrphanGoesBeforeAnInterruptedMission() {
        when(missions.findAll(anyInt())).thenReturn(List.of(
                mission("M-I", MissionStatus.INTERRUPTED, "TEAM-DEVELOPMENT"),
                mission("M-O", MissionStatus.PLANNING, "TEAM-DEVELOPMENT")));
        service.onStartup();
        verify(executor).resumeAsync(eq("M-O"), any());
        verify(executor, never()).resumeAsync(eq("M-I"), any());
    }

    @Test void aFailedValidationWithApprovedRequirementsStartsACorrection() {
        listed(mission("M-1", MissionStatus.AWAITING_INVESTOR, "TEAM-DEVELOPMENT"));
        when(missions.lastValidationStatus("M-1")).thenReturn(Optional.of("FAILED"));
        when(requirements.approved("M-1")).thenReturn(Optional.of(new RequirementsMemoryService.Version(1, "{}", "APPROVED", "", null)));
        when(requirements.currentIncrement("M-1")).thenReturn(1);
        when(missions.evidenceRound("M-1")).thenReturn(0);
        service.recoverAll();
        verify(recovery).addCorrectionRound(eq("M-1"), argThat(r -> r.round() == 1 && r.increment() == 1 && !r.byFounder()));
        verify(missions).claimNextRound("M-1", 0, MissionRecoveryService.CORRECTABLE);
        verify(executor).correctAsync(eq("M-1"), eq(1), startsWith(CorrectionReport.HEADER), eq(false));
    }

    @Test void unvalidatedCancelledOrWithoutRequirementsAreLeftAlone() {
        when(missions.findAll(anyInt())).thenReturn(List.of(
                mission("M-U", MissionStatus.AWAITING_INVESTOR, "TEAM-DEVELOPMENT"),
                mission("M-C", MissionStatus.CANCELLED, "TEAM-DEVELOPMENT"),
                mission("M-N", MissionStatus.FAILED, "TEAM-DEVELOPMENT")));
        when(missions.lastValidationStatus("M-U")).thenReturn(Optional.of("UNVALIDATED"));
        when(requirements.approved(anyString())).thenReturn(Optional.empty());
        service.recoverAll();
        verify(executor, never()).correctAsync(anyString(), anyInt(), anyString(), anyBoolean());
    }

    // Spec misiones durables §2: "reanuda" relanza una discovery INTERRUPTED como la primera vez.
    @Test void resumingAnInterruptedDiscoveryRunsItAgain() {
        when(missions.find("M-D")).thenReturn(Optional.of(mission("M-D", MissionStatus.INTERRUPTED, null)));
        when(missions.instructionOf("M-D")).thenReturn(Optional.of("busca un nicho"));
        service.resume("M-D");
        verify(executor).executeAsync("M-D", "busca un nicho");
    }

    @Test void onlyInterruptedMissionsCanBeResumed() {
        when(missions.find("M-1")).thenReturn(Optional.of(mission("M-1", MissionStatus.FAILED, "TEAM-DEVELOPMENT")));
        assertThrows(IllegalStateException.class, () -> service.resume("M-1"));
    }

    /** Revisión 2026-10-06 §5: misión del Development Group detenida, con repositorio y plan guardado. */
    private DevelopmentWorkspaceService verifiable(MissionStatus status) {
        var workspace = mock(DevelopmentWorkspaceService.class);
        service.setWorkspace(workspace);
        when(missions.find("M-1")).thenReturn(Optional.of(mission("M-1", status, "TEAM-DEVELOPMENT")));
        when(workspace.headIfAny("M-1")).thenReturn(Optional.of("h".repeat(40)));
        when(missions.lastTeamPlanJson("M-1")).thenReturn(Optional.of("{}"));
        when(requirements.currentIncrement("M-1")).thenReturn(2);
        when(executor.verifyAsync("M-1")).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        return workspace;
    }

    // Review Focus 4: nunca en paralelo con un hilo vivo ni sobre una misión en curso.
    @Test void verifyIsRejectedForALiveOrRunningMission() {
        verifiable(MissionStatus.AWAITING_INVESTOR);
        when(executor.isLive("M-1")).thenReturn(true);
        assertTrue(assertThrows(IllegalStateException.class, () -> service.verify("M-1")).getMessage()
                .contains("se está ejecutando"));

        when(executor.isLive("M-1")).thenReturn(false);
        when(missions.find("M-1")).thenReturn(Optional.of(mission("M-1", MissionStatus.PLANNING, "TEAM-DEVELOPMENT")));
        assertTrue(assertThrows(IllegalStateException.class, () -> service.verify("M-1")).getMessage().contains("PLANNING"));

        verify(executor, never()).verifyAsync(anyString());
        verify(recovery, never()).resetCounter(anyString(), anyInt());
    }

    @Test void verifyNeedsADevelopmentMissionWithRepository() {
        var workspace = verifiable(MissionStatus.FAILED);
        when(workspace.headIfAny("M-1")).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class, () -> service.verify("M-1"));

        when(missions.find("M-1")).thenReturn(Optional.of(mission("M-1", MissionStatus.FAILED, "TEAM-MARKETING-GROWTH")));
        assertThrows(IllegalStateException.class, () -> service.verify("M-1"));

        when(missions.find("M-0")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.verify("M-0"));
        verify(executor, never()).verifyAsync(anyString());
    }

    // Fix 5: el contador lo pone en cero verifyAsync, tras pasar a EVALUATING (no acá).
    @Test void verifyRunsInEveryStoppedState() {
        for (var status : List.of(MissionStatus.BLOCKED, MissionStatus.FAILED, MissionStatus.AWAITING_INVESTOR,
                MissionStatus.INTERRUPTED)) {
            verifiable(status);
            service.verify("M-1");
        }
        verify(executor, times(4)).verifyAsync("M-1");
        verify(recovery, never()).resetCounter(anyString(), anyInt());
    }

    // Fix 2: una huérfana cuya última tarea es una verificación humana sin resultado se retoma como verificación.
    @Test void anOrphanHumanVerificationIsResumedAsAHumanVerification() {
        when(missions.findAll(anyInt())).thenReturn(List.of(mission("M-1", MissionStatus.EVALUATING, "TEAM-DEVELOPMENT")));
        approved("M-1");
        when(missions.evidenceRound("M-1")).thenReturn(2);
        var now = Instant.now();
        when(missions.tasks("M-1")).thenReturn(List.of(
                new com.aicompany.core.model.AgentTask("M-1-BACKEND-R2", "M-1", "backend", "X", "COMPLETED", "{}",
                        now.minusSeconds(60), "WORK", "/w", "a".repeat(40), List.of(), null, null),
                new com.aicompany.core.model.AgentTask("M-1-HUMAN-VERIFY-1", "M-1", "qa", "HUMAN_VERIFY", "RUNNING", "x",
                        now, "VALIDATION", null, null, List.of(), null, null)));
        service.onStartup();
        verify(executor).resumeAsync(eq("M-1"), argThat(p -> p.stage() == ResumePoint.Stage.HUMAN_VERIFY));
    }

    // Revisión final (1): una verificación humana FAILED (verifyHead lanzó) seguida de una corrección cortada no es una
    // verificación pendiente: la misión se retoma como corrección.
    @Test void aFailedHumanVerificationIsNotPendingAndTheCutCorrectionResumes() {
        var now = Instant.now();
        when(missions.evidenceRound("M-1")).thenReturn(3);
        when(recovery.correctionRound("M-1", 3)).thenReturn(Optional.of(new CorrectionRound(3, 1, "x", 1, false)));
        when(missions.tasks("M-1")).thenReturn(List.of(new com.aicompany.core.model.AgentTask("M-1-HUMAN-VERIFY-1", "M-1",
                "qa", "HUMAN_VERIFY", "FAILED", "x", now, "VALIDATION", null, null, List.of(), null, null)));
        var snapshot = service.snapshot(mission("M-1", MissionStatus.EVALUATING, "TEAM-DEVELOPMENT"));
        assertNull(snapshot.humanVerifyTask());
        assertTrue(snapshot.correctionRound());
    }

    // Spec 2026-10-14 Kael §1.2: un spec GAME en DRAFT cuyo diseño es de una versión anterior retoma en Kael.
    @Test void aGameDraftWithAnOutdatedDesignNeedsKael() throws Exception {
        var designs = mock(InteractionDesignMemoryService.class);
        service.setInteractionDesigns(designs);
        var spec = new com.aicompany.core.agent.model.RequirementsSpec("Juego", List.of(), List.of(), List.of(), List.of(),
                List.of(), "GAME");
        when(missions.tasks("M-1")).thenReturn(List.of());
        when(requirements.latest("M-1")).thenReturn(Optional.of(new RequirementsMemoryService.Version(2,
                tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(spec), "DRAFT", "", null)));
        when(designs.latest("M-1")).thenReturn(Optional.of(new InteractionDesignMemoryService.Version(1, "{}", "DRAFT", 1, "")));
        var snapshot = service.snapshot(mission("M-1", MissionStatus.PLANNING, "TEAM-DEVELOPMENT"));
        assertTrue(snapshot.designNeeded());
        assertFalse(snapshot.designForLatestSpec());
        assertEquals(ResumePoint.Stage.DESIGN, ResumePoint.of(snapshot).stage());
        when(designs.latest("M-1")).thenReturn(Optional.of(new InteractionDesignMemoryService.Version(2, "{}", "DRAFT", 2, "")));
        when(designs.pendingAdjustment("M-1")).thenReturn(Optional.of("paleta"));
        assertEquals(ResumePoint.Stage.DESIGN_ADJUSTMENT,
                ResumePoint.of(service.snapshot(mission("M-1", MissionStatus.PLANNING, "TEAM-DEVELOPMENT"))).stage());
    }

    // Revisión conjunta (spec 2026-10-15): el par DRAFT sin revisión cerrada (con la policy encendida) se retoma en la revisión.
    @Test void aDraftPairWithoutAClosedReviewIsPendingOnlyWithThePolicyOn() throws Exception {
        var designs = mock(InteractionDesignMemoryService.class);
        var review = mock(JointReviewService.class);
        var reviews = mock(JointReviewMemoryService.class);
        service.setInteractionDesigns(designs);
        service.setJointReview(review, reviews);
        var spec = new com.aicompany.core.agent.model.RequirementsSpec("Juego", List.of(), List.of(), List.of(), List.of(),
                List.of(), "GAME");
        when(missions.tasks("M-1")).thenReturn(List.of());
        when(requirements.latest("M-1")).thenReturn(Optional.of(new RequirementsMemoryService.Version(2,
                tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(spec), "DRAFT", "", null)));
        when(designs.latest("M-1")).thenReturn(Optional.of(new InteractionDesignMemoryService.Version(3, "{}", "DRAFT", 2, "")));
        when(review.enabled()).thenReturn(true);
        when(reviews.closedFor("M-1", 2, 3)).thenReturn(Optional.empty());
        var snapshot = service.snapshot(mission("M-1", MissionStatus.PLANNING, "TEAM-DEVELOPMENT"));
        assertTrue(snapshot.jointReviewPending());
        assertEquals(ResumePoint.Stage.JOINT_REVIEW, ResumePoint.of(snapshot).stage());
        when(reviews.closedFor("M-1", 2, 3)).thenReturn(Optional.of(new JointReviewMemoryService.Review(1, 1, 2, 3,
                "COHERENT", "[]", "[]", "", "")));
        assertFalse(service.snapshot(mission("M-1", MissionStatus.PLANNING, "TEAM-DEVELOPMENT")).jointReviewPending());
        when(reviews.closedFor("M-1", 2, 3)).thenReturn(Optional.empty());
        when(review.enabled()).thenReturn(false);
        assertEquals(ResumePoint.Stage.AWAIT_APPROVAL,
                ResumePoint.of(service.snapshot(mission("M-1", MissionStatus.PLANNING, "TEAM-DEVELOPMENT"))).stage());
    }

    @Test void aFinishedHumanVerificationIsNotPending() {
        var now = Instant.now();
        when(missions.evidenceRound("M-1")).thenReturn(0);
        when(missions.tasks("M-1")).thenReturn(List.of(new com.aicompany.core.model.AgentTask("M-1-HUMAN-VERIFY-1", "M-1",
                "qa", "HUMAN_VERIFY", "COMPLETED", "x", now, "VALIDATION", null, null, List.of(), "FAILED", "[]")));
        assertNull(service.snapshot(mission("M-1", MissionStatus.EVALUATING, "TEAM-DEVELOPMENT")).humanVerifyTask());
    }

    // Revisión 2026-10-06 §5: tras una verificación humana, las 20 rondas del incremento vuelven a estar disponibles.
    @Test void theCounterRestartsAfterAHumanVerification() {
        when(recovery.correctionRounds("M-1")).thenReturn(List.of(new CorrectionRound(1, 1, "a", 5, false),
                new CorrectionRound(2, 1, "b", 4, false), new CorrectionRound(3, 1, "c", 3, false)));
        assertTrue(service.brake("M-1", 1, 2).isPresent());

        when(recovery.counterResetAfter("M-1", 1)).thenReturn(3);
        assertTrue(service.brake("M-1", 1, 2).isEmpty());
        assertFalse(service.needsStrategy("M-1", 1, 3));
    }

    @Test void theCorrectionCounterBlocks() {
        when(recovery.correctionRounds("M-1")).thenReturn(List.of(new CorrectionRound(1, 1, "a", 5, false),
                new CorrectionRound(2, 1, "b", 4, false), new CorrectionRound(3, 1, "c", 3, false)));
        assertTrue(service.brake("M-1", 1, 2).orElseThrow().contains("3"));
    }

    @Test void theCostBlocks() {
        when(cost.missionCost("M-1")).thenReturn(new ModelCostService.Summary(List.of(
                new ModelUsageMemoryService.Row("bedrock", "kimi", 10, 1000, 0, 500, 5.10))));
        assertTrue(service.brake("M-1", 1, 2).orElseThrow().contains("US$"));
    }

    // Revisión 2026-10-06 §2: sin avance ya no bloquea; pide una estrategia nueva.
    @Test void noProgressAsksForAStrategyInsteadOfBlocking() {
        when(recovery.correctionRounds("M-1")).thenReturn(List.of(new CorrectionRound(1, 1, "a", 4, false),
                new CorrectionRound(2, 1, "b", 4, false)));
        assertTrue(service.brake("M-1", 1, 4).isEmpty());
        assertTrue(service.needsStrategy("M-1", 1, 4));
        assertFalse(service.needsStrategy("M-1", 1, 3));
    }

    /** El almacén real: addCorrectionRound agrega la ronda a lo que devuelve correctionRounds. */
    private void realStore(List<CorrectionRound> prior) {
        var store = new java.util.ArrayList<>(prior);
        when(recovery.correctionRounds("M-1")).thenAnswer(inv -> List.copyOf(store));
        doAnswer(inv -> store.add(inv.getArgument(1))).when(recovery).addCorrectionRound(eq("M-1"), any());
        listed(mission("M-1", MissionStatus.FAILED, "TEAM-DEVELOPMENT"));
        when(requirements.approved("M-1")).thenReturn(Optional.of(new RequirementsMemoryService.Version(1, "{}", "APPROVED", "", null)));
        when(requirements.currentIncrement("M-1")).thenReturn(1);
        when(missions.evidenceRound("M-1")).thenReturn(prior.size());
    }

    // Revisión 2026-10-06 §2: la decisión se toma antes de registrar la ronda que se lanza.
    @Test void noProgressLaunchesTheCorrectionWithStrategy() {
        realStore(List.of(new CorrectionRound(1, 1, "a", 1, false), new CorrectionRound(2, 1, "b", 1, false)));
        failedValidation();
        service.recoverAll();
        verify(executor).correctAsync(eq("M-1"), eq(3), anyString(), eq(true));
    }

    // Revisión final (5): una falla del mensaje de la misión (sin validación) no busca estrategia en internet.
    @Test void aFailureWithoutValidationNeverSearchesAStrategy() {
        realStore(List.of(new CorrectionRound(1, 1, "a", 1, false), new CorrectionRound(2, 1, "b", 1, false)));
        service.recoverAll();
        verify(executor).correctAsync(eq("M-1"), eq(3), anyString(), eq(false));
    }

    private void failedValidation() {
        when(missions.tasks("M-1")).thenReturn(List.of(new com.aicompany.core.model.AgentTask("M-1-QA-REVIEW", "M-1",
                "qa", "X", "COMPLETED", "x", Instant.now(), "VALIDATION", null, null, List.of(), "FAILED", "[]")));
    }

    @Test void aSinglePriorRoundNeverLaunchesWithStrategy() {
        realStore(List.of(new CorrectionRound(1, 1, "a", 1, false)));
        service.recoverAll();
        verify(executor).correctAsync(eq("M-1"), eq(2), anyString(), eq(false));
    }

    @Test void progressLaunchesWithoutStrategy() {
        realStore(List.of(new CorrectionRound(1, 1, "a", 5, false), new CorrectionRound(2, 1, "b", 4, false)));
        service.recoverAll();
        verify(executor).correctAsync(eq("M-1"), eq(3), anyString(), eq(false));
    }

    @Test void fewerThanTwoAutomaticRoundsNeverAskForAStrategy() {
        when(recovery.correctionRounds("M-1")).thenReturn(List.of(new CorrectionRound(1, 1, "a", 4, false)));
        assertFalse(service.needsStrategy("M-1", 1, 9));
    }

    @Test void aFounderRoundResetsTheStrategyCount() {
        when(recovery.correctionRounds("M-1")).thenReturn(List.of(new CorrectionRound(1, 1, "a", 4, false),
                new CorrectionRound(2, 1, "b", 4, false), new CorrectionRound(3, 1, "fundador", 4, true)));
        assertFalse(service.needsStrategy("M-1", 1, 4));
    }

    @Test void aFounderRoundResetsTheCounter() {
        when(recovery.correctionRounds("M-1")).thenReturn(List.of(new CorrectionRound(1, 1, "a", 5, false),
                new CorrectionRound(2, 1, "b", 4, false), new CorrectionRound(3, 1, "c", 3, false),
                new CorrectionRound(4, 1, "fundador", 3, true)));
        assertTrue(service.brake("M-1", 1, 2).isEmpty());
    }

    @Test void aBrakeLeavesTheMissionBlocked() {
        listed(mission("M-1", MissionStatus.FAILED, "TEAM-DEVELOPMENT"));
        when(requirements.approved("M-1")).thenReturn(Optional.of(new RequirementsMemoryService.Version(1, "{}", "APPROVED", "", null)));
        when(requirements.currentIncrement("M-1")).thenReturn(1);
        when(recovery.correctionRounds("M-1")).thenReturn(List.of(new CorrectionRound(1, 1, "a", 5, false),
                new CorrectionRound(2, 1, "b", 4, false), new CorrectionRound(3, 1, "c", 3, false)));
        service.recoverAll();
        verify(executor).block(eq("M-1"), contains("correcciones"));
        verify(executor, never()).correctAsync(anyString(), anyInt(), anyString(), anyBoolean());
    }

    @Test void aLostRoundClaimDoesNotStartACorrection() {
        listed(mission("M-1", MissionStatus.FAILED, "TEAM-DEVELOPMENT"));
        when(requirements.approved("M-1")).thenReturn(Optional.of(new RequirementsMemoryService.Version(1, "{}", "APPROVED", "", null)));
        when(requirements.currentIncrement("M-1")).thenReturn(1);
        when(missions.claimNextRound("M-1", 0, MissionRecoveryService.CORRECTABLE)).thenReturn(false);
        service.recoverAll();
        verify(executor, never()).correctAsync(anyString(), anyInt(), anyString(), anyBoolean());
    }

    // Revisión final (1b): entre el listado y el CAS el fundador lanzó otra ronda; la recuperación no lanza ni bloquea.
    @Test void aMissionRelaunchedSinceTheListingIsNeitherCorrectedNorBlocked() {
        listed(mission("M-1", MissionStatus.FAILED, "TEAM-DEVELOPMENT"));
        when(missions.find("M-1")).thenReturn(Optional.of(mission("M-1", MissionStatus.DELEGATING, "TEAM-DEVELOPMENT")));
        approved("M-1");
        when(recovery.correctionRounds("M-1")).thenReturn(List.of(new CorrectionRound(1, 1, "a", 5, false),
                new CorrectionRound(2, 1, "b", 4, false), new CorrectionRound(3, 1, "c", 3, false)));
        service.recoverAll();
        verify(missions, never()).claimNextRound(anyString(), anyInt(), anySet());
        verify(executor, never()).block(anyString(), anyString());
        verify(executor, never()).correctAsync(anyString(), anyInt(), anyString(), anyBoolean());
    }

    @Test void aMissionThatBecameLiveIsNeverBlocked() {
        listed(mission("M-1", MissionStatus.FAILED, "TEAM-DEVELOPMENT"));
        approved("M-1");
        when(executor.isLive("M-1")).thenReturn(false, true);
        when(recovery.correctionRounds("M-1")).thenReturn(List.of(new CorrectionRound(1, 1, "a", 5, false),
                new CorrectionRound(2, 1, "b", 4, false), new CorrectionRound(3, 1, "c", 3, false)));
        service.recoverAll();
        verify(executor, never()).block(anyString(), anyString());
    }

    // Revisión final (2): "reanuda" respeta los agentes encendidos en todos los equipos.
    @Test void resumingWithAgentsOffIsRejected() {
        var missionService = mock(MissionService.class);
        service.setMissionService(missionService);
        doThrow(new IllegalStateException("está apagado Sofía")).when(missionService).requireAgentsOn(isNull(), anyString());
        when(missions.find("M-D")).thenReturn(Optional.of(mission("M-D", MissionStatus.INTERRUPTED, null)));
        assertThrows(IllegalStateException.class, () -> service.resume("M-D"));
        verify(executor, never()).executeAsync(anyString(), anyString());
    }

    @Test void automaticResumeAndCorrectionSkipMissionsWithAgentsOff() {
        var missionService = mock(MissionService.class);
        service.setMissionService(missionService);
        doThrow(new IllegalStateException("está apagada Aria")).when(missionService)
                .requireAgentsOn(eq("TEAM-DEVELOPMENT"), anyString());
        listed(mission("M-I", MissionStatus.INTERRUPTED, "TEAM-DEVELOPMENT"),
                mission("M-F", MissionStatus.FAILED, "TEAM-DEVELOPMENT"));
        approved("M-F");
        service.recoverAll();
        verify(executor, never()).resumeAsync(anyString(), any());
        verify(missions, never()).claimNextRound(anyString(), anyInt(), anySet());
        verify(executor, never()).correctAsync(anyString(), anyInt(), anyString(), anyBoolean());
        verify(executor, never()).block(anyString(), anyString());
    }

    // Revisión final (3): el orquestador pregunta si la corrección automática se ocupa de la misión.
    @Test void willCorrectAFailedMissionWithApprovedRequirementsAndNoBrake() {
        listed(mission("M-1", MissionStatus.FAILED, "TEAM-DEVELOPMENT"));
        approved("M-1");
        when(executor.isLive("M-1")).thenReturn(true); // el orquestador se entera mientras el hilo termina
        assertTrue(service.willCorrect("M-1"));
    }

    @Test void willNotCorrectWhenABrakeFiresOrWithoutRequirements() {
        listed(mission("M-1", MissionStatus.FAILED, "TEAM-DEVELOPMENT"), mission("M-2", MissionStatus.FAILED, "TEAM-DEVELOPMENT"));
        approved("M-1");
        when(recovery.correctionRounds("M-1")).thenReturn(List.of(new CorrectionRound(1, 1, "a", 5, false),
                new CorrectionRound(2, 1, "b", 4, false), new CorrectionRound(3, 1, "c", 3, false)));
        when(requirements.approved("M-2")).thenReturn(Optional.empty());
        assertFalse(service.willCorrect("M-1"));
        assertFalse(service.willCorrect("M-2"));
        assertFalse(service.willCorrect("M-X"));
    }

    // Revisión final (6a): si la detección de huérfanas falló al arrancar, el pase siguiente la reintenta.
    @Test void aFailedOrphanCollectionIsRetriedByTheNextPass() {
        when(missions.findAll(anyInt())).thenThrow(new RuntimeException("neo4j caído")).thenThrow(new RuntimeException("neo4j caído"))
                .thenReturn(List.of(mission("M-1", MissionStatus.PLANNING, "TEAM-DEVELOPMENT")));
        service.onStartup();
        verify(executor, never()).resumeAsync(anyString(), any());
        service.recoverAll();
        verify(executor).resumeAsync(eq("M-1"), any());
    }

    // Revisión final (6b): una huérfana que no se puede retomar queda INTERRUPTED al tercer intento (con su evento).
    @Test void anOrphanThatCannotBeResumedIsInterruptedAfterThreeAttempts() {
        when(missions.findAll(anyInt())).thenReturn(List.of(mission("M-1", MissionStatus.PLANNING, "TEAM-DEVELOPMENT")));
        when(executor.resumeAsync(eq("M-1"), any())).thenThrow(new IllegalStateException("plan ilegible"));
        service.onStartup();
        service.recoverAll();
        verify(executor, never()).interrupt(anyString());
        service.recoverAll();
        verify(executor).interrupt("M-1");
        service.recoverAll();
        verify(executor, times(3)).resumeAsync(eq("M-1"), any());
    }
}
