package com.aicompany.core.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cliente OpenAI-compatible de NVIDIA NIM
 * (`https://integrate.api.nvidia.com/v1/chat/completions`), usado solo
 * para el CEO (ver {@link LlmProvider}). Dos diferencias de formato
 * respecto a Ollama que este cliente normaliza para que el resto de
 * {@code CeoService} no necesite saber qué proveedor respondió:
 *
 * <ol>
 *   <li>La respuesta trae {@code function.arguments} como un string JSON
 *       serializado, no un objeto ya decodificado (como sí hace Ollama)
 *       — se decodifica acá antes de construir el {@link LlmResponse}
 *       ({@link #parseResponse}).</li>
 *   <li>En sentido inverso, un mensaje "assistant" de `tool_calls` que
 *       {@code CeoService} vuelve a mandar en el segundo turno (con
 *       `arguments` como {@code Map}, formato que sí acepta Ollama)
 *       necesita re-serializarse a string JSON antes de mandarse a NVIDIA
 *       ({@link #normalizeOutgoingMessages}) — si no, la API lo rechaza.</li>
 * </ol>
 *
 * Mismo criterio que {@code SerperSearchAdapter}: construye su propio
 * {@code RestClient} internamente (no inyectado) y valida la API key al
 * momento de la llamada, no al construirse — así el bean existe siempre,
 * sin condicionales de arranque, y solo falla si de verdad se intenta
 * usar sin key configurada.
 */
public class NvidiaNimLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(NvidiaNimLlmProvider.class);

    private final RestClient client;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final JsonMapper jsonMapper;

    public NvidiaNimLlmProvider(
            String baseUrl,
            String apiKey,
            String model,
            int maxTokens,
            JsonMapper jsonMapper) {

        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .build();

        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public LlmResponse chat(
            String operation,
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools) {

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("NVIDIA_API_KEY no está configurada");
        }

        try {

            var body = new LinkedHashMap<String, Object>();
            body.put("model", model);
            body.put("stream", false);
            body.put("max_tokens", maxTokens);
            body.put("messages", normalizeOutgoingMessages(messages));

            if (tools != null && !tools.isEmpty()) {
                body.put("tools", tools);
            }

            var raw = client
                    .post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            return parseResponse(raw);

        } catch (Exception ex) {

            log.error("NVIDIA_ERROR operation={} model={}", operation, model, ex);
            throw ex;
        }
    }

    /**
     * Sin modificador de acceso a propósito: permite que
     * {@code NvidiaNimLlmProviderTest} verifique la normalización sin
     * red real.
     */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> normalizeOutgoingMessages(List<Map<String, Object>> messages) {

        var normalized = new ArrayList<Map<String, Object>>();

        // Ver `docs/superpowers/plans/2026-09-18-nvidia-ceo-provider.md`
        // (hallazgo 1+9+10): la API OpenAI-compatible de NVIDIA exige que
        // todo `tool_calls[]` traiga `id`/`type`, y que el `role:"tool"`
        // que le sigue traiga el `tool_call_id` correspondiente —
        // `CeoService.chat()` construye ambos mensajes sin ninguno de los
        // dos (Ollama no lo exige). Esta app solo manda un tool_call por
        // turno (confirmado en `CeoService.chat`/`executeAgentTask`), así
        // que una sola variable de "id pendiente" alcanza para propagarlo
        // del mensaje "assistant" al siguiente mensaje "tool".
        String pendingToolCallId = null;

        for (var message : messages) {

            var rawToolCalls = message.get("tool_calls");

            if (!(rawToolCalls instanceof List<?> toolCallsList) || toolCallsList.isEmpty()) {

                if ("tool".equals(message.get("role"))
                        && pendingToolCallId != null
                        && !message.containsKey("tool_call_id")) {

                    var normalizedMessage = new LinkedHashMap<>(message);
                    normalizedMessage.put("tool_call_id", pendingToolCallId);
                    normalized.add(normalizedMessage);

                } else {
                    normalized.add(message);
                }

                // Nunca propagar un id viejo más allá del mensaje
                // inmediatamente siguiente al "assistant" que lo generó.
                pendingToolCallId = null;
                continue;
            }

            var normalizedToolCalls = new ArrayList<Map<String, Object>>();
            String firstToolCallId = null;

            for (int i = 0; i < toolCallsList.size(); i++) {

                var item = toolCallsList.get(i);

                if (!(item instanceof Map<?, ?> toolCall)) {
                    continue;
                }

                var normalizedToolCall = new LinkedHashMap<String, Object>((Map<String, Object>) toolCall);

                var existingId = normalizedToolCall.get("id");
                var toolCallId = existingId instanceof String s && !s.isBlank()
                        ? s
                        : "call_" + i;

                normalizedToolCall.putIfAbsent("id", toolCallId);
                normalizedToolCall.putIfAbsent("type", "function");

                if (firstToolCallId == null) {
                    firstToolCallId = String.valueOf(normalizedToolCall.get("id"));
                }

                var function = (Map<String, Object>) toolCall.get("function");
                var arguments = function == null ? null : function.get("arguments");

                if (arguments instanceof Map<?, ?>) {

                    var normalizedFunction = new LinkedHashMap<>(function);
                    normalizedFunction.put("arguments", jsonMapper.writeValueAsString(arguments));
                    normalizedToolCall.put("function", normalizedFunction);
                }

                normalizedToolCalls.add(normalizedToolCall);
            }

            var normalizedMessage = new LinkedHashMap<>(message);
            normalizedMessage.put("tool_calls", normalizedToolCalls);
            normalized.add(normalizedMessage);

            pendingToolCallId = firstToolCallId;
        }

        return normalized;
    }

    /**
     * Sin modificador de acceso a propósito: permite que
     * {@code NvidiaNimLlmProviderTest} verifique el parseo con fixtures
     * JSON, sin red real (mismo patrón que
     * {@code SerperSearchAdapter.parseResults}).
     *
     * Parsea la forma OpenAI-compatible de NVIDIA NIM:
     * {@code choices[0].message.content} (puede venir JSON `null` — el
     * truncamiento real observado en el spike con `gpt-oss-20b` y
     * `max_tokens` insuficiente, ver el spec) y
     * {@code choices[0].message.tool_calls[].function.arguments} como
     * string JSON serializado, decodificado acá a `Map`.
     */
    @SuppressWarnings("unchecked")
    LlmResponse parseResponse(String raw) {

        var root = jsonMapper.readTree(raw);
        var choices = root.path("choices");

        if (!choices.isArray() || choices.isEmpty()) {
            return new LlmResponse(null, List.of());
        }

        var message = choices.get(0).path("message");
        var contentNode = message.path("content");
        var content = contentNode.isNull() ? null : contentNode.asString(null);

        var toolCallsNode = message.path("tool_calls");
        var toolCalls = new ArrayList<Map<String, Object>>();

        if (toolCallsNode.isArray()) {

            for (var toolCallNode : toolCallsNode) {

                var toolCallId = toolCallNode.path("id").asString(null);
                var typeNode = toolCallNode.path("type");
                var toolCallType = typeNode.isMissingNode() || typeNode.isNull()
                        ? "function"
                        : typeNode.asString("function");

                var functionNode = toolCallNode.path("function");
                var name = functionNode.path("name").asString(null);
                var argumentsRaw = functionNode.path("arguments").asString(null);

                Map<String, Object> arguments;

                try {
                    arguments = argumentsRaw == null
                            ? Map.of()
                            : jsonMapper.readValue(argumentsRaw, Map.class);
                } catch (Exception ex) {
                    arguments = Map.of();
                }

                var toolCall = new LinkedHashMap<String, Object>();
                toolCall.put("id", toolCallId);
                toolCall.put("type", toolCallType);
                toolCall.put("function", Map.of(
                        "name", name == null ? "" : name,
                        "arguments", arguments
                ));

                toolCalls.add(toolCall);
            }
        }

        return new LlmResponse(content, toolCalls);
    }
}
