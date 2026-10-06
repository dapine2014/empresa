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
        when(missions.claimNextRound(anyString(), anyInt())).thenReturn(true);
    }

    private static MissionResponse mission(String id, MissionStatus status, String team) {
        return new MissionResponse(id, status, "TEST", 0, "", "", Instant.now(), null, team);
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

    @Test void aFailedValidationWithApprovedRequirementsStartsACorrection() {
        when(missions.findAll(anyInt())).thenReturn(List.of(mission("M-1", MissionStatus.AWAITING_INVESTOR, "TEAM-DEVELOPMENT")));
        when(missions.lastValidationStatus("M-1")).thenReturn(Optional.of("FAILED"));
        when(requirements.approved("M-1")).thenReturn(Optional.of(new RequirementsMemoryService.Version(1, "{}", "APPROVED", "", null)));
        when(requirements.currentIncrement("M-1")).thenReturn(1);
        when(missions.evidenceRound("M-1")).thenReturn(0);
        service.recoverAll();
        verify(recovery).addCorrectionRound(eq("M-1"), argThat(r -> r.round() == 1 && r.increment() == 1 && !r.byFounder()));
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

    // Review Focus 3: dos rondas seguidas sin bajar lo que falla -> BLOCKED (falla de infraestructura repetida).
    @Test void noProgressInTwoRoundsBlocks() {
        when(recovery.correctionRounds("M-1")).thenReturn(List.of(new CorrectionRound(1, 1, "a", 4, false),
                new CorrectionRound(2, 1, "b", 4, false)));
        assertTrue(service.brake("M-1", 1, 4).orElseThrow().contains("sin avance"));
        assertTrue(service.brake("M-1", 1, 3).isEmpty());
    }

    @Test void aFounderRoundResetsTheCounter() {
        when(recovery.correctionRounds("M-1")).thenReturn(List.of(new CorrectionRound(1, 1, "a", 5, false),
                new CorrectionRound(2, 1, "b", 4, false), new CorrectionRound(3, 1, "c", 3, false),
                new CorrectionRound(4, 1, "fundador", 3, true)));
        assertTrue(service.brake("M-1", 1, 2).isEmpty());
    }

    @Test void aBrakeLeavesTheMissionBlocked() {
        when(missions.findAll(anyInt())).thenReturn(List.of(mission("M-1", MissionStatus.FAILED, "TEAM-DEVELOPMENT")));
        when(requirements.approved("M-1")).thenReturn(Optional.of(new RequirementsMemoryService.Version(1, "{}", "APPROVED", "", null)));
        when(requirements.currentIncrement("M-1")).thenReturn(1);
        when(recovery.correctionRounds("M-1")).thenReturn(List.of(new CorrectionRound(1, 1, "a", 5, false),
                new CorrectionRound(2, 1, "b", 4, false), new CorrectionRound(3, 1, "c", 3, false)));
        service.recoverAll();
        verify(executor).block(eq("M-1"), contains("correcciones"));
        verify(executor, never()).correctAsync(anyString(), anyInt(), anyString());
    }

    @Test void aLostRoundClaimDoesNotStartACorrection() {
        when(missions.findAll(anyInt())).thenReturn(List.of(mission("M-1", MissionStatus.FAILED, "TEAM-DEVELOPMENT")));
        when(requirements.approved("M-1")).thenReturn(Optional.of(new RequirementsMemoryService.Version(1, "{}", "APPROVED", "", null)));
        when(requirements.currentIncrement("M-1")).thenReturn(1);
        when(missions.claimNextRound("M-1", 0)).thenReturn(false);
        service.recoverAll();
        verify(executor, never()).correctAsync(anyString(), anyInt(), anyString());
    }
}
