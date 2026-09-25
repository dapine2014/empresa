package com.aicompany.core.service;

import com.aicompany.core.agent.AgentRuntime;
import com.aicompany.core.agent.model.AgentResult;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.AgentExecutionOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Crear tareas → ejecutarlas en paralelo vía AgentRuntime → esperar a cada
 * agente por separado ("agent failure ≠ mission failure") → replanificar
 * los fallidos. Extraído tal cual de MissionExecutor para que lo usen el
 * flujo de discovery y AnalysisTeamStrategy (spec §2).
 */
@Service
public class AgentTaskBatchRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskBatchRunner.class);

    /**
     * Reintentos a nivel de MISIÓN para un agente que ya agotó sus 3
     * intentos internos: una segunda oportunidad completa desde cero, no
     * una corrección incremental.
     */
    private static final int MAX_AGENT_REPLANS = 1;

    /** kind == null en discovery (la tarea se crea exactamente como antes). */
    public record AgentTaskDefinition(String agentId, String action, String objective, String kind) {
    }

    private final MissionMemoryService memory;
    private final AgentRuntime runtime;
    private final CompanyEventPublisher events;

    public AgentTaskBatchRunner(MissionMemoryService memory, AgentRuntime runtime, CompanyEventPublisher events) {
        this.memory = memory;
        this.runtime = runtime;
        this.events = events;
    }

    public List<AgentExecutionOutcome> run(
            String missionId,
            String instruction,
            List<AgentTaskDefinition> definitions,
            Runnable onSubmitted) {

        var definitionsByAgent = definitions.stream()
                .collect(Collectors.toMap(AgentTaskDefinition::agentId, Function.identity()));

        var futuresByAgent = new LinkedHashMap<String, CompletableFuture<AgentResult>>();

        for (var definition : definitions) {

            var agentId = definition.agentId();
            var taskId = missionId + "-" + agentId.toUpperCase();

            log.info("MISSION {} - creating task {} for agent {}", missionId, taskId, agentId);

            createTask(taskId, missionId, definition);

            events.publishTask("EMPRESA_TASK_CREATED", taskId, missionId, agentId, "PENDING", "Tarea creada.");

            futuresByAgent.put(agentId, runtime.execute(
                    taskId, missionId, agentId, definition.action(),
                    instruction + "\nObjetivo específico: " + definition.objective()));
        }

        onSubmitted.run();

        List<AgentExecutionOutcome> outcomes = new ArrayList<>();

        for (var entry : futuresByAgent.entrySet()) {

            var agentId = entry.getKey();

            try {

                outcomes.add(AgentExecutionOutcome.success(agentId, entry.getValue().join()));

            } catch (Exception ex) {

                var reason = safeMessage(ex, "El agente no completó su tarea.");

                log.warn("MISSION {} - agent {} did not complete, continuing with partial results: {}",
                        missionId, agentId, reason);

                outcomes.add(AgentExecutionOutcome.failure(agentId, reason));
            }
        }

        log.info("MISSION {} - all agent tasks settled", missionId);

        return replanFailedAgents(missionId, instruction, definitionsByAgent, outcomes);
    }

    private void createTask(String taskId, String missionId, AgentTaskDefinition definition) {
        if (definition.kind() == null) {
            memory.createTask(taskId, missionId, definition.agentId(), definition.action());
        } else {
            memory.createTask(taskId, missionId, definition.agentId(), definition.action(), definition.kind());
        }
    }

    private List<AgentExecutionOutcome> replanFailedAgents(
            String missionId,
            String instruction,
            Map<String, AgentTaskDefinition> definitionsByAgent,
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

                log.warn("MISSION {} - replanning agent {} (attempt {} of {}) after: {}",
                        missionId, agentId, replanAttempt, MAX_AGENT_REPLANS, current.error());

                events.publish(
                        "EMPRESA_MISSION_REPLANNED",
                        missionId,
                        taskId,
                        agentId,
                        Map.of(
                                "replanAttempt", replanAttempt,
                                "previousError", current.error() == null ? "" : current.error()
                        )
                );

                createTask(taskId, missionId, definition);

                events.publishTask(
                        "EMPRESA_TASK_CREATED",
                        taskId,
                        missionId,
                        agentId,
                        "PENDING",
                        "Tarea replanificada a nivel de misión (intento " + replanAttempt
                                + " de " + MAX_AGENT_REPLANS + ")."
                );

                var future = runtime.execute(taskId, missionId, agentId, definition.action(),
                        instruction + "\nObjetivo específico: " + definition.objective());

                try {

                    current = AgentExecutionOutcome.success(agentId, future.join());

                    log.info("MISSION {} - agent {} recovered after replan attempt {}",
                            missionId, agentId, replanAttempt);

                } catch (Exception ex) {

                    current = AgentExecutionOutcome.failure(agentId,
                            safeMessage(ex, "El agente no completó su tarea."));
                }
            }

            settled.add(current);
        }

        return settled;
    }

    private String safeMessage(Exception ex, String defaultMessage) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? defaultMessage : ex.getMessage();
    }
}
