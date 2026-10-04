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
    private final RestClient probeClient;
    /** Keys editables desde Settings (2026-09-29): cambia en caliente, la siguiente llamada ya usa la nueva. */
    private volatile String apiKey;
    private final Duration retryDelay;
    /**
     * Verificado en vivo (MISSION-B1-INV-1791031663, 2026-10-03): una llamada quedó colgada 5 h 27 min; el read-timeout
     * es por inactividad y NVIDIA mantenía la conexión. Plazo total por llamada; al vencer, el modelo "no responde".
     */
    private final Duration totalTimeout;
    static final Duration DEFAULT_TOTAL_TIMEOUT = Duration.ofMinutes(12);
    private static final java.util.concurrent.ExecutorService CALLS =
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    private volatile String providerName = "nvidia";
    private volatile boolean nvidiaOptions = true;
    private volatile List<String> checkModels = List.of();
    private volatile String thinkingOffParam = "";

    /** Proveedores pagos (2026-10-04): nombre, si lleva las opciones de NVIDIA y con qué modelos se prueba una key. */
    public void configure(String providerName, boolean nvidiaOptions, List<String> checkModels) {
        configure(providerName, nvidiaOptions, checkModels, "");
    }

    /** {@code thinkingOffParam}: parámetro propio del proveedor que apaga el razonamiento (Alibaba: enable_thinking). */
    public void configure(String providerName, boolean nvidiaOptions, List<String> checkModels, String thinkingOffParam) {
        this.providerName = providerName;
        this.nvidiaOptions = nvidiaOptions;
        this.checkModels = List.copyOf(checkModels);
        this.thinkingOffParam = thinkingOffParam == null ? "" : thinkingOffParam;
    }

    public List<String> checkModels() {
        return checkModels;
    }

    public String providerName() {
        return providerName;
    }

    public OpenAiCompatibleClient(RestClient client, String apiKey, Duration retryDelay) {
        this(client, client, apiKey, retryDelay);
    }

    /** probeClient: mismo endpoint con timeout corto, solo para ping (spec salud de modelos 2026-09-28). */
    public OpenAiCompatibleClient(RestClient client, RestClient probeClient, String apiKey, Duration retryDelay) {
        this(client, probeClient, apiKey, retryDelay, DEFAULT_TOTAL_TIMEOUT);
    }

    public OpenAiCompatibleClient(RestClient client, RestClient probeClient, String apiKey, Duration retryDelay,
                                  Duration totalTimeout) {
        this.client = client;
        this.probeClient = probeClient;
        this.apiKey = apiKey;
        this.retryDelay = retryDelay;
        this.totalTimeout = totalTimeout;
    }

    /** La llamada en un hilo propio con plazo total; al vencer se cancela (el cliente HTTP del JDK aborta el envío). */
    @SuppressWarnings("unchecked")
    private Map<String, Object> postWithDeadline(String model, Map<String, Object> body) {
        var call = CALLS.submit(() -> (Map<String, Object>) client.post().uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .body(body)
                .retrieve()
                .body(Map.class));
        try {
            return call.get(totalTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException ex) {
            call.cancel(true);
            throw new RemoteUnavailableException("Modelo remoto " + model + " no respondió en "
                    + totalTimeout.toMinutes() + " min (plazo total por llamada).", ex);
        } catch (InterruptedException ex) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw new RemoteUnavailableException("Llamada a " + model + " interrumpida.", ex);
        } catch (java.util.concurrent.ExecutionException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new RemoteUnavailableException("Modelo remoto " + model + " no responde: " + ex.getCause(), ex);
        }
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    /** Pista para la pantalla: solo los últimos 4 caracteres, nunca la key. */
    public String keyHint() {
        var key = apiKey;
        return key == null || key.isBlank() ? "" : "…" + key.substring(Math.max(0, key.length() - 4));
    }

    public enum KeyCheck { OK, UNAUTHORIZED, UNAVAILABLE }

    private volatile String lastCheckError = "";

    /** Verificado en vivo (2026-10-04, Alibaba): el motivo del proveedor en la última prueba de key, sin la key. */
    public String lastCheckError() {
        return lastCheckError;
    }

    static String redactedReason(String reason, String key) {
        var text = key == null || key.isBlank() ? reason : reason.replace(key, "****");
        text = text.replaceAll("\\s+", " ").strip();
        return text.length() > 300 ? text.substring(0, 300) + "…" : text;
    }

    /** Prueba una key candidata con una llamada mínima, sin tocar la vigente; nunca lanza ni loguea la key. */
    public KeyCheck checkKey(String model, String candidate) {
        lastCheckError = "";
        try {
            var body = new LinkedHashMap<String, Object>();
            body.put("model", model);
            body.put("messages", List.of(Map.of("role", "user", "content", "Responde solo: ok")));
            body.put("max_tokens", 10);
            if (nvidiaOptions) {
                body.put("chat_template_kwargs", Map.of("thinking", false, "enable_thinking", false));
            }
            if (!thinkingOffParam.isBlank()) {
                body.put(thinkingOffParam, false);
            }
            probeClient.post().uri("/chat/completions").header("Authorization", "Bearer " + candidate)
                    .body(body).retrieve().body(Map.class);
            return KeyCheck.OK;
        } catch (org.springframework.web.client.HttpClientErrorException ex) {
            var status = ex.getStatusCode().value();
            lastCheckError = redactedReason("HTTP " + status + ": " + ex.getResponseBodyAsString(), candidate);
            return status == 401 || status == 403 ? KeyCheck.UNAUTHORIZED : KeyCheck.UNAVAILABLE;
        } catch (Exception ex) {
            lastCheckError = redactedReason(String.valueOf(ex.getMessage()), candidate);
            return KeyCheck.UNAVAILABLE;
        }
    }

    /** ¿El modelo responde? Llamada mínima (10 tokens); nunca lanza. */
    @SuppressWarnings("unchecked")
    public boolean ping(String model) {
        try {
            var body = new LinkedHashMap<String, Object>();
            body.put("model", model);
            body.put("messages", List.of(Map.of("role", "user", "content", "Responde solo: ok")));
            body.put("max_tokens", 10);
            if (nvidiaOptions) {
                body.put("chat_template_kwargs", Map.of("thinking", false, "enable_thinking", false));
            }
            if (!thinkingOffParam.isBlank()) {
                body.put(thinkingOffParam, false);
            }
            var response = probeClient.post().uri("/chat/completions").header("Authorization", "Bearer " + apiKey)
                    .body(body).retrieve().body(Map.class);
            var choices = response == null ? null : (List<Object>) response.get("choices");
            return choices != null && !choices.isEmpty();
        } catch (Exception ex) {
            return false;
        }
    }

    /** Respuesta remota: content y tool_calls ya en formato Ollama ({"function": {"name", "arguments": Map}}). */
    /** finishReason: "stop", "length" (cortada por el límite de salida), "tool_calls"… o null si no vino. */
    public record RemoteReply(String content, List<Map<String, Object>> toolCalls, String finishReason) {
        public RemoteReply(String content, List<Map<String, Object>> toolCalls) {
            this(content, toolCalls, null);
        }
    }

    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();

    public String chat(String model, List<Map<String, Object>> messages, boolean json, int maxTokens) {
        return complete(model, messages, null, json, maxTokens).content();
    }

    /**
     * Llamada con herramientas opcionales (spec 2026-09-27 §2): la definición de Ollama ya es la de OpenAI; el
     * historial con tool_calls de Ollama se traduce a OpenAI (id, type, arguments como texto, tool_call_id) y las
     * tool_calls de la respuesta vuelven en formato Ollama. Una llamada con arguments inválidos se descarta.
     */
    @SuppressWarnings("unchecked")
    public RemoteReply complete(String model, List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                                boolean json, int maxTokens) {

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Falta la key del proveedor " + providerName + " para usar el modelo remoto "
                    + model + " (cárgala en Settings → Keys de modelos).");
        }

        var body = new LinkedHashMap<String, Object>();
        body.put("model", model);
        body.put("messages", toOpenAi(messages));
        body.put("max_tokens", maxTokens);
        body.put("temperature", 0.2);
        if (nvidiaOptions) {
            body.put("chat_template_kwargs", Map.of("thinking", false, "enable_thinking", false));
        }
        if (!thinkingOffParam.isBlank()) {
            body.put(thinkingOffParam, false);
        }
        if (json) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
        }

        IllegalStateException last = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                var response = postWithDeadline(model, body);
                var choices = response == null ? null : (List<Map<String, Object>>) response.get("choices");
                if (choices == null || choices.isEmpty()) {
                    // Verificado en vivo (MISSION-SANDBOX-VERIFY-19): intermitente, el siguiente intento funcionó.
                    var excerpt = String.valueOf(response);
                    last = new RemoteUnavailableException("Respuesta sin choices del modelo remoto " + model + ": "
                            + excerpt.substring(0, Math.min(300, excerpt.length())), null);
                    log.warn("REMOTE_MODEL_RETRY model={} attempt={} reason=sin choices response={}", model, attempt,
                            excerpt.substring(0, Math.min(300, excerpt.length())));
                    sleep();
                    continue;
                }
                var message = (Map<String, Object>) choices.get(0).get("message");
                var content = message == null ? null : message.get("content");
                var rawCalls = message == null ? null : message.get("tool_calls");
                var finishReason = choices.get(0).get("finish_reason");
                return new RemoteReply(content == null ? "" : String.valueOf(content), toOllamaToolCalls(rawCalls),
                        finishReason == null ? null : String.valueOf(finishReason));
            } catch (RestClientResponseException ex) {
                var status = ex.getStatusCode().value();
                var detail = "Modelo remoto " + model + " respondió HTTP " + status + ": "
                        + ex.getResponseBodyAsString().lines().findFirst().orElse("");
                if (status != 429 && status < 500) {
                    throw new IllegalStateException(detail, ex);
                }
                last = new RemoteUnavailableException(detail, ex);
                log.warn("REMOTE_MODEL_RETRY model={} attempt={} status={}", model, attempt, status);
                sleep();
            } catch (org.springframework.web.client.RestClientException ex) {
                // Verificado en vivo (kimi-k3 caído, 2026-09-28): timeout y cuerpo ilegible (octet-stream). El tiempo ya
                // se gastó esperando: no se reintenta acá, lo cuenta ModelHealthService.
                throw new RemoteUnavailableException("Modelo remoto " + model + " no responde: " + ex.getMessage(), ex);
            }
        }

        throw last;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> toOpenAi(List<Map<String, Object>> messages) {
        var result = new java.util.ArrayList<Map<String, Object>>();
        var counter = 0;
        String lastCallId = null;
        for (var m : messages) {
            var role = String.valueOf(m.get("role"));
            var out = new LinkedHashMap<String, Object>();
            out.put("role", role);
            out.put("content", m.get("content") == null ? "" : String.valueOf(m.get("content")));
            if ("assistant".equals(role) && m.get("tool_calls") instanceof List<?> calls && !calls.isEmpty()) {
                var translated = new java.util.ArrayList<Map<String, Object>>();
                for (var call : calls) {
                    var function = (Map<String, Object>) ((Map<String, Object>) call).get("function");
                    lastCallId = "call_" + counter++;
                    var arguments = function.get("arguments");
                    translated.add(Map.of("id", lastCallId, "type", "function", "function", Map.of(
                            "name", String.valueOf(function.get("name")),
                            "arguments", arguments instanceof String text ? text : JSON.writeValueAsString(arguments))));
                }
                out.put("tool_calls", translated);
            }
            if ("tool".equals(role) && lastCallId != null) {
                out.put("tool_call_id", lastCallId);
            }
            result.add(out);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> toOllamaToolCalls(Object rawCalls) {
        if (!(rawCalls instanceof List<?> calls)) {
            return List.of();
        }
        var result = new java.util.ArrayList<Map<String, Object>>();
        for (var call : calls) {
            if (!(call instanceof Map<?, ?> map) || !(map.get("function") instanceof Map<?, ?> function)) {
                continue;
            }
            try {
                var arguments = function.get("arguments");
                Map<String, Object> parsed = arguments instanceof Map<?, ?> already
                        ? (Map<String, Object>) already
                        : JSON.readValue(String.valueOf(arguments), Map.class);
                result.add(Map.of("function", Map.of("name", String.valueOf(function.get("name")), "arguments", parsed)));
            } catch (Exception ex) {
                log.warn("REMOTE_TOOL_CALL_DROPPED reason=arguments no son JSON: {}", ex.getMessage());
            }
        }
        return result;
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
