package com.aicompany.core.service;

import java.util.Map;

import com.aicompany.core.agent.model.TeamPlan;

import com.aicompany.core.model.TeamSnapshot;

import com.aicompany.core.model.TaskIds;

import com.aicompany.core.agent.model.AgentResult;
import com.aicompany.core.agent.validation.ContradictionDetector;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.AgentExecutionOutcome;
import com.aicompany.core.model.FinancialCriteriaResponse;
import com.aicompany.core.model.MissionStatus;
import com.aicompany.core.model.PolicyKey;
import com.aicompany.core.model.TeamExecutionMode;
import com.aicompany.core.model.TeamExecutionResult;
import com.aicompany.core.model.TeamMissionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Service
public class MissionExecutor {

    private static final Logger log =
            LoggerFactory.getLogger(MissionExecutor.class);

    private final MissionMemoryService memory;
    private final AgentTaskBatchRunner batchRunner;
    private final CeoService ceoService;
    private final CompanyMemoryService companyMemory;
    private final PromptMemoryService promptMemory;
    private final String defaultCeoModel;
    private final Executor orchestratorExecutor;
    private final CompanyEventPublisher events;
    private final JsonMapper jsonMapper;
    private final ContradictionDetector contradictionDetector;
    private final CompanyPolicyService companyPolicyService;
    private final OpportunityMemoryService opportunityMemory;
    private final AlertMailService alertMailService;
    private final TeamWorkPlanner teamWorkPlanner;
    private final List<TeamExecutionStrategy> teamStrategies;

    public MissionExecutor(
            MissionMemoryService memory,
            AgentTaskBatchRunner batchRunner,
            CeoService ceoService,
            CompanyMemoryService companyMemory,
            PromptMemoryService promptMemory,
            @Value("${ollama.ceo-model}") String defaultCeoModel,
            @Qualifier("missionOrchestratorExecutor")
            Executor orchestratorExecutor,
            CompanyEventPublisher events,
            JsonMapper jsonMapper,
            ContradictionDetector contradictionDetector,
            CompanyPolicyService companyPolicyService,
            OpportunityMemoryService opportunityMemory,
            AlertMailService alertMailService,
            TeamWorkPlanner teamWorkPlanner,
            List<TeamExecutionStrategy> teamStrategies) {

        this.memory = memory;
        this.batchRunner = batchRunner;
        this.ceoService = ceoService;
        this.companyMemory = companyMemory;
        this.promptMemory = promptMemory;
        this.defaultCeoModel = defaultCeoModel;
        this.orchestratorExecutor = orchestratorExecutor;
        this.events = events;
        this.jsonMapper = jsonMapper;
        this.contradictionDetector = contradictionDetector;
        this.companyPolicyService = companyPolicyService;
        this.opportunityMemory = opportunityMemory;
        this.alertMailService = alertMailService;
        this.teamWorkPlanner = teamWorkPlanner;
        this.teamStrategies = teamStrategies;
    }

    private ProductAutomation productAutomation;

    /** Spec catálogo §6 A (2026-09-28). Setter opcional: los tests que no lo usan no cambian. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setProductAutomation(ProductAutomation productAutomation) {
        this.productAutomation = productAutomation;
    }

    private ProductOrchestrator productOrchestrator;

    /**
     * Spec orquestador §2 (2026-09-28): hilo propio, así el orquestador (que puede lanzar misiones) nunca bloquea ni
     * ocupa el pool de misiones.
     */
    private static final java.util.concurrent.ExecutorService ORCHESTRATOR_NOTIFIER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                var thread = new Thread(r, "orchestrator-notifier");
                thread.setDaemon(true);
                return thread;
            });

    /** {@code @Lazy}: el orquestador depende de MissionService, que depende de este executor (ciclo de beans). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setProductOrchestrator(@org.springframework.context.annotation.Lazy ProductOrchestrator productOrchestrator) {
        this.productOrchestrator = productOrchestrator;
    }

    private com.aicompany.core.recovery.MissionRecoveryService recoveryService;

    /** {@code @Lazy}: el servicio de recuperación depende de este executor (ciclo de beans). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRecoveryService(@org.springframework.context.annotation.Lazy
                                   com.aicompany.core.recovery.MissionRecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    /** El orquestador tampoco puede tumbar una misión: se avisa en otro hilo y un fallo solo se registra. */
    private void notifyOrchestrator(String missionId) {
        if (productOrchestrator == null) {
            return;
        }
        ORCHESTRATOR_NOTIFIER.execute(() -> {
            try {
                productOrchestrator.onMissionFinished(missionId);
            } catch (Exception ex) {
                log.warn("MISSION {} - orchestrator notification failed: {}", missionId, ex.getMessage());
            }
        });
    }

    /** El catálogo nunca puede tumbar una misión: si falla, solo se registra. */
    private void catalog(String missionId, String what, Runnable action) {
        if (productAutomation == null) {
            return;
        }
        try {
            action.run();
        } catch (Exception ex) {
            log.warn("MISSION {} - catalog automation ({}) failed: {}", missionId, what, ex.getMessage());
        }
    }

    public CompletableFuture<Void> executeAsync(
            String missionId,
            String instruction) {

        log.info(
                "MISSION {} - submitting orchestration",
                missionId
        );

        events.publishMission(
                "EMPRESA_MISSION_STARTED",
                missionId,
                "PLANNING",
                0,
                "Iniciando",
                "Orquestación enviada al runtime."
        );

        try {

            // Costo real por misión (2026-10-04): las llamadas del hilo de la misión quedan atribuidas a ella.
            return runLive(missionId, () -> executeInternal(
                    missionId,
                    instruction
            ));

        } catch (Exception ex) {

            log.error(
                    "MISSION {} - could not submit orchestration",
                    missionId,
                    ex
            );

            safeFail(
                    missionId,
                    ex
            );

            return CompletableFuture.failedFuture(ex);
        }
    }

    /**
     * Ronda de evidencia (spec 2026-09-16, revisión 2026-09-27): el inversionista pidió más evidencia. Discovery
     * re-ejecuta las 5 tareas; un equipo reutiliza el último plan del líder (o replanifica si no lo hay). Cada agente
     * recibe su parte del pedido (la reparte el CEO) antepuesta al objetivo, y los ids llevan el sufijo -R<n>.
     */
    public CompletableFuture<Void> reexecuteAsync(String missionId, String instruction, int round, String investorRequest) {

        log.info("MISSION {} - submitting evidence round {}", missionId, round);

        events.publish("EMPRESA_MISSION_EVIDENCE_ROUND_STARTED", missionId, null, "human",
                Map.of("evidenceRound", round, "reasoning", investorRequest == null ? "" : investorRequest));

        // Síncrono (revisión final): la misión deja AWAITING_INVESTOR/FAILED antes de volver a MissionService, así un
        // segundo pedido en el mismo instante se rechaza en vez de lanzar otra ronda en paralelo.
        advanceMission(missionId, MissionStatus.DELEGATING, 10, "Ronda de evidencia " + round,
                "Arrancando la ronda " + round + " con el pedido del inversionista.");

        try {
            return runLive(missionId, () -> reexecuteInternal(missionId, instruction, round, investorRequest));
        } catch (Exception ex) {
            log.error("MISSION {} - could not submit evidence round {}", missionId, round, ex);
            safeFail(missionId, ex);
            return CompletableFuture.failedFuture(ex);
        }
    }

    private RequirementsWriter requirementsWriter;
    private RequirementsMemoryService requirementsMemory;

    /** Spec 2026-10-03 Aria: opcional (sin él, el Development Group arranca por el plan de Neo como antes). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRequirements(RequirementsWriter requirementsWriter, RequirementsMemoryService requirementsMemory) {
        this.requirementsWriter = requirementsWriter;
        this.requirementsMemory = requirementsMemory;
    }

    private boolean ariaApplies(TeamExecutionMode mode) {
        return mode == TeamExecutionMode.DEVELOPMENT && requirementsWriter != null && requirementsMemory != null;
    }

    static String investorBlock(String routedRequest) {
        return "SOLICITUD DEL INVERSIONISTA (ronda de evidencia):\n" + routedRequest;
    }

    private void reexecuteInternal(String missionId, String instruction, int round, String investorRequest) {

        try {
            var priorResults = priorRoundResults(missionId, round);
            var ceoModel = companyMemory.agentModel("ceo", defaultCeoModel);
            var teamId = memory.teamId(missionId).orElse(null);

            if (teamId != null) {
                reexecuteTeamMission(missionId, instruction, teamId, round, investorRequest, priorResults, ceoModel);
                return;
            }

            advanceMission(missionId, MissionStatus.DELEGATING, 10, "Ronda de evidencia " + round,
                    "Re-ejecutando Sales, Product, Finance, Engineering y QA con el pedido del inversionista.");

            var seedCapitalUsd = companyPolicyService.activeValue(PolicyKey.SEED_CAPITAL_USD);
            var base = discoveryDefinitions(seedCapitalUsd, memory.financialCriteria(missionId).orElse(null));
            var routed = ceoService.routeInvestorFeedback(instruction, priorResults, investorRequest,
                    base.stream().map(AgentTaskBatchRunner.AgentTaskDefinition::agentId).toList(), ceoModel);
            var definitions = base.stream()
                    .map(d -> new AgentTaskBatchRunner.AgentTaskDefinition(d.agentId(), d.action(),
                            investorBlock(routed.get(d.agentId())) + "\n" + d.objective(), d.kind()))
                    .toList();

            var outcomes = batchRunner.run(missionId, instruction, definitions,
                    () -> advanceMission(missionId, MissionStatus.WAITING_AGENT_RESULTS, 30,
                            "Trabajo paralelo (ronda " + round + ")", "Los agentes están trabajando en paralelo."),
                    round);

            consolidateAgentOutcomes(missionId, instruction, outcomes, seedCapitalUsd);

        } catch (Exception ex) {
            log.error("MISSION {} - evidence round {} failed", missionId, round, ex);
            safeFail(missionId, ex);
        }
    }

    private void reexecuteTeamMission(String missionId, String instruction, String teamId, int round,
                                      String investorRequest, String priorResults, String ceoModel) {

        var mode = TeamExecutionMode.forTeamType(TeamMemoryService.teamType(teamId)
                .orElseThrow(() -> new IllegalStateException("teamId desconocido: " + teamId)));

        // Revisión final Aria (16): una misión anterior a Aria sigue como antes, con el plan de Neo que ya tiene.
        if (ariaApplies(mode) && !requirementsMemory.predatesRequirements(missionId)) {
            var approved = requirementsMemory.approved(missionId);
            if (approved.isEmpty()) {
                // Revisión final Aria (2c): sin requisitos aprobados Neo nunca planifica; el pedido vuelve a Aria.
                var written = rewriteRequirements(missionId, instruction, investorRequest);
                awaitApproval(missionId, written);
                return;
            }
            var current = requirementsMemory.currentIncrement(missionId);
            if (current > 0) {
                // Revisión final Aria (2a/b): la ronda corrige el incremento en curso, con su bloque.
                instruction = withIncrement(jsonMapper.readValue(approved.get().specJson(),
                        com.aicompany.core.agent.model.RequirementsSpec.class), current, instruction);
            }
        }

        TeamSnapshot team = null;
        TeamPlan plan = null;
        var saved = memory.lastTeamPlanJson(missionId);
        if (saved.isPresent()) {
            var savedPlan = jsonMapper.readValue(saved.get(), TeamPlan.class);
            // FALTANTES (Bloque 1, m-2): el plan guardado se reutiliza solo si cumple las reglas y el equipo de hoy.
            var errors = teamWorkPlanner.revalidate(missionId, teamId, savedPlan, mode);
            if (errors.isEmpty()) {
                team = teamWorkPlanner.teamSnapshot(teamId);
                plan = savedPlan;
            } else {
                log.warn("MISSION {} - saved team plan no longer valid, replanning round {}: {}", missionId, round, errors);
            }
        }
        if (plan == null) {
            // Falló antes de tener plan (o el guardado ya no vale): el líder planifica de nuevo con el pedido del inversionista.
            advanceMission(missionId, MissionStatus.PLANNING, 5, "Planificación del equipo (ronda " + round + ")",
                    "El líder de " + teamId + " está descomponiendo el trabajo con el pedido del inversionista.");
            var request = investorRequest == null || investorRequest.isBlank()
                    ? CeoService.NO_INVESTOR_COMMENT : investorRequest;
            var planned = teamWorkPlanner.plan(missionId, teamId, instruction + "\n\n" + investorBlock(request), mode, round);
            team = planned.team();
            plan = planned.plan();
        }

        var agentIds = plan.tasksOrEmpty().stream().filter(java.util.Objects::nonNull)
                .map(TeamPlan.PlannedTask::agentId).distinct().toList();
        var routed = ceoService.routeInvestorFeedback(instruction, priorResults, investorRequest, agentIds, ceoModel);
        var tasks = plan.tasksOrEmpty().stream().filter(java.util.Objects::nonNull)
                .map(t -> new TeamPlan.PlannedTask(t.agentId(), t.kind(), t.action(),
                        investorBlock(routed.getOrDefault(t.agentId(), investorRequest)) + "\n" + t.objective(),
                        t.requiredCapabilities(), t.ownedPaths(), t.assignments()))
                .toList();
        var withRequest = new TeamPlan(plan.summary(), plan.techStack(), plan.entryPoint(), tasks,
                plan.participationConflicts(), plan.stackProfile(), plan.boundedContexts(), plan.ubiquitousLanguage(),
                plan.database(), plan.components());

        advanceMission(missionId, MissionStatus.DELEGATING, 10, "Ronda de evidencia " + round,
                "Re-ejecutando el plan de " + team.teamName() + " con el pedido del inversionista.");

        runTeamPlan(missionId, instruction, mode, new TeamMissionContext(missionId, instruction, team, withRequest, round));
    }

    /** Resultados de la ronda anterior, para que el CEO reparta el pedido sabiendo qué hizo cada uno. */
    private String priorRoundResults(String missionId, int round) {
        var text = memory.tasks(missionId).stream()
                .filter(t -> TaskIds.roundOf(t.taskId()) == round - 1 && "COMPLETED".equals(t.status()))
                .map(t -> t.agentId() + " / " + t.action() + ": " + (t.result() == null ? "" : t.result()))
                .collect(java.util.stream.Collectors.joining("\n\n"));
        return text.length() > 12_000 ? text.substring(0, 12_000) + "…" : text;
    }

    private void executeInternal(
            String missionId,
            String instruction) {

        log.info("MISSION {} - async execution started", missionId);

        try {

            var teamId = memory.teamId(missionId).orElse(null);

            if (teamId != null) {
                executeTeamMission(missionId, instruction, teamId);
                return;
            }

            advanceMission(
                    missionId,
                    MissionStatus.PLANNING,
                    5,
                    "Planificación",
                    "CEO está definiendo el trabajo de la misión."
            );

            log.info("MISSION {} -> PLANNING", missionId);

            advanceMission(
                    missionId,
                    MissionStatus.DELEGATING,
                    10,
                    "Delegación",
                    "Asignando tareas paralelas a Sales, Product, Finance, Engineering y QA."
            );

            log.info("MISSION {} -> DELEGATING", missionId);

            var seedCapitalUsd = companyPolicyService.activeValue(PolicyKey.SEED_CAPITAL_USD);
            var financialCriteria = memory.financialCriteria(missionId).orElse(null);

            var outcomes = batchRunner.run(
                    missionId,
                    instruction,
                    discoveryDefinitions(seedCapitalUsd, financialCriteria),
                    () -> {
                        advanceMission(
                                missionId,
                                MissionStatus.WAITING_AGENT_RESULTS,
                                30,
                                "Trabajo paralelo",
                                "Los agentes están trabajando en paralelo."
                        );
                        log.info("MISSION {} -> WAITING_AGENT_RESULTS", missionId);
                    }
            );

            consolidateAgentOutcomes(missionId, instruction, outcomes, seedCapitalUsd);

        } catch (Exception ex) {

            log.error("MISSION {} - execution failed", missionId, ex);

            safeFail(missionId, ex);
        }
    }

    /**
     * Misión con teamId (spec §2): el líder planifica, el plan se valida
     * en Java y la estrategia del tipo de equipo ejecuta. Nunca crea las 5
     * tareas fijas de discovery.
     */
    private void executeTeamMission(String missionId, String instruction, String teamId) {

        var teamType = TeamMemoryService.teamType(teamId)
                .orElseThrow(() -> new IllegalStateException("teamId desconocido: " + teamId));

        var mode = TeamExecutionMode.forTeamType(teamType);

        var strategy = teamStrategies.stream()
                .filter(s -> s.mode() == mode)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No hay estrategia de ejecución para " + mode));

        if (ariaApplies(mode)) {
            advanceMission(missionId, MissionStatus.PLANNING, 3, "Requisitos (Aria)",
                    "Aria está convirtiendo el pedido en historias de usuario.");
            var written = requirementsWriter.write(missionId, instruction, java.util.Optional.empty(), null);
            if (companyPolicyService.activeValue(PolicyKey.SPEC_APPROVAL_REQUIRED) >= 1.0) {
                awaitApproval(missionId, written);
                return;
            }
            publishReady(missionId, written);
            requirementsMemory.approve(missionId, written.version(), "policy");
            // Revisión final Aria (14): aprobado por la policy, no por el fundador (agentId "policy").
            events.publish("EMPRESA_REQUIREMENTS_APPROVED", missionId, null, "policy",
                    Map.of("version", written.version(), "approvedBy", "policy"));
            planAndRunIncrement(missionId, instruction, teamId, mode, written.spec(), 1, 0);
            return;
        }

        advanceMission(
                missionId,
                MissionStatus.PLANNING,
                5,
                "Planificación del equipo",
                "El líder de " + teamId + " está descomponiendo el trabajo."
        );

        var planned = teamWorkPlanner.plan(missionId, teamId, instruction, mode);

        advanceMission(
                missionId,
                MissionStatus.DELEGATING,
                10,
                "Delegación",
                "Plan del líder validado: " + planned.plan().tasksOrEmpty().size()
                        + " tareas para " + planned.team().teamName() + "."
        );

        runTeamPlan(missionId, instruction, mode,
                new TeamMissionContext(missionId, instruction, planned.team(), planned.plan()));
    }

    private void publishReady(String missionId, RequirementsWriter.Written written) {
        events.publish("EMPRESA_REQUIREMENTS_READY", missionId, null, RequirementsWriter.ARIA,
                Map.of("version", written.version(), "stories", written.spec().userStoriesOrEmpty().size()));
    }

    private void awaitApproval(String missionId, RequirementsWriter.Written written) {
        publishReady(missionId, written);
        advanceMission(missionId, MissionStatus.AWAITING_SPEC_APPROVAL, 8, "Requisitos listos (Aria)",
                "Aria escribió " + written.spec().userStoriesOrEmpty().size() + " historias en "
                        + written.spec().incrementNumbers().size() + " incremento(s). Esperan tu aprobación: "
                        + com.aicompany.core.agent.validation.RequirementsText.summary(written.spec()));
    }

    static String withIncrement(com.aicompany.core.agent.model.RequirementsSpec spec, int increment, String instruction) {
        return com.aicompany.core.agent.validation.RequirementsText.incrementBlock(spec, increment)
                + "\nPEDIDO ORIGINAL DEL FUNDADOR: " + instruction;
    }

    /**
     * Spec 2026-10-03 Aria §3 (revisión final 3-5): Neo planifica cada incremento con solo sus HU (ronda 0 = el 1; los
     * siguientes, como ronda nueva sobre el mismo repositorio). Cuenta como construido al llegar a AWAITING_INVESTOR.
     */
    private void planAndRunIncrement(String missionId, String instruction, String teamId, TeamExecutionMode mode,
                                     com.aicompany.core.agent.model.RequirementsSpec spec, int increment, int round) {
        var withIncrement = withIncrement(spec, increment, instruction);
        requirementsMemory.setBuildingIncrement(missionId, increment);
        advanceMission(missionId, MissionStatus.PLANNING, 5, "Planificación del equipo",
                "Neo planifica el incremento " + increment + ".");
        // Verificado en vivo (incremento 2): Neo planifica sobre el producto existente (contextos y componentes).
        var planned = round == 0
                ? teamWorkPlanner.plan(missionId, teamId, withIncrement, mode)
                : teamWorkPlanner.plan(missionId, teamId, withIncrement, mode, round, existingProduct(missionId));
        advanceMission(missionId, MissionStatus.DELEGATING, 10, "Delegación",
                "Plan del líder validado: " + planned.plan().tasksOrEmpty().size() + " tareas (incremento "
                        + increment + ").");
        runTeamPlan(missionId, withIncrement, mode, new TeamMissionContext(missionId, withIncrement, planned.team(),
                planned.plan(), round));
    }

    /**
     * Verificado en vivo (incremento 2, segunda corrida): el último plan podía ser el de una corrida fallida con solo el
     * contexto nuevo. El producto existente es la unión de todos los planes de la misión.
     */
    private TeamPlan existingProduct(String missionId) {
        TeamPlan product = null;
        for (var json : memory.teamPlanJsons(missionId)) {
            var plan = jsonMapper.readValue(json, TeamPlan.class);
            product = product == null ? plan
                    : com.aicompany.core.agent.validation.IncrementPlanMerger.merge(plan, product);
        }
        return product;
    }

    /** Spec misiones durables §2: misiones con un hilo trabajando (contador por misión: el final de una ronda no desregistra la siguiente). Al arrancar está vacío: toda misión en curso es huérfana. */
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> liveMissions = new java.util.concurrent.ConcurrentHashMap<>();

    public boolean isLive(String missionId) {
        return liveMissions.containsKey(missionId);
    }

    public java.util.Set<String> liveMissionIds() {
        return java.util.Set.copyOf(liveMissions.keySet());
    }

    /** Corre {@code work} en el pool de misiones con la misión registrada como viva mientras dure. */
    private CompletableFuture<Void> runLive(String missionId, Runnable work) {
        liveMissions.merge(missionId, 1, Integer::sum);
        try {
            return CompletableFuture.runAsync(() -> {
                try {
                    // Costo real por misión (2026-10-04): las llamadas del hilo quedan atribuidas a la misión.
                    ModelCallContext.with(missionId, null, work);
                } finally {
                    liveMissions.computeIfPresent(missionId, (k, n) -> n > 1 ? n - 1 : null);
                }
            }, orchestratorExecutor);
        } catch (RuntimeException ex) {
            liveMissions.computeIfPresent(missionId, (k, n) -> n > 1 ? n - 1 : null);
            throw ex;
        }
    }

    static final String NO_WORKSPACE = "No existe el repositorio de la misión: no se puede retomar ni corregir.";

    private DevelopmentWorkspaceService workspace;

    /** Spec misiones durables §2.1: para saber si una ronda > 0 tiene repositorio (opcional en tests). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setWorkspace(DevelopmentWorkspaceService workspace) {
        this.workspace = workspace;
    }

    private com.aicompany.core.recovery.RecoveryMemoryService recoveryMemory;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRecoveryMemory(com.aicompany.core.recovery.RecoveryMemoryService recoveryMemory) {
        this.recoveryMemory = recoveryMemory;
    }

    /**
     * Spec misiones durables §2: retoma una huérfana desde {@code point}, en la misma ronda (nunca gasta otra). Discovery y
     * otros equipos quedan INTERRUPTED.
     */
    public CompletableFuture<Void> resumeAsync(String missionId, com.aicompany.core.recovery.ResumePoint point) {
        if (point.stage() == com.aicompany.core.recovery.ResumePoint.Stage.INTERRUPT) {
            interrupt(missionId);
            return CompletableFuture.completedFuture(null);
        }
        if (point.stage() == com.aicompany.core.recovery.ResumePoint.Stage.HUMAN_VERIFY) {
            // Revisión 2026-10-06 §5 (fix 2): la verificación cortada queda FAILED y se verifica de nuevo (tarea n+1),
            // sin modelos y sin tocar el repositorio; nunca se retoma como una ronda.
            var prefix = missionId + HUMAN_VERIFY_SUFFIX;
            memory.tasks(missionId).stream()
                    .filter(t -> t.taskId().startsWith(prefix) && t.validationStatus() == null
                            && !"FAILED".equals(t.status()))
                    .forEach(t -> {
                        var message = "Verificación humana interrumpida por un reinicio de company-core; se repite.";
                        memory.updateTask(t.taskId(), "FAILED", message);
                        events.publishTask("EMPRESA_TASK_FAILED", t.taskId(), missionId,
                                DevelopmentTeamStrategy.HUMAN_VERIFY_AGENT, "FAILED", message);
                    });
            events.publish("EMPRESA_MISSION_RESUMED", missionId, null, "system",
                    Map.of("stage", point.stage().name(), "round", point.round(), "kind", point.kind().name()));
            return verifyAsync(missionId);
        }
        events.publish("EMPRESA_MISSION_RESUMED", missionId, null, "system",
                Map.of("stage", point.stage().name(), "round", point.round(), "kind", point.kind().name()));
        return submit(missionId, "resume " + point.stage(), () -> resumeInternal(missionId, point));
    }

    private void resumeInternal(String missionId, com.aicompany.core.recovery.ResumePoint point) {
        var teamId = memory.teamId(missionId).orElseThrow();
        var instruction = memory.instructionOf(missionId).orElse("");
        var mode = TeamExecutionMode.forTeamType(TeamMemoryService.teamType(teamId).orElseThrow());
        switch (point.stage()) {
            case REQUIREMENTS -> executeTeamMission(missionId, instruction, teamId);
            case ADJUSTMENT -> {
                var adjustment = requirementsMemory.pendingAdjustment(missionId).orElseThrow();
                var written = rewriteRequirements(missionId, instruction, adjustment);
                events.publish("EMPRESA_REQUIREMENTS_ADJUSTED", missionId, null, "human",
                        Map.of("version", written.version(), "instruction", adjustment));
                awaitApproval(missionId, written);
            }
            case AWAIT_APPROVAL -> {
                var latest = requirementsMemory.latest(missionId).orElseThrow();
                awaitApproval(missionId, new RequirementsWriter.Written(latest.version(), jsonMapper.readValue(
                        latest.specJson(), com.aicompany.core.agent.model.RequirementsSpec.class)));
            }
            case PLAN, GENERATE, VERIFY -> resumeRound(missionId, instruction, teamId, mode, point);
            case INTERRUPT -> interrupt(missionId);
        }
    }

    private void resumeRound(String missionId, String instruction, String teamId, TeamExecutionMode mode,
                             com.aicompany.core.recovery.ResumePoint point) {
        var round = point.round();
        switch (point.kind()) {
            case ORIGINAL, INCREMENT -> {
                var increment = point.increment() == null ? 1 : point.increment();
                var spec = approvedSpec(missionId);
                var team = teamWorkPlanner.teamSnapshot(teamId);
                var saved = memory.planJson(TaskIds.planTask(missionId, team.leaderAgentId(), round));
                if (saved.isEmpty()) {
                    planAndRunIncrement(missionId, instruction, teamId, mode, spec, increment, round);
                    return;
                }
                var withIncrement = withIncrement(spec, increment, instruction);
                requirementsMemory.setBuildingIncrement(missionId, increment);
                advanceMission(missionId, MissionStatus.DELEGATING, 10, "Retomada",
                        "Retomando el incremento " + increment + " tras un reinicio.");
                runTeamPlan(missionId, withIncrement, mode, new TeamMissionContext(missionId, withIncrement, team,
                        jsonMapper.readValue(saved.get(), TeamPlan.class), round));
            }
            case EVIDENCE -> {
                var requests = memory.evidenceRequests(missionId);
                var request = requests.isEmpty() ? CeoService.NO_INVESTOR_COMMENT : requests.get(requests.size() - 1);
                reexecuteTeamMission(missionId, instruction, teamId, round, request, priorRoundResults(missionId, round),
                        companyMemory.agentModel("ceo", defaultCeoModel));
            }
            case CORRECTION -> {
                var request = recoveryMemory.correctionRound(missionId, round)
                        .map(com.aicompany.core.recovery.CorrectionRound::request).orElseThrow();
                reexecuteTeamMission(missionId, instruction, teamId, round, request, priorRoundResults(missionId, round),
                        companyMemory.agentModel("ceo", defaultCeoModel));
            }
        }
    }

    /**
     * Spec misiones durables §3: ronda de corrección ({@code round} ya reclamado con claimNextRound y registrado en
     * correctionRounds por MissionRecoveryService o MissionService). No consume MAX_EVIDENCE_ROUNDS.
     */
    public CompletableFuture<Void> correctAsync(String missionId, int round, String request) {
        return correctAsync(missionId, round, request, false);
    }

    private com.aicompany.core.recovery.CorrectionStrategist correctionStrategist;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCorrectionStrategist(@org.springframework.context.annotation.Lazy
                                        com.aicompany.core.recovery.CorrectionStrategist correctionStrategist) {
        this.correctionStrategist = correctionStrategist;
    }

    /**
     * Revisión 2026-10-06 §2: {@code withStrategy} (sin avance en las últimas rondas) busca una estrategia nueva en el hilo de
     * la ronda, antes de correr; sin estrategia válida la ronda corre igual.
     */
    public CompletableFuture<Void> correctAsync(String missionId, int round, String request, boolean withStrategy) {
        events.publish("EMPRESA_MISSION_CORRECTION_STARTED", missionId, null, "system", Map.of("round", round));
        advanceMission(missionId, MissionStatus.DELEGATING, 10, "Corrección automática (ronda " + round + ")",
                "Corrigiendo contra las HU, los criterios y los escenarios del incremento.");
        return submit(missionId, "correction " + round, () -> reexecuteInternal(missionId,
                memory.instructionOf(missionId).orElse(""), round,
                withStrategy ? withNewStrategy(missionId, round, request) : request));
    }

    /** Antepone la estrategia buscada al pedido y la deja guardada (para retomar sin volver a buscar). Nunca lanza. */
    private String withNewStrategy(String missionId, int round, String request) {
        if (correctionStrategist == null || recoveryMemory == null) {
            return request;
        }
        try {
            var rounds = recoveryMemory.correctionRounds(missionId).stream()
                    .sorted(java.util.Comparator.comparingInt(com.aicompany.core.recovery.CorrectionRound::round)).toList();
            var current = rounds.stream().filter(r -> r.round() == round).findFirst()
                    .map(r -> new com.aicompany.core.recovery.CorrectionReport(r.request(), r.failing(), r.failures()))
                    .orElse(null);
            var previous = rounds.stream().filter(r -> r.round() < round)
                    .map(r -> new com.aicompany.core.recovery.CorrectionReport(r.request(), r.failing(), r.failures()))
                    .toList();
            var strategy = correctionStrategist.propose(missionId, round, current, previous, stackOf(missionId));
            if (strategy.isEmpty()) {
                return request;
            }
            var s = strategy.get();
            var withStrategy = "ESTRATEGIA NUEVA (error persistente): " + s.strategy() + "\nFuentes: "
                    + String.join(", ", s.sources()) + "\n\n" + request;
            recoveryMemory.addStrategy(missionId, s);
            recoveryMemory.updateCorrectionRequest(missionId, round, withStrategy);
            events.publish("EMPRESA_CORRECTION_STRATEGY_PROPOSED", missionId, null, "engineering",
                    Map.of("round", round, "fingerprint", s.fingerprint(), "sources", s.sources()));
            return withStrategy;
        } catch (Exception ex) {
            log.warn("MISSION {} - could not get a correction strategy: {}", missionId, ex.getMessage());
            return request;
        }
    }

    /** Stack para la búsqueda: lo que declara el último plan guardado. */
    private String stackOf(String missionId) {
        try {
            var saved = memory.lastTeamPlanJson(missionId);
            if (saved.isEmpty()) {
                return "";
            }
            var profiles = jsonMapper.readValue(saved.get(), TeamPlan.class).componentProfiles();
            var parts = new java.util.ArrayList<String>();
            if (profiles.contains(com.aicompany.core.model.StackProfile.DOTNET_APP)) {
                parts.add(".NET 8");
            }
            if (profiles.contains(com.aicompany.core.model.StackProfile.FLUTTER_WEB_APP)) {
                parts.add("Flutter 3.41");
            }
            return String.join(" ", parts);
        } catch (Exception ex) {
            return "";
        }
    }

    public static final String HUMAN_VERIFY_SUFFIX = "-HUMAN-VERIFY-";

    /**
     * Spec misiones durables, revisión 2026-10-06 §5 (manos humanas): verifica el HEAD real del repositorio (chequeos
     * deterministas, sandbox y cobertura de escenarios) sin llamar a ningún modelo, en una tarea VALIDATION nueva
     * {@code <misión>-HUMAN-VERIFY-<n>}, y deja la misión en AWAITING_INVESTOR con su validationStatus (si queda VERIFIED,
     * advanceMission confirma el incremento como en cualquier ronda). Los chequeos de estado los hace MissionRecoveryService.
     */
    public CompletableFuture<Void> verifyAsync(String missionId) {
        advanceMission(missionId, MissionStatus.EVALUATING, 75, "Verificación humana",
                "Verificando el último commit del repositorio sin modelos: chequeos deterministas, sandbox y escenarios.");
        // Fix 5: el contador de correcciones del incremento vuelve a cero recién con la misión en EVALUATING.
        if (recoveryMemory != null) {
            try {
                recoveryMemory.resetCounter(missionId,
                        requirementsMemory == null ? 1 : Math.max(1, requirementsMemory.currentIncrement(missionId)));
            } catch (Exception ex) {
                log.warn("MISSION {} - could not reset the correction counter: {}", missionId, ex.getMessage());
            }
        }
        return submit(missionId, "human verification", () -> {
            var teamId = memory.teamId(missionId).orElseThrow();
            var plan = jsonMapper.readValue(memory.lastTeamPlanJson(missionId).orElseThrow(() -> new IllegalStateException(
                    "MISSION " + missionId + " no tiene un plan de Neo guardado.")), TeamPlan.class);
            var strategy = teamStrategies.stream().filter(DevelopmentTeamStrategy.class::isInstance)
                    .map(DevelopmentTeamStrategy.class::cast).findFirst()
                    .orElseThrow(() -> new IllegalStateException("No hay estrategia de desarrollo."));
            var prefix = missionId + HUMAN_VERIFY_SUFFIX;
            var taskId = prefix + (memory.tasks(missionId).stream().filter(t -> t.taskId().startsWith(prefix)).count() + 1);
            memory.createTask(taskId, missionId, DevelopmentTeamStrategy.HUMAN_VERIFY_AGENT, "HUMAN_VERIFY",
                    TeamPlan.KIND_VALIDATION);
            events.publishTask("EMPRESA_TASK_CREATED", taskId, missionId, DevelopmentTeamStrategy.HUMAN_VERIFY_AGENT,
                    "PENDING", "Verificación humana del último commit.");
            var context = new TeamMissionContext(missionId, memory.instructionOf(missionId).orElse(""),
                    teamWorkPlanner.teamSnapshot(teamId), plan, memory.evidenceRound(missionId));
            memory.updateTask(taskId, "RUNNING", "Verificando el último commit sin modelos.");
            TeamExecutionResult result;
            try {
                result = strategy.verifyHead(context, taskId);
            } catch (RuntimeException ex) {
                var message = "Verificación humana fallida: " + (ex.getMessage() == null ? "error inesperado" : ex.getMessage());
                memory.updateTask(taskId, "FAILED", message);
                events.publishTask("EMPRESA_TASK_FAILED", taskId, missionId, DevelopmentTeamStrategy.HUMAN_VERIFY_AGENT,
                        "FAILED", message);
                throw ex;
            }
            var verifiable = result instanceof TeamExecutionResult.Development development
                    ? development.verifiableState() : "";
            advanceMission(missionId, MissionStatus.AWAITING_INVESTOR, 95, "Verificación humana",
                    "Verificación humana del último commit (" + taskId + ", sin modelos).\n\n" + verifiable);
            catalog(missionId, "build", () -> productAutomation.buildFinished(missionId));
        });
    }

    /** Revisión final Aria (6): un pool lleno no deja la misión trabada en un estado intermedio. */
    private CompletableFuture<Void> submit(String missionId, String what, Runnable work) {
        try {
            return runLive(missionId, () -> {
                try {
                    work.run();
                } catch (Exception ex) {
                    log.error("MISSION {} - {} failed", missionId, what, ex);
                    safeFail(missionId, ex);
                }
            });
        } catch (Exception ex) {
            log.error("MISSION {} - could not submit {}", missionId, what, ex);
            safeFail(missionId, ex);
            return CompletableFuture.failedFuture(ex);
        }
    }

    private com.aicompany.core.agent.model.RequirementsSpec approvedSpec(String missionId) {
        var version = requirementsMemory.approved(missionId)
                .orElseThrow(() -> new IllegalStateException("MISSION " + missionId + " no tiene requisitos aprobados."));
        return jsonMapper.readValue(version.specJson(), com.aicompany.core.agent.model.RequirementsSpec.class);
    }

    /** Spec 2026-10-03 Aria §1: tras aprobar (aunque company-core se haya reiniciado), el plan de Neo con el incremento 1. */
    public CompletableFuture<Void> continueAfterApprovalAsync(String missionId) {
        // Revisión final (6): síncrono, la misión deja AWAITING_SPEC_APPROVAL aunque el hilo espere en la cola.
        advanceMission(missionId, MissionStatus.PLANNING, 5, "Planificación del equipo",
                "Requisitos aprobados: Neo planifica el incremento 1.");
        return submit(missionId, "continue after approval", () -> {
            var teamId = memory.teamId(missionId).orElseThrow();
            var instruction = memory.instructionOf(missionId).orElse("");
            var mode = TeamExecutionMode.forTeamType(TeamMemoryService.teamType(teamId).orElseThrow());
            planAndRunIncrement(missionId, instruction, teamId, mode, approvedSpec(missionId), 1, 0);
        });
    }

    /** Spec 2026-10-03 Aria §2: Aria rehace los requisitos con el ajuste del fundador y vuelve a esperar. */
    public CompletableFuture<Void> adjustRequirementsAsync(String missionId, String adjustment) {
        advanceMission(missionId, MissionStatus.PLANNING, 3, "Requisitos (Aria)", "Aria ajusta las historias: " + adjustment);
        return submit(missionId, "requirements adjustment", () -> {
            var written = rewriteRequirements(missionId, memory.instructionOf(missionId).orElse(""), adjustment);
            events.publish("EMPRESA_REQUIREMENTS_ADJUSTED", missionId, null, "human",
                    Map.of("version", written.version(), "instruction", adjustment));
            awaitApproval(missionId, written);
        });
    }

    private RequirementsWriter.Written rewriteRequirements(String missionId, String instruction, String adjustment) {
        var previous = requirementsMemory.latest(missionId).map(v -> jsonMapper.readValue(v.specJson(),
                com.aicompany.core.agent.model.RequirementsSpec.class));
        return requirementsWriter.write(missionId, instruction, previous, adjustment);
    }

    /**
     * Spec 2026-10-03 Aria §3: el incremento N es una ronda de la misma misión sobre el mismo repositorio, planificada por
     * Neo con solo las HU de N. No cuenta como ronda de evidencia. {@code round} ya lo reclamó {@code MissionService}.
     */
    public CompletableFuture<Void> buildIncrementAsync(String missionId, int increment, int round) {
        requirementsMemory.addIncrementRound(missionId, round, increment);
        events.publish("EMPRESA_INCREMENT_STARTED", missionId, null, "human", Map.of("increment", increment, "round", round));
        advanceMission(missionId, MissionStatus.PLANNING, 5, "Incremento " + increment,
                "Neo planifica el incremento " + increment + " sobre el mismo repositorio.");
        return submit(missionId, "increment " + increment, () -> {
            var teamId = memory.teamId(missionId).orElseThrow();
            var instruction = memory.instructionOf(missionId).orElse("");
            var mode = TeamExecutionMode.forTeamType(TeamMemoryService.teamType(teamId).orElseThrow());
            planAndRunIncrement(missionId, instruction, teamId, mode, approvedSpec(missionId), increment, round);
        });
    }

    private void runTeamPlan(String missionId, String instruction, TeamExecutionMode mode, TeamMissionContext context) {

        // Spec misiones durables §2.1: una ronda > 0 del Development Group trabaja sobre el repositorio de la misión; sin
        // él no hay nada que retomar ni corregir y la misión espera al fundador (sin gastar más llamadas). Revisión
        // 2026-10-06 §6: solo si el repositorio existió (alguna tarea con commit); si nunca existió, se escribe el scaffold.
        if (mode == TeamExecutionMode.DEVELOPMENT && context.round() > 0 && workspace != null
                && workspace.headIfAny(missionId).isEmpty()
                && memory.tasks(missionId).stream().anyMatch(t -> t.commitSha() != null)) {
            block(missionId, NO_WORKSPACE);
            return;
        }

        var strategy = teamStrategies.stream()
                .filter(s -> s.mode() == mode)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No hay estrategia de ejecución para " + mode));

        var result = strategy.execute(context, (status, progress, step, message) ->
                advanceMission(missionId, status, progress, step, message));

        switch (result) {
            case TeamExecutionResult.AgentOutcomes outcomes -> consolidateAgentOutcomes(
                    missionId, instruction, outcomes.outcomes(),
                    companyPolicyService.activeValue(PolicyKey.SEED_CAPITAL_USD));
            case TeamExecutionResult.Development development ->
                    consolidateDevelopment(missionId, instruction, development);
        }
    }

    /**
     * El CEO consolida; el bloque "Estado verificable" lo agrega Java al
     * final, así que los hechos verificables no dependen de la redacción
     * del modelo (spec §8).
     */
    private void consolidateDevelopment(
            String missionId, String instruction, TeamExecutionResult.Development development) {

        advanceMission(
                missionId,
                MissionStatus.CONSOLIDATING,
                85,
                "Consolidación",
                "CEO está consolidando el resultado del equipo."
        );

        var finalResult = ceoService.executeMission(
                instruction,
                development.resultsForCeo(),
                promptMemory.activePrompt("ceo"),
                companyMemory.agentModel("ceo", defaultCeoModel)
        );

        advanceMission(
                missionId,
                MissionStatus.AWAITING_INVESTOR,
                95,
                "Recomendación",
                finalResult + "\n\n" + development.verifiableState()
        );

        log.info("MISSION {} -> AWAITING_INVESTOR (team development)", missionId);

        catalog(missionId, "build", () -> productAutomation.buildFinished(missionId));
    }

    /** Las 5 tareas fijas de discovery — misiones SIN teamId, sin cambios. */
    private List<AgentTaskBatchRunner.AgentTaskDefinition> discoveryDefinitions(
            double seedCapitalUsd,
            FinancialCriteriaResponse financialCriteria) {

        return List.of(
                new AgentTaskBatchRunner.AgentTaskDefinition(
                        "sales",
                        "MARKET_DISCOVERY",
                        "Identificar perfiles de clientes y señales de demanda que deban validarse.",
                        null
                ),
                new AgentTaskBatchRunner.AgentTaskDefinition(
                        "product",
                        "OFFER_DESIGN",
                        "Definir una oferta mínima vendible alineada con las restricciones de capital.",
                        null
                ),
                new AgentTaskBatchRunner.AgentTaskDefinition(
                        "finance",
                        "UNIT_ECONOMICS",
                        financeObjective(seedCapitalUsd, financialCriteria),
                        null
                ),
                new AgentTaskBatchRunner.AgentTaskDefinition(
                        "engineering",
                        "DELIVERY_FEASIBILITY",
                        "Evaluar la capacidad de entregar la oferta con los recursos tecnológicos disponibles.",
                        null
                ),
                new AgentTaskBatchRunner.AgentTaskDefinition(
                        "qa",
                        "QUALITY_RISK_REVIEW",
                        "Identificar, de forma independiente a los demás agentes (esta tarea corre en paralelo, no tiene acceso a sus resultados), riesgos, huecos de evidencia y supuestos no verificados en la oportunidad de negocio descrita en la misión, antes de comprometer capital.",
                        null
                )
        );
    }

    /**
     * EVALUATING → CONSOLIDATING → AWAITING_INVESTOR sobre resultados de
     * AgentRuntime (AgentResult). Lo usan discovery y los equipos de
     * análisis (AnalysisTeamStrategy).
     */
    private void consolidateAgentOutcomes(
            String missionId,
            String instruction,
            List<AgentExecutionOutcome> outcomes,
            double seedCapitalUsd) {

        advanceMission(
                missionId,
                MissionStatus.EVALUATING,
                70,
                "Evaluación",
                "Todos los agentes terminaron. CEO está revisando resultados."
        );

        var agentResults = outcomes.stream()
                .filter(AgentExecutionOutcome::completed)
                .map(AgentExecutionOutcome::result)
                .toList();

        var failedAgents = outcomes.stream()
                .filter(outcome -> !outcome.completed())
                .toList();

        if (agentResults.isEmpty()) {
            throw new IllegalStateException(
                    "Los " + failedAgents.size() + " agente(s) de la misión fallaron: "
                            + failedAgents.stream()
                                    .map(o -> o.agentId() + " (" + o.error() + ")")
                                    .collect(Collectors.joining("; "))
            );
        }

        if (!failedAgents.isEmpty()) {
            log.warn("MISSION {} - continuing with partial results, failed agents={}",
                    missionId, failedAgents.stream().map(AgentExecutionOutcome::agentId).toList());
        }

        /*
         * Flujo Opportunity -> Customer candidato (100% nivel 🟢): la misión
         * produjo al menos un resultado, así que hay una oportunidad que
         * registrar; los candidatos quedan como Customer {status:'LEAD'}.
         */
        opportunityMemory.recordOpportunity(missionId, instruction);

        for (var result : agentResults) {
            opportunityMemory.recordCandidates(missionId, result.agent(), result.customerCandidates());
        }

        var structuredResults = serializeAgentResults(agentResults);

        log.info("MISSION {} - structured agent results generated agents={}", missionId, agentResults.size());

        var contradictions = contradictionDetector.detect(
                agentResults,
                seedCapitalUsd,
                companyPolicyService.activeValue(PolicyKey.CONTRADICTION_SEED_CAPITAL_MULTIPLE)
        );

        if (!contradictions.isEmpty()) {
            log.warn("MISSION {} - contradictions detected: {}", missionId, contradictions);
        }

        var resultsForCeo = structuredResults;

        if (!contradictions.isEmpty()) {
            resultsForCeo += "\n\nCONTRADICCIONES_DETECTADAS "
                    + "(reglas deterministas, no del modelo; "
                    + "no las ignores al consolidar):\n- "
                    + String.join("\n- ", contradictions);
        }

        if (!failedAgents.isEmpty()) {
            resultsForCeo += "\n\nAGENTES_FALLIDOS (no completaron su tarea "
                    + "tras agotar reintentos; este es un "
                    + "resultado PARCIAL — decide cómo abordar "
                    + "el hueco en tu recomendación, no lo "
                    + "ignores):\n- "
                    + failedAgents.stream()
                            .map(o -> o.agentId() + ": " + o.error())
                            .collect(Collectors.joining("\n- "));
        }

        advanceMission(
                missionId,
                MissionStatus.CONSOLIDATING,
                85,
                "Consolidación",
                "CEO está consolidando la recomendación."
        );

        var finalResult = ceoService.executeMission(
                instruction,
                resultsForCeo,
                promptMemory.activePrompt("ceo"),
                companyMemory.agentModel("ceo", defaultCeoModel)
        );

        advanceMission(
                missionId,
                MissionStatus.AWAITING_INVESTOR,
                95,
                "Recomendación",
                finalResult
        );

        log.info("MISSION {} -> AWAITING_INVESTOR", missionId);

        if (memory.teamId(missionId).isEmpty()) {
            outcomes.stream()
                    .filter(o -> o.completed() && ProductAutomation.PRODUCT_AGENT.equals(o.agentId()))
                    .findFirst()
                    .ifPresent(o -> catalog(missionId, "idea", () -> productAutomation.ideaFromDiscovery(missionId, o.result())));
        } else {
            catalog(missionId, "build", () -> productAutomation.buildFinished(missionId));
        }
    }

    private String financeObjective(double seedCapitalUsd, FinancialCriteriaResponse financialCriteria) {

        var base = ("Estimar costos, precio, margen y condiciones de la oferta, "
                + "apoyándote en el capital semilla vigente de Forjai (US$%.2f) "
                + "como recurso disponible de la empresa — no como un monto de "
                + "utilidad que esta misión deba superar por defecto.")
                .formatted(seedCapitalUsd);

        if (financialCriteria == null) {
            return base;
        }

        var deadlineText = financialCriteria.deadline() == null
                ? "sin plazo definido"
                : "para " + financialCriteria.deadline();

        return base + " "
                + ("Esta misión tiene un objetivo financiero explícito: %s >= %.2f %s, %s. "
                        + "Analiza cómo alcanzarlo, pero nunca alteres los valores que reportes "
                        + "para forzar que el resultado coincida con este objetivo — tu análisis "
                        + "orienta la decisión, no reescribe los datos.")
                .formatted(
                        financialCriteria.metric(), financialCriteria.targetAmount(),
                        financialCriteria.currency(), deadlineText);
    }

    private String serializeAgentResults(
            List<AgentResult> results) {

        try {

            return jsonMapper.writeValueAsString(
                    results
            );

        } catch (JacksonException ex) {

            log.error(
                    "Could not serialize agent results",
                    ex
            );

            throw new IllegalStateException(
                    "No se pudieron serializar los resultados de los agentes.",
                    ex
            );
        }
    }

    private String safeMessage(
            Exception ex,
            String defaultMessage) {

        return ex.getMessage() == null || ex.getMessage().isBlank()
                ? defaultMessage
                : ex.getMessage();
    }

    /** El fundador está en Colombia (UTC-5): la hora del motivo va en su hora local. */
    private static final java.time.format.DateTimeFormatter COLOMBIA_TIME = java.time.format.DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm").withZone(java.time.ZoneId.of("America/Bogota"));

    /** Spec misiones durables §1: una interrupción no es una falla (sin correo, el orquestador no la cuenta). */
    public void interrupt(String missionId) {
        advanceMission(missionId, MissionStatus.INTERRUPTED, 100, "Interrumpida",
                "Interrumpida por un reinicio de company-core el " + COLOMBIA_TIME.format(java.time.Instant.now())
                        + " (hora de Colombia). Reanúdala con \"reanuda " + missionId + "\".");
        events.publish("EMPRESA_MISSION_INTERRUPTED", missionId, null, "system", Map.of());
    }

    /** Spec misiones durables §3: un freno de la corrección automática deja la misión esperando al fundador. */
    public void block(String missionId, String reason) {
        advanceMission(missionId, MissionStatus.BLOCKED, 100, "Bloqueada", reason);
        events.publish("EMPRESA_MISSION_BLOCKED", missionId, null, "system", Map.of("reason", reason));
    }

    private void safeFail(
            String missionId,
            Exception ex) {

        try {

            var message =
                    ex.getMessage() == null
                            ? "Error inesperado"
                            : ex.getMessage();

            advanceMission(
                    missionId,
                    MissionStatus.FAILED,
                    100,
                    "Error",
                    message
            );

            log.error(
                    "MISSION {} -> FAILED: {}",
                    missionId,
                    message
            );

        } catch (Exception memoryError) {

            log.error(
                    "MISSION {} - could not persist failure state",
                    missionId,
                    memoryError
            );
        }
    }

    /**
     * Persiste la transición en Neo4j y la publica en Kafka en la misma
     * operación, para que las dos memorias no se desincronicen (antes,
     * las transiciones de misión solo se escribían en Neo4j —
     * {@code EMPRESA_MISSION_CREATED} figuraba en {@code docs/EVENTS.md}
     * pero ningún transición de misión llegaba a Kafka).
     */
    private void advanceMission(
            String missionId,
            MissionStatus status,
            int progress,
            String currentStep,
            String message) {

        memory.updateMission(
                missionId,
                status,
                progress,
                currentStep,
                message
        );

        events.publishMission(
                status == MissionStatus.FAILED
                        ? "EMPRESA_MISSION_FAILED"
                        : "EMPRESA_MISSION_UPDATED",
                missionId,
                status.name(),
                progress,
                currentStep,
                message
        );

        /*
         * Alertas inmediatas (`empresa.md` §18): de los 6 tipos que pide el
         * documento, hoy solo hay señal clara y ya manejada explícitamente
         * en estas dos transiciones — "decisión estratégica" (la misión
         * necesita al fundador humano) y "fallo crítico" (todos los
         * agentes fallaron). El resto (bloqueo, riesgo importante, límite
         * de presupuesto, incidente grave) no tiene todavía una señal
         * determinista en el código, así que no se inventa una heurística.
         */
        if (status == MissionStatus.AWAITING_INVESTOR) {

            alertMailService.send(
                    "Misión " + missionId + " requiere tu decisión",
                    "La misión " + missionId + " llegó a AWAITING_INVESTOR y "
                            + "necesita tu aprobación.\n\n" + message,
                    false
            );

        } else if (status == MissionStatus.AWAITING_SPEC_APPROVAL) {
            alertMailService.send("Misión " + missionId + ": requisitos de Aria para aprobar",
                    "Aria escribió las historias de usuario de " + missionId + ". Revísalas en Missions o en el chat "
                            + "(\"muéstrame las HU de " + missionId + "\") y apruébalas, ajústalas o recházalas.\n\n"
                            + message, false);
        } else if (status == MissionStatus.FAILED) {

            alertMailService.send(
                    "Misión " + missionId + " falló",
                    "La misión " + missionId + " terminó en FAILED: " + message,
                    true
            );
        } else if (status == MissionStatus.BLOCKED) {
            alertMailService.send("Misión " + missionId + " bloqueada: espera tu indicación",
                    "La misión " + missionId + " quedó BLOCKED: " + message + "\n\nDecide en Missions o en el chat "
                            + "(\"pide más evidencia sobre " + missionId + ": <tu indicación>\", aprobar o rechazar).", true);
        }

        if (status == MissionStatus.AWAITING_INVESTOR && requirementsMemory != null) {
            try {
                // Verificado en vivo (incremento 2): solo un incremento VERIFIED cuenta como construido.
                if (memory.lastValidationStatus(missionId).filter("VERIFIED"::equals).isPresent()) {
                    requirementsMemory.confirmBuildingIncrement(missionId);
                }
            } catch (Exception ex) {
                log.warn("MISSION {} - could not confirm the built increment: {}", missionId, ex.getMessage());
            }
        }

        if (status == MissionStatus.AWAITING_INVESTOR || status == MissionStatus.FAILED) {
            notifyOrchestrator(missionId);
        }
        if ((status == MissionStatus.AWAITING_INVESTOR || status == MissionStatus.FAILED || status == MissionStatus.BLOCKED)
                && recoveryService != null) {
            try {
                recoveryService.onMissionFinished(missionId);
            } catch (Exception ex) {
                log.warn("MISSION {} - recovery notification failed: {}", missionId, ex.getMessage());
            }
        }
    }
}
