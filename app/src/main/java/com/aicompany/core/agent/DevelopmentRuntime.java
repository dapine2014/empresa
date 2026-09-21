package com.aicompany.core.agent;

import com.aicompany.core.agent.model.DevelopmentResult;
import com.aicompany.core.agent.validation.DevelopmentPathValidationGate;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.service.CeoService;
import com.aicompany.core.service.CompanyMemoryService;
import com.aicompany.core.service.MissionMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Espejo deliberado de {@code AgentRuntime} para un contrato distinto
 * ({@link DevelopmentResult}, no {@code AgentResult}) — ver
 * docs/superpowers/specs/2026-09-21-development-generation-design.md,
 * decisión 4. No se generalizó {@code AgentRuntime} para aceptar un
 * contrato pluggable: evita tocar código de discovery ya probado en
 * producción para una necesidad que hoy tiene un solo caso de uso.
 */
@Service
public class DevelopmentRuntime {

    private static final Logger log =
            LoggerFactory.getLogger(DevelopmentRuntime.class);

    private static final int MAX_RESULT_RETRIES = 2;

    private final CeoService ceoService;
    private final MissionMemoryService memory;
    private final CompanyMemoryService companyMemory;
    private final String defaultAgentModel;
    private final Executor agentTaskExecutor;
    private final CompanyEventPublisher events;
    private final DevelopmentPathValidationGate pathGate;
    private final JsonMapper jsonMapper;

    public DevelopmentRuntime(
            CeoService ceoService,
            MissionMemoryService memory,
            CompanyMemoryService companyMemory,
            @Value("${ollama.agent-model}") String defaultAgentModel,
            @Qualifier("agentTaskExecutor") Executor agentTaskExecutor,
            CompanyEventPublisher events,
            DevelopmentPathValidationGate pathGate,
            JsonMapper jsonMapper) {

        this.ceoService = ceoService;
        this.memory = memory;
        this.companyMemory = companyMemory;
        this.defaultAgentModel = defaultAgentModel;
        this.agentTaskExecutor = agentTaskExecutor;
        this.events = events;
        this.pathGate = pathGate;
        this.jsonMapper = jsonMapper;
    }

    public CompletableFuture<DevelopmentResult> execute(
            String taskId,
            String missionId,
            String agentId,
            String action,
            String prompt) {

        log.info(
                "TASK {} - submitting development agent {} action {}",
                taskId, agentId, action
        );

        try {

            return CompletableFuture.supplyAsync(
                    () -> executeInternal(taskId, missionId, agentId, prompt),
                    agentTaskExecutor
            );

        } catch (Exception ex) {

            var message = safeMessage(ex, "No se pudo iniciar la tarea de desarrollo.");

            memory.updateTask(taskId, "FAILED", message);

            events.publishTask(
                    "EMPRESA_TASK_FAILED", taskId, missionId, agentId, "FAILED", message
            );

            log.error("TASK {} - could not submit development task", taskId, ex);

            return CompletableFuture.failedFuture(ex);
        }
    }

    private DevelopmentResult executeInternal(
            String taskId,
            String missionId,
            String agentId,
            String prompt) {

        log.info("TASK {} - development agent {} started", taskId, agentId);

        memory.updateTask(taskId, "RUNNING", "Agente iniciado.");

        var model = companyMemory.agentModel(agentId, defaultAgentModel);

        memory.setAgentStatus(agentId, "WORKING");

        events.publishTask(
                "EMPRESA_TASK_STARTED", taskId, missionId, agentId, "RUNNING", "Agente iniciado."
        );

        try {

            DevelopmentResult result = null;
            String validationFeedback = null;

            for (int attempt = 0; attempt <= MAX_RESULT_RETRIES; attempt++) {

                var attemptPrompt = prompt;

                if (validationFeedback != null) {

                    attemptPrompt += """

                            CORRECCIÓN DEL INTENTO ANTERIOR

                            El resultado anterior fue rechazado.
                            Corrige únicamente los errores indicados.

                            ERRORES:
                            %s

                            Nunca uses rutas absolutas ni ".." en los paths.
                            """.formatted(validationFeedback);
                }

                if (attempt > 0) {

                    events.publishTask(
                            "EMPRESA_TASK_RETRY", taskId, missionId, agentId, "RETRYING",
                            "Reintentando resultado de desarrollo. Intento "
                                    + (attempt + 1) + " de " + (MAX_RESULT_RETRIES + 1)
                    );
                }

                try {

                    result = ceoService.generateDevelopmentArtifact(agentId, attemptPrompt, model);

                } catch (Exception ex) {

                    var reason = safeMessage(ex, "El agente no devolvió una respuesta procesable.");

                    if (attempt == MAX_RESULT_RETRIES) {

                        throw new IllegalStateException(
                                "El agente " + agentId
                                        + " no produjo un DevelopmentResult procesable después de "
                                        + (MAX_RESULT_RETRIES + 1) + " intentos: " + reason,
                                ex
                        );
                    }

                    validationFeedback = reason;
                    continue;
                }

                var validation = pathGate.validate(result);

                if (validation.valid()) {
                    break;
                }

                validationFeedback = String.join("\n- ", validation.errors());

                if (attempt == MAX_RESULT_RETRIES) {

                    throw new IllegalStateException(
                            "Rutas de archivo inválidas después de "
                                    + (MAX_RESULT_RETRIES + 1) + " intentos: " + validationFeedback
                    );
                }
            }

            var resultJson = toJson(result);

            memory.updateTask(taskId, "COMPLETED", resultJson);

            events.publishTask(
                    "EMPRESA_TASK_COMPLETED", taskId, missionId, agentId, "COMPLETED", resultJson
            );

            log.info(
                    "TASK {} - development agent {} completed files={}",
                    taskId, agentId, result.files().size()
            );

            return result;

        } catch (Exception ex) {

            var message = safeMessage(ex, "Error inesperado");

            memory.updateTask(taskId, "FAILED", message);

            events.publishTask(
                    "EMPRESA_TASK_FAILED", taskId, missionId, agentId, "FAILED", message
            );

            log.error("TASK {} - development agent {} failed", taskId, agentId, ex);

            throw ex;

        } finally {

            memory.setAgentStatus(agentId, "IDLE");
        }
    }

    private String toJson(DevelopmentResult result) {
        try {
            return jsonMapper.writeValueAsString(result);
        } catch (JacksonException ex) {
            throw new IllegalStateException("No se pudo serializar el resultado de desarrollo.", ex);
        }
    }

    private String safeMessage(Exception ex, String defaultMessage) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? defaultMessage : ex.getMessage();
    }
}
