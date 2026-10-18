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
    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    private final MissionService service = new MissionService(memory, executor, events,
            teamMemory, mock(DevelopmentWorkspaceService.class), policies);

    private final InteractionDesignMemoryService designs = mock(InteractionDesignMemoryService.class);

    {
        service.setInteractionDesign(designs);
        service.setRequirements(requirements);
        when(executor.reexecuteAsync(anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(memory.instructionOf("M-9")).thenReturn(Optional.of("Un CRM"));
        when(requirements.claimDecision(anyString(), anyInt(), any())).thenReturn(true);
        when(requirements.claimDecisionForKael(anyString(), anyInt(), anyInt(), anyString())).thenReturn(true);
        when(memory.claimNextRound(anyString(), anyInt(), anySet())).thenReturn(true);
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
        // Revisión final Aria (14): el rechazo avisa el cambio de estado como cualquier cierre de misión.
        verify(events).publishMission(eq("EMPRESA_MISSION_UPDATED"), eq("M-9"), eq("CANCELLED"), eq(100), anyString(), anyString());
    }

    // Revisión final Aria (12): dos decisiones simultáneas (doble clic) sobre la misma versión: solo una pasa.
    @Test
    void aConcurrentDecisionThatLosesTheClaimDoesNothing() {
        when(memory.find("M-9")).thenReturn(Optional.of(missionIn(MissionStatus.AWAITING_SPEC_APPROVAL)));
        when(requirements.latest("M-9")).thenReturn(Optional.of(new RequirementsMemoryService.Version(2, "{}", "DRAFT", "", null)));
        when(requirements.claimDecision(eq("M-9"), eq(2), any())).thenReturn(false);

        for (var decision : RequirementsDecisionCommand.RequirementsDecision.values()) {
            assertThrows(IllegalStateException.class, () -> service.decideRequirements("M-9",
                    new RequirementsDecisionCommand(decision, "ajuste")));
        }
        verify(requirements, never()).approve(anyString(), anyInt(), anyString());
        verify(requirements, never()).reject(anyString(), anyInt());
        verifyNoInteractions(executor);
    }

    // Revisión final Aria (12): dos pedidos simultáneos del mismo incremento lanzan una sola ronda.
    @Test
    void aConcurrentIncrementThatLosesTheRoundDoesNothing() {
        when(memory.find("M-9")).thenReturn(Optional.of(missionIn(MissionStatus.AWAITING_INVESTOR)));
        when(requirements.approved("M-9")).thenReturn(Optional.of(new RequirementsMemoryService.Version(1,
                "{\"increments\":[{\"number\":1},{\"number\":2}]}", "APPROVED", "", null)));
        when(requirements.builtIncrement("M-9")).thenReturn(1);
        when(memory.evidenceRound("M-9")).thenReturn(1);
        when(memory.claimNextRound(eq("M-9"), eq(1), anySet())).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> service.buildIncrement("M-9", 2));
        verifyNoInteractions(executor);
    }

    // Revisión 2026-10-06 §4: se construye el incremento siguiente sobre una BLOCKED.
    @Test
    void anIncrementCanBeBuiltOnABlockedMission() {
        when(memory.find("M-9")).thenReturn(Optional.of(missionIn(MissionStatus.BLOCKED)));
        when(requirements.approved("M-9")).thenReturn(Optional.of(new RequirementsMemoryService.Version(1,
                "{\"increments\":[{\"number\":1},{\"number\":2}]}", "APPROVED", "", null)));
        when(requirements.builtIncrement("M-9")).thenReturn(1);
        when(memory.evidenceRound("M-9")).thenReturn(3);
        when(memory.claimNextRound(eq("M-9"), eq(3), anySet())).thenReturn(true);
        assertDoesNotThrow(() -> service.buildIncrement("M-9", 2));
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

        when(memory.evidenceRound("M-9")).thenReturn(3);
        service.buildIncrement("M-9", 2);
        verify(memory).claimNextRound(eq("M-9"), eq(3), argThat(s -> s.equals(java.util.Set.of(MissionStatus.AWAITING_INVESTOR, MissionStatus.COMPLETED, MissionStatus.FAILED, MissionStatus.BLOCKED))));
        verify(executor).buildIncrementAsync("M-9", 2, 4);
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

        verify(memory).claimNextRound(eq("M-9"), eq(1), argThat(s -> s.contains(MissionStatus.BLOCKED) && s.contains(MissionStatus.FAILED) && s.contains(MissionStatus.AWAITING_INVESTOR)));
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

    // Revisión final Aria (16): pedir más evidencia sobre una misión anterior a Aria no exige a Aria encendida.
    @Test
    void anEvidenceRoundOfAMissionThatPredatesAriaDoesNotNeedAria() {
        when(memory.find("M-9")).thenReturn(Optional.of(missionIn(MissionStatus.AWAITING_INVESTOR)));
        when(memory.teamId("M-9")).thenReturn(Optional.of("TEAM-DEVELOPMENT"));
        when(policies.activeValue(PolicyKey.MAX_EVIDENCE_ROUNDS)).thenReturn(2.0);
        when(requirements.predatesRequirements("M-9")).thenReturn(true);
        when(teamMemory.snapshot("TEAM-DEVELOPMENT")).thenReturn(new com.aicompany.core.model.TeamSnapshot(
                "TEAM-DEVELOPMENT", "Development Group", "ACTIVE", "engineering", java.util.List.of()));
        var availability = mock(AgentAvailability.class);
        service.setAgentAvailability(availability);

        service.recordDecision("M-9", new DecisionCommand(InvestorDecision.REQUEST_MORE_EVIDENCE, "más"));

        verify(availability).requireEnabled(argThat(ids -> !ids.contains(AgentAvailability.PRODUCT_OWNER)), anyString());
    }

    // Revisión final Aria (16): el orquestador cuenta las rondas de evidencia igual que el endpoint (sin incrementos).
    @Test
    void evidenceRoundsUsedDiscountIncrements() {
        when(memory.evidenceRound("M-9")).thenReturn(3);
        when(requirements.incrementRounds("M-9")).thenReturn(2);
        assertEquals(1, service.evidenceRoundsUsed("M-9"));
    }

    // Spec misiones durables §2: el ajuste reclamado guarda su instrucción, para retomarlo tras un reinicio.
    @Test
    void anAdjustmentClaimKeepsItsInstruction() {
        when(memory.find("M-9")).thenReturn(Optional.of(missionIn(MissionStatus.AWAITING_SPEC_APPROVAL)));
        when(requirements.latest("M-9")).thenReturn(Optional.of(new RequirementsMemoryService.Version(1, "{}", "DRAFT", "", null)));
        when(requirements.claimDecision(anyString(), anyInt(), any())).thenReturn(true);

        service.decideRequirements("M-9", new RequirementsDecisionCommand(
                RequirementsDecisionCommand.RequirementsDecision.ADJUST, " solo clientes "));

        verify(requirements).claimDecision("M-9", 1, "solo clientes");
    }

    // Spec misiones durables §3: las correcciones no consumen rondas de evidencia.
    @Test
    void correctionRoundsDoNotCountAgainstTheEvidenceLimit() {
        var recovery = mock(com.aicompany.core.recovery.RecoveryMemoryService.class);
        service.setRecoveryMemory(recovery);
        when(memory.evidenceRound("M-9")).thenReturn(4);
        when(requirements.incrementRounds("M-9")).thenReturn(1);
        when(recovery.correctionRounds("M-9")).thenReturn(java.util.List.of(
                new com.aicompany.core.recovery.CorrectionRound(2, 1, "x", 3, false),
                new com.aicompany.core.recovery.CorrectionRound(3, 1, "y", 2, false)));
        assertEquals(1, service.evidenceRoundsUsed("M-9"));
    }

    private void awaitingWithDesign(int specVersion, int designSpecVersion) {
        when(memory.find("M-9")).thenReturn(Optional.of(missionIn(MissionStatus.AWAITING_SPEC_APPROVAL)));
        when(requirements.latest("M-9")).thenReturn(Optional.of(new RequirementsMemoryService.Version(specVersion, "{}", "DRAFT", "", null)));
        when(designs.latest("M-9")).thenReturn(Optional.of(new InteractionDesignMemoryService.Version(4, "{}", "DRAFT", designSpecVersion, "")));
    }

    @Test
    void approvingApprovesTheStoriesAndTheDesignTogether() {
        awaitingWithDesign(2, 2);
        service.decideRequirements("M-9", new RequirementsDecisionCommand(
                RequirementsDecisionCommand.RequirementsDecision.APPROVE, null));
        // Revisión final (M-1): HU y diseño se aprueban en una sola transacción.
        verify(requirements).approveWithDesign("M-9", 2, 4, "founder");
        verify(requirements, never()).approve(anyString(), anyInt(), anyString());
        verify(events).publish(eq("EMPRESA_REQUIREMENTS_APPROVED"), eq("M-9"), isNull(), eq("human"),
                argThat(m -> Integer.valueOf(4).equals(m.get("interactionDesignVersion"))));
    }

    // Review Focus 4: un diseño hecho sobre otra versión de HU no se aprueba.
    @Test
    void aDesignForOtherStoriesCannotBeApproved() {
        awaitingWithDesign(3, 2);
        assertThrows(IllegalStateException.class, () -> service.decideRequirements("M-9", new RequirementsDecisionCommand(
                RequirementsDecisionCommand.RequirementsDecision.APPROVE, null)));
        verify(requirements, never()).approve(anyString(), anyInt(), anyString());
    }

    @Test
    void anAdjustmentForKaelGoesOnlyToKael() {
        awaitingWithDesign(2, 2);
        service.decideRequirements("M-9", new RequirementsDecisionCommand(
                RequirementsDecisionCommand.RequirementsDecision.ADJUST, "paleta más grande",
                RequirementsDecisionCommand.Target.KAEL));
        // Revisión final (M-1): reclamar la decisión y guardar el ajuste en el diseño, en una sola transacción.
        verify(requirements).claimDecisionForKael("M-9", 2, 4, "paleta más grande");
        verify(requirements, never()).claimDecision(anyString(), anyInt(), any());
        verify(executor).adjustDesignAsync("M-9", "paleta más grande");
        verify(executor, never()).adjustRequirementsAsync(anyString(), anyString());
    }

    @Test
    void kaelCannotBeAdjustedWithoutADesign() {
        when(memory.find("M-9")).thenReturn(Optional.of(missionIn(MissionStatus.AWAITING_SPEC_APPROVAL)));
        when(requirements.latest("M-9")).thenReturn(Optional.of(new RequirementsMemoryService.Version(1, "{}", "DRAFT", "", null)));
        when(designs.latest("M-9")).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class, () -> service.decideRequirements("M-9", new RequirementsDecisionCommand(
                RequirementsDecisionCommand.RequirementsDecision.ADJUST, "x", RequirementsDecisionCommand.Target.KAEL)));
    }

    @Test
    void rejectingRejectsTheDesignToo() {
        awaitingWithDesign(2, 2);
        service.decideRequirements("M-9", new RequirementsDecisionCommand(
                RequirementsDecisionCommand.RequirementsDecision.REJECT, null));
        verify(designs).reject("M-9", 4);
    }

    // Review Focus 3: una misión sin diseño (spec viejo o NONE) se aprueba como siempre.
    @Test
    void approvingWithoutDesignWorksAsBefore() {
        when(memory.find("M-9")).thenReturn(Optional.of(missionIn(MissionStatus.AWAITING_SPEC_APPROVAL)));
        when(requirements.latest("M-9")).thenReturn(Optional.of(new RequirementsMemoryService.Version(2, "{}", "DRAFT", "", null)));
        when(designs.latest("M-9")).thenReturn(Optional.empty());
        service.decideRequirements("M-9", new RequirementsDecisionCommand(
                RequirementsDecisionCommand.RequirementsDecision.APPROVE, null));
        verify(requirements).approve("M-9", 2, "founder");
        verify(requirements, never()).approveWithDesign(anyString(), anyInt(), anyInt(), anyString());
    }

    @Test
    void aSecondAdjustmentForKaelLoses() {
        awaitingWithDesign(2, 2);
        when(requirements.claimDecisionForKael("M-9", 2, 4, "x")).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> service.decideRequirements("M-9", new RequirementsDecisionCommand(
                RequirementsDecisionCommand.RequirementsDecision.ADJUST, "x", RequirementsDecisionCommand.Target.KAEL)));
        verify(executor, never()).adjustDesignAsync(anyString(), anyString());
    }

    private void awaitingAppSpecWithoutDesign() {
        var spec = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(
                new com.aicompany.core.agent.model.RequirementsSpec("CRM", java.util.List.of(), java.util.List.of(),
                        java.util.List.of(), java.util.List.of(), java.util.List.of(), "APP"));
        when(memory.find("M-9")).thenReturn(Optional.of(missionIn(MissionStatus.AWAITING_SPEC_APPROVAL)));
        when(requirements.latest("M-9")).thenReturn(Optional.of(new RequirementsMemoryService.Version(2, spec, "DRAFT", "", null)));
        when(designs.latest("M-9")).thenReturn(Optional.empty());
    }

    // Revisión final (M-3): una app o un juego no se aprueba ni se ajusta antes de que Kael termine su diseño.
    @Test
    void anAppCannotBeDecidedBeforeKaelsDesign() {
        awaitingAppSpecWithoutDesign();
        var ex = assertThrows(IllegalStateException.class, () -> service.decideRequirements("M-9",
                new RequirementsDecisionCommand(RequirementsDecisionCommand.RequirementsDecision.APPROVE, null)));
        assertEquals("Kael todavía no terminó el diseño de M-9: espera a que esté listo.", ex.getMessage());
        assertThrows(IllegalStateException.class, () -> service.decideRequirements("M-9",
                new RequirementsDecisionCommand(RequirementsDecisionCommand.RequirementsDecision.ADJUST, "más campos")));
        verify(requirements, never()).claimDecision(anyString(), anyInt(), any());
        verifyNoInteractions(executor);
    }

    @Test
    void anAppCanStillBeRejectedBeforeKaelsDesign() {
        awaitingAppSpecWithoutDesign();
        service.decideRequirements("M-9", new RequirementsDecisionCommand(
                RequirementsDecisionCommand.RequirementsDecision.REJECT, null));
        verify(requirements).reject("M-9", 2);
    }

    private final ContractMemoryService contracts = mock(ContractMemoryService.class);

    private void withDraftContract(int version) {
        service.setContracts(contracts);
        when(memory.find("M-9")).thenReturn(Optional.of(missionIn(MissionStatus.AWAITING_SPEC_APPROVAL)));
        when(contracts.latest("M-9")).thenReturn(Optional.of(new ContractMemoryService.Version(version,
                "{\"summary\":\"Un CRM.\",\"experience\":\"APP\",\"items\":[],\"outOfScope\":[],\"useEpics\":false,"
                        + "\"epicsReason\":\"x\"}", "DRAFT", "", null)));
        when(contracts.claimDecision(anyString(), anyInt(), any())).thenReturn(true);
    }

    // Contrato (spec 2026-10-16 contrato §1): con el contrato en DRAFT, la decisión es sobre el contrato.
    @Test
    void approvingADraftContractContinuesToTheStories() {
        withDraftContract(2);

        var view = service.decideRequirements("M-9", new RequirementsDecisionCommand(
                RequirementsDecisionCommand.RequirementsDecision.APPROVE, null));

        verify(contracts).approve("M-9", 2, "founder");
        verify(executor).continueAfterContractAsync("M-9");
        verify(executor, never()).continueAfterApprovalAsync(anyString());
        verify(requirements, never()).approve(anyString(), anyInt(), anyString());
        verify(events).publish(eq("EMPRESA_CONTRACT_APPROVED"), eq("M-9"), isNull(), eq("human"), anyMap());
        assertNotNull(view);
    }

    @Test
    void adjustingADraftContractGoesToAriaAndKaelCannotAdjustIt() {
        withDraftContract(1);

        service.decideRequirements("M-9", new RequirementsDecisionCommand(
                RequirementsDecisionCommand.RequirementsDecision.ADJUST, " agrega inventarios "));
        verify(contracts).claimDecision("M-9", 1, "agrega inventarios");
        verify(executor).adjustContractAsync("M-9", "agrega inventarios");

        assertThrows(IllegalStateException.class, () -> service.decideRequirements("M-9", new RequirementsDecisionCommand(
                RequirementsDecisionCommand.RequirementsDecision.ADJUST, "x", RequirementsDecisionCommand.Target.KAEL)));
        assertThrows(IllegalArgumentException.class, () -> service.decideRequirements("M-9", new RequirementsDecisionCommand(
                RequirementsDecisionCommand.RequirementsDecision.ADJUST, " ")));
    }

    @Test
    void rejectingADraftContractCancelsTheMission() {
        withDraftContract(1);

        service.decideRequirements("M-9", new RequirementsDecisionCommand(
                RequirementsDecisionCommand.RequirementsDecision.REJECT, null));

        verify(contracts).reject("M-9", 1);
        verify(memory).updateMission(eq("M-9"), eq(MissionStatus.CANCELLED), eq(100), eq("Contrato rechazado"), anyString());
        verify(events).publish(eq("EMPRESA_CONTRACT_REJECTED"), eq("M-9"), isNull(), eq("human"), anyMap());
    }

    // Review Focus 1: doble clic sobre el contrato.
    @Test
    void aSecondDecisionOnTheSameContractVersionIsRejected() {
        withDraftContract(1);
        when(contracts.claimDecision(anyString(), anyInt(), any())).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> service.decideRequirements("M-9", new RequirementsDecisionCommand(
                RequirementsDecisionCommand.RequirementsDecision.APPROVE, null)));
        verify(executor, never()).continueAfterContractAsync(anyString());
    }

    @Test
    void theViewIncludesTheContract() {
        withDraftContract(1);

        var view = service.requirements("M-9").orElseThrow();

        assertEquals(1, view.contract().version());
        assertEquals("DRAFT", view.contract().status());
        assertTrue(view.contract().summary().startsWith("Contrato (versión 1, aplicación): Un CRM."), view.contract().summary());
    }
}
