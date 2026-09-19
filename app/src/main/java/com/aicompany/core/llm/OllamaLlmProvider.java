package com.aicompany.core.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Envuelve exactamente la llamada RestClient a Ollama (`POST /api/chat`)
 * que antes vivía en {@code CeoService.callModel}, acotada a las llamadas
 * del CEO: nunca pasa `format` (exclusivo del `AgentResult` de los
 * agentes) ni `think` (exclusivo del turno de decisión de herramienta de
 * los agentes) — el CEO nunca usó ninguno de los dos. `tools` sí se
 * soporta: {@code CeoService.chat} lo usa para `query_company_memory`.
 */
public class OllamaLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaLlmProvider.class);

    private final RestClient ollama;
    private final String model;

    public OllamaLlmProvider(RestClient ollama, String model) {
        this.ollama = ollama;
        this.model = model;
    }

    @Override
    public LlmResponse chat(
            String operation,
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools) {

        var body = new LinkedHashMap<String, Object>();
        body.put("model", model);
        body.put("stream", false);
        body.put("messages", messages);

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

            log.error("OLLAMA_ERROR operation={} model={}", operation, model, ex);
            throw ex;
        }

        return parseResponse(response);
    }

    /**
     * Sin modificador de acceso a propósito: permite que
     * {@code OllamaLlmProviderTest} verifique el parseo sin RestClient
     * real, mismo patrón que {@code SerperSearchAdapter.parseResults}.
     */
    @SuppressWarnings("unchecked")
    LlmResponse parseResponse(Map<String, Object> response) {

        if (response == null) {
            return new LlmResponse("Sin respuesta del modelo.", List.of());
        }

        var msg = (Map<String, Object>) response.get("message");

        if (msg == null) {
            return new LlmResponse("Sin respuesta del modelo.", List.of());
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

        return new LlmResponse(content, toolCalls);
    }
}
