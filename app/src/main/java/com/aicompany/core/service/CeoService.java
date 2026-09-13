package com.aicompany.core.service;

import com.aicompany.core.agent.model.AgentResult;
import com.aicompany.core.agent.model.AgentResultSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

@Service
public class CeoService {

    private static final Logger log =
            LoggerFactory.getLogger(CeoService.class);

    private final RestClient ollama;
    private final String ceoModel;
    private final String agentModel;
    private final JsonMapper jsonMapper;

    public CeoService(
            RestClient ollama,
            @Value("${ollama.ceo-model}") String ceoModel,
            @Value("${ollama.agent-model}") String agentModel,
            JsonMapper jsonMapper) {

        this.ollama = ollama;
        this.ceoModel = ceoModel;
        this.agentModel = agentModel;
        this.jsonMapper = jsonMapper;
    }

    public String chat(String message) {

        return callModel(
                "CEO_CHAT",
                "ceo",
                ceoModel,
                systemPrompt(),
                message
        );
    }

    public AgentResult executeAgentTask(
            String agentId,
            String prompt) {

        var system =
                systemPrompt()
                        + "\nTu rol específico en esta tarea es: "
                        + agentId
                        + ".";

        var response = callModel(
                "AGENT_TASK",
                agentId,
                agentModel,
                system,
                prompt
        );

        /*try {

            var normalizedResponse = normalizeJsonResponse(response);

         return jsonMapper.readValue(
         normalizedResponse,
         AgentResult.class
        );

        } catch (Exception ex) {

            //log.error(
              //      "AGENT_RESULT_PARSE_ERROR agent={} model={} response={}",
              //      agentId,
              //      agentModel,
              //      response,
              //      ex
            //);
            log.error(
        "AGENT_RESULT_PARSE_ERROR agent={} model={} responseLength={}",
        agentId,
        agentModel,
        response == null ? 0 : response.length(),
        ex
);

log.error(
        "AGENT_RESULT_RAW agent={} response={}",
        agentId,
        response
);

            throw new IllegalStateException(
                    "El agente "
                            + agentId
                            + " no devolvió un AgentResult JSON válido.",
                    ex
            );
        }*/
        
        try {

    var normalizedResponse =
            normalizeJsonResponse(response);

    log.info(
            "AGENT_RESULT_RAW agent={} response={}",
            agentId,
            response
    );

    log.info(
            "AGENT_RESULT_NORMALIZED agent={} response={}",
            agentId,
            normalizedResponse
    );

    var result =
            jsonMapper.readValue(
                    normalizedResponse,
                    AgentResult.class
            );

    log.info(
            "AGENT_RESULT_PARSED agent={} verificationStatus={} confidence={}",
            agentId,
            result.verificationStatus(),
            result.confidence()
    );

    return result;

} catch (Exception ex) {

    log.error(
            "AGENT_RESULT_PARSE_ERROR agent={} model={} reason={}",
            agentId,
            agentModel,
            ex.getMessage(),
            ex
    );

    throw new IllegalStateException(
            "El agente "
                    + agentId
                    + " no devolvió un AgentResult JSON válido.",
            ex
    );
}
    }

    public String executeMission(
            String instruction,
            String agentResults) {

        var prompt = """
                Actúa como CEO de AI Company.
                Consolida los resultados de los agentes y determina el siguiente paso.
                No conviertas hipótesis en hechos.
                Si no existe evidencia real de mercado,
                declara que la misión todavía no está validada.

                INSTRUCCIÓN:
                %s

                RESULTADOS DE AGENTES:
                %s
                """.formatted(
                instruction,
                agentResults
        );

        return callModel(
                "MISSION_CONSOLIDATION",
                "ceo",
                ceoModel,
                systemPrompt(),
                prompt
        );
    }

    private String systemPrompt() {

        return """
                Eres el CEO de AI Company,
                una empresa real operada principalmente por agentes de IA.

                Capital semilla inicial: US$50.
                Horizonte: 60 días.

                La empresa utiliza IA para operar y crear negocios;
                no vende la plataforma de IA como producto.

                Debes buscar valor económico real,
                exigir evidencia y conservar iniciativa estratégica.

                El inversionista puede aprobar, rechazar
                o proponer una alternativa.

                No inventes clientes, ventas, ingresos,
                búsquedas o evidencia.

                Diferencia siempre entre:
                - hecho
                - hipótesis
                - estimación
                - evidencia
                - resultado verificado

                Responde en español.
                """;
    }
    private String normalizeJsonResponse(String response) {

    if (response == null) {
        throw new IllegalStateException(
                "Respuesta del modelo null."
        );
    }

    var normalized = response.trim();

    /*
     * Caso:
     *
     * ```json
     * { ... }
     * ```
     */
    if (normalized.startsWith("```json")) {

        normalized = normalized.substring(
                "```json".length()
        ).trim();

        if (normalized.endsWith("```")) {
            normalized = normalized.substring(
                    0,
                    normalized.length() - 3
            ).trim();
        }
    }

    /*
     * Caso:
     *
     * ```
     * { ... }
     * ```
     */
    else if (normalized.startsWith("```")) {

        normalized = normalized.substring(
                3
        ).trim();

        if (normalized.endsWith("```")) {
            normalized = normalized.substring(
                    0,
                    normalized.length() - 3
            ).trim();
        }
    }

    /*
     * Si el modelo agregó texto antes/después,
     * intentamos recuperar exclusivamente el objeto JSON.
     */
    int start = normalized.indexOf('{');
    int end = normalized.lastIndexOf('}');

    if (start >= 0 && end > start) {

        normalized =
                normalized.substring(
                        start,
                        end + 1
                );
    }

    return normalized;
}

    @SuppressWarnings("unchecked")
    private String callModel(
            String operation,
            String actor,
            String model,
            String system,
            String message) {

        var startedAt = System.nanoTime();

        var body = "AGENT_TASK".equals(operation)
        ? Map.of(
                "model", model,
                "stream", false,
                "format", AgentResultSchema.SCHEMA,
                "messages", new Object[]{
                        Map.of(
                                "role",
                                "system",
                                "content",
                                system
                        ),
                        Map.of(
                                "role",
                                "user",
                                "content",
                                message
                        )
                }
        )
        : Map.of(
                "model", model,
                "stream", false,
                "messages", new Object[]{
                        Map.of(
                                "role",
                                "system",
                                "content",
                                system
                        ),
                        Map.of(
                                "role",
                                "user",
                                "content",
                                message
                        )
                }
        );

        Map<String, Object> response;

        try {

            response = ollama
                    .post()
                    .uri("/api/chat")
                    .body(body)
                    .retrieve()
                    .body(Map.class);

        } catch (Exception ex) {

            var durationMs =
                    (System.nanoTime() - startedAt)
                            / 1_000_000;

            log.error(
                    "OLLAMA_ERROR operation={} actor={} model={} durationMs={}",
                    operation,
                    actor,
                    model,
                    durationMs,
                    ex
            );

            throw ex;
        }

        var localDurationMs =
                (System.nanoTime() - startedAt)
                        / 1_000_000;

        if (response == null) {

            log.warn(
                    "OLLAMA_EMPTY_RESPONSE operation={} actor={} model={} durationMs={}",
                    operation,
                    actor,
                    model,
                    localDurationMs
            );

            return "Sin respuesta del modelo.";
        }

        logMetrics(
                operation,
                actor,
                model,
                localDurationMs,
                response
        );

        var msg =
                (Map<?, ?>) response.get("message");

        return msg == null
                ? "Sin respuesta del modelo."
                : String.valueOf(
                msg.get("content")
        );
    }

    private void logMetrics(
            String operation,
            String actor,
            String model,
            long localDurationMs,
            Map<String, Object> response) {

        long totalDurationMs =
                nanosToMillis(
                        response.get("total_duration")
                );

        long loadDurationMs =
                nanosToMillis(
                        response.get("load_duration")
                );

        long promptEvalDurationMs =
                nanosToMillis(
                        response.get("prompt_eval_duration")
                );

        long evalDurationMs =
                nanosToMillis(
                        response.get("eval_duration")
                );

        int promptTokens =
                intValue(
                        response.get("prompt_eval_count")
                );

        int outputTokens =
                intValue(
                        response.get("eval_count")
                );

        double tokensPerSecond =
                evalDurationMs > 0
                        ? (outputTokens * 1000.0)
                        / evalDurationMs
                        : 0.0;

        log.info(
                "OLLAMA_METRICS operation={} actor={} model={} " +
                "durationMs={} ollamaDurationMs={} loadMs={} " +
                "promptTokens={} outputTokens={} promptEvalMs={} " +
                "outputEvalMs={} tokensPerSec={}",
                operation,
                actor,
                model,
                localDurationMs,
                totalDurationMs,
                loadDurationMs,
                promptTokens,
                outputTokens,
                promptEvalDurationMs,
                evalDurationMs,
                String.format(
                        "%.2f",
                        tokensPerSecond
                )
        );
    }

    private long nanosToMillis(Object value) {

        if (!(value instanceof Number number)) {
            return 0L;
        }

        return number.longValue()
                / 1_000_000L;
    }

    private int intValue(Object value) {

        if (!(value instanceof Number number)) {
            return 0;
        }

        return number.intValue();
    }
}
