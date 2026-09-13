package com.aicompany.core.service;

import com.aicompany.core.agent.model.AgentResult;
import com.aicompany.core.agent.model.AgentResultSchema;
import com.aicompany.core.evidence.EvidenceAcquisitionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class CeoService {

    private static final Logger log =
            LoggerFactory.getLogger(CeoService.class);

    /**
     * Definición de la única herramienta disponible hoy para las tareas de
     * agente. Formato "function calling" de Ollama (compatible con OpenAI).
     */
    private static final List<Map<String, Object>> AGENT_TOOLS = List.of(
            Map.of(
                    "type", "function",
                    "function", Map.of(
                            "name", "search_web_evidence",
                            "description",
                            "Busca fuentes web públicas y reales para "
                                    + "validar una afirmación empresarial. "
                                    + "Devuelve una lista de resultados con "
                                    + "título, URL y resumen. Nunca inventes "
                                    + "una URL: usa únicamente las que "
                                    + "devuelva esta herramienta.",
                            "parameters", Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "query", Map.of(
                                                    "type", "string",
                                                    "description",
                                                    "La consulta de "
                                                            + "búsqueda, en "
                                                            + "español."
                                            )
                                    ),
                                    "required", List.of("query")
                            )
                    )
            )
    );

    private final RestClient ollama;
    private final String ceoModel;
    private final String agentModel;
    private final JsonMapper jsonMapper;
    private final EvidenceAcquisitionService evidenceAcquisitionService;

    public CeoService(
            RestClient ollama,
            @Value("${ollama.ceo-model}") String ceoModel,
            @Value("${ollama.agent-model}") String agentModel,
            JsonMapper jsonMapper,
            EvidenceAcquisitionService evidenceAcquisitionService) {

        this.ollama = ollama;
        this.ceoModel = ceoModel;
        this.agentModel = agentModel;
        this.jsonMapper = jsonMapper;
        this.evidenceAcquisitionService = evidenceAcquisitionService;
    }

    public String chat(String message) {

        var messages = List.<Map<String, Object>>of(
                Map.of("role", "system", "content", systemPrompt()),
                Map.of("role", "user", "content", message)
        );

        return callModel(
                "CEO_CHAT", "ceo", ceoModel, messages, null, null
        ).content();
    }

    /**
     * Ejecuta la tarea de un agente en hasta dos turnos con Ollama:
     *
     * 1. Un turno con la herramienta {@code search_web_evidence} disponible
     *    (sin `format`, porque `format` y `tools` no pueden combinarse —
     *    verificado en vivo: con ambos presentes el modelo queda forzado a
     *    la gramática del schema y no puede pedir la herramienta). Si el
     *    modelo la pide, la ejecutamos de verdad contra
     *    {@link EvidenceAcquisitionService} y le devolvemos el resultado
     *    real como turno "tool".
     * 2. Un turno final con `format: AgentResultSchema.SCHEMA` (sin
     *    `tools`) para obligar al modelo a producir el `AgentResult`
     *    estructurado, ahora con la evidencia real (si la pidió) ya en el
     *    historial de la conversación.
     *
     * Nota sobre el modelo: qwen2.5-coder no envuelve su respuesta en
     * `&lt;tool_call&gt;...&lt;/tool_call&gt;` como espera la plantilla de
     * Ollama, así que `message.tool_calls` casi nunca viene poblado —
     * verificado en vivo. Por eso {@link #detectInlineToolCall} también
     * intenta reconocer un llamado a herramienta cuando viene como JSON
     * suelto en `message.content`.
     *
     * El turno de decisión usa un prompt **corto y separado**
     * ({@code taskSummary}), no el {@code prompt} completo con el
     * contrato JSON del `AgentResult`: verificado en vivo que cuando el
     * "FORMATO OBLIGATORIO" está presente en el mismo turno, el modelo lo
     * ignora casi siempre y llena directamente esa plantilla (incluso con
     * {@code tool_choice: "required"}) — la plantilla JSON concreta le
     * gana a la instrucción de usar la herramienta.
     */
    public AgentResult executeAgentTask(
            String agentId,
            String prompt,
            String taskSummary) {

        var system =
                systemPrompt()
                        + "\nTu rol específico en esta tarea es: "
                        + agentId
                        + ".";

        var toolDecisionMessages = List.<Map<String, Object>>of(
                Map.of(
                        "role", "system",
                        "content", toolDecisionSystemPrompt(agentId)
                ),
                Map.of("role", "user", "content", taskSummary)
        );

        var toolTurn =
                callModel(
                        "AGENT_TOOL_CALL",
                        agentId,
                        agentModel,
                        toolDecisionMessages,
                        null,
                        AGENT_TOOLS
                );

        var toolCall =
                !toolTurn.toolCalls().isEmpty()
                        ? parseStructuredToolCall(toolTurn.toolCalls().get(0))
                        : detectInlineToolCall(toolTurn.content());

        log.info(
                "AGENT_TOOL_TURN agent={} rawToolCalls={} content={} toolCallDetected={}",
                agentId,
                toolTurn.toolCalls(),
                toolTurn.content(),
                toolCall != null
        );

        var messages = new ArrayList<Map<String, Object>>();
        messages.add(Map.of("role", "system", "content", system));
        messages.add(Map.of("role", "user", "content", prompt));

        if (toolCall != null) {

            log.info(
                    "TASK agent={} requested tool={} query={}",
                    agentId,
                    toolCall.name(),
                    toolCall.query()
            );

            var toolResultJson = executeTool(agentId, toolCall);

            messages.add(Map.of(
                    "role", "assistant",
                    "content", "",
                    "tool_calls", List.of(Map.of(
                            "function", Map.of(
                                    "name", toolCall.name(),
                                    "arguments", Map.of(
                                            "query", toolCall.query()
                                    )
                            )
                    ))
            ));

            messages.add(Map.of(
                    "role", "tool",
                    "content", toolResultJson
            ));
        }

        var finalTurn =
                callModel(
                        "AGENT_TASK",
                        agentId,
                        agentModel,
                        messages,
                        AgentResultSchema.SCHEMA,
                        null
                );

        var response = finalTurn.content();

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

    /**
     * Ejecuta {@code search_web_evidence} de verdad. Si falla (sin API key,
     * error del proveedor, etc.), no tumba la tarea — le devuelve al modelo
     * un resultado de error para que pueda seguir (declarando
     * NOT_VALIDATED, por ejemplo) en vez de que la excepción se propague.
     */
    private String executeTool(
            String agentId,
            ToolCall toolCall) {

        try {

            var candidates =
                    evidenceAcquisitionService.searchEvidence(
                            toolCall.query()
                    );

            var simplified =
                    candidates.stream()
                            .map(candidate -> Map.of(
                                    "title",
                                    candidate.title() == null
                                            ? ""
                                            : candidate.title(),
                                    "url",
                                    candidate.url(),
                                    "snippet",
                                    candidate.snippet() == null
                                            ? ""
                                            : candidate.snippet()
                            ))
                            .toList();

            return jsonMapper.writeValueAsString(simplified);

        } catch (Exception ex) {

            log.warn(
                    "TOOL_CALL_FAILED agent={} tool={} query={} reason={}",
                    agentId,
                    toolCall.name(),
                    toolCall.query(),
                    ex.getMessage()
            );

            return jsonMapper.writeValueAsString(
                    Map.of(
                            "error",
                            "No se pudo completar la búsqueda: "
                                    + (ex.getMessage() == null
                                    ? "error desconocido"
                                    : ex.getMessage())
                    )
            );
        }
    }

    @SuppressWarnings("unchecked")
    private ToolCall parseStructuredToolCall(
            Map<String, Object> rawToolCall) {

        var function =
                (Map<String, Object>) rawToolCall.get("function");

        if (function == null) {
            return null;
        }

        var name = String.valueOf(function.get("name"));
        var arguments = function.get("arguments");

        String query = null;

        if (arguments instanceof Map<?, ?> argMap) {

            var value = argMap.get("query");

            query = value == null ? null : String.valueOf(value);
        }

        if (!"search_web_evidence".equals(name)
                || query == null
                || query.isBlank()) {
            return null;
        }

        return new ToolCall(name, query);
    }

    private ToolCall detectInlineToolCall(String content) {

        if (content == null || content.isBlank()) {
            return null;
        }

        try {

            var normalized = normalizeJsonResponse(content);
            var node = jsonMapper.readTree(normalized);

            if (!node.isObject()) {
                return null;
            }

            var name = node.path("name").asString(null);
            var argumentsNode = node.path("arguments");

            if (!"search_web_evidence".equals(name)
                    || !argumentsNode.isObject()) {
                return null;
            }

            var query = argumentsNode.path("query").asString(null);

            if (query == null || query.isBlank()) {
                return null;
            }

            return new ToolCall(name, query);

        } catch (Exception ex) {
            return null;
        }
    }

    private record ToolCall(String name, String query) {
    }

    /**
     * Prompt corto, deliberadamente sin el "FORMATO OBLIGATORIO" del
     * AgentResult (ver el javadoc de {@link #executeAgentTask}) — su único
     * propósito es que el modelo decida si necesita buscar evidencia
     * real antes de continuar.
     */
    private String toolDecisionSystemPrompt(String agentId) {

        return """
                Eres el agente %s de AI Company. Antes de hacer cualquier
                otra cosa, evalúa si la tarea que se te describe necesita
                evidencia real de mercado (precios, competencia, demanda,
                costos típicos) que no tengas todavía.

                Si la necesitas, responde ÚNICAMENTE con este JSON, sin
                ningún otro texto:
                {"name": "search_web_evidence", "arguments": {"query": "..."}}

                Si NO la necesitas, responde únicamente con la palabra:
                NINGUNA
                """.formatted(agentId);
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

        var messages = List.<Map<String, Object>>of(
                Map.of("role", "system", "content", systemPrompt()),
                Map.of("role", "user", "content", prompt)
        );

        return callModel(
                "MISSION_CONSOLIDATION", "ceo", ceoModel, messages, null, null
        ).content();
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
    private ModelMessage callModel(
            String operation,
            String actor,
            String model,
            List<Map<String, Object>> messages,
            Object format,
            List<Map<String, Object>> tools) {

        var startedAt = System.nanoTime();

        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("model", model);
        body.put("stream", false);
        body.put("messages", messages);

        if (format != null) {
            body.put("format", format);
        }

        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
        }

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

            return new ModelMessage("Sin respuesta del modelo.", List.of());
        }

        logMetrics(
                operation,
                actor,
                model,
                localDurationMs,
                response
        );

        var msg =
                (Map<String, Object>) response.get("message");

        if (msg == null) {
            return new ModelMessage("Sin respuesta del modelo.", List.of());
        }

        var content = String.valueOf(msg.get("content"));
        var rawToolCalls = msg.get("tool_calls");

        List<Map<String, Object>> toolCalls = new ArrayList<>();

        if (rawToolCalls instanceof List<?> list) {

            for (var item : list) {

                if (item instanceof Map<?, ?> map) {
                    toolCalls.add((Map<String, Object>) map);
                }
            }
        }

        return new ModelMessage(content, toolCalls);
    }

    private record ModelMessage(
            String content,
            List<Map<String, Object>> toolCalls
    ) {
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
