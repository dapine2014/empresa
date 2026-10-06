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
        when(executor.correctAsync(anyString(), anyInt(), anyString())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
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
        verify(executor).correctAsync(eq("M-1"), eq(1), startsWith(CorrectionReport.HEADER));
    }

    @Test void unvalidatedCancelledOrWithoutRequirementsAreLeftAlone() {
        when(missions.findAll(anyInt())).thenReturn(List.of(
                mission("M-U", MissionStatus.AWAITING_INVESTOR, "TEAM-DEVELOPMENT"),
                mission("M-C", MissionStatus.CANCELLED, "TEAM-DEVELOPMENT"),
                mission("M-N", MissionStatus.FAILED, "TEAM-DEVELOPMENT")));
        when(missions.lastValidationStatus("M-U")).thenReturn(Optional.of("UNVALIDATED"));
        when(requirements.approved(anyString())).thenReturn(Optional.empty());
        service.recoverAll();
        verify(executor, never()).correctAsync(anyString(), anyInt(), anyString());
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
        verify(executor, never()).correctAsync(anyString(), anyInt(), anyString());
    }

    @Test void aLostRoundClaimDoesNotStartACorrection() {
        listed(mission("M-1", MissionStatus.FAILED, "TEAM-DEVELOPMENT"));
        when(requirements.approved("M-1")).thenReturn(Optional.of(new RequirementsMemoryService.Version(1, "{}", "APPROVED", "", null)));
        when(requirements.currentIncrement("M-1")).thenReturn(1);
        when(missions.claimNextRound("M-1", 0, MissionRecoveryService.CORRECTABLE)).thenReturn(false);
        service.recoverAll();
        verify(executor, never()).correctAsync(anyString(), anyInt(), anyString());
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
        verify(executor, never()).correctAsync(anyString(), anyInt(), anyString());
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
        verify(executor, never()).correctAsync(anyString(), anyInt(), anyString());
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
