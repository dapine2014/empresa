package com.aicompany.core.service;

import com.aicompany.core.agent.AgentRuntime;
import com.aicompany.core.agent.model.AgentResult;
import com.aicompany.core.agent.validation.ContradictionDetector;
import com.aicompany.core.config.AppProperties;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.MissionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Service
public class MissionExecutor {

    private static final Logger log =
            LoggerFactory.getLogger(MissionExecutor.class);

    private final MissionMemoryService memory;
    private final AgentRuntime runtime;
    private final CeoService ceoService;
    private final Executor orchestratorExecutor;
    private final CompanyEventPublisher events;
    private final JsonMapper jsonMapper;
    private final ContradictionDetector contradictionDetector;
    private final AppProperties appProperties;

    public MissionExecutor(
            MissionMemoryService memory,
            AgentRuntime runtime,
            CeoService ceoService,
            @Qualifier("missionOrchestratorExecutor")
            Executor orchestratorExecutor,
            CompanyEventPublisher events,
            JsonMapper jsonMapper,
            ContradictionDetector contradictionDetector,
            AppProperties appProperties) {

        this.memory = memory;
        this.runtime = runtime;
        this.ceoService = ceoService;
        this.orchestratorExecutor = orchestratorExecutor;
        this.events = events;
        this.jsonMapper = jsonMapper;
        this.contradictionDetector = contradictionDetector;
        this.appProperties = appProperties;
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

                    new String[]{
                            "sales",
                            "MARKET_DISCOVERY",
                            "Identificar perfiles de clientes y señales de demanda que deban validarse."
                    },

                    new String[]{
                            "product",
                            "OFFER_DESIGN",
                            "Definir una oferta mínima vendible alineada con las restricciones de capital."
                    },

                    new String[]{
                            "finance",
                            "UNIT_ECONOMICS",
                            "Estimar costos, precio, margen y condiciones necesarias para superar US$50 de utilidad neta."
                    },

                    new String[]{
                            "engineering",
                            "DELIVERY_FEASIBILITY",
                            "Evaluar la capacidad de entregar la oferta con los recursos tecnológicos disponibles."
                    },

                    new String[]{
                            "qa",
                            "QUALITY_RISK_REVIEW",
                            "Identificar, de forma independiente a los demás agentes (esta tarea corre en paralelo, no tiene acceso a sus resultados), riesgos, huecos de evidencia y supuestos no verificados en la oportunidad de negocio descrita en la misión, antes de comprometer capital."
                    }
            );

            var futures =
                    new ArrayList<CompletableFuture<AgentResult>>();

            for (var definition : definitions) {

                var agentId = definition[0];
                var action = definition[1];
                var objective = definition[2];

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

                futures.add(future);
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

            CompletableFuture.allOf(
                    futures.toArray(
                            new CompletableFuture[0]
                    )
            ).join();

            log.info(
                    "MISSION {} - all agent tasks completed",
                    missionId
            );

            advanceMission(
                    missionId,
                    MissionStatus.EVALUATING,
                    70,
                    "Evaluación",
                    "Todos los agentes terminaron. CEO está revisando resultados."
            );

            /*
             * Recuperamos los resultados estructurados.
             *
             * Ya no concatenamos texto libre generado por los agentes.
             */
            var agentResults =
                    futures.stream()
                            .map(CompletableFuture::join)
                            .toList();

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
                    contradictions.isEmpty()
                            ? structuredResults
                            : structuredResults
                                    + "\n\nCONTRADICCIONES_DETECTADAS "
                                    + "(reglas deterministas, no del modelo; "
                                    + "no las ignores al consolidar):\n- "
                                    + String.join("\n- ", contradictions);

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
                            resultsForCeo
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
    }
}
