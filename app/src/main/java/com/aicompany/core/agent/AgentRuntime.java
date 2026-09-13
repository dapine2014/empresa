package com.aicompany.core.agent;

import com.aicompany.core.agent.model.AgentResult;
import com.aicompany.core.agent.validation.AgentResultValidator;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.service.CeoService;
import com.aicompany.core.service.MissionMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Service
public class AgentRuntime {

    private static final Logger log =
            LoggerFactory.getLogger(AgentRuntime.class);

    private final CeoService ceoService;
    private final MissionMemoryService memory;
    private final Executor agentTaskExecutor;
    private final CompanyEventPublisher events;
    private final AgentResultValidator validator;
    private final JsonMapper jsonMapper;

    public AgentRuntime(
            CeoService ceoService,
            MissionMemoryService memory,
            @Qualifier("agentTaskExecutor") Executor agentTaskExecutor,
            CompanyEventPublisher events,
            AgentResultValidator validator,
            JsonMapper jsonMapper) {

        this.ceoService = ceoService;
        this.memory = memory;
        this.agentTaskExecutor = agentTaskExecutor;
        this.events = events;
        this.validator = validator;
        this.jsonMapper = jsonMapper;
    }

    public CompletableFuture<AgentResult> execute(
            String taskId,
            String missionId,
            String agentId,
            String action,
            String instruction) {

        log.info(
                "TASK {} - submitting agent {} action {}",
                taskId,
                agentId,
                action
        );

        try {

            return CompletableFuture.supplyAsync(
                    () -> executeInternal(
                            taskId,
                            missionId,
                            agentId,
                            action,
                            instruction
                    ),
                    agentTaskExecutor
            );

        } catch (Exception ex) {

            var message =
                    safeMessage(
                            ex,
                            "No se pudo iniciar la tarea."
                    );

            memory.updateTask(
                    taskId,
                    "FAILED",
                    message
            );

            events.publishTask(
                    "EMPRESA_TASK_FAILED",
                    taskId,
                    missionId,
                    agentId,
                    "FAILED",
                    message
            );

            log.error(
                    "TASK {} - could not submit",
                    taskId,
                    ex
            );

            return CompletableFuture.failedFuture(ex);
        }
    }

    private AgentResult executeInternal(
            String taskId,
            String missionId,
            String agentId,
            String action,
            String instruction) {

        log.info(
                "TASK {} - agent {} started",
                taskId,
                agentId
        );

        memory.updateTask(
                taskId,
                "RUNNING",
                "Agente iniciado."
        );

        events.publishTask(
                "EMPRESA_TASK_STARTED",
                taskId,
                missionId,
                agentId,
                "RUNNING",
                "Agente iniciado."
        );

        try {

            var prompt = buildPrompt(
                    agentId,
                    action,
                    instruction
            );

            var result =
                    ceoService.executeAgentTask(
                            agentId,
                            prompt
                    );

            var validation =
                    validator.validate(result);

            if (!validation.valid()) {

                var message =
                        "Resultado de agente inválido: "
                                + String.join(
                                "; ",
                                validation.errors()
                        );

                log.warn(
                        "TASK {} - agent {} rejected by validation: {}",
                        taskId,
                        agentId,
                        validation.errors()
                );

                throw new IllegalStateException(
                        message
                );
            }

            var resultJson =
                    toJson(result);

            memory.updateTask(
                    taskId,
                    "COMPLETED",
                    resultJson
            );

            events.publishTask(
                    "EMPRESA_TASK_COMPLETED",
                    taskId,
                    missionId,
                    agentId,
                    "COMPLETED",
                    resultJson
            );

            log.info(
                    "TASK {} - agent {} completed " +
                    "verificationStatus={} confidence={}",
                    taskId,
                    agentId,
                    result.verificationStatus(),
                    result.confidence()
            );

            return result;

        } catch (Exception ex) {

            var message =
                    safeMessage(
                            ex,
                            "Error inesperado"
                    );

            memory.updateTask(
                    taskId,
                    "FAILED",
                    message
            );

            events.publishTask(
                    "EMPRESA_TASK_FAILED",
                    taskId,
                    missionId,
                    agentId,
                    "FAILED",
                    message
            );

            log.error(
                    "TASK {} - agent {} failed",
                    taskId,
                    agentId,
                    ex
            );

            throw ex;
        }
    }

    private String buildPrompt(
            String agentId,
            String action,
            String instruction) {

        return """
                Estás trabajando dentro de AI Company como el agente %s.
                Esta es una tarea real dentro de una misión empresarial.

                REGLAS:

                - No inventes clientes, ventas, ingresos, costos,
                  fuentes o evidencia.
                - Una hipótesis NO es un hecho.
                - Una estimación NO es un hecho.
                - La evidencia requerida NO es evidencia.
                - Solo marca verified=true cuando exista
                  una fuente realmente verificable.
                - Solo utiliza verificationStatus=VALIDATED
                  cuando exista evidencia verificada.
                - Si no puedes verificar algo,
                  utiliza NOT_VALIDATED.
                - No declares un resultado futuro como
                  resultado verificado.
                - No presentes cálculos sin comprobarlos.
                - Responde ÚNICAMENTE con JSON válido.
                - No utilices Markdown.
                - No agregues texto antes o después del JSON.

                FORMATO OBLIGATORIO:

                {
                  "agent": "%s",
                  "action": "%s",
                  "verificationStatus": "NOT_VALIDATED",
                  "facts": [],
                  "hypotheses": [],
                  "estimates": [],
                  "evidence": [],
                  "evidenceRequired": [],
                  "calculations": [],
                  "risks": [],
                  "recommendation": "",
                  "confidence": 0.0
                }

                EVIDENCE:

                {
                  "description": "",
                  "source": "",
                  "sourceType": "WEB|CUSTOMER|TRANSACTION|INTERNAL|NONE",
                  "verified": false
                }

                CALCULATION:

                {
                  "name": "",
                  "inputA": 0,
                  "inputB": 0,
                  "operation": "ADD|SUBTRACT",
                  "result": 0
                }

                ACCIÓN:
                %s

                MISIÓN:
                %s
                """.formatted(
                agentId,
                agentId,
                action,
                action,
                instruction
        );
    }

    private String toJson(
            AgentResult result) {

        try {

            return jsonMapper.writeValueAsString(
                    result
            );

        } catch (JacksonException ex) {

            throw new IllegalStateException(
                    "No se pudo serializar el resultado del agente.",
                    ex
            );
        }
    }

    private String safeMessage(
            Exception ex,
            String defaultMessage) {

        return ex.getMessage() == null
                || ex.getMessage().isBlank()
                ? defaultMessage
                : ex.getMessage();
    }
}
