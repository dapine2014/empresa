package com.aicompany.core.agent;

import com.aicompany.core.agent.model.AgentResult;
import com.aicompany.core.agent.validation.AgentResultValidator;
import com.aicompany.core.agent.validation.EvidenceBindingGate;
import com.aicompany.core.agent.validation.EvidenceValidationGate;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.service.CeoService;
import com.aicompany.core.service.MissionMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Service
public class AgentRuntime {

    private static final Logger log =
            LoggerFactory.getLogger(AgentRuntime.class);

    /**
     * Intento 1 → normal. Intento 2 → corrección. Intento 3 → corrección
     * final. Se reintenta tanto si {@link CeoService#executeAgentTask}
     * lanza excepción (Ollama no devolvió JSON parseable) como si
     * {@link AgentResultValidator} rechaza el resultado ya parseado — en
     * ambos casos el agente recibe el motivo exacto del rechazo y se le
     * pide corregir solo eso, en vez de fallar la tarea al primer intento.
     */
    private static final int MAX_RESULT_RETRIES = 2;

    private final CeoService ceoService;
    private final MissionMemoryService memory;
    private final Executor agentTaskExecutor;
    private final CompanyEventPublisher events;
    private final AgentResultValidator validator;
    private final EvidenceValidationGate evidenceGate;
    private final EvidenceBindingGate evidenceBindingGate;
    private final JsonMapper jsonMapper;

    public AgentRuntime(
            CeoService ceoService,
            MissionMemoryService memory,
            @Qualifier("agentTaskExecutor") Executor agentTaskExecutor,
            CompanyEventPublisher events,
            AgentResultValidator validator,
            EvidenceValidationGate evidenceGate,
            EvidenceBindingGate evidenceBindingGate,
            JsonMapper jsonMapper) {

        this.ceoService = ceoService;
        this.memory = memory;
        this.agentTaskExecutor = agentTaskExecutor;
        this.events = events;
        this.validator = validator;
        this.evidenceGate = evidenceGate;
        this.evidenceBindingGate = evidenceBindingGate;
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

            AgentResult result = null;
            // LinkedHashSet, no List: se ACUMULA entre intentos (no se
            // sobrescribe) — si el intento 1 buscó y confirmó URLs reales
            // pero no las citó, y el intento 2 no vuelve a pedir la
            // herramienta (su turno de decisión es independiente cada
            // vez), esas URLs reales no deben "olvidarse". El agente sigue
            // debiendo citar al menos una fuente real de cualquier intento
            // anterior, no solo del intento actual.
            var confirmedEvidenceUrls = new LinkedHashSet<String>();
            String validationFeedback = null;

            for (int attempt = 0; attempt <= MAX_RESULT_RETRIES; attempt++) {

                var prompt = buildPrompt(
                        agentId,
                        action,
                        instruction
                );

                var taskSummary = buildTaskSummary(
                        action,
                        instruction
                );

                if (validationFeedback != null) {

                    prompt += """

                            CORRECCIÓN DEL INTENTO ANTERIOR

                            El resultado anterior fue rechazado.
                            Corrige únicamente los errores indicados.

                            ERRORES:
                            %s

                            No inventes evidencia.
                            Mantén estrictamente el contrato AgentResult.
                            Los cálculos deben ser matemáticamente consistentes.
                            """.formatted(validationFeedback);
                }

                log.info(
                        "TASK {} - agent {} inference attempt={}",
                        taskId,
                        agentId,
                        attempt + 1
                );

                if (attempt > 0) {

                    events.publishTask(
                            "EMPRESA_TASK_RETRY",
                            taskId,
                            missionId,
                            agentId,
                            "RETRYING",
                            "Reintentando resultado del agente. Intento "
                                    + (attempt + 1)
                                    + " de "
                                    + (MAX_RESULT_RETRIES + 1)
                    );
                }

                try {

                    var outcome =
                            ceoService.executeAgentTask(
                                    agentId,
                                    prompt,
                                    taskSummary,
                                    missionId,
                                    taskId
                            );

                    result = outcome.result();
                    confirmedEvidenceUrls.addAll(outcome.confirmedEvidenceUrls());

                } catch (Exception ex) {

                    var reason =
                            safeMessage(
                                    ex,
                                    "El agente no devolvió una respuesta procesable."
                            );

                    if (attempt == MAX_RESULT_RETRIES) {

                        // Último intento: sí queremos el stack trace completo,
                        // esto ya no se va a reintentar.
                        log.warn(
                                "TASK {} - agent {} inference failed attempt={} reason={}",
                                taskId,
                                agentId,
                                attempt + 1,
                                reason,
                                ex
                        );

                        throw new IllegalStateException(
                                "El agente " + agentId
                                        + " no produjo un resultado "
                                        + "procesable después de "
                                        + (MAX_RESULT_RETRIES + 1)
                                        + " intentos: "
                                        + reason,
                                ex
                        );
                    }

                    // Intentos intermedios: solo el mensaje, sin stack trace
                    // completo — es una condición esperada que se reintenta,
                    // no un fallo final; el stack trace aquí solo genera
                    // ruido en los logs.
                    log.warn(
                            "TASK {} - agent {} inference failed attempt={} "
                                    + "reason={} (se reintentará)",
                            taskId,
                            agentId,
                            attempt + 1,
                            reason
                    );

                    validationFeedback = reason;
                    continue;
                }

                var validation =
                        validator.validate(result);

                if (validation.valid()) {

                    var binding =
                            evidenceBindingGate.check(
                                    confirmedEvidenceUrls,
                                    result
                            );

                    if (binding.bound()) {

                        log.info(
                                "TASK {} - agent {} validation passed attempt={}",
                                taskId,
                                agentId,
                                attempt + 1
                        );

                        break;
                    }

                    log.warn(
                            "TASK {} - agent {} evidence binding rejected attempt={} errors={}",
                            taskId,
                            agentId,
                            attempt + 1,
                            binding.errors()
                    );

                    validationFeedback =
                            String.join(
                                    "\n- ",
                                    binding.errors()
                            );

                    if (attempt == MAX_RESULT_RETRIES) {

                        throw new IllegalStateException(
                                "El agente " + agentId
                                        + " buscó evidencia real pero no la "
                                        + "citó en su resultado después de "
                                        + (MAX_RESULT_RETRIES + 1)
                                        + " intentos: "
                                        + validationFeedback
                        );
                    }

                    continue;
                }

                log.warn(
                        "TASK {} - agent {} validation rejected attempt={} errors={}",
                        taskId,
                        agentId,
                        attempt + 1,
                        validation.errors()
                );

                validationFeedback =
                        String.join(
                                "\n- ",
                                validation.errors()
                        );

                if (attempt == MAX_RESULT_RETRIES) {

                    throw new IllegalStateException(
                            "Resultado de agente inválido después de "
                                    + (MAX_RESULT_RETRIES + 1)
                                    + " intentos: "
                                    + validationFeedback
                    );
                }
            }

            var evidenceValidation =
                    evidenceGate.validate(result);

            if (!evidenceValidation.valid()) {

                var message =
                        "Evidencia inválida: "
                                + String.join(
                                "; ",
                                evidenceValidation.errors()
                        );

                log.warn(
                        "TASK {} - agent {} rejected by evidence gate: {}",
                        taskId,
                        agentId,
                        evidenceValidation.errors()
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

            memory.recordEvidence(
                    taskId,
                    missionId,
                    agentId,
                    result.evidence()
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

    /**
     * Versión corta de la tarea, sin el contrato JSON completo — se usa
     * solo para el turno de decisión de herramienta en
     * {@code CeoService.executeAgentTask}. Verificado en vivo: cuando se
     * le pide al modelo decidir sobre la herramienta *dentro* del mismo
     * prompt que ya trae el "FORMATO OBLIGATORIO" del AgentResult, el
     * modelo ignora la herramienta casi siempre (incluso con
     * `tool_choice: "required"`) y llena directamente la plantilla JSON
     * — la instrucción de la herramienta pierde contra un ejemplo JSON
     * concreto y explícito. Con un prompt corto y dedicado, sin esa
     * plantilla compitiendo, el modelo pide la herramienta de forma
     * consistente.
     */
    private String buildTaskSummary(
            String action,
            String instruction) {

        return """
                ACCIÓN: %s

                MISIÓN:
                %s
                """.formatted(
                action,
                instruction
        );
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

                Si en el turno anterior pediste evidencia con
                search_web_evidence, ya la tienes disponible más abajo en
                la conversación: incorpórala en evidence/facts/
                evidenceRequired según corresponda, citando la URL real
                que te devolvió (nunca una URL inventada).

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
