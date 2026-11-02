package com.aicompany.core.service;

import com.aicompany.core.agent.AgentRuntime;
import com.aicompany.core.agent.model.InteractionDesign;
import com.aicompany.core.agent.model.Mockups;
import com.aicompany.core.agent.model.RequirementsSpec;
import com.aicompany.core.agent.model.VisualDesign;
import com.aicompany.core.agent.validation.ContradictionDetector;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.Captures;
import com.aicompany.core.model.MissionStatus;
import com.aicompany.core.model.PolicyKey;
import com.aicompany.core.model.TeamExecutionMode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Spec 2026-11-01 front con diseño visual: tras Kael, el front diseña y el fundador aprueba todo junto con las HU. */
class MissionExecutorVisualDesignTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final MissionMemoryService memory = mock(MissionMemoryService.class);
    private final CeoService ceoService = mock(CeoService.class);
    private final CompanyMemoryService companyMemory = mock(CompanyMemoryService.class);
    private final PromptMemoryService promptMemory = mock(PromptMemoryService.class);
    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    private final CompanyPolicyService policies = mock(CompanyPolicyService.class);
    private final TeamWorkPlanner planner = mock(TeamWorkPlanner.class);
    private final TeamExecutionStrategy development = mock(TeamExecutionStrategy.class);
    private final RequirementsWriter writer = mock(RequirementsWriter.class);
    private final RequirementsMemoryService requirements = mock(RequirementsMemoryService.class);
    private final InteractionDesignWriter designWriter = mock(InteractionDesignWriter.class);
    private final InteractionDesignMemoryService designMemory = mock(InteractionDesignMemoryService.class);
    private final VisualDesignWriter visualWriter = mock(VisualDesignWriter.class);
    private final VisualDesignMemoryService visualMemory = mock(VisualDesignMemoryService.class);
    private final DesignCaptureService capture = mock(DesignCaptureService.class);
    private final AgentAvailability availability = mock(AgentAvailability.class);

    private final MissionExecutor executor = new MissionExecutor(
            memory, new AgentTaskBatchRunner(memory, mock(AgentRuntime.class), events), ceoService, companyMemory,
            promptMemory, "qwen2.5-coder:14b", Runnable::run, events, JSON, mock(ContradictionDetector.class),
            policies, mock(OpportunityMemoryService.class), mock(AlertMailService.class), planner, List.of(development));

    static final RequirementsSpec APP_SPEC = new RequirementsSpec("CRM", List.of(), List.of(),
            List.of(new RequirementsSpec.Role("vendedor", "x")),
            List.of(new RequirementsSpec.UserStory("HU-01", "vendedor", "registrar clientes", "seguirlos", List.of("c"),
                    List.of(), List.of())),
            List.of(new RequirementsSpec.Increment(1, "MVP", List.of("HU-01"))), "APP");
    static final RequirementsSpec GAME_SPEC = new RequirementsSpec("Pong", List.of(), List.of(), APP_SPEC.roles(),
            APP_SPEC.userStories(), APP_SPEC.increments(), "GAME");

    static final InteractionDesign APP_DESIGN = new InteractionDesign("APP", new InteractionDesign.AppDesign(
            List.of(new InteractionDesign.Screen("PANT-01", "Clientes", "ver", "vendedor", List.of())),
            List.of(new InteractionDesign.Flow("HU-01", List.of("PANT-01"), List.of())), List.of(), List.of()), null);
    static final InteractionDesign GAME_DESIGN = MissionExecutorTeamTest.DESIGN;

    static final VisualDesign VISUAL = new VisualDesign(VisualDesignWriterTest.system("#FFFFFF"),
            new Mockups(List.of(new Mockups.Mockup("PANT-01", "<div data-id=\"PANT-01\">x</div>")), List.of()));

    private static Captures complete() {
        return new Captures(Map.of("PANT-01-phone", Path.of("p.png"), "PANT-01-desktop", Path.of("d.png")), Map.of());
    }

    {
        when(companyMemory.agentModel(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(promptMemory.activePrompt(anyString())).thenReturn("");
        when(development.mode()).thenReturn(TeamExecutionMode.DEVELOPMENT);
        when(memory.teamId("M-9")).thenReturn(Optional.of("TEAM-DEVELOPMENT"));
        when(memory.instructionOf("M-9")).thenReturn(Optional.of("Un CRM"));
        when(policies.activeValue(PolicyKey.SPEC_APPROVAL_REQUIRED)).thenReturn(1.0);
        when(availability.isEnabled("frontend-ui")).thenReturn(true);
        when(availability.isEnabled("specialist-3d")).thenReturn(true);
        executor.setRequirements(writer, requirements);
        executor.setInteractionDesign(designWriter, designMemory);
        executor.setVisualDesign(visualWriter, visualMemory, capture, availability);
    }

    private void appMission() {
        when(writer.write(eq("M-9"), eq("Un CRM"), eq(Optional.empty()), isNull()))
                .thenReturn(new RequirementsWriter.Written(1, APP_SPEC));
        when(designWriter.write(eq("M-9"), eq(APP_SPEC), eq(1), eq(Optional.empty()), isNull()))
                .thenReturn(new InteractionDesignWriter.Written(1, APP_DESIGN));
        when(visualWriter.write(eq("M-9"), anyString(), eq(APP_SPEC), eq(APP_DESIGN.app()), eq(1), eq(Optional.empty()), isNull()))
                .thenReturn(new VisualDesignWriter.Written(1, VISUAL));
        when(visualMemory.latest("M-9")).thenReturn(Optional.empty());
    }

    private void verifyAwaitingWith(String text) {
        verify(memory).updateMission(eq("M-9"), eq(MissionStatus.AWAITING_SPEC_APPROVAL), anyInt(), anyString(),
                argThat(m -> m.contains(text)));
    }

    @Test
    void appExperienceRunsFrontAfterKaelBeforeApproval() throws Exception {
        appMission();
        when(capture.captureMockups("M-9", 1, 1, VISUAL)).thenReturn(complete());

        executor.executeAsync("M-9", "Un CRM").get();

        var order = inOrder(designWriter, visualWriter, capture, memory);
        order.verify(designWriter).write(eq("M-9"), eq(APP_SPEC), eq(1), eq(Optional.empty()), isNull());
        order.verify(visualWriter).write(eq("M-9"), eq("frontend-ui"), eq(APP_SPEC), eq(APP_DESIGN.app()), eq(1),
                eq(Optional.empty()), isNull());
        order.verify(capture).captureMockups("M-9", 1, 1, VISUAL);
        order.verify(memory).updateMission(eq("M-9"), eq(MissionStatus.AWAITING_SPEC_APPROVAL), anyInt(), anyString(),
                argThat(m -> m.contains("Kael") && m.contains("el front diseñó el sistema visual y 1 maqueta")));
        verify(events).publish(eq("EMPRESA_VISUAL_DESIGN_READY"), eq("M-9"), isNull(), eq("frontend-ui"),
                argThat(d -> Integer.valueOf(1).equals(d.get("version"))
                        && Integer.valueOf(1).equals(d.get("interactionDesignVersion"))));
        verify(planner, never()).plan(any(), any(), any(), any());
    }

    @Test
    void withMilaOffOrionDesignsTheVisualSystem() throws Exception {
        appMission();
        when(availability.isEnabled("frontend-ui")).thenReturn(false);
        when(visualWriter.write(eq("M-9"), eq("specialist-3d"), any(), any(), anyInt(), any(), any()))
                .thenReturn(new VisualDesignWriter.Written(1, VISUAL));
        when(capture.captureMockups("M-9", 1, 1, VISUAL)).thenReturn(complete());

        executor.executeAsync("M-9", "Un CRM").get();

        verify(visualWriter).write(eq("M-9"), eq("specialist-3d"), any(), any(), anyInt(), any(), any());
        verifyAwaitingWith("el front diseñó el sistema visual");
    }

    // Igual que con Kael apagado: sin ningún front encendido la misión queda FAILED y nunca espera.
    @Test
    void withNoFrontOnTheAppMissionFails() throws Exception {
        appMission();
        when(availability.isEnabled("frontend-ui")).thenReturn(false);
        when(availability.isEnabled("specialist-3d")).thenReturn(false);
        when(visualWriter.write(eq("M-9"), eq("frontend-ui"), any(), any(), anyInt(), any(), any()))
                .thenThrow(new IllegalStateException("El agente frontend-ui está apagado"));

        executor.executeAsync("M-9", "Un CRM").get();

        verify(memory).updateMission(eq("M-9"), eq(MissionStatus.FAILED), anyInt(), anyString(),
                argThat(m -> m.contains("frontend-ui está apagado")));
        verify(memory, never()).updateMission(eq("M-9"), eq(MissionStatus.AWAITING_SPEC_APPROVAL), anyInt(), anyString(),
                anyString());
    }

    @Test
    void gameExperienceSkipsVisualDesign() throws Exception {
        when(writer.write(eq("M-9"), eq("Un CRM"), eq(Optional.empty()), isNull()))
                .thenReturn(new RequirementsWriter.Written(1, GAME_SPEC));
        when(designWriter.write(eq("M-9"), eq(GAME_SPEC), eq(1), eq(Optional.empty()), isNull()))
                .thenReturn(new InteractionDesignWriter.Written(1, GAME_DESIGN));

        executor.executeAsync("M-9", "Un CRM").get();

        verifyNoInteractions(visualWriter, capture);
        verifyAwaitingWith("Kael diseñó el juego");
    }

    // Con la policy en 0, la policy aprueba HU, diseño de Kael y diseño visual juntos.
    @Test
    void withThePolicyOffTheVisualIsApprovedWithTheStories() throws Exception {
        appMission();
        when(policies.activeValue(PolicyKey.SPEC_APPROVAL_REQUIRED)).thenReturn(0.0);
        when(capture.captureMockups("M-9", 1, 1, VISUAL)).thenReturn(complete());
        when(requirements.approved("M-9")).thenReturn(Optional.of(new RequirementsMemoryService.Version(1,
                JSON.writeValueAsString(APP_SPEC), "APPROVED", "", null)));
        when(planner.plan(eq("M-9"), eq("TEAM-DEVELOPMENT"), anyString(), any())).thenThrow(new IllegalStateException("fin"));

        executor.executeAsync("M-9", "Un CRM").get();

        verify(requirements).approveWithDesign("M-9", 1, 1, 1, "policy");
        verify(events).publish(eq("EMPRESA_REQUIREMENTS_APPROVED"), eq("M-9"), isNull(), eq("policy"),
                argThat(d -> Integer.valueOf(1).equals(d.get("visualDesignVersion"))));
    }

    // Las HU, el diseño de Kael y el visual se aprueban en una sola transacción de Neo4j.
    @Test
    @SuppressWarnings("unchecked")
    void approveApprovesVisualInSameTransaction() {
        var driver = mock(org.neo4j.driver.Driver.class);
        var session = mock(org.neo4j.driver.Session.class);
        var tx = mock(org.neo4j.driver.TransactionContext.class);
        when(driver.session()).thenReturn(session);
        when(session.executeWrite(any())).thenAnswer(inv ->
                ((org.neo4j.driver.TransactionCallback<Object>) inv.getArgument(0)).execute(tx));

        new RequirementsMemoryService(driver).approveWithDesign("M-9", 2, 4, 3, "founder");

        verify(session, times(1)).executeWrite(any());
        var cypher = org.mockito.ArgumentCaptor.forClass(String.class);
        var params = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(tx, atLeast(3)).run(cypher.capture(), params.capture());
        assertTrue(cypher.getAllValues().stream().anyMatch(c -> c.contains("RequirementsVersion") && c.contains("APPROVED")));
        assertTrue(cypher.getAllValues().stream().anyMatch(c -> c.contains("InteractionDesignVersion") && c.contains("APPROVED")));
        assertTrue(cypher.getAllValues().stream().anyMatch(c -> c.contains("VisualDesignVersion {version:$vd})")
                && c.contains("'APPROVED'")));
        // La versión visual aprobada antes queda SUPERSEDED en la misma transacción.
        assertTrue(cypher.getAllValues().stream().anyMatch(c -> c.contains("VisualDesignVersion") && c.contains("SUPERSEDED")));
        assertTrue(params.getAllValues().stream().anyMatch(p -> Integer.valueOf(3).equals(p.get("vd"))));
    }

    @Test
    void adjustVisualGoesToFrontWriter() throws Exception {
        when(requirements.latest("M-9")).thenReturn(Optional.of(new RequirementsMemoryService.Version(1,
                JSON.writeValueAsString(APP_SPEC), "DRAFT", "", null)));
        when(designMemory.latest("M-9")).thenReturn(Optional.of(new InteractionDesignMemoryService.Version(1,
                JSON.writeValueAsString(APP_DESIGN), "DRAFT", 1, "")));
        var visual2 = new VisualDesignWriter.Written(2, VISUAL);
        when(visualMemory.latest("M-9")).thenReturn(Optional.of(new VisualDesignMemoryService.Version(1,
                JSON.writeValueAsString(VISUAL), "DRAFT", 1, 1, "")));
        when(visualWriter.write(eq("M-9"), eq("frontend-ui"), eq(APP_SPEC), eq(APP_DESIGN.app()), eq(1),
                eq(Optional.of(VISUAL)), eq("más contraste"))).thenAnswer(inv -> {
                    when(visualMemory.latest("M-9")).thenReturn(Optional.of(new VisualDesignMemoryService.Version(2,
                            JSON.writeValueAsString(VISUAL), "DRAFT", 1, 1, "más contraste")));
                    return visual2;
                });
        when(capture.captureMockups("M-9", 1, 2, VISUAL)).thenReturn(complete());

        executor.adjustVisualAsync("M-9", "más contraste").get();

        var order = inOrder(requirements, visualWriter, capture, memory);
        order.verify(requirements).releaseDecision("M-9", 1);
        order.verify(visualWriter).write(eq("M-9"), eq("frontend-ui"), eq(APP_SPEC), eq(APP_DESIGN.app()), eq(1),
                eq(Optional.of(VISUAL)), eq("más contraste"));
        order.verify(capture).captureMockups("M-9", 1, 2, VISUAL);
        order.verify(memory).updateMission(eq("M-9"), eq(MissionStatus.AWAITING_SPEC_APPROVAL), anyInt(), anyString(),
                argThat(m -> m.contains("versión 2")));
        verify(writer, never()).write(any(), any(), any(), any());
        verify(designWriter, never()).write(any(), any(), anyInt(), any(), any());
        verify(events).publish(eq("EMPRESA_VISUAL_DESIGN_ADJUSTED"), eq("M-9"), isNull(), eq("human"), anyMap());
    }

    @Test
    void captureFailureLeavesApprovalWithWarning() throws Exception {
        appMission();
        when(capture.captureMockups(anyString(), anyInt(), anyInt(), any()))
                .thenThrow(new IllegalStateException("sandbox-runner no disponible: connection refused"));

        executor.executeAsync("M-9", "Un CRM").get();

        verifyAwaitingWith("sin imágenes: sandbox-runner no disponible: connection refused");
        verify(memory, never()).updateMission(eq("M-9"), eq(MissionStatus.FAILED), anyInt(), anyString(), anyString());
    }

    // Revisión R8: el job da PASS con capturas parciales; las que faltan se le muestran al fundador como «sin imagen».
    @Test
    void missingImagesAreShownAsWithoutImage() throws Exception {
        appMission();
        when(capture.captureMockups("M-9", 1, 1, VISUAL)).thenReturn(
                new Captures(Map.of("PANT-01-phone", Path.of("p.png")), Map.of()));

        executor.executeAsync("M-9", "Un CRM").get();

        verifyAwaitingWith("sin imagen: PANT-01-desktop");
    }

    // Si Aria cambia las HU, el visual queda SUPERSEDED como el de Kael y el front vuelve a diseñar.
    @Test
    void adjustingAriaSupersedesTheVisualDraft() throws Exception {
        when(requirements.latest("M-9")).thenReturn(Optional.of(new RequirementsMemoryService.Version(1,
                JSON.writeValueAsString(APP_SPEC), "DRAFT", "", null)));
        when(writer.write(eq("M-9"), eq("Un CRM"), any(), eq("otra HU")))
                .thenReturn(new RequirementsWriter.Written(2, APP_SPEC));
        when(designWriter.write(eq("M-9"), eq(APP_SPEC), eq(2), any(), anyString()))
                .thenReturn(new InteractionDesignWriter.Written(2, APP_DESIGN));
        when(visualMemory.latest("M-9")).thenReturn(Optional.empty());
        when(visualWriter.write(eq("M-9"), anyString(), any(), any(), anyInt(), any(), any()))
                .thenReturn(new VisualDesignWriter.Written(2, VISUAL));
        when(capture.captureMockups("M-9", 1, 2, VISUAL)).thenReturn(complete());

        executor.adjustRequirementsAsync("M-9", "otra HU").get();

        var order = inOrder(visualMemory, visualWriter);
        order.verify(visualMemory).supersedeDraft("M-9");
        order.verify(visualWriter).write(eq("M-9"), anyString(), any(), any(), anyInt(), any(), any());
    }

    // Al retomar la espera (reinicio), el visual DRAFT del diseño vigente se reutiliza: ni el front ni el sandbox repiten.
    @Test
    void resumingTheWaitReusesTheVisualDraftAndItsCapture() throws Exception {
        when(requirements.latest("M-9")).thenReturn(Optional.of(new RequirementsMemoryService.Version(1,
                JSON.writeValueAsString(APP_SPEC), "DRAFT", "", null)));
        when(designMemory.latest("M-9")).thenReturn(Optional.of(new InteractionDesignMemoryService.Version(1,
                JSON.writeValueAsString(APP_DESIGN), "DRAFT", 1, "")));
        when(visualMemory.latest("M-9")).thenReturn(Optional.of(new VisualDesignMemoryService.Version(1,
                JSON.writeValueAsString(VISUAL), "DRAFT", 1, 1, "")));
        when(capture.mockupCaptures("M-9", 1)).thenReturn(Optional.of(complete()));

        executor.resumeAsync("M-9", new com.aicompany.core.recovery.ResumePoint(
                com.aicompany.core.recovery.ResumePoint.Stage.AWAIT_APPROVAL, 0,
                com.aicompany.core.recovery.ResumePoint.RoundKind.ORIGINAL, null)).get();

        verifyNoInteractions(visualWriter);
        verify(capture, never()).captureMockups(anyString(), anyInt(), anyInt(), any());
        verifyAwaitingWith("el front diseñó el sistema visual y 1 maqueta");
    }
}
