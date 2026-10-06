package com.aicompany.core.service;

import com.aicompany.core.agent.AgentRuntime;
import com.aicompany.core.agent.model.AgentResult;
import com.aicompany.core.agent.model.TeamPlan;
import com.aicompany.core.agent.model.TeamPlan.PlannedTask;
import com.aicompany.core.agent.validation.ContradictionDetector;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MissionExecutorTeamTest {

    private final MissionMemoryService memory = mock(MissionMemoryService.class);
    private final AgentRuntime runtime = mock(AgentRuntime.class);
    private final CeoService ceoService = mock(CeoService.class);
    private final CompanyMemoryService companyMemory = mock(CompanyMemoryService.class);
    private final PromptMemoryService promptMemory = mock(PromptMemoryService.class);
    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    private final ContradictionDetector contradictionDetector = mock(ContradictionDetector.class);
    private final CompanyPolicyService companyPolicyService = mock(CompanyPolicyService.class);
    private final OpportunityMemoryService opportunityMemory = mock(OpportunityMemoryService.class);
    private final AlertMailService alertMailService = mock(AlertMailService.class);
    private final TeamWorkPlanner planner = mock(TeamWorkPlanner.class);
    private final TeamExecutionStrategy analysis = mock(TeamExecutionStrategy.class);
    private final TeamExecutionStrategy development = mock(TeamExecutionStrategy.class);

    private final MissionExecutor executor = new MissionExecutor(
            memory, new AgentTaskBatchRunner(memory, runtime, events), ceoService, companyMemory, promptMemory,
            "qwen2.5-coder:14b", Runnable::run, events, JsonMapper.builder().build(), contradictionDetector,
            companyPolicyService, opportunityMemory, alertMailService, planner, List.of(analysis, development));

    {
        when(companyMemory.agentModel(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(promptMemory.activePrompt(anyString())).thenReturn("");
        when(companyPolicyService.activeValue(PolicyKey.SEED_CAPITAL_USD)).thenReturn(50.0);
        when(companyPolicyService.activeValue(PolicyKey.CONTRADICTION_SEED_CAPITAL_MULTIPLE)).thenReturn(100.0);
        when(analysis.mode()).thenReturn(TeamExecutionMode.ANALYSIS);
        when(development.mode()).thenReturn(TeamExecutionMode.DEVELOPMENT);
        when(ceoService.executeMission(anyString(), anyString(), anyString(), anyString())).thenReturn("consolidado");
    }

    private static TeamPlanResult planned(String teamId) {
        var team = new TeamSnapshot(teamId, "Equipo", "ACTIVE", "engineering", List.of(
                new TeamMemberInfo("engineering", "Neo", "rol", "ROLE", List.of("x"), "qwen3:8b")));
        var plan = new TeamPlan("plan", "HTML5", "web/index.html", List.of(
                new PlannedTask("engineering", "WORK", "BUILD", "Construir", List.of("x"), List.of("web"))));
        return new TeamPlanResult(team, plan);
    }

    // Spec evidence-rounds (revisión 2026-09-27): la ronda de un equipo reutiliza el último plan guardado.
    @Test
    void anEvidenceRoundOfATeamReusesTheSavedPlanWithoutCallingThePlanner() throws Exception {
        var saved = planned("TEAM-MARKETING-GROWTH");
        when(memory.teamId("M-2")).thenReturn(Optional.of("TEAM-MARKETING-GROWTH"));
        when(memory.tasks("M-2")).thenReturn(List.of());
        when(memory.lastTeamPlanJson("M-2")).thenReturn(Optional.of(JsonMapper.builder().build().writeValueAsString(saved.plan())));
        when(planner.teamSnapshot("TEAM-MARKETING-GROWTH")).thenReturn(saved.team());
        when(ceoService.routeInvestorFeedback(any(), any(), any(), anyList(), any()))
                .thenReturn(java.util.Map.of("engineering", "Más canales"));
        when(analysis.execute(any(), any())).thenReturn(new TeamExecutionResult.AgentOutcomes(List.of()));

        executor.reexecuteAsync("M-2", "Plan de contenido", 1, "Más canales").get();

        verify(planner, never()).plan(any(), any(), any(), any());
        verify(planner, never()).plan(any(), any(), any(), any(), anyInt());
        verify(analysis).execute(argThat(c -> c.round() == 1 && c.plan().workTasks().stream()
                .allMatch(t -> t.objective().contains("SOLICITUD DEL INVERSIONISTA")
                        && t.objective().contains("Más canales"))), any());
    }

    @Test
    void aTeamMissionThatFailedBeforeHavingAPlanIsPlannedAgainWithTheRequest() throws Exception {
        when(memory.teamId("M-3")).thenReturn(Optional.of("TEAM-MARKETING-GROWTH"));
        when(memory.tasks("M-3")).thenReturn(List.of());
        when(memory.lastTeamPlanJson("M-3")).thenReturn(Optional.empty());
        when(planner.plan(eq("M-3"), eq("TEAM-MARKETING-GROWTH"), argThat(i -> i != null && i.contains("Más canales")),
                any(), eq(1))).thenReturn(planned("TEAM-MARKETING-GROWTH"));
        when(ceoService.routeInvestorFeedback(any(), any(), any(), anyList(), any()))
                .thenReturn(java.util.Map.of("engineering", "Más canales"));
        when(analysis.execute(any(), any())).thenReturn(new TeamExecutionResult.AgentOutcomes(List.of()));

        executor.reexecuteAsync("M-3", "Plan de contenido", 1, "Más canales").get();

        verify(planner).plan(eq("M-3"), eq("TEAM-MARKETING-GROWTH"), argThat(i -> i != null && i.contains("Más canales")),
                any(), eq(1));
        verify(analysis).execute(argThat(c -> c.round() == 1), any());
    }

    // Spec catálogo §6 A: una construcción terminada intenta pasar sus productos a "listo para vender".
    @Test
    void aFinishedBuildTellsTheCatalog() throws Exception {
        when(memory.teamId("M-1")).thenReturn(Optional.of("TEAM-DEVELOPMENT"));
        when(planner.plan(anyString(), anyString(), anyString(), any())).thenReturn(planned("TEAM-DEVELOPMENT"));
        when(development.execute(any(), any())).thenReturn(new TeamExecutionResult.Development("reporte", "ESTADO"));
        var automation = mock(ProductAutomation.class);
        executor.setProductAutomation(automation);

        executor.executeAsync("M-1", "crear").get();

        verify(automation).buildFinished("M-1");
        verify(automation, never()).ideaFromDiscovery(any(), any());
    }

    @Test
    void anEngineeringMissionNeverCreatesDiscoveryTasks() throws Exception {
        when(memory.teamId("M-1")).thenReturn(Optional.of("TEAM-DEVELOPMENT"));
        when(planner.plan("M-1", "TEAM-DEVELOPMENT", "crear un juego", TeamExecutionMode.DEVELOPMENT))
                .thenReturn(planned("TEAM-DEVELOPMENT"));
        when(development.execute(any(), any()))
                .thenReturn(new TeamExecutionResult.Development("reporte", "ESTADO VERIFICABLE ..."));

        executor.executeAsync("M-1", "crear un juego").get();

        verifyNoInteractions(runtime);
        verify(memory, never()).createTask(anyString(), anyString(), eq("finance"), anyString());
        verify(memory, never()).createTask(anyString(), anyString(), eq("sales"), anyString());
        verify(memory, never()).createTask(anyString(), anyString(), eq("product"), anyString());
        verify(analysis, never()).execute(any(), any());
    }

    @Test
    void developmentResultsEndWithTheJavaGeneratedVerifiableState() throws Exception {
        when(memory.teamId("M-1")).thenReturn(Optional.of("TEAM-DEVELOPMENT"));
        when(planner.plan(anyString(), anyString(), anyString(), any())).thenReturn(planned("TEAM-DEVELOPMENT"));
        when(development.execute(any(), any()))
                .thenReturn(new TeamExecutionResult.Development("reporte", "ESTADO VERIFICABLE X"));

        executor.executeAsync("M-1", "crear un juego").get();

        verify(ceoService).executeMission(eq("crear un juego"), eq("reporte"), anyString(), anyString());
        verify(memory).updateMission(eq("M-1"), eq(MissionStatus.AWAITING_INVESTOR), anyInt(), anyString(),
                eq("consolidado\n\nESTADO VERIFICABLE X"));
    }

    @Test
    void anAnalysisTeamIsConsolidatedLikeDiscovery() throws Exception {
        when(memory.teamId("M-2")).thenReturn(Optional.of("TEAM-MARKETING-GROWTH"));
        when(planner.plan("M-2", "TEAM-MARKETING-GROWTH", "lanzar", TeamExecutionMode.ANALYSIS))
                .thenReturn(planned("TEAM-MARKETING-GROWTH"));
        var result = new AgentResult(
                "growth-content", "SEO_PLAN", "NOT_VALIDATED",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                "recomendación", 0.5);
        when(analysis.execute(any(), any())).thenReturn(new TeamExecutionResult.AgentOutcomes(
                List.of(AgentExecutionOutcome.success("growth-content", result))));
        when(contradictionDetector.detect(any(), anyDouble(), anyDouble())).thenReturn(List.of());

        executor.executeAsync("M-2", "lanzar").get();

        verify(development, never()).execute(any(), any());
        verify(memory).updateMission(eq("M-2"), eq(MissionStatus.AWAITING_INVESTOR), anyInt(), anyString(), eq("consolidado"));
    }

    @Test
    void anInvalidPlanFailsTheMission() throws Exception {
        when(memory.teamId("M-3")).thenReturn(Optional.of("TEAM-DEVELOPMENT"));
        when(planner.plan(anyString(), anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("El líder engineering no produjo un plan válido"));

        executor.executeAsync("M-3", "x").get();

        verify(memory).updateMission(eq("M-3"), eq(MissionStatus.FAILED), eq(100), anyString(),
                contains("no produjo un plan válido"));
    }

    @Test
    void aMissionWithoutTeamNeverCallsThePlanner() throws Exception {
        when(memory.teamId("M-4")).thenReturn(Optional.empty());

        executor.executeAsync("M-4", "x").get();

        verifyNoInteractions(planner);
    }
    private final RequirementsWriter writer = mock(RequirementsWriter.class);
    private final RequirementsMemoryService requirements = mock(RequirementsMemoryService.class);

    private static final com.aicompany.core.agent.model.RequirementsSpec SPEC = new com.aicompany.core.agent.model.RequirementsSpec(
            "CRM", List.of(), List.of(), List.of(new com.aicompany.core.agent.model.RequirementsSpec.Role("vendedor", "x")),
            List.of(new com.aicompany.core.agent.model.RequirementsSpec.UserStory("HU-01", "vendedor", "registrar clientes",
                            "seguirlos", List.of("c"), List.of(), List.of(new com.aicompany.core.agent.model.RequirementsSpec.Scenario(
                            "ESC-01", "alta", List.of("d"), List.of("c"), List.of("e")))),
                    new com.aicompany.core.agent.model.RequirementsSpec.UserStory("HU-02", "vendedor", "oportunidades",
                            "vender", List.of("c"), List.of(), List.of(new com.aicompany.core.agent.model.RequirementsSpec.Scenario(
                            "ESC-02", "op", List.of("d"), List.of("c"), List.of("e"))))),
            List.of(new com.aicompany.core.agent.model.RequirementsSpec.Increment(1, "MVP", List.of("HU-01")),
                    new com.aicompany.core.agent.model.RequirementsSpec.Increment(2, "Ops", List.of("HU-02"))));

    private void withAria() throws Exception {
        executor.setRequirements(writer, requirements);
        when(memory.teamId("M-9")).thenReturn(Optional.of("TEAM-DEVELOPMENT"));
        when(memory.instructionOf("M-9")).thenReturn(Optional.of("Un CRM"));
        when(writer.write(eq("M-9"), eq("Un CRM"), eq(Optional.empty()), isNull()))
                .thenReturn(new RequirementsWriter.Written(1, SPEC));
        when(requirements.approved("M-9")).thenReturn(Optional.of(new RequirementsMemoryService.Version(1,
                JsonMapper.builder().build().writeValueAsString(SPEC), "APPROVED", "", null)));
        when(development.execute(any(), any())).thenReturn(new TeamExecutionResult.Development("r", "estado"));
        when(memory.lastValidationStatus("M-9")).thenReturn(Optional.of("VERIFIED"));
    }

    // Spec 2026-10-03 Aria §1: tras Aria la misión espera al fundador; Neo no corre.
    @Test
    void aDevelopmentMissionStopsAfterAriaWaitingForApproval() throws Exception {
        withAria();
        when(companyPolicyService.activeValue(PolicyKey.SPEC_APPROVAL_REQUIRED)).thenReturn(1.0);

        executor.executeAsync("M-9", "Un CRM").get();

        verify(memory).updateMission(eq("M-9"), eq(MissionStatus.AWAITING_SPEC_APPROVAL), anyInt(), anyString(), anyString());
        verify(planner, never()).plan(any(), any(), any(), any());
        verify(alertMailService).send(contains("M-9"), anyString(), eq(false));
    }

    // Spec 2026-10-03 Aria §3 + Review Focus 3: aprobar (incluso con un executor nuevo tras reiniciar) planifica solo el incremento 1.
    @Test
    void approvingPlansOnlyTheFirstIncrement() throws Exception {
        withAria();
        when(planner.plan(eq("M-9"), eq("TEAM-DEVELOPMENT"), anyString(), any())).thenReturn(planned("TEAM-DEVELOPMENT"));

        executor.continueAfterApprovalAsync("M-9").get();

        verify(planner).plan(eq("M-9"), eq("TEAM-DEVELOPMENT"), argThat(i -> i.contains("INCREMENTO 1 de 2")
                && i.contains("HU-01 (vendedor)") && !i.contains("HU-02 (vendedor)") && i.contains("Un CRM")), any());
        // Revisión final (3): el incremento cuenta como construido solo al llegar a AWAITING_INVESTOR.
        var order = inOrder(requirements, memory);
        order.verify(requirements).setBuildingIncrement("M-9", 1);
        order.verify(memory).updateMission(eq("M-9"), eq(MissionStatus.AWAITING_INVESTOR), anyInt(), anyString(), anyString());
        order.verify(requirements).confirmBuildingIncrement("M-9");
        verify(requirements, never()).setBuiltIncrement(anyString(), anyInt());
    }

    // Verificado en vivo (MISSION-ARIA-CRM-1791093806, incremento 2): un incremento que llega a AWAITING_INVESTOR con la
    // validación FAILED no cuenta como construido (se puede reintentar).
    @Test
    void anIncrementThatIsNotVerifiedIsNotCountedAsBuilt() throws Exception {
        withAria();
        when(memory.lastValidationStatus("M-9")).thenReturn(Optional.of("FAILED"));
        when(planner.plan(eq("M-9"), eq("TEAM-DEVELOPMENT"), anyString(), any())).thenReturn(planned("TEAM-DEVELOPMENT"));

        executor.continueAfterApprovalAsync("M-9").get();

        verify(memory).updateMission(eq("M-9"), eq(MissionStatus.AWAITING_INVESTOR), anyInt(), anyString(), anyString());
        verify(requirements, never()).confirmBuildingIncrement(anyString());
    }

    // Revisión final (3): si la construcción falla, el incremento no queda como construido.
    @Test
    void aFailedIncrementIsNotCountedAsBuilt() throws Exception {
        withAria();
        when(planner.plan(eq("M-9"), eq("TEAM-DEVELOPMENT"), anyString(), any())).thenReturn(planned("TEAM-DEVELOPMENT"));
        when(development.execute(any(), any())).thenThrow(new IllegalStateException("sandbox caído"));

        executor.continueAfterApprovalAsync("M-9").get();

        verify(memory).updateMission(eq("M-9"), eq(MissionStatus.FAILED), anyInt(), anyString(), anyString());
        verify(requirements, never()).confirmBuildingIncrement(anyString());
    }

    // Revisión final (6): aprobar mueve la misión en el acto (no queda en AWAITING_SPEC_APPROVAL mientras espera el pool),
    // y un pool lleno la deja FAILED en vez de trabada.
    @Test
    void approvalMovesTheMissionBeforeTheThreadAndAFullPoolFailsIt() throws Exception {
        withAria();
        var idle = new MissionExecutor(memory, new AgentTaskBatchRunner(memory, runtime, events), ceoService, companyMemory,
                promptMemory, "qwen2.5-coder:14b", r -> { }, events, JsonMapper.builder().build(), contradictionDetector,
                companyPolicyService, opportunityMemory, alertMailService, planner, List.of(analysis, development));
        idle.setRequirements(writer, requirements);
        idle.continueAfterApprovalAsync("M-9");
        verify(memory).updateMission(eq("M-9"), eq(MissionStatus.PLANNING), anyInt(), anyString(), anyString());

        var full = new MissionExecutor(memory, new AgentTaskBatchRunner(memory, runtime, events), ceoService, companyMemory,
                promptMemory, "qwen2.5-coder:14b", r -> { throw new java.util.concurrent.RejectedExecutionException("lleno"); },
                events, JsonMapper.builder().build(), contradictionDetector, companyPolicyService, opportunityMemory,
                alertMailService, planner, List.of(analysis, development));
        full.setRequirements(writer, requirements);
        assertTrue(full.buildIncrementAsync("M-9", 2, 2).isCompletedExceptionally());
        verify(memory).updateMission(eq("M-9"), eq(MissionStatus.FAILED), anyInt(), anyString(), anyString());
    }

    @Test
    void withTheSwitchOffTheValidSpecGoesStraightToNeo() throws Exception {
        withAria();
        when(companyPolicyService.activeValue(PolicyKey.SPEC_APPROVAL_REQUIRED)).thenReturn(0.0);
        when(planner.plan(eq("M-9"), eq("TEAM-DEVELOPMENT"), anyString(), any())).thenReturn(planned("TEAM-DEVELOPMENT"));

        executor.executeAsync("M-9", "Un CRM").get();

        verify(requirements).approve("M-9", 1, "policy");
        // Revisión final Aria (14): sin aprobación del fundador también quedan los eventos de requisitos listos y aprobados.
        verify(events).publish(eq("EMPRESA_REQUIREMENTS_READY"), eq("M-9"), isNull(), eq("product-owner"), anyMap());
        verify(events).publish(eq("EMPRESA_REQUIREMENTS_APPROVED"), eq("M-9"), isNull(), eq("policy"),
                argThat(d -> "policy".equals(d.get("approvedBy")) && Integer.valueOf(1).equals(d.get("version"))));
        verify(planner).plan(eq("M-9"), eq("TEAM-DEVELOPMENT"), contains("INCREMENTO 1"), any());
    }

    // Revisión final (4, 5): Neo planifica cada incremento con sus HU (spec §3), como ronda nueva; el bloque va una vez.
    @Test
    void buildingIncrementTwoIsPlannedByNeoWithItsStoriesAsARound() throws Exception {
        withAria();
        // Verificado en vivo (incremento 2, segunda corrida): el último plan guardado era el de una corrida fallida con solo
        // el contexto nuevo; Neo debe recibir la unión de TODOS los planes anteriores.
        var json = JsonMapper.builder().build();
        var first = new TeamPlan("plan 1", null, null, List.of(), List.of(), "DOTNET_APP",
                List.of(new TeamPlan.BoundedContext("Clientes", "c")), List.of());
        var second = new TeamPlan("plan 2", null, null, List.of(), List.of(), "DOTNET_APP",
                List.of(new TeamPlan.BoundedContext("Oportunidades", "o")), List.of());
        when(memory.teamPlanJsons("M-9")).thenReturn(List.of(json.writeValueAsString(first), json.writeValueAsString(second)));
        when(planner.plan(eq("M-9"), eq("TEAM-DEVELOPMENT"), anyString(), any(), eq(2), any())).thenReturn(planned("TEAM-DEVELOPMENT"));

        executor.buildIncrementAsync("M-9", 2, 2).get();

        // Verificado en vivo (incremento 2): Neo recibe el plan del producto existente para conservarlo.
        verify(planner).plan(eq("M-9"), eq("TEAM-DEVELOPMENT"), argThat(i -> i.contains("INCREMENTO 2 de 2")
                && i.contains("HU-02 (vendedor)") && !i.contains("HU-01 (vendedor)") && i.contains("Un CRM")), any(), eq(2),
                argThat(p -> p != null && p.contextNames().equals(List.of("Clientes", "Oportunidades"))));
        verify(development).execute(argThat(c -> c.round() == 2 && c.instruction().contains("INCREMENTO 2 de 2")
                && c.plan().workTasks().stream().noneMatch(t -> t.objective().contains("INCREMENTO"))), any());
        verify(requirements).addIncrementRound("M-9", 2, 2);
        verify(requirements).setBuildingIncrement("M-9", 2);
    }

    // Revisión final (2a/b): una ronda de evidencia de una misión con requisitos conserva el bloque del incremento en curso.
    @Test
    void anEvidenceRoundKeepsTheIncrementBlock() throws Exception {
        withAria();
        when(requirements.currentIncrement("M-9")).thenReturn(2);
        when(memory.tasks("M-9")).thenReturn(List.of());
        when(memory.lastTeamPlanJson("M-9")).thenReturn(Optional.of(JsonMapper.builder().build()
                .writeValueAsString(planned("TEAM-DEVELOPMENT").plan())));
        when(planner.teamSnapshot("TEAM-DEVELOPMENT")).thenReturn(planned("TEAM-DEVELOPMENT").team());
        when(ceoService.routeInvestorFeedback(any(), any(), any(), anyList(), any())).thenReturn(java.util.Map.of());

        executor.reexecuteAsync("M-9", "Un CRM", 3, "falta validar el monto").get();

        verify(development).execute(argThat(c -> c.instruction().contains("INCREMENTO 2 de 2")
                && c.instruction().contains("Un CRM")), any());
    }

    // Revisión final (2c): sin requisitos aprobados, pedir más evidencia vuelve a Aria y nunca a Neo (spec §3).
    @Test
    void anEvidenceRoundWithoutApprovedRequirementsGoesBackToAria() throws Exception {
        withAria();
        when(requirements.approved("M-9")).thenReturn(Optional.empty());
        when(requirements.latest("M-9")).thenReturn(Optional.empty());
        when(memory.tasks("M-9")).thenReturn(List.of());
        when(writer.write(eq("M-9"), eq("Un CRM"), any(), eq("más detalle")))
                .thenReturn(new RequirementsWriter.Written(2, SPEC));

        executor.reexecuteAsync("M-9", "Un CRM", 1, "más detalle").get();

        verify(memory).updateMission(eq("M-9"), eq(MissionStatus.AWAITING_SPEC_APPROVAL), anyInt(), anyString(), anyString());
        verify(planner, never()).plan(any(), any(), any(), any(), anyInt());
        verify(planner, never()).plan(any(), any(), any(), any());
    }

    // Revisión final Aria (16): una misión anterior a Aria (sin requisitos, con plan de Neo) reutiliza ese plan sin Aria.
    @Test
    void anEvidenceRoundOfAMissionThatPredatesAriaReusesThePlanWithoutAria() throws Exception {
        withAria();
        when(requirements.approved("M-9")).thenReturn(Optional.empty());
        when(requirements.predatesRequirements("M-9")).thenReturn(true);
        when(memory.tasks("M-9")).thenReturn(List.of());
        when(memory.lastTeamPlanJson("M-9")).thenReturn(Optional.of(JsonMapper.builder().build()
                .writeValueAsString(planned("TEAM-DEVELOPMENT").plan())));
        when(planner.teamSnapshot("TEAM-DEVELOPMENT")).thenReturn(planned("TEAM-DEVELOPMENT").team());
        when(ceoService.routeInvestorFeedback(any(), any(), any(), anyList(), any())).thenReturn(java.util.Map.of());

        executor.reexecuteAsync("M-9", "Un CRM", 1, "más detalle").get();

        verify(writer, never()).write(any(), any(), any(), any());
        verify(planner, never()).plan(any(), any(), any(), any(), anyInt());
        verify(development).execute(argThat(c -> !c.instruction().contains("INCREMENTO")), any());
    }

    // FALTANTES (Bloque 1, m-2): un plan guardado que ya no cumple las reglas actuales (p. ej. de TEAM-ENGINEERING, con
    // Neo escribiendo código, o con un agente apagado) no se reutiliza: Neo replanifica con el pedido del inversionista.
    @Test
    void anEvidenceRoundWithASavedPlanThatNoLongerValidatesIsReplanned() throws Exception {
        withAria();
        when(requirements.predatesRequirements("M-9")).thenReturn(true);
        when(memory.tasks("M-9")).thenReturn(List.of());
        when(memory.lastTeamPlanJson("M-9")).thenReturn(Optional.of(JsonMapper.builder().build()
                .writeValueAsString(planned("TEAM-DEVELOPMENT").plan())));
        when(planner.revalidate(eq("M-9"), eq("TEAM-DEVELOPMENT"), any(), any()))
                .thenReturn(List.of("engineering (TECH_LEAD) no escribe código."));
        when(planner.plan(eq("M-9"), eq("TEAM-DEVELOPMENT"), anyString(), any(), eq(1))).thenReturn(planned("TEAM-DEVELOPMENT"));
        when(ceoService.routeInvestorFeedback(any(), any(), any(), anyList(), any())).thenReturn(java.util.Map.of());

        executor.reexecuteAsync("M-9", "Un CRM", 1, "más detalle").get();

        verify(planner).plan(eq("M-9"), eq("TEAM-DEVELOPMENT"), contains("más detalle"), any(), eq(1));
        verify(development).execute(any(), any());
    }

    // Spec misiones durables §2: retomar la generación de la ronda 0 usa el plan guardado de esa ronda (sin replanificar).
    @Test
    void resumingTheOriginalRoundUsesItsSavedPlan() throws Exception {
        withAria();
        when(memory.planJson(TaskIds.planTask("M-9", "engineering", 0))).thenReturn(Optional.of(
                JsonMapper.builder().build().writeValueAsString(planned("TEAM-DEVELOPMENT").plan())));
        when(planner.teamSnapshot("TEAM-DEVELOPMENT")).thenReturn(planned("TEAM-DEVELOPMENT").team());

        executor.resumeAsync("M-9", new com.aicompany.core.recovery.ResumePoint(
                com.aicompany.core.recovery.ResumePoint.Stage.GENERATE, 0,
                com.aicompany.core.recovery.ResumePoint.RoundKind.ORIGINAL, null)).get();

        verify(planner, never()).plan(any(), any(), any(), any());
        verify(development).execute(argThat(c -> c.round() == 0 && c.instruction().contains("INCREMENTO 1")), any());
        verify(events).publish(eq("EMPRESA_MISSION_RESUMED"), eq("M-9"), isNull(), eq("system"), anyMap());
    }

    // Spec misiones durables §2: sin plan, Neo planifica la misma ronda e incremento.
    @Test
    void resumingWithoutPlanPlansTheSameIncrement() throws Exception {
        withAria();
        when(planner.teamSnapshot("TEAM-DEVELOPMENT")).thenReturn(planned("TEAM-DEVELOPMENT").team());
        when(planner.plan(eq("M-9"), eq("TEAM-DEVELOPMENT"), anyString(), any(), eq(2), any())).thenReturn(planned("TEAM-DEVELOPMENT"));
        executor.resumeAsync("M-9", new com.aicompany.core.recovery.ResumePoint(
                com.aicompany.core.recovery.ResumePoint.Stage.PLAN, 2,
                com.aicompany.core.recovery.ResumePoint.RoundKind.INCREMENT, 2)).get();
        verify(planner).plan(eq("M-9"), eq("TEAM-DEVELOPMENT"), contains("INCREMENTO 2"), any(), eq(2), any());
    }

    // Review Focus 4: una corrección cortada se retoma con su pedido guardado (misma ronda).
    @Test
    void resumingACorrectionRoundUsesItsSavedRequest() throws Exception {
        withAria();
        var recovery = mock(com.aicompany.core.recovery.RecoveryMemoryService.class);
        executor.setRecoveryMemory(recovery);
        when(recovery.correctionRound("M-9", 3)).thenReturn(Optional.of(
                new com.aicompany.core.recovery.CorrectionRound(3, 1, "CORRECCIÓN AUTOMÁTICA (Java):\n- x", 2, false)));
        when(memory.tasks("M-9")).thenReturn(List.of());
        when(ceoService.routeInvestorFeedback(any(), any(), any(), anyList(), any())).thenReturn(java.util.Map.of());
        when(memory.lastTeamPlanJson("M-9")).thenReturn(Optional.of(JsonMapper.builder().build()
                .writeValueAsString(planned("TEAM-DEVELOPMENT").plan())));
        when(planner.teamSnapshot("TEAM-DEVELOPMENT")).thenReturn(planned("TEAM-DEVELOPMENT").team());

        executor.resumeAsync("M-9", new com.aicompany.core.recovery.ResumePoint(
                com.aicompany.core.recovery.ResumePoint.Stage.GENERATE, 3,
                com.aicompany.core.recovery.ResumePoint.RoundKind.CORRECTION, null)).get();

        verify(development).execute(argThat(c -> c.round() == 3
                && c.plan().workTasks().stream().allMatch(t -> t.objective().contains("CORRECCIÓN AUTOMÁTICA"))), any());
        verify(recovery, never()).addCorrectionRound(any(), any());
    }

    private com.aicompany.core.recovery.RecoveryMemoryService strategyRecovery(com.aicompany.core.recovery.CorrectionStrategist strategist)
            throws Exception {
        withAria();
        var recovery = mock(com.aicompany.core.recovery.RecoveryMemoryService.class);
        executor.setRecoveryMemory(recovery);
        executor.setCorrectionStrategist(strategist);
        when(recovery.correctionRounds("M-9")).thenReturn(List.of(
                new com.aicompany.core.recovery.CorrectionRound(1, 1, "a", 1, false, List.of("e1")),
                new com.aicompany.core.recovery.CorrectionRound(2, 1, "b", 1, false, List.of("e1")),
                new com.aicompany.core.recovery.CorrectionRound(3, 1, "CORRECCIÓN AUTOMÁTICA (Java):\n- x", 1, false, List.of("e1"))));
        when(memory.tasks("M-9")).thenReturn(List.of());
        when(ceoService.routeInvestorFeedback(any(), any(), any(), anyList(), any())).thenReturn(java.util.Map.of());
        when(memory.lastTeamPlanJson("M-9")).thenReturn(Optional.of(JsonMapper.builder().build()
                .writeValueAsString(planned("TEAM-DEVELOPMENT").plan())));
        when(planner.teamSnapshot("TEAM-DEVELOPMENT")).thenReturn(planned("TEAM-DEVELOPMENT").team());
        return recovery;
    }

    // Revisión 2026-10-06 §2: con estrategia, se antepone al pedido, se guarda y se publica.
    @Test
    void aCorrectionWithStrategyPrependsSavesAndPublishesIt() throws Exception {
        var strategist = mock(com.aicompany.core.recovery.CorrectionStrategist.class);
        var recovery = strategyRecovery(strategist);
        when(strategist.propose(eq("M-9"), eq(3), any(), anyList(), anyString())).thenReturn(Optional.of(
                new com.aicompany.core.recovery.CorrectionStrategy("fp", 3, "Reescribe X desde cero", List.of("https://a"))));

        executor.correctAsync("M-9", 3, "CORRECCIÓN AUTOMÁTICA (Java):\n- x", true).get();

        verify(strategist).propose(eq("M-9"), eq(3), argThat(r -> r.failures().equals(List.of("e1"))),
                argThat(p -> p.size() == 2), anyString());
        verify(recovery).addStrategy(eq("M-9"), any());
        verify(recovery).updateCorrectionRequest(eq("M-9"), eq(3),
                startsWith("ESTRATEGIA NUEVA (error persistente): Reescribe X desde cero"));
        verify(events).publish(eq("EMPRESA_CORRECTION_STRATEGY_PROPOSED"), eq("M-9"), isNull(), eq("engineering"), anyMap());
        verify(development).execute(argThat(c -> c.round() == 3 && c.plan().workTasks().stream()
                .allMatch(t -> t.objective().contains("ESTRATEGIA NUEVA"))), any());
    }

    // Review Focus 3: retomar una corrección cuya estrategia ya quedó guardada no vuelve a buscar.
    @Test
    void resumingACorrectionWithASavedStrategyDoesNotSearchAgain() throws Exception {
        var strategist = mock(com.aicompany.core.recovery.CorrectionStrategist.class);
        var recovery = strategyRecovery(strategist);
        when(recovery.correctionRound("M-9", 3)).thenReturn(Optional.of(new com.aicompany.core.recovery.CorrectionRound(
                3, 1, "ESTRATEGIA NUEVA (error persistente): X\n\nCORRECCIÓN AUTOMÁTICA (Java):\n- x", 1, false)));

        executor.resumeAsync("M-9", new com.aicompany.core.recovery.ResumePoint(
                com.aicompany.core.recovery.ResumePoint.Stage.GENERATE, 3,
                com.aicompany.core.recovery.ResumePoint.RoundKind.CORRECTION, null)).get();

        verifyNoInteractions(strategist);
        verify(development).execute(argThat(c -> c.round() == 3), any());
    }

    // Review Focus 1: sin estrategia, la ronda corre igual.
    @Test
    void withoutAStrategyTheRoundRunsAnyway() throws Exception {
        var strategist = mock(com.aicompany.core.recovery.CorrectionStrategist.class);
        var recovery = strategyRecovery(strategist);
        when(strategist.propose(any(), anyInt(), any(), anyList(), anyString())).thenReturn(Optional.empty());

        executor.correctAsync("M-9", 3, "CORRECCIÓN AUTOMÁTICA (Java):\n- x", true).get();

        verify(development).execute(argThat(c -> c.round() == 3), any());
        verify(recovery, never()).addStrategy(any(), any());
        verify(recovery, never()).updateCorrectionRequest(any(), anyInt(), any());
    }

    // Spec misiones durables §1: discovery y otros equipos quedan INTERRUPTED.
    @Test
    void anInterruptPointLeavesTheMissionInterrupted() throws Exception {
        executor.resumeAsync("M-9", new com.aicompany.core.recovery.ResumePoint(
                com.aicompany.core.recovery.ResumePoint.Stage.INTERRUPT, 0,
                com.aicompany.core.recovery.ResumePoint.RoundKind.ORIGINAL, null)).get();
        verify(memory).updateMission(eq("M-9"), eq(MissionStatus.INTERRUPTED), anyInt(), anyString(),
                matches("(?s).*el \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2} \\(hora de Colombia\\).*"));
    }

    // Spec misiones durables §3: la corrección es una ronda de equipo con el reporte como pedido.
    @Test
    void aCorrectionRunsTheTeamRoundWithTheReport() throws Exception {
        withAria();
        when(memory.tasks("M-9")).thenReturn(List.of());
        when(ceoService.routeInvestorFeedback(any(), any(), any(), anyList(), any())).thenReturn(java.util.Map.of());
        when(memory.lastTeamPlanJson("M-9")).thenReturn(Optional.of(JsonMapper.builder().build()
                .writeValueAsString(planned("TEAM-DEVELOPMENT").plan())));
        when(planner.teamSnapshot("TEAM-DEVELOPMENT")).thenReturn(planned("TEAM-DEVELOPMENT").team());

        executor.correctAsync("M-9", 2, "CORRECCIÓN AUTOMÁTICA (Java):\n- ESC-05").get();

        verify(events).publish(eq("EMPRESA_MISSION_CORRECTION_STARTED"), eq("M-9"), isNull(), eq("system"), anyMap());
        verify(development).execute(argThat(c -> c.round() == 2), any());
    }

    // Spec misiones durables §2.1: sin repositorio, una ronda > 0 del Development Group queda BLOCKED con el motivo.
    @Test
    void aRoundWhoseRepositoryWasLostIsBlocked() throws Exception {
        withAria();
        var workspace = mock(DevelopmentWorkspaceService.class);
        executor.setWorkspace(workspace);
        when(workspace.headIfAny("M-9")).thenReturn(Optional.empty());
        when(memory.tasks("M-9")).thenReturn(List.of(new AgentTask("M-9-ENGINEERING", "M-9", "engineering", "ARCHITECTURE",
                "COMPLETED", "{}", java.time.Instant.now(), "WORK", "/w", "a".repeat(40), List.of(), null, null)));
        when(ceoService.routeInvestorFeedback(any(), any(), any(), anyList(), any())).thenReturn(java.util.Map.of());
        when(memory.lastTeamPlanJson("M-9")).thenReturn(Optional.of(JsonMapper.builder().build()
                .writeValueAsString(planned("TEAM-DEVELOPMENT").plan())));
        when(planner.teamSnapshot("TEAM-DEVELOPMENT")).thenReturn(planned("TEAM-DEVELOPMENT").team());

        executor.correctAsync("M-9", 2, "CORRECCIÓN AUTOMÁTICA (Java):\n- ESC-05").get();

        verify(development, never()).execute(any(), any());
        verify(memory).updateMission(eq("M-9"), eq(MissionStatus.BLOCKED), anyInt(), anyString(),
                eq(MissionExecutor.NO_WORKSPACE));
        verify(events).publish(eq("EMPRESA_MISSION_BLOCKED"), eq("M-9"), isNull(), eq("system"), anyMap());
    }

    // Revisión 2026-10-06 §6: sin repositorio que nunca existió (ninguna tarea con commit) la ronda sigue.
    @Test
    void aRoundWithoutRepositoryThatNeverExistedRuns() throws Exception {
        withAria();
        var workspace = mock(DevelopmentWorkspaceService.class);
        executor.setWorkspace(workspace);
        when(workspace.headIfAny("M-9")).thenReturn(Optional.empty());
        when(memory.tasks("M-9")).thenReturn(List.of());
        when(ceoService.routeInvestorFeedback(any(), any(), any(), anyList(), any())).thenReturn(java.util.Map.of());
        when(memory.lastTeamPlanJson("M-9")).thenReturn(Optional.of(JsonMapper.builder().build()
                .writeValueAsString(planned("TEAM-DEVELOPMENT").plan())));
        when(planner.teamSnapshot("TEAM-DEVELOPMENT")).thenReturn(planned("TEAM-DEVELOPMENT").team());

        executor.correctAsync("M-9", 2, "CORRECCIÓN AUTOMÁTICA (Java):\n- ESC-05").get();

        verify(development).execute(argThat(c -> c.round() == 2), any());
        verify(memory, never()).updateMission(eq("M-9"), eq(MissionStatus.BLOCKED), anyInt(), anyString(), anyString());
    }

    @Test
    void aRoundAfterTheFirstWithRepositoryRuns() throws Exception {
        withAria();
        var workspace = mock(DevelopmentWorkspaceService.class);
        executor.setWorkspace(workspace);
        when(workspace.headIfAny("M-9")).thenReturn(Optional.of("abc123"));
        when(memory.tasks("M-9")).thenReturn(List.of());
        when(ceoService.routeInvestorFeedback(any(), any(), any(), anyList(), any())).thenReturn(java.util.Map.of());
        when(memory.lastTeamPlanJson("M-9")).thenReturn(Optional.of(JsonMapper.builder().build()
                .writeValueAsString(planned("TEAM-DEVELOPMENT").plan())));
        when(planner.teamSnapshot("TEAM-DEVELOPMENT")).thenReturn(planned("TEAM-DEVELOPMENT").team());

        executor.correctAsync("M-9", 2, "CORRECCIÓN AUTOMÁTICA (Java):\n- ESC-05").get();

        verify(development).execute(argThat(c -> c.round() == 2), any());
    }

    /** Revisión 2026-10-06 §5: executor con la estrategia de desarrollo real (mock de la clase) para verifyHead. */
    private MissionExecutor humanVerifyExecutor(DevelopmentTeamStrategy dev) throws Exception {
        when(dev.mode()).thenReturn(TeamExecutionMode.DEVELOPMENT);
        var exec = new MissionExecutor(
                memory, new AgentTaskBatchRunner(memory, runtime, events), ceoService, companyMemory, promptMemory,
                "qwen2.5-coder:14b", Runnable::run, events, JsonMapper.builder().build(), contradictionDetector,
                companyPolicyService, opportunityMemory, alertMailService, planner, List.of(analysis, dev));
        exec.setRequirements(writer, requirements);
        when(memory.teamId("M-9")).thenReturn(Optional.of("TEAM-DEVELOPMENT"));
        when(memory.instructionOf("M-9")).thenReturn(Optional.of("Un CRM"));
        when(memory.evidenceRound("M-9")).thenReturn(3);
        when(memory.lastTeamPlanJson("M-9")).thenReturn(Optional.of(JsonMapper.builder().build()
                .writeValueAsString(planned("TEAM-DEVELOPMENT").plan())));
        when(planner.teamSnapshot("TEAM-DEVELOPMENT")).thenReturn(planned("TEAM-DEVELOPMENT").team());
        when(memory.tasks("M-9")).thenReturn(List.of(new AgentTask("M-9-HUMAN-VERIFY-1", "M-9", "qa", "HUMAN_VERIFY",
                "COMPLETED", "x", java.time.Instant.now(), "VALIDATION", null, null, List.of(), null, null)));
        return exec;
    }

    // Revisión 2026-10-06 §5 (manos humanas): una tarea VALIDATION nueva, verifyHead sin modelos y AWAITING_INVESTOR.
    @Test
    void aHumanVerificationRunsVerifyHeadWithoutModelsAndAwaitsTheInvestor() throws Exception {
        var dev = mock(DevelopmentTeamStrategy.class);
        var exec = humanVerifyExecutor(dev);
        when(dev.verifyHead(any(), eq("M-9-HUMAN-VERIFY-2")))
                .thenReturn(new TeamExecutionResult.Development("r", "ESTADO VERIFICABLE (generado por Forjai)"));
        when(memory.lastValidationStatus("M-9")).thenReturn(Optional.of("VERIFIED"));

        exec.verifyAsync("M-9").get();

        verify(memory).updateMission(eq("M-9"), eq(MissionStatus.EVALUATING), anyInt(), anyString(), anyString());
        verify(memory).createTask("M-9-HUMAN-VERIFY-2", "M-9", "qa", "HUMAN_VERIFY", "VALIDATION");
        verify(dev).verifyHead(argThat(c -> c.missionId().equals("M-9") && c.round() == 3
                && c.team().teamId().equals("TEAM-DEVELOPMENT")), eq("M-9-HUMAN-VERIFY-2"));
        verify(dev, never()).execute(any(), any());
        verifyNoInteractions(ceoService);
        verify(memory).updateMission(eq("M-9"), eq(MissionStatus.AWAITING_INVESTOR), anyInt(), anyString(),
                contains("ESTADO VERIFICABLE"));
        // Mismo cierre que una ronda: VERIFIED confirma el incremento.
        verify(requirements).confirmBuildingIncrement("M-9");
    }

    @Test
    void aFailedHumanVerificationAlsoAwaitsTheInvestor() throws Exception {
        var dev = mock(DevelopmentTeamStrategy.class);
        var exec = humanVerifyExecutor(dev);
        when(dev.verifyHead(any(), anyString())).thenReturn(new TeamExecutionResult.Development("r", "ESTADO"));
        when(memory.lastValidationStatus("M-9")).thenReturn(Optional.of("FAILED"));

        exec.verifyAsync("M-9").get();

        verify(memory).updateMission(eq("M-9"), eq(MissionStatus.AWAITING_INVESTOR), anyInt(), anyString(), anyString());
        verify(requirements, never()).confirmBuildingIncrement(anyString());
        verify(memory, never()).updateMission(eq("M-9"), eq(MissionStatus.FAILED), anyInt(), anyString(), anyString());
    }
}
