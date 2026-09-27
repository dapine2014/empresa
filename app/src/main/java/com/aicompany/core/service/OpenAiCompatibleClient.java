package com.aicompany.core.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cliente de un endpoint compatible con OpenAI (/v1/chat/completions), hoy la API alojada de NVIDIA
 * (integrate.api.nvidia.com). Lo usa solo CeoService para los agentes cuyo Agent.model lleva el prefijo
 * "nvidia:" (decisión del fundador, 2026-09-26: Engineering con moonshotai/kimi-k3 tras 18 misiones con
 * qwen3:8b sin build verde). Verificado en vivo: el modo json_schema de esa API trunca los strings largos,
 * así que se usa json_object y la estructura la valida Java como siempre; el "thinking" se apaga.
 */
public class OpenAiCompatibleClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleClient.class);
    static final int MAX_ATTEMPTS = 3;

    private final RestClient client;
    private final String apiKey;
    private final Duration retryDelay;

    public OpenAiCompatibleClient(RestClient client, String apiKey, Duration retryDelay) {
        this.client = client;
        this.apiKey = apiKey;
        this.retryDelay = retryDelay;
    }

    @SuppressWarnings("unchecked")
    public String chat(String model, List<Map<String, Object>> messages, boolean json, int maxTokens) {

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Falta NVIDIA_API_KEY para usar el modelo remoto " + model + ".");
        }

        var body = new LinkedHashMap<String, Object>();
        body.put("model", model);
        body.put("messages", messages.stream()
                .map(m -> Map.of("role", m.get("role"), "content", String.valueOf(m.get("content"))))
                .toList());
        body.put("max_tokens", maxTokens);
        body.put("temperature", 0.2);
        body.put("chat_template_kwargs", Map.of("thinking", false, "enable_thinking", false));
        if (json) {
            body.put("response_format", Map.of("type", "json_object"));
        }

        RuntimeException last = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                var response = client.post().uri("/chat/completions")
                        .header("Authorization", "Bearer " + apiKey)
                        .body(body)
                        .retrieve()
                        .body(Map.class);
                var choices = response == null ? null : (List<Map<String, Object>>) response.get("choices");
                if (choices == null || choices.isEmpty()) {
                    throw new IllegalStateException("Respuesta sin choices del modelo remoto " + model + ".");
                }
                var message = (Map<String, Object>) choices.get(0).get("message");
                var content = message == null ? null : message.get("content");
                return content == null ? "" : String.valueOf(content);
            } catch (RestClientResponseException ex) {
                var status = ex.getStatusCode().value();
                last = new IllegalStateException("Modelo remoto " + model + " respondió HTTP " + status + ": "
                        + ex.getResponseBodyAsString().lines().findFirst().orElse(""), ex);
                if (status != 429 && status < 500) {
                    throw last;
                }
                log.warn("REMOTE_MODEL_RETRY model={} attempt={} status={}", model, attempt, status);
                sleep();
            }
        }

        throw last;
    }

    private void sleep() {
        if (retryDelay.isZero()) {
            return;
        }
        try {
            Thread.sleep(retryDelay.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
