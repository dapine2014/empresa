package com.aicompany.core.service;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.DecisionCommand;
import com.aicompany.core.model.InvestorDecision;
import com.aicompany.core.model.MissionResponse;
import com.aicompany.core.model.MissionStatus;
import com.aicompany.core.model.PolicyKey;
import com.aicompany.core.model.RequirementsDecisionCommand;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Spec 2026-10-03 Aria §2-§3: decisiones sobre los requisitos e incrementos. */
class MissionServiceRequirementsTest {

    private final MissionMemoryService memory = mock(MissionMemoryService.class);
    private final MissionExecutor executor = mock(MissionExecutor.class);
    private final CompanyPolicyService policies = mock(CompanyPolicyService.class);
    private final RequirementsMemoryService requirements = mock(RequirementsMemoryService.class);
    private final TeamMemoryService teamMemory = mock(TeamMemoryService.class);
    private final MissionService service = new MissionService(memory, executor, mock(CompanyEventPublisher.class),
            teamMemory, mock(DevelopmentWorkspaceService.class), policies);

    {
        service.setRequirements(requirements);
        when(executor.reexecuteAsync(anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(memory.instructionOf("M-9")).thenReturn(Optional.of("Un CRM"));
    }

    private static MissionResponse missionIn(MissionStatus status) {
        return new MissionResponse("M-9", status, "TEST", 0, "", "", Instant.now(), null, "TEAM-DEVELOPMENT");
    }

    // Review Focus 2: aprobar dos veces no lanza otra ejecución.
    @Test
    void requirementsCanOnlyBeDecidedWhileWaitingForApproval() {
        when(memory.find("M-9")).thenReturn(Optional.of(missionIn(MissionStatus.PLANNING)));
        assertThrows(IllegalStateException.class, () -> service.decideRequirements("M-9",
                new RequirementsDecisionCommand(RequirementsDecisionCommand.RequirementsDecision.APPROVE, null)));
        verifyNoInteractions(executor);
    }

    @Test
    void approvingMarksTheVersionAndContinues() {
        when(memory.find("M-9")).thenReturn(Optional.of(missionIn(MissionStatus.AWAITING_SPEC_APPROVAL)));
        when(requirements.latest("M-9")).thenReturn(Optional.of(new RequirementsMemoryService.Version(2, "{}", "DRAFT", "", null)));

        service.decideRequirements("M-9", new RequirementsDecisionCommand(
                RequirementsDecisionCommand.RequirementsDecision.APPROVE, null));

        verify(requirements).approve("M-9", 2, "founder");
        verify(executor).continueAfterApprovalAsync("M-9");
    }

    @Test
    void aSecondApprovalBeforeTheThreadMovesOnIsRejected() {
        when(memory.find("M-9")).thenReturn(Optional.of(missionIn(MissionStatus.AWAITING_SPEC_APPROVAL)));
        when(requirements.latest("M-9")).thenReturn(Optional.of(new RequirementsMemoryService.Version(2, "{}", "APPROVED", "", null)));
        assertThrows(IllegalStateException.class, () -> service.decideRequirements("M-9",
                new RequirementsDecisionCommand(RequirementsDecisionCommand.RequirementsDecision.APPROVE, null)));
        verifyNoInteractions(executor);
    }

    @Test
    void adjustingNeedsAnInstructionAndRejectingCancels() {
        when(memory.find("M-9")).thenReturn(Optional.of(missionIn(MissionStatus.AWAITING_SPEC_APPROVAL)));
        when(requirements.latest("M-9")).thenReturn(Optional.of(new RequirementsMemoryService.Version(1, "{}", "DRAFT", "", null)));
        assertThrows(IllegalArgumentException.class, () -> service.decideRequirements("M-9",
                new RequirementsDecisionCommand(RequirementsDecisionCommand.RequirementsDecision.ADJUST, " ")));

        service.decideRequirements("M-9", new RequirementsDecisionCommand(
                RequirementsDecisionCommand.RequirementsDecision.REJECT, null));
        verify(requirements).reject("M-9", 1);
        verify(memory).updateMission(eq("M-9"), eq(MissionStatus.CANCELLED), anyInt(), anyString(), anyString());
    }

    // Review Focus 5.
    @Test
    void anIncrementNeedsAnApprovedSpecThePreviousOneBuiltAndAStoppedMission() {
        when(memory.find("M-9")).thenReturn(Optional.of(missionIn(MissionStatus.AWAITING_INVESTOR)));
        when(requirements.approved("M-9")).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class, () -> service.buildIncrement("M-9", 2));

        when(requirements.approved("M-9")).thenReturn(Optional.of(new RequirementsMemoryService.Version(1,
                "{\"increments\":[{\"number\":1},{\"number\":2},{\"number\":3}]}", "APPROVED", "", null)));
        when(requirements.builtIncrement("M-9")).thenReturn(1);
        assertThrows(IllegalStateException.class, () -> service.buildIncrement("M-9", 3));
        assertThrows(IllegalStateException.class, () -> service.buildIncrement("M-9", 9));

        service.buildIncrement("M-9", 2);
        verify(executor).buildIncrementAsync("M-9", 2);
    }

    // Spec 2026-10-03 Aria §3: los incrementos no consumen las rondas de evidencia.
    @Test
    void incrementRoundsDoNotCountAgainstTheEvidenceLimit() {
        when(memory.find("M-9")).thenReturn(Optional.of(missionIn(MissionStatus.AWAITING_INVESTOR)));
        when(memory.evidenceRound("M-9")).thenReturn(2);
        when(requirements.incrementRounds("M-9")).thenReturn(2);
        when(policies.activeValue(PolicyKey.MAX_EVIDENCE_ROUNDS)).thenReturn(1.0);

        assertDoesNotThrow(() -> service.recordDecision("M-9",
                new DecisionCommand(InvestorDecision.REQUEST_MORE_EVIDENCE, "más")));
    }

    // Revisión final (1): una ronda de evidencia después de un incremento usa un número de ronda nuevo (no pisa sus tareas).
    @Test
    void anEvidenceRoundAfterAnIncrementGetsANewRoundNumber() {
        when(memory.find("M-9")).thenReturn(Optional.of(missionIn(MissionStatus.AWAITING_INVESTOR)));
        when(memory.evidenceRound("M-9")).thenReturn(1);
        when(requirements.incrementRounds("M-9")).thenReturn(1);
        when(policies.activeValue(PolicyKey.MAX_EVIDENCE_ROUNDS)).thenReturn(2.0);

        service.recordDecision("M-9", new DecisionCommand(InvestorDecision.REQUEST_MORE_EVIDENCE, "más"));

        verify(memory).setEvidenceRound("M-9", 2);
        verify(executor).reexecuteAsync("M-9", "Un CRM", 2, "más");
    }

    // Revisión final (8): aprobar o ajustar exige a Aria, Alex y Neo encendidos (nunca se llama a un agente apagado).
    @Test
    void approvingOrAdjustingNeedsTheAgentsTurnedOn() {
        when(memory.find("M-9")).thenReturn(Optional.of(missionIn(MissionStatus.AWAITING_SPEC_APPROVAL)));
        when(requirements.latest("M-9")).thenReturn(Optional.of(new RequirementsMemoryService.Version(1, "{}", "DRAFT", "", null)));
        when(teamMemory.snapshot("TEAM-DEVELOPMENT")).thenReturn(new com.aicompany.core.model.TeamSnapshot(
                "TEAM-DEVELOPMENT", "Development Group", "ACTIVE", "engineering", java.util.List.of()));
        var availability = mock(AgentAvailability.class);
        doThrow(new IllegalStateException("Aria (product-owner) está apagada")).when(availability).requireEnabled(anyList(), anyString());
        service.setAgentAvailability(availability);

        assertThrows(IllegalStateException.class, () -> service.decideRequirements("M-9",
                new RequirementsDecisionCommand(RequirementsDecisionCommand.RequirementsDecision.APPROVE, null)));
        assertThrows(IllegalStateException.class, () -> service.decideRequirements("M-9",
                new RequirementsDecisionCommand(RequirementsDecisionCommand.RequirementsDecision.ADJUST, "x")));
        verify(requirements, never()).approve(anyString(), anyInt(), anyString());
        verifyNoInteractions(executor);
    }
}
