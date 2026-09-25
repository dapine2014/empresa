package com.aicompany.core.service;

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

            return CompletableFuture.runAsync(
                    () -> executeInternal(
                            missionId,
                            instruction
                    ),
                    orchestratorExecutor
            );

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

        var context = new TeamMissionContext(missionId, instruction, planned.team(), planned.plan());

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

        } else if (status == MissionStatus.FAILED) {

            alertMailService.send(
                    "Misión " + missionId + " falló",
                    "La misión " + missionId + " terminó en FAILED: " + message,
                    true
            );
        }
    }
}
