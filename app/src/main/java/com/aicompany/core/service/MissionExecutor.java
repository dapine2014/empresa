package com.aicompany.core.service;

import com.aicompany.core.agent.AgentRuntime;
import com.aicompany.core.agent.DevelopmentRuntime;
import com.aicompany.core.agent.model.AgentResult;
import com.aicompany.core.agent.model.DevelopmentResult;
import com.aicompany.core.agent.validation.ContradictionDetector;
import com.aicompany.core.config.AppProperties;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.AgentExecutionOutcome;
import com.aicompany.core.model.DevelopmentExecutionOutcome;
import com.aicompany.core.model.MissionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Service
public class MissionExecutor {

    private static final Logger log =
            LoggerFactory.getLogger(MissionExecutor.class);

    /**
     * Reintentos a nivel de MISIÓN para un agente que ya agotó sus 3
     * intentos internos (`AgentRuntime.MAX_RESULT_RETRIES`). Deliberadamente
     * bajo (1): es una segunda oportunidad completa desde cero (turno de
     * decisión + turno final nuevos), no una corrección incremental como el
     * reintento interno — si tampoco funciona a la segunda, lo más probable
     * es un problema real (no un bache pasajero del modelo) y seguir
     * insistiendo solo alargaría la misión sin cambiar el resultado.
     */
    private static final int MAX_AGENT_REPLANS = 1;

    private record AgentDefinition(
            String agentId,
            String action,
            String objective) {
    }

    private final MissionMemoryService memory;
    private final AgentRuntime runtime;
    private final CeoService ceoService;
    private final CompanyMemoryService companyMemory;
    private final String defaultCeoModel;
    private final Executor orchestratorExecutor;
    private final CompanyEventPublisher events;
    private final JsonMapper jsonMapper;
    private final ContradictionDetector contradictionDetector;
    private final AppProperties appProperties;
    private final OpportunityMemoryService opportunityMemory;
    private final AlertMailService alertMailService;
    private final DevelopmentRuntime developmentRuntime;
    private final DevelopmentWorkspaceService developmentWorkspace;

    public MissionExecutor(
            MissionMemoryService memory,
            AgentRuntime runtime,
            CeoService ceoService,
            CompanyMemoryService companyMemory,
            @Value("${ollama.ceo-model}") String defaultCeoModel,
            @Qualifier("missionOrchestratorExecutor")
            Executor orchestratorExecutor,
            CompanyEventPublisher events,
            JsonMapper jsonMapper,
            ContradictionDetector contradictionDetector,
            AppProperties appProperties,
            OpportunityMemoryService opportunityMemory,
            AlertMailService alertMailService,
            DevelopmentRuntime developmentRuntime,
            DevelopmentWorkspaceService developmentWorkspace) {

        this.memory = memory;
        this.runtime = runtime;
        this.ceoService = ceoService;
        this.companyMemory = companyMemory;
        this.defaultCeoModel = defaultCeoModel;
        this.orchestratorExecutor = orchestratorExecutor;
        this.events = events;
        this.jsonMapper = jsonMapper;
        this.contradictionDetector = contradictionDetector;
        this.appProperties = appProperties;
        this.opportunityMemory = opportunityMemory;
        this.alertMailService = alertMailService;
        this.developmentRuntime = developmentRuntime;
        this.developmentWorkspace = developmentWorkspace;
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

    private record DevelopmentDefinition(
            String agentId,
            String action,
            String subdirectory,
            String objective) {
    }

    private static final List<DevelopmentDefinition> DEVELOPMENT_DEFINITIONS = List.of(
            new DevelopmentDefinition(
                    "engineering", "ARCHITECTURE_DEVELOPMENT", "architecture",
                    "Definir la arquitectura técnica y el andamiaje inicial del backend."
            ),
            new DevelopmentDefinition(
                    "backend", "BACKEND_DEVELOPMENT", "backend",
                    "Implementar la lógica de negocio y las APIs principales."
            ),
            new DevelopmentDefinition(
                    "frontend-ui", "FRONTEND_DEVELOPMENT", "frontend",
                    "Implementar la interfaz de usuario inicial."
            )
    );

    /**
     * Segunda orquestación de {@code MissionExecutor}, disparada desde
     * {@code MissionService.recordDecision} cuando el inversionista
     * aprueba la misión — mismo patrón que {@link #executeAsync} para
     * discovery (async, un método público que delega a uno interno sobre
     * {@code orchestratorExecutor}), pero para las 3 tareas de desarrollo
     * real del Engineering Team. Ver
     * docs/superpowers/specs/2026-09-21-development-generation-design.md.
     */
    public CompletableFuture<Void> executeDevelopmentAsync(
            String missionId,
            String instruction) {

        log.info("MISSION {} - submitting development orchestration", missionId);

        try {

            return CompletableFuture.runAsync(
                    () -> executeDevelopmentInternal(missionId, instruction),
                    orchestratorExecutor
            );

        } catch (Exception ex) {

            log.error("MISSION {} - could not submit development orchestration", missionId, ex);

            safeFail(missionId, ex);

            return CompletableFuture.failedFuture(ex);
        }
    }

    private void executeDevelopmentInternal(String missionId, String instruction) {

        log.info("MISSION {} - development execution started", missionId);

        try {

            var discoveryContext =
                    memory.tasks(missionId).stream()
                            .filter(t -> "OFFER_DESIGN".equals(t.action())
                                    || "DELIVERY_FEASIBILITY".equals(t.action()))
                            .filter(t -> "COMPLETED".equals(t.status()))
                            .map(t -> t.agentId() + " (" + t.action() + "):\n" + t.result())
                            .collect(Collectors.joining("\n\n"));

            var contextForPrompt =
                    discoveryContext.isBlank() ? "(sin contexto adicional)" : discoveryContext;

            var futuresByAgent =
                    new LinkedHashMap<String, CompletableFuture<DevelopmentResult>>();
            var subdirectoryByAgent =
                    new LinkedHashMap<String, String>();

            for (var definition : DEVELOPMENT_DEFINITIONS) {

                var agentId = definition.agentId();
                var taskId = missionId + "-" + agentId.toUpperCase() + "-DEV";

                memory.createTask(taskId, missionId, agentId, definition.action());

                events.publishTask(
                        "EMPRESA_TASK_CREATED", taskId, missionId, agentId, "PENDING",
                        "Tarea de desarrollo creada."
                );

                var prompt = buildDevelopmentPrompt(
                        agentId, definition.objective(), instruction, contextForPrompt
                );

                var future =
                        developmentRuntime.execute(
                                taskId, missionId, agentId, definition.action(), prompt
                        );

                futuresByAgent.put(agentId, future);
                subdirectoryByAgent.put(agentId, definition.subdirectory());
            }

            advanceMission(
                    missionId, MissionStatus.EXECUTING, 97, "Desarrollo",
                    "Los agentes están generando código real en paralelo."
            );

            var outcomes = new ArrayList<DevelopmentExecutionOutcome>();

            for (var entry : futuresByAgent.entrySet()) {

                var agentId = entry.getKey();

                try {

                    var result = entry.getValue().join();

                    outcomes.add(DevelopmentExecutionOutcome.success(agentId, result));

                } catch (Exception ex) {

                    var reason = safeMessage(ex, "El agente no completó su tarea de desarrollo.");

                    log.warn(
                            "MISSION {} - development agent {} did not complete: {}",
                            missionId, agentId, reason
                    );

                    outcomes.add(DevelopmentExecutionOutcome.failure(agentId, reason));
                }
            }

            var completedOutcomes =
                    outcomes.stream().filter(DevelopmentExecutionOutcome::completed).toList();
            var failedOutcomes =
                    outcomes.stream().filter(o -> !o.completed()).toList();

            if (completedOutcomes.isEmpty()) {

                throw new IllegalStateException(
                        "Los " + failedOutcomes.size()
                                + " agente(s) de desarrollo fallaron: "
                                + failedOutcomes.stream()
                                        .map(o -> o.agentId() + " (" + o.error() + ")")
                                        .collect(Collectors.joining("; "))
                );
            }

            var successfulWrites = new ArrayList<DevelopmentExecutionOutcome>();

            for (var outcome : completedOutcomes) {

                try {

                    developmentWorkspace.writeFiles(
                            missionId,
                            subdirectoryByAgent.get(outcome.agentId()),
                            outcome.result()
                    );

                    successfulWrites.add(outcome);

                } catch (Exception ex) {

                    log.warn(
                            "MISSION {} - could not write files for agent {}: {}",
                            missionId, outcome.agentId(), ex.getMessage()
                    );
                }
            }

            if (successfulWrites.isEmpty()) {

                throw new IllegalStateException(
                        "Ningún agente pudo escribir archivos reales al workspace "
                                + "(writeFiles falló para los " + completedOutcomes.size()
                                + " agente(s) que completaron su tarea de desarrollo)."
                );
            }

            // El commit consolidado solo debe reflejar lo que realmente se
            // escribió a disco — nunca un agente cuyo writeFiles falló.
            var commitMessage =
                    "Desarrollo generado por Forjai Engineering Team\n\n"
                            + successfulWrites.stream()
                                    .map(o -> "- " + o.agentId() + ": " + o.result().summary())
                                    .collect(Collectors.joining("\n"));

            developmentWorkspace.commitWorkspace(missionId, commitMessage);

            var statusMessage =
                    failedOutcomes.isEmpty() && successfulWrites.size() == completedOutcomes.size()
                            ? "Los " + successfulWrites.size()
                                    + " agente(s) completaron el desarrollo real y escribieron sus archivos."
                            : "Desarrollo parcial — " + successfulWrites.size() + " de "
                                    + DEVELOPMENT_DEFINITIONS.size()
                                    + " agente(s) escribieron archivos reales."
                                    + (failedOutcomes.isEmpty()
                                            ? ""
                                            : " Agentes fallidos: "
                                                    + failedOutcomes.stream()
                                                            .map(DevelopmentExecutionOutcome::agentId)
                                                            .collect(Collectors.joining(", ")));

            advanceMission(
                    missionId, MissionStatus.COMPLETED, 100, "Desarrollo completado", statusMessage
            );

            log.info("MISSION {} -> COMPLETED (desarrollo)", missionId);

        } catch (Exception ex) {

            log.error("MISSION {} - development execution failed", missionId, ex);

            safeFail(missionId, ex);
        }
    }

    private String buildDevelopmentPrompt(
            String agentId,
            String objective,
            String instruction,
            String discoveryContext) {

        return """
                Estás trabajando dentro de Forjai como el agente %s del Engineering Team.
                La misión ya fue aprobada por el inversionista humano — tu tarea es
                generar código real (archivos completos, no solo un plan) para
                arrancar el desarrollo.

                OBJETIVO ESPECÍFICO: %s

                REGLAS:
                - Genera archivos de código reales y completos, no pseudocódigo ni
                  placeholders sin terminar.
                - Cada "path" debe ser relativo (nunca empezar con "/", nunca
                  contener "..").
                - Responde ÚNICAMENTE con JSON válido, sin Markdown, sin texto
                  antes o después.

                MISIÓN:
                %s

                CONTEXTO DE DISCOVERY YA VALIDADO:
                %s
                """.formatted(agentId, objective, instruction, discoveryContext);
    }

    private void executeInternal(
            String missionId,
            String instruction) {

        log.info(
                "MISSION {} - async execution started",
                missionId
        );

        try {

            advanceMission(
                    missionId,
                    MissionStatus.PLANNING,
                    5,
                    "Planificación",
                    "CEO está definiendo el trabajo de la misión."
            );

            log.info(
                    "MISSION {} -> PLANNING",
                    missionId
            );

            advanceMission(
                    missionId,
                    MissionStatus.DELEGATING,
                    10,
                    "Delegación",
                    "Asignando tareas paralelas a Sales, Product, Finance, Engineering y QA."
            );

            log.info(
                    "MISSION {} -> DELEGATING",
                    missionId
            );

            var definitions = List.of(

                    new AgentDefinition(
                            "sales",
                            "MARKET_DISCOVERY",
                            "Identificar perfiles de clientes y señales de demanda que deban validarse."
                    ),

                    new AgentDefinition(
                            "product",
                            "OFFER_DESIGN",
                            "Definir una oferta mínima vendible alineada con las restricciones de capital."
                    ),

                    new AgentDefinition(
                            "finance",
                            "UNIT_ECONOMICS",
                            "Estimar costos, precio, margen y condiciones necesarias para superar US$50 de utilidad neta."
                    ),

                    new AgentDefinition(
                            "engineering",
                            "DELIVERY_FEASIBILITY",
                            "Evaluar la capacidad de entregar la oferta con los recursos tecnológicos disponibles."
                    ),

                    new AgentDefinition(
                            "qa",
                            "QUALITY_RISK_REVIEW",
                            "Identificar, de forma independiente a los demás agentes (esta tarea corre en paralelo, no tiene acceso a sus resultados), riesgos, huecos de evidencia y supuestos no verificados en la oportunidad de negocio descrita en la misión, antes de comprometer capital."
                    )
            );

            var definitionsByAgent =
                    definitions.stream()
                            .collect(Collectors.toMap(
                                    AgentDefinition::agentId,
                                    definition -> definition
                            ));

            var futuresByAgent =
                    new LinkedHashMap<String, CompletableFuture<AgentResult>>();

            for (var definition : definitions) {

                var agentId = definition.agentId();
                var action = definition.action();
                var objective = definition.objective();

                var taskId =
                        missionId
                                + "-"
                                + agentId.toUpperCase();

                log.info(
                        "MISSION {} - creating task {} for agent {}",
                        missionId,
                        taskId,
                        agentId
                );

                memory.createTask(
                        taskId,
                        missionId,
                        agentId,
                        action
                );

                events.publishTask(
                        "EMPRESA_TASK_CREATED",
                        taskId,
                        missionId,
                        agentId,
                        "PENDING",
                        "Tarea creada."
                );

                var future =
                        runtime.execute(
                                taskId,
                                missionId,
                                agentId,
                                action,
                                instruction
                                        + "\nObjetivo específico: "
                                        + objective
                        );

                futuresByAgent.put(agentId, future);
            }

            advanceMission(
                    missionId,
                    MissionStatus.WAITING_AGENT_RESULTS,
                    30,
                    "Trabajo paralelo",
                    "Los agentes están trabajando en paralelo."
            );

            log.info(
                    "MISSION {} -> WAITING_AGENT_RESULTS",
                    missionId
            );

            /*
             * Agent failure != Mission failure: antes, un solo agente que
             * agotara sus reintentos tumbaba `allOf(...).join()`, que
             * propagaba la excepción y mandaba TODA la misión a FAILED,
             * descartando cualquier resultado de los demás agentes que sí
             * hubieran completado. Ahora se espera a cada agente por
             * separado (igual se espera a todos antes de seguir — no se
             * corta ni se acelera nada) y se captura éxito/fallo por
             * agente en un `AgentExecutionOutcome`. Solo si TODOS los
             * agentes fallan no hay nada que consolidar y la misión sí
             * falla; si al menos uno completó, la misión sigue con
             * resultado parcial y el CEO recibe explícitamente qué agentes
             * faltan y por qué, para que sea su consolidación (no un
             * `catch` genérico) la que decida qué hacer con el hueco.
             */
            List<AgentExecutionOutcome> outcomes =
                    new ArrayList<>();

            for (var entry : futuresByAgent.entrySet()) {

                var agentId = entry.getKey();

                try {

                    var result = entry.getValue().join();

                    outcomes.add(
                            AgentExecutionOutcome.success(agentId, result)
                    );

                } catch (Exception ex) {

                    var reason =
                            safeMessage(
                                    ex,
                                    "El agente no completó su tarea."
                            );

                    log.warn(
                            "MISSION {} - agent {} did not complete, "
                                    + "continuing with partial results: {}",
                            missionId,
                            agentId,
                            reason
                    );

                    outcomes.add(
                            AgentExecutionOutcome.failure(agentId, reason)
                    );
                }
            }

            log.info(
                    "MISSION {} - all agent tasks settled",
                    missionId
            );

            /*
             * Replanificación automática: un agente que agotó sus 3
             * intentos internos (ver "Reintento de resultados de agente")
             * todavía puede recuperarse a nivel de misión — se le da hasta
             * `MAX_AGENT_REPLANS` oportunidades más de correr su tarea
             * desde cero (turno de decisión + turno final nuevos, no una
             * continuación del intento fallido). Es un nivel de reintento
             * distinto y por encima del interno de `AgentRuntime`: ese ya
             * se agotó cuando llegamos aquí.
             */
            outcomes = replanFailedAgents(
                    missionId,
                    instruction,
                    definitionsByAgent,
                    outcomes
            );

            advanceMission(
                    missionId,
                    MissionStatus.EVALUATING,
                    70,
                    "Evaluación",
                    "Todos los agentes terminaron. CEO está revisando resultados."
            );

            var agentResults =
                    outcomes.stream()
                            .filter(AgentExecutionOutcome::completed)
                            .map(AgentExecutionOutcome::result)
                            .toList();

            var failedAgents =
                    outcomes.stream()
                            .filter(outcome -> !outcome.completed())
                            .toList();

            if (agentResults.isEmpty()) {

                throw new IllegalStateException(
                        "Los "
                                + failedAgents.size()
                                + " agente(s) de la misión fallaron: "
                                + failedAgents.stream()
                                        .map(o -> o.agentId() + " (" + o.error() + ")")
                                        .collect(Collectors.joining("; "))
                );
            }

            if (!failedAgents.isEmpty()) {

                log.warn(
                        "MISSION {} - continuing with partial results, "
                                + "failed agents={}",
                        missionId,
                        failedAgents.stream()
                                .map(AgentExecutionOutcome::agentId)
                                .toList()
                );
            }

            /*
             * Flujo Opportunity -> Customer candidato (100% nivel 🟢,
             * `empresa.md` §5): la misión produjo al menos un resultado,
             * así que hay una oportunidad de negocio real que registrar.
             * Los candidatos de cliente que cada agente haya identificado
             * (campo opcional `AgentResult.customerCandidates`, casi
             * siempre vacío) quedan como nodos `Customer {status:'LEAD'}`
             * separados de los clientes reales de `CustomerController`
             * (canal humano, sin cambios) — ver
             * `OpportunityMemoryService` para el porqué de esa separación.
             */
            opportunityMemory.recordOpportunity(missionId, instruction);

            for (var result : agentResults) {

                opportunityMemory.recordCandidates(
                        missionId,
                        result.agent(),
                        result.customerCandidates()
                );
            }

            /*
             * Recuperamos los resultados estructurados.
             *
             * Ya no concatenamos texto libre generado por los agentes.
             */
            var structuredResults =
                    serializeAgentResults(
                            agentResults
                    );

            log.info(
                    "MISSION {} - structured agent results generated agents={}",
                    missionId,
                    agentResults.size()
            );

            var contradictions =
                    contradictionDetector.detect(
                            agentResults,
                            appProperties.seedCapitalUsd()
                    );

            if (!contradictions.isEmpty()) {

                log.warn(
                        "MISSION {} - contradictions detected: {}",
                        missionId,
                        contradictions
                );
            }

            var resultsForCeo =
                    structuredResults;

            if (!contradictions.isEmpty()) {

                resultsForCeo +=
                        "\n\nCONTRADICCIONES_DETECTADAS "
                                + "(reglas deterministas, no del modelo; "
                                + "no las ignores al consolidar):\n- "
                                + String.join("\n- ", contradictions);
            }

            if (!failedAgents.isEmpty()) {

                resultsForCeo +=
                        "\n\nAGENTES_FALLIDOS (no completaron su tarea "
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

            var finalResult =
                    ceoService.executeMission(
                            instruction,
                            resultsForCeo,
                            companyMemory.agentModel("ceo", defaultCeoModel)
                    );

            advanceMission(
                    missionId,
                    MissionStatus.AWAITING_INVESTOR,
                    95,
                    "Recomendación",
                    finalResult
            );

            log.info(
                    "MISSION {} -> AWAITING_INVESTOR",
                    missionId
            );

        } catch (Exception ex) {

            log.error(
                    "MISSION {} - execution failed",
                    missionId,
                    ex
            );

            safeFail(
                    missionId,
                    ex
            );
        }
    }

    /**
     * Le da a cada agente que agotó sus reintentos internos hasta
     * {@code MAX_AGENT_REPLANS} oportunidades más de correr su tarea desde
     * cero antes de aceptarlo como definitivamente fallido. Publica
     * {@code EMPRESA_MISSION_REPLANNED} (`EMPRESA_AI_NUEVO_TODO_EVIDENCE.md`
     * §22) por cada intento de replan, con el error anterior como dato —
     * así queda auditable cuántas veces y por qué se reintentó a este
     * nivel, no solo a nivel de `AgentRuntime`.
     */
    private List<AgentExecutionOutcome> replanFailedAgents(
            String missionId,
            String instruction,
            Map<String, AgentDefinition> definitionsByAgent,
            List<AgentExecutionOutcome> outcomes) {

        var settled = new ArrayList<AgentExecutionOutcome>();

        for (var outcome : outcomes) {

            var current = outcome;
            var replanAttempt = 0;

            while (!current.completed() && replanAttempt < MAX_AGENT_REPLANS) {

                replanAttempt++;

                var agentId = current.agentId();
                var definition = definitionsByAgent.get(agentId);
                var taskId = missionId + "-" + agentId.toUpperCase();

                log.warn(
                        "MISSION {} - replanning agent {} (attempt {} of {}) after: {}",
                        missionId,
                        agentId,
                        replanAttempt,
                        MAX_AGENT_REPLANS,
                        current.error()
                );

                events.publish(
                        "EMPRESA_MISSION_REPLANNED",
                        missionId,
                        taskId,
                        agentId,
                        Map.of(
                                "replanAttempt", replanAttempt,
                                "previousError",
                                current.error() == null ? "" : current.error()
                        )
                );

                memory.createTask(
                        taskId,
                        missionId,
                        agentId,
                        definition.action()
                );

                events.publishTask(
                        "EMPRESA_TASK_CREATED",
                        taskId,
                        missionId,
                        agentId,
                        "PENDING",
                        "Tarea replanificada a nivel de misión (intento "
                                + replanAttempt
                                + " de "
                                + MAX_AGENT_REPLANS
                                + ")."
                );

                var future =
                        runtime.execute(
                                taskId,
                                missionId,
                                agentId,
                                definition.action(),
                                instruction
                                        + "\nObjetivo específico: "
                                        + definition.objective()
                        );

                try {

                    var result = future.join();

                    current = AgentExecutionOutcome.success(agentId, result);

                    log.info(
                            "MISSION {} - agent {} recovered after replan attempt {}",
                            missionId,
                            agentId,
                            replanAttempt
                    );

                } catch (Exception ex) {

                    current = AgentExecutionOutcome.failure(
                            agentId,
                            safeMessage(ex, "El agente no completó su tarea.")
                    );
                }
            }

            settled.add(current);
        }

        return settled;
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
