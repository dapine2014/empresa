package com.aicompany.core.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class OpenAiCompatibleClientTest {

    private static final String OK = """
            {"choices":[{"message":{"role":"assistant","content":"{\\"summary\\":\\"s\\",\\"files\\":[]}"},
            "finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5}}""";

    private static final List<Map<String, Object>> MESSAGES = List.of(
            Map.of("role", "system", "content", "sys"), Map.of("role", "user", "content", "hola"));

    // Verificado en vivo con integrate.api.nvidia.com: json_schema trunca los strings largos (código cortado);
    // json_object + validación en Java funciona. El "thinking" por defecto se mezcla en content: se apaga.
    @Test
    void sendsJsonObjectModeWithThinkingOffAndTheBearerKey() {
        var builder = RestClient.builder().baseUrl("https://api.test/v1");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.test/v1/chat/completions"))
                .andExpect(header("Authorization", "Bearer k3y"))
                .andExpect(jsonPath("$.model").value("moonshotai/kimi-k3"))
                .andExpect(jsonPath("$.response_format.type").value("json_object"))
                .andExpect(jsonPath("$.chat_template_kwargs.thinking").value(false))
                .andExpect(jsonPath("$.max_tokens").value(16384))
                .andExpect(jsonPath("$.messages[1].content").value("hola"))
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        var client = new OpenAiCompatibleClient(builder.build(), "k3y", Duration.ZERO);

        assertEquals("{\"summary\":\"s\",\"files\":[]}", client.chat("moonshotai/kimi-k3", MESSAGES, true, 16384));
        server.verify();
    }

    @Test
    void rateLimitsAndServerErrorsAreRetried() {
        var builder = RestClient.builder().baseUrl("https://api.test/v1");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.test/v1/chat/completions")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo("https://api.test/v1/chat/completions")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(requestTo("https://api.test/v1/chat/completions")).andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        var client = new OpenAiCompatibleClient(builder.build(), "k3y", Duration.ZERO);

        assertTrue(client.chat("m", MESSAGES, true, 100).contains("summary"));
        server.verify();
    }

    @Test
    void aMissingKeyFailsWithAClearMessage() {
        var client = new OpenAiCompatibleClient(RestClient.builder().baseUrl("https://api.test/v1").build(), "", Duration.ZERO);
        var ex = assertThrows(IllegalStateException.class, () -> client.chat("m", MESSAGES, true, 100));
        assertTrue(ex.getMessage().contains("NVIDIA_API_KEY"), ex.getMessage());
    }

    @Test
    void aClientErrorIsNotRetried() {
        var builder = RestClient.builder().baseUrl("https://api.test/v1");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(times(1), requestTo("https://api.test/v1/chat/completions")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        var client = new OpenAiCompatibleClient(builder.build(), "k3y", Duration.ZERO);

        var ex = assertThrows(IllegalStateException.class, () -> client.chat("m", MESSAGES, true, 100));
        // Un 4xx es un error del pedido, no del modelo: nunca cuenta como caída (spec salud de modelos).
        assertFalse(ex instanceof RemoteUnavailableException, ex.toString());
        server.verify();
    }

    // Verificado en vivo (MISSION-SANDBOX-VERIFY-19): la API a veces responde 200 sin choices (intermitente: el
    // intento siguiente funcionó). Se reintenta como un 5xx y el error final muestra qué devolvió.
    @Test
    void aResponseWithoutChoicesIsRetriedAndReported() {
        var builder = RestClient.builder().baseUrl("https://api.test/v1");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.test/v1/chat/completions"))
                .andRespond(withSuccess("{\"error\":{\"message\":\"upstream timeout\"}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.test/v1/chat/completions")).andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        var client = new OpenAiCompatibleClient(builder.build(), "k3y", Duration.ZERO);

        assertTrue(client.chat("m", MESSAGES, true, 100).contains("summary"));
        server.verify();
    }

    @Test
    void persistentMissingChoicesFailWithTheResponseExcerpt() {
        var builder = RestClient.builder().baseUrl("https://api.test/v1");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(times(OpenAiCompatibleClient.MAX_ATTEMPTS), requestTo("https://api.test/v1/chat/completions"))
                .andRespond(withSuccess("{\"error\":{\"message\":\"upstream timeout\"}}", MediaType.APPLICATION_JSON));

        var client = new OpenAiCompatibleClient(builder.build(), "k3y", Duration.ZERO);

        var ex = assertThrows(RemoteUnavailableException.class, () -> client.chat("m", MESSAGES, true, 100));
        assertTrue(ex.getMessage().contains("upstream timeout"), ex.getMessage());
    }

    // Spec salud de modelos (2026-09-28): verificado en vivo con kimi-k3 caído en NVIDIA (504, sin respuesta, cuerpo
    // application/octet-stream). Esos fallos son de disponibilidad y se tipan para que ModelHealthService los cuente.
    @Test
    void persistentServerErrorsAreAnAvailabilityFailure() {
        var builder = RestClient.builder().baseUrl("https://api.test/v1");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(times(OpenAiCompatibleClient.MAX_ATTEMPTS), requestTo("https://api.test/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT));

        var client = new OpenAiCompatibleClient(builder.build(), "k3y", Duration.ZERO);

        var ex = assertThrows(RemoteUnavailableException.class, () -> client.chat("m", MESSAGES, true, 100));
        assertTrue(ex.getMessage().contains("504"), ex.getMessage());
    }

    @Test
    void aTimeoutIsAnAvailabilityFailure() {
        var builder = RestClient.builder().baseUrl("https://api.test/v1");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.test/v1/chat/completions"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withException(new java.net.SocketTimeoutException("Read timed out")));

        var client = new OpenAiCompatibleClient(builder.build(), "k3y", Duration.ZERO);

        assertThrows(RemoteUnavailableException.class, () -> client.chat("m", MESSAGES, true, 100));
    }

    @Test
    void anUnreadableBodyIsAnAvailabilityFailure() {
        var builder = RestClient.builder().baseUrl("https://api.test/v1");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.test/v1/chat/completions"))
                .andRespond(withSuccess("<html>gateway</html>", MediaType.APPLICATION_OCTET_STREAM));

        var client = new OpenAiCompatibleClient(builder.build(), "k3y", Duration.ZERO);

        assertThrows(RemoteUnavailableException.class, () -> client.chat("m", MESSAGES, true, 100));
    }

    @Test
    void pingIsTrueOnlyWhenTheModelAnswers() {
        var builder = RestClient.builder().baseUrl("https://api.test/v1");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.test/v1/chat/completions"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.max_tokens").value(10))
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.test/v1/chat/completions")).andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT));
        server.expect(requestTo("https://api.test/v1/chat/completions"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withException(new java.net.SocketTimeoutException("Read timed out")));

        var client = new OpenAiCompatibleClient(builder.build(), "k3y", Duration.ZERO);

        assertTrue(client.ping("m"));
        assertFalse(client.ping("m"));
        assertFalse(client.ping("m"));
    }

    private static final String TOOL_CALLS = """
            {"choices":[{"message":{"content":"","tool_calls":[
              {"id":"call-1","type":"function","function":{"name":"search_web_evidence","arguments":"{\\"query\\":\\"x\\"}"}},
              {"id":"call-2","type":"function","function":{"name":"search_web_evidence","arguments":"%s"}}]}}]}""";

    // Verificado en vivo (2026-09-27): los 4 candidatos piden herramientas en formato OpenAI (arguments como texto).
    @Test
    void toolsAreSentAndToolCallsComeBackInOllamaFormat() {
        var builder = RestClient.builder().baseUrl("https://api.test/v1");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.test/v1/chat/completions"))
                .andExpect(jsonPath("$.tools[0].function.name").value("search_web_evidence"))
                .andExpect(jsonPath("$.response_format").doesNotExist())
                .andRespond(withSuccess(TOOL_CALLS.formatted("{\\\"query\\\":\\\"y\\\"}"), MediaType.APPLICATION_JSON));

        var reply = new OpenAiCompatibleClient(builder.build(), "k", Duration.ZERO).complete("m", MESSAGES,
                List.of(Map.of("type", "function", "function", Map.of("name", "search_web_evidence"))), false, 4096);

        assertEquals(2, reply.toolCalls().size());
        assertEquals(Map.of("name", "search_web_evidence", "arguments", Map.of("query", "x")),
                reply.toolCalls().get(0).get("function"));
    }

    // Review Focus: arguments que no son JSON → esa llamada se descarta, sin excepción.
    @Test
    void toolCallsWithInvalidArgumentsAreDropped() {
        var builder = RestClient.builder().baseUrl("https://api.test/v1");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.test/v1/chat/completions"))
                .andRespond(withSuccess(TOOL_CALLS.formatted("no-json"), MediaType.APPLICATION_JSON));

        var reply = new OpenAiCompatibleClient(builder.build(), "k", Duration.ZERO).complete("m", MESSAGES,
                List.of(Map.of("type", "function", "function", Map.of("name", "search_web_evidence"))), false, 4096);

        assertEquals(1, reply.toolCalls().size());
    }

    // El historial con tool_calls de Ollama se traduce: id + arguments como texto + tool_call_id en el mensaje tool.
    @Test
    void anOllamaToolRoundTripIsTranslated() {
        var builder = RestClient.builder().baseUrl("https://api.test/v1");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.test/v1/chat/completions"))
                .andExpect(jsonPath("$.messages[2].tool_calls[0].id").value("call_0"))
                .andExpect(jsonPath("$.messages[2].tool_calls[0].type").value("function"))
                .andExpect(jsonPath("$.messages[2].tool_calls[0].function.arguments").value("{\"query\":\"x\"}"))
                .andExpect(jsonPath("$.messages[3].role").value("tool"))
                .andExpect(jsonPath("$.messages[3].tool_call_id").value("call_0"))
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        var history = List.<Map<String, Object>>of(
                Map.of("role", "system", "content", "sys"),
                Map.of("role", "user", "content", "hola"),
                Map.of("role", "assistant", "content", "", "tool_calls", List.of(Map.of("function",
                        Map.of("name", "search_web_evidence", "arguments", Map.of("query", "x"))))),
                Map.of("role", "tool", "content", "resultado"));

        new OpenAiCompatibleClient(builder.build(), "k", Duration.ZERO).complete("m", history, null, true, 100);
        server.verify();
    }

    // Keys editables desde Settings (2026-09-29): la key cambia en caliente y una candidata se prueba antes de guardarla.
    @Test
    void aNewKeyIsUsedByTheNextCallWithoutRestarting() {
        var builder = RestClient.builder().baseUrl("https://api.test/v1");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.test/v1/chat/completions")).andExpect(header("Authorization", "Bearer nueva"))
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));
        var client = new OpenAiCompatibleClient(builder.build(), "vieja", Duration.ZERO);

        client.setApiKey("nueva");
        client.chat("m", MESSAGES, true, 100);

        server.verify();
        assertEquals("…ueva", client.keyHint());
    }

    @Test
    void aCandidateKeyIsCheckedWithItsOwnBearer() {
        var builder = RestClient.builder().baseUrl("https://api.test/v1");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.test/v1/chat/completions")).andExpect(header("Authorization", "Bearer candidata"))
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.test/v1/chat/completions")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo("https://api.test/v1/chat/completions")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        var client = new OpenAiCompatibleClient(builder.build(), "vigente", Duration.ZERO);

        assertEquals(OpenAiCompatibleClient.KeyCheck.OK, client.checkKey("m", "candidata"));
        assertEquals(OpenAiCompatibleClient.KeyCheck.UNAUTHORIZED, client.checkKey("m", "mala"));
        assertEquals(OpenAiCompatibleClient.KeyCheck.UNAVAILABLE, client.checkKey("m", "otra"));
        assertEquals("…ente", client.keyHint());
    }
}
